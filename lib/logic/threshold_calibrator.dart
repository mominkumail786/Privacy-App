import 'dart:math' as math;

enum CalibrationQuality {
  /// Owner and stranger scores are clearly apart.
  good,

  /// They overlap or are very close: the model cannot separate you well on this phone.
  overlap,

  /// Only the owner was measured. The stranger side is an assumption.
  ownerOnly,
  notEnoughData,
}

class CalibrationResult {
  const CalibrationResult({
    required this.quality,
    required this.matchThreshold,
    required this.uncertainMargin,
    required this.ownerLow,
    required this.otherHigh,
  });

  final CalibrationQuality quality;
  final double? matchThreshold;
  final double? uncertainMargin;

  /// Low end (5th percentile) of the owner's scores.
  final double? ownerLow;

  /// High end (95th percentile) of the other person's scores.
  final double? otherHigh;
}

/// Turns measured scores into thresholds, so the numbers come from data on THIS phone and not from a guess.
///
/// ownerScores: similarity of the owner's live face to the stored profile.
/// otherScores: similarity of another person's face to the same profile (may be empty).
class ThresholdCalibrator {
  const ThresholdCalibrator._();

  static const int minSamples = 10;
  static const double _minGoodGap = 0.10;
  static const double _maxMargin = 0.10;
  static const double _minThreshold = 0.25;
  static const double _maxThreshold = 0.70;

  // With only the owner measured, we stay a little under the owner's low end, but never below 0.30: in the
  // measurements, different people on clean photos reached about 0.30 (95th percentile).
  static const double _ownerOnlyFloor = 0.30;
  static const double _ownerOnlySlack = 0.05;

  /// Value at percentile [p] (0..100), linear between neighbours.
  static double percentile(List<double> values, double p) {
    if (values.isEmpty) throw ArgumentError('values must not be empty');
    final sorted = List<double>.of(values)..sort();
    final position = (p / 100) * (sorted.length - 1);
    final low = position.floor();
    final high = math.min(low + 1, sorted.length - 1);
    final fraction = position - low;
    return sorted[low] + (sorted[high] - sorted[low]) * fraction;
  }

  static CalibrationResult calibrate(List<double> ownerScores, List<double> otherScores) {
    if (ownerScores.length < minSamples) {
      return const CalibrationResult(
          quality: CalibrationQuality.notEnoughData, matchThreshold: null, uncertainMargin: null, ownerLow: null, otherHigh: null);
    }
    final ownerLow = percentile(ownerScores, 5);

    if (otherScores.length < minSamples) {
      final match = math.max(_ownerOnlyFloor, math.min(_maxThreshold, ownerLow - _ownerOnlySlack));
      return CalibrationResult(
          quality: CalibrationQuality.ownerOnly, matchThreshold: match, uncertainMargin: _maxMargin, ownerLow: ownerLow, otherHigh: null);
    }

    final otherHigh = percentile(otherScores, 95);
    final gap = ownerLow - otherHigh;
    final middle = (ownerLow + otherHigh) / 2;
    final match = math.max(_minThreshold, math.min(_maxThreshold, middle));
    if (gap >= _minGoodGap) {
      // The "not sure" zone is exactly the gap between the two groups (at most 0.10 wide).
      return CalibrationResult(
          quality: CalibrationQuality.good,
          matchThreshold: match,
          uncertainMargin: math.min(_maxMargin, gap / 2),
          ownerLow: ownerLow,
          otherHigh: otherHigh);
    }
    // Not separable: keep a wide "not sure" zone so the app does not make confident wrong claims.
    return CalibrationResult(
        quality: CalibrationQuality.overlap, matchThreshold: match, uncertainMargin: _maxMargin, ownerLow: ownerLow, otherHigh: otherHigh);
  }
}
