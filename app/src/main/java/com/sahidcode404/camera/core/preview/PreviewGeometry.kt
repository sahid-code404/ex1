package com.sahidcode404.camera.core.preview

import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.sahidcode404.camera.core.model.AspectMode
import com.sahidcode404.camera.core.model.LensFacing
import kotlin.math.abs
import kotlin.math.max

object PreviewGeometry {
    fun chooseSize(
        candidates: List<Size>,
        viewWidth: Int,
        viewHeight: Int,
        sensorRatio: Float?,
        aspect: AspectMode,
    ): Size {
        require(candidates.isNotEmpty())
        val viewRatio = if (viewWidth > 0 && viewHeight > 0) {
            maxOf(viewWidth, viewHeight).toFloat() / minOf(viewWidth, viewHeight)
        } else {
            4f / 3f
        }
        val target = when (aspect) {
            AspectMode.SENSOR -> sensorRatio ?: 4f / 3f
            AspectMode.ONE_ONE -> 1f
            AspectMode.FOUR_THREE -> 4f / 3f
            AspectMode.SIXTEEN_NINE -> 16f / 9f
            AspectMode.FULL -> viewRatio
        }
        val bounded = candidates.filter { it.width <= 3840 && it.height <= 2160 }.ifEmpty { candidates }
        return bounded.minWithOrNull(
            compareBy<Size>(
                { abs(normalizedRatio(it) - target) },
                { abs(it.width.toLong() * it.height - 1920L * 1080L) },
            ),
        ) ?: candidates.first()
    }

    /**
     * Applies the same center-crop geometry model used by CamX: solve rotation in final display
     * coordinates, scale after axis swap, then mirror the final front-camera image. The child keeps
     * the exact stream size so SurfaceTexture never gets stretched into an unrelated aspect ratio.
     */
    fun applyTextureTransform(
        view: TextureView,
        buffer: Size,
        sensorOrientation: Int,
        displayRotation: Int,
        facing: LensFacing,
        mirrorFront: Boolean,
    ) {
        applyViewTransform(view, buffer, sensorOrientation, displayRotation, facing, mirrorFront)
    }

    fun applyViewTransform(
        view: View,
        stream: Size,
        sensorOrientation: Int,
        displayRotation: Int,
        facing: LensFacing,
        mirrorFront: Boolean,
    ) {
        val parent = view.parent as? View ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val rotation = rotationDegrees(sensorOrientation, displayRotation, facing)
        val swapAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapAxes) stream.height else stream.width
        val rotatedHeight = if (swapAxes) stream.width else stream.height
        val scale = max(
            parent.width.toDouble() / rotatedWidth.toDouble(),
            parent.height.toDouble() / rotatedHeight.toDouble(),
        ).toFloat()
        val renderedWidth = rotatedWidth * scale
        val renderedHeight = rotatedHeight * scale
        val translatedX = (parent.width - renderedWidth) / 2f
        val translatedY = (parent.height - renderedHeight) / 2f
        val translationX = translatedX + (renderedWidth - stream.width) / 2f
        val translationY = translatedY + (renderedHeight - stream.height) / 2f
        val mirror = facing == LensFacing.FRONT && mirrorFront
        val mirrorLocalX = mirror && !swapAxes
        val mirrorLocalY = mirror && swapAxes

        val params = view.layoutParams
        if (params.width != stream.width || params.height != stream.height) {
            params.width = stream.width
            params.height = stream.height
            view.layoutParams = params
        }
        view.pivotX = stream.width / 2f
        view.pivotY = stream.height / 2f
        view.rotation = rotation.toFloat()
        view.scaleX = if (mirrorLocalX) -scale else scale
        view.scaleY = if (mirrorLocalY) -scale else scale
        view.translationX = translationX
        view.translationY = translationY
    }

    fun rotationDegrees(sensorOrientation: Int, displayRotation: Int, facing: LensFacing): Int {
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return when (facing) {
            LensFacing.FRONT -> Math.floorMod(sensorOrientation + deviceDegrees, 360)
            LensFacing.BACK, LensFacing.EXTERNAL, LensFacing.UNKNOWN -> Math.floorMod(sensorOrientation - deviceDegrees, 360)
        }
    }

    fun normalizedRatio(size: Size): Float = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
}
