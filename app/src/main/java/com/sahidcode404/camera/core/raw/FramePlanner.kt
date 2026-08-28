package com.sahidcode404.camera.core.raw

import kotlin.math.ln
import kotlin.math.roundToInt

data class FramePlan(
    val frameCount: Int,
    val evOffsets: List<Float>,
)

object FramePlanner {
    fun plan(
        exposureNs: Long?,
        iso: Int?,
        minFrames: Int,
        maxFrames: Int,
        manualSensor: Boolean,
    ): FramePlan {
        val low = minFrames.coerceIn(2, 16)
        val high = maxFrames.coerceIn(low, 16)
        val exposure = (exposureNs ?: 10_000_000L).coerceAtLeast(100_000L)
        val sensitivity = (iso ?: 100).coerceAtLeast(50)

        // Scene burden is a monotonic proxy, not a vendor-specific lux model.
        val burden = log2(exposure / 8_000_000.0) + log2(sensitivity / 100.0)
        val t = ((burden + 1.0) / 6.0).coerceIn(0.0, 1.0)
        val count = (low + (high - low) * t).roundToInt().coerceIn(low, high)

        val offsets = if (!manualSensor) {
            List(count) { 0f }
        } else {
            buildList(count) {
                // Center-heavy bracket: most frames maximize SNR, a few protect highlights/shadows.
                val pattern = floatArrayOf(0f, 0f, -1.0f, 0f, 1.0f, -2.0f, 0.5f, 0f, -0.5f, 1.5f, -1.5f, 0f, 0.75f, -0.75f, 0f, 0f)
                repeat(count) { add(pattern[it]) }
            }
        }
        return FramePlan(count, offsets)
    }

    private fun log2(value: Double): Double = ln(value.coerceAtLeast(1e-9)) / ln(2.0)
}
