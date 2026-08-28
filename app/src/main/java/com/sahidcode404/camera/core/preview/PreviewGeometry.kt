package com.sahidcode404.camera.core.preview

import android.graphics.Matrix
import android.graphics.RectF
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.sahidcode404.camera.core.model.AspectMode
import com.sahidcode404.camera.core.model.LensFacing
import kotlin.math.abs

object PreviewGeometry {
    fun chooseSize(
        candidates: List<Size>,
        viewWidth: Int,
        viewHeight: Int,
        sensorRatio: Float?,
        aspect: AspectMode,
    ): Size {
        require(candidates.isNotEmpty())
        val viewRatio = if (viewWidth > 0 && viewHeight > 0) maxOf(viewWidth, viewHeight).toFloat() / minOf(viewWidth, viewHeight) else 4f / 3f
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

    fun applyTransform(
        view: TextureView,
        buffer: Size,
        sensorOrientation: Int,
        displayRotation: Int,
        facing: LensFacing,
        mirrorFront: Boolean,
    ) {
        if (view.width == 0 || view.height == 0) return
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = if (facing == LensFacing.FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
        val rotatedW = if (rotation == 90 || rotation == 270) buffer.height.toFloat() else buffer.width.toFloat()
        val rotatedH = if (rotation == 90 || rotation == 270) buffer.width.toFloat() else buffer.height.toFloat()
        val viewRect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val bufferRect = RectF(0f, 0f, rotatedW, rotatedH)
        val matrix = Matrix()
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)
        val scale = maxOf(view.width / rotatedW, view.height / rotatedH)
        matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
        matrix.postRotate(rotation.toFloat(), viewRect.centerX(), viewRect.centerY())
        if (facing == LensFacing.FRONT && mirrorFront) {
            matrix.postScale(-1f, 1f, viewRect.centerX(), viewRect.centerY())
        }
        view.setTransform(matrix)
    }

    fun normalizedRatio(size: Size): Float = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
}
