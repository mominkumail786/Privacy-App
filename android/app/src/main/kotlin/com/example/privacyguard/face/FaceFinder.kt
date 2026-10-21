package com.example.privacyguard.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.example.privacyguard.logic.FacePose
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/** One face found in a picture. Numbers only. */
data class FoundFace(
    val box: Rect,
    /** Head turn left/right (ML Kit headEulerAngleY). */
    val yaw: Float,
    /** Head tilt up/down (ML Kit headEulerAngleX). */
    val pitch: Float,
    /** Head roll (ML Kit headEulerAngleZ). */
    val roll: Float,
    /** Face width / picture width. */
    val sizeRatio: Float,
) {
    fun pose() = FacePose(sizeRatio, yaw, pitch)
}

/** Finds faces with ML Kit. The model is bundled with the library, so it works without internet. */
class FaceFinder : Closeable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // ACCURATE mode: gives reliable head angles. We only run about 2-3 frames per second, so this is OK.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // Eye open / smile probabilities are NOT used (no eye gaze, see OtherPersonDetector).
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f) // the real "too small" limit is a setting, applied later
            .build(),
    )

    /**
     * [bitmap] must be upright (use [FaceImages.uprightBitmap]).
     * The answer arrives on [executor]. null means detection failed.
     */
    fun find(bitmap: Bitmap, executor: Executor, onDone: (List<FoundFace>?) -> Unit) {
        val width = bitmap.width.toFloat()
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(executor) { faces ->
                onDone(
                    faces.map {
                        FoundFace(
                            box = it.boundingBox,
                            yaw = it.headEulerAngleY,
                            pitch = it.headEulerAngleX,
                            roll = it.headEulerAngleZ,
                            sizeRatio = it.boundingBox.width() / width,
                        )
                    },
                )
            }
            .addOnFailureListener(executor) { onDone(null) }
    }

    override fun close() {
        detector.close()
    }
}

/** Picture helpers. Nothing here writes a picture to disk. */
object FaceImages {

    /** Converts a camera frame to a normal upright Bitmap (in memory only). */
    fun uprightBitmap(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** Cuts a square around the face (with a small margin), kept inside the picture. */
    fun cropFace(bitmap: Bitmap, box: Rect): Bitmap {
        val wanted = (max(box.width(), box.height()) * 1.15f).toInt()
        val side = max(1, min(wanted, min(bitmap.width, bitmap.height)))
        val left = (box.centerX() - side / 2).coerceIn(0, bitmap.width - side)
        val top = (box.centerY() - side / 2).coerceIn(0, bitmap.height - side)
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    /** Grey values (0..255) of a face at 112 x 112, used for the brightness / blur check. */
    fun gray112(face: Bitmap): IntArray {
        val size = FaceEmbedder.INPUT_SIZE
        val small = Bitmap.createScaledBitmap(face, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small !== face) small.recycle()
        return IntArray(pixels.size) {
            val p = pixels[it]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
    }
}
