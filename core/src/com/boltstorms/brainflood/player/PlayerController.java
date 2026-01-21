
package com.boltstorms.brainflood.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;

public class PlayerController {

    public static final float PLAYER_HALF_M = 0.35f;

    private Body player;

    public Body createPlayer(World world, float xM, float yM) {
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

        return player;
    }

    public Body getPlayer() {
        return player;
    }

    public void update(float dt) {
        if (player == null) return;

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
}
