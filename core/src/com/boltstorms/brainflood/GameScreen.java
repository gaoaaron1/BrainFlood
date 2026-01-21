package com.boltstorms.brainflood;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.MapObjects;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.ArrayDeque;

public class GameScreen implements Screen {

    private static final String MAP_PATH = "Stages/level_01.tmx";

    // Layer names (match Tiled)
    private static final String LAYER_BG = "background_layer";
    private static final String LAYER_FG = "foreground_layer";
    private static final String LAYER_FG_DECOR = "foreground_decor";
    private static final String LAYER_WALL = "wall";

    // Object layers (match Tiled)
    private static final String OBJ_PLAYER_SPAWN = "player_spawn";
    private static final String OBJ_INLET = "inlet";
    private static final String OBJ_OUTLET = "outlet";

    // Pixels-per-meter for Box2D conversions
    private static final float PPM = 32f;

    private TiledMap map;
    private OrthogonalTiledMapRenderer mapRenderer;

    private OrthographicCamera camPx; // camera in PIXELS
    private Viewport viewport;

    private ShapeRenderer shapes;

    private World world;
    private Box2DDebugRenderer debug;

    private Body player;

    private int tileW, tileH;
    private int mapW, mapH;
    private int mapWidthPx, mapHeightPx;

    private TiledMapTileLayer wallLayer;

    // Reachability mask from inlet (open tiles connected to inlet)
    private boolean[][] reachable;
    private boolean[][] outside;

    private int inletTx, inletTy;
    private int outletTx, outletTy;

    private Vector2 inletPxFixed = new Vector2();
    private Vector2 outletPxFixed = new Vector2();

    // --------- Water simulation (per-tile fill: 0..1) ----------
    private float[][] water;      // [y][x] 0..1
    private float[][] downFlux;   // how much moved DOWN through this cell this frame (visual)
    private float waterTime = 0f;

    // Water source / flow tuning
    private float sourceTilesPerSec = 2.2f; // tiles/sec injected at inlet
    private float downRate = 10.0f;         // max tiles/sec moving downward
    private float sideRate = 4.0f;          // max tiles/sec moving sideways
    private float leakDrainRate = 12.0f;    // tiles/sec drained if tile borders outside
    private int flowIterations = 4;         // more = smoother/faster propagation, more CPU

    // Waterfall visuals tuning
    private float waterfallFluxThreshold = 0.02f; // if downFlux > this => part of waterfall
    private float surfaceSkipFlux = 0.015f;       // if downFlux > this => skip surface highlight dashes

    // Falling water front (visual). Starts at inlet and falls with gravity.
    private float fallYPx;
    private float fallVY = 0f;              // px/s
    private float fallGravityPx = -2600f;   // px/s^2
    private float impactYPx;                // first solid tile below inlet (px)
    private boolean waterStarted = false;

    // Player size (meters)
    private static final float PLAYER_HALF_M = 0.35f;

    // Buoyancy
    private static final float BUOYANCY_STRENGTH = 25f;
    private static final float WATER_DRAG = 4f;

    @Override
    public void show() {
        map = new TmxMapLoader().load(MAP_PATH);
        mapRenderer = new OrthogonalTiledMapRenderer(map);
        shapes = new ShapeRenderer();

        wallLayer = (TiledMapTileLayer) map.getLayers().get(LAYER_WALL);
        if (wallLayer == null) throw new RuntimeException("Missing tile layer: " + LAYER_WALL);

        mapW = wallLayer.getWidth();
        mapH = wallLayer.getHeight();
        tileW = (int) wallLayer.getTileWidth();
        tileH = (int) wallLayer.getTileHeight();

        mapWidthPx = mapW * tileW;
        mapHeightPx = mapH * tileH;

        camPx = new OrthographicCamera();
        viewport = new FitViewport(mapWidthPx, mapHeightPx, camPx);
        viewport.apply();

        camPx.position.set(mapWidthPx / 2f, mapHeightPx / 2f, 0);
        camPx.update();

        world = new World(new Vector2(0, -18f), true);
        debug = new Box2DDebugRenderer();

        buildWallColliders(wallLayer);

        // Read object layers ONCE
        Vector2 spawnPx = getObjectCenterPx(OBJ_PLAYER_SPAWN);
        Vector2 inletPx = getObjectCenterPx(OBJ_INLET);
        Vector2 outletPx = getObjectCenterPx(OBJ_OUTLET);

        inletTx = pxToTileX(inletPx.x);
        inletTy = pxToTileY(inletPx.y);

        outletTx = pxToTileX(outletPx.x);
        outletTy = pxToTileY(outletPx.y);

        // Player
        Vector2 spawnM = pxToMeters(spawnPx);
        createPlayer(spawnM.x, spawnM.y);

        // Masks + reachability
        computeOutsideMask();
        computeReachableFromInlet();

        // lock inlet/outlet pixel positions to final tile coords
        inletPxFixed.set((inletTx + 0.5f) * tileW, (inletTy + 0.5f) * tileH);
        outletPxFixed.set((outletTx + 0.5f) * tileW, (outletTy + 0.5f) * tileH);

        // Water grids
        water = new float[mapH][mapW];
        downFlux = new float[mapH][mapW];

        // Stream init (visual)
        fallYPx = inletPxFixed.y;
        fallVY = 0f;
        impactYPx = computeStreamImpactYPx();
        waterStarted = false;

        Gdx.app.log("WATER", "inlet=(" + inletTx + "," + inletTy + ") impactYPx=" + impactYPx);
    }

    // ---------- Object reading ----------
    private Vector2 getObjectCenterPx(String objectLayerName) {
        MapLayer layer = map.getLayers().get(objectLayerName);
        if (layer == null) throw new RuntimeException("Missing object layer: " + objectLayerName);

        MapObjects objs = layer.getObjects();
        if (objs.getCount() == 0) throw new RuntimeException("No objects in layer: " + objectLayerName);

        MapObject obj = objs.get(0);
        Rectangle r = ((RectangleMapObject) obj).getRectangle();
        return new Vector2(r.x + r.width * 0.5f, r.y + r.height * 0.5f);
    }

    // ---------- Conversions ----------
    private Vector2 pxToMeters(Vector2 px) {
        return new Vector2(px.x / PPM, px.y / PPM);
    }

    private int pxToTileX(float px) {
        return MathUtils.clamp((int) (px / tileW), 0, mapW - 1);
    }

    private int pxToTileY(float py) {
        return MathUtils.clamp((int) (py / tileH), 0, mapH - 1);
    }

    // ---------- Walls ----------
    private void buildWallColliders(TiledMapTileLayer wallLayer) {
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                TiledMapTileLayer.Cell cell = wallLayer.getCell(x, y);
                if (cell == null || cell.getTile() == null) continue;

                float tilePxX = x * tileW;
                float tilePxY = y * tileH;

                float cxM = (tilePxX + tileW * 0.5f) / PPM;
                float cyM = (tilePxY + tileH * 0.5f) / PPM;
                float hxM = (tileW * 0.5f) / PPM;
                float hyM = (tileH * 0.5f) / PPM;

                BodyDef bd = new BodyDef();
                bd.type = BodyDef.BodyType.StaticBody;
                bd.position.set(cxM, cyM);

                Body b = world.createBody(bd);

                PolygonShape shape = new PolygonShape();
                shape.setAsBox(hxM, hyM);

                FixtureDef fd = new FixtureDef();
                fd.shape = shape;
                fd.friction = 0.2f;

                b.createFixture(fd);
                shape.dispose();
            }
        }
    }

    // ---------- Player ----------
    private void createPlayer(float xM, float yM) {
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.DynamicBody;
        bd.position.set(xM, yM);
        bd.fixedRotation = true;

        player = world.createBody(bd);

        PolygonShape box = new PolygonShape();
        box.setAsBox(PLAYER_HALF_M, PLAYER_HALF_M);

        FixtureDef fd = new FixtureDef();
        fd.shape = box;
        fd.density = 1f;
        fd.friction = 0.2f;
        fd.restitution = 0f;

        player.createFixture(fd);
        box.dispose();
    }

    private void handleControls(float dt) {
        float move = 0f;
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) move -= 1f;
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) move += 1f;

        float desiredVx = move * 5f;
        float vx = player.getLinearVelocity().x;
        float impulseX = (desiredVx - vx) * player.getMass();
        player.applyLinearImpulse(new Vector2(impulseX, 0), player.getWorldCenter(), true);

        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            player.applyLinearImpulse(new Vector2(0, 5f * player.getMass()), player.getWorldCenter(), true);
        }
    }

    // ---------- Reachability / Outside ----------
    private boolean isWall(int x, int y) {
        TiledMapTileLayer.Cell cell = wallLayer.getCell(x, y);
        return cell != null && cell.getTile() != null;
    }

    private boolean isOpen(int x, int y) {
        return !isWall(x, y);
    }

    private void computeOutsideMask() {
        outside = new boolean[mapH][mapW];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        // Seed BFS with all OPEN border tiles
        for (int x = 0; x < mapW; x++) {
            if (isOpen(x, 0)) { outside[0][x] = true; q.add(new int[]{x, 0}); }
            if (isOpen(x, mapH - 1)) { outside[mapH - 1][x] = true; q.add(new int[]{x, mapH - 1}); }
        }
        for (int y = 0; y < mapH; y++) {
            if (isOpen(0, y)) { outside[y][0] = true; q.add(new int[]{0, y}); }
            if (isOpen(mapW - 1, y)) { outside[y][mapW - 1] = true; q.add(new int[]{mapW - 1, y}); }
        }

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            int cx = cur[0], cy = cur[1];

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];
                if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
                if (!isOpen(nx, ny)) continue;
                if (outside[ny][nx]) continue;

                outside[ny][nx] = true;
                q.addLast(new int[]{nx, ny});
            }
        }
    }

    private void computeReachableFromInlet() {
        reachable = new boolean[mapH][mapW];

        // if inlet is in wall or outside, nudge it inward
        if (isWall(inletTx, inletTy) || outside[inletTy][inletTx]) {
            int[] n = findNearestInterior(inletTx, inletTy);
            inletTx = n[0];
            inletTy = n[1];
        }

        ArrayDeque<int[]> q = new ArrayDeque<>();
        reachable[inletTy][inletTx] = true;
        q.add(new int[]{inletTx, inletTy});

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            int cx = cur[0], cy = cur[1];

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];

                if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
                if (reachable[ny][nx]) continue;
                if (isWall(nx, ny)) continue;

                reachable[ny][nx] = true;
                q.addLast(new int[]{nx, ny});
            }
        }
    }

    private int[] findNearestInterior(int sx, int sy) {
        for (int r = 1; r < Math.max(mapW, mapH); r++) {
            for (int y = sy - r; y <= sy + r; y++) {
                for (int x = sx - r; x <= sx + r; x++) {
                    if (x < 0 || x >= mapW || y < 0 || y >= mapH) continue;
                    if (!isWall(x, y) && !outside[y][x]) {
                        return new int[]{x, y};
                    }
                }
            }
        }
        return new int[]{sx, sy};
    }

    // basin impact y (px) below inlet
    private float computeStreamImpactYPx() {
        int x = inletTx;
        for (int y = inletTy - 1; y >= 0; y--) {
            if (isWall(x, y)) {
                float impact = (y + 1) * tileH;
                return Math.min(impact, inletPxFixed.y - 1f);
            }
        }
        return 0f;
    }

    // ---------- Water simulation ----------
    private void updateWater(float dt) {
        // Stream "fall" visual until it hits the basin
        if (!waterStarted) {
            fallVY += fallGravityPx * dt;
            fallYPx += fallVY * dt;
            if (fallYPx <= impactYPx) {
                fallYPx = impactYPx;
                fallVY = 0f;
                waterStarted = true;
            }
            return;
        }

        // reset flux for this frame (for waterfall rendering)
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                downFlux[y][x] = 0f;
            }
        }

        // Add source water at inlet tile
        addWaterAtInlet(dt);

        // Simulate flow (a few iterations per frame helps it move through gaps smoothly)
        for (int i = 0; i < flowIterations; i++) {
            stepWater(dt / flowIterations);
        }

        // Drain any water that reaches outside-connected empty space
        drainOutside(dt);
    }

    private void addWaterAtInlet(float dt) {
        if (inletTx < 0 || inletTx >= mapW || inletTy < 0 || inletTy >= mapH) return;
        if (isWall(inletTx, inletTy)) return;
        if (!reachable[inletTy][inletTx]) return;
        if (outside[inletTy][inletTx]) return;

        water[inletTy][inletTx] = Math.min(1f, water[inletTy][inletTx] + sourceTilesPerSec * dt);
    }

    private void stepWater(float dt) {
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (isWall(x, y)) { water[y][x] = 0f; continue; }
                if (!reachable[y][x]) { water[y][x] = 0f; continue; }
                if (outside[y][x]) { water[y][x] = 0f; continue; }

                float w = water[y][x];
                if (w <= 0f) continue;

                // 1) Flow down fast
                if (y > 0 && canHoldWater(x, y - 1)) {
                    float space = 1f - water[y - 1][x];
                    if (space > 0f) {
                        float move = Math.min(w, space);
                        move = Math.min(move, downRate * dt);

                        water[y][x] -= move;
                        water[y - 1][x] += move;

                        // record down-flow (for waterfall visuals)
                        downFlux[y][x] += move;

                        w = water[y][x];
                        if (w <= 0f) continue;
                    }
                }

                // 2) Flow sideways slower (equalize)
                flowSide(x, y, -1, dt);
                flowSide(x, y, +1, dt);
            }
        }
    }

    private boolean canHoldWater(int x, int y) {
        if (x < 0 || x >= mapW || y < 0 || y >= mapH) return false;
        if (isWall(x, y)) return false;
        if (!reachable[y][x]) return false;
        if (outside[y][x]) return false;
        return true;
    }

    private void flowSide(int x, int y, int dir, float dt) {
        int nx = x + dir;
        if (!canHoldWater(nx, y)) return;

        float a = water[y][x];
        float b = water[y][nx];
        if (a <= 0f) return;

        float diff = a - b;
        if (diff <= 0.02f) return;

        float want = diff * 0.5f;
        float move = Math.min(want, sideRate * dt);
        move = Math.min(move, a);

        water[y][x] -= move;
        water[y][nx] += move;
    }

    private void drainOutside(float dt) {
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (isWall(x, y)) continue;
                if (!reachable[y][x]) continue;
                if (outside[y][x]) continue;

                float w = water[y][x];
                if (w <= 0f) continue;

                boolean nearOutside = false;
                for (int i = 0; i < 4; i++) {
                    int nx = x + dx[i];
                    int ny = y + dy[i];
                    if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
                    if (outside[ny][nx] && !isWall(nx, ny)) {
                        nearOutside = true;
                        break;
                    }
                }

                if (nearOutside) {
                    float drain = Math.min(w, leakDrainRate * dt);
                    water[y][x] -= drain;
                }
            }
        }
    }

    // ---------- Buoyancy ----------
    private void applyBuoyancy(float dt) {
        if (!waterStarted) return;

        Vector2 pM = player.getPosition(); // meters
        float px = pM.x * PPM;
        float py = pM.y * PPM;

        int tx = pxToTileX(px);
        int ty = pxToTileY(py);

        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return;
        if (reachable == null || !reachable[ty][tx]) return;
        if (outside[ty][tx]) return;

        float tileBottomPx = ty * tileH;
        float localSurfacePx = tileBottomPx + water[ty][tx] * tileH;
        float surfaceM = localSurfacePx / PPM;

        float bottomM = pM.y - PLAYER_HALF_M;
        float submerged = MathUtils.clamp(surfaceM - bottomM, 0f, PLAYER_HALF_M * 2f);
        float frac = submerged / (PLAYER_HALF_M * 2f);

        if (frac > 0f) {
            float forceY = BUOYANCY_STRENGTH * frac * player.getMass();
            player.applyForceToCenter(0, forceY, true);

            Vector2 v = player.getLinearVelocity();
            player.applyForceToCenter(-v.x * WATER_DRAG * player.getMass(),
                    -v.y * WATER_DRAG * player.getMass(),
                    true);
        }
    }

    // ---------- Game update ----------
    private void update(float dt) {
        waterTime += dt;

        handleControls(dt);
        updateWater(dt);
        applyBuoyancy(dt);

        world.step(1f / 60f, 6, 2);
    }

    // ---------- Rendering ----------
    private void renderWaterAndPlayer() {
        shapes.setProjectionMatrix(camPx.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // --- Falling stream ribbon (inlet) ---
        float sx = inletPxFixed.x;
        float syTop = inletPxFixed.y;
        float syBot = waterStarted ? impactYPx : fallYPx;

        float ribbonW = 10f;
        int segs = 18;

        float bottomY = Math.min(syTop, syBot);
        float topY = Math.max(syTop, syBot);
        float height = topY - bottomY;

        if (height > 0.5f) {
            for (int i = 0; i < segs; i++) {
                float a0 = i / (float) segs;
                float a1 = (i + 1) / (float) segs;

                float y0 = MathUtils.lerp(topY, bottomY, a0);
                float y1 = MathUtils.lerp(topY, bottomY, a1);

                float wob0 = MathUtils.sin(waterTime * 8f + a0 * 6f) * 2.5f;
                float wob1 = MathUtils.sin(waterTime * 8f + a1 * 6f) * 2.5f;

                float segBottom = Math.min(y0, y1);
                float segTop = Math.max(y0, y1);

                shapes.setColor(0.65f, 0.9f, 1.0f, 0.9f);
                shapes.rect(sx - ribbonW * 0.5f + wob0, segBottom, ribbonW, segTop - segBottom);

                shapes.setColor(0.78f, 0.95f, 1.0f, 0.55f);
                shapes.rect(sx - ribbonW * 0.25f + wob1, segBottom, ribbonW * 0.5f, segTop - segBottom);
            }
        }

        // --- Water tiles (per-tile fill) ---
        if (waterStarted) {
            // body fill
            for (int y = 0; y < mapH; y++) {
                float tileBottom = y * tileH;
                for (int x = 0; x < mapW; x++) {
                    float w = water[y][x];
                    if (w <= 0f) continue;

                    float fillH = w * tileH;
                    shapes.setColor(0.0f, 0.55f, 1.0f, 0.75f);
                    shapes.rect(x * tileW, tileBottom, tileW, fillH);
                }
            }

            // surface highlight ONLY on real surfaces (and NOT in waterfalls)
            shapes.setColor(0.75f, 0.92f, 1.0f, 0.55f);
            for (int x = 0; x < mapW; x++) {
                for (int y = 0; y < mapH; y++) {
                    float w = water[y][x];
                    if (w <= 0.01f) continue;

                    // skip tiles that are actively falling (prevents dashed artifacts)
                    if (downFlux[y][x] > surfaceSkipFlux) continue;

                    boolean aboveEmpty =
                            (y == mapH - 1) ||
                                    water[y + 1][x] <= 0.01f ||
                                    isWall(x, y + 1);

                    if (!aboveEmpty) continue;

                    float tileBottom = y * tileH;
                    float surfaceY = tileBottom + w * tileH;
                    float wave = MathUtils.sin((x * 0.8f) + waterTime * 3f) * 2.5f;

                    shapes.rect(x * tileW, surfaceY - 3f + wave, tileW, 3f);
                }
            }

            // SEGMENTED WATERFALL RIBBONS (match inlet style)
            for (int x = 0; x < mapW; x++) {
                int y = 0;
                while (y < mapH) {
                    // find start of a falling run
                    while (y < mapH && downFlux[y][x] <= waterfallFluxThreshold) y++;
                    if (y >= mapH) break;

                    int startY = y;
                    float maxFlux = downFlux[y][x];

                    // extend run while falling
                    while (y < mapH && downFlux[y][x] > waterfallFluxThreshold) {
                        maxFlux = Math.max(maxFlux, downFlux[y][x]);
                        y++;
                    }
                    int endY = y;

                    float runBottom = startY * tileH;
                    float runTop = endY * tileH;
                    float runH = runTop - runBottom;
                    if (runH <= 1f) continue;

                    float alpha = MathUtils.clamp(0.25f + maxFlux * 1.4f, 0.25f, 0.95f);
                    float baseW = MathUtils.clamp(8f + maxFlux * 20f, 8f, 18f);

                    int runSegs = MathUtils.clamp((int) (runH / 16f), 10, 40);
                    float centerX = (x + 0.5f) * tileW;

                    for (int i = 0; i < runSegs; i++) {
                        float a0 = i / (float) runSegs;
                        float a1 = (i + 1) / (float) runSegs;

                        float y0 = MathUtils.lerp(runTop, runBottom, a0);
                        float y1 = MathUtils.lerp(runTop, runBottom, a1);

                        float segBottom = Math.min(y0, y1);
                        float segTop = Math.max(y0, y1);

                        float wob0 = MathUtils.sin(waterTime * 8f + a0 * 6f + x * 0.7f) * 2.5f;
                        float wob1 = MathUtils.sin(waterTime * 11f + a0 * 9f + x * 0.4f) * 1.6f;
                        float wob = wob0 * 0.7f + wob1 * 0.3f;

                        // slight taper
                        float taper = 1f - 0.25f * a0;
                        float w = baseW * taper;

                        shapes.setColor(0.65f, 0.9f, 1.0f, alpha);
                        shapes.rect(centerX - w * 0.5f + wob, segBottom, w, segTop - segBottom);

                        shapes.setColor(0.78f, 0.95f, 1.0f, alpha * 0.6f);
                        shapes.rect(centerX - w * 0.25f + wob * 0.7f, segBottom, w * 0.5f, segTop - segBottom);
                    }
                }
            }
        }

        // Player
        shapes.setColor(0.1f, 0.85f, 0.2f, 1f);
        Vector2 pm = player.getPosition();
        float ppx = pm.x * PPM;
        float ppy = pm.y * PPM;
        float halfPx = PLAYER_HALF_M * PPM;
        shapes.rect(ppx - halfPx, ppy - halfPx, halfPx * 2f, halfPx * 2f);

        shapes.end();
    }

    @Override
    public void render(float delta) {
        float dt = Math.min(delta, 1f / 30f);
        update(dt);

        Gdx.gl.glClearColor(0.93f, 0.93f, 0.93f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camPx.update();
        mapRenderer.setView(camPx);

        renderLayerIfExists(LAYER_BG);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        renderWaterAndPlayer();

        renderLayerIfExists(LAYER_WALL);
        renderLayerIfExists(LAYER_FG);
        renderLayerIfExists(LAYER_FG_DECOR);

        // debug.render(world, camPx.combined);
    }

    private void renderLayerIfExists(String layerName) {
        MapLayer layer = map.getLayers().get(layerName);
        if (layer == null) return;
        mapRenderer.render(new int[]{map.getLayers().getIndex(layerName)});
    }

    @Override
    public void resize(int width, int height) {
        if (viewport != null) viewport.update(width, height, true);
    }

    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void hide() {}

    @Override
    public void dispose() {
        shapes.dispose();
        debug.dispose();
        world.dispose();
        mapRenderer.dispose();
        map.dispose();
    }
}
