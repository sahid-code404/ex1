package com.sahidcode404.camera.core.raw

object RawBurstTimeoutPolicy {
    private const val MIN_TIMEOUT_MS = 8_000L
    private const val MAX_TIMEOUT_MS = 60_000L
    private const val FALLBACK_EXPOSURE_NS = 10_000_000L
    private const val MAX_REASONABLE_EXPOSURE_NS = 30_000_000_000L

    fun timeoutMs(
        plannedExposureNs: List<Long>,
        frameCount: Int = plannedExposureNs.size,
    ): Long {
        val count = frameCount.coerceIn(1, 32)
        var totalExposureNs = 0L
        repeat(count) { index ->
            val exposure = plannedExposureNs.getOrNull(index) ?: FALLBACK_EXPOSURE_NS
            totalExposureNs += exposure.coerceIn(100_000L, MAX_REASONABLE_EXPOSURE_NS)
        }
        val exposureMs = totalExposureNs / 1_000_000.0
        val transportAndHalMs = count * 250.0
        val estimateMs = exposureMs * 3.0 + transportAndHalMs + 3_000.0
        return estimateMs.toLong().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
