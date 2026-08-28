package com.sahidcode404.camera.core.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBurstTimeoutPolicyTest {
    @Test
    fun fastDayBurstUsesSafetyFloor() {
        assertEquals(
            8_000L,
            RawBurstTimeoutPolicy.timeoutMs(List(8) { 5_000_000L }, 8),
        )
    }

    @Test
    fun longerNightBurstGetsMoreTime() {
        val day = RawBurstTimeoutPolicy.timeoutMs(List(8) { 5_000_000L }, 8)
        val night = RawBurstTimeoutPolicy.timeoutMs(List(12) { 250_000_000L }, 12)
        assertTrue(night > day)
    }

    @Test
    fun pathologicalPlanIsBounded() {
        assertEquals(
            60_000L,
            RawBurstTimeoutPolicy.timeoutMs(List(16) { 30_000_000_000L }, 16),
        )
    }
}
