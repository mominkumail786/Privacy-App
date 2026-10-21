/// Measures how fast pictures are processed: pictures per second (last 2 seconds) and the average time one picture
/// needs from the camera to the result. Used to show real numbers on the enrollment screen.
class FrameRateMeter {
  FrameRateMeter({this.windowMs = 2000});

  final int windowMs;
  final List<int> _times = [];
  double _averageLatencyMs = 0;

  double get averageLatencyMs => _averageLatencyMs;

  void record(int nowMs, int latencyMs) {
    _times.add(nowMs);
    while (_times.isNotEmpty && nowMs - _times.first > windowMs) {
      _times.removeAt(0);
    }
    _averageLatencyMs = _averageLatencyMs == 0 ? latencyMs.toDouble() : _averageLatencyMs * 0.8 + latencyMs * 0.2;
  }

  double get picturesPerSecond {
    if (_times.length < 2) return 0;
    final span = _times.last - _times.first;
    return span <= 0 ? 0 : (_times.length - 1) * 1000 / span;
  }
}
