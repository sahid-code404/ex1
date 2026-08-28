package com.sahidcode404.camera.core.raw

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DngOutputWriter {
    fun write(
        context: Context,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        size: Size,
        packedRaw: ByteBuffer,
        orientation: Int,
    ): Uri {
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.dng"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                context.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                    DngCreator(characteristics, captureResult).use { dng ->
                        dng.setOrientation(orientation)
                        packedRaw.rewind()
                        dng.writeByteBuffer(stream, size, packedRaw, 0L)
                    }
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw t
            }
        } else {
            val publicAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val directory = if (publicAllowed) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            } else {
                File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)), "Camera")
            }
            check(directory.exists() || directory.mkdirs()) { "Cannot create Camera output directory" }
            val file = File(directory, name)
            FileOutputStream(file).use { stream ->
                DngCreator(characteristics, captureResult).use { dng ->
                    dng.setOrientation(orientation)
                    packedRaw.rewind()
                    dng.writeByteBuffer(stream, size, packedRaw, 0L)
                }
            }
            if (publicAllowed) {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/x-adobe-dng"), null)
            }
            Uri.fromFile(file)
        }
    }
}
