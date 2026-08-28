package com.sahidcode404.camera.core.raw

object RawTimestampPairingPolicy {
    const val DEFAULT_TOLERANCE_NS: Long = 2_000_000L

    fun match(
        timestamp: Long,
        candidates: Collection<Long>,
        toleranceNs: Long = DEFAULT_TOLERANCE_NS,
    ): Long? {
        if (candidates.contains(timestamp)) return timestamp
        if (toleranceNs < 0L) return null

        var best: Long? = null
        var bestDistance = Long.MAX_VALUE
        var tied = false
        for (candidate in candidates) {
            val distance = if (candidate >= timestamp) candidate - timestamp else timestamp - candidate
            when {
                distance < bestDistance -> {
                    best = candidate
                    bestDistance = distance
                    tied = false
                }
                distance == bestDistance -> tied = true
            }
        }
        return best?.takeIf { !tied && bestDistance <= toleranceNs }
    }
}
