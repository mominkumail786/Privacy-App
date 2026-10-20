package com.example.privacyguard.logic

import kotlin.math.abs

/** One measurement of the (single) visible face in one camera picture. Angles are in degrees, from ML Kit. */
data class PoseSample(
    /** Head turn left/right (ML Kit headEulerAngleY). */
    val yaw: Float,
    /** Head tilt up/down (ML Kit headEulerAngleX). */
    val pitch: Float,
    /** Head roll (ML Kit headEulerAngleZ). */
    val roll: Float,
    /** Face width / picture width. */
    val sizeRatio: Float,
    /** Face centre in the picture, 0..1 from the left. */
    val centerX: Float,
    /** Face centre in the picture, 0..1 from the top. */
    val centerY: Float,
)

/**
 * All numbers of the enrollment movement detection. They are plain values so they can be tuned.
 *
 * Why these values (honest note): the angles are RELATIVE to the user's own neutral pose, which is measured in
 * step 1. A comfortable head turn is roughly 20-30 degrees and a comfortable nod 10-20 degrees, while ML Kit's
 * angle noise is a few degrees. So we ask for about half of a comfortable movement. These defaults were tested
 * with simulated users only; they were NOT calibrated on a real phone. Check the live numbers on the enrollment screen.
 */
data class PoseThresholds(
    /** Head turn (yaw) needed, in degrees away from the neutral pose, to count as "turned". */
    val turnEnter: Float = 12f,
    /** The turn only counts as "released" when it falls below this (hysteresis: stops flickering at the limit). */
    val turnExit: Float = 8f,
    val tiltEnter: Float = 8f,
    val tiltExit: Float = 5f,
    /** While turning, the pitch may change by at most this (and the other way round). */
    val offAxisMax: Float = 15f,
    val offAxisSlack: Float = 5f,
    /**
     * Step 1: the face must be roughly facing the camera. These are the only ABSOLUTE limits, and they are wide on
     * purpose: a phone held at chest height easily gives 25-30 degrees of pitch, and that must not block step 1.
     */
    val centerMaxYaw: Float = 25f,
    val centerMaxPitch: Float = 35f,
    /** Step 1: the angles of the last pictures may differ by at most this ("hold still"). */
    val centerStableSpread: Float = 5f,
    val frontWindow: Int = 5,
    /** A pose must be held for this many pictures and this many milliseconds before it is captured. */
    val minHoldFrames: Int = 3,
    val holdMillis: Long = 400,
    val minFaceSize: Float = 0.22f,
    val maxFaceSize: Float = 0.90f,
    val maxRoll: Float = 25f,
    val frontCenterTolX: Float = 0.25f,
    val frontCenterTolY: Float = 0.30f,
    /** Time rules for ONE step. */
    val hintAfterMillis: Long = 8_000,
    val relaxAfterMillis: Long = 12_000,
    val manualAfterMillis: Long = 15_000,
    /** After [relaxAfterMillis] the required movement is multiplied by this (smaller movement accepted). */
    val relaxFactor: Float = 0.7f,
    /** If no picture arrives for this long, the app was paused: that time is not counted. */
    val pauseGapMillis: Long = 3_000,
)

/** What the screen should tell the user right now. */
enum class EnrollFeedback {
    STARTING, NO_FACE, MULTIPLE_FACES, TOO_FAR, TOO_CLOSE, NOT_CENTERED, HEAD_ROLLED, NOT_STRAIGHT,
    MOVE_MORE, WRONG_DIRECTION, OFF_AXIS, HOLD_STILL, READY,
}

/** Everything the screen needs after one picture. */
data class EnrollUpdate(
    val step: EnrollStep,
    val feedback: EnrollFeedback,
    /** 0..1: how far the user is towards the required movement (for the live meter). */
    val progress: Float,
    /** Movement measured towards the required direction, in degrees (0 for step 1). */
    val measuredDegrees: Float,
    val requiredDegrees: Float,
    /** True while the required pose is being held. */
    val poseOk: Boolean,
    /** The pose was held long enough: the screen should now take the sample (and call [EnrollmentMachine.onCaptured]). */
    val readyToCapture: Boolean,
    /** The step took very long: a "capture by hand" button may be shown. */
    val manualCaptureAllowed: Boolean,
    /** The step is slow: show a hint. */
    val slow: Boolean,
    /** A smaller movement is accepted now. */
    val relaxed: Boolean,
    val stepElapsedMs: Long,
    val totalElapsedMs: Long,
    val capturedCount: Int,
    val finished: Boolean,
)

/**
 * The enrollment steps as an explicit state machine: FRONT -> LEFT -> RIGHT -> UP -> DOWN -> finished.
 *
 * How it works
 *  - Step 1 (FRONT) waits until the face is roughly straight and holds still, then stores the user's own
 *    NEUTRAL angles (yaw and pitch). Phones are held at different heights, so the neutral pose is not 0 degrees.
 *  - All later steps measure the movement RELATIVE to that neutral pose.
 *  - The sign of ML Kit's angles depends on how the camera image is mirrored, and that could not be checked on a
 *    real phone. So the first step of each pair (LEFT, UP) accepts a movement to either side and remembers the side;
 *    the second step (RIGHT, DOWN) needs the OPPOSITE side. Both sides are always captured.
 *  - Hysteresis: a pose starts at the "enter" angle and only ends below the smaller "exit" angle.
 *  - A pose must be held for a few pictures and a short time, so one noisy picture never counts.
 *  - If a step is slow: after a while a hint appears, then a smaller movement is accepted, then the user may
 *    capture by hand (the sample still has to pass the quality and same-face checks outside this class).
 *  - Face missing, several faces, too small, rolled or off-centre: only the hold counter of the CURRENT step
 *    is reset. Finished steps stay finished.
 *
 * The class does not use a clock or a camera. The caller passes the time in, so it is fully testable.
 * Use it from ONE thread only.
 */
class EnrollmentMachine(private val t: PoseThresholds = PoseThresholds()) {

    var step: EnrollStep = EnrollStep.FRONT
        private set
    var capturedCount = 0
        private set
    val isFinished: Boolean get() = finished

    private var finished = false
    private var startedAt = -1L
    private var stepStartedAt = -1L
    private var lastFrameAt = -1L

    private var baselineYaw = 0f
    private var baselinePitch = 0f
    private var turnSign = 0   // side of the first turn (LEFT step). 0 = unknown
    private var tiltSign = 0   // side of the first tilt (UP step). 0 = unknown

    private var inPose = false
    private var stableFrames = 0
    private var stableSince = -1L
    private var lastDelta = 0f
    private var lastPose: PoseSample? = null
    private val recent = ArrayDeque<PoseSample>()

    /** Handle one camera picture. [faceCount] = how many faces were found, [pose] = the face if exactly one. */
    fun onFrame(nowMs: Long, faceCount: Int, pose: PoseSample?, manualRequested: Boolean = false): EnrollUpdate {
        if (startedAt < 0) {
            startedAt = nowMs
            stepStartedAt = nowMs
        }
        if (lastFrameAt >= 0 && nowMs - lastFrameAt > t.pauseGapMillis) {
            // The app was paused (or the camera stopped). Do not count that time, and forget old stability.
            val gap = nowMs - lastFrameAt
            startedAt += gap
            stepStartedAt += gap
            resetHold()
        }
        lastFrameAt = nowMs

        if (finished) return update(nowMs, EnrollFeedback.READY, 0f, 0f, false, false)

        val problem = problemWith(faceCount, pose)
        if (problem != null || pose == null) {
            resetHold()
            return update(nowMs, problem ?: EnrollFeedback.NO_FACE, 0f, 0f, false, false)
        }
        lastPose = pose
        return when (step) {
            EnrollStep.FRONT -> frontStep(nowMs, pose, manualRequested)
            EnrollStep.LEFT -> movementStep(nowMs, pose, manualRequested, turn = true, second = false)
            EnrollStep.RIGHT -> movementStep(nowMs, pose, manualRequested, turn = true, second = true)
            EnrollStep.UP -> movementStep(nowMs, pose, manualRequested, turn = false, second = false)
            EnrollStep.DOWN -> movementStep(nowMs, pose, manualRequested, turn = false, second = true)
        }
    }

    /** The caller took a good sample for the current step. Moves on to the next step. */
    fun onCaptured(nowMs: Long) {
        if (finished) return
        when (step) {
            EnrollStep.FRONT -> {
                val last = lastPose
                if (recent.isNotEmpty()) {
                    baselineYaw = median(recent.map { it.yaw })
                    baselinePitch = median(recent.map { it.pitch })
                } else if (last != null) {
                    // Captured by hand: use the pose of that moment as the neutral pose.
                    baselineYaw = last.yaw
                    baselinePitch = last.pitch
                }
            }
            EnrollStep.LEFT -> turnSign = signOf(lastDelta)
            EnrollStep.UP -> tiltSign = signOf(lastDelta)
            else -> Unit
        }
        capturedCount++
        if (step == EnrollStep.DOWN) {
            finished = true
        } else {
            step = EnrollStep.entries[step.ordinal + 1]
        }
        resetHold()
        stepStartedAt = nowMs
    }

    /** "Retry step": forget the hold counters and the timer of the current step only. */
    fun retryStep(nowMs: Long) {
        resetHold()
        stepStartedAt = nowMs
    }

    /** "Restart": everything from the beginning. The caller must also drop the samples it kept. */
    fun restart() {
        step = EnrollStep.FRONT
        capturedCount = 0
        finished = false
        startedAt = -1
        stepStartedAt = -1
        lastFrameAt = -1
        baselineYaw = 0f
        baselinePitch = 0f
        turnSign = 0
        tiltSign = 0
        lastDelta = 0f
        resetHold()
    }

    // ------------------------------------------------------------------------------------------ steps

    private fun frontStep(now: Long, s: PoseSample, manual: Boolean): EnrollUpdate {
        // The "Capture now" button must always work once it is shown (the face is valid here).
        val manualNow = manual && manualAllowed(now)
        if (abs(s.yaw) > t.centerMaxYaw || abs(s.pitch) > t.centerMaxPitch) {
            resetHold()
            return update(now, EnrollFeedback.NOT_STRAIGHT, 0f, 0f, false, manualNow)
        }
        recent.addLast(s)
        while (recent.size > t.frontWindow) recent.removeFirst()

        val steady = recent.size >= t.minHoldFrames &&
            spread(recent.map { it.yaw }) <= t.centerStableSpread &&
            spread(recent.map { it.pitch }) <= t.centerStableSpread
        if (!steady) {
            stableSince = -1
            return update(now, EnrollFeedback.HOLD_STILL, 0f, 0f, false, manualNow)
        }
        if (stableSince < 0) stableSince = now
        val held = now - stableSince
        val ready = held >= t.holdMillis
        val progress = (held.toFloat() / t.holdMillis).coerceIn(0f, 1f)
        return update(
            now, if (ready) EnrollFeedback.READY else EnrollFeedback.HOLD_STILL,
            progress, 0f, true, ready || manualNow,
        )
    }

    private fun movementStep(now: Long, s: PoseSample, manual: Boolean, turn: Boolean, second: Boolean): EnrollUpdate {
        val delta = if (turn) s.yaw - baselineYaw else s.pitch - baselinePitch
        val offAxis = if (turn) abs(s.pitch - baselinePitch) else abs(s.yaw - baselineYaw)
        lastDelta = delta

        val relax = relaxFactor(now)
        val enter = (if (turn) t.turnEnter else t.tiltEnter) * relax
        val exit = (if (turn) t.turnExit else t.tiltExit) * relax
        val firstSign = if (turn) turnSign else tiltSign

        // "movement" = how far the user went in the direction this step wants.
        val movement: Float
        var wrongWay = false
        if (!second || firstSign == 0) {
            movement = abs(delta)               // the first step of a pair: either side is fine
        } else {
            movement = -firstSign * delta       // the second step: the OPPOSITE side of the first step
            wrongWay = firstSign * delta >= exit
        }

        // Hysteresis: start at "enter", stop only below "exit".
        if (!inPose && movement >= enter && offAxis <= t.offAxisMax) {
            inPose = true
        } else if (inPose && (movement < exit || offAxis > t.offAxisMax + t.offAxisSlack)) {
            inPose = false
        }
        if (inPose) {
            if (stableSince < 0) {
                stableSince = now
                stableFrames = 0
            }
            stableFrames++
        } else {
            stableSince = -1
            stableFrames = 0
        }
        val held = if (stableSince >= 0) now - stableSince else 0L
        val ready = inPose && stableFrames >= t.minHoldFrames && held >= t.holdMillis

        val feedback = when {
            ready -> EnrollFeedback.READY
            inPose -> EnrollFeedback.HOLD_STILL
            wrongWay -> EnrollFeedback.WRONG_DIRECTION
            movement >= enter && offAxis > t.offAxisMax -> EnrollFeedback.OFF_AXIS
            else -> EnrollFeedback.MOVE_MORE
        }
        val progress = (movement / enter).coerceIn(0f, 1f)
        return update(now, feedback, progress, movement.coerceAtLeast(0f), inPose, ready || manualAllowed(now) && manual)
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun problemWith(faceCount: Int, s: PoseSample?): EnrollFeedback? = when {
        faceCount > 1 -> EnrollFeedback.MULTIPLE_FACES   // checked first: with several faces no single pose is given
        faceCount == 0 || s == null -> EnrollFeedback.NO_FACE
        s.sizeRatio < t.minFaceSize -> EnrollFeedback.TOO_FAR
        s.sizeRatio > t.maxFaceSize -> EnrollFeedback.TOO_CLOSE
        abs(s.roll) > t.maxRoll -> EnrollFeedback.HEAD_ROLLED
        step == EnrollStep.FRONT &&
            (abs(s.centerX - 0.5f) > t.frontCenterTolX || abs(s.centerY - 0.5f) > t.frontCenterTolY) -> EnrollFeedback.NOT_CENTERED
        // While turning or nodding the face moves a little: it only has to stay inside the picture.
        step != EnrollStep.FRONT && (s.centerX < 0.1f || s.centerX > 0.9f || s.centerY < 0.05f || s.centerY > 0.95f) ->
            EnrollFeedback.NOT_CENTERED
        else -> null
    }

    private fun relaxFactor(now: Long): Float = if (now - stepStartedAt >= t.relaxAfterMillis) t.relaxFactor else 1f

    private fun manualAllowed(now: Long): Boolean = now - stepStartedAt >= t.manualAfterMillis

    private fun requiredDegrees(now: Long): Float = when (step) {
        EnrollStep.FRONT -> 0f
        EnrollStep.LEFT, EnrollStep.RIGHT -> t.turnEnter * relaxFactor(now)
        EnrollStep.UP, EnrollStep.DOWN -> t.tiltEnter * relaxFactor(now)
    }

    private fun resetHold() {
        inPose = false
        stableFrames = 0
        stableSince = -1
        recent.clear()
    }

    private fun update(
        now: Long,
        feedback: EnrollFeedback,
        progress: Float,
        measured: Float,
        poseOk: Boolean,
        readyToCapture: Boolean,
    ): EnrollUpdate {
        val stepElapsed = if (stepStartedAt < 0) 0L else now - stepStartedAt
        val total = if (startedAt < 0) 0L else now - startedAt
        return EnrollUpdate(
            step = step,
            feedback = feedback,
            progress = progress,
            measuredDegrees = measured,
            requiredDegrees = requiredDegrees(now),
            poseOk = poseOk,
            readyToCapture = readyToCapture && !finished,
            manualCaptureAllowed = !finished && stepElapsed >= t.manualAfterMillis &&
                feedback != EnrollFeedback.NO_FACE && feedback != EnrollFeedback.MULTIPLE_FACES &&
                feedback != EnrollFeedback.TOO_FAR && feedback != EnrollFeedback.TOO_CLOSE &&
                feedback != EnrollFeedback.HEAD_ROLLED && feedback != EnrollFeedback.NOT_CENTERED,
            slow = !finished && stepElapsed >= t.hintAfterMillis,
            relaxed = !finished && stepElapsed >= t.relaxAfterMillis && step != EnrollStep.FRONT,
            stepElapsedMs = stepElapsed,
            totalElapsedMs = total,
            capturedCount = capturedCount,
            finished = finished,
        )
    }

    private fun signOf(d: Float): Int = if (abs(d) < MIN_SIGN_DEGREES) 0 else if (d > 0) 1 else -1

    private fun spread(values: List<Float>): Float = if (values.isEmpty()) 0f else values.max() - values.min()

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        /** A movement smaller than this has no reliable side (used when a step was captured by hand). */
        private const val MIN_SIGN_DEGREES = 3f
    }
}
