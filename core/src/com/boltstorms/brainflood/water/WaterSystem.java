package com.boltstorms.brainflood.water;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.boltstorms.brainflood.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class WaterSystem {

    public interface SolidQuery {
        boolean isSolid(int tx, int ty);
    }

    private final Level level;
    private final SolidQuery solidQuery;

    private final int mapW, mapH, tileW, tileH;

    // water state
    private final float[][] water;      // [y][x] 0..1
    private final float[][] downFlux;   // [y][x] amount moved down this frame (visual)

    // masks
    private boolean[][] reachable;
    private boolean[][] outside;

    // inlet/outlet tiles
    private int inletTx, inletTy;
    private int outletTx, outletTy;

    // fixed centers
    private final Vector2 inletPxFixed = new Vector2();
    private final Vector2 outletPxFixed = new Vector2();

    // sim + visuals
    private float waterTime = 0f;

    public float sourceTilesPerSec = 2.2f;
    public float downRate = 10.0f;
    public float sideRate = 4.0f;
    public float leakDrainRate = 12.0f;
    public int flowIterations = 4;

    public float waterfallFluxThreshold = 0.02f;
    public float surfaceSkipFlux = 0.015f;

    // inlet falling-stream visual
    private float fallYPx;
    private float fallVY = 0f;
    public float fallGravityPx = -2600f;
    private float impactYPx;
    private boolean waterStarted = false;

    // ---- NEW: surface extraction (for smooth rendering) ----
    public static class SurfaceRun {
        public int y;              // tile row of the run
        public int x0, x1;          // inclusive x range
        public float[] surfaceYPx;  // per x in [x0..x1]
    }

    public WaterSystem(Level level,
                       int inletTx, int inletTy,
                       int outletTx, int outletTy,
                       SolidQuery solidQuery) {

        this.level = level;
        this.mapW = level.mapW();
        this.mapH = level.mapH();
        this.tileW = level.tileW();
        this.tileH = level.tileH();
        this.solidQuery = solidQuery;

        this.inletTx = inletTx;
        this.inletTy = inletTy;
        this.outletTx = outletTx;
        this.outletTy = outletTy;

        this.water = new float[mapH][mapW];
        this.downFlux = new float[mapH][mapW];

        computeOutsideMask();
        computeReachableFromInlet();

        inletPxFixed.set(level.tileCenterPx(this.inletTx, this.inletTy));
        outletPxFixed.set(level.tileCenterPx(this.outletTx, this.outletTy));

        fallYPx = inletPxFixed.y;
        fallVY = 0f;
        impactYPx = computeStreamImpactYPx();
        waterStarted = false;
    }

    // -------------------------
    // Solid/Open helpers
    // -------------------------
    private boolean isSolid(int x, int y) {
        return solidQuery != null && solidQuery.isSolid(x, y);
    }

    private boolean isOpen(int x, int y) {
        return !isSolid(x, y);
    }

    private boolean canHoldWater(int x, int y) {
        if (x < 0 || x >= mapW || y < 0 || y >= mapH) return false;
        if (isSolid(x, y)) return false;
        if (!reachable[y][x]) return false;
        if (outside[y][x]) return false;
        return true;
    }

    // IMPORTANT: if a tile becomes solid (vocab block), remove any stored water there
    private void purgeWaterInSolids() {
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (isSolid(x, y)) {
                    water[y][x] = 0f;
                    downFlux[y][x] = 0f;
                }
            }
        }
    }

    // -------------------------
    // Public API
    // -------------------------
    public void onLevelChanged() {
        computeOutsideMask();
        computeReachableFromInlet();
        impactYPx = computeStreamImpactYPx();
        purgeWaterInSolids();
    }

    public boolean isWaterStarted() { return waterStarted; }

    public float getWaterTime() { return waterTime; }

    public int mapW() { return mapW; }
    public int mapH() { return mapH; }
    public int tileW() { return tileW; }
    public int tileH() { return tileH; }

    public float getFill(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return 0f;
        if (!waterStarted) return 0f;
        return MathUtils.clamp(water[ty][tx], 0f, 1f);
    }

    public float getLocalSurfacePx(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return 0f;
        float tileBottomPx = ty * tileH;
        return tileBottomPx + water[ty][tx] * tileH;
    }

    public boolean isInWaterRegion(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return false;
        if (reachable == null) return false;
        return reachable[ty][tx] && !outside[ty][tx] && !isSolid(tx, ty);
    }

    public void update(float dt) {
        waterTime += dt;

        // falling inlet visual
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

        purgeWaterInSolids();

        // reset flux
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                downFlux[y][x] = 0f;
            }
        }

        addWaterAtInlet(dt);

        for (int i = 0; i < flowIterations; i++) {
            stepWater(dt / flowIterations);
        }

        drainOutside(dt);
        cleanupTinyWater();
    }

    // -------------------------
    // Rendering helpers (inlet)
    // -------------------------
    public void renderInletOnly(ShapeRenderer shapes) {
        renderInletStream(shapes);
    }

    private void renderInletStream(ShapeRenderer shapes) {
        float sx = inletPxFixed.x;
        float syTop = inletPxFixed.y;
        float syBot = waterStarted ? impactYPx : fallYPx;

        float ribbonW = 10f;
        int segs = 18;

        float bottomY = Math.min(syTop, syBot);
        float topY = Math.max(syTop, syBot);
        float height = topY - bottomY;
        if (height <= 0.5f) return;

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

    // -------------------------
    // NEW: Surface runs for smooth rendering
    // -------------------------
    private boolean isSurfaceTile(int x, int y) {
        float w = water[y][x];
        if (w <= 0.01f) return false;
        if (isSolid(x, y) || !reachable[y][x] || outside[y][x]) return false;

        // "surface" means top is exposed (either top of map, or above is empty-ish, or above is solid boundary)
        if (y == mapH - 1) return true;
        if (isSolid(x, y + 1)) return true;
        return water[y + 1][x] <= 0.01f;
    }

    /** Returns all contiguous horizontal surface runs (per row). */
    public List<SurfaceRun> getSurfaceRunsPx() {
        ArrayList<SurfaceRun> runs = new ArrayList<>();
        if (!waterStarted) return runs;

        // For each row, find surface tiles and merge contiguous x into runs.
        for (int y = 0; y < mapH; y++) {
            int x = 0;
            while (x < mapW) {
                while (x < mapW && !isSurfaceTile(x, y)) x++;
                if (x >= mapW) break;

                int start = x;
                int end = x;

                while (end + 1 < mapW && isSurfaceTile(end + 1, y)) end++;

                SurfaceRun r = new SurfaceRun();
                r.y = y;
                r.x0 = start;
                r.x1 = end;
                r.surfaceYPx = new float[end - start + 1];

                float tileBottom = y * tileH;
                for (int ix = start; ix <= end; ix++) {
                    float surf = tileBottom + MathUtils.clamp(water[y][ix], 0f, 1f) * tileH;
                    r.surfaceYPx[ix - start] = surf;
                }

                runs.add(r);
                x = end + 1;
            }
        }
        return runs;
    }

    // -------------------------
    // Water sim
    // -------------------------
    private void addWaterAtInlet(float dt) {
        if (inletTx < 0 || inletTx >= mapW || inletTy < 0 || inletTy >= mapH) return;
        if (isSolid(inletTx, inletTy)) return;
        if (!reachable[inletTy][inletTx]) return;
        if (outside[inletTy][inletTx]) return;

        water[inletTy][inletTx] = Math.min(1f, water[inletTy][inletTx] + sourceTilesPerSec * dt);
    }

    private void stepWater(float dt) {
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (isSolid(x, y)) { water[y][x] = 0f; continue; }
                if (!reachable[y][x]) { water[y][x] = 0f; continue; }
                if (outside[y][x]) { water[y][x] = 0f; continue; }

                float w = water[y][x];
                if (w <= 0f) continue;

                // down (y-1 is down because y=0 is bottom in this project)
                if (y > 0 && canHoldWater(x, y - 1)) {
                    float space = 1f - water[y - 1][x];
                    if (space > 0f) {
                        float move = Math.min(w, space);
                        move = Math.min(move, downRate * dt);

                        water[y][x] -= move;
                        water[y - 1][x] += move;

                        downFlux[y][x] += move;

                        w = water[y][x];
                        if (w <= 0f) continue;
                    }
                }

                // sideways
                flowSide(x, y, -1, dt);
                flowSide(x, y, +1, dt);
            }
        }
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
                if (isSolid(x, y)) continue;
                if (!reachable[y][x]) continue;
                if (outside[y][x]) continue;

                float w = water[y][x];
                if (w <= 0f) continue;

                boolean nearOutside = false;
                for (int i = 0; i < 4; i++) {
                    int nx = x + dx[i];
                    int ny = y + dy[i];
                    if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
                    if (outside[ny][nx] && isOpen(nx, ny)) {
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

    private void cleanupTinyWater() {
        // kill crumbs *only near leaks/outside* so you don't erase legit thin layers
        final float EPS = 0.06f;

        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                float w = water[y][x];
                if (w <= 0f) continue;

                if (w < EPS && isNearOutside(x, y)) {
                    water[y][x] = 0f;
                }
            }
        }
    }

    private boolean isNearOutside(int x, int y) {
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        for (int i = 0; i < 4; i++) {
            int nx = x + dx[i];
            int ny = y + dy[i];
            if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
            if (outside != null && outside[ny][nx] && isOpen(nx, ny)) return true;
        }
        return false;
    }

    // -------------------------
    // Masks
    // -------------------------
    private void computeOutsideMask() {
        outside = new boolean[mapH][mapW];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        // seed open border tiles
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

        // nudge inlet inward if invalid
        if (isSolid(inletTx, inletTy) || outside[inletTy][inletTx]) {
            int[] n = findNearestInterior(inletTx, inletTy);
            inletTx = n[0];
            inletTy = n[1];
            inletPxFixed.set(level.tileCenterPx(inletTx, inletTy));
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
                if (isSolid(nx, ny)) continue;

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
                    if (!isSolid(x, y) && !outside[y][x]) {
                        return new int[]{x, y};
                    }
                }
            }
        }
        return new int[]{sx, sy};
    }

    private float computeStreamImpactYPx() {
        int x = inletTx;
        for (int y = inletTy - 1; y >= 0; y--) {
            if (isSolid(x, y)) {
                float impact = (y + 1) * tileH;
                return Math.min(impact, inletPxFixed.y - 1f);
            }
        }
        return 0f;
    }
}
