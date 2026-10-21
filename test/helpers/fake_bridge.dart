import 'dart:async';

import 'package:privacyguard/bridge/native_bridge.dart';
import 'package:privacyguard/logic/other_person_detector.dart';
import 'package:privacyguard/logic/thresholds.dart';
import 'package:privacyguard/models/app_settings.dart';
import 'package:privacyguard/models/frame_event.dart';
import 'package:privacyguard/models/guard_event.dart';
import 'package:privacyguard/models/guard_status.dart';
import 'package:privacyguard/services/permission_service.dart';

/// An in-memory stand-in for the Kotlin side. Records every call so tests can check what the app asked for.
class FakeBridge implements NativeBridge {
  FakeBridge({
    this.status = GuardStatus.stopped,
    this.cameraGranted = true,
    this.notificationsGranted = true,
    this.hasProfile = true,
    this.modelAvailable = true,
    this.batteryOptimized = true,
    this.onboardingDone = true,
  });

  GuardStatus status;
  FrameState? detection;
  double? lastScore;
  bool cameraGranted;
  bool notificationsGranted;
  bool hasProfile;
  bool modelAvailable;
  bool batteryOptimized;
  bool onboardingDone;
  bool autoStartPending = false;

  AppSettingsData settings = AppSettingsData.defaults;
  final List<GuardEvent> events = [];
  final List<String> calls = [];

  // camera session
  final StreamController<CameraMessage> _camera = StreamController<CameraMessage>.broadcast();
  bool cameraRunning = false;
  bool cameraStartResult = true;
  bool computeSimilarity = false;
  int cameraStarts = 0;
  int cameraStops = 0;
  bool saveResult = true;
  List<double>? savedProfile;

  /// What `captureSample` answers. Set per test.
  Future<CaptureResult> Function(int frameId) onCapture = (_) async => const CaptureResult.problem(SampleProblem.failed);
  final List<int> captureRequests = [];

  void emit(CameraMessage m) => _camera.add(m);
  bool get hasCameraListener => _camera.hasListener;

  @override
  Future<GuardSnapshot> getSnapshot() async {
    calls.add('getSnapshot');
    return GuardSnapshot(
      status: status,
      detection: detection,
      lastScore: lastScore,
      cameraGranted: cameraGranted,
      notificationsGranted: notificationsGranted,
      hasProfile: hasProfile,
      modelAvailable: modelAvailable,
      batteryOptimized: batteryOptimized,
    );
  }

  @override
  Future<void> startProtection() async {
    calls.add('startProtection');
    status = GuardStatus.active;
  }

  @override
  Future<void> stopProtection() async {
    calls.add('stopProtection');
    status = GuardStatus.stopped;
  }

  @override
  Future<bool> consumeAutoStart() async {
    final r = autoStartPending;
    autoStartPending = false;
    return r;
  }

  @override
  Future<AppSettingsData> getSettings() async => settings.copyWith(onboardingDone: onboardingDone);

  @override
  Future<AppSettingsData> setOnboardingDone(bool done) async {
    calls.add('setOnboardingDone:$done');
    onboardingDone = done;
    return settings = settings.copyWith(onboardingDone: done);
  }

  @override
  Future<AppSettingsData> setVibration(bool on) async {
    calls.add('setVibration:$on');
    return settings = settings.copyWith(vibration: on);
  }

  @override
  Future<AppSettingsData> setCooldownSeconds(int seconds) async {
    calls.add('setCooldown:$seconds');
    return settings = settings.copyWith(cooldownSeconds: seconds);
  }

  @override
  Future<AppSettingsData> applySensitivity(Sensitivity level) async {
    calls.add('applySensitivity:${level.name}');
    return settings = settings.copyWith(
      sensitivity: level,
      thresholds: Thresholds.forSensitivity(level).copyWith(alertOnUncertain: settings.thresholds.alertOnUncertain),
    );
  }

  @override
  Future<AppSettingsData> setThresholds(Thresholds thresholds) async {
    calls.add('setThresholds');
    return settings = settings.copyWith(thresholds: thresholds);
  }

  @override
  Future<List<GuardEvent>> getEvents() async => events.reversed.toList();

  @override
  Future<void> clearEvents() async {
    calls.add('clearEvents');
    events.clear();
  }

  @override
  Future<void> addEvent(EventType type, {double? score}) async {
    calls.add('addEvent:${type.nativeName}');
    events.add(GuardEvent(DateTime(2026, 1, 2, 3, 4, 5).millisecondsSinceEpoch, type, score));
  }

  @override
  Future<void> deleteProfile() async {
    calls.add('deleteProfile');
    hasProfile = false;
    status = GuardStatus.stopped;
  }

  @override
  Future<bool> saveProfile(List<double> profile) async {
    calls.add('saveProfile');
    if (saveResult) {
      savedProfile = profile;
      hasProfile = true;
    }
    return saveResult;
  }

  @override
  Future<void> requestIgnoreBatteryOptimizations() async => calls.add('requestIgnoreBattery');

  @override
  Future<void> openBatteryList() async => calls.add('openBatteryList');

  @override
  Future<String> appVersion() async => '1.2.3';

  @override
  Future<bool> startCamera({bool computeSimilarity = false}) async {
    calls.add('startCamera');
    cameraStarts++;
    this.computeSimilarity = computeSimilarity;
    cameraRunning = cameraStartResult;
    return cameraStartResult;
  }

  @override
  Future<void> stopCamera() async {
    calls.add('stopCamera');
    cameraStops++;
    cameraRunning = false;
  }

  @override
  Stream<CameraMessage> get cameraMessages => _camera.stream;

  @override
  Future<CaptureResult> captureSample(int frameId) {
    captureRequests.add(frameId);
    return onCapture(frameId);
  }

  Future<void> close() => _camera.close();
}

class FakePermissions implements PermissionService {
  FakePermissions(this.bridge);

  final FakeBridge bridge;
  PermissionOutcome nextOutcome = PermissionOutcome.granted;
  final List<AppPermission> requested = [];
  int settingsOpened = 0;

  @override
  Future<PermissionOutcome> request(AppPermission permission) async {
    requested.add(permission);
    if (nextOutcome == PermissionOutcome.granted) {
      if (permission == AppPermission.camera) bridge.cameraGranted = true;
      if (permission == AppPermission.notifications) bridge.notificationsGranted = true;
    }
    return nextOutcome;
  }

  @override
  Future<void> openAppSettings() async => settingsOpened++;
}
