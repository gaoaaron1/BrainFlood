
package com.boltstorms.brainflood.level;

import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.math.Vector2;

public class WallPhysics {

    private final World world;
    private final float ppm;

    private Body[][] wallBodies; // [y][x]

    public WallPhysics(World world, float ppm) {
        this.world = world;
        this.ppm = ppm;
    }

    public void buildAll(Level level) {
        wallBodies = new Body[level.mapH()][level.mapW()];

        for (int y = 0; y < level.mapH(); y++) {
            for (int x = 0; x < level.mapW(); x++) {
                if (!level.isWall(x, y)) continue;
                wallBodies[y][x] = createWallBody(level, x, y);
            }
        }
    }

    public void destroyWall(int tx, int ty) {
        if (wallBodies == null) return;
        Body b = wallBodies[ty][tx];
        if (b != null) {
            world.destroyBody(b);
            wallBodies[ty][tx] = null;
        }
    }

    public void createWall(Level level, int tx, int ty) {
        // optional helper if you later want to place walls
        if (wallBodies == null) return;
        if (wallBodies[ty][tx] != null) return;
        wallBodies[ty][tx] = createWallBody(level, tx, ty);
    }

    private Body createWallBody(Level level, int tx, int ty) {
        float tilePxX = tx * level.tileW();
        float tilePxY = ty * level.tileH();

        float cxM = (tilePxX + level.tileW() * 0.5f) / ppm;
        float cyM = (tilePxY + level.tileH() * 0.5f) / ppm;
        float hxM = (level.tileW() * 0.5f) / ppm;
        float hyM = (level.tileH() * 0.5f) / ppm;

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
