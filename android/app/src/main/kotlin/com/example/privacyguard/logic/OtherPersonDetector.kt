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

/** Verdict for one face. */
enum class FaceVerdict { OWNER, UNCERTAIN, STRANGER }

/** What the app understood about one whole camera picture. Shown on the main screen. */
enum class FrameState {
    /** No face found at all. Never an alert. */
    NO_FACE,
    /** Faces found, but none is big enough and turned towards the screen. Never an alert. */
    NOT_FACING,
    /** Only the owner is looking at the screen. */
    OWNER,
    /** The owner and at least one confident stranger. */
    OWNER_AND_OTHER,
    /** At least one confident stranger and no owner. */
    OTHER_PERSON,
    /** A face that is neither clearly the owner nor clearly a stranger. */
    UNCERTAIN,
}

/** Result for one camera frame. */
data class FrameResult(
    val facesSeen: Int,
    /** Faces that are big enough and turned towards the screen. */
    val facingFaces: Int,
    val ownerFaces: Int,
    val uncertainFaces: Int,
    val strangerFaces: Int,
    /** Highest similarity to my profile among the facing faces (null if no face was checked). */
    val bestMatch: Float?,
    /** Lowest similarity among the facing faces (null if no face was checked). */
    val lowestMatch: Float?,
    /** True if this picture counts as a vote for "alert". Depends on the uncertain-face policy. */
    val otherPersonLooking: Boolean,
) {
    val state: FrameState
        get() = when {
            facesSeen == 0 -> FrameState.NO_FACE
            facingFaces == 0 -> FrameState.NOT_FACING
            strangerFaces > 0 && ownerFaces > 0 -> FrameState.OWNER_AND_OTHER
            strangerFaces > 0 -> FrameState.OTHER_PERSON
            uncertainFaces > 0 -> FrameState.UNCERTAIN
            else -> FrameState.OWNER
        }
}

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

    /** Owner, stranger, or not sure, from the similarity to the owner's profile. */
    fun verdict(similarity: Float): FaceVerdict = when {
        similarity >= thresholds.matchThreshold -> FaceVerdict.OWNER
        similarity < thresholds.strangerBelow -> FaceVerdict.STRANGER
        else -> FaceVerdict.UNCERTAIN
    }

    /**
     * [faces] = every face in the frame.
     * [embeddingOf] gives the number vector of face number i. It is only called for faces that
     * face the screen (making a vector is the slow part, so we skip the others to save battery).
     * Every face is judged on its own, so an owner next to a stranger still gives an alert for the stranger.
     */
    fun analyze(faces: List<FacePose>, embeddingOf: (Int) -> FloatArray): FrameResult {
        // Look at the biggest (closest) faces first, and never more than MAX_FACES_CHECKED.
        val facing = faces.indices
            .filter { isFacingScreen(faces[it]) }
            .sortedByDescending { faces[it].sizeRatio }
            .take(MAX_FACES_CHECKED)

        var best: Float? = null
        var lowest: Float? = null
        var owners = 0
        var uncertain = 0
        var strangers = 0
        for (i in facing) {
            val similarity = FaceMath.cosineSimilarity(myProfile, embeddingOf(i))
            if (best == null || similarity > best) best = similarity
            if (lowest == null || similarity < lowest) lowest = similarity
            when (verdict(similarity)) {
                FaceVerdict.OWNER -> owners++
                FaceVerdict.UNCERTAIN -> uncertain++
                FaceVerdict.STRANGER -> strangers++
            }
        }
        val vote = strangers > 0 || (thresholds.alertOnUncertain && uncertain > 0)
        return FrameResult(faces.size, facing.size, owners, uncertain, strangers, best, lowest, vote)
    }

    companion object {
        const val MAX_FACES_CHECKED = 3
    }
}
