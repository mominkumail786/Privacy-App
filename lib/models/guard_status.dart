import '../logic/other_person_detector.dart';

/// The real state of the protection, as reported by the native service. The screen shows exactly this.
enum GuardStatus {
  active,
  pausedCameraBusy,
  pausedScreenOff,
  pausedLowBattery,

  /// The camera works but face analysis keeps failing: nothing is really being checked.
  degraded,
  stopped,
  permissionMissing,
  setupNeeded;

  /// True while the native service is running (even if paused or degraded). The camera is then in use by it.
  bool get isRunning =>
      this == active || this == pausedCameraBusy || this == pausedScreenOff || this == pausedLowBattery || this == degraded;

  bool get isPaused => this == pausedCameraBusy || this == pausedScreenOff || this == pausedLowBattery;

  /// Parses the Kotlin enum name (ACTIVE, PAUSED_CAMERA_BUSY, ...). Unknown or missing = stopped (the safe truth).
  static GuardStatus parse(Object? name) {
    switch (name) {
      case 'ACTIVE':
        return GuardStatus.active;
      case 'PAUSED_CAMERA_BUSY':
        return GuardStatus.pausedCameraBusy;
      case 'PAUSED_SCREEN_OFF':
        return GuardStatus.pausedScreenOff;
      case 'PAUSED_LOW_BATTERY':
        return GuardStatus.pausedLowBattery;
      case 'DEGRADED':
        return GuardStatus.degraded;
      case 'PERMISSION_MISSING':
        return GuardStatus.permissionMissing;
      case 'SETUP_NEEDED':
        return GuardStatus.setupNeeded;
      default:
        return GuardStatus.stopped;
    }
  }
}

/// Everything the home screen needs, read in one call from the native side.
class GuardSnapshot {
  const GuardSnapshot({
    required this.status,
    required this.detection,
    required this.lastScore,
    required this.cameraGranted,
    required this.notificationsGranted,
    required this.hasProfile,
    required this.modelAvailable,
    required this.batteryOptimized,
  });

  /// Used before the first answer arrives.
  static const GuardSnapshot initial = GuardSnapshot(
    status: GuardStatus.stopped,
    detection: null,
    lastScore: null,
    cameraGranted: false,
    notificationsGranted: false,
    hasProfile: false,
    modelAvailable: true,
    batteryOptimized: true,
  );

  final GuardStatus status;

  /// What the last analysed picture showed. null = nothing yet.
  final FrameState? detection;

  /// Best similarity to the owner's profile in the last analysed picture. Numbers only, never pictures.
  final double? lastScore;
  final bool cameraGranted;
  final bool notificationsGranted;
  final bool hasProfile;
  final bool modelAvailable;
  final bool batteryOptimized;

  factory GuardSnapshot.fromMap(Map<Object?, Object?> m) => GuardSnapshot(
        status: GuardStatus.parse(m['status']),
        detection: FrameState.parse(m['detection']),
        lastScore: (m['lastScore'] as num?)?.toDouble(),
        cameraGranted: m['cameraGranted'] == true,
        notificationsGranted: m['notificationsGranted'] == true,
        hasProfile: m['hasProfile'] == true,
        modelAvailable: m['modelAvailable'] != false,
        batteryOptimized: m['batteryOptimized'] != false,
      );

  /// The status to show. If the camera permission was taken away while nothing runs, the truth is "Permission missing".
  GuardStatus get shownStatus => (!cameraGranted && !status.isRunning) ? GuardStatus.permissionMissing : status;

  bool get running => status.isRunning;

  /// Start is allowed only when everything needed is in place.
  bool get canStart => cameraGranted && notificationsGranted && hasProfile && modelAvailable && !running;
}
