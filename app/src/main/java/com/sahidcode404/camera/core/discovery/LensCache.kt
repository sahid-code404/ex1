package com.sahidcode404.camera.core.discovery

import android.content.Context
import android.os.Build
import android.util.Size
import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import com.sahidcode404.camera.core.model.LensFacing
import com.sahidcode404.camera.core.model.RouteKind
import org.json.JSONArray
import org.json.JSONObject

class LensCache(context: Context) {
    private val prefs = context.getSharedPreferences("lens_topology_v1", Context.MODE_PRIVATE)

    fun load(currentPublicIds: List<String>): List<CanonicalLens>? {
        if (prefs.getString("fingerprint", null) != Build.FINGERPRINT) return null
        if (prefs.getString("public_ids", null) != UniversalCameraDiscoverer.publicIdSetHash(currentPublicIds)) return null
        val raw = prefs.getString("topology", null) ?: return null
        return runCatching { decode(JSONArray(raw)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun save(currentPublicIds: List<String>, lenses: List<CanonicalLens>) {
        prefs.edit()
            .putString("fingerprint", Build.FINGERPRINT)
            .putString("public_ids", UniversalCameraDiscoverer.publicIdSetHash(currentPublicIds))
            .putString("topology", encode(lenses).toString())
            .apply()
    }

    fun saveLastWorkingRoute(lensId: String, routeKey: String) {
        prefs.edit().putString("last_lens", lensId).putString("last_route", routeKey).apply()
    }

    fun lastLensId(): String? = prefs.getString("last_lens", null)

    private fun encode(lenses: List<CanonicalLens>) = JSONArray().apply {
        lenses.forEach { lens ->
            put(JSONObject().apply {
                put("id", lens.id)
                put("label", lens.label)
                put("facing", lens.facing.name)
                put("routes", JSONArray().apply { lens.routes.forEach { put(encodeRoute(it)) } })
            })
        }
    }

    private fun encodeRoute(route: CameraRoute) = JSONObject().apply {
        put("openId", route.openId)
        put("physicalId", route.physicalId ?: JSONObject.NULL)
        put("kind", route.kind.name)
        put("facing", route.facing.name)
        put("focal", JSONArray(route.focalLengthsMm))
        put("sensorW", route.sensorPhysicalWidthMm ?: JSONObject.NULL)
        put("sensorH", route.sensorPhysicalHeightMm ?: JSONObject.NULL)
        put("orientation", route.sensorOrientation)
        put("preview", encodeSizes(route.previewSizes))
        put("raw", encodeSizes(route.rawSizes))
        put("manual", route.supportsManualSensor)
        put("cfa", route.cfa ?: JSONObject.NULL)
        put("black", JSONArray(route.blackLevels.toList()))
        put("white", route.whiteLevel ?: JSONObject.NULL)
        put("optical", route.opticalFingerprint)
    }

    private fun decode(array: JSONArray): List<CanonicalLens> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val routesJson = o.getJSONArray("routes")
            val routes = buildList {
                for (j in 0 until routesJson.length()) add(decodeRoute(routesJson.getJSONObject(j)))
            }
            if (routes.isNotEmpty()) {
                add(
                    CanonicalLens(
                        id = o.getString("id"),
                        label = o.getString("label"),
                        facing = LensFacing.valueOf(o.getString("facing")),
                        routes = routes,
                    ),
                )
            }
        }
    }

    private fun decodeRoute(o: JSONObject): CameraRoute {
        val black = o.getJSONArray("black")
        return CameraRoute(
            openId = o.getString("openId"),
            physicalId = o.optString("physicalId").takeIf { it.isNotBlank() && it != "null" },
            kind = RouteKind.valueOf(o.getString("kind")),
            facing = LensFacing.valueOf(o.getString("facing")),
            focalLengthsMm = decodeFloats(o.getJSONArray("focal")),
            sensorPhysicalWidthMm = if (o.isNull("sensorW")) null else o.getDouble("sensorW").toFloat(),
            sensorPhysicalHeightMm = if (o.isNull("sensorH")) null else o.getDouble("sensorH").toFloat(),
            sensorOrientation = o.getInt("orientation"),
            previewSizes = decodeSizes(o.getJSONArray("preview")),
            rawSizes = decodeSizes(o.getJSONArray("raw")),
            supportsManualSensor = o.getBoolean("manual"),
            cfa = if (o.isNull("cfa")) null else o.getInt("cfa"),
            blackLevels = IntArray(4) { index -> if (index < black.length()) black.getInt(index) else 0 },
            whiteLevel = if (o.isNull("white")) null else o.getInt("white"),
            opticalFingerprint = o.getString("optical"),
        )
    }

    private fun encodeSizes(sizes: List<Size>) = JSONArray().apply {
        sizes.forEach { put(JSONArray().put(it.width).put(it.height)) }
    }

    private fun decodeSizes(array: JSONArray): List<Size> = buildList {
        for (i in 0 until array.length()) {
            val pair = array.getJSONArray(i)
            add(Size(pair.getInt(0), pair.getInt(1)))
        }
    }

    private fun decodeFloats(array: JSONArray): List<Float> = buildList {
        for (i in 0 until array.length()) add(array.getDouble(i).toFloat())
    }
}
