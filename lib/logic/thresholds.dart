/// Three ready-made sensitivity levels. The Settings screen lets the user pick one.
enum Sensitivity { low, medium, high }

/// All numbers that decide "is another person looking at my screen?".
///
/// The real decision runs in the native Kotlin service (it must also work when the app is closed).
/// This Dart copy is used by the screens (calibration, settings) and in tests.
/// A test (test/logic/thresholds_test.dart) checks that these presets are identical to the Kotlin ones.
///
/// A face gets one of three verdicts from its similarity to the owner's profile:
///   similarity >= matchThreshold                  -> owner
///   strangerBelow <= similarity < matchThreshold  -> uncertain (never claimed as owner or stranger)
///   similarity < strangerBelow                    -> stranger
class Thresholds {
  const Thresholds({
    required this.matchThreshold,
    required this.uncertainMargin,
    required this.alertOnUncertain,
    required this.maxHeadAngle,
    required this.minFaceSize,
    required this.windowSize,
    required this.requiredCount,
  });

  /// Cosine similarity (-1..1). A face at or above this is treated as "me".
  final double matchThreshold;

  /// Width of the "not sure" zone below [matchThreshold].
  final double uncertainMargin;

  /// false = a face in the "not sure" zone gives no alert (it is only shown). true = it alerts as well.
  final bool alertOnUncertain;

  /// Head turn limit in degrees. A face turned more than this (left/right or up/down) is NOT facing the screen.
  final double maxHeadAngle;

  /// Smallest face we care about: face width / picture width (0.10 = 10%).
  final double minFaceSize;

  /// Alert only if at least [requiredCount] of the last [windowSize] pictures said "other person looking".
  final int windowSize;
  final int requiredCount;

  /// Below this similarity a face is a confident stranger.
  double get strangerBelow => matchThreshold - uncertainMargin;

  /// Presets, measured with the real face model (see docs/RECOGNITION_REPORT.md).
  factory Thresholds.forSensitivity(Sensitivity s) {
    switch (s) {
      case Sensitivity.low:
        return const Thresholds(
            matchThreshold: 0.35, uncertainMargin: 0.10, alertOnUncertain: false,
            maxHeadAngle: 20, minFaceSize: 0.20, windowSize: 5, requiredCount: 4);
      case Sensitivity.medium:
        return const Thresholds(
            matchThreshold: 0.40, uncertainMargin: 0.10, alertOnUncertain: false,
            maxHeadAngle: 30, minFaceSize: 0.12, windowSize: 5, requiredCount: 3);
      case Sensitivity.high:
        return const Thresholds(
            matchThreshold: 0.50, uncertainMargin: 0.10, alertOnUncertain: false,
            maxHeadAngle: 40, minFaceSize: 0.08, windowSize: 4, requiredCount: 2);
    }
  }

  Thresholds copyWith({
    double? matchThreshold,
    double? uncertainMargin,
    bool? alertOnUncertain,
    double? maxHeadAngle,
    double? minFaceSize,
    int? windowSize,
    int? requiredCount,
  }) {
    return Thresholds(
      matchThreshold: matchThreshold ?? this.matchThreshold,
      uncertainMargin: uncertainMargin ?? this.uncertainMargin,
      alertOnUncertain: alertOnUncertain ?? this.alertOnUncertain,
      maxHeadAngle: maxHeadAngle ?? this.maxHeadAngle,
      minFaceSize: minFaceSize ?? this.minFaceSize,
      windowSize: windowSize ?? this.windowSize,
      requiredCount: requiredCount ?? this.requiredCount,
    );
  }

  Map<String, Object?> toMap() => {
        'matchThreshold': matchThreshold,
        'uncertainMargin': uncertainMargin,
        'alertOnUncertain': alertOnUncertain,
        'maxHeadAngle': maxHeadAngle,
        'minFaceSize': minFaceSize,
        'windowSize': windowSize,
        'requiredCount': requiredCount,
      };

  factory Thresholds.fromMap(Map<Object?, Object?> m) {
    final d = Thresholds.forSensitivity(Sensitivity.medium);
    double num_(String k, double fallback) => (m[k] as num?)?.toDouble() ?? fallback;
    return Thresholds(
      matchThreshold: num_('matchThreshold', d.matchThreshold),
      uncertainMargin: num_('uncertainMargin', d.uncertainMargin),
      alertOnUncertain: (m['alertOnUncertain'] as bool?) ?? d.alertOnUncertain,
      maxHeadAngle: num_('maxHeadAngle', d.maxHeadAngle),
      minFaceSize: num_('minFaceSize', d.minFaceSize),
      windowSize: (m['windowSize'] as num?)?.toInt() ?? d.windowSize,
      requiredCount: (m['requiredCount'] as num?)?.toInt() ?? d.requiredCount,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is Thresholds &&
      other.matchThreshold == matchThreshold &&
      other.uncertainMargin == uncertainMargin &&
      other.alertOnUncertain == alertOnUncertain &&
      other.maxHeadAngle == maxHeadAngle &&
      other.minFaceSize == minFaceSize &&
      other.windowSize == windowSize &&
      other.requiredCount == requiredCount;

  @override
  int get hashCode => Object.hash(matchThreshold, uncertainMargin, alertOnUncertain, maxHeadAngle, minFaceSize,
      windowSize, requiredCount);

  @override
  String toString() => 'Thresholds(${toMap()})';
}
