package com.example.privacyguard

import com.example.privacyguard.data.SafeFile
import com.example.privacyguard.logic.EnrollFeedback
import com.example.privacyguard.logic.EnrollStep
import com.example.privacyguard.logic.EnrollUpdate
import com.example.privacyguard.logic.EnrollmentMachine
import com.example.privacyguard.logic.FrameRateMeter
import com.example.privacyguard.logic.PoseSample
import com.example.privacyguard.logic.PoseThresholds
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SIMULATED users only. These tests prove the state machine logic (neutral pose, hysteresis, hold time,
 * recovery). They do NOT prove that real ML Kit angles on a real phone behave like this.
 */
private class SimulatedUser(
    private val neutralYaw: Float = 0f,
    private val neutralPitch: Float = 0f,
    /** Which sign the user's "first side" has in ML Kit's angles (+1 or -1). */
    private val sideSign: Int = 1,
    private val turnDegrees: Float = 22f,
    private val tiltDegrees: Float = 13f,
    private val speedDegPerSecond: Float = 60f,
    private val reactionMs: Long = 600,
    private val noiseDegrees: Float = 1.5f,
    private val fps: Int = 10,
    seed: Long = 7,
) {
    private val random = Random(seed)
    var yaw = neutralYaw
    var pitch = neutralPitch
    var now = 1_000L
    private var lastStep: EnrollStep? = null
    private var stepSince = now
    var captures = 0

    /** Goal angles for a step. UP/DOWN and LEFT/RIGHT go to opposite sides. */
    private fun goal(step: EnrollStep): Pair<Float, Float> = when (step) {
        EnrollStep.FRONT -> neutralYaw to neutralPitch
        EnrollStep.LEFT -> (neutralYaw + sideSign * turnDegrees) to neutralPitch
        EnrollStep.RIGHT -> (neutralYaw - sideSign * turnDegrees) to neutralPitch
        EnrollStep.UP -> neutralYaw to (neutralPitch + sideSign * tiltDegrees)
        EnrollStep.DOWN -> neutralYaw to (neutralPitch - sideSign * tiltDegrees)
    }

    private fun noise() = (random.nextGaussian() * noiseDegrees * 0.5).toFloat().coerceIn(-noiseDegrees, noiseDegrees)

    fun pose(size: Float = 0.45f, cx: Float = 0.5f, cy: Float = 0.5f, roll: Float = 0f) =
        PoseSample(yaw + noise(), pitch + noise(), roll, size, cx, cy)

    /** One camera picture: the user moves towards the goal of the current step, then the machine sees the pose. */
    fun frame(
        m: EnrollmentMachine,
        faceCount: Int = 1,
        poseOverride: PoseSample? = null,
        manual: Boolean = false,
    ): EnrollUpdate {
        now += 1000L / fps
        if (m.step != lastStep) {
            lastStep = m.step
            stepSince = now
        }
        if (now - stepSince >= reactionMs) {
            val (gy, gp) = goal(m.step)
            val maxMove = speedDegPerSecond / fps
            yaw += (gy - yaw).coerceIn(-maxMove, maxMove)
            pitch += (gp - pitch).coerceIn(-maxMove, maxMove)
        }
        val u = m.onFrame(now, faceCount, if (faceCount == 1) poseOverride ?: pose() else null, manual)
        if (u.readyToCapture) {
            m.onCaptured(now)
            captures++
        }
        return u
    }

    /** Runs until the machine finishes or [limitMs] pass. Returns the simulated time used, or null. */
    fun runToEnd(m: EnrollmentMachine, limitMs: Long = 90_000): Long? {
        val start = now
        while (now - start < limitMs) {
            frame(m)
            if (m.isFinished) return now - start
        }
        return null
    }
}

class EnrollmentMachineTest {

    @Test fun idealUserFinishesAllFiveStepsQuickly() {
        val m = EnrollmentMachine()
        val u = SimulatedUser()
        val ms = u.runToEnd(m)
        assertNotNull("must finish", ms)
        assertEquals(5, m.capturedCount)
        assertTrue("took $ms ms of simulated time", ms!! < 30_000)
    }

    @Test fun phoneHeldLowWithNonZeroNeutralPoseStillWorks() {
        // The old absolute rules could not pass step 1 with pitch 16 degrees.
        val m = EnrollmentMachine()
        val u = SimulatedUser(neutralYaw = 6f, neutralPitch = 16f)
        assertNotNull(u.runToEnd(m))
        assertEquals(5, m.capturedCount)
    }

    @Test fun phoneHeldHighWithNegativeNeutralPitchStillWorks() {
        val m = EnrollmentMachine()
        assertNotNull(SimulatedUser(neutralYaw = -5f, neutralPitch = -14f).runToEnd(m))
    }

    @Test fun worksWhenMlKitSignsAreTheOtherWayRound() {
        val m = EnrollmentMachine()
        assertNotNull(SimulatedUser(sideSign = -1).runToEnd(m))
        assertEquals(5, m.capturedCount)
    }

    @Test fun slowUserWithSmallNaturalMovementsStillFinishesInUnderAMinute() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(turnDegrees = 15f, tiltDegrees = 9f, speedDegPerSecond = 12f, reactionMs = 1500, noiseDegrees = 2.5f)
        val ms = u.runToEnd(m)
        assertNotNull(ms)
        assertTrue("took $ms ms", ms!! < 60_000)
    }

    @Test fun veryFastMovementStillNeedsTheHoldTime() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(speedDegPerSecond = 400f, reactionMs = 0, noiseDegrees = 0f)
        var capturedBeforeHold = false
        var lastCaptureAt = 0L
        repeat(200) {
            val before = u.captures
            u.frame(m)
            if (u.captures > before) {
                // two captures can never be closer together than the hold time
                if (lastCaptureAt != 0L && u.now - lastCaptureAt < PoseThresholds().holdMillis) capturedBeforeHold = true
                lastCaptureAt = u.now
            }
        }
        assertFalse(capturedBeforeHold)
        assertTrue(m.isFinished)
    }

    @Test fun oneNoisyFrameDoesNotAdvanceAStep() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        // get through step 1
        while (m.step == EnrollStep.FRONT) u.frame(m)
        // user stays neutral, one single frame shows a 30 degree turn
        val spike = PoseSample(30f, 0f, 0f, 0.45f, 0.5f, 0.5f)
        val before = m.capturedCount
        u.yaw = 0f
        u.frame(m, poseOverride = spike)
        assertEquals(before, m.capturedCount)
        assertEquals(EnrollStep.LEFT, m.step)
    }

    @Test fun faceLostForSeveralSecondsDoesNotResetFinishedSteps() {
        val m = EnrollmentMachine()
        val u = SimulatedUser()
        while (m.step != EnrollStep.RIGHT) u.frame(m)
        val captured = m.capturedCount
        repeat(40) { assertEquals(EnrollFeedback.NO_FACE, u.frame(m, faceCount = 0).feedback) } // 4 seconds without a face
        assertEquals(captured, m.capturedCount)
        assertEquals(EnrollStep.RIGHT, m.step)
        assertNotNull(u.runToEnd(m))
    }

    @Test fun twoFacesBlockProgressUntilOnlyOneIsLeft() {
        val m = EnrollmentMachine()
        val u = SimulatedUser()
        repeat(60) { assertEquals(EnrollFeedback.MULTIPLE_FACES, u.frame(m, faceCount = 2).feedback) }
        assertEquals(0, m.capturedCount)
        assertNotNull(u.runToEnd(m))
    }

    @Test fun faceThatIsTooSmallTooBigRolledOrOffScreenIsRejected() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        assertEquals(EnrollFeedback.TOO_FAR, u.frame(m, poseOverride = u.pose(size = 0.1f)).feedback)
        assertEquals(EnrollFeedback.TOO_CLOSE, u.frame(m, poseOverride = u.pose(size = 0.95f)).feedback)
        assertEquals(EnrollFeedback.HEAD_ROLLED, u.frame(m, poseOverride = u.pose(roll = 40f)).feedback)
        assertEquals(EnrollFeedback.NOT_CENTERED, u.frame(m, poseOverride = u.pose(cx = 0.05f)).feedback)
        assertEquals(0, m.capturedCount)
    }

    @Test fun faceTurnedAwayOnStepOneIsToldToLookStraight() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        val fb = u.frame(m, poseOverride = PoseSample(45f, 0f, 0f, 0.45f, 0.5f, 0.5f)).feedback
        assertEquals(EnrollFeedback.NOT_STRAIGHT, fb)
    }

    @Test fun secondStepOfAPairNeedsTheOppositeSide() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.RIGHT) u.frame(m)
        // user wrongly repeats the first side
        u.yaw = 22f
        var sawWrongWay = false
        repeat(60) {
            u.yaw = 22f
            if (u.frame(m, poseOverride = PoseSample(22f, 0f, 0f, 0.45f, 0.5f, 0.5f)).feedback == EnrollFeedback.WRONG_DIRECTION) sawWrongWay = true
        }
        assertTrue(sawWrongWay)
        assertEquals(EnrollStep.RIGHT, m.step)
    }

    @Test fun tiltingWhileTurningIsToldToKeepTheHeadLevel() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.LEFT) u.frame(m)
        val fb = u.frame(m, poseOverride = PoseSample(22f, 25f, 0f, 0.45f, 0.5f, 0.5f)).feedback
        assertEquals(EnrollFeedback.OFF_AXIS, fb)
    }

    @Test fun hysteresisKeepsThePoseWhenTheAngleWobblesAroundTheLimit() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.LEFT) u.frame(m)
        // angle wobbles between 10.5 and 13 degrees: enter is 12, exit is 8
        var captured = false
        for (i in 0 until 40) {
            val yaw = if (i % 2 == 0) 13f else 10.5f
            u.yaw = yaw
            u.frame(m, poseOverride = PoseSample(yaw, 0f, 0f, 0.45f, 0.5f, 0.5f))
            if (m.step != EnrollStep.LEFT) { captured = true; break }
        }
        assertTrue("a wobbling pose must still be accepted", captured)
    }

    @Test fun smallerMovementIsAcceptedOnlyAfterTheRelaxTime() {
        val limits = PoseThresholds()
        val m = EnrollmentMachine(limits)
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.LEFT) u.frame(m)
        val nine = PoseSample(9.5f, 0f, 0f, 0.45f, 0.5f, 0.5f) // between relaxed (8.4) and normal (12)
        u.yaw = 9.5f
        // before the relax time: not enough
        var t0 = u.now
        while (u.now - t0 < limits.relaxAfterMillis - 1_500) {
            u.yaw = 9.5f
            u.frame(m, poseOverride = nine)
        }
        assertEquals(EnrollStep.LEFT, m.step)
        // after the relax time: accepted
        var advanced = false
        repeat(60) {
            u.yaw = 9.5f
            u.frame(m, poseOverride = nine)
            if (m.step != EnrollStep.LEFT) advanced = true
        }
        assertTrue(advanced)
    }

    @Test fun slowHintAndManualCaptureAppearOnTime() {
        val limits = PoseThresholds()
        val m = EnrollmentMachine(limits)
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.LEFT) u.frame(m)
        val neutral = PoseSample(0f, 0f, 0f, 0.45f, 0.5f, 0.5f)
        var last: EnrollUpdate? = null
        val start = u.now
        var slowAt = -1L
        var manualAt = -1L
        while (u.now - start < 20_000) {
            u.yaw = 0f
            last = u.frame(m, poseOverride = neutral)
            if (last.slow && slowAt < 0) slowAt = u.now - start
            if (last.manualCaptureAllowed && manualAt < 0) manualAt = u.now - start
        }
        assertTrue("hint at $slowAt", slowAt in (limits.hintAfterMillis - 200)..(limits.hintAfterMillis + 300))
        assertTrue("manual at $manualAt", manualAt in (limits.manualAfterMillis - 200)..(limits.manualAfterMillis + 300))
        // pressing the manual button now captures, because the face is valid
        val u2 = u.frame(m, poseOverride = neutral, manual = true)
        assertTrue(u2.readyToCapture || m.step != EnrollStep.LEFT)
    }

    @Test fun manualCaptureIsRefusedBeforeTheWaitAndWithoutAFace() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.LEFT) u.frame(m)
        val neutral = PoseSample(0f, 0f, 0f, 0.45f, 0.5f, 0.5f)
        assertFalse(u.frame(m, poseOverride = neutral, manual = true).readyToCapture) // too early
        repeat(170) { u.frame(m, poseOverride = neutral) } // 17 s
        assertFalse(u.frame(m, faceCount = 0, manual = true).readyToCapture)          // no face: never
    }

    @Test fun retryStepKeepsFinishedStepsAndRestartsTheTimer() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        while (m.step != EnrollStep.UP) u.frame(m)
        val captured = m.capturedCount
        repeat(100) { u.frame(m, poseOverride = PoseSample(0f, 0f, 0f, 0.45f, 0.5f, 0.5f)) }
        m.retryStep(u.now)
        assertEquals(EnrollStep.UP, m.step)
        assertEquals(captured, m.capturedCount)
        val after = u.frame(m, poseOverride = PoseSample(0f, 0f, 0f, 0.45f, 0.5f, 0.5f))
        assertTrue(after.stepElapsedMs < 500)
    }

    @Test fun restartGoesBackToStepOne() {
        val m = EnrollmentMachine()
        val u = SimulatedUser()
        while (m.step != EnrollStep.UP) u.frame(m)
        m.restart()
        assertEquals(EnrollStep.FRONT, m.step)
        assertEquals(0, m.capturedCount)
        assertFalse(m.isFinished)
        assertNotNull(SimulatedUser().runToEnd(m))
    }

    @Test fun timeWhileTheAppWasPausedIsNotCounted() {
        val m = EnrollmentMachine()
        val u = SimulatedUser(noiseDegrees = 0f)
        val neutral = PoseSample(0f, 0f, 0f, 0.45f, 0.5f, 0.5f)
        u.frame(m, poseOverride = neutral)
        u.now += 120_000 // two minutes in the background
        val after = u.frame(m, poseOverride = neutral)
        assertTrue("step time ${after.stepElapsedMs}", after.stepElapsedMs < 1_000)
        assertFalse(after.slow)
    }

    @Test fun capturedSamplesFollowTheStepOrder() {
        val m = EnrollmentMachine()
        val u = SimulatedUser()
        val order = mutableListOf<EnrollStep>()
        repeat(2_000) {
            val step = m.step
            val before = m.capturedCount
            u.frame(m)
            if (m.capturedCount > before) order += step
            if (m.isFinished) return@repeat
        }
        assertEquals(listOf(EnrollStep.FRONT, EnrollStep.LEFT, EnrollStep.RIGHT, EnrollStep.UP, EnrollStep.DOWN), order)
    }
}

class FrameRateMeterTest {
    @Test fun measuresPicturesPerSecondAndLatency() {
        val meter = FrameRateMeter()
        for (i in 0..20) meter.record(i * 100L, 80) // 10 pictures per second
        assertEquals(10f, meter.picturesPerSecond, 0.5f)
        assertEquals(80f, meter.averageLatencyMs, 1f)
    }

    @Test fun oldSamplesLeaveTheWindow() {
        val meter = FrameRateMeter(windowMs = 1_000)
        meter.record(0, 50); meter.record(100, 50)
        meter.record(10_000, 50)
        assertEquals(0f, meter.picturesPerSecond, 0.001f) // only one sample left in the window
    }
}

class SafeFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun writesAndReplaces() {
        val target = File(folder.root, "profile.enc")
        assertTrue(SafeFile.writeVerified(target, byteArrayOf(1, 2, 3)) { true })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(SafeFile.writeVerified(target, byteArrayOf(9, 9)) { true })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(9, 9)))
        assertFalse(File(folder.root, "profile.enc.tmp").exists())
    }

    @Test fun failedVerificationKeepsTheOldFileUntouched() {
        val target = File(folder.root, "profile.enc")
        SafeFile.writeVerified(target, byteArrayOf(1, 2, 3)) { true }
        assertFalse(SafeFile.writeVerified(target, byteArrayOf(7, 7, 7)) { false })
        assertTrue("old profile must survive", target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(folder.root, "profile.enc.tmp").exists())
    }

    @Test fun exceptionInVerifyKeepsTheOldFileUntouched() {
        val target = File(folder.root, "profile.enc")
        SafeFile.writeVerified(target, byteArrayOf(1), { true })
        assertFalse(SafeFile.writeVerified(target, byteArrayOf(2)) { error("boom") })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1)))
    }

    @Test fun unwritableFolderReturnsFalse() {
        val missing = File(folder.root, "nope/deeper/profile.enc")
        assertFalse(SafeFile.writeVerified(missing, byteArrayOf(1)) { true })
        assertNull(missing.takeIf { it.exists() })
    }
}
