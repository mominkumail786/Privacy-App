import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';

import '../logic/thresholds.dart';
import '../models/app_settings.dart';
import '../models/frame_event.dart';
import '../models/guard_event.dart';
import '../models/guard_status.dart';
import 'native_bridge.dart';

/// The real bridge to the Kotlin side (`PrivacyGuardPlugin`). Channel and method names must match that file.
class MethodChannelBridge implements NativeBridge {
  MethodChannelBridge({MethodChannel? control, EventChannel? camera})
      : _control = control ?? const MethodChannel(controlChannelName),
        _camera = camera ?? const EventChannel(cameraChannelName);

  static const String controlChannelName = 'privacyguard/control';
  static const String cameraChannelName = 'privacyguard/camera';
  static const String previewViewType = 'privacyguard/camera_preview';

  final MethodChannel _control;
  final EventChannel _camera;

  Future<Map<Object?, Object?>> _map(String method, [Object? args]) async {
    final r = await _control.invokeMethod<Map<Object?, Object?>>(method, args);
    return r ?? const <Object?, Object?>{};
  }

  @override
  Future<GuardSnapshot> getSnapshot() async => GuardSnapshot.fromMap(await _map('getSnapshot'));

  @override
  Future<void> startProtection() => _control.invokeMethod<void>('startProtection');

  @override
  Future<void> stopProtection() => _control.invokeMethod<void>('stopProtection');

  @override
  Future<bool> consumeAutoStart() async => (await _control.invokeMethod<bool>('consumeAutoStart')) ?? false;

  @override
  Future<AppSettingsData> getSettings() async => AppSettingsData.fromMap(await _map('getSettings'));

  Future<AppSettingsData> _update(Map<String, Object?> patch) async =>
      AppSettingsData.fromMap(await _map('updateSettings', patch));

  @override
  Future<AppSettingsData> setOnboardingDone(bool done) => _update({'onboardingDone': done});

  @override
  Future<AppSettingsData> setVibration(bool on) => _update({'vibration': on});

  @override
  Future<AppSettingsData> setCooldownSeconds(int seconds) => _update({'cooldownSeconds': seconds});

  @override
  Future<AppSettingsData> setThresholds(Thresholds thresholds) => _update({'thresholds': thresholds.toMap()});

  @override
  Future<AppSettingsData> applySensitivity(Sensitivity level) async =>
      AppSettingsData.fromMap(await _map('applySensitivity', {'level': level.name.toUpperCase()}));

  @override
  Future<List<GuardEvent>> getEvents() async {
    final r = await _control.invokeMethod<List<Object?>>('getEvents');
    final events = <GuardEvent>[];
    for (final e in r ?? const <Object?>[]) {
      if (e is Map) {
        final event = GuardEvent.fromMap(e.cast<Object?, Object?>());
        if (event != null) events.add(event);
      }
    }
    return events;
  }

  @override
  Future<void> clearEvents() => _control.invokeMethod<void>('clearEvents');

  @override
  Future<void> addEvent(EventType type, {double? score}) =>
      _control.invokeMethod<void>('addEvent', {'type': type.nativeName, 'score': score});

  @override
  Future<void> deleteProfile() => _control.invokeMethod<void>('deleteProfile');

  @override
  Future<bool> saveProfile(List<double> profile) async =>
      (await _control.invokeMethod<bool>('saveProfile', {'profile': Float64List.fromList(profile)})) ?? false;

  @override
  Future<void> requestIgnoreBatteryOptimizations() => _control.invokeMethod<void>('requestIgnoreBatteryOptimizations');

  @override
  Future<void> openBatteryList() => _control.invokeMethod<void>('openBatteryList');

  @override
  Future<String> appVersion() async => (await _control.invokeMethod<String>('appVersion')) ?? '1.0';

  @override
  Future<bool> startCamera({bool computeSimilarity = false}) async =>
      (await _control.invokeMethod<bool>('startCamera', {'computeSimilarity': computeSimilarity})) ?? false;

  @override
  Future<void> stopCamera() => _control.invokeMethod<void>('stopCamera');

  @override
  Stream<CameraMessage> get cameraMessages => _camera.receiveBroadcastStream().map(_parse).where((m) => m != null).cast();

  static CameraMessage? _parse(Object? raw) {
    if (raw is! Map) return null;
    final m = raw.cast<Object?, Object?>();
    if (m['type'] == 'error') {
      return CameraError(m['code'] == 'CAMERA_FAILED' ? CameraProblem.cameraFailed : CameraProblem.detectionFailed);
    }
    final frame = FrameEvent.fromMap(m);
    return frame == null ? null : CameraFrame(frame);
  }

  @override
  Future<CaptureResult> captureSample(int frameId) async =>
      CaptureResult.fromMap(await _map('captureSample', {'frameId': frameId}));
}
