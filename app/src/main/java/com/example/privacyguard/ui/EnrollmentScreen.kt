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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.example.privacyguard.data.EventLogStore
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.face.FaceFinder
import com.example.privacyguard.face.FaceImages
import com.example.privacyguard.face.FoundFace
import com.example.privacyguard.logic.EnrollFeedback
import com.example.privacyguard.logic.EnrollStep
import com.example.privacyguard.logic.EnrollUpdate
import com.example.privacyguard.logic.EnrollmentCheck
import com.example.privacyguard.logic.EnrollmentMachine
import com.example.privacyguard.logic.EventType
import com.example.privacyguard.logic.FrameRateMeter
import com.example.privacyguard.logic.ImageQuality
import com.example.privacyguard.logic.PoseSample
import com.example.privacyguard.logic.QualityProblem
import com.example.privacyguard.util.Permissions
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Face enrollment: 5 pictures (straight, left, right, up, down) -> one averaged, encrypted profile.
 *
 * The movement rules are in logic/EnrollmentMachine.kt (tested without a phone). This file only connects
 * the camera, ML Kit, the recognition model and the screen:
 *  - ONE picture is analysed at a time. While one is busy, newer camera pictures are dropped (no stale queue).
 *  - Camera, ML Kit and the recognition model are created once for the whole screen.
 *  - Nothing heavy runs on the main thread. The screen is updated only when something visible changed.
 */
@Composable
fun EnrollmentScreen(activity: ComponentActivity, onFinished: () -> Unit, onCancel: () -> Unit) {
    when {
        !Permissions.hasCamera(activity) -> ScreenScaffold(title = stringResource(R.string.enroll_title), onBack = onCancel) {
            Text(stringResource(R.string.enroll_no_permission), color = MaterialTheme.colorScheme.error)
            Button(onClick = { Permissions.openAppSettings(activity) }) { Text(stringResource(R.string.perm_open_settings)) }
        }

        !FaceEmbedder.isModelAvailable(activity) -> ScreenScaffold(title = stringResource(R.string.enroll_title), onBack = onCancel) {
            Text(stringResource(R.string.enroll_model_missing), color = MaterialTheme.colorScheme.error)
        }

        else -> EnrollmentCamera(activity, onFinished, onCancel)
    }
}

/** Everything the screen shows. Only a changed copy is sent to the screen. */
private data class EnrollUi(
    val step: EnrollStep = EnrollStep.FRONT,
    val feedback: EnrollFeedback = EnrollFeedback.STARTING,
    val progress: Float = 0f,
    val measured: Int = 0,
    val required: Int = 0,
    val poseOk: Boolean = false,
    val manualAllowed: Boolean = false,
    val slow: Boolean = false,
    val relaxed: Boolean = false,
    val captured: Int = 0,
    val flash: Boolean = false,
    val rejectedRes: Int = 0,
    val seconds: Int = 0,
    val fps: Int = 0,
    val latencyMs: Int = 0,
    val saving: Boolean = false,
    val failedRes: Int = 0,
)

/** Buttons set these flags; the analysis thread reads them. So the state machine is only used from one thread. */
private class EnrollControls {
    val retryStep = AtomicBoolean(false)
    val restart = AtomicBoolean(false)
    val manualCapture = AtomicBoolean(false)
}

private fun stepTextRes(step: EnrollStep): Int = when (step) {
    EnrollStep.FRONT -> R.string.enroll_front
    EnrollStep.LEFT -> R.string.enroll_left
    EnrollStep.RIGHT -> R.string.enroll_right
    EnrollStep.UP -> R.string.enroll_up
    EnrollStep.DOWN -> R.string.enroll_down
}

private fun feedbackRes(f: EnrollFeedback): Int = when (f) {
    EnrollFeedback.STARTING -> R.string.fb_starting
    EnrollFeedback.NO_FACE -> R.string.enroll_no_face
    EnrollFeedback.MULTIPLE_FACES -> R.string.enroll_many_faces
    EnrollFeedback.TOO_FAR -> R.string.enroll_too_far
    EnrollFeedback.TOO_CLOSE -> R.string.enroll_too_close
    EnrollFeedback.NOT_CENTERED -> R.string.enroll_center
    EnrollFeedback.HEAD_ROLLED -> R.string.enroll_roll
    EnrollFeedback.NOT_STRAIGHT -> R.string.fb_not_straight
    EnrollFeedback.MOVE_MORE -> R.string.fb_move_more
    EnrollFeedback.WRONG_DIRECTION -> R.string.fb_wrong_direction
    EnrollFeedback.OFF_AXIS -> R.string.fb_off_axis
    EnrollFeedback.HOLD_STILL -> R.string.fb_hold
    EnrollFeedback.READY -> R.string.fb_ready
}

private fun qualityRes(p: QualityProblem): Int = when (p) {
    QualityProblem.TOO_DARK -> R.string.enroll_dark
    QualityProblem.TOO_BRIGHT -> R.string.enroll_bright
    QualityProblem.BLURRY -> R.string.enroll_blurry
}

@Composable
private fun EnrollmentCamera(activity: ComponentActivity, onFinished: () -> Unit, onCancel: () -> Unit) {
    var ui by remember { mutableStateOf(EnrollUi()) }
    val controls = remember { EnrollControls() }
    val previewView = remember { PreviewView(activity) }

    DisposableEffect(Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = Executor { task ->
            try {
                executor.execute(task)
            } catch (e: RejectedExecutionException) {
                // The screen was closed. Ignore.
            }
        }
        val finder = FaceFinder()

        // ----- everything below runs on the single analysis thread -----
        var embedder: FaceEmbedder? = null          // loaded once, below
        val machine = EnrollmentMachine()
        val samples = ArrayList<FloatArray>()       // one embedding per finished step, in step order
        val meter = FrameRateMeter()
        val busy = AtomicBoolean(false)             // true while one picture is being analysed
        var detectFailures = 0
        var flashUntil = 0L
        var rejectedRes = 0
        var rejectedUntil = 0L
        var failedRes = 0
        var saveAttempted = false
        var done = false
        var lastUi = EnrollUi()
        var lastUpdate: EnrollUpdate? = null

        fun publish(u: EnrollUpdate, now: Long, saving: Boolean) {
            val next = EnrollUi(
                step = machine.step,
                feedback = u.feedback,
                progress = (u.progress * 20).roundToInt() / 20f, // steps of 5%: fewer screen updates
                measured = u.measuredDegrees.roundToInt(),
                required = u.requiredDegrees.roundToInt(),
                poseOk = u.poseOk,
                manualAllowed = u.manualCaptureAllowed,
                slow = u.slow,
                relaxed = u.relaxed,
                captured = machine.capturedCount,
                flash = now < flashUntil,
                rejectedRes = if (now < rejectedUntil) rejectedRes else 0,
                seconds = (u.totalElapsedMs / 1000).toInt(),
                fps = meter.picturesPerSecond.roundToInt(),
                latencyMs = meter.averageLatencyMs.roundToInt(),
                saving = saving,
                failedRes = failedRes,
            )
            if (next != lastUi) {
                lastUi = next
                mainHandler.post { ui = next }
            }
        }

        fun reject(res: Int, now: Long) {
            rejectedRes = res
            rejectedUntil = now + 1_500
        }

        /** Takes the sample of the current step if the picture is good. Returns true if it was taken. */
        fun tryCapture(bitmap: android.graphics.Bitmap, face: FoundFace, now: Long): Boolean {
            val crop = FaceImages.cropFace(bitmap, face.box)
            val problem = ImageQuality.problem(FaceImages.gray112(crop), 112, 112)
            if (problem != null) {
                reject(qualityRes(problem), now)
                return false
            }
            val model = embedder
            if (model == null) {
                reject(R.string.fb_starting, now)
                return false
            }
            val embedding = model.embed(crop)
            if (!EnrollmentCheck.isValidEmbedding(embedding, model.embeddingSize)) {
                reject(R.string.enroll_bad_sample, now)
                return false
            }
            // Every sample must look like the first (straight) one. A bad sample only repeats ITS step.
            if (samples.isNotEmpty() && !EnrollmentCheck.isSameFace(samples[0], embedding)) {
                reject(R.string.enroll_not_same, now)
                return false
            }
            samples.add(embedding)
            machine.onCaptured(now)
            flashUntil = now + 800
            return true
        }

        fun saveProfile(u: EnrollUpdate, now: Long) {
            saveAttempted = true
            val check = EnrollmentCheck.verify(samples)
            val profile = check.profile
            if (!check.ok || profile == null) {
                // Should not happen (each sample was checked). Start again instead of saving something doubtful.
                Log.w("Enrollment", "Final check failed, lowest similarity ${check.lowestSimilarity}")
                machine.restart()
                samples.clear()
                saveAttempted = false
                reject(R.string.enroll_not_same, now)
                return
            }
            // Saved to a temporary file, read back, and only then moved over the old profile.
            val store = ProfileStore(activity)
            if (store.save(profile) && store.load()?.size == profile.size) {
                done = true
                samples.clear() // the numbers are saved (encrypted); forget them here
                val seconds = u.totalElapsedMs / 1000f
                EventLogStore(activity).add(EventType.ENROLLMENT_DONE, seconds)
                Log.i("Enrollment", "Finished in $seconds s, ${meter.picturesPerSecond} pictures/s, ${meter.averageLatencyMs} ms")
                mainHandler.post { onFinished() }
            } else {
                failedRes = R.string.enroll_error_save
            }
        }

        fun handle(bitmap: android.graphics.Bitmap, faces: List<FoundFace>?, startedAt: Long) {
            val now = SystemClock.elapsedRealtime()
            if (faces == null) {
                detectFailures++
                if (detectFailures >= 10) {
                    failedRes = R.string.enroll_detect_failed
                    val last = lastUpdate
                    if (last != null) publish(last, now, false) else mainHandler.post { ui = ui.copy(failedRes = R.string.enroll_detect_failed) }
                }
                return
            }
            detectFailures = 0
            if (failedRes == R.string.enroll_detect_failed) failedRes = 0

            if (controls.restart.getAndSet(false)) {
                machine.restart()
                samples.clear()
                saveAttempted = false
                failedRes = 0
                rejectedUntil = 0
                flashUntil = 0
            }
            if (controls.retryStep.getAndSet(false)) machine.retryStep(now)
            val manual = controls.manualCapture.getAndSet(false)

            val face = if (faces.size == 1) faces[0] else null
            val pose = face?.let {
                PoseSample(
                    yaw = it.yaw, pitch = it.pitch, roll = it.roll, sizeRatio = it.sizeRatio,
                    centerX = it.box.centerX() / bitmap.width.toFloat(),
                    centerY = it.box.centerY() / bitmap.height.toFloat(),
                )
            }
            val update = machine.onFrame(now, faces.size, pose, manual)
            lastUpdate = update
            if (update.readyToCapture && face != null) tryCapture(bitmap, face, now)

            meter.record(now, now - startedAt)
            var saving = false
            if (machine.isFinished && !saveAttempted) {
                saving = true
                saveProfile(update, now)
            }
            if (!done) publish(update, now, saving || (machine.isFinished && failedRes == 0))
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
            // Still busy with an older picture (or finished): drop this one instead of building a queue.
            if (done || !busy.compareAndSet(false, true)) {
                image.close()
            } else {
                val startedAt = SystemClock.elapsedRealtime()
                val bitmap = try {
                    FaceImages.uprightBitmap(image)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (bitmap == null) {
                    busy.set(false)
                } else {
                    try {
                        finder.find(bitmap, safeExecutor) { faces ->
                            try {
                                handle(bitmap, faces, startedAt)
                            } catch (e: Exception) {
                                Log.w("Enrollment", "Picture failed", e)
                            } finally {
                                bitmap.recycle() // pictures are never kept
                                busy.set(false)
                            }
                        }
                    } catch (e: Exception) {
                        bitmap.recycle()
                        busy.set(false)
                    }
                }
            }
        }

        // Load the recognition model once, off the main thread, while the camera starts.
        executor.execute {
            try {
                embedder = FaceEmbedder(activity)
            } catch (e: Exception) {
                Log.e("Enrollment", "Could not load the recognition model", e)
                failedRes = R.string.enroll_error
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
                ui = ui.copy(failedRes = R.string.enroll_error)
            }
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            // Close the models after any running work has finished.
            try {
                executor.execute {
                    embedder?.close()
                    finder.close()
                }
            } catch (e: RejectedExecutionException) {
                // Already closed.
            }
            executor.shutdown()
        }
    }

    ScreenScaffold(title = stringResource(R.string.enroll_title), onBack = onCancel) {
        // Everything the user must read is ABOVE the camera, so it is visible without scrolling.
        val stepCount = EnrollStep.entries.size
        val shownStep = (ui.captured + 1).coerceAtMost(stepCount)
        Text(stringResource(R.string.enroll_step_format, shownStep), style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { ui.captured / stepCount.toFloat() }, modifier = Modifier.fillMaxWidth())

        if (ui.saving) {
            Text(stringResource(R.string.enroll_saving), style = MaterialTheme.typography.titleMedium)
        } else {
            // Big instruction for the current step.
            Text(stringResource(stepTextRes(ui.step)), style = MaterialTheme.typography.titleLarge)

            // One line of feedback: captured / rejected sample / what to do now.
            val good = ui.feedback == EnrollFeedback.READY || ui.feedback == EnrollFeedback.HOLD_STILL
            when {
                ui.flash -> Text(stringResource(R.string.enroll_captured), style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                ui.rejectedRes != 0 -> Text(stringResource(ui.rejectedRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                else -> Text(
                    stringResource(feedbackRes(ui.feedback)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (good) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                )
            }

            // Live indicator: how far the movement is (bar and degrees).
            LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
            if (ui.step != EnrollStep.FRONT) {
                Text(stringResource(R.string.enroll_meter, ui.measured, ui.required), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Camera preview (70% wide, so the text above stays on screen). The oval turns green while the pose is held.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(3f / 4f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            val ringColor = if (ui.poseOk) Color(0xFF4CAF50) else Color.White
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                val w = size.width * 0.62f
                val h = size.height * 0.62f
                drawOval(
                    color = ringColor,
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = ComposeSize(w, h),
                    style = Stroke(width = 6.dp.toPx()),
                )
            }
        }

        if (!ui.saving) {
            if (ui.slow) {
                Text(stringResource(R.string.enroll_slow_hint), style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.relaxed) {
                Text(stringResource(R.string.enroll_relaxed), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ui.manualAllowed) {
            Button(onClick = { controls.manualCapture.set(true) }) { Text(stringResource(R.string.enroll_btn_manual)) }
            Text(stringResource(R.string.enroll_manual_note), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { controls.retryStep.set(true) }) { Text(stringResource(R.string.enroll_btn_retry)) }
            OutlinedButton(onClick = { controls.restart.set(true) }) { Text(stringResource(R.string.enroll_btn_restart)) }
        }

        // Real numbers measured on this phone while enrolling.
        Text(
            stringResource(R.string.enroll_time, ui.seconds) + "   |   " +
                stringResource(R.string.enroll_perf, ui.fps, ui.latencyMs),
            style = MaterialTheme.typography.bodySmall,
        )
        if (ui.failedRes != 0) {
            Text(stringResource(ui.failedRes), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.enroll_intro), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.enroll_hint_sides), style = MaterialTheme.typography.bodySmall)
    }
}
