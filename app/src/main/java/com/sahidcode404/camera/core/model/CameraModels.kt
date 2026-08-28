package com.sahidcode404.camera.core.model

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }
enum class PreviewMode { API, RAW }
enum class AspectMode { SENSOR, ONE_ONE, FOUR_THREE, SIXTEEN_NINE, FULL }
enum class RouteKind { PUBLIC, LOGICAL_PHYSICAL }

data class CameraRoute(
    val openId: String,
    val physicalId: String?,
    val kind: RouteKind,
    val facing: LensFacing,
    val focalLengthsMm: List<Float>,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val sensorOrientation: Int,
    val previewSizes: List<Size>,
    val rawSizes: List<Size>,
    val supportsManualSensor: Boolean,
    val cfa: Int?,
    val blackLevels: IntArray,
    val whiteLevel: Int?,
    val opticalFingerprint: String,
) {
    val supportsRaw: Boolean get() = rawSizes.isNotEmpty()
    val routeKey: String get() = if (physicalId == null) openId else "$openId::$physicalId"
}

data class CanonicalLens(
    val id: String,
    val label: String,
    val facing: LensFacing,
    val routes: List<CameraRoute>,
) {
    val preferredRoute: CameraRoute get() = routes.first()
    val supportsRaw: Boolean get() = routes.any { it.supportsRaw }
}

fun lensFacingOf(value: Int?): LensFacing = when (value) {
    CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
    CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
    CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
    else -> LensFacing.UNKNOWN
}
