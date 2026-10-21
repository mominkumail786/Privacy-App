/// The five head positions captured during enrollment.
enum EnrollStep { front, left, right, up, down }

/// One measurement of the (single) visible face in one camera picture. Angles are in degrees.
class PoseSample {
  const PoseSample({
    required this.yaw,
    required this.pitch,
    required this.roll,
    required this.sizeRatio,
    required this.centerX,
    required this.centerY,
  });

  /// Head turn left/right.
  final double yaw;

  /// Head tilt up/down.
  final double pitch;

  /// Head roll.
  final double roll;

  /// Face width / picture width.
  final double sizeRatio;

  /// Face centre in the picture, 0..1 from the left / from the top.
  final double centerX;
  final double centerY;
}

/// All numbers of the enrollment movement detection. Plain values, so they can be tuned.
///
/// Why these values (honest note): the angles are RELATIVE to the user's own neutral pose, which is measured in
/// step 1. A comfortable head turn is roughly 20-30 degrees and a comfortable nod 10-20 degrees, while the face
/// detector's angle noise is a few degrees. So we ask for about half of a comfortable movement. These defaults
/// were tested with simulated users only; they were NOT calibrated on a real phone.
class PoseThresholds {
  const PoseThresholds({
    this.turnEnter = 12,
    this.turnExit = 8,
    this.tiltEnter = 8,
    this.tiltExit = 5,
    this.offAxisMax = 15,
    this.offAxisSlack = 5,
    this.centerMaxYaw = 25,
    this.centerMaxPitch = 35,
    this.centerStableSpread = 5,
    this.frontWindow = 5,
    this.minHoldFrames = 3,
    this.holdMillis = 400,
    this.minFaceSize = 0.22,
    this.maxFaceSize = 0.90,
    this.maxRoll = 25,
    this.frontCenterTolX = 0.25,
    this.frontCenterTolY = 0.30,
    this.hintAfterMillis = 8000,
    this.relaxAfterMillis = 12000,
    this.manualAfterMillis = 15000,
    this.relaxFactor = 0.7,
    this.pauseGapMillis = 3000,
  });

  /// Head turn (yaw) needed, in degrees away from the neutral pose, to count as "turned".
  final double turnEnter;

  /// The turn is only "released" below this (hysteresis: stops flickering at the limit).
  final double turnExit;
  final double tiltEnter;
  final double tiltExit;

  /// While turning, the pitch may change by at most this (and the other way round).
  final double offAxisMax;
  final double offAxisSlack;

  /// Step 1: the only ABSOLUTE limits. Wide on purpose: a phone held at chest height easily gives 25-30 degrees
  /// of pitch, and that must not block step 1.
  final double centerMaxYaw;
  final double centerMaxPitch;

  /// Step 1: the angles of the last pictures may differ by at most this ("hold still").
  final double centerStableSpread;
  final int frontWindow;

  /// A pose must be held for this many pictures and this many milliseconds before it is captured.
  final int minHoldFrames;
  final int holdMillis;
  final double minFaceSize;
  final double maxFaceSize;
  final double maxRoll;
  final double frontCenterTolX;
  final double frontCenterTolY;

  /// Time rules for ONE step.
  final int hintAfterMillis;
  final int relaxAfterMillis;
  final int manualAfterMillis;

  /// After [relaxAfterMillis] the required movement is multiplied by this (a smaller movement is accepted).
  final double relaxFactor;

  /// If no picture arrives for this long, the app was paused: that time is not counted.
  final int pauseGapMillis;
}

/// What the screen should tell the user right now.
enum EnrollFeedback {
  starting,
  noFace,
  multipleFaces,
  tooFar,
  tooClose,
  notCentered,
  headRolled,
  notStraight,
  moveMore,
  wrongDirection,
  offAxis,
  holdStill,
  ready,
}

/// Everything the screen needs after one picture.
class EnrollUpdate {
  const EnrollUpdate({
    required this.step,
    required this.feedback,
    required this.progress,
    required this.measuredDegrees,
    required this.requiredDegrees,
    required this.poseOk,
    required this.readyToCapture,
    required this.manualCaptureAllowed,
    required this.slow,
    required this.relaxed,
    required this.stepElapsedMs,
    required this.totalElapsedMs,
    required this.capturedCount,
    required this.finished,
  });

  final EnrollStep step;
  final EnrollFeedback feedback;

  /// 0..1: how far the user is towards the required movement (for the live meter).
  final double progress;

  /// Movement measured towards the required direction, in degrees (0 for step 1).
  final double measuredDegrees;
  final double requiredDegrees;

  /// True while the required pose is being held.
  final bool poseOk;

  /// The pose was held long enough: take the sample now (and call [EnrollmentMachine.onCaptured]).
  final bool readyToCapture;

  /// The step took very long: a "capture by hand" button may be shown.
  final bool manualCaptureAllowed;

  /// The step is slow: show a hint.
  final bool slow;

  /// A smaller movement is accepted now.
  final bool relaxed;
  final int stepElapsedMs;
  final int totalElapsedMs;
  final int capturedCount;
  final bool finished;
}

/// The enrollment steps as an explicit state machine: front -> left -> right -> up -> down -> finished.
///
/// How it works
///  - Step 1 (front) waits until the face is roughly straight and holds still, then stores the user's own NEUTRAL
///    angles (yaw and pitch). Phones are held at different heights, so the neutral pose is not 0 degrees.
///  - All later steps measure the movement RELATIVE to that neutral pose.
///  - The sign of the detector's angles depends on how the camera image is mirrored, and that could not be checked
///    on a real phone. So the first step of each pair (left, up) accepts a movement to either side and remembers
///    the side; the second step (right, down) needs the OPPOSITE side. Both sides are always captured.
///  - Hysteresis: a pose starts at the "enter" angle and only ends below the smaller "exit" angle.
///  - A pose must be held for a few pictures and a short time, so one noisy picture never counts.
///  - If a step is slow: a hint appears, then a smaller movement is accepted, then the user may capture by hand
///    (the sample still has to pass the quality and same-face checks outside this class).
///  - Face missing, several faces, too small, rolled or off-centre: only the hold counter of the CURRENT step is
///    reset. Finished steps stay finished.
///
/// The class uses no clock and no camera: the caller passes the time in, so it is fully testable.
class EnrollmentMachine {
  EnrollmentMachine([this._t = const PoseThresholds()]);

  final PoseThresholds _t;

  EnrollStep _step = EnrollStep.front;
  int _capturedCount = 0;
  bool _finished = false;
  int _startedAt = -1;
  int _stepStartedAt = -1;
  int _lastFrameAt = -1;

  double _baselineYaw = 0;
  double _baselinePitch = 0;
  int _turnSign = 0; // side of the first turn (left step). 0 = unknown
  int _tiltSign = 0; // side of the first tilt (up step). 0 = unknown

  bool _inPose = false;
  int _stableFrames = 0;
  int _stableSince = -1;
  double _lastDelta = 0;
  PoseSample? _lastPose;
  final List<PoseSample> _recent = [];

  EnrollStep get step => _step;
  int get capturedCount => _capturedCount;
  bool get isFinished => _finished;

  /// Handles one camera picture. [faceCount] = how many faces were found, [pose] = the face if exactly one.
  EnrollUpdate onFrame(int nowMs, int faceCount, PoseSample? pose, {bool manualRequested = false}) {
    if (_startedAt < 0) {
      _startedAt = nowMs;
      _stepStartedAt = nowMs;
    }
    if (_lastFrameAt >= 0 && nowMs - _lastFrameAt > _t.pauseGapMillis) {
      // The app was paused (or the camera stopped). Do not count that time, and forget old stability.
      final gap = nowMs - _lastFrameAt;
      _startedAt += gap;
      _stepStartedAt += gap;
      _resetHold();
    }
    _lastFrameAt = nowMs;

    if (_finished) return _update(nowMs, EnrollFeedback.ready, 0, 0, false, false);

    final problem = _problemWith(faceCount, pose);
    if (problem != null || pose == null) {
      _resetHold();
      return _update(nowMs, problem ?? EnrollFeedback.noFace, 0, 0, false, false);
    }
    _lastPose = pose;
    switch (_step) {
      case EnrollStep.front:
        return _frontStep(nowMs, pose, manualRequested);
      case EnrollStep.left:
        return _movementStep(nowMs, pose, manualRequested, turn: true, second: false);
      case EnrollStep.right:
        return _movementStep(nowMs, pose, manualRequested, turn: true, second: true);
      case EnrollStep.up:
        return _movementStep(nowMs, pose, manualRequested, turn: false, second: false);
      case EnrollStep.down:
        return _movementStep(nowMs, pose, manualRequested, turn: false, second: true);
    }
  }

  /// The caller took a good sample for the current step. Moves on to the next step.
  void onCaptured(int nowMs) {
    if (_finished) return;
    switch (_step) {
      case EnrollStep.front:
        final last = _lastPose;
        if (_recent.isNotEmpty) {
          _baselineYaw = _median(_recent.map((p) => p.yaw).toList());
          _baselinePitch = _median(_recent.map((p) => p.pitch).toList());
        } else if (last != null) {
          // Captured by hand: use the pose of that moment as the neutral pose.
          _baselineYaw = last.yaw;
          _baselinePitch = last.pitch;
        }
      case EnrollStep.left:
        _turnSign = _signOf(_lastDelta);
      case EnrollStep.up:
        _tiltSign = _signOf(_lastDelta);
      case EnrollStep.right:
      case EnrollStep.down:
        break;
    }
    _capturedCount++;
    if (_step == EnrollStep.down) {
      _finished = true;
    } else {
      _step = EnrollStep.values[_step.index + 1];
    }
    _resetHold();
    _stepStartedAt = nowMs;
  }

  /// "Retry step": forget the hold counters and the timer of the current step only.
  void retryStep(int nowMs) {
    _resetHold();
    _stepStartedAt = nowMs;
  }

  /// "Restart": everything from the beginning. The caller must also drop the samples it kept.
  void restart() {
    _step = EnrollStep.front;
    _capturedCount = 0;
    _finished = false;
    _startedAt = -1;
    _stepStartedAt = -1;
    _lastFrameAt = -1;
    _baselineYaw = 0;
    _baselinePitch = 0;
    _turnSign = 0;
    _tiltSign = 0;
    _lastDelta = 0;
    _lastPose = null;
    _resetHold();
  }

  // ------------------------------------------------------------------------------------------ steps

  EnrollUpdate _frontStep(int now, PoseSample s, bool manual) {
    // The "Capture now" button must always work once it is shown (the face is valid here).
    final manualNow = manual && _manualAllowed(now);
    if (s.yaw.abs() > _t.centerMaxYaw || s.pitch.abs() > _t.centerMaxPitch) {
      _resetHold();
      return _update(now, EnrollFeedback.notStraight, 0, 0, false, manualNow);
    }
    _recent.add(s);
    while (_recent.length > _t.frontWindow) {
      _recent.removeAt(0);
    }

    final steady = _recent.length >= _t.minHoldFrames &&
        _spread(_recent.map((p) => p.yaw).toList()) <= _t.centerStableSpread &&
        _spread(_recent.map((p) => p.pitch).toList()) <= _t.centerStableSpread;
    if (!steady) {
      _stableSince = -1;
      return _update(now, EnrollFeedback.holdStill, 0, 0, false, manualNow);
    }
    if (_stableSince < 0) _stableSince = now;
    final held = now - _stableSince;
    final ready = held >= _t.holdMillis;
    final progress = (held / _t.holdMillis).clamp(0.0, 1.0).toDouble();
    return _update(now, ready ? EnrollFeedback.ready : EnrollFeedback.holdStill, progress, 0, true, ready || manualNow);
  }

  EnrollUpdate _movementStep(int now, PoseSample s, bool manual, {required bool turn, required bool second}) {
    final delta = turn ? s.yaw - _baselineYaw : s.pitch - _baselinePitch;
    final offAxis = turn ? (s.pitch - _baselinePitch).abs() : (s.yaw - _baselineYaw).abs();
    _lastDelta = delta;

    final relax = _relaxFactor(now);
    final enter = (turn ? _t.turnEnter : _t.tiltEnter) * relax;
    final exit = (turn ? _t.turnExit : _t.tiltExit) * relax;
    final firstSign = turn ? _turnSign : _tiltSign;

    // "movement" = how far the user went in the direction this step wants.
    double movement;
    var wrongWay = false;
    if (!second || firstSign == 0) {
      movement = delta.abs(); // the first step of a pair: either side is fine
    } else {
      movement = -firstSign * delta; // the second step: the OPPOSITE side of the first step
      wrongWay = firstSign * delta >= exit;
    }

    // Hysteresis: start at "enter", stop only below "exit".
    if (!_inPose && movement >= enter && offAxis <= _t.offAxisMax) {
      _inPose = true;
    } else if (_inPose && (movement < exit || offAxis > _t.offAxisMax + _t.offAxisSlack)) {
      _inPose = false;
    }
    if (_inPose) {
      if (_stableSince < 0) {
        _stableSince = now;
        _stableFrames = 0;
      }
      _stableFrames++;
    } else {
      _stableSince = -1;
      _stableFrames = 0;
    }
    final held = _stableSince >= 0 ? now - _stableSince : 0;
    final ready = _inPose && _stableFrames >= _t.minHoldFrames && held >= _t.holdMillis;

    final EnrollFeedback feedback;
    if (ready) {
      feedback = EnrollFeedback.ready;
    } else if (_inPose) {
      feedback = EnrollFeedback.holdStill;
    } else if (wrongWay) {
      feedback = EnrollFeedback.wrongDirection;
    } else if (movement >= enter && offAxis > _t.offAxisMax) {
      feedback = EnrollFeedback.offAxis;
    } else {
      feedback = EnrollFeedback.moveMore;
    }
    final progress = (movement / enter).clamp(0.0, 1.0).toDouble();
    return _update(now, feedback, progress, movement < 0 ? 0 : movement, _inPose, ready || (_manualAllowed(now) && manual));
  }

  // ------------------------------------------------------------------------------------------ helpers

  EnrollFeedback? _problemWith(int faceCount, PoseSample? s) {
    if (faceCount > 1) return EnrollFeedback.multipleFaces; // checked first: with several faces no single pose is given
    if (faceCount == 0 || s == null) return EnrollFeedback.noFace;
    if (s.sizeRatio < _t.minFaceSize) return EnrollFeedback.tooFar;
    if (s.sizeRatio > _t.maxFaceSize) return EnrollFeedback.tooClose;
    if (s.roll.abs() > _t.maxRoll) return EnrollFeedback.headRolled;
    if (_step == EnrollStep.front) {
      if ((s.centerX - 0.5).abs() > _t.frontCenterTolX || (s.centerY - 0.5).abs() > _t.frontCenterTolY) {
        return EnrollFeedback.notCentered;
      }
    } else if (s.centerX < 0.1 || s.centerX > 0.9 || s.centerY < 0.05 || s.centerY > 0.95) {
      // While turning or nodding the face moves a little: it only has to stay inside the picture.
      return EnrollFeedback.notCentered;
    }
    return null;
  }

  double _relaxFactor(int now) => now - _stepStartedAt >= _t.relaxAfterMillis ? _t.relaxFactor : 1.0;

  bool _manualAllowed(int now) => now - _stepStartedAt >= _t.manualAfterMillis;

  double _requiredDegrees(int now) {
    switch (_step) {
      case EnrollStep.front:
        return 0;
      case EnrollStep.left:
      case EnrollStep.right:
        return _t.turnEnter * _relaxFactor(now);
      case EnrollStep.up:
      case EnrollStep.down:
        return _t.tiltEnter * _relaxFactor(now);
    }
  }

  void _resetHold() {
    _inPose = false;
    _stableFrames = 0;
    _stableSince = -1;
    _recent.clear();
  }

  EnrollUpdate _update(int now, EnrollFeedback feedback, double progress, double measured, bool poseOk, bool readyToCapture) {
    final stepElapsed = _stepStartedAt < 0 ? 0 : now - _stepStartedAt;
    final total = _startedAt < 0 ? 0 : now - _startedAt;
    const noButton = {
      EnrollFeedback.noFace,
      EnrollFeedback.multipleFaces,
      EnrollFeedback.tooFar,
      EnrollFeedback.tooClose,
      EnrollFeedback.headRolled,
      EnrollFeedback.notCentered,
    };
    return EnrollUpdate(
      step: _step,
      feedback: feedback,
      progress: progress,
      measuredDegrees: measured,
      requiredDegrees: _requiredDegrees(now),
      poseOk: poseOk,
      readyToCapture: readyToCapture && !_finished,
      manualCaptureAllowed: !_finished && stepElapsed >= _t.manualAfterMillis && !noButton.contains(feedback),
      slow: !_finished && stepElapsed >= _t.hintAfterMillis,
      relaxed: !_finished && stepElapsed >= _t.relaxAfterMillis && _step != EnrollStep.front,
      stepElapsedMs: stepElapsed,
      totalElapsedMs: total,
      capturedCount: _capturedCount,
      finished: _finished,
    );
  }

  /// A movement smaller than this has no reliable side (used when a step was captured by hand).
  static const double _minSignDegrees = 3;

  int _signOf(double d) => d.abs() < _minSignDegrees ? 0 : (d > 0 ? 1 : -1);

  double _spread(List<double> v) {
    if (v.isEmpty) return 0;
    var lo = v.first, hi = v.first;
    for (final x in v) {
      if (x < lo) lo = x;
      if (x > hi) hi = x;
    }
    return hi - lo;
  }

  double _median(List<double> v) {
    final sorted = List<double>.of(v)..sort();
    return sorted[sorted.length ~/ 2];
  }
}
