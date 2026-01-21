
package com.boltstorms.brainflood.player;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.boltstorms.brainflood.level.Level;
import com.boltstorms.brainflood.water.WaterSystem;

public class BuoyancySystem {

    private final Level level;
    private final float ppm;

    public float buoyancyStrength = 25f;
    public float waterDrag = 4f;

    public BuoyancySystem(Level level, float ppm) {
        this.level = level;
        this.ppm = ppm;
    }

    public void apply(Body player, WaterSystem water, float dt) {
        if (player == null) return;
        if (!water.isWaterStarted()) return;

        Vector2 pM = player.getPosition();
        float px = pM.x * ppm;
        float py = pM.y * ppm;

        int tx = level.pxToTileX(px);
        int ty = level.pxToTileY(py);

        if (!water.isInWaterRegion(tx, ty)) return;

        float surfaceM = water.getLocalSurfacePx(tx, ty) / ppm;

        float bottomM = pM.y - PlayerController.PLAYER_HALF_M;
        float submerged = MathUtils.clamp(surfaceM - bottomM, 0f, PlayerController.PLAYER_HALF_M * 2f);
        float frac = submerged / (PlayerController.PLAYER_HALF_M * 2f);

        if (frac > 0f) {
            float forceY = buoyancyStrength * frac * player.getMass();
            player.applyForceToCenter(0, forceY, true);

            Vector2 v = player.getLinearVelocity();
            player.applyForceToCenter(-v.x * waterDrag * player.getMass(),
                    -v.y * waterDrag * player.getMass(),
                    true);
        }
    }
}
