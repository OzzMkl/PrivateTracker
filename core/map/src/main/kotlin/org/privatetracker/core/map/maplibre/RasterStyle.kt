package org.privatetracker.core.map.maplibre

import org.json.JSONArray
import org.json.JSONObject
import org.privatetracker.core.map.TileSourceConfig

internal const val TILE_SOURCE_ID = "tiles"

/** A MapLibre style with one raster layer. Built with JSONObject so any URL is escaped correctly. */
internal fun rasterStyleJson(tiles: TileSourceConfig): String =
    JSONObject()
        .put("version", 8)
        .put(
            "sources",
            JSONObject().put(
                TILE_SOURCE_ID,
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", JSONArray().put(tiles.urlTemplate))
                    .put("tileSize", 256)
                    .put("maxzoom", tiles.maxZoom)
                    .put("attribution", tiles.attribution),
            ),
        )
        .put("layers", JSONArray().put(JSONObject().put("id", TILE_SOURCE_ID).put("type", "raster").put("source", TILE_SOURCE_ID)))
        .toString()
