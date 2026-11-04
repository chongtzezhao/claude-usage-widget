package com.usage.claudewidget.widget

import com.usage.claudewidget.data.FiveHourSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiveHourRateTest {

    private val start = 1_700_000_000_000L
    private val minute = 60 * 1000L
    private val fifteenMin = 15 * minute

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
            resetAtEpochMs = start + 2 * fifteenMin + 3 * 60 * minute,
            now = start + 2 * fifteenMin,
        )

        assertEquals(20f, rate!!.pctPerHour, 0.01f)
        assertEquals(80, rate.projectedPct)
        assertNull(rate.capsInMinutes)
    }

    /**
     * Regression test for the stuck "collecting" line: a real device refreshes
     * roughly hourly, so two readings an hour apart must produce a rate.
     */
    @Test
    fun computesRateFromHourlySamples() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 60 * minute, 16f),
        )

        val rate = computeFiveHourRate(
            samples = samples,
            usedPct = 16,
            resetAtEpochMs = start + 60 * minute + 3 * 60 * minute,
            now = start + 60 * minute,
        )

        assertEquals(6f, rate!!.pctPerHour, 0.01f)
        assertEquals(34, rate.projectedPct)
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
            resetAtEpochMs = start + 2 * fifteenMin + 60 * minute,
            now = start + 2 * fifteenMin,
        )

        assertEquals(40f, rate!!.pctPerHour, 0.01f)
        assertEquals(100, rate.projectedPct)
        assertEquals(45L, rate.capsInMinutes)
    }

    @Test
    fun ignoresReadingsOlderThanTheSpan() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 120 * minute, 20f),
            FiveHourSample(start + 180 * minute, 26f),
        )

        val rate = computeFiveHourRate(
            samples = samples,
            usedPct = 26,
            resetAtEpochMs = start + 180 * minute + 3 * 60 * minute,
            now = start + 180 * minute,
        )

        // The 3h-old reading is outside the 2h span, so the slope uses the last hour.
        assertEquals(6f, rate!!.pctPerHour, 0.01f)
        assertEquals(44, rate.projectedPct)
    }

    @Test
    fun rejectsTooFewSamples() {
        assertNull(
            computeFiveHourRate(
                samples = listOf(FiveHourSample(start, 10f)),
                usedPct = 10,
                resetAtEpochMs = start + 3 * 60 * minute,
                now = start,
            )
        )
    }

    @Test
    fun rejectsTooShortSpan() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 5 * minute, 12f),
        )

        assertNull(
            computeFiveHourRate(
                samples = samples,
                usedPct = 12,
                resetAtEpochMs = start + 3 * 60 * minute,
                now = start + 5 * minute,
            )
        )
    }

    @Test
    fun rejectsOverlongSpan() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 180 * minute, 30f),
        )

        assertNull(
            computeFiveHourRate(
                samples = samples,
                usedPct = 30,
                resetAtEpochMs = start + 4 * 60 * minute,
                now = start + 180 * minute,
            )
        )
    }

    @Test
    fun rejectsStaleSamples() {
        val samples = listOf(
            FiveHourSample(start, 10f),
            FiveHourSample(start + 60 * minute, 16f),
        )
        val now = start + 60 * minute + 3 * 60 * minute

        assertNull(
            computeFiveHourRate(
                samples = samples,
                usedPct = 16,
                resetAtEpochMs = now + 3 * 60 * minute,
                now = now,
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
            resetAtEpochMs = start + 2 * fifteenMin + 2 * 60 * minute,
            now = start + 2 * fifteenMin,
        )

        assertEquals(0f, rate!!.pctPerHour, 0.01f)
        assertEquals(42, rate.projectedPct)
        assertNull(rate.capsInMinutes)
    }
}
