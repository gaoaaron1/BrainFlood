
package com.boltstorms.brainflood.level;

import com.badlogic.gdx.maps.MapLayer;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.MapObjects;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

public class Level {

    // Layer names (match Tiled)
    public static final String LAYER_BG = "background_layer";
    public static final String LAYER_FG = "foreground_layer";
    public static final String LAYER_FG_DECOR = "foreground_decor";
    public static final String LAYER_WALL = "wall";

    // Object layers (match Tiled)
    public static final String OBJ_PLAYER_SPAWN = "player_spawn";
    public static final String OBJ_INLET = "inlet";
    public static final String OBJ_OUTLET = "outlet";

    private final TiledMap map;
    private final TiledMapTileLayer wallLayer;

    private final int mapW, mapH;
    private final int tileW, tileH;

    public Level(TiledMap map) {
        this.map = map;

        this.wallLayer = (TiledMapTileLayer) map.getLayers().get(LAYER_WALL);
        if (wallLayer == null) throw new RuntimeException("Missing tile layer: " + LAYER_WALL);

        this.mapW = wallLayer.getWidth();
        this.mapH = wallLayer.getHeight();
        this.tileW = (int) wallLayer.getTileWidth();
        this.tileH = (int) wallLayer.getTileHeight();
    }

    public TiledMap getMap() { return map; }
    public TiledMapTileLayer getWallLayer() { return wallLayer; }

    public int mapW() { return mapW; }
    public int mapH() { return mapH; }
    public int tileW() { return tileW; }
    public int tileH() { return tileH; }

    public int mapWidthPx() { return mapW * tileW; }
    public int mapHeightPx() { return mapH * tileH; }

    public boolean isWall(int tx, int ty) {
        TiledMapTileLayer.Cell cell = wallLayer.getCell(tx, ty);
        return cell != null && cell.getTile() != null;
    }

    public boolean isOpen(int tx, int ty) {
        return !isWall(tx, ty);
    }

    public void removeWall(int tx, int ty) {
        wallLayer.setCell(tx, ty, null);
    }

    public int pxToTileX(float px) {
        return MathUtils.clamp((int)(px / tileW), 0, mapW - 1);
    }

    public int pxToTileY(float py) {
        return MathUtils.clamp((int)(py / tileH), 0, mapH - 1);
    }

    public Vector2 tileCenterPx(int tx, int ty) {
        return new Vector2((tx + 0.5f) * tileW, (ty + 0.5f) * tileH);
    }

    public Vector2 getObjectCenterPx(String objectLayerName) {
        MapLayer layer = map.getLayers().get(objectLayerName);
        if (layer == null) throw new RuntimeException("Missing object layer: " + objectLayerName);

        MapObjects objs = layer.getObjects();
        if (objs.getCount() == 0) throw new RuntimeException("No objects in layer: " + objectLayerName);

        MapObject obj = objs.get(0);
        Rectangle r = ((RectangleMapObject) obj).getRectangle();
        return new Vector2(r.x + r.width * 0.5f, r.y + r.height * 0.5f);
    }
}
