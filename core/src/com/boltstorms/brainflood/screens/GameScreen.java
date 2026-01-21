package com.boltstorms.brainflood.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import com.boltstorms.brainflood.level.Level;
import com.boltstorms.brainflood.level.WallPhysics;
import com.boltstorms.brainflood.player.BuoyancySystem;
import com.boltstorms.brainflood.player.PlayerController;
import com.boltstorms.brainflood.water.WaterSystem;

public class GameScreen implements Screen {

    private static final String MAP_PATH = "Stages/level_01.tmx";
    private static final float PPM = 32f;

    private Level level;

    private TiledMap map;
    private OrthogonalTiledMapRenderer mapRenderer;

    private OrthographicCamera camPx;
    private Viewport viewport;
    private ShapeRenderer shapes;

    private World world;
    private Box2DDebugRenderer debug;

    private WallPhysics wallPhysics;
    private WaterSystem waterSystem;

    private PlayerController playerController;
    private BuoyancySystem buoyancySystem;

    @Override
    public void show() {
        map = new TmxMapLoader().load(MAP_PATH);
        level = new Level(map);
        mapRenderer = new OrthogonalTiledMapRenderer(map);

        shapes = new ShapeRenderer();

        camPx = new OrthographicCamera();
        viewport = new FitViewport(level.mapWidthPx(), level.mapHeightPx(), camPx);
        viewport.apply();
        camPx.position.set(level.mapWidthPx() / 2f, level.mapHeightPx() / 2f, 0);
        camPx.update();

        world = new World(new Vector2(0, -18f), true);
        debug = new Box2DDebugRenderer();

        wallPhysics = new WallPhysics(world, PPM);
        wallPhysics.buildAll(level);

        // objects
        Vector2 spawnPx = level.getObjectCenterPx(Level.OBJ_PLAYER_SPAWN);
        Vector2 inletPx = level.getObjectCenterPx(Level.OBJ_INLET);
        Vector2 outletPx = level.getObjectCenterPx(Level.OBJ_OUTLET);

        int inletTx = level.pxToTileX(inletPx.x);
        int inletTy = level.pxToTileY(inletPx.y);
        int outletTx = level.pxToTileX(outletPx.x);
        int outletTy = level.pxToTileY(outletPx.y);

        waterSystem = new WaterSystem(level, inletTx, inletTy, outletTx, outletTy);

        playerController = new PlayerController();
        buoyancySystem = new BuoyancySystem(level, PPM);

        Vector2 spawnM = new Vector2(spawnPx.x / PPM, spawnPx.y / PPM);
        playerController.createPlayer(world, spawnM.x, spawnM.y);
    }

    private void handleMouseDestroy() {
        if (!Gdx.input.justTouched()) return;

        Vector2 worldPx = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
        int tx = level.pxToTileX(worldPx.x);
        int ty = level.pxToTileY(worldPx.y);

        if (!level.isWall(tx, ty)) return;

        level.removeWall(tx, ty);
        wallPhysics.destroyWall(tx, ty);

        waterSystem.onLevelChanged();
    }

    private void update(float dt) {
        handleMouseDestroy();

        playerController.update(dt);
        waterSystem.update(dt);
        buoyancySystem.apply(playerController.getPlayer(), waterSystem, dt);

        world.step(1f / 60f, 6, 2);
    }

    @Override
    public void render(float delta) {
        float dt = Math.min(delta, 1f / 30f);
        update(dt);

        Gdx.gl.glClearColor(0.93f, 0.93f, 0.93f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camPx.update();
        mapRenderer.setView(camPx);

        renderLayerIfExists(Level.LAYER_BG);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.setProjectionMatrix(camPx.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Water draws itself (includes inlet stream)
        waterSystem.render(shapes);

        // Player
        if (playerController.getPlayer() != null) {
            shapes.setColor(0.1f, 0.85f, 0.2f, 1f);
            Vector2 pm = playerController.getPlayer().getPosition();
            float ppx = pm.x * PPM;
            float ppy = pm.y * PPM;
            float halfPx = PlayerController.PLAYER_HALF_M * PPM;
            shapes.rect(ppx - halfPx, ppy - halfPx, halfPx * 2f, halfPx * 2f);
        }

        shapes.end();

        renderLayerIfExists(Level.LAYER_WALL);
        renderLayerIfExists(Level.LAYER_FG);
        renderLayerIfExists(Level.LAYER_FG_DECOR);

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
