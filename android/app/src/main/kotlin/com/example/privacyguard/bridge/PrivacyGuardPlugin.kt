package com.example.privacyguard.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.example.privacyguard.MainActivity
import com.example.privacyguard.data.AppSettings
import com.example.privacyguard.data.EventLogStore
import com.example.privacyguard.data.ProfileStore
import com.example.privacyguard.face.FaceEmbedder
import com.example.privacyguard.logic.EventType
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.logic.Thresholds
import com.example.privacyguard.service.GuardState
import com.example.privacyguard.service.PrivacyGuardService
import com.example.privacyguard.util.Permissions
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * The bridge between the Flutter screens and the native side (service, camera, ML Kit, model, Keystore).
 * Method and channel names must match `lib/bridge/method_channel_bridge.dart`.
 *
 * Numbers only cross this bridge: flags, thresholds, face vectors, head angles. Never pictures.
 */
class PrivacyGuardPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private lateinit var context: Context
    private lateinit var control: MethodChannel
    private lateinit var cameraEvents: EventChannel
    private val mainHandler = Handler(Looper.getMainLooper())

    private val settings by lazy { AppSettings(context) }
    private val eventLog by lazy { EventLogStore(context) }
    private val profileStore by lazy { ProfileStore(context) }

    private var sink: EventChannel.EventSink? = null
    private var session: FaceSession? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        control = MethodChannel(binding.binaryMessenger, CONTROL_CHANNEL).also { it.setMethodCallHandler(this) }
        cameraEvents = EventChannel(binding.binaryMessenger, CAMERA_CHANNEL).also { it.setStreamHandler(this) }
        binding.platformViewRegistry.registerViewFactory(PREVIEW_VIEW_TYPE, CameraPreviewFactory())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stopSession()
        control.setMethodCallHandler(null)
        cameraEvents.setStreamHandler(null)
    }

    // ---- camera messages to Dart
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    private fun send(message: Map<String, Any?>) {
        mainHandler.post { sink?.success(message) }
    }

    // ---- control calls from Dart
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getSnapshot" -> result.success(snapshot())
                "startProtection" -> {
                    // Only called while the app is visible (Android rule for starting a camera service).
                    PrivacyGuardService.start(context)
                    result.success(null)
                }
                "stopProtection" -> {
                    PrivacyGuardService.stop(context)
                    result.success(null)
                }
                "consumeAutoStart" -> result.success(MainActivity.consumeAutoStart())

                "getSettings" -> result.success(settingsMap())
                "updateSettings" -> {
                    updateSettings(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                    result.success(settingsMap())
                }
                "applySensitivity" -> {
                    val level = Sensitivity.valueOf(call.argument<String>("level") ?: "MEDIUM")
                    settings.applySensitivity(level)
                    result.success(settingsMap())
                }

                "getEvents" -> result.success(
                    eventLog.all().map {
                        mapOf("time" to it.timeMillis, "type" to it.type.name, "score" to it.score?.toCleanDouble())
                    },
                )
                "clearEvents" -> {
                    eventLog.clear()
                    result.success(null)
                }
                "addEvent" -> {
                    val type = EventType.valueOf(call.argument<String>("type") ?: "")
                    eventLog.add(type, call.argument<Number>("score")?.toFloat())
                    result.success(null)
                }

                "deleteProfile" -> {
                    PrivacyGuardService.stop(context) // protection cannot work without the profile
                    profileStore.delete() // overwrites the file, deletes it, deletes the Keystore key
                    result.success(null)
                }
                "saveProfile" -> {
                    val values = toFloats(call.argument<Any>("profile"))
                    // Saved to a temporary file, read back and decrypted, and only then moved over the old profile.
                    val ok = values.isNotEmpty() && profileStore.save(values) && profileStore.load()?.size == values.size
                    result.success(ok)
                }

                "requestIgnoreBatteryOptimizations" -> {
                    // One-tap system dialog. If the phone does not have it, fall back to the full list of apps.
                    try {
                        startSystemScreen(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    } catch (e: Exception) {
                        openBatteryList()
                    }
                    result.success(null)
                }
                "openBatteryList" -> {
                    openBatteryList()
                    result.success(null)
                }
                "appVersion" -> result.success(appVersion())

                "startCamera" -> startCamera(call.argument<Boolean>("computeSimilarity") == true, result)
                "stopCamera" -> {
                    stopSession()
                    result.success(null)
                }
                "captureSample" -> {
                    val current = session
                    val frameId = call.argument<Int>("frameId") ?: -1
                    if (current == null) {
                        result.success(mapOf("ok" to false, "problem" to "NOT_READY"))
                    } else {
                        current.captureSample(frameId) { answer -> mainHandler.post { result.success(answer) } }
                    }
                }

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("native_error", e.message ?: e.javaClass.simpleName, null)
        }
    }

    // ---- camera session
    private fun startCamera(computeSimilarity: Boolean, result: MethodChannel.Result) {
        val view = PreviewRegistry.current
        if (view == null) {
            result.success(false) // the camera picture view does not exist yet
            return
        }
        stopSession()
        val started = FaceSession(context, computeSimilarity) { message -> send(message) }
        session = started
        started.start(view) { ok ->
            if (!ok) {
                if (session === started) session = null
                started.close()
            }
            result.success(ok)
        }
    }

    private fun stopSession() {
        session?.close()
        session = null
    }

    // ---- data for Dart
    private fun snapshot(): Map<String, Any?> {
        val power = context.getSystemService(PowerManager::class.java)
        return mapOf(
            "status" to GuardState.status.name,
            "detection" to GuardState.detection?.name,
            "lastScore" to GuardState.lastScore?.toCleanDouble(),
            "cameraGranted" to Permissions.hasCamera(context),
            "notificationsGranted" to Permissions.hasNotifications(context),
            "hasProfile" to profileStore.hasProfile(),
            "modelAvailable" to FaceEmbedder.isModelAvailable(context),
            "batteryOptimized" to (power?.isIgnoringBatteryOptimizations(context.packageName) != true),
        )
    }

    private fun settingsMap(): Map<String, Any?> = mapOf(
        "onboardingDone" to settings.onboardingDone,
        "vibration" to settings.vibration,
        "cooldownSeconds" to settings.cooldownSeconds,
        "sensitivity" to settings.sensitivity.name,
        "thresholds" to thresholdsMap(settings.thresholds),
    )

    private fun thresholdsMap(t: Thresholds): Map<String, Any?> = mapOf(
        "matchThreshold" to t.matchThreshold.toCleanDouble(),
        "uncertainMargin" to t.uncertainMargin.toCleanDouble(),
        "alertOnUncertain" to t.alertOnUncertain,
        "maxHeadAngle" to t.maxHeadAngle.toCleanDouble(),
        "minFaceSize" to t.minFaceSize.toCleanDouble(),
        "windowSize" to t.windowSize,
        "requiredCount" to t.requiredCount,
    )

    private fun updateSettings(patch: Map<*, *>) {
        (patch["onboardingDone"] as? Boolean)?.let { settings.onboardingDone = it }
        (patch["vibration"] as? Boolean)?.let { settings.vibration = it }
        (patch["cooldownSeconds"] as? Number)?.let { settings.cooldownSeconds = it.toInt().coerceIn(5, 300) }
        (patch["thresholds"] as? Map<*, *>)?.let { settings.thresholds = thresholdsFrom(it, settings.thresholds) }
    }

    /** Builds thresholds from the Dart map. Missing keys keep their current value; values are kept in a sane range. */
    private fun thresholdsFrom(m: Map<*, *>, current: Thresholds): Thresholds {
        fun float(key: String, fallback: Float) = (m[key] as? Number)?.toFloat() ?: fallback
        fun int(key: String, fallback: Int) = (m[key] as? Number)?.toInt() ?: fallback
        val window = int("windowSize", current.windowSize).coerceIn(1, 30)
        return Thresholds(
            matchThreshold = float("matchThreshold", current.matchThreshold).coerceIn(0.05f, 0.95f),
            uncertainMargin = float("uncertainMargin", current.uncertainMargin).coerceIn(0f, 0.5f),
            alertOnUncertain = (m["alertOnUncertain"] as? Boolean) ?: current.alertOnUncertain,
            maxHeadAngle = float("maxHeadAngle", current.maxHeadAngle).coerceIn(5f, 90f),
            minFaceSize = float("minFaceSize", current.minFaceSize).coerceIn(0.02f, 0.8f),
            windowSize = window,
            requiredCount = int("requiredCount", current.requiredCount).coerceIn(1, window),
        )
    }

    private fun toFloats(value: Any?): FloatArray = when (value) {
        is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
        is FloatArray -> value
        is List<*> -> FloatArray(value.size) { (value[it] as Number).toFloat() }
        else -> FloatArray(0)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun openBatteryList() {
        try {
            startSystemScreen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Permissions.openAppSettings(context) // last fallback
        }
    }

    private fun startSystemScreen(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Float 0.4f becomes 0.4, not 0.4000000059604645 (the channel only carries doubles). */
    private fun Float.toCleanDouble(): Double = toString().toDouble()

    companion object {
        const val CONTROL_CHANNEL = "privacyguard/control"
        const val CAMERA_CHANNEL = "privacyguard/camera"
        const val PREVIEW_VIEW_TYPE = "privacyguard/camera_preview"
    }
}
