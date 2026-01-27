package com.boltstorms.brainflood.water;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.boltstorms.brainflood.level.Level;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.ArrayDeque;

/**
 * Tile-based water simulation + smooth rendering (Marching Squares mesh).
 *
 * SIM stays tile-bucket (water[ty][tx] in 0..1).
 * RENDER becomes smooth via marching squares on a corner scalar field.
 */
public class WaterSystem {

    public interface SolidQuery {
        boolean isSolid(int tx, int ty);
    }

    private final Level level;
    private final SolidQuery solidQuery;

    private final int mapW, mapH, tileW, tileH;

    // water state
    private final float[][] water;      // [y][x] 0..1
    private final float[][] downFlux;   // [y][x] moved down this frame (waterfalls visual)

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

    // -------------------------
    // Smooth water rendering
    // -------------------------
    private static final float ISO = 0.06f;  // “water exists” threshold for the mesh
    private static final int MAX_TRIS_PER_CELL = 4; // safe upper bound

    private Mesh waterMesh;
    private ShaderProgram waterShader;

    // dynamic CPU buffers
    private float[] verts;   // x,y,r,g,b,a (6 floats)
    private short[] indices;

    private int vertCount;   // number of vertices currently in verts (not floats)
    private int indexCount;  // number of indices currently in indices

    // marching squares scalar field at corners (mapW+1 x mapH+1)
    private float[] cornerField;

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

        initWaterRenderer();
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

        // inlet falling visual
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

    // -------------------------
    // Rendering
    // -------------------------
    /**
     * Keep your old inlet stream / waterfalls using ShapeRenderer if you want.
     * Call this DURING your ShapeRenderer pass (before shapes.end()).
     */
    public void renderAdditives(ShapeRenderer shapes) {
        renderInletStream(shapes);
        if (!waterStarted) return;
        renderWaterfalls(shapes); // same as your old style
    }

    /**
     * Smooth water body render (mesh).
     * Call this AFTER shapes.end() (it does its own GL calls).
     */
    public void renderWater(Matrix4 proj) {
        if (!waterStarted) return;

        buildCornerField();
        buildWaterMesh();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        waterShader.bind();
        waterShader.setUniformMatrix("u_projTrans", proj);
        waterMesh.render(waterShader, GL20.GL_TRIANGLES);
    }

    public void dispose() {
        if (waterMesh != null) waterMesh.dispose();
        if (waterShader != null) waterShader.dispose();
    }

    // -------------------------
    // Smooth water renderer init
    // -------------------------
    private void initWaterRenderer() {
        String vert =
                "attribute vec2 a_position;\n" +
                        "attribute vec4 a_color;\n" +
                        "uniform mat4 u_projTrans;\n" +
                        "varying vec4 v_col;\n" +
                        "void main(){\n" +
                        "  v_col = a_color;\n" +
                        "  gl_Position = u_projTrans * vec4(a_position, 0.0, 1.0);\n" +
                        "}\n";

        String frag =
                "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
                        "varying vec4 v_col;\n" +
                        "void main(){\n" +
                        "  gl_FragColor = v_col;\n" +
                        "}\n";

        waterShader = new ShaderProgram(vert, frag);
        if (!waterShader.isCompiled()) {
            throw new RuntimeException("Water shader compile error:\n" + waterShader.getLog());
        }

        // Worst-case triangle count:
        // Each cell can create up to MAX_TRIS_PER_CELL triangles.
        int maxTris = mapW * mapH * MAX_TRIS_PER_CELL;

        // Each tri has 3 verts, but we index-share within a polygon; still allocate safely.
        // We'll allocate as if every tri adds 3 unique verts (safe).
        int maxVerts = maxTris * 3;

        verts = new float[maxVerts * 6];      // 6 floats per vertex
        indices = new short[maxTris * 3];     // 3 indices per tri

        waterMesh = new Mesh(true, maxVerts, indices.length,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color")
        );

        cornerField = new float[(mapW + 1) * (mapH + 1)];
    }

    // -------------------------
    // Marching Squares field
    // -------------------------
    private void buildCornerField() {
        // Corner scalar field, but computed in a way that DOESN'T “smear” through solids.
        // We weight the 4 touching tiles by closeness and ignore invalid tiles entirely.

        int w = mapW + 1;
        int h = mapH + 1;

        for (int cy = 0; cy < h; cy++) {
            for (int cx = 0; cx < w; cx++) {

                // Touching tiles around the corner:
                // (cx-1,cy-1) bottom-left, (cx,cy-1) bottom-right
                // (cx-1,cy)   top-left,    (cx,cy)   top-right
                float vBL = tileWaterSafe(cx - 1, cy - 1);
                float vBR = tileWaterSafe(cx,     cy - 1);
                float vTL = tileWaterSafe(cx - 1, cy);
                float vTR = tileWaterSafe(cx,     cy);

                // weights (closer tiles count a bit more)
                // if a tile is invalid, treat it as "not contributing"
                float sum = 0f;
                float wsum = 0f;

                if (vBL >= 0f) { sum += vBL * 1.0f; wsum += 1.0f; }
                if (vBR >= 0f) { sum += vBR * 1.0f; wsum += 1.0f; }
                if (vTL >= 0f) { sum += vTL * 1.0f; wsum += 1.0f; }
                if (vTR >= 0f) { sum += vTR * 1.0f; wsum += 1.0f; }

                float v;
                if (wsum <= 0f) v = 0f;
                else v = sum / wsum;

                cornerField[cy * w + cx] = v;
            }
        }
    }

    /**
     * Returns:
     *  - water amount 0..1 if this tile is valid water space
     *  - -1 if it should NOT contribute to the corner field (solid/outside/unreachable)
     */
    private float tileWaterSafe(int tx, int ty) {
        if (tx < 0 || tx >= mapW || ty < 0 || ty >= mapH) return -1f;
        if (isSolid(tx, ty)) return -1f;
        if (!reachable[ty][tx]) return -1f;
        if (outside[ty][tx]) return -1f;
        return water[ty][tx];
    }


    // -------------------------
    // Marching Squares mesh build
    // -------------------------
    private void buildWaterMesh() {
        vertCount = 0;
        indexCount = 0;

        int fw = mapW + 1;

        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {

                // corner values for this cell
                // corners: 0=bl,1=br,2=tr,3=tl
                float v0 = cornerField[(y)     * fw + (x)];
                float v1 = cornerField[(y)     * fw + (x + 1)];
                float v2 = cornerField[(y + 1) * fw + (x + 1)];
                float v3 = cornerField[(y + 1) * fw + (x)];

                int c0 = (v0 >= ISO) ? 1 : 0;
                int c1 = (v1 >= ISO) ? 1 : 0;
                int c2 = (v2 >= ISO) ? 1 : 0;
                int c3 = (v3 >= ISO) ? 1 : 0;

                int mask = (c0) | (c1 << 1) | (c2 << 2) | (c3 << 3);
                if (mask == 0) continue;
                if (mask == 15) {
                    // full cell filled: just add a quad (2 tris)
                    addQuadCell(x, y);
                    continue;
                }

                // edge intersection points (in cell-local 0..1)
                // edges: e0 bottom(0-1), e1 right(1-2), e2 top(2-3), e3 left(3-0)
                Vector2 e0 = edgePoint(0, v0, v1);
                Vector2 e1 = edgePoint(1, v1, v2);
                Vector2 e2 = edgePoint(2, v2, v3);
                Vector2 e3 = edgePoint(3, v3, v0);

                // Ambiguous cases (5 and 10): choose connection based on center value
// Ambiguous cases (5 and 10): use a stable decider (asymptotic-style)
// This prevents popping/glitches as values hover near ISO.
                if (mask == 5 || mask == 10) {

                    // Decider: compare products around ISO (more stable than averaging)
                    // If (v0-ISO)*(v2-ISO) > (v1-ISO)*(v3-ISO), connect one way, else the other.
                    float a = (v0 - ISO) * (v2 - ISO);
                    float b = (v1 - ISO) * (v3 - ISO);

                    boolean connectDiagonal02 = (a > b);

                    if (mask == 5) {
                        // corners inside: 0 and 2
                        if (connectDiagonal02) {
                            // connect 0-2 through center (diamond-ish)
                            addPoly(x, y, new Vector2[]{ e0, e1, e2, e3 });
                        } else {
                            // two separate triangles
                            addPoly(x, y, new Vector2[]{ corner(0), e0, e3 });
                            addPoly(x, y, new Vector2[]{ corner(2), e2, e1 });
                        }
                    } else { // mask == 10 (corners inside: 1 and 3)
                        if (!connectDiagonal02) {
                            // connect 1-3 through center
                            addPoly(x, y, new Vector2[]{ e0, e1, e2, e3 });
                        } else {
                            // two separate triangles
                            addPoly(x, y, new Vector2[]{ corner(1), e1, e0 });
                            addPoly(x, y, new Vector2[]{ corner(3), e3, e2 });
                        }
                    }
                    continue;
                }


                // Standard polygon per case (filled region inside)
                switch (mask) {
                    case 1:  addPoly(x,y, new Vector2[]{ corner(0), e0, e3 }); break;
                    case 2:  addPoly(x,y, new Vector2[]{ corner(1), e1, e0 }); break;
                    case 3:  addPoly(x,y, new Vector2[]{ corner(0), corner(1), e1, e3 }); break;
                    case 4:  addPoly(x,y, new Vector2[]{ corner(2), e2, e1 }); break;
                    case 6:  addPoly(x,y, new Vector2[]{ corner(1), corner(2), e2, e0 }); break;
                    case 7:  addPoly(x,y, new Vector2[]{ corner(0), corner(1), corner(2), e2, e3 }); break;
                    case 8:  addPoly(x,y, new Vector2[]{ corner(3), e3, e2 }); break;
                    case 9:  addPoly(x,y, new Vector2[]{ corner(0), e0, e2, corner(3) }); break;
                    case 11: addPoly(x,y, new Vector2[]{ corner(0), corner(1), e1, e2, corner(3) }); break;
                    case 12: addPoly(x,y, new Vector2[]{ e3, corner(3), corner(2), e1 }); break;
                    case 13: addPoly(x,y, new Vector2[]{ e0, corner(0), corner(3), corner(2), e1 }); break;
                    case 14: addPoly(x,y, new Vector2[]{ e3, e0, corner(1), corner(2), corner(3) }); break;
                    default:
                        // should not happen
                        break;
                }
            }
        }

        // upload to GPU
        waterMesh.setVertices(verts, 0, vertCount * 6);
        waterMesh.setIndices(indices, 0, indexCount);
    }

    // local corner positions in a cell (0..1)
    private Vector2 corner(int idx) {
        switch (idx) {
            case 0: return new Vector2(0f, 0f); // bl
            case 1: return new Vector2(1f, 0f); // br
            case 2: return new Vector2(1f, 1f); // tr
            case 3: return new Vector2(0f, 1f); // tl
            default: return new Vector2(0f, 0f);
        }
    }

    // edge intersection (cell-local coords)
    private Vector2 edgePoint(int edge, float a, float b) {
        float t;
        float d = (b - a);
        if (Math.abs(d) < 1e-6f) t = 0.5f;
        else t = (ISO - a) / d;
        t = MathUtils.clamp(t, 0f, 1f);

        switch (edge) {
            case 0: return new Vector2(t, 0f);       // bottom
            case 1: return new Vector2(1f, t);       // right
            case 2: return new Vector2(1f - t, 1f);  // top
            case 3: return new Vector2(0f, 1f - t);  // left
            default: return new Vector2(0f, 0f);
        }
    }

    private void addQuadCell(int tx, int ty) {
        // add quad as 2 triangles in world px
        Vector2 p0 = toWorld(tx, ty, 0f, 0f);
        Vector2 p1 = toWorld(tx, ty, 1f, 0f);
        Vector2 p2 = toWorld(tx, ty, 1f, 1f);
        Vector2 p3 = toWorld(tx, ty, 0f, 1f);

        short i0 = addVertex(p0.x, p0.y);
        short i1 = addVertex(p1.x, p1.y);
        short i2 = addVertex(p2.x, p2.y);
        short i3 = addVertex(p3.x, p3.y);

        addTri(i0, i1, i2);
        addTri(i0, i2, i3);
    }

    private void addPoly(int cellX, int cellY, Vector2[] polyLocal) {
        if (polyLocal.length < 3) return;

        // Convert to world points
        // Triangulate fan around vertex 0
        short base = addVertex(toWorld(cellX, cellY, polyLocal[0].x, polyLocal[0].y));

        for (int i = 1; i < polyLocal.length - 1; i++) {
            short i1 = addVertex(toWorld(cellX, cellY, polyLocal[i].x, polyLocal[i].y));
            short i2 = addVertex(toWorld(cellX, cellY, polyLocal[i + 1].x, polyLocal[i + 1].y));
            addTri(base, i1, i2);
        }
    }

    private Vector2 toWorld(int cellX, int cellY, float lx, float ly) {
        return new Vector2((cellX + lx) * tileW, (cellY + ly) * tileH);
    }

    private short addVertex(Vector2 p) {
        return addVertex(p.x, p.y);
    }

    private short addVertex(float x, float y) {
        // position + vertex color (cheap “real water” gradient + shimmer)
        // deeper (low y) darker, near top lighter
        float t = MathUtils.clamp(y / (mapH * (float)tileH), 0f, 1f);

        // subtle animated shimmer (no tiles!)
        float shimmer = MathUtils.sin((x * 0.012f) + waterTime * 1.4f) * 0.04f
                + MathUtils.sin((y * 0.010f) + waterTime * 1.1f) * 0.03f;

        float r = MathUtils.lerp(0.04f, 0.20f, t) + shimmer;
        float g = MathUtils.lerp(0.28f, 0.70f, t) + shimmer;
        float b = MathUtils.lerp(0.78f, 1.00f, t) + shimmer;
        float a = MathUtils.lerp(0.70f, 0.88f, t);

        int base = vertCount * 6;
        verts[base]     = x;
        verts[base + 1] = y;
        verts[base + 2] = r;
        verts[base + 3] = g;
        verts[base + 4] = b;
        verts[base + 5] = a;

        short idx = (short) vertCount;
        vertCount++;
        return idx;
    }

    private void addTri(short a, short b, short c) {
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    // -------------------------
    // Waterfalls + inlet stream (your existing style)
    // -------------------------
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

            shapes.setColor(0.65f, 0.9f, 1.0f, 0.85f);
            shapes.rect(sx - ribbonW * 0.5f + wob0, segBottom, ribbonW, segTop - segBottom);

            shapes.setColor(0.78f, 0.95f, 1.0f, 0.45f);
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

                float alpha = MathUtils.clamp(0.18f + maxFlux * 1.2f, 0.18f, 0.85f);
                float baseW = MathUtils.clamp(7f + maxFlux * 18f, 7f, 16f);

                int segs = MathUtils.clamp((int) (runH / 28f), 6, 18);
                float centerX = (x + 0.5f) * tileW;

                for (int i = 0; i < segs; i++) {
                    float a0 = i / (float) segs;

                    float y0 = MathUtils.lerp(runTop, runBottom, a0);
                    float y1 = MathUtils.lerp(runTop, runBottom, (i + 1) / (float) segs);

                    float segBottom = Math.min(y0, y1);
                    float segTop = Math.max(y0, y1);

                    float drift = MathUtils.sin(waterTime * 2.0f + segBottom * 0.02f + x * 0.6f) * 2.5f;

                    float taper = 1f - 0.18f * a0;
                    float w = baseW * taper;
                    float cx = centerX + drift;

                    shapes.setColor(0.65f, 0.9f, 1.0f, alpha);
                    shapes.rect(cx - w * 0.5f, segBottom, w, segTop - segBottom);

                    shapes.setColor(0.82f, 0.96f, 1.0f, alpha * 0.35f);
                    shapes.rect(cx - w * 0.18f, segBottom, w * 0.36f, segTop - segBottom);
                }
            }
        }
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
        if (isSolid(x, y)) return false;
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
