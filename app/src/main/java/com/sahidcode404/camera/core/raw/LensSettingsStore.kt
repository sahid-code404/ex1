package com.sahidcode404.camera.core.raw

import android.content.Context

data class LensBurstBounds(val minFrames: Int, val maxFrames: Int)

class LensSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lens_capture_settings", Context.MODE_PRIVATE)

    fun bounds(lensId: String): LensBurstBounds {
        val min = prefs.getInt("$lensId.min", 3).coerceIn(2, 16)
        val max = prefs.getInt("$lensId.max", 10).coerceIn(min, 16)
        return LensBurstBounds(min, max)
    }

    fun setBounds(lensId: String, minFrames: Int, maxFrames: Int) {
        val min = minFrames.coerceIn(2, 16)
        val max = maxFrames.coerceIn(min, 16)
        prefs.edit().putInt("$lensId.min", min).putInt("$lensId.max", max).apply()
    }
}
