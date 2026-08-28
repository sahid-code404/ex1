package com.sahidcode404.camera.core.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawTimestampPairingPolicyTest {
    @Test
    fun exactTimestampWins() {
        assertEquals(200L, RawTimestampPairingPolicy.match(200L, listOf(100L, 200L, 300L), 5L))
    }

    @Test
    fun uniqueNearestTimestampWithinToleranceIsAccepted() {
        assertEquals(1_001_000L, RawTimestampPairingPolicy.match(1_000_000L, listOf(900_000L, 1_001_000L), 2_000L))
    }

    @Test
    fun equidistantCandidatesAreRejected() {
        assertNull(RawTimestampPairingPolicy.match(1_000L, listOf(900L, 1_100L), 200L))
    }

    @Test
    fun candidateOutsideToleranceIsRejected() {
        assertNull(RawTimestampPairingPolicy.match(1_000L, listOf(1_500L), 100L))
    }
}
