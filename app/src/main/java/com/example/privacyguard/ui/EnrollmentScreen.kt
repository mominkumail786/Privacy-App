package com.example.privacyguard.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.privacyguard.R
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.face.FaceFinder
import com.example.privacyguard.face.FaceImages
import com.example.privacyguard.logic.EnrollStep
import com.example.privacyguard.logic.EnrollmentCheck
import com.example.privacyguard.logic.EnrollmentSession
import com.example.privacyguard.logic.FaceMath
import com.example.privacyguard.logic.ImageQuality
import com.example.privacyguard.logic.QualityProblem
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs

/** Face enrollment: 5 pictures (front, left, right, up, down) -> one averaged, encrypted profile. */
@Composable
fun EnrollmentScreen(activity: ComponentActivity, onFinished: () -> Unit, onCancel: () -> Unit) {
    if (!FaceEmbedder.isModelAvailable(activity)) {
        ScreenScaffold(title = stringResource(R.string.enroll_title), onBack = onCancel) {
            Text(stringResource(R.string.enroll_model_missing), color = MaterialTheme.colorScheme.error)
        }
        return
    }
    EnrollmentCamera(activity, onFinished, onCancel)
}

private fun stepTextRes(step: EnrollStep): Int = when (step) {
    EnrollStep.FRONT -> R.string.enroll_front
    EnrollStep.LEFT -> R.string.enroll_left
    EnrollStep.RIGHT -> R.string.enroll_right
    EnrollStep.UP -> R.string.enroll_up
    EnrollStep.DOWN -> R.string.enroll_down
}

@Composable
private fun EnrollmentCamera(activity: ComponentActivity, onFinished: () -> Unit, onCancel: () -> Unit) {
    // What the screen shows. These are only changed on the main thread.
    var stepNumber by remember { mutableIntStateOf(0) }       // 0..4 = steps, 5 = saving
    var feedbackRes by remember { mutableIntStateOf(R.string.enroll_front) }
    var failed by remember { mutableStateOf(false) }
    var inconsistent by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(activity) }

    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = Executor { task ->
            try {
                executor.execute(task)
            } catch (e: RejectedExecutionException) {
                // Screen was closed. Ignore.
            }
        }
        val finder = FaceFinder()
        val embedder = FaceEmbedder(activity)

        // ----- everything below runs on the single analysis thread -----
        val session = EnrollmentSession()
        val embeddings = mutableListOf<FloatArray>()
        var goodFrames = 0
        var lastAnalyzedAt = 0L
        var pauseUntil = 0L
        var finishedCapturing = false

        fun show(res: Int) {
            mainHandler.post { feedbackRes = res }
        }

        fun saveProfile() {
            try {
                // 1. Do the 5 pictures belong together? If not, start again instead of saving a bad profile.
                val check = EnrollmentCheck.verify(embeddings)
                val profile = check.profile
                if (!check.ok || profile == null) {
                    Log.w("Enrollment", "Samples inconsistent, lowest similarity ${check.lowestSimilarity}")
                    embeddings.clear()
                    session.reset()
                    finishedCapturing = false
                    mainHandler.post {
                        stepNumber = 0
                        inconsistent = true
                        feedbackRes = R.string.enroll_front
                    }
                    return
                }
                // 2. Save it encrypted, then read it back: protection is only enabled for a profile that really loads.
                val store = ProfileStore(activity)
                store.save(profile)
                val back = store.load()
                if (back == null || back.size != profile.size || FaceMath.cosineSimilarity(back, profile) < 0.999f) {
                    store.delete()
                    mainHandler.post { failed = true; stepNumber = 0 }
                    return
                }
                embeddings.clear() // the numbers are saved (encrypted); forget them here
                mainHandler.post { onFinished() }
            } catch (e: Exception) {
                Log.e("Enrollment", "Saving the profile failed", e)
                mainHandler.post { failed = true }
            }
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { image ->
            val now = SystemClock.elapsedRealtime()
            if (finishedCapturing || now < pauseUntil || now - lastAnalyzedAt < 300) {
                image.close()
            } else {
                lastAnalyzedAt = now
                val bitmap = try {
                    FaceImages.uprightBitmap(image)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (bitmap != null) {
                    finder.find(bitmap, safeExecutor) { faces ->
                        try {
                            when {
                                faces == null || faces.isEmpty() -> { goodFrames = 0; show(R.string.enroll_no_face) }
                                faces.size > 1 -> { goodFrames = 0; show(R.string.enroll_many_faces) }
                                else -> {
                                    val face = faces[0]
                                    val crop = FaceImages.cropFace(bitmap, face.box)
                                    val problem = ImageQuality.problem(FaceImages.gray112(crop), 112, 112)
                                    when {
                                        face.sizeRatio < MIN_FACE_RATIO -> { goodFrames = 0; show(R.string.enroll_too_far) }
                                        face.sizeRatio > MAX_FACE_RATIO -> { goodFrames = 0; show(R.string.enroll_too_close) }
                                        !isCentered(face.box.centerX() / bitmap.width.toFloat(), face.box.centerY() / bitmap.height.toFloat()) -> {
                                            goodFrames = 0
                                            show(R.string.enroll_center)
                                        }
                                        abs(face.roll) > MAX_ROLL -> { goodFrames = 0; show(R.string.enroll_roll) }
                                        !session.poseMatches(face.yaw, face.pitch) -> {
                                            goodFrames = 0
                                            show(stepTextRes(session.currentStep))
                                        }
                                        problem == QualityProblem.TOO_DARK -> { goodFrames = 0; show(R.string.enroll_dark) }
                                        problem == QualityProblem.TOO_BRIGHT -> { goodFrames = 0; show(R.string.enroll_bright) }
                                        problem == QualityProblem.BLURRY -> { goodFrames = 0; show(R.string.enroll_blurry) }
                                        ++goodFrames < FRAMES_TO_HOLD -> show(R.string.enroll_hold)
                                        else -> {
                                            // Good position held for a moment: take it. Only the numbers are kept.
                                            embeddings.add(embedder.embed(crop))
                                            session.onCaptured(face.yaw, face.pitch)
                                            goodFrames = 0
                                            pauseUntil = SystemClock.elapsedRealtime() + PAUSE_AFTER_CAPTURE_MS
                                            val next = session.index
                                            mainHandler.post {
                                                stepNumber = next
                                                feedbackRes = R.string.enroll_captured
                                            }
                                            if (session.isFinished) {
                                                finishedCapturing = true
                                                saveProfile()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("Enrollment", "Frame failed", e)
                        } finally {
                            bitmap.recycle() // pictures are never kept
                        }
                    }
                }
            }
        }

        // ----- camera -----
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                provider = providerFuture.get()
                provider?.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("Enrollment", "Camera failed", e)
                failed = true
            }
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            // Close the models after any running work has finished.
            try {
                executor.execute {
                    embedder.close()
                    finder.close()
                }
            } catch (e: RejectedExecutionException) {
                // Already closed.
            }
            executor.shutdown()
        }
    }

    ScreenScaffold(title = stringResource(R.string.enroll_title), onBack = onCancel) {
        Text(stringResource(R.string.enroll_intro), style = MaterialTheme.typography.bodyMedium)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            // The oval: put your face inside it.
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                val w = size.width * 0.62f
                val h = size.height * 0.62f
                drawOval(
                    color = Color.White,
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = ComposeSize(w, h),
                    style = Stroke(width = 6.dp.toPx()),
                )
            }
        }

        val steps = EnrollStep.entries
        val shownStep = stepNumber.coerceAtMost(steps.size - 1)
        Text(
            stringResource(R.string.enroll_step_format, shownStep + 1),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(progress = { stepNumber / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth())

        if (stepNumber >= steps.size) {
            Text(stringResource(R.string.enroll_saving), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(stringResource(stepTextRes(steps[stepNumber])), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(feedbackRes), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.enroll_hint_sides), style = MaterialTheme.typography.bodySmall)
        }
        if (inconsistent) {
            Text(stringResource(R.string.enroll_inconsistent), color = MaterialTheme.colorScheme.error)
        }
        if (failed) {
            Text(stringResource(R.string.enroll_error), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The face centre must be near the middle of the picture (where the oval is). Values are 0..1 of width / height. */
private fun isCentered(x: Float, y: Float): Boolean = abs(x - 0.5f) < 0.2f && abs(y - 0.5f) < 0.25f

private const val MIN_FACE_RATIO = 0.25f      // face must fill at least 25% of the picture width
private const val MAX_FACE_RATIO = 0.85f
private const val MAX_ROLL = 20f              // degrees
private const val FRAMES_TO_HOLD = 3          // good position must be seen in 3 checks (about 1 second)
private const val PAUSE_AFTER_CAPTURE_MS = 1200L
