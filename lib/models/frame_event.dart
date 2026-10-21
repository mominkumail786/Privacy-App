import '../logic/enrollment_machine.dart';

/// What the native camera session tells Dart about ONE analysed camera picture. Numbers only, never pixels.
class FrameEvent {
  const FrameEvent({
    required this.frameId,
    required this.timeMs,
    required this.latencyMs,
    required this.faceCount,
    required this.pose,
    required this.facing,
    required this.similarity,
  });

  /// Identifies the picture; `captureSample` refers to it.
  final int frameId;

  /// Native monotonic clock (SystemClock.elapsedRealtime) in milliseconds when the picture was handled.
  final int timeMs;

  /// Time from the camera to this result.
  final int latencyMs;
  final int faceCount;

  /// Set only when exactly one face is in view.
  final PoseSample? pose;

  /// Only when similarity was requested and exactly one face is in view: does that face look at the screen
  /// (same rule as the real protection)?
  final bool facing;

  /// Only when similarity was requested, exactly one facing face is in view and a profile exists.
  final double? similarity;

  static FrameEvent? fromMap(Map<Object?, Object?> m) {
    final id = (m['frameId'] as num?)?.toInt();
    final time = (m['t'] as num?)?.toInt();
    final faces = (m['faces'] as num?)?.toInt();
    if (id == null || time == null || faces == null) return null;
    final p = m['pose'];
    PoseSample? pose;
    if (p is Map) {
      double n(String k) => (p[k] as num?)?.toDouble() ?? 0;
      pose = PoseSample(yaw: n('yaw'), pitch: n('pitch'), roll: n('roll'), sizeRatio: n('size'), centerX: n('cx'), centerY: n('cy'));
    }
    return FrameEvent(
      frameId: id,
      timeMs: time,
      latencyMs: (m['latencyMs'] as num?)?.toInt() ?? 0,
      faceCount: faces,
      pose: faces == 1 ? pose : null,
      facing: m['facing'] == true,
      similarity: (m['similarity'] as num?)?.toDouble(),
    );
  }
}

/// Why a camera session sent an error instead of a picture.
enum CameraProblem { detectionFailed, cameraFailed }

/// Why a sample could not be taken from a picture.
enum SampleProblem { stale, tooDark, tooBright, blurry, notReady, failed }

/// Answer of `captureSample`: either a 192-number face vector or the reason there is none.
class CaptureResult {
  const CaptureResult.ok(List<double> this.embedding)
      : problem = null;
  const CaptureResult.problem(SampleProblem this.problem) : embedding = null;

  final List<double>? embedding;
  final SampleProblem? problem;
  bool get ok => embedding != null;

  static CaptureResult fromMap(Map<Object?, Object?> m) {
    final e = m['embedding'];
    if (m['ok'] == true && e is List) {
      return CaptureResult.ok(e.map((x) => (x as num).toDouble()).toList(growable: false));
    }
    switch (m['problem']) {
      case 'STALE':
        return const CaptureResult.problem(SampleProblem.stale);
      case 'TOO_DARK':
        return const CaptureResult.problem(SampleProblem.tooDark);
      case 'TOO_BRIGHT':
        return const CaptureResult.problem(SampleProblem.tooBright);
      case 'BLURRY':
        return const CaptureResult.problem(SampleProblem.blurry);
      case 'NOT_READY':
        return const CaptureResult.problem(SampleProblem.notReady);
      default:
        return const CaptureResult.problem(SampleProblem.failed);
    }
  }
}
