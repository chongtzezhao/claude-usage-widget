package com.usage.claudewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiveHourSampleBufferTest {

    private val start = 1_700_000_000_000L
    private val minute = 60 * 1000L
    private val reset = start + 5 * 60 * minute

    private fun sample(atMinutes: Long, pct: Float) =
        FiveHourSample(start + atMinutes * minute, pct)

    private fun merge(
        existing: List<FiveHourSample>,
        atMinutes: Long,
        pct: Float,
        previousReset: Long = reset,
        resetAt: Long = reset,
    ) = mergeFiveHourSamples(
        existing = existing,
        previousResetAtEpochMs = previousReset,
        atEpochMs = start + atMinutes * minute,
        pct = pct,
        resetAtEpochMs = resetAt,
    )

    /** A slow refresh is normal, not a broken cadence: it must not wipe the buffer. */
    @Test
    fun keepsSamplesAcrossSlowRefreshes() {
        var samples = merge(emptyList(), 0, 10f)
        samples = merge(samples, 50, 12f)
        samples = merge(samples, 100, 15f)

        assertEquals(3, samples.size)
        assertEquals(listOf(10f, 12f, 15f), samples.map { it.pct })
    }

    @Test
    fun startsOverWhenTheWindowResets() {
        val existing = listOf(sample(0, 30f), sample(60, 40f))

        val next = merge(existing, atMinutes = 65, pct = 3f, resetAt = reset + 5 * 60 * minute)

        assertEquals(1, next.size)
        assertEquals(3f, next.single().pct, 0.001f)
    }

    /** Codex can rebuild `reset_at` from `reset_after_seconds`; a few seconds of drift is the same window. */
    @Test
    fun toleratesSmallResetDrift() {
        val existing = listOf(sample(0, 30f))

        val next = merge(existing, atMinutes = 60, pct = 40f, resetAt = reset + 30 * 1000L)

        assertEquals(2, next.size)
    }

    @Test
    fun keepsBufferOnRoundingDip() {
        val existing = listOf(sample(0, 42f))

        val next = merge(existing, atMinutes = 60, pct = 41.5f)

        assertEquals(2, next.size)
    }

    @Test
    fun startsOverOnRealDrop() {
        val existing = listOf(sample(0, 42f), sample(60, 45f))

        val next = merge(existing, atMinutes = 120, pct = 20f)

        assertEquals(1, next.size)
        assertEquals(20f, next.single().pct, 0.001f)
    }

    @Test
    fun replacesNearDuplicate() {
        val existing = listOf(sample(0, 10f))

        val next = merge(existing, atMinutes = 3, pct = 11f)

        assertEquals(1, next.size)
        assertEquals(11f, next.single().pct, 0.001f)
        assertEquals(start + 3 * minute, next.single().atEpochMs)
    }

    @Test
    fun keepsAtMostCountReadings() {
        var samples = emptyList<FiveHourSample>()
        for (i in 0..4) samples = merge(samples, i * 10L, 10f + i)

        assertEquals(FiveHourSampling.COUNT, samples.size)
        // The oldest readings fall off first.
        assertEquals(start + 10 * minute, samples.first().atEpochMs)
    }

    @Test
    fun dropsReadingsOlderThanTheSpan() {
        val existing = listOf(sample(0, 10f), sample(120, 20f))

        val next = merge(existing, atMinutes = 180, pct = 26f)

        assertEquals(2, next.size)
        assertEquals(start + 120 * minute, next.first().atEpochMs)
    }

    @Test
    fun clearsOnInvalidInput() {
        assertTrue(mergeFiveHourSamples(emptyList(), 0L, 0L, 10f, reset).isEmpty())
        assertTrue(mergeFiveHourSamples(emptyList(), 0L, start, -1f, reset).isEmpty())
        assertTrue(mergeFiveHourSamples(emptyList(), 0L, start, 10f, 0L).isEmpty())
    }
}
