package com.iamadedo.phoneapp

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Offline Maps Sync Manager — WearOS / Pixel Watch platform feature.
 *
 * Downloads a compact vector tile set (GeoJSON simplified) around the user's
 * current location and sends it to the watch via the existing file transfer
 * system (OFFLINE_MAPS_SYNC message).
 *
 * Source: OpenStreetMap via Overpass API (free, no key required).
 * The tile covers a configurable radius (default 2 km) and includes:
 *   - Roads and paths
 *   - POI names (hospitals, parks, transit)
 *   - Trail markers
 *
 * Data is serialised as compact JSON and sent via the chunked file transfer
 * protocol already built into BluetoothService.
 *
 * The watch app stores the tile in internal storage and the compass / workout
 * screens overlay it as a simple canvas map.
 */
class OfflineMapsSyncManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val PREFS        = "offline_maps_prefs"
        private const val KEY_LAST_LAT = "last_sync_lat"
        private const val KEY_LAST_LON = "last_sync_lon"
        private const val KEY_LAST_TS  = "last_sync_ts"
        private const val RADIUS_M     = 2000           // 2 km radius
        private const val REFRESH_DIST = 1000.0         // re-sync if moved 1 km
        private const val REFRESH_MS   = 6 * 60 * 60_000L  // or every 6 hours
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun syncIfNeeded(location: Location) {
        val lastLat = prefs.getFloat(KEY_LAST_LAT, 0f).toDouble()
        val lastLon = prefs.getFloat(KEY_LAST_LON, 0f).toDouble()
        val lastTs  = prefs.getLong(KEY_LAST_TS, 0L)
        val now     = System.currentTimeMillis()

        val results = FloatArray(1)
        Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, results)
        val movedFar = results[0] > REFRESH_DIST
        val stale    = now - lastTs > REFRESH_MS

        if (movedFar || stale) {
            scope.launch { sync(location.latitude, location.longitude) }
        }
    }

    fun forcSync(lat: Double, lon: Double) {
        scope.launch { sync(lat, lon) }
    }

    private suspend fun sync(lat: Double, lon: Double) {
        try {
            val geoJson = fetchTile(lat, lon) ?: return
            val file = File(context.cacheDir, "offline_map_tile.json")
            file.writeText(geoJson)

            prefs.edit()
                .putFloat(KEY_LAST_LAT, lat.toFloat())
                .putFloat(KEY_LAST_LON, lon.toFloat())
                .putLong(KEY_LAST_TS, System.currentTimeMillis())
                .apply()

            // Signal companion that a new map tile is ready via OFFLINE_MAPS_SYNC
            // The actual file transfer uses the existing file transfer protocol
            val meta = JSONObject().apply {
                put("lat",    lat)
                put("lon",    lon)
                put("radius", RADIUS_M)
                put("size",   file.length())
                put("path",   file.absolutePath)
            }
            val msg = ProtocolMessage(
                type = MessageType.OFFLINE_MAPS_SYNC,
                data = com.google.gson.JsonParser.parseString(meta.toString()).asJsonObject
            )
            onSendMessage(msg)

        } catch (e: Exception) {
            android.util.Log.e("OfflineMapsSync", "Sync failed: ${e.message}")
        }
    }

    private fun fetchTile(lat: Double, lon: Double): String? {
        val bbox = buildBbox(lat, lon, RADIUS_M)
        val query = """
            [out:json][timeout:15];
            (
              way["highway"~"primary|secondary|tertiary|residential|path|footway|cycleway"]($bbox);
              node["amenity"~"hospital|pharmacy|police|fire_station"]($bbox);
              node["leisure"="park"]($bbox);
              node["public_transport"="stop_position"]($bbox);
            );
            out center 200;
        """.trimIndent()

        val url = URL(OVERPASS_URL)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout    = 20_000
        conn.outputStream.bufferedWriter().use { it.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}") }

        if (conn.responseCode != 200) return null
        val raw = conn.inputStream.bufferedReader().readText()
        return convertToSimpleGeoJson(raw, lat, lon)
    }

    private fun buildBbox(lat: Double, lon: Double, radiusM: Int): String {
        val deg = radiusM / 111_000.0
        return "${lat - deg},${lon - deg},${lat + deg},${lon + deg}"
    }

    /**
     * Convert Overpass JSON to a compact GeoJSON-like format
     * optimised for small watch screens.
     */
    private fun convertToSimpleGeoJson(overpassJson: String, centerLat: Double, centerLon: Double): String {
        return try {
            val root     = JSONObject(overpassJson)
            val elements = root.getJSONArray("elements")
            val features = JSONArray()

            for (i in 0 until elements.length()) {
                val el   = elements.getJSONObject(i)
                val type = el.getString("type")
                val tags = el.optJSONObject("tags") ?: continue

                when (type) {
                    "node" -> {
                        val feature = JSONObject().apply {
                            put("t", "p")   // point
                            put("x", el.getDouble("lon") - centerLon)   // relative coords
                            put("y", el.getDouble("lat") - centerLat)
                            put("n", tags.optString("name", tags.optString("amenity", "")))
                        }
                        features.put(feature)
                    }
                    "way" -> {
                        val highway = tags.optString("highway", "")
                        if (highway.isNotEmpty()) {
                            val center = el.optJSONObject("center")
                            if (center != null) {
                                val feature = JSONObject().apply {
                                    put("t", "r")   // road
                                    put("x", center.getDouble("lon") - centerLon)
                                    put("y", center.getDouble("lat") - centerLat)
                                    put("h", highway)
                                    put("n", tags.optString("name", ""))
                                }
                                features.put(feature)
                            }
                        }
                    }
                }
            }

            JSONObject().apply {
                put("c_lat", centerLat)
                put("c_lon", centerLon)
                put("r",     RADIUS_M)
                put("f",     features)
            }.toString()
        } catch (e: Exception) { null } ?: ""
    }
}
