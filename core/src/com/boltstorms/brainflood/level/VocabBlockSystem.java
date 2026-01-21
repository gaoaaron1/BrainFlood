package com.boltstorms.brainflood.level;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VocabBlockSystem {

    public static final String OBJ_LAYER = "vocab_blocks";

    public enum Side { HANZI, ENGLISH }

    public static class VocabPair {
        public final String hanzi;
        public final String english;
        public VocabPair(String hanzi, String english) {
            this.hanzi = hanzi;
            this.english = english;
        }
    }

    public static class VocabBlock {
        public final Rectangle boundsPx;
        public final int pairId;
        public final Side side;
        public final String text;

        public boolean broken = false;
        public boolean selected = false;

        public Body body; // collider while active

        public VocabBlock(Rectangle boundsPx, int pairId, Side side, String text) {
            this.boundsPx = boundsPx;
            this.pairId = pairId;
            this.side = side;
            this.text = text;
        }

        public boolean contains(float px, float py) {
            return boundsPx.contains(px, py);
        }
    }

    private final Level level;
    private final World world;
    private final float ppm;

    private final boolean[][] solid; // [ty][tx]
    private final List<VocabBlock> blocks = new ArrayList<>();

    private VocabBlock selectedA = null;

    public VocabBlockSystem(Level level, World world, float ppm) {
        this.level = level;
        this.world = world;
        this.ppm = ppm;
        this.solid = new boolean[level.mapH()][level.mapW()];
    }

    /** Call once after loading the map. */
    public void loadAndRandomize(List<VocabPair> vocabPool, int pairsNeeded) {
        blocks.clear();
        clearSolid();

        Array<Rectangle> rects = readRectsFromLayer();
        if (rects.size == 0) throw new RuntimeException("No objects in layer: " + OBJ_LAYER);
        if (rects.size % 2 != 0) throw new RuntimeException("vocab_blocks must be even (you have " + rects.size + ")");

        int totalBlocks = rects.size;
        int neededPairs = totalBlocks / 2;
        pairsNeeded = neededPairs;

        if (vocabPool.size() < pairsNeeded) {
            throw new RuntimeException("Not enough vocab pairs. Need " + pairsNeeded + ", have " + vocabPool.size());
        }

        List<VocabPair> poolCopy = new ArrayList<>(vocabPool);
        Collections.shuffle(poolCopy);
        List<VocabPair> chosen = poolCopy.subList(0, pairsNeeded);

        class Assignment {
            int pairId; Side side; String text;
            Assignment(int pairId, Side side, String text){ this.pairId = pairId; this.side = side; this.text = text; }
        }

        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            assignments.add(new Assignment(i, Side.HANZI, chosen.get(i).hanzi));
            assignments.add(new Assignment(i, Side.ENGLISH, chosen.get(i).english));
        }

        Collections.shuffle(assignments);
        rects.shuffle();

        for (int i = 0; i < totalBlocks; i++) {
            Rectangle r = rects.get(i);
            Assignment a = assignments.get(i);

            VocabBlock vb = new VocabBlock(new Rectangle(r), a.pairId, a.side, a.text);
            vb.body = createStaticBoxBody(r);
            blocks.add(vb);

            // ✅ MARK ALL TILES COVERED BY THIS RECTANGLE AS SOLID
            markSolidRect(r, true);
        }

        Gdx.app.log("VOCAB", "Loaded " + blocks.size() + " vocab blocks (" + pairsNeeded + " pairs).");
    }

    /** Click handling. */
    public boolean handleClick(float worldPxX, float worldPxY) {
        VocabBlock clicked = findTopmostBlock(worldPxX, worldPxY);
        if (clicked == null || clicked.broken) return false;

        if (selectedA == null) {
            select(clicked);
            selectedA = clicked;
            return true;
        }

        if (selectedA == clicked) {
            deselect(selectedA);
            selectedA = null;
            return true;
        }

        VocabBlock selectedB = clicked;
        select(selectedB);

        boolean isMatch = (selectedA.pairId == selectedB.pairId) && (selectedA.side != selectedB.side);

        if (isMatch) {
            breakBlock(selectedA);
            breakBlock(selectedB);
            selectedA = null;
        } else {
            deselect(selectedA);
            selectedA = selectedB;
        }

        return true;
    }

    public boolean isSolidTile(int tx, int ty) {
        if (tx < 0 || tx >= level.mapW() || ty < 0 || ty >= level.mapH()) return false;
        return solid[ty][tx];
    }

    public List<VocabBlock> getBlocks() {
        return blocks;
    }

    // ----------------- internals -----------------

    private void clearSolid() {
        for (int y = 0; y < level.mapH(); y++) {
            for (int x = 0; x < level.mapW(); x++) {
                solid[y][x] = false;
            }
        }
    }

    private void markSolidRect(Rectangle rPx, boolean value) {
        // Use small insets so borders don’t accidentally spill into neighbor tiles
        float inset = 0.01f;

        float left = rPx.x + inset;
        float right = rPx.x + rPx.width - inset;
        float bottom = rPx.y + inset;
        float top = rPx.y + rPx.height - inset;

        int x0 = level.pxToTileX(left);
        int x1 = level.pxToTileX(right);
        int y0 = level.pxToTileY(bottom);
        int y1 = level.pxToTileY(top);

        if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }

        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                if (tx < 0 || tx >= level.mapW() || ty < 0 || ty >= level.mapH()) continue;
                solid[ty][tx] = value;
            }
        }
    }

    private Array<Rectangle> readRectsFromLayer() {
        MapLayer layer = level.getMap().getLayers().get(OBJ_LAYER);
        if (layer == null) throw new RuntimeException("Missing object layer: " + OBJ_LAYER);

        Array<Rectangle> rects = new Array<>();
        for (MapObject obj : layer.getObjects()) {
            if (obj instanceof RectangleMapObject) {
                rects.add(new Rectangle(((RectangleMapObject) obj).getRectangle()));
            }
        }
        return rects;
    }

    private VocabBlock findTopmostBlock(float px, float py) {
        for (VocabBlock b : blocks) {
            if (!b.broken && b.contains(px, py)) return b;
        }
        return null;
    }

    private void select(VocabBlock b) { b.selected = true; }
    private void deselect(VocabBlock b) { b.selected = false; }

    private void breakBlock(VocabBlock b) {
        b.broken = true;
        b.selected = false;

        // ✅ clear ALL covered tiles, not just one
        markSolidRect(b.boundsPx, false);

        if (b.body != null) {
            world.destroyBody(b.body);
            b.body = null;
        }
    }

    private Body createStaticBoxBody(Rectangle rPx) {
        float cxM = (rPx.x + rPx.width * 0.5f) / ppm;
        float cyM = (rPx.y + rPx.height * 0.5f) / ppm;

        float hxM = (rPx.width * 0.5f) / ppm;
        float hyM = (rPx.height * 0.5f) / ppm;

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
        return b;
    }
}
