import 'dart:math' as math;

/// Small maths helpers for face "number vectors" (embeddings).
class FaceMath {
  const FaceMath._();

  /// Scales a vector so its length is 1. Returns a copy.
  static List<double> l2Normalize(List<double> v) {
    var sum = 0.0;
    for (final x in v) {
      sum += x * x;
    }
    final length = math.sqrt(sum);
    if (length == 0) return List<double>.of(v);
    return [for (final x in v) x / length];
  }

  /// Cosine similarity: 1 = same direction (same person), around 0 = unrelated, -1 = opposite.
  static double cosineSimilarity(List<double> a, List<double> b) {
    if (a.length != b.length) {
      throw ArgumentError('Vectors must have the same size (${a.length} vs ${b.length})');
    }
    var dot = 0.0, na = 0.0, nb = 0.0;
    for (var i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    if (na == 0 || nb == 0) return 0;
    return dot / (math.sqrt(na) * math.sqrt(nb));
  }

  /// Averages several embeddings into one profile vector (length 1). Every face counts the same.
  static List<double> averageProfile(List<List<double>> embeddings) {
    if (embeddings.isEmpty) throw ArgumentError('Need at least one embedding');
    final size = embeddings[0].length;
    final sum = List<double>.filled(size, 0);
    for (final e in embeddings) {
      if (e.length != size) throw ArgumentError('All embeddings must have the same size');
      final n = l2Normalize(e);
      for (var i = 0; i < size; i++) {
        sum[i] += n[i];
      }
    }
    return l2Normalize(sum);
  }
}
