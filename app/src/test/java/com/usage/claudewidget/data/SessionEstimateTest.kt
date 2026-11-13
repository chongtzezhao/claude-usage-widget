package com.usage.claudewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionEstimateTest {

    private val start = 1_700_000_000_000L
    private val fiveHour = 5 * 60 * 60 * 1000L
    private val fifteenMin = 15 * 60 * 1000L
    private val fortyFiveMin = 45 * 60 * 1000L
    private val reset5 = start + fiveHour
    private val weekReset = start + 4 * 24 * fiveHour

    /** Readings land late in the 5H window unless a test says otherwise. */
    private fun record(
        at: Long,
        fivePct: Float,
        weekPct: Float,
        fiveReset: Long = reset5,
        weekResetMs: Long = weekReset,
    ) = UsageRecord(at, fivePct, fiveReset, weekPct, weekResetMs)

    // ---- EWMA ----

    @Test
    fun firstSampleInitialisesTheEwma() {
        val calibration = SessionEstimate.fold(null, 40f)
        assertEquals(40f, calibration.pointsPerSession, 0f)
        assertEquals(1, calibration.samples)
    }

    @Test
    fun laterSamplesAreWeightedByAlpha() {
        val calibration = SessionEstimate.fold(SessionCalibration(20f, 3), 30f)
        assertEquals(0.2f * 30f + 0.8f * 20f, calibration.pointsPerSession, 0.0001f)
        assertEquals(4, calibration.samples)
    }

    // ---- window calibration ----

    @Test
    fun windowSampleFromFiveHourAndWeeklyProgress() {
        // 30 points of 5H usage moved the week 12 points: 100 * 12 / 30.
        val window = listOf(
            record(reset5 - 2 * fifteenMin, 20f, 10f),
            record(reset5 - fifteenMin, 35f, 16f),
            record(reset5, 50f, 22f),
        )

        assertEquals(40f, SessionEstimate.windowSample(window)!!, 0f)
    }

    @Test
    fun windowSampleKeepsFullFloatPrecision() {
        // A window yielding 7.25 must not collapse to 7.
        val window = listOf(
            record(reset5 - fifteenMin, 0f, 0f),
            record(reset5, 100f, 7.25f),
        )

        assertEquals(7.25f, SessionEstimate.windowSample(window)!!, 0f)
        assertEquals(7.25f, SessionEstimate.fold(null, 7.25f).pointsPerSession, 0f)
    }

    @Test
    fun singleRecordWindowIsSkipped() {
        assertNull(SessionEstimate.windowSample(listOf(record(reset5, 20f, 10f))))
        assertNull(SessionEstimate.windowSample(emptyList()))
    }

    @Test
    fun tooLittleFiveHourProgressIsSkipped() {
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 21f, 20f),
        )

        assertNull(SessionEstimate.windowSample(window))
    }

    @Test
    fun flatOrFallingWeeklyUsageIsSkipped() {
        val flat = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 50f, 10f),
        )
        val falling = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 50f, 8f),
        )

        assertNull(SessionEstimate.windowSample(flat))
        assertNull(SessionEstimate.windowSample(falling))
    }

    @Test
    fun weeklyResetInsideTheWindowIsSkipped() {
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 50f, 22f, weekResetMs = weekReset + fiveHour),
        )

        assertNull(SessionEstimate.windowSample(window))
    }

    @Test
    fun missingWeeklyUsageIsSkipped() {
        val missingFirst = listOf(
            record(reset5 - fifteenMin, 20f, -1f),
            record(reset5, 50f, 22f),
        )
        val missingLast = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 50f, -1f),
        )

        assertNull(SessionEstimate.windowSample(missingFirst))
        assertNull(SessionEstimate.windowSample(missingLast))
    }

    @Test
    fun windowEndingLongBeforeTheFiveHourResetIsSkipped() {
        val late = listOf(
            record(reset5 - 3 * fifteenMin, 20f, 10f),
            record(reset5 - fortyFiveMin - 1, 50f, 22f),
        )
        val justInTime = listOf(
            record(reset5 - 3 * fifteenMin, 20f, 10f),
            record(reset5 - fortyFiveMin, 50f, 22f),
        )

        assertNull(SessionEstimate.windowSample(late))
        assertEquals(40f, SessionEstimate.windowSample(justInTime)!!, 0f)
    }

    // ---- remainingFullSessions ----

    @Test
    fun noCalibrationMeansNoEstimate() {
        assertNull(SessionEstimate.remainingFullSessions(null, 50f, weekReset, start))
        assertNull(
            SessionEstimate.remainingFullSessions(
                SessionCalibration(20f, 0),
                50f,
                weekReset,
                start,
            )
        )
    }

    @Test
    fun unusableWeeklyWindowMeansNoEstimate() {
        val calibration = SessionCalibration(20f, 2)

        assertNull(SessionEstimate.remainingFullSessions(calibration, -1f, weekReset, start))
        assertNull(SessionEstimate.remainingFullSessions(calibration, 50f, start, start))
        assertNull(SessionEstimate.remainingFullSessions(calibration, 50f, start - 1, start))
    }

    @Test
    fun degenerateCalibrationMeansNoEstimate() {
        assertNull(
            SessionEstimate.remainingFullSessions(
                SessionCalibration(Float.NaN, 1),
                50f,
                weekReset,
                start,
            )
        )
        assertNull(
            SessionEstimate.remainingFullSessions(
                SessionCalibration(0f, 1),
                50f,
                weekReset,
                start,
            )
        )
        assertNull(
            SessionEstimate.remainingFullSessions(
                SessionCalibration(-5f, 1),
                50f,
                weekReset,
                start,
            )
        )
    }

    @Test
    fun quotaLimitsTheEstimate() {
        // 50 weekly points left at 25 per session: 2 sessions, week still long.
        val estimate = SessionEstimate.remainingFullSessions(
            SessionCalibration(25f, 4),
            50f,
            start + 40 * fiveHour,
            start,
        )

        assertEquals(2f, estimate!!, 0f)
    }

    @Test
    fun timeLeftLimitsTheEstimate() {
        // 90 points would allow 4.5 sessions but only 10 hours remain: 2.
        val estimate = SessionEstimate.remainingFullSessions(
            SessionCalibration(20f, 4),
            10f,
            start + 10 * 60 * 60 * 1000L,
            start,
        )

        assertEquals(2f, estimate!!, 0f)
    }

    @Test
    fun overFullWeekClampsToZero() {
        val estimate = SessionEstimate.remainingFullSessions(
            SessionCalibration(20f, 4),
            120f,
            start + 40 * fiveHour,
            start,
        )

        assertEquals(0f, estimate!!, 0f)
    }

    @Test
    fun estimateIsNotRoundedOrFloored() {
        // 3 weekly points left at 1.25 each: 2.4 sessions.
        val estimate = SessionEstimate.remainingFullSessions(
            SessionCalibration(1.25f, 2),
            97f,
            start + 40 * fiveHour,
            start,
        )

        assertEquals(2.4f, estimate!!, 0.0001f)
    }
}
