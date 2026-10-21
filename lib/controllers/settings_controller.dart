import 'package:flutter/foundation.dart';

import '../bridge/native_bridge.dart';
import '../logic/thresholds.dart';
import '../models/app_settings.dart';

/// Settings screen state. The values live on the native side (the service reads them while the app is closed).
/// Sliders change the local value while dragging and save once when the finger is lifted.
class SettingsController extends ChangeNotifier {
  SettingsController(this._bridge);

  final NativeBridge _bridge;
  AppSettingsData data = AppSettingsData.defaults;
  bool loaded = false;
  String version = '1.0';
  bool _disposed = false;

  Future<void> load() async {
    final settings = await _bridge.getSettings();
    final v = await _bridge.appVersion();
    if (_disposed) return;
    data = settings;
    version = v;
    loaded = true;
    notifyListeners();
  }

  Future<void> chooseSensitivity(Sensitivity level) async {
    data = data.copyWith(sensitivity: level, thresholds: Thresholds.forSensitivity(level).copyWith(alertOnUncertain: data.thresholds.alertOnUncertain));
    notifyListeners();
    final saved = await _bridge.applySensitivity(level);
    if (_disposed) return;
    // Take over only what this action changed, so a slider that is still being dragged keeps its value.
    data = data.copyWith(sensitivity: saved.sensitivity, thresholds: saved.thresholds);
    notifyListeners();
  }

  /// While dragging a slider: update the screen only.
  void previewThresholds(Thresholds t) {
    data = data.copyWith(thresholds: t);
    notifyListeners();
  }

  Future<void> commitThresholds() async {
    final saved = await _bridge.setThresholds(data.thresholds);
    if (_disposed) return;
    data = data.copyWith(thresholds: saved.thresholds);
    notifyListeners();
  }

  Future<void> setAlertOnUncertain(bool on) async {
    previewThresholds(data.thresholds.copyWith(alertOnUncertain: on));
    await commitThresholds();
  }

  Future<void> setVibration(bool on) async {
    data = data.copyWith(vibration: on);
    notifyListeners();
    await _bridge.setVibration(on);
  }

  void previewCooldown(int seconds) {
    data = data.copyWith(cooldownSeconds: seconds);
    notifyListeners();
  }

  Future<void> commitCooldown() => _bridge.setCooldownSeconds(data.cooldownSeconds);

  /// Stops protection and erases the profile and its key.
  Future<void> deleteProfile() => _bridge.deleteProfile();

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
