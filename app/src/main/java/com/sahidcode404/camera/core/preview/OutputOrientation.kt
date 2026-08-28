package com.sahidcode404.camera.core.preview

import android.view.Surface
import com.sahidcode404.camera.core.model.LensFacing

object OutputOrientation {
    // TIFF/EXIF orientation values accepted by DngCreator.setOrientation().
    private const val NORMAL = 1
    private const val FLIP_HORIZONTAL = 2
    private const val ROTATE_180 = 3
    private const val FLIP_VERTICAL = 4
    private const val TRANSPOSE = 5
    private const val ROTATE_90 = 6
    private const val TRANSVERSE = 7
    private const val ROTATE_270 = 8

    fun exifOrientation(sensorOrientation: Int, displayRotation: Int, facing: LensFacing, mirrorFront: Boolean): Int {
        val device = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = if (facing == LensFacing.FRONT) {
            (sensorOrientation + device) % 360
        } else {
            (sensorOrientation - device + 360) % 360
        }
        val mirrored = facing == LensFacing.FRONT && mirrorFront
        return if (!mirrored) {
            when (rotation) {
                90 -> ROTATE_90
                180 -> ROTATE_180
                270 -> ROTATE_270
                else -> NORMAL
            }
        } else {
            when (rotation) {
                90 -> TRANSVERSE
                180 -> FLIP_VERTICAL
                270 -> TRANSPOSE
                else -> FLIP_HORIZONTAL
            }
        }
    }
}
