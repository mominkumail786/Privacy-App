package com.example.privacyguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.data.EventLogStore
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.face.FaceFinder
import com.example.privacyguard.face.FaceImages
import com.example.privacyguard.face.FoundFace
import com.example.privacyguard.logic.AlertCooldown
import com.example.privacyguard.logic.EventType
import com.example.privacyguard.logic.FailureMonitor
import com.example.privacyguard.logic.FrameState
import com.example.privacyguard.logic.OtherPersonDetector
import com.example.privacyguard.logic.TemporalFilter
import com.example.privacyguard.util.Permissions
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Watches the front camera in the background (foreground service of type "camera").
 *
 * How it works, in short:
 *  - CameraX gives us frames. We use only about 2-3 per second and throw the rest away.
 *  - Each used frame: ML Kit finds faces -> faces that look at the screen get a number vector
 *    -> compared with your profile -> "other person looking?" -> temporal filter -> alert.
 *  - Frames only live in memory. Nothing is saved or sent anywhere.
 *  - Every few seconds a "watchdog" checks: screen on? battery ok? camera still delivering frames?
 *    If the camera was taken by another app, it keeps retrying and shows the real status.
 */
class PrivacyGuardService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Work (face detection + embedding) happens on this one background thread.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Same thread, but ignores tasks that arrive after we shut down (so a late ML Kit callback cannot crash us).
    private val safeExecutor = Executor { task ->
        try {
            analysisExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Service is closing. Nothing to do.
        }
    }

    private var finder: FaceFinder? = null
    private var embedder: FaceEmbedder? = null
    private var profile: FloatArray? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraStateLive: LiveData<CameraState>? = null
    private var orientationListener: OrientationEventListener? = null

    private var started = false
    private var endStatus = GuardStatus.STOPPED // what the screen should show after we stop

    // Flags describing why we might be paused.
    private var cameraBound = false
    private var cameraBoundAt = 0L
    @Volatile private var screenOn = true
    @Volatile private var lowBattery = false
    @Volatile private var cameraBusy = false
    @Volatile private var lastFrameAt = 0L

    // Face analysis failing again and again must be visible: "Active" would be a lie.
    private val failures = FailureMonitor()
    @Volatile private var degraded = false
    private lateinit var eventLog: EventLogStore

    // True while one picture is being analysed. Newer camera pictures are dropped meanwhile, so no queue of old pictures builds up.
    private val detecting = AtomicBoolean(false)

    // Used only on the analysis thread.
    private var lastAnalyzedAt = 0L
    private var filter = TemporalFilter(5, 3)
    private var filterSetup = Pair(5, 3)
    private val cooldown = AlertCooldown { settings.cooldownSeconds * 1000L }

    // ---------------------------------------------------------------- start / stop

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        powerManager = getSystemService(PowerManager::class.java)
        eventLog = EventLogStore(this)
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // The "Stop" button in the notification.
        if (intent?.action == ACTION_STOP) {
            settings.protectionWanted = false
            stopSelf()
            return START_NOT_STICKY
        }

        // We must call startForeground quickly. This is also where Android checks the camera permission.
        if (!enterForeground()) {
            // Android refused. Typical reasons: camera permission missing, or Android restarted us
            // in the background (not allowed for camera services on Android 14/15).
            endStatus = if (Permissions.hasCamera(this)) GuardStatus.STOPPED else GuardStatus.PERMISSION_MISSING
            if (endStatus == GuardStatus.STOPPED) Notifier.showRestartPrompt(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY // user tapped Start twice: nothing more to do
        started = true

        if (!prepare()) {
            stopSelf()
            return START_NOT_STICKY
        }

        settings.protectionWanted = true
        eventLog.add(EventType.PROTECTION_STARTED)
        screenOn = powerManager.isInteractive
        registerScreenReceiver()
        applyState()
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)

        // START_STICKY: if Android kills us, it tries to restart us. On Android 14/15 that restart is
        // refused for camera services, and enterForeground() above then shows "Tap to restart".
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifier.ID_STATUS,
            Notifier.statusNotification(this, getString(GuardStatus.ACTIVE.textRes)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not start as foreground service", e)
        false
    }

    /** Loads the face profile and models. Returns false if something needed is missing. */
    private fun prepare(): Boolean {
        if (!Permissions.hasCamera(this)) {
            endStatus = GuardStatus.PERMISSION_MISSING
            return false
        }
        profile = ProfileStore(this).load()
        if (profile == null || !FaceEmbedder.isModelAvailable(this)) {
            endStatus = GuardStatus.SETUP_NEEDED
            return false
        }
        return try {
            val newEmbedder = FaceEmbedder(this)
            embedder = newEmbedder
            // A profile made by a different model has another length and can never be compared.
            // Without this check every picture would fail silently.
            if (profile?.size != newEmbedder.embeddingSize) {
                Log.e(TAG, "Profile size ${profile?.size} does not match model size ${newEmbedder.embeddingSize}")
                eventLog.add(EventType.PROFILE_INVALID)
                endStatus = GuardStatus.SETUP_NEEDED
                return false
            }
            finder = FaceFinder()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not load face models", e)
            endStatus = GuardStatus.SETUP_NEEDED
            false
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // It was never registered.
        }
        unbindCamera()

        // Close the models on the analysis thread, after any running work is finished.
        val embedderToClose = embedder
        val finderToClose = finder
        try {
            analysisExecutor.execute {
                embedderToClose?.close()
                finderToClose?.close()
            }
        } catch (e: RejectedExecutionException) {
            // Already shut down.
        }
        analysisExecutor.shutdown()

        if (started) eventLog.add(EventType.PROTECTION_STOPPED)
        GuardState.status = endStatus
        GuardState.detection = null
        GuardState.lastScore = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state machine (main thread)

    /** Decides if the camera should run right now, starts or stops it, and publishes the status. */
    private fun applyState() {
        lowBattery = computeLowBattery()
        val shouldRun = screenOn && !lowBattery // pause analysis when the screen is off or battery is low
        if (shouldRun && !cameraBound) bindCamera()
        if (!shouldRun && cameraBound) unbindCamera()
        publishStatus()
    }

    private fun publishStatus() {
        val status = when {
            !screenOn -> GuardStatus.PAUSED_SCREEN_OFF
            lowBattery -> GuardStatus.PAUSED_LOW_BATTERY
            cameraBusy -> GuardStatus.PAUSED_CAMERA_BUSY
            degraded -> GuardStatus.DEGRADED
            else -> GuardStatus.ACTIVE
        }
        if (status != GuardState.status) {
            val before = GuardState.status
            GuardState.status = status
            Notifier.updateStatus(this, getString(status.textRes))
            if (status == GuardStatus.PAUSED_CAMERA_BUSY) eventLog.add(EventType.CAMERA_BUSY)
            if (before == GuardStatus.PAUSED_CAMERA_BUSY && status == GuardStatus.ACTIVE) eventLog.add(EventType.CAMERA_RESUMED)
        }
        if (status != GuardStatus.ACTIVE) {
            GuardState.detection = null
            GuardState.lastScore = null
        }
    }

    /** Runs every few seconds: re-checks everything and retries the camera if it is busy or silent. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!Permissions.hasCamera(this@PrivacyGuardService)) {
                endStatus = GuardStatus.PERMISSION_MISSING // permission was removed in system settings
                stopSelf()
                return
            }
            screenOn = powerManager.isInteractive
            applyState()

            if (cameraBound) {
                val lastSign = max(lastFrameAt, cameraBoundAt)
                val silent = SystemClock.elapsedRealtime() - lastSign > NO_FRAME_MS
                if (silent) cameraBusy = true // no frames for a while: do not pretend we are watching
                if (cameraBusy) {
                    rebindCamera() // try again; CameraX reports OPEN as soon as the camera is free
                    publishStatus()
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            applyState()
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Low battery: 15% or less and not charging. Resumes at 20% or when charging (so it does not flip back and forth). */
    private fun computeLowBattery(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        val percent = level * 100 / scale
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return when {
            charging -> false
            lowBattery -> percent < LOW_BATTERY_RESUME_PERCENT
            else -> percent <= LOW_BATTERY_PERCENT
        }
    }

    // ---------------------------------------------------------------- camera (main thread)

    private fun bindCamera() {
        cameraBound = true
        cameraBoundAt = SystemClock.elapsedRealtime()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraBound) return@addListener // stopped while we were waiting
            try {
                cameraProvider = future.get()
                attachAnalysis()
            } catch (e: Exception) {
                Log.w(TAG, "Camera provider failed", e)
                cameraBusy = true
                publishStatus()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun attachAnalysis() {
        val provider = cameraProvider ?: return
        detachAnalysis()

        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        useCase.setAnalyzer(analysisExecutor) { image -> onFrame(image) }

        try {
            val camera: Camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
            analysis = useCase
            val live = camera.cameraInfo.cameraState
            live.observe(this, cameraStateObserver)
            cameraStateLive = live
            startOrientationListener()
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind the camera", e)
            cameraBusy = true
            publishStatus()
        }
    }

    /** CameraX tells us when the camera is opened, or why it could not be opened (for example in use). */
    private val cameraStateObserver = Observer<CameraState> { state ->
        when {
            state.error != null -> cameraBusy = true // camera in use by another app, disabled, etc.
            state.type == CameraState.Type.OPEN -> cameraBusy = false
        }
        publishStatus()
    }

    /** Games are often played in landscape. Tell CameraX how the phone is held so faces stay upright. */
    private fun startOrientationListener() {
        val listener = orientationListener ?: object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                analysis?.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        orientationListener = listener
        if (listener.canDetectOrientation()) listener.enable()
    }

    private fun detachAnalysis() {
        cameraStateLive?.removeObserver(cameraStateObserver)
        cameraStateLive = null
        orientationListener?.disable()
        analysis?.let {
            it.clearAnalyzer()
            cameraProvider?.unbind(it)
        }
        analysis = null
    }

    private fun unbindCamera() {
        cameraBound = false
        detachAnalysis()
    }

    private fun rebindCamera() {
        cameraBoundAt = SystemClock.elapsedRealtime()
        if (cameraProvider != null) attachAnalysis() else bindCamera()
    }

    // ---------------------------------------------------------------- frames (analysis thread)

    private fun onFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        if (cameraBusy) {
            cameraBusy = false // frames are arriving again, so the camera works
            mainHandler.post { publishStatus() }
        }
        // Only about 2-3 frames per second are analysed. All other frames are dropped right away.
        if (now - lastAnalyzedAt < FRAME_GAP_MS || !detecting.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAnalyzedAt = now

        val bitmap = try {
            FaceImages.uprightBitmap(image)
        } catch (e: Exception) {
            null
        } finally {
            image.close() // we have our own copy now; give the camera buffer back
        }
        val faceFinder = finder
        if (faceFinder == null) {
            bitmap?.recycle()
            detecting.set(false)
            return
        }
        if (bitmap == null) {
            recordFailure() // the camera picture could not be converted
            detecting.set(false)
            return
        }

        try {
            faceFinder.find(bitmap, safeExecutor) { faces ->
                try {
                    if (faces == null) {
                        recordFailure() // ML Kit could not process this picture
                    } else {
                        handleFaces(bitmap, faces)
                        recordSuccess()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame analysis failed", e)
                    recordFailure() // for example the embedding model threw an exception
                } finally {
                    bitmap.recycle() // the frame is discarded; it was never saved
                    detecting.set(false)
                }
            }
        } catch (e: Exception) {
            bitmap.recycle()
            detecting.set(false)
            recordFailure()
        }
    }

    /** Called on the analysis thread. After 5 failures in a row the status changes to "degraded". */
    private fun recordFailure() {
        if (failures.failure()) {
            degraded = true
            eventLog.add(EventType.ANALYSIS_FAILING)
            mainHandler.post { publishStatus() }
        }
    }

    private fun recordSuccess() {
        if (failures.success()) {
            degraded = false
            eventLog.add(EventType.ANALYSIS_RECOVERED)
            mainHandler.post { publishStatus() }
        }
    }

    private fun handleFaces(bitmap: Bitmap, faces: List<FoundFace>) {
        val myProfile = profile ?: return
        val faceEmbedder = embedder ?: return
        val thresholds = settings.thresholds // read every time, so Settings changes work immediately

        val detector = OtherPersonDetector(thresholds, myProfile)
        val result = detector.analyze(faces.map { it.pose() }) { index ->
            faceEmbedder.embed(FaceImages.cropFace(bitmap, faces[index].box))
        }

        val setup = Pair(thresholds.windowSize, thresholds.requiredCount)
        if (setup != filterSetup) {
            filter = TemporalFilter(setup.first, setup.second)
            filterSetup = setup
        }
        val confirmed = filter.add(result.otherPersonLooking)

        // What the main screen shows. Only shown while the status is Active (see publishStatus).
        if (GuardState.status == GuardStatus.ACTIVE) {
            GuardState.detection = result.state
            GuardState.lastScore = result.bestMatch
        }

        if (confirmed && cooldown.tryAlert(SystemClock.elapsedRealtime())) {
            Notifier.showAlert(this, settings.vibration)
            // Log what the alert was about: a confident stranger, or (only if the user chose so) an uncertain face.
            if (result.state == FrameState.UNCERTAIN) {
                eventLog.add(EventType.UNCERTAIN_ALERT, result.lowestMatch)
            } else {
                eventLog.add(EventType.OTHER_PERSON_ALERT, result.lowestMatch)
            }
        }
    }

    companion object {
        private const val TAG = "PrivacyGuard"
        const val ACTION_STOP = "com.example.privacyguard.STOP"

        private const val FRAME_GAP_MS = 400L          // about 2.5 frames per second
        private const val WATCHDOG_MS = 5_000L         // retry / re-check every 5 seconds
        private const val NO_FRAME_MS = 10_000L        // no frames for 10 s = camera is not really working
        private const val LOW_BATTERY_PERCENT = 15
        private const val LOW_BATTERY_RESUME_PERCENT = 20

        /** Call only while the app is visible (the user tapped "Start Protection"). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PrivacyGuardService::class.java))
        }

        fun stop(context: Context) {
            AppSettings(context).protectionWanted = false
            context.stopService(Intent(context, PrivacyGuardService::class.java))
        }
    }
}
