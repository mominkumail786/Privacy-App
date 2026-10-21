import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/logic/enrollment_check.dart';
import 'package:privacyguard/logic/frame_rate_meter.dart';
import 'package:privacyguard/logic/threshold_calibrator.dart';

List<double> _sample(double angleDegrees) {
  final a = angleDegrees * math.pi / 180;
  return [math.cos(a), math.sin(a), 0];
}

List<double> _scores(double center, double spread, [int n = 30]) =>
    [for (var i = 0; i < n; i++) center - spread + 2 * spread * i / (n - 1)];

void main() {
  group('EnrollmentCheck', () {
    test('consistent samples are accepted', () {
      final r = EnrollmentCheck.verify([_sample(0), _sample(10), _sample(-10), _sample(15), _sample(-15)]);
      expect(r.ok, isTrue);
      expect(r.profile, isNotNull);
    });
    test('one sample of a different face is rejected', () {
      expect(EnrollmentCheck.verify([_sample(0), _sample(5), _sample(-5), _sample(8), _sample(100)]).ok, isFalse);
    });
    test('too few samples are rejected', () {
      final r = EnrollmentCheck.verify([_sample(0), _sample(1)]);
      expect(r.ok, isFalse);
      expect(r.profile, isNull);
    });
    test('a turned head is still the same face, a very different one is not', () {
      final first = _sample(0);
      expect(EnrollmentCheck.isSameFace(first, _sample(40)), isTrue);
      expect(EnrollmentCheck.isSameFace(first, _sample(85)), isFalse);
    });
    test('broken embeddings are rejected', () {
      expect(EnrollmentCheck.isValidEmbedding([1, 0, 0], 3), isTrue);
      expect(EnrollmentCheck.isValidEmbedding([1, 0], 3), isFalse);
      expect(EnrollmentCheck.isValidEmbedding([double.nan, 0, 0], 3), isFalse);
      expect(EnrollmentCheck.isValidEmbedding([double.infinity, 0, 0], 3), isFalse);
      expect(EnrollmentCheck.isValidEmbedding([0, 0, 0], 3), isFalse);
      expect(EnrollmentCheck.isValidEmbedding([9, 9, 9], 3), isFalse);
    });
  });

  group('ThresholdCalibrator', () {
    test('separated groups give GOOD and a threshold between them', () {
      final r = ThresholdCalibrator.calibrate(_scores(0.75, 0.1), _scores(0.10, 0.1));
      expect(r.quality, CalibrationQuality.good);
      expect(r.matchThreshold!, greaterThan(r.otherHigh!));
      expect(r.matchThreshold!, lessThan(r.ownerLow!));
      expect(r.uncertainMargin!, lessThanOrEqualTo(0.10));
      expect(r.matchThreshold! - r.uncertainMargin!, greaterThanOrEqualTo(r.otherHigh! - 1e-9));
    });
    test('overlapping groups give OVERLAP and a wide uncertain zone', () {
      final r = ThresholdCalibrator.calibrate(_scores(0.55, 0.15), _scores(0.45, 0.15));
      expect(r.quality, CalibrationQuality.overlap);
      expect(r.uncertainMargin, 0.10);
    });
    test('owner only is flagged and never below the floor', () {
      final r = ThresholdCalibrator.calibrate(_scores(0.30, 0.05), const []);
      expect(r.quality, CalibrationQuality.ownerOnly);
      expect(r.matchThreshold!, greaterThanOrEqualTo(0.30));
    });
    test('too few owner samples give no numbers', () {
      final r = ThresholdCalibrator.calibrate([0.7, 0.8], const []);
      expect(r.quality, CalibrationQuality.notEnoughData);
      expect(r.matchThreshold, isNull);
    });
    test('percentile is correct', () {
      final v = [1.0, 2.0, 3.0, 4.0, 5.0];
      expect(ThresholdCalibrator.percentile(v, 0), 1);
      expect(ThresholdCalibrator.percentile(v, 50), 3);
      expect(ThresholdCalibrator.percentile(v, 100), 5);
      expect(ThresholdCalibrator.percentile(v, 5), closeTo(1.2, 1e-9));
    });
  });

  group('FrameRateMeter', () {
    test('measures pictures per second and latency', () {
      final m = FrameRateMeter();
      for (var i = 0; i <= 20; i++) {
        m.record(i * 100, 80);
      }
      expect(m.picturesPerSecond, closeTo(10, 0.5));
      expect(m.averageLatencyMs, closeTo(80, 1));
    });
    test('old samples leave the window', () {
      final m = FrameRateMeter(windowMs: 1000);
      m.record(0, 50);
      m.record(100, 50);
      m.record(10000, 50);
      expect(m.picturesPerSecond, 0);
    });
  });
}
