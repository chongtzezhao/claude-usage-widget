package com.usage.claudewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEstimateTest {

    private val start = 1_700_000_000_000L
    private val fiveHour = 5 * 60 * 60 * 1000L
    private val fifteenMin = 15 * 60 * 1000L
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
        val calibration = SessionEstimate.fold(null, 40f)!!
        assertEquals(40f, calibration.pointsPerSession, 0f)
        assertEquals(1, calibration.samples)
    }

    @Test
    fun laterSamplesAreWeightedByAlpha() {
        val calibration = SessionEstimate.fold(SessionCalibration(20f, 3), 30f)!!
        assertEquals(0.2f * 30f + 0.8f * 20f, calibration.pointsPerSession, 0.0001f)
        assertEquals(4, calibration.samples)
    }

    @Test
    fun invalidSamplesLeaveTheEwmaUntouched() {
        val previous = SessionCalibration(20f, 3)
        assertEquals(previous, SessionEstimate.fold(previous, Float.NaN))
        assertEquals(previous, SessionEstimate.fold(previous, 0f))
        assertEquals(previous, SessionEstimate.fold(previous, -1f))
        assertEquals(previous, SessionEstimate.fold(previous, 100.5f))
        assertNull(SessionEstimate.fold(null, Float.NaN))
    }

    @Test
    fun aPoisonedEwmaRecoversOnTheNextValidSample() {
        val calibration = SessionEstimate.fold(SessionCalibration(Float.NaN, 3), 40f)!!
        assertEquals(40f, calibration.pointsPerSession, 0f)
        assertEquals(1, calibration.samples)
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
        assertEquals(7.25f, SessionEstimate.fold(null, 7.25f)!!.pointsPerSession, 0f)
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
        // WorkManager batches refreshes to roughly hourly, so the slack allows
        // the last reading to be up to 90 minutes before the reset.
        val slack = SessionEstimate.RESET_SLACK_MS
        assertEquals(90 * 60 * 1000L, slack)
        val late = listOf(
            record(reset5 - slack - fifteenMin, 20f, 10f),
            record(reset5 - slack - 1, 50f, 22f),
        )
        val justInTime = listOf(
            record(reset5 - slack - fifteenMin, 20f, 10f),
            record(reset5 - slack, 50f, 22f),
        )

        assertNull(SessionEstimate.windowSample(late))
        assertEquals(40f, SessionEstimate.windowSample(justInTime)!!, 0f)
    }

    @Test
    fun fiveHourResetDriftWithinToleranceStaysOneWindow() {
        // Reset times move a little between fetches; still the same window.
        // The endpoints sit exactly five minutes apart, the tolerance.
        val drift = FiveHourSampling.RESET_TOLERANCE_MS / 2
        val window = listOf(
            record(reset5 - 2 * fifteenMin, 20f, 10f, fiveReset = reset5 - drift),
            record(reset5 - fifteenMin, 35f, 16f, fiveReset = reset5),
            record(reset5, 50f, 22f, fiveReset = reset5 + drift),
        )

        assertEquals(40f, SessionEstimate.windowSample(window)!!, 0f)
    }

    @Test
    fun fiveHourResetDriftBeyondToleranceIsRejected() {
        // A jump past the tolerance is a different window, not drift.
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(
                reset5,
                50f,
                22f,
                fiveReset = reset5 + FiveHourSampling.RESET_TOLERANCE_MS + 1,
            ),
        )

        assertNull(SessionEstimate.windowSample(window))
    }

    @Test
    fun weeklyResetDriftWithinToleranceDoesNotDisqualifyTheWindow() {
        val drift = FiveHourSampling.RESET_TOLERANCE_MS / 2
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f, weekResetMs = weekReset - drift),
            record(reset5, 50f, 22f, weekResetMs = weekReset + drift),
        )

        assertEquals(40f, SessionEstimate.windowSample(window)!!, 0f)
    }

    @Test
    fun weeklyResetBeyondToleranceDisqualifiesTheWindow() {
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(
                reset5,
                50f,
                22f,
                weekResetMs = weekReset + FiveHourSampling.RESET_TOLERANCE_MS + 1,
            ),
        )

        assertNull(SessionEstimate.windowSample(window))
    }

    @Test
    fun zeroResetRecordsStayRejectedDespiteTheTolerance() {
        val zeroFive = listOf(
            record(reset5 - fifteenMin, 20f, 10f, fiveReset = 0L),
            record(reset5, 50f, 22f, fiveReset = 0L),
        )
        val zeroWeek = listOf(
            record(reset5 - fifteenMin, 20f, 10f, weekResetMs = 0L),
            record(reset5, 50f, 22f, weekResetMs = 0L),
        )

        assertNull(SessionEstimate.windowSample(zeroFive))
        assertNull(SessionEstimate.windowSample(zeroWeek))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f, fiveReset = 0L)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f, weekResetMs = 0L)))
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

    @Test
    fun nanInputsAreSkipped() {
        val nanFive = listOf(
            record(reset5 - fifteenMin, Float.NaN, 10f),
            record(reset5, 50f, 22f),
        )
        val nanWeek = listOf(
            record(reset5 - fifteenMin, 20f, Float.NaN),
            record(reset5, 50f, 22f),
        )

        assertNull(SessionEstimate.windowSample(nanFive))
        assertNull(SessionEstimate.windowSample(nanWeek))
    }

    @Test
    fun impossibleSamplesAreSkipped() {
        // 11 points of 5H progress cannot move the week 12 points.
        val window = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 31f, 22f),
        )

        assertNull(SessionEstimate.windowSample(window))
    }

    @Test
    fun windowsWithUnknownOrMixedResetsAreSkipped() {
        val unknownReset = listOf(
            record(reset5 - fifteenMin, 20f, 10f, fiveReset = 0L),
            record(reset5, 50f, 22f, fiveReset = 0L),
        )
        val mixedReset = listOf(
            record(reset5 - fifteenMin, 20f, 10f),
            record(reset5, 50f, 22f, fiveReset = reset5 + fiveHour),
        )

        assertNull(SessionEstimate.windowSample(unknownReset))
        assertNull(SessionEstimate.windowSample(mixedReset))
    }

    @Test
    fun unusableRecordsAreRejected() {
        assertTrue(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f).copy(atEpochMs = 0L)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f).copy(fiveHourResetMs = 0L)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, 22f).copy(weekResetMs = 0L)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, -1f, 22f)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, -1f)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, Float.NaN, 22f)))
        assertFalse(SessionEstimate.isUsableRecord(record(reset5, 50f, Float.NaN)))
    }

    @Test
    fun nanWeeklyUsageMeansNoEstimate() {
        assertNull(
            SessionEstimate.remainingFullSessions(
                SessionCalibration(20f, 2),
                Float.NaN,
                weekReset,
                start,
            )
        )
    }

    // ---- history codec ----

    @Test
    fun historyCodecRoundTripsFullPrecision() {
        val records = listOf(
            record(start, 12.345678f, 0.1f),
            record(start + fifteenMin, 33.333332f, 7.25f),
        )

        assertEquals(records, SessionEstimate.decodeHistory(SessionEstimate.encodeHistory(records)))
    }

    @Test
    fun malformedHistoryEntriesAreDropped() {
        val decoded = SessionEstimate.decodeHistory("garbage;1:2:3:4:5;;9:8:7:6")

        assertEquals(1, decoded.size)
        assertEquals(UsageRecord(1L, 2f, 3L, 4f, 5L), decoded.first())
    }

    @Test
    fun historyKeepsOnlyTheNewestEntries() {
        val records = (0 until SessionEstimate.HISTORY_LIMIT + 5).map {
            record(start + it * fifteenMin, it.toFloat(), it.toFloat())
        }

        val decoded = SessionEstimate.decodeHistory(SessionEstimate.encodeHistory(records))

        assertEquals(SessionEstimate.HISTORY_LIMIT, decoded.size)
        assertEquals(records[5], decoded.first())
    }
}
