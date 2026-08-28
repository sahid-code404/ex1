package com.sahidcode404.camera.core.discovery

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import com.sahidcode404.camera.core.model.LensFacing
import com.sahidcode404.camera.core.model.RouteKind
import com.sahidcode404.camera.core.model.lensFacingOf
import java.security.MessageDigest
import kotlin.math.roundToInt

class UniversalCameraDiscoverer(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun publicIds(): List<String> = manager.cameraIdList.toList()

    fun discover(): List<CanonicalLens> {
        val publicIds = publicIds()
        val publicRoutes = publicIds.mapNotNull { id -> readRoute(id, null, RouteKind.PUBLIC) }
        val physicalByParent = linkedMapOf<String, MutableList<CameraRoute>>()

        if (Build.VERSION.SDK_INT >= 28) {
            for (parent in publicIds) {
                val characteristics = runCatching { manager.getCameraCharacteristics(parent) }.getOrNull() ?: continue
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
                for (physicalId in characteristics.physicalCameraIds) {
                    val route = readRoute(parent, physicalId, RouteKind.LOGICAL_PHYSICAL) ?: continue
                    physicalByParent.getOrPut(parent) { mutableListOf() }.add(route)
                }
            }
        }

        val result = mutableListOf<CanonicalLens>()
        val consumedDirectIds = mutableSetOf<String>()

        for (publicRoute in publicRoutes) {
            val children = physicalByParent[publicRoute.openId].orEmpty()
            if (children.isEmpty()) {
                if (publicRoute.openId in consumedDirectIds) continue
                result += lensForSingleRoute(publicRoute)
                continue
            }

            for (child in children.sortedWith(compareBy({ it.facing.ordinal }, { representativeFocal(it) }, { it.routeKey }))) {
                val directDuplicate = publicRoutes.firstOrNull {
                    it.openId == child.physicalId && it.opticalFingerprint == child.opticalFingerprint
                }
                val profiles = buildList {
                    add(child)
                    if (directDuplicate != null) {
                        add(directDuplicate)
                        consumedDirectIds += directDuplicate.openId
                    }
                    add(publicRoute)
                }.distinctBy { it.routeKey }
                result += CanonicalLens(
                    id = stableId("lens|${child.opticalFingerprint}|${child.physicalId.orEmpty()}"),
                    label = opticalLabel(child),
                    facing = child.facing,
                    routes = profiles,
                )
            }
        }

        return result
            .filter { lens -> lens.routes.any { it.previewSizes.isNotEmpty() } }
            .sortedWith(compareBy<CanonicalLens>({ facingOrder(it.facing) }, { representativeFocal(it.preferredRoute) }, { it.id }))
            .let(::deduplicateLabels)
    }

    private fun lensForSingleRoute(route: CameraRoute) = CanonicalLens(
        id = stableId("lens|${route.opticalFingerprint}|${route.routeKey}"),
        label = opticalLabel(route),
        facing = route.facing,
        routes = listOf(route),
    )

    private fun readRoute(openId: String, physicalId: String?, kind: RouteKind): CameraRoute? {
        val metadataId = physicalId ?: openId
        val c = runCatching { manager.getCameraCharacteristics(metadataId) }.getOrNull() ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previewSizes = runCatching { map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.width > 0 && it.height > 0 }
            .distinct()
        if (previewSizes.isEmpty()) return null

        val available = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawAdvertised = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawSizes = if (rawAdvertised) {
            runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .filter { it.width > 0 && it.height > 0 }
                .distinct()
        } else emptyList()

        val focal = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf())
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val manual = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val blackPattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevels = intArrayOf(
            blackPattern?.getOffsetForIndex(0, 0) ?: 0,
            blackPattern?.getOffsetForIndex(0, 1) ?: 0,
            blackPattern?.getOffsetForIndex(1, 0) ?: 0,
            blackPattern?.getOffsetForIndex(1, 1) ?: 0,
        )
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val facing = lensFacingOf(c.get(CameraCharacteristics.LENS_FACING))
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val opticalFingerprint = stableId(
            listOf(
                facing.name,
                focal.joinToString(",") { "%.4f".format(java.util.Locale.US, it) },
                physical?.width?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
                physical?.height?.let { "%.4f".format(java.util.Locale.US, it) }.orEmpty(),
                pixelArray?.let { "${it.width}x${it.height}" }.orEmpty(),
                active?.let { "${it.width()}x${it.height()}" }.orEmpty(),
                orientation.toString(),
                cfa?.toString().orEmpty(),
            ).joinToString("|"),
        )

        return CameraRoute(
            openId = openId,
            physicalId = physicalId,
            kind = kind,
            facing = facing,
            focalLengthsMm = focal,
            sensorPhysicalWidthMm = physical?.width,
            sensorPhysicalHeightMm = physical?.height,
            sensorOrientation = orientation,
            previewSizes = previewSizes.sortedByDescending { it.width.toLong() * it.height },
            rawSizes = rawSizes.sortedByDescending { it.width.toLong() * it.height },
            supportsManualSensor = manual,
            cfa = cfa,
            blackLevels = blackLevels,
            whiteLevel = white,
            opticalFingerprint = opticalFingerprint,
        )
    }

    private fun opticalLabel(route: CameraRoute): String {
        val focal = representativeFocal(route)
        val sensorWidth = route.sensorPhysicalWidthMm
        return if (focal > 0f && sensorWidth != null && sensorWidth > 0f) {
            val equivalent = (focal * 36f / sensorWidth).roundToInt().coerceAtLeast(1)
            when (route.facing) {
                LensFacing.FRONT -> "Front ${equivalent}mm"
                LensFacing.BACK -> "${equivalent}mm"
                LensFacing.EXTERNAL -> "External ${equivalent}mm"
                LensFacing.UNKNOWN -> "Lens ${equivalent}mm"
            }
        } else {
            when (route.facing) {
                LensFacing.FRONT -> "Front"
                LensFacing.BACK -> "Back"
                LensFacing.EXTERNAL -> "External"
                LensFacing.UNKNOWN -> "Camera"
            }
        }
    }

    private fun deduplicateLabels(input: List<CanonicalLens>): List<CanonicalLens> {
        val counts = mutableMapOf<String, Int>()
        return input.map { lens ->
            val n = (counts[lens.label] ?: 0) + 1
            counts[lens.label] = n
            if (n == 1) lens else lens.copy(label = "${lens.label} $n")
        }
    }

    private fun representativeFocal(route: CameraRoute): Float = route.focalLengthsMm.firstOrNull() ?: Float.MAX_VALUE

    private fun facingOrder(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    companion object {
        fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

        fun publicIdSetHash(ids: List<String>): String = stableId(ids.sorted().joinToString("\u0000"))
    }
}
