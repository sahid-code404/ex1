package com.sahidcode404.camera.core.raw

import android.app.ActivityManager
import android.content.Context
import android.util.Size

object BurstMemoryBudget {
    fun maxFrames(context: Context, rawSize: Size, requestedMax: Int): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryClassBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
        val budget = (memoryClassBytes / 4L).coerceIn(64L * 1024L * 1024L, 256L * 1024L * 1024L)
        val bytesPerFrame = rawSize.width.toLong() * rawSize.height * 2L
        val possible = (budget / bytesPerFrame.coerceAtLeast(1L)).toInt().coerceAtLeast(2)
        return minOf(requestedMax.coerceIn(2, 16), possible.coerceAtMost(16))
    }
}
