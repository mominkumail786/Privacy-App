import 'dart:async';

import 'package:flutter/foundation.dart';

import '../bridge/native_bridge.dart';
import '../logic/enrollment_check.dart';
import '../logic/enrollment_machine.dart';
import '../logic/frame_rate_meter.dart';
import '../models/frame_event.dart';
import '../models/guard_event.dart';

/// Why the last sample was refused (shown for a moment, then the normal feedback returns).
enum EnrollReject { none, tooDark, tooBright, blurry, notSame, badSample, starting }

/// A problem that stays until the user acts.
enum EnrollFailure { none, detectFailed, saveFailed, cameraFailed }

/// Everything the enrollment screen shows. A new copy is published only when something visible changed.
class EnrollmentUi {
  const EnrollmentUi({
    this.step = EnrollStep.front,
    this.feedback = EnrollFeedback.starting,
    this.progress = 0,
    this.measured = 0,
    this.required = 0,
    this.poseOk = false,
    this.manualAllowed = false,
    this.slow = false,
    this.relaxed = false,
    this.captured = 0,
    this.flash = false,
    this.rejected = EnrollReject.none,
    this.seconds = 0,
    this.fps = 0,
    this.latencyMs = 0,
    this.saving = false,
    this.failure = EnrollFailure.none,
    this.finished = false,
  });

  final EnrollStep step;
  final EnrollFeedback feedback;
  final double progress;
  final int measured;
  final int required;
  final bool poseOk;
  final bool manualAllowed;
  final bool slow;
  final bool relaxed;
  final int captured;
  final bool flash;
  final EnrollReject rejected;
  final int seconds;
  final int fps;
  final int latencyMs;
  final bool saving;
  final EnrollFailure failure;

  /// The profile was saved and verified.
  final bool finished;

  Object get _key => (
        step, feedback, progress, measured, required, poseOk, manualAllowed, slow, relaxed, captured, flash, rejected,
        seconds, fps, latencyMs, saving, failure, finished,
      );

  @override
  bool operator ==(Object other) => other is EnrollmentUi && other._key == _key;

  @override
  int get hashCode => _key.hashCode;
}

/// Connects the camera messages of the native side to the [EnrollmentMachine] (all movement rules are in Dart).
///
/// Per picture: the machine decides; when it says "capture", the native side turns that picture into a face vector;
/// Dart checks the vector, collects 5 of them, averages them and asks the native side to save them encrypted.
/// ONE picture is handled at a time. Newer pictures that arrive meanwhile are dropped (no queue of old pictures).
class EnrollmentController extends ChangeNotifier {
  EnrollmentController(this._bridge, {EnrollmentMachine? machine}) : _machine = machine ?? EnrollmentMachine();

  final NativeBridge _bridge;
  final EnrollmentMachine _machine;
  final List<List<double>> _samples = [];
  final FrameRateMeter _meter = FrameRateMeter();
  StreamSubscription<CameraMessage>? _sub;

  EnrollmentUi ui = const EnrollmentUi();

  bool _started = false;
  bool _disposed = false;
  bool _done = false;
  bool _busy = false;
  bool _saveAttempted = false;
  bool _retryRequested = false;
  bool _restartRequested = false;
  bool _manualRequested = false;
  int _detectFailures = 0;
  int _flashUntil = 0;
  int _rejectedUntil = 0;
  EnrollReject _rejected = EnrollReject.none;
  EnrollFailure _failure = EnrollFailure.none;
  EnrollUpdate? _last;

  /// Called by the screen when the camera preview exists.
  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    _sub = _bridge.cameraMessages.listen(_onMessage);
    await _startCamera();
  }

  Future<void> _startCamera() async {
    final ok = await _bridge.startCamera();
    if (!ok && !_disposed) {
      _failure = EnrollFailure.cameraFailed;
      _publish(_last, 0, false, force: true);
    }
  }

  /// The app went to the background: free the camera. The machine ignores the time that passes meanwhile.
  Future<void> pauseCamera() async {
    if (_started && !_disposed && !_done) await _bridge.stopCamera();
  }

  Future<void> resumeCamera() async {
    if (_started && !_disposed && !_done) await _startCamera();
  }

  // ---- buttons (they only set flags; the picture handler applies them, so the machine has one user)
  void retryStep() => _retryRequested = true;
  void restart() => _restartRequested = true;
  void captureNow() => _manualRequested = true;

  void _onMessage(CameraMessage m) {
    if (_disposed || _done) return;
    switch (m) {
      case CameraError(:final problem):
        if (problem == CameraProblem.cameraFailed) {
          _failure = EnrollFailure.cameraFailed;
        } else if (++_detectFailures >= 10) {
          _failure = EnrollFailure.detectFailed;
        } else {
          return;
        }
        _publish(_last, _last == null ? 0 : _lastTime, false, force: true);
      case CameraFrame(:final frame):
        _onFrame(frame);
    }
  }

  int _lastTime = 0;

  Future<void> _onFrame(FrameEvent f) async {
    if (_busy) return; // still working on an older picture: drop this one
    _busy = true;
    try {
      await _handle(f);
    } catch (e) {
      debugPrint('Enrollment picture failed: $e');
    } finally {
      _busy = false;
    }
  }

  Future<void> _handle(FrameEvent f) async {
    final now = f.timeMs;
    _lastTime = now;
    _detectFailures = 0;
    if (_failure == EnrollFailure.detectFailed || _failure == EnrollFailure.cameraFailed) _failure = EnrollFailure.none;

    if (_restartRequested) {
      _restartRequested = false;
      _machine.restart();
      _samples.clear();
      _saveAttempted = false;
      _failure = EnrollFailure.none;
      _rejectedUntil = 0;
      _flashUntil = 0;
    }
    if (_retryRequested) {
      _retryRequested = false;
      _machine.retryStep(now);
    }
    final manual = _manualRequested;
    _manualRequested = false;

    final update = _machine.onFrame(now, f.faceCount, f.pose, manualRequested: manual);
    _last = update;
    if (update.readyToCapture && f.pose != null) await _tryCapture(f, now);

    _meter.record(now, f.latencyMs);
    var saving = false;
    if (_machine.isFinished && !_saveAttempted) {
      saving = true;
      _publish(update, now, true); // show "Saving..." before the (slower) save
      await _saveProfile(update, now);
    }
    if (!_done && !_disposed) _publish(update, now, saving || (_machine.isFinished && _failure == EnrollFailure.none));
  }

  void _reject(EnrollReject r, int now) {
    _rejected = r;
    _rejectedUntil = now + 1500;
  }

  /// Takes the sample of the current step if the picture is good. Returns true if it was taken.
  Future<bool> _tryCapture(FrameEvent f, int now) async {
    final CaptureResult r = await _bridge.captureSample(f.frameId);
    if (_disposed) return false;
    final problem = r.problem;
    if (problem != null) {
      switch (problem) {
        case SampleProblem.stale:
          break; // a newer picture replaced it; the next picture asks again
        case SampleProblem.tooDark:
          _reject(EnrollReject.tooDark, now);
        case SampleProblem.tooBright:
          _reject(EnrollReject.tooBright, now);
        case SampleProblem.blurry:
          _reject(EnrollReject.blurry, now);
        case SampleProblem.notReady:
          _reject(EnrollReject.starting, now);
        case SampleProblem.failed:
          _reject(EnrollReject.badSample, now);
      }
      return false;
    }
    final embedding = r.embedding!;
    final expectedSize = _samples.isEmpty ? embedding.length : _samples[0].length;
    if (!EnrollmentCheck.isValidEmbedding(embedding, expectedSize)) {
      _reject(EnrollReject.badSample, now);
      return false;
    }
    // Every sample must look like the first (straight) one. A bad sample only repeats ITS step.
    if (_samples.isNotEmpty && !EnrollmentCheck.isSameFace(_samples[0], embedding)) {
      _reject(EnrollReject.notSame, now);
      return false;
    }
    _samples.add(embedding);
    _machine.onCaptured(now);
    _flashUntil = now + 800;
    return true;
  }

  Future<void> _saveProfile(EnrollUpdate update, int now) async {
    _saveAttempted = true;
    final check = EnrollmentCheck.verify(_samples);
    final profile = check.profile;
    if (!check.ok || profile == null) {
      // Should not happen (each sample was checked). Start again instead of saving something doubtful.
      debugPrint('Enrollment final check failed, lowest similarity ${check.lowestSimilarity}');
      _machine.restart();
      _samples.clear();
      _saveAttempted = false;
      _reject(EnrollReject.notSame, now);
      return;
    }
    final saved = await _bridge.saveProfile(profile); // encrypted, read back and compared on the native side
    if (_disposed) return;
    if (saved) {
      _done = true;
      _samples.clear(); // the numbers are saved (encrypted); forget them here
      await _bridge.addEvent(EventType.enrollmentDone, score: update.totalElapsedMs / 1000);
      await _bridge.stopCamera();
      ui = EnrollmentUi(
        step: _machine.step, feedback: EnrollFeedback.ready, captured: 5, seconds: update.totalElapsedMs ~/ 1000, finished: true,
      );
      if (!_disposed) notifyListeners();
    } else {
      _failure = EnrollFailure.saveFailed;
    }
  }

  void _publish(EnrollUpdate? u, int now, bool saving, {bool force = false}) {
    final base = u;
    final next = EnrollmentUi(
      step: _machine.step,
      feedback: base?.feedback ?? EnrollFeedback.starting,
      progress: base == null ? 0 : (base.progress * 20).round() / 20, // steps of 5%: fewer screen updates
      measured: base?.measuredDegrees.round() ?? 0,
      required: base?.requiredDegrees.round() ?? 0,
      poseOk: base?.poseOk ?? false,
      manualAllowed: base?.manualCaptureAllowed ?? false,
      slow: base?.slow ?? false,
      relaxed: base?.relaxed ?? false,
      captured: _machine.capturedCount,
      flash: now < _flashUntil,
      rejected: now < _rejectedUntil ? _rejected : EnrollReject.none,
      seconds: (base?.totalElapsedMs ?? 0) ~/ 1000,
      fps: _meter.picturesPerSecond.round(),
      latencyMs: _meter.averageLatencyMs.round(),
      saving: saving,
      failure: _failure,
    );
    if (next != ui || force) {
      ui = next;
      if (!_disposed) notifyListeners();
    }
  }

  /// Releases the camera. Safe to call more than once.
  Future<void> stop() async {
    await _sub?.cancel();
    _sub = null;
    if (_started) await _bridge.stopCamera();
  }

  @override
  void dispose() {
    _disposed = true;
    _sub?.cancel();
    if (_started) unawaited(_bridge.stopCamera());
    super.dispose();
  }
}
