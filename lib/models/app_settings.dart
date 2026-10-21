import '../logic/thresholds.dart';

/// The user's settings, as stored by the native side (it needs them while the app is closed).
class AppSettingsData {
  const AppSettingsData({
    required this.onboardingDone,
    required this.vibration,
    required this.cooldownSeconds,
    required this.sensitivity,
    required this.thresholds,
  });

  static AppSettingsData defaults = AppSettingsData(
    onboardingDone: false,
    vibration: true,
    cooldownSeconds: 30,
    sensitivity: Sensitivity.medium,
    thresholds: Thresholds.forSensitivity(Sensitivity.medium),
  );

  final bool onboardingDone;
  final bool vibration;
  final int cooldownSeconds;
  final Sensitivity sensitivity;
  final Thresholds thresholds;

  factory AppSettingsData.fromMap(Map<Object?, Object?> m) {
    final d = AppSettingsData.defaults;
    final t = m['thresholds'];
    return AppSettingsData(
      onboardingDone: (m['onboardingDone'] as bool?) ?? d.onboardingDone,
      vibration: (m['vibration'] as bool?) ?? d.vibration,
      cooldownSeconds: (m['cooldownSeconds'] as num?)?.toInt() ?? d.cooldownSeconds,
      sensitivity: _parseSensitivity(m['sensitivity']) ?? d.sensitivity,
      thresholds: t is Map ? Thresholds.fromMap(t.cast<Object?, Object?>()) : d.thresholds,
    );
  }

  AppSettingsData copyWith({
    bool? onboardingDone,
    bool? vibration,
    int? cooldownSeconds,
    Sensitivity? sensitivity,
    Thresholds? thresholds,
  }) =>
      AppSettingsData(
        onboardingDone: onboardingDone ?? this.onboardingDone,
        vibration: vibration ?? this.vibration,
        cooldownSeconds: cooldownSeconds ?? this.cooldownSeconds,
        sensitivity: sensitivity ?? this.sensitivity,
        thresholds: thresholds ?? this.thresholds,
      );

  static Sensitivity? _parseSensitivity(Object? v) {
    for (final s in Sensitivity.values) {
      if (s.name.toUpperCase() == v) return s;
    }
    return null;
  }
}
