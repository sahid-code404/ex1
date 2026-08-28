package com.sahidcode404.camera.core.discovery

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
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

    /**
     * First-install bootstrap path. It intentionally inspects only public Camera2 routes and skips
     * RAW stream enumeration plus logical/physical AUX expansion. The goal is to get one credible
     * preview on screen before the expensive topology pass.
     */
    fun discoverStartupSeed(): List<CanonicalLens> {
        val ids = boundedPublicIds()
        return ids.mapNotNull { id ->
            readRoute(
                openId = id,
                physicalId = null,
                kind = RouteKind.PUBLIC,
                includeRawStreams = false,
            )
        }
            .map(::lensForSingleRoute)
            .sortedWith(
                compareBy<CanonicalLens>(
                    { facingOrder(it.facing) },
                    { representativeFocal(it.preferredRoute) },
                    { it.id },
                ),
            )
            .let(::deduplicateLabels)
    }

    /** Full post-first-frame topology and RAW capability discovery. */
    fun discover(): List<CanonicalLens> {
        val publicIds = boundedPublicIds()
        val publicRoutes = publicIds.mapNotNull { id ->
            readRoute(
                openId = id,
                physicalId = null,
                kind = RouteKind.PUBLIC,
                includeRawStreams = true,
            )
        }
        val physicalByParent = linkedMapOf<String, MutableList<CameraRoute>>()

        if (Build.VERSION.SDK_INT >= 28) {
            for (parent in publicIds) {
                val characteristics = runCatching { manager.getCameraCharacteristics(parent) }.getOrNull() ?: continue
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue

                val physicalIds = characteristics.physicalCameraIds
                if (physicalIds.size > MAX_PHYSICAL_IDS_PER_LOGICAL) continue
                for (physicalId in physicalIds) {
                    val route = readRoute(
                        openId = parent,
                        physicalId = physicalId,
                        kind = RouteKind.LOGICAL_PHYSICAL,
                        includeRawStreams = true,
                    ) ?: continue
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
                    // Keep the logical/public route as a capability fallback. This is especially
                    // important on devices whose physical route rejects a requested stream combo.
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

    private fun boundedPublicIds(): List<String> {
        val ids = publicIds()
        check(ids.size <= MAX_PUBLIC_IDS) { "Camera2 advertised more than $MAX_PUBLIC_IDS public IDs" }
        return ids
    }

    private fun lensForSingleRoute(route: CameraRoute) = CanonicalLens(
        id = stableId("lens|${route.opticalFingerprint}|${route.routeKey}"),
        label = opticalLabel(route),
        facing = route.facing,
        routes = listOf(route),
    )

    private fun readRoute(
        openId: String,
        physicalId: String?,
        kind: RouteKind,
        includeRawStreams: Boolean,
    ): CameraRoute? {
        val metadataId = physicalId ?: openId
        val c = runCatching { manager.getCameraCharacteristics(metadataId) }.getOrNull() ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previewSizes = runCatching { map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .distinct()
            .take(MAX_PREVIEW_SIZES + 1)
            .toList()
        if (previewSizes.isEmpty() || previewSizes.size > MAX_PREVIEW_SIZES) return null

        val available = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawAdvertised = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawSizes = if (includeRawStreams && rawAdvertised) {
            runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .asSequence()
                .filter { it.width > 0 && it.height > 0 }
                .distinct()
                .take(MAX_RAW_SIZES + 1)
                .toList()
                .takeIf { it.size <= MAX_RAW_SIZES }
                ?: emptyList()
        } else {
            emptyList()
        }

        val focal = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf())
            .asSequence()
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
            .take(MAX_FOCAL_LENGTHS + 1)
            .toList()
        if (focal.size > MAX_FOCAL_LENGTHS) return null

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
        return if (focal > 0f && focal.isFinite() && sensorWidth != null && sensorWidth > 0f) {
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
        private const val MAX_PUBLIC_IDS = 64
        private const val MAX_PHYSICAL_IDS_PER_LOGICAL = 64
        private const val MAX_FOCAL_LENGTHS = 16
        private const val MAX_PREVIEW_SIZES = 128
        private const val MAX_RAW_SIZES = 64

        fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

        fun publicIdSetHash(ids: List<String>): String = stableId(ids.sorted().joinToString("\u0000"))
    }
}
