package com.usage.claudewidget.widget

import com.usage.claudewidget.data.FiveHourSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiveHourRateTest {

    private val start = 1_700_000_000_000L
    private val fifteenMin = 15 * 60 * 1000L

    @Test
    fun computesRateAndProjection() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + fifteenMin, 15f),
            FiveHourSample(start + 2 * fifteenMin, 20f),
        )

        val rate = computeFiveHourRate(
            samples = samples,
            usedPct = 20,
            resetAtEpochMs = start + 2 * fifteenMin + 3 * 60 * 60 * 1000L,
            now = start + 2 * fifteenMin,
        )

        assertEquals(20f, rate!!.pctPerHour, 0.01f)
        assertEquals(80, rate.projectedPct)
        assertNull(rate.capsInMinutes)
    }

    @Test
    fun reportsCapsInWhenProjectionHitsTheCap() {
        val samples = listOf(
            FiveHourSample(start, 50f),
            FiveHourSample(start + fifteenMin, 60f),
            FiveHourSample(start + 2 * fifteenMin, 70f),
        )

        val rate = computeFiveHourRate(
            samples = samples,
            usedPct = 70,
            resetAtEpochMs = start + 2 * fifteenMin + 60 * 60 * 1000L,
            now = start + 2 * fifteenMin,
        )

        assertEquals(40f, rate!!.pctPerHour, 0.01f)
        assertEquals(100, rate.projectedPct)
        assertEquals(45L, rate.capsInMinutes)
    }

    @Test
    fun rejectsBrokenCadence() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 10 * 60 * 1000L, 15f),
            FiveHourSample(start + 20 * 60 * 1000L, 20f),
        )

        assertNull(
            computeFiveHourRate(
                samples = samples,
                usedPct = 20,
                resetAtEpochMs = start + 5 * 60 * 60 * 1000L,
                now = start + 20 * 60 * 1000L,
            )
        )
    }

    @Test
    fun rejectsResetAlreadyPassed() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + fifteenMin, 15f),
            FiveHourSample(start + 2 * fifteenMin, 20f),
        )

        assertNull(
            computeFiveHourRate(
                samples = samples,
                usedPct = 20,
                resetAtEpochMs = start + 2 * fifteenMin,
                now = start + 2 * fifteenMin,
            )
        )
    }

    @Test
    fun idleRateProjectsCurrentUsage() {
        val samples = listOf(
            FiveHourSample(start, 42f),
            FiveHourSample(start + fifteenMin, 42f),
            FiveHourSample(start + 2 * fifteenMin, 42f),
        )

        val rate = computeFiveHourRate(
            samples = samples,
            usedPct = 42,
            resetAtEpochMs = start + 2 * fifteenMin + 2 * 60 * 60 * 1000L,
            now = start + 2 * fifteenMin,
        )

        assertEquals(0f, rate!!.pctPerHour, 0.01f)
        assertEquals(42, rate.projectedPct)
        assertNull(rate.capsInMinutes)
    }
}
