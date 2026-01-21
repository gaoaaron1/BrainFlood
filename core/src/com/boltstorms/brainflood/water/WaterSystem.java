package com.boltstorms.brainflood.water;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.boltstorms.brainflood.level.Level;

import java.util.ArrayDeque;

public class WaterSystem {

    private final Level level;

    private final int mapW, mapH, tileW, tileH;

    // water state
    private float[][] water;      // [y][x] 0..1
    private float[][] downFlux;   // [y][x] amount moved down this frame (visual)

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

    public WaterSystem(Level level, int inletTx, int inletTy, int outletTx, int outletTy) {
        this.level = level;
        this.mapW = level.mapW();
        this.mapH = level.mapH();
        this.tileW = level.tileW();
        this.tileH = level.tileH();

        this.inletTx = inletTx;
        this.inletTy = inletTy;
        this.outletTx = outletTx;
        this.outletTy = outletTy;

        water = new float[mapH][mapW];
        downFlux = new float[mapH][mapW];

        computeOutsideMask();
        computeReachableFromInlet();

        inletPxFixed.set(level.tileCenterPx(this.inletTx, this.inletTy));
        outletPxFixed.set(level.tileCenterPx(this.outletTx, this.outletTy));

        fallYPx = inletPxFixed.y;
        fallVY = 0f;
        impactYPx = computeStreamImpactYPx();
        waterStarted = false;
    }

    public void onLevelChanged() {
        computeOutsideMask();
        computeReachableFromInlet();
        impactYPx = computeStreamImpactYPx();
    }

    public boolean isWaterStarted() { return waterStarted; }

    public float getWaterTime() { return waterTime; }

    public float getLocalSurfacePx(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return 0f;
        float tileBottomPx = ty * tileH;
        return tileBottomPx + water[ty][tx] * tileH;
    }

    public boolean isInWaterRegion(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return false;
        if (reachable == null) return false;
        return reachable[ty][tx] && !outside[ty][tx] && !level.isWall(tx, ty);
    }

    public void update(float dt) {
        waterTime += dt;

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
    }

    public void render(ShapeRenderer shapes) {
        // 1) inlet falling ribbon
        renderInletStream(shapes);

        if (!waterStarted) return;

        // 2) water body
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

        // 3) surface highlights (skip waterfall tiles)
        shapes.setColor(0.75f, 0.92f, 1.0f, 0.55f);
        for (int x = 0; x < mapW; x++) {
            for (int y = 0; y < mapH; y++) {
                float w = water[y][x];
                if (w <= 0.01f) continue;

                if (downFlux[y][x] > surfaceSkipFlux) continue;

                boolean aboveEmpty =
                        (y == mapH - 1) ||
                                water[y + 1][x] <= 0.01f ||
                                level.isWall(x, y + 1);

                if (!aboveEmpty) continue;

                float tileBottom = y * tileH;
                float surfaceY = tileBottom + w * tileH;
                float wave = MathUtils.sin((x * 0.8f) + waterTime * 3f) * 2.5f;

                shapes.rect(x * tileW, surfaceY - 3f + wave, tileW, 3f);
            }
        }

        // 4) segmented waterfall ribbons
        renderWaterfalls(shapes);
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

    private void renderWaterfalls(ShapeRenderer shapes) {
        for (int x = 0; x < mapW; x++) {
            int y = 0;
            while (y < mapH) {
                while (y < mapH && downFlux[y][x] <= waterfallFluxThreshold) y++;
                if (y >= mapH) break;

                int startY = y;
                float maxFlux = downFlux[y][x];

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

    private void addWaterAtInlet(float dt) {
        if (inletTx < 0 || inletTx >= mapW || inletTy < 0 || inletTy >= mapH) return;
        if (level.isWall(inletTx, inletTy)) return;
        if (!reachable[inletTy][inletTx]) return;
        if (outside[inletTy][inletTx]) return;

        water[inletTy][inletTx] = Math.min(1f, water[inletTy][inletTx] + sourceTilesPerSec * dt);
    }

    private void stepWater(float dt) {
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                if (level.isWall(x, y)) { water[y][x] = 0f; continue; }
                if (!reachable[y][x]) { water[y][x] = 0f; continue; }
                if (outside[y][x]) { water[y][x] = 0f; continue; }

                float w = water[y][x];
                if (w <= 0f) continue;

                // down
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

    private boolean canHoldWater(int x, int y) {
        if (x < 0 || x >= mapW || y < 0 || y >= mapH) return false;
        if (level.isWall(x, y)) return false;
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
                if (level.isWall(x, y)) continue;
                if (!reachable[y][x]) continue;
                if (outside[y][x]) continue;

                float w = water[y][x];
                if (w <= 0f) continue;

                boolean nearOutside = false;
                for (int i = 0; i < 4; i++) {
                    int nx = x + dx[i];
                    int ny = y + dy[i];
                    if (nx < 0 || nx >= mapW || ny < 0 || ny >= mapH) continue;
                    if (outside[ny][nx] && !level.isWall(nx, ny)) {
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

    private void computeOutsideMask() {
        outside = new boolean[mapH][mapW];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        for (int x = 0; x < mapW; x++) {
            if (level.isOpen(x, 0)) { outside[0][x] = true; q.add(new int[]{x, 0}); }
            if (level.isOpen(x, mapH - 1)) { outside[mapH - 1][x] = true; q.add(new int[]{x, mapH - 1}); }
        }
        for (int y = 0; y < mapH; y++) {
            if (level.isOpen(0, y)) { outside[y][0] = true; q.add(new int[]{0, y}); }
            if (level.isOpen(mapW - 1, y)) { outside[y][mapW - 1] = true; q.add(new int[]{mapW - 1, y}); }
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
                if (!level.isOpen(nx, ny)) continue;
                if (outside[ny][nx]) continue;

                outside[ny][nx] = true;
                q.addLast(new int[]{nx, ny});
            }
        }
    }

    private void computeReachableFromInlet() {
        reachable = new boolean[mapH][mapW];

        if (level.isWall(inletTx, inletTy) || outside[inletTy][inletTx]) {
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
                if (level.isWall(nx, ny)) continue;

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
                    if (!level.isWall(x, y) && !outside[y][x]) {
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
            if (level.isWall(x, y)) {
                float impact = (y + 1) * tileH;
                return Math.min(impact, inletPxFixed.y - 1f);
            }
        }
        return 0f;
    }
}
