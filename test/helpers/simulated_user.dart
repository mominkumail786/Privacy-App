import 'dart:math' as math;

import 'package:privacyguard/logic/enrollment_machine.dart';

/// A made-up user who follows the instructions of the enrollment machine.
///
/// SIMULATION ONLY: it proves the logic (neutral pose, hysteresis, hold time, recovery). It does NOT prove that
/// real face-detector angles on a real phone behave like this.
class SimulatedUser {
  SimulatedUser({
    this.neutralYaw = 0,
    this.neutralPitch = 0,
    this.sideSign = 1,
    this.turnDegrees = 22,
    this.tiltDegrees = 13,
    this.speedDegPerSecond = 60,
    this.reactionMs = 600,
    this.noiseDegrees = 1.5,
    this.fps = 10,
    int seed = 7,
  })  : _random = math.Random(seed),
        yaw = neutralYaw,
        pitch = neutralPitch;

  final double neutralYaw;
  final double neutralPitch;

  /// Which sign the user's "first side" has in the detector's angles (+1 or -1).
  final int sideSign;
  final double turnDegrees;
  final double tiltDegrees;
  final double speedDegPerSecond;
  final int reactionMs;
  final double noiseDegrees;
  final int fps;
  final math.Random _random;

  double yaw;
  double pitch;
  int now = 1000;
  EnrollStep? _lastStep;
  int _stepSince = 1000;
  int captures = 0;

  /// Goal angles for a step. Up/down and left/right go to opposite sides.
  (double, double) goal(EnrollStep step) {
    switch (step) {
      case EnrollStep.front:
        return (neutralYaw, neutralPitch);
      case EnrollStep.left:
        return (neutralYaw + sideSign * turnDegrees, neutralPitch);
      case EnrollStep.right:
        return (neutralYaw - sideSign * turnDegrees, neutralPitch);
      case EnrollStep.up:
        return (neutralYaw, neutralPitch + sideSign * tiltDegrees);
      case EnrollStep.down:
        return (neutralYaw, neutralPitch - sideSign * tiltDegrees);
    }
  }

  // Box-Muller gaussian noise, limited to +-noiseDegrees.
  double _noise() {
    if (noiseDegrees == 0) return 0;
    final u1 = _random.nextDouble().clamp(1e-9, 1.0);
    final u2 = _random.nextDouble();
    final g = math.sqrt(-2 * math.log(u1)) * math.cos(2 * math.pi * u2);
    return (g * noiseDegrees * 0.5).clamp(-noiseDegrees, noiseDegrees).toDouble();
  }

  PoseSample pose({double size = 0.45, double cx = 0.5, double cy = 0.5, double roll = 0}) =>
      PoseSample(yaw: yaw + _noise(), pitch: pitch + _noise(), roll: roll, sizeRatio: size, centerX: cx, centerY: cy);

  /// One camera picture: the user moves towards the goal of the current step, then the machine sees the pose.
  EnrollUpdate frame(EnrollmentMachine m, {int faceCount = 1, PoseSample? poseOverride, bool manual = false}) {
    now += 1000 ~/ fps;
    if (m.step != _lastStep) {
      _lastStep = m.step;
      _stepSince = now;
    }
    if (now - _stepSince >= reactionMs) {
      final (gy, gp) = goal(m.step);
      final maxMove = speedDegPerSecond / fps;
      yaw += (gy - yaw).clamp(-maxMove, maxMove).toDouble();
      pitch += (gp - pitch).clamp(-maxMove, maxMove).toDouble();
    }
    final u = m.onFrame(now, faceCount, faceCount == 1 ? (poseOverride ?? pose()) : null, manualRequested: manual);
    if (u.readyToCapture) {
      m.onCaptured(now);
      captures++;
    }
    return u;
  }

  /// Runs until the machine finishes or [limitMs] pass. Returns the simulated time used, or null.
  int? runToEnd(EnrollmentMachine m, {int limitMs = 90000}) {
    final start = now;
    while (now - start < limitMs) {
      frame(m);
      if (m.isFinished) return now - start;
    }
    return null;
  }
}
