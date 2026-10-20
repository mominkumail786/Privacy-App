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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.privacyguard.R
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.face.FaceFinder
import com.example.privacyguard.face.FaceImages
import com.example.privacyguard.logic.CalibrationQuality
import com.example.privacyguard.logic.CalibrationResult
import com.example.privacyguard.logic.FaceMath
import com.example.privacyguard.logic.OtherPersonDetector
import com.example.privacyguard.logic.ThresholdCalibrator
import com.example.privacyguard.service.GuardState
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures how similar the OWNER's live face is to the stored profile on THIS phone, and (optionally)
 * how similar ANOTHER person is. From those two sets of numbers it suggests the thresholds.
 * Nothing is saved except the thresholds you choose to apply. No pictures are kept.
 */
@Composable
fun CalibrationScreen(activity: ComponentActivity, onDone: () -> Unit) {
    val profile = remember { ProfileStore(activity).load() }
    val problemRes = when {
        !FaceEmbedder.isModelAvailable(activity) -> R.string.enroll_model_missing
        profile == null -> R.string.calib_need_profile
        GuardState.status.isRunning -> R.string.setup_stop_first // the camera is used by the protection service
        else -> null
    }
    if (problemRes != null || profile == null) {
        ScreenScaffold(title = stringResource(R.string.calib_title), onBack = onDone) {
            Text(stringResource(problemRes ?: R.string.calib_need_profile), color = MaterialTheme.colorScheme.error)
        }
        return
    }
    CalibrationCamera(activity, profile, onDone)
}

private enum class CalibPhase { INTRO, OWNER, ASK_OTHER, OTHER, RESULT }

private const val TARGET_SAMPLES = 30   // about 10 seconds of pictures
private const val MIN_GAP_MS = 300L     // about 3 pictures per second

@Composable
private fun CalibrationCamera(activity: ComponentActivity, profile: FloatArray, onDone: () -> Unit) {
    var phase by remember { mutableStateOf(CalibPhase.INTRO) }
    var count by remember { mutableIntStateOf(0) }
    var liveScore by remember { mutableStateOf<Float?>(null) }
    var hintRes by remember { mutableIntStateOf(R.string.calib_look) }
    var result by remember { mutableStateOf<CalibrationResult?>(null) }
    var applied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(activity) }
    val settings = remember { AppSettings(activity) }

    // 0 = not measuring, 1 = measuring the owner, 2 = measuring another person. Read by the analysis thread.
    val recording = remember { AtomicInteger(0) }
    val ownerScores = remember { Collections.synchronizedList(mutableListOf<Float>()) }
    val otherScores = remember { Collections.synchronizedList(mutableListOf<Float>()) }

    fun showResult() {
        result = ThresholdCalibrator.calibrate(ownerScores.toList(), otherScores.toList())
        phase = CalibPhase.RESULT
    }

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
        var lastAnalyzedAt = 0L
        val busy = AtomicBoolean(false) // one picture at a time; newer ones are dropped

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
            val mode = recording.get()
            if (mode == 0 || now - lastAnalyzedAt < MIN_GAP_MS || !busy.compareAndSet(false, true)) {
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
                if (bitmap == null) {
                    busy.set(false)
                } else {
                    finder.find(bitmap, safeExecutor) { faces ->
                        try {
                            if (faces != null && recording.get() == mode) {
                                // Same rule as the real monitoring: only faces that look at the screen are measured.
                                val detector = OtherPersonDetector(settings.thresholds, profile)
                                val facing = faces.filter { detector.isFacingScreen(it.pose()) }
                                if (faces.size != 1 || facing.size != 1) {
                                    // Exactly one person must be in view, otherwise we would mix up two people.
                                    mainHandler.post { hintRes = R.string.calib_one_person }
                                } else {
                                    val crop = FaceImages.cropFace(bitmap, facing[0].box)
                                    val score = FaceMath.cosineSimilarity(profile, embedder.embed(crop))
                                    val list = if (mode == 1) ownerScores else otherScores
                                    list.add(score)
                                    val size = list.size
                                    mainHandler.post {
                                        liveScore = score
                                        count = size
                                        hintRes = R.string.calib_look
                                    }
                                    // compareAndSet makes sure only one picture ends the measurement.
                                    if (size >= TARGET_SAMPLES && recording.compareAndSet(mode, 0)) {
                                        mainHandler.post {
                                            if (mode == 1) phase = CalibPhase.ASK_OTHER else showResult()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("Calibration", "Picture failed", e)
                            mainHandler.post { failed = true }
                        } finally {
                            bitmap.recycle() // pictures are never kept
                            busy.set(false)
                        }
                    }
                }
            }
        }

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
                Log.e("Calibration", "Camera failed", e)
                failed = true
            }
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
            disposed = true
            recording.set(0)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
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

    ScreenScaffold(title = stringResource(R.string.calib_title), onBack = onDone) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        )

        when (phase) {
            CalibPhase.INTRO -> {
                Text(stringResource(R.string.calib_intro), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    ownerScores.clear()
                    count = 0
                    liveScore = null
                    recording.set(1)
                    phase = CalibPhase.OWNER
                }) { Text(stringResource(R.string.calib_btn_owner)) }
            }

            CalibPhase.OWNER, CalibPhase.OTHER -> {
                Text(
                    stringResource(if (phase == CalibPhase.OWNER) R.string.calib_recording_owner else R.string.calib_recording_other),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(progress = { count / TARGET_SAMPLES.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.calib_samples, count, TARGET_SAMPLES))
                liveScore?.let { Text(stringResource(R.string.calib_live, "%.2f".format(it))) }
                Text(stringResource(hintRes), style = MaterialTheme.typography.bodyMedium)
            }

            CalibPhase.ASK_OTHER -> {
                Text(stringResource(R.string.calib_other_prompt), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        otherScores.clear()
                        count = 0
                        liveScore = null
                        recording.set(2)
                        phase = CalibPhase.OTHER
                    }) { Text(stringResource(R.string.calib_btn_other)) }
                    OutlinedButton(onClick = { showResult() }) { Text(stringResource(R.string.calib_btn_skip)) }
                }
            }

            CalibPhase.RESULT -> {
                val r = result
                if (r != null) {
                    val qualityRes = when (r.quality) {
                        CalibrationQuality.GOOD -> R.string.calib_quality_good
                        CalibrationQuality.OVERLAP -> R.string.calib_quality_overlap
                        CalibrationQuality.OWNER_ONLY -> R.string.calib_quality_owner_only
                        CalibrationQuality.NOT_ENOUGH_DATA -> R.string.calib_quality_no_data
                    }
                    Text(stringResource(R.string.calib_result_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(qualityRes), style = MaterialTheme.typography.bodyMedium)
                    val match = r.matchThreshold
                    val margin = r.uncertainMargin
                    if (match != null && margin != null) {
                        Text(
                            stringResource(
                                R.string.calib_numbers,
                                r.ownerLow?.let { "%.2f".format(it) } ?: "-",
                                r.otherHigh?.let { "%.2f".format(it) } ?: "-",
                                "%.2f".format(match),
                                "%.2f".format(match - margin),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            enabled = !applied,
                            onClick = {
                                settings.thresholds = settings.thresholds.copy(matchThreshold = match, uncertainMargin = margin)
                                applied = true
                            },
                        ) { Text(stringResource(if (applied) R.string.calib_applied else R.string.calib_btn_apply)) }
                    }
                }
                OutlinedButton(onClick = {
                    result = null
                    applied = false
                    phase = CalibPhase.INTRO
                }) { Text(stringResource(R.string.calib_btn_again)) }
            }
        }
        if (failed) {
            Text(stringResource(R.string.enroll_error), color = MaterialTheme.colorScheme.error)
        }
    }
}
