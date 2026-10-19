package com.example.privacyguard.logic

import kotlin.math.abs

/** What ML Kit tells us about one face (no picture data, only numbers). */
data class FacePose(
    /** Face width / picture width. */
    val sizeRatio: Float,
    /** Head turn left/right in degrees (ML Kit headEulerAngleY). 0 = looking straight at the camera. */
    val yaw: Float,
    /** Head tilt up/down in degrees (ML Kit headEulerAngleX). 0 = straight. */
    val pitch: Float,
)

/**
 * EYE GAZE IS NOT USED.
 * Phone front cameras are too small and too low-resolution to tell reliably where the eyes look,
 * so we only use head direction. This is the one place where gaze could be added later:
 * write a class that implements [GazeEstimator], and pass it to [OtherPersonDetector].
 * Return true/false when you are sure, or null when you do not know.
 */
interface GazeEstimator {
    fun isLookingAtScreen(face: FacePose): Boolean?
}

/** Default: "I don't know". The detector then uses head pose only. */
object NoGazeEstimator : GazeEstimator {
    override fun isLookingAtScreen(face: FacePose): Boolean? = null
}

/** Result for one camera frame. */
data class FrameResult(
    val facesSeen: Int,
    /** Faces that are big enough and turned towards the screen. */
    val facingFaces: Int,
    /** Highest similarity to my profile among the facing faces (null if no face was checked). */
    val bestMatch: Float?,
    /** True if at least one facing face does NOT match me. */
    val otherPersonLooking: Boolean,
)

/** Decides, for one frame, whether another person seems to be looking at the screen. */
class OtherPersonDetector(
    private val thresholds: Thresholds,
    private val myProfile: FloatArray,
    private val gaze: GazeEstimator = NoGazeEstimator,
) {
    /** True if this face is big enough and its head points towards the screen. */
    fun isFacingScreen(face: FacePose): Boolean {
        if (face.sizeRatio < thresholds.minFaceSize) return false
        // Head pose: a visible face does NOT mean the person is looking. Check the head angles.
        val headOk = abs(face.yaw) <= thresholds.maxHeadAngle && abs(face.pitch) <= thresholds.maxHeadAngle
        if (!headOk) return false
        // Optional gaze hook (always "unknown" for now).
        return gaze.isLookingAtScreen(face) != false
    }

    /**
     * [faces] = every face in the frame.
     * [embeddingOf] gives the number vector of face number i. It is only called for faces that
     * face the screen (making a vector is the slow part, so we skip the others to save battery).
     */
    fun analyze(faces: List<FacePose>, embeddingOf: (Int) -> FloatArray): FrameResult {
        // Look at the biggest (closest) faces first, and never more than MAX_FACES_CHECKED.
        val facing = faces.indices
            .filter { isFacingScreen(faces[it]) }
            .sortedByDescending { faces[it].sizeRatio }
            .take(MAX_FACES_CHECKED)

        var best: Float? = null
        var other = false
        for (i in facing) {
            val similarity = FaceMath.cosineSimilarity(myProfile, embeddingOf(i))
            if (best == null || similarity > best) best = similarity
            if (similarity < thresholds.matchThreshold) other = true
        }
        return FrameResult(faces.size, facing.size, best, other)
    }

    companion object {
        const val MAX_FACES_CHECKED = 3
    }
}
