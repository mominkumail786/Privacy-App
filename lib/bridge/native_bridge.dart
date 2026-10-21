import '../logic/thresholds.dart';
import '../models/app_settings.dart';
import '../models/frame_event.dart';
import '../models/guard_event.dart';
import '../models/guard_status.dart';

/// A message from the camera session: a picture result, or a problem.
sealed class CameraMessage {
  const CameraMessage();
}

class CameraFrame extends CameraMessage {
  const CameraFrame(this.frame);
  final FrameEvent frame;
}

class CameraError extends CameraMessage {
  const CameraError(this.problem);
  final CameraProblem problem;
}

/// Everything Dart needs from the Android side. The real implementation talks over a MethodChannel; the tests use
/// a fake. Nothing here carries pictures: only numbers, flags and the encrypted-profile commands.
///
/// WHY THE NATIVE SIDE EXISTS: the protection must keep watching the camera while the app is closed. On Android 14/15
/// that is only allowed from a native foreground service of type "camera". Flutter code stops when the app is not
/// visible, so that service, the camera, ML Kit, the TFLite model and the Keystore stay in Kotlin.
abstract class NativeBridge {
  // ---- protection service
  Future<GuardSnapshot> getSnapshot();

  /// Asks the service to start. Call only while the app is visible (Android rule).
  Future<void> startProtection();
  Future<void> stopProtection();

  /// True once if the user tapped "Tap to restart Privacy Guard" (after a phone restart). Cleared by reading.
  Future<bool> consumeAutoStart();

  // ---- settings
  Future<AppSettingsData> getSettings();
  Future<AppSettingsData> setOnboardingDone(bool done);
  Future<AppSettingsData> setVibration(bool on);
  Future<AppSettingsData> setCooldownSeconds(int seconds);

  /// Picking a level overwrites all advanced numbers (the "alert on uncertain" choice is kept).
  Future<AppSettingsData> applySensitivity(Sensitivity level);
  Future<AppSettingsData> setThresholds(Thresholds thresholds);

  // ---- event log
  Future<List<GuardEvent>> getEvents();
  Future<void> clearEvents();
  Future<void> addEvent(EventType type, {double? score});

  // ---- profile
  /// Stops the service, overwrites and deletes the encrypted profile and its Keystore key.
  Future<void> deleteProfile();

  /// Saves the profile encrypted. True only if it was read back and decrypted to the same numbers.
  Future<bool> saveProfile(List<double> profile);

  // ---- system screens
  Future<void> requestIgnoreBatteryOptimizations();
  Future<void> openBatteryList();
  Future<String> appVersion();

  // ---- camera session (enrollment and calibration only; the protection service has its own camera)
  /// Starts the front camera, shown in the camera preview widget that is on screen. Returns false if it failed.
  Future<bool> startCamera({bool computeSimilarity = false});
  Future<void> stopCamera();

  /// One message per analysed picture. Newer pictures are dropped on the native side while one is being analysed.
  Stream<CameraMessage> get cameraMessages;

  /// Takes the face of picture [frameId] (the latest one still held in memory) and turns it into a face vector.
  Future<CaptureResult> captureSample(int frameId);
}
