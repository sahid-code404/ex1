package com.sahidcode404.camera.core.preview

import android.media.ExifInterface
import android.view.Surface
import com.sahidcode404.camera.core.model.LensFacing

object OutputOrientation {
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
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } else {
            when (rotation) {
                90 -> ExifInterface.ORIENTATION_TRANSVERSE
                180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
                270 -> ExifInterface.ORIENTATION_TRANSPOSE
                else -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
            }
        }
    }
}
