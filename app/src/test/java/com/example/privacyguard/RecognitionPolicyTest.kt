package com.example.privacyguard

import com.example.privacyguard.logic.AlertCooldown
import com.example.privacyguard.logic.CalibrationQuality
import com.example.privacyguard.logic.EnrollmentCheck
import com.example.privacyguard.logic.EventLogFormat
import com.example.privacyguard.logic.EventType
import com.example.privacyguard.logic.FailureMonitor
import com.example.privacyguard.logic.FaceMath
import com.example.privacyguard.logic.FacePose
import com.example.privacyguard.logic.FaceVerdict
import com.example.privacyguard.logic.FrameState
import com.example.privacyguard.logic.GuardEvent
import com.example.privacyguard.logic.OtherPersonDetector
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.logic.TemporalFilter
import com.example.privacyguard.logic.ThresholdCalibrator
import com.example.privacyguard.logic.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A vector that has exactly [cos] cosine similarity with the vector (1,0,0). */
private fun withSimilarity(cos: Float): FloatArray {
    val sin = Math.sqrt((1.0 - cos * cos).coerceAtLeast(0.0)).toFloat()
    return floatArrayOf(cos, sin, 0f)
}

private val OWNER_PROFILE = floatArrayOf(1f, 0f, 0f)
private fun facing(size: Float = 0.3f) = FacePose(size, 0f, 0f)

/** Owner / uncertain / stranger rules (spec: phase B and C). */
class VerdictPolicyTest {
    private val t = Thresholds.forSensitivity(Sensitivity.MEDIUM)
    private val d = OtherPersonDetector(t, OWNER_PROFILE)

    @Test fun thresholdsLeaveAnUncertainZone() {
        assertTrue(t.strangerBelow < t.matchThreshold)
        assertEquals(FaceVerdict.OWNER, d.verdict(t.matchThreshold))
        assertEquals(FaceVerdict.OWNER, d.verdict(0.9f))
        assertEquals(FaceVerdict.UNCERTAIN, d.verdict(t.matchThreshold - 0.01f))
        assertEquals(FaceVerdict.UNCERTAIN, d.verdict(t.strangerBelow))
        assertEquals(FaceVerdict.STRANGER, d.verdict(t.strangerBelow - 0.01f))
    }

    @Test fun ownerOnlyGivesOwnerStateAndNoVote() {
        val r = d.analyze(listOf(facing())) { withSimilarity(0.8f) }
        assertEquals(FrameState.OWNER, r.state)
        assertFalse(r.otherPersonLooking)
    }

    @Test fun confidentStrangerGivesOtherPersonStateAndVote() {
        val r = d.analyze(listOf(facing())) { withSimilarity(0.05f) }
        assertEquals(FrameState.OTHER_PERSON, r.state)
        assertTrue(r.otherPersonLooking)
        assertEquals(1, r.strangerFaces)
    }

    // TEST 5: owner and another person together
    @Test fun ownerAndStrangerTogetherAreJudgedSeparately() {
        val scores = listOf(0.85f, 0.02f)
        val r = d.analyze(listOf(facing(0.4f), facing(0.25f))) { i -> withSimilarity(scores[i]) }
        assertEquals(FrameState.OWNER_AND_OTHER, r.state)
        assertEquals(1, r.ownerFaces)
        assertEquals(1, r.strangerFaces)
        assertTrue(r.otherPersonLooking) // the owner being present does not excuse the stranger
    }

    // TEST 7: poor light / hidden face -> uncertain, no false identity claim
    @Test fun uncertainFaceIsNotClaimedAsOwnerOrStrangerAndDoesNotAlertByDefault() {
        val r = d.analyze(listOf(facing())) { withSimilarity((t.matchThreshold + t.strangerBelow) / 2) }
        assertEquals(FrameState.UNCERTAIN, r.state)
        assertEquals(0, r.ownerFaces)
        assertEquals(0, r.strangerFaces)
        assertFalse(r.otherPersonLooking)
    }

    @Test fun uncertainPolicyCanBeSwitchedToAlert() {
        val strict = OtherPersonDetector(t.copy(alertOnUncertain = true), OWNER_PROFILE)
        val r = strict.analyze(listOf(facing())) { withSimilarity((t.matchThreshold + t.strangerBelow) / 2) }
        assertEquals(FrameState.UNCERTAIN, r.state)
        assertTrue(r.otherPersonLooking)
    }

    // TEST 6: no face
    @Test fun noFaceIsNeverAnAlert() {
        val r = d.analyze(emptyList()) { error("must not be called") }
        assertEquals(FrameState.NO_FACE, r.state)
        assertFalse(r.otherPersonLooking)
    }

    @Test fun facesNotLookingAtTheScreenAreNeverAnAlert() {
        val away = FacePose(0.3f, t.maxHeadAngle + 20f, 0f)
        val small = FacePose(t.minFaceSize / 2, 0f, 0f)
        val r = d.analyze(listOf(away, small)) { error("must not be called") }
        assertEquals(FrameState.NOT_FACING, r.state)
        assertFalse(r.otherPersonLooking)
    }
}

/** TEST 4 and TEST 11 and TEST 8: confirmation, alert storms, face lost and found. */
class AlertFlowTest {
    private val t = Thresholds.forSensitivity(Sensitivity.MEDIUM)

    /** Runs the same steps as the service: detector -> temporal filter -> cooldown. Returns times of alerts. */
    private fun run(frames: List<Pair<Long, Float?>>, cooldownMs: Long = 30_000L): List<Long> {
        val detector = OtherPersonDetector(t, OWNER_PROFILE)
        val filter = TemporalFilter(t.windowSize, t.requiredCount)
        val cooldown = AlertCooldown { cooldownMs }
        val alerts = mutableListOf<Long>()
        for ((time, similarity) in frames) {
            val faces = if (similarity == null) emptyList() else listOf(facing())
            val result = detector.analyze(faces) { withSimilarity(similarity!!) }
            if (filter.add(result.otherPersonLooking) && cooldown.tryAlert(time)) alerts += time
        }
        return alerts
    }

    @Test fun ownerForTenMinutesNeverAlerts() {
        val frames = (0 until 1500).map { it * 400L to 0.75f }
        assertTrue(run(frames).isEmpty())
    }

    @Test fun strangerIsAlertedAfterConfirmationFramesNotOnTheFirstFrame() {
        val frames = (0 until 10).map { it * 400L to 0.05f }
        val alerts = run(frames)
        assertEquals(1, alerts.size)
        assertEquals(t.requiredCount - 1, (alerts[0] / 400L).toInt()) // the 3rd frame with required=3
    }

    @Test fun oneBadFrameOfTheOwnerDoesNotAlert() {
        val frames = listOf(0.8f, 0.8f, 0.8f, 0.05f, 0.8f, 0.8f, 0.8f).mapIndexed { i, s -> i * 400L to s }
        assertTrue(run(frames).isEmpty())
    }

    @Test fun strangerForFiveMinutesGivesOneAlertPerCooldownNotOnePerFrame() {
        val frames = (0 until 750).map { it * 400L to 0.05f } // 300 seconds
        val alerts = run(frames)
        assertEquals(10, alerts.size) // 30 s cooldown -> 300 s / 30 s
        for (i in 1 until alerts.size) assertTrue(alerts[i] - alerts[i - 1] >= 30_000L)
    }

    @Test fun faceLostAndFoundIsNotAnAlertForTheOwner() {
        val frames = ((0 until 5).map { it * 400L to 0.8f } +
            (5 until 15).map { it * 400L to null } +
            (15 until 20).map { it * 400L to 0.8f })
        assertTrue(run(frames).isEmpty())
    }

    @Test fun strangerWhoLeavesAndReturnsAfterCooldownIsAlertedAgain() {
        val away = (10 until 90).map { it * 400L to (null as Float?) }
        val frames = (0 until 10).map { it * 400L to 0.05f } + away + (90 until 100).map { it * 400L to 0.05f }
        assertEquals(2, run(frames).size)
    }
}

/** TEST 2 / 10: enrollment checks (the parts that do not need a camera). */
class EnrollmentCheckTest {
    private fun sample(angleDegrees: Double): FloatArray {
        val a = Math.toRadians(angleDegrees)
        return floatArrayOf(Math.cos(a).toFloat(), Math.sin(a).toFloat(), 0f)
    }

    @Test fun consistentSamplesAreAccepted() {
        val r = EnrollmentCheck.verify(listOf(sample(0.0), sample(10.0), sample(-10.0), sample(15.0), sample(-15.0)))
        assertTrue(r.ok)
        assertNotNull(r.profile)
        assertEquals(1f, FaceMath.cosineSimilarity(r.profile!!, r.profile!!), 1e-5f)
    }

    @Test fun oneSampleOfADifferentPersonIsRejected() {
        val r = EnrollmentCheck.verify(listOf(sample(0.0), sample(5.0), sample(-5.0), sample(8.0), sample(100.0)))
        assertFalse(r.ok)
    }

    @Test fun aSampleOfAnotherFaceIsRejectedWhenTakenNotLater() {
        val first = sample(0.0)
        assertTrue(EnrollmentCheck.isSameFace(first, sample(40.0)))   // turned head: still the same face
        assertFalse(EnrollmentCheck.isSameFace(first, sample(85.0)))  // clearly another face
    }

    @Test fun brokenEmbeddingsAreRejected() {
        assertTrue(EnrollmentCheck.isValidEmbedding(floatArrayOf(1f, 0f, 0f), 3))
        assertFalse(EnrollmentCheck.isValidEmbedding(floatArrayOf(1f, 0f), 3))                 // wrong length
        assertFalse(EnrollmentCheck.isValidEmbedding(floatArrayOf(Float.NaN, 0f, 0f), 3))       // not a number
        assertFalse(EnrollmentCheck.isValidEmbedding(floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f), 3))
        assertFalse(EnrollmentCheck.isValidEmbedding(floatArrayOf(0f, 0f, 0f), 3))              // all zero
        assertFalse(EnrollmentCheck.isValidEmbedding(floatArrayOf(9f, 9f, 9f), 3))              // not normalised
    }

    @Test fun tooFewSamplesAreRejected() {
        val r = EnrollmentCheck.verify(listOf(sample(0.0), sample(1.0)))
        assertFalse(r.ok)
        assertNull(r.profile)
    }
}

/** Threshold calibration from measured scores. These are synthetic numbers: they test the maths, not the model. */
class ThresholdCalibratorTest {
    private fun scores(center: Float, spread: Float, n: Int = 30) =
        List(n) { center - spread + 2 * spread * it / (n - 1) }

    @Test fun separatedGroupsGiveGoodQualityAndThresholdBetweenThem() {
        val r = ThresholdCalibrator.calibrate(scores(0.75f, 0.1f), scores(0.10f, 0.1f))
        assertEquals(CalibrationQuality.GOOD, r.quality)
        assertTrue(r.matchThreshold!! > r.otherHigh!!)
        assertTrue(r.matchThreshold!! < r.ownerLow!!)
        assertTrue(r.uncertainMargin!! <= 0.10f)
        assertTrue(r.matchThreshold!! - r.uncertainMargin!! >= r.otherHigh!! - 1e-4f)
    }

    @Test fun overlappingGroupsAreReportedAsOverlapAndKeepAWideUncertainZone() {
        val r = ThresholdCalibrator.calibrate(scores(0.55f, 0.15f), scores(0.45f, 0.15f))
        assertEquals(CalibrationQuality.OVERLAP, r.quality)
        assertEquals(0.10f, r.uncertainMargin!!, 1e-6f)
    }

    @Test fun ownerOnlyIsFlaggedAndNeverBelowTheFloor() {
        val r = ThresholdCalibrator.calibrate(scores(0.30f, 0.05f), emptyList())
        assertEquals(CalibrationQuality.OWNER_ONLY, r.quality)
        assertTrue(r.matchThreshold!! >= 0.30f)
    }

    @Test fun tooFewOwnerSamplesGiveNoNumbers() {
        val r = ThresholdCalibrator.calibrate(listOf(0.7f, 0.8f), emptyList())
        assertEquals(CalibrationQuality.NOT_ENOUGH_DATA, r.quality)
        assertNull(r.matchThreshold)
    }

    @Test fun percentileIsCorrect() {
        val v = listOf(1f, 2f, 3f, 4f, 5f)
        assertEquals(1f, ThresholdCalibrator.percentile(v, 0f), 1e-6f)
        assertEquals(3f, ThresholdCalibrator.percentile(v, 50f), 1e-6f)
        assertEquals(5f, ThresholdCalibrator.percentile(v, 100f), 1e-6f)
        assertEquals(1.2f, ThresholdCalibrator.percentile(v, 5f), 1e-5f)
    }
}

/** Monitoring must not silently stop while the UI claims protection is active. */
class FailureMonitorTest {
    @Test fun becomesDegradedAfterFiveFailuresInARow() {
        val m = FailureMonitor(5)
        repeat(4) { assertFalse(m.failure()) }
        assertFalse(m.isDegraded)
        assertTrue(m.failure())
        assertTrue(m.isDegraded)
        assertFalse(m.failure()) // reported only once
    }

    @Test fun aSuccessInBetweenResetsTheCount() {
        val m = FailureMonitor(3)
        m.failure(); m.failure(); m.success(); m.failure(); m.failure()
        assertFalse(m.isDegraded)
    }

    @Test fun recoveryIsReported() {
        val m = FailureMonitor(2)
        m.failure(); m.failure()
        assertTrue(m.isDegraded)
        assertTrue(m.success())
        assertFalse(m.isDegraded)
        assertFalse(m.success())
    }
}

class EventLogFormatTest {
    @Test fun roundTripWithAndWithoutScore() {
        val a = GuardEvent(1_700_000_000_000L, EventType.OTHER_PERSON_ALERT, 0.12f)
        val b = GuardEvent(1_700_000_001_000L, EventType.CAMERA_BUSY, null)
        assertEquals(a, EventLogFormat.decode(EventLogFormat.encode(a)))
        assertEquals(b, EventLogFormat.decode(EventLogFormat.encode(b)))
    }

    @Test fun damagedLinesAreIgnored() {
        assertNull(EventLogFormat.decode("garbage"))
        assertNull(EventLogFormat.decode("12|NOT_A_TYPE|"))
        assertNull(EventLogFormat.decode("x|CAMERA_BUSY|"))
        assertNull(EventLogFormat.decode("12|CAMERA_BUSY|abc"))
    }

    @Test fun trimKeepsTheNewestLines() {
        val lines = (1..10).map { "$it" }
        assertEquals(listOf("8", "9", "10"), EventLogFormat.trim(lines, 3))
        assertEquals(lines, EventLogFormat.trim(lines, 20))
    }
}
