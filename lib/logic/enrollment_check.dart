import 'dart:math' as math;

import 'face_math.dart';

/// Result of [EnrollmentCheck.verify].
class EnrollmentVerdict {
  const EnrollmentVerdict({required this.ok, required this.lowestSimilarity, required this.profile});
  final bool ok;
  final double lowestSimilarity;
  final List<double>? profile;
}

/// Checks for the enrollment samples.
///
/// Each sample is checked when it is taken (is it a valid embedding, and does it look like the first one?).
/// A bad sample only repeats ITS step. It never throws the other steps away.
class EnrollmentCheck {
  const EnrollmentCheck._();

  static const int requiredSamples = 5;

  /// A new sample must look at least this much like the first (straight) sample. Only an "obviously a different
  /// person or a broken picture" guard: in the measurements different people reached 0.30 in only about 5% of the
  /// pairs on clean photos, while the same person was below 0.38 in only 5% of photo pairs taken on different days.
  /// Pictures from one enrollment session look more alike than that.
  static const double minPairSimilarity = 0.30;

  /// True if [e] is a usable vector of the model's size: right length, finite numbers, length close to 1.
  static bool isValidEmbedding(List<double> e, int expectedSize) {
    if (e.length != expectedSize) return false;
    var sum = 0.0;
    for (final x in e) {
      if (!x.isFinite) return false;
      sum += x * x;
    }
    final length = math.sqrt(sum);
    return length >= 0.5 && length <= 1.5;
  }

  /// Does [candidate] look like the same face as the first (straight) sample?
  static bool isSameFace(List<double> first, List<double> candidate) =>
      FaceMath.cosineSimilarity(first, candidate) >= minPairSimilarity;

  /// Final sanity check before saving: 5 valid samples that all agree with their average.
  static EnrollmentVerdict verify(List<List<double>> samples) {
    if (samples.length < requiredSamples) {
      return const EnrollmentVerdict(ok: false, lowestSimilarity: 0, profile: null);
    }
    final size = samples[0].length;
    if (samples.any((s) => !isValidEmbedding(s, size))) {
      return const EnrollmentVerdict(ok: false, lowestSimilarity: 0, profile: null);
    }
    final profile = FaceMath.averageProfile(samples);
    final lowest = samples.map((s) => FaceMath.cosineSimilarity(s, profile)).reduce(math.min);
    return EnrollmentVerdict(ok: lowest >= minPairSimilarity, lowestSimilarity: lowest, profile: profile);
  }
}
