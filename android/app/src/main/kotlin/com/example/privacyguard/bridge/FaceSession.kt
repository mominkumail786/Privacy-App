package com.example.privacyguard.bridge

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.face.FaceFinder
import com.example.privacyguard.face.FaceImages
import com.example.privacyguard.face.FoundFace
import com.example.privacyguard.logic.FaceMath
import com.example.privacyguard.logic.ImageQuality
import com.example.privacyguard.logic.OtherPersonDetector
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One front-camera session for the Flutter enrollment and calibration screens (the protection service has its own).
 *
 * What it does: front camera -> ML Kit finds faces -> one small message per picture goes to Dart
 * (number of faces, head angles, size, position). Dart decides what the user should do next.
 * The face vector (the slow part, MobileFaceNet) is only made when Dart asks for it ([captureSample]),
 * or, in calibration mode, once per picture for the single face that looks at the screen.
 *
 *  - ONE picture is analysed at a time. While one is busy, newer camera pictures are dropped (no stale queue).
 *  - Everything heavy runs on ONE background thread. That thread also owns the picture that [captureSample] uses.
 *  - Pictures live in memory only. Only the latest one is kept, and it is freed when a newer one replaces it.
 *  - Camera, ML Kit and the recognition model are released by [close].
 */
class FaceSession(
    private val context: Context,
    private val computeSimilarity: Boolean,
    /** Called on the analysis thread. The plugin moves the message to the main thread. */
    private val emit: (Map<String, Any?>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    // Same thread, but ignores tasks that arrive after close (so a late ML Kit callback cannot crash).
    private val safeExecutor = Executor { task ->
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Closed. Nothing to do.
        }
    }

    private val finder = FaceFinder()
    private val settings = AppSettings(appContext)
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false

    // ---- used on the analysis thread only
    private var embedder: FaceEmbedder? = null
    private var modelFailed = false
    private var profile: FloatArray? = null
    private var frameCounter = 0
    private var lastAnalyzedAt = 0L
    private var heldBitmap: Bitmap? = null
    private var heldId = -1
    private var heldFace: FoundFace? = null

    // ---- used on the main thread only
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private val lifecycle = SessionLifecycle()
    private val cameraStateObserver = Observer<CameraState> { state ->
        if (state.error != null && !closed) emit(mapOf("type" to "error", "code" to "CAMERA_FAILED"))
    }

    /** Starts the camera and draws it into [previewView]. [onStarted] is called on the main thread. */
    fun start(previewView: PreviewView, onStarted: (Boolean) -> Unit) {
        // Load the recognition model (and, for calibration, the profile) once, off the main thread.
        executor.execute {
            try {
                embedder = FaceEmbedder(appContext)
                if (computeSimilarity) profile = ProfileStore(appContext).load()
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the recognition model", e)
                modelFailed = true
            }
        }

        val analysisUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysisUseCase.setAnalyzer(executor) { image ->
            val now = SystemClock.elapsedRealtime()
            // Closed, still busy with an older picture, or (calibration) too soon after the last one: drop it.
            val tooSoon = computeSimilarity && now - lastAnalyzedAt < CALIBRATION_GAP_MS
            if (closed || tooSoon || !busy.compareAndSet(false, true)) {
                image.close()
                return@setAnalyzer
            }
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
                return@setAnalyzer
            }
            try {
                finder.find(bitmap, safeExecutor) { faces ->
                    try {
                        handle(bitmap, faces, now)
                    } catch (e: Exception) {
                        Log.w(TAG, "Picture failed", e)
                    } finally {
                        busy.set(false)
                    }
                }
            } catch (e: Exception) {
                bitmap.recycle()
                busy.set(false)
            }
        }
        analysis = analysisUseCase

        val previewUseCase = Preview.Builder().build()
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        preview = previewUseCase

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (closed) {
                onStarted(false)
                return@addListener
            }
            try {
                val cameraProvider = providerFuture.get()
                lifecycle.start()
                val bound = cameraProvider.bindToLifecycle(
                    lifecycle, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysisUseCase,
                )
                bound.cameraInfo.cameraState.observeForever(cameraStateObserver)
                provider = cameraProvider
                camera = bound
                onStarted(true)
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed", e)
                onStarted(false)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Runs on the analysis thread. Takes over the ownership of [bitmap]: it is either kept or recycled here. */
    private fun handle(bitmap: Bitmap, faces: List<FoundFace>?, startedAt: Long) {
        if (closed) {
            bitmap.recycle()
            return
        }
        if (faces == null) {
            bitmap.recycle()
            emit(mapOf("type" to "error", "code" to "DETECTION_FAILED"))
            return
        }

        // Keep this picture for [captureSample] and free the previous one.
        val id = ++frameCounter
        heldBitmap?.recycle()
        heldBitmap = bitmap
        heldId = id
        heldFace = if (faces.size == 1) faces[0] else null

        val single = if (faces.size == 1) faces[0] else null
        var facing = false
        var similarity: Double? = null
        if (computeSimilarity && single != null) {
            // Same rule as the real protection: only a face that looks at the screen is measured.
            facing = OtherPersonDetector(settings.thresholds, profile ?: FloatArray(0)).isFacingScreen(single.pose())
            val model = embedder
            val owner = profile
            if (facing && model != null && owner != null) {
                val crop = FaceImages.cropFace(bitmap, single.box)
                similarity = FaceMath.cosineSimilarity(owner, model.embed(crop)).toDouble()
            }
        }

        val now = SystemClock.elapsedRealtime()
        emit(
            mapOf(
                "type" to "frame",
                "frameId" to id,
                "t" to now,
                "latencyMs" to (now - startedAt).toInt(),
                "faces" to faces.size,
                "pose" to single?.let {
                    mapOf(
                        "yaw" to it.yaw.toDouble(),
                        "pitch" to it.pitch.toDouble(),
                        "roll" to it.roll.toDouble(),
                        "size" to it.sizeRatio.toDouble(),
                        "cx" to it.box.centerX().toDouble() / bitmap.width,
                        "cy" to it.box.centerY().toDouble() / bitmap.height,
                    )
                },
                "facing" to facing,
                "similarity" to similarity,
            ),
        )
    }

    /**
     * Makes the face vector of picture [frameId] (only the latest picture is still in memory).
     * [onResult] is called on the analysis thread.
     */
    fun captureSample(frameId: Int, onResult: (Map<String, Any?>) -> Unit) {
        try {
            executor.execute { onResult(captureNow(frameId)) }
        } catch (e: RejectedExecutionException) {
            onResult(problem("FAILED"))
        }
    }

    private fun captureNow(frameId: Int): Map<String, Any?> {
        val bitmap = heldBitmap
        val face = heldFace
        if (bitmap == null || face == null || heldId != frameId) return problem("STALE")
        val model = embedder ?: return problem(if (modelFailed) "FAILED" else "NOT_READY")
        return try {
            val crop = FaceImages.cropFace(bitmap, face.box)
            val quality = ImageQuality.problem(FaceImages.gray112(crop), FaceEmbedder.INPUT_SIZE, FaceEmbedder.INPUT_SIZE)
            if (quality != null) return problem(quality.name) // TOO_DARK, TOO_BRIGHT or BLURRY
            val vector = model.embed(crop)
            mapOf("ok" to true, "embedding" to DoubleArray(vector.size) { vector[it].toDouble() })
        } catch (e: Exception) {
            Log.w(TAG, "Sample failed", e)
            problem("FAILED")
        }
    }

    private fun problem(name: String): Map<String, Any?> = mapOf("ok" to false, "problem" to name)

    /** Releases the camera, ML Kit, the model and the picture in memory. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        analysis?.clearAnalyzer()
        camera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        // unbind() takes only non-null use cases.
        provider?.unbind(*listOfNotNull<UseCase>(preview, analysis).toTypedArray())
        lifecycle.stop()
        // Close the models after any running work has finished.
        try {
            executor.execute {
                heldBitmap?.recycle()
                heldBitmap = null
                embedder?.close()
                finder.close()
            }
        } catch (e: RejectedExecutionException) {
            // Already closed.
        }
        executor.shutdown()
    }

    /** A lifecycle that is "started" while the session runs, so CameraX opens and closes the camera with it. */
    private class SessionLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun stop() {
            // INITIALIZED cannot jump to DESTROYED; if the camera never started there is nothing to stop.
            if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "FaceSession"
        const val CALIBRATION_GAP_MS = 300L // about 3 pictures per second
    }
}
