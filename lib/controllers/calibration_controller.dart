import 'dart:async';

import 'package:flutter/foundation.dart';

import '../bridge/native_bridge.dart';
import '../logic/threshold_calibrator.dart';
import '../models/frame_event.dart';

enum CalibPhase { intro, owner, askOther, other, result }

enum CalibHint { look, onePerson }

/// Measures how similar the OWNER's live face is to the stored profile on THIS phone, and (optionally) how similar
/// ANOTHER person is. From both sets of numbers it suggests thresholds. No pictures are kept.
class CalibrationController extends ChangeNotifier {
  CalibrationController(this._bridge);

  static const int targetSamples = 30; // about 10 seconds of pictures
  static const int minGapMs = 300; // about 3 pictures per second

  final NativeBridge _bridge;
  StreamSubscription<CameraMessage>? _sub;

  CalibPhase phase = CalibPhase.intro;
  int count = 0;
  double? liveScore;
  CalibHint hint = CalibHint.look;
  CalibrationResult? result;
  bool applied = false;
  bool failed = false;

  final List<double> _owner = [];
  final List<double> _other = [];
  int _recording = 0; // 0 = not measuring, 1 = owner, 2 = another person
  int _lastAt = -1000000;
  bool _started = false;
  bool _disposed = false;

  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    _sub = _bridge.cameraMessages.listen(_onMessage);
    final ok = await _bridge.startCamera(computeSimilarity: true);
    if (!ok && !_disposed) {
      failed = true;
      notifyListeners();
    }
  }

  Future<void> pauseCamera() async {
    if (_started && !_disposed) await _bridge.stopCamera();
  }

  Future<void> resumeCamera() async {
    if (_started && !_disposed) await _bridge.startCamera(computeSimilarity: true);
  }

  void beginOwner() {
    _owner.clear();
    _begin(1, CalibPhase.owner);
  }

  void beginOther() {
    _other.clear();
    _begin(2, CalibPhase.other);
  }

  void _begin(int who, CalibPhase next) {
    count = 0;
    liveScore = null;
    hint = CalibHint.look;
    _recording = who;
    phase = next;
    notifyListeners();
  }

  void skipOther() => _showResult();

  void measureAgain() {
    result = null;
    applied = false;
    phase = CalibPhase.intro;
    notifyListeners();
  }

  void _showResult() {
    _recording = 0;
    result = ThresholdCalibrator.calibrate(List.of(_owner), List.of(_other));
    phase = CalibPhase.result;
    notifyListeners();
  }

  void _onMessage(CameraMessage m) {
    if (_disposed || _recording == 0) return;
    switch (m) {
      case CameraError(:final problem):
        if (problem == CameraProblem.cameraFailed) {
          failed = true;
          notifyListeners();
        }
      case CameraFrame(:final frame):
        _onFrame(frame);
    }
  }

  void _onFrame(FrameEvent f) {
    if (f.timeMs - _lastAt < minGapMs) return;
    _lastAt = f.timeMs;
    final score = f.similarity;
    // One face that looks at the screen but has no number (for example no profile): nothing to measure, no hint.
    if (f.faceCount == 1 && f.facing && score == null) return;
    // Exactly one person must be in view and look at the screen, otherwise two people would be mixed up.
    if (f.faceCount != 1 || !f.facing || score == null) {
      hint = CalibHint.onePerson;
      notifyListeners();
      return;
    }
    final mode = _recording;
    final list = mode == 1 ? _owner : _other;
    list.add(score);
    count = list.length;
    liveScore = score;
    hint = CalibHint.look;
    if (count >= targetSamples) {
      _recording = 0;
      if (mode == 1) {
        phase = CalibPhase.askOther;
        notifyListeners();
      } else {
        _showResult();
      }
      return;
    }
    notifyListeners();
  }

  /// Saves the suggested numbers as the active thresholds (the other settings stay as they are).
  Future<void> apply() async {
    final r = result;
    final match = r?.matchThreshold;
    final margin = r?.uncertainMargin;
    if (r == null || match == null || margin == null || applied) return;
    final current = await _bridge.getSettings();
    await _bridge.setThresholds(current.thresholds.copyWith(matchThreshold: match, uncertainMargin: margin));
    if (_disposed) return;
    applied = true;
    notifyListeners();
  }

  Future<void> stop() async {
    _recording = 0;
    await _sub?.cancel();
    _sub = null;
    if (_started) await _bridge.stopCamera();
  }

  @override
  void dispose() {
    _disposed = true;
    _recording = 0;
    _sub?.cancel();
    if (_started) unawaited(_bridge.stopCamera());
    super.dispose();
  }
}
