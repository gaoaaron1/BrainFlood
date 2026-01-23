package com.boltstorms.brainflood.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Box2DDebugRenderer;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import com.boltstorms.brainflood.level.Level;
import com.boltstorms.brainflood.level.VocabBlockSystem;
import com.boltstorms.brainflood.level.WallPhysics;
import com.boltstorms.brainflood.player.BuoyancySystem;
import com.boltstorms.brainflood.player.PlayerController;
import com.boltstorms.brainflood.water.WaterSystem;

import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

import java.util.List;

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

    private VocabBlockSystem vocabBlocks;

    // text
    private SpriteBatch batch;
    private BitmapFont font;
    private FreeTypeFontGenerator fontGen;

    // water textures
    private Texture waterTex;      // water body fill
    private Texture waterLineTex;  // optional, can be same as waterTex

    @Override
    public void show() {
        map = new TmxMapLoader().load(MAP_PATH);
        level = new Level(map);
        mapRenderer = new OrthogonalTiledMapRenderer(map);

        shapes = new ShapeRenderer();
        batch = new SpriteBatch();

        camPx = new OrthographicCamera();
        viewport = new FitViewport(level.mapWidthPx(), level.mapHeightPx(), camPx);
        viewport.apply();
        camPx.position.set(level.mapWidthPx() / 2f, level.mapHeightPx() / 2f, 0);
        camPx.update();

        world = new World(new Vector2(0, -18f), true);
        debug = new Box2DDebugRenderer();

        wallPhysics = new WallPhysics(world, PPM);
        wallPhysics.buildAll(level);

        vocabBlocks = new VocabBlockSystem(level, world, PPM);

        // vocab pool
        List<VocabBlockSystem.VocabPair> pool = new java.util.ArrayList<VocabBlockSystem.VocabPair>();
        pool.add(new VocabBlockSystem.VocabPair("狗", "dog"));
        pool.add(new VocabBlockSystem.VocabPair("猫", "cat"));
        pool.add(new VocabBlockSystem.VocabPair("水", "water"));
        pool.add(new VocabBlockSystem.VocabPair("火", "fire"));
        pool.add(new VocabBlockSystem.VocabPair("人", "person"));
        pool.add(new VocabBlockSystem.VocabPair("山", "mountain"));

        vocabBlocks.loadAndRandomize(pool, 4);

        // font
        fontGen = new FreeTypeFontGenerator(Gdx.files.internal("NotoSansSC-VariableFont_wght.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = 18;
        p.characters =
                FreeTypeFontGenerator.DEFAULT_CHARS +
                        "狗猫水火人山" +
                        "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞";
        font = fontGen.generateFont(p);

        // objects
        Vector2 spawnPx = level.getObjectCenterPx(Level.OBJ_PLAYER_SPAWN);
        Vector2 inletPx = level.getObjectCenterPx(Level.OBJ_INLET);
        Vector2 outletPx = level.getObjectCenterPx(Level.OBJ_OUTLET);

        int inletTx = level.pxToTileX(inletPx.x);
        int inletTy = level.pxToTileY(inletPx.y);
        int outletTx = level.pxToTileX(outletPx.x);
        int outletTy = level.pxToTileY(outletPx.y);

        waterSystem = new WaterSystem(level, inletTx, inletTy, outletTx, outletTy,
                (tx, ty) -> level.isWall(tx, ty) || vocabBlocks.isSolidTile(tx, ty)
        );

        playerController = new PlayerController();
        buoyancySystem = new BuoyancySystem(level, PPM);

        Vector2 spawnM = new Vector2(spawnPx.x / PPM, spawnPx.y / PPM);
        playerController.createPlayer(world, spawnM.x, spawnM.y);

        // water textures (put these in assets; you can reuse the same file)
        // If you don't have them yet, make simple placeholder textures.
        waterTex = new Texture(Gdx.files.internal("water.png"));
        waterLineTex = waterTex;
    }

    // ---------------------------
    // Input + Update
    // ---------------------------
    private void handleMouseClick() {
        if (!Gdx.input.justTouched()) return;

        Vector2 worldPx = viewport.unproject(new Vector2(Gdx.input.getX(), Gdx.input.getY()));

        boolean used = vocabBlocks.handleClick(worldPx.x, worldPx.y);
        if (used) {
            waterSystem.onLevelChanged();
            return;
        }

        int tx = level.pxToTileX(worldPx.x);
        int ty = level.pxToTileY(worldPx.y);

        if (!level.isWall(tx, ty)) return;

        level.removeWall(tx, ty);
        wallPhysics.destroyWall(tx, ty);
        waterSystem.onLevelChanged();
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
        handleMouseClick();

        world.step(1f / 60f, 6, 2);
    }

    // ---------------------------
    // RENDER
    // ---------------------------
    @Override
    public void render(float delta) {
        float dt = Math.min(delta, 1f / 30f);
        update(dt);

        camPx.update();
        mapRenderer.setView(camPx);

        Gdx.gl.glClearColor(0.93f, 0.93f, 0.93f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // background
        renderLayerIfExists(Level.LAYER_BG);

        // blocks + player
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.setProjectionMatrix(camPx.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (VocabBlockSystem.VocabBlock b : vocabBlocks.getBlocks()) {
            if (b.broken) continue;

            if (b.selected) shapes.setColor(1f, 1f, 0f, 0.35f);
            else if (b.side == VocabBlockSystem.Side.HANZI) shapes.setColor(0.2f, 0.2f, 0.2f, 0.75f);
            else shapes.setColor(0.15f, 0.15f, 0.35f, 0.75f);

            shapes.rect(b.boundsPx.x, b.boundsPx.y, b.boundsPx.width, b.boundsPx.height);
        }

        if (playerController.getPlayer() != null) {
            shapes.setColor(0.1f, 0.85f, 0.2f, 1f);
            Vector2 pm = playerController.getPlayer().getPosition();
            float ppx = pm.x * PPM;
            float ppy = pm.y * PPM;
            float halfPx = PlayerController.PLAYER_HALF_M * PPM;
            shapes.rect(ppx - halfPx, ppy - halfPx, halfPx * 2f, halfPx * 2f);
        }

        shapes.end();

        // walls/fg
        renderLayerIfExists(Level.LAYER_WALL);
        renderLayerIfExists(Level.LAYER_FG);
        renderLayerIfExists(Level.LAYER_FG_DECOR);

        // text
        batch.setProjectionMatrix(camPx.combined);
        batch.begin();
        for (VocabBlockSystem.VocabBlock b : vocabBlocks.getBlocks()) {
            if (b.broken) continue;
            float tx = b.boundsPx.x + 6f;
            float ty = b.boundsPx.y + b.boundsPx.height * 0.65f;
            font.draw(batch, b.text, tx, ty);
        }
        batch.end();

        // water (body + smooth surface + inlet stream)
        drawWater();
        // debug.render(world, camPx.combined);
    }

    private void drawWater() {
        // inlet stream
        shapes.setProjectionMatrix(camPx.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        waterSystem.renderInletOnly(shapes);
        shapes.end();

        if (!waterSystem.isWaterStarted()) return;

        // --- 1) draw water body as merged runs (fast, no tile edges spam) ---
        batch.setProjectionMatrix(camPx.combined);
        batch.begin();

        final int w = waterSystem.mapW();
        final int h = waterSystem.mapH();
        final int tw = waterSystem.tileW();
        final int th = waterSystem.tileH();

        final float DRAW_MIN = 0.02f;

        // Draw as horizontal merged strips. Still tile-based fill, but MUCH cleaner.
        for (int ty = 0; ty < h; ty++) {
            float yPx = ty * th;

            int x = 0;
            while (x < w) {
                float fill = waterSystem.getFill(x, ty);
                if (fill < DRAW_MIN) { x++; continue; }

                // start run
                int start = x;
                int end = x;

                while (end + 1 < w && waterSystem.getFill(end + 1, ty) >= DRAW_MIN) end++;

                // For the run, use the MIN fill across the run for height so it doesn't step mid-strip.
                // (Surface line will provide the “real” look.)
                float minFill = 1f;
                for (int ix = start; ix <= end; ix++) {
                    minFill = Math.min(minFill, waterSystem.getFill(ix, ty));
                }

                float height = Math.max(1f, minFill * th);

                // Use tint alpha (subtle)
                batch.setColor(1f, 1f, 1f, 0.75f);
                batch.draw(waterTex, start * tw, yPx, (end - start + 1) * tw, height);

                x = end + 1;
            }
        }

        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();

        // --- 2) draw smooth “real” surface runs on top ---
        // We draw a continuous band across contiguous surface tiles per row.
        // This avoids the “vertical slabs” bug entirely.
        List<WaterSystem.SurfaceRun> runs = waterSystem.getSurfaceRunsPx();

        shapes.setProjectionMatrix(camPx.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        float t = waterSystem.getWaterTime();
        float surfaceThickness = 4.0f;

        for (WaterSystem.SurfaceRun r : runs) {
            int len = r.x1 - r.x0 + 1;
            if (len <= 0) continue;

            // draw as a sequence of small rect segments so it looks continuous but smooth
            for (int i = 0; i < len; i++) {
                int tx = r.x0 + i;
                float x0 = tx * tw;
                float x1 = x0 + tw;

                float baseY = r.surfaceYPx[i];

                // small wave offset
                float wave = (float)Math.sin((tx * 0.85f) + t * 3.2f) * 1.8f;
                float y = baseY + wave;

                // lighter crest
                shapes.setColor(0.75f, 0.92f, 1.0f, 0.85f);
                shapes.rect(x0, y - surfaceThickness * 0.5f, (x1 - x0), surfaceThickness);

                // tiny highlight lip
                shapes.setColor(0.90f, 0.97f, 1.0f, 0.35f);
                shapes.rect(x0, y + 1.0f, (x1 - x0), 2.0f);
            }
        }

        shapes.end();
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
        if (shapes != null) shapes.dispose();
        if (debug != null) debug.dispose();
        if (world != null) world.dispose();
        if (mapRenderer != null) mapRenderer.dispose();
        if (map != null) map.dispose();

        if (batch != null) batch.dispose();
        if (font != null) font.dispose();
        if (fontGen != null) fontGen.dispose();

        if (waterTex != null) waterTex.dispose();
    }
}
