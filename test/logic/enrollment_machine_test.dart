import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/logic/enrollment_machine.dart';

import '../helpers/simulated_user.dart';

const _neutral = PoseSample(yaw: 0, pitch: 0, roll: 0, sizeRatio: 0.45, centerX: 0.5, centerY: 0.5);

PoseSample _p(double yaw, [double pitch = 0]) =>
    PoseSample(yaw: yaw, pitch: pitch, roll: 0, sizeRatio: 0.45, centerX: 0.5, centerY: 0.5);

void main() {
  test('ideal user finishes all five steps quickly (simulated)', () {
    final m = EnrollmentMachine();
    final ms = SimulatedUser().runToEnd(m);
    expect(ms, isNotNull, reason: 'must finish');
    expect(m.capturedCount, 5);
    expect(ms!, lessThan(30000));
  });

  test('phone held low (neutral pitch 16) still works', () {
    final m = EnrollmentMachine();
    expect(SimulatedUser(neutralYaw: 6, neutralPitch: 16).runToEnd(m), isNotNull);
    expect(m.capturedCount, 5);
  });

  test('phone held at chest height (neutral pitch 30) still works', () {
    final m = EnrollmentMachine();
    expect(SimulatedUser(neutralYaw: 4, neutralPitch: 30).runToEnd(m), isNotNull);
    expect(m.capturedCount, 5);
  });

  test('phone held high (negative pitch) still works', () {
    expect(SimulatedUser(neutralYaw: -5, neutralPitch: -14).runToEnd(EnrollmentMachine()), isNotNull);
  });

  test('works when the detector signs are the other way round', () {
    final m = EnrollmentMachine();
    expect(SimulatedUser(sideSign: -1).runToEnd(m), isNotNull);
    expect(m.capturedCount, 5);
  });

  test('slow user with small natural movements finishes in under a minute', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(turnDegrees: 15, tiltDegrees: 9, speedDegPerSecond: 12, reactionMs: 1500, noiseDegrees: 2.5);
    final ms = u.runToEnd(m);
    expect(ms, isNotNull);
    expect(ms!, lessThan(60000));
  });

  test('very fast movement still needs the hold time', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(speedDegPerSecond: 400, reactionMs: 0, noiseDegrees: 0);
    var lastCaptureAt = 0;
    var tooClose = false;
    for (var i = 0; i < 200; i++) {
      final before = u.captures;
      u.frame(m);
      if (u.captures > before) {
        if (lastCaptureAt != 0 && u.now - lastCaptureAt < const PoseThresholds().holdMillis) tooClose = true;
        lastCaptureAt = u.now;
      }
    }
    expect(tooClose, isFalse);
    expect(m.isFinished, isTrue);
  });

  test('one noisy picture does not advance a step', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step == EnrollStep.front) {
      u.frame(m);
    }
    final before = m.capturedCount;
    u.yaw = 0;
    u.frame(m, poseOverride: _p(30)); // one single picture shows a 30 degree turn
    expect(m.capturedCount, before);
    expect(m.step, EnrollStep.left);
  });

  test('face lost for 4 seconds does not reset finished steps', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser();
    while (m.step != EnrollStep.right) {
      u.frame(m);
    }
    final captured = m.capturedCount;
    for (var i = 0; i < 40; i++) {
      expect(u.frame(m, faceCount: 0).feedback, EnrollFeedback.noFace);
    }
    expect(m.capturedCount, captured);
    expect(m.step, EnrollStep.right);
    expect(u.runToEnd(m), isNotNull);
  });

  test('two faces block progress until only one is left', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser();
    for (var i = 0; i < 60; i++) {
      expect(u.frame(m, faceCount: 2).feedback, EnrollFeedback.multipleFaces);
    }
    expect(m.capturedCount, 0);
    expect(u.runToEnd(m), isNotNull);
  });

  test('a face that is too small, too big, rolled or off screen is rejected', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    expect(u.frame(m, poseOverride: u.pose(size: 0.1)).feedback, EnrollFeedback.tooFar);
    expect(u.frame(m, poseOverride: u.pose(size: 0.95)).feedback, EnrollFeedback.tooClose);
    expect(u.frame(m, poseOverride: u.pose(roll: 40)).feedback, EnrollFeedback.headRolled);
    expect(u.frame(m, poseOverride: u.pose(cx: 0.05)).feedback, EnrollFeedback.notCentered);
    expect(m.capturedCount, 0);
  });

  test('a face turned far away on step 1 is told to look straight', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    expect(u.frame(m, poseOverride: _p(45)).feedback, EnrollFeedback.notStraight);
  });

  test('the second step of a pair needs the opposite side', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.right) {
      u.frame(m);
    }
    var sawWrongWay = false;
    for (var i = 0; i < 60; i++) {
      u.yaw = 22; // the user wrongly repeats the first side
      if (u.frame(m, poseOverride: _p(22)).feedback == EnrollFeedback.wrongDirection) sawWrongWay = true;
    }
    expect(sawWrongWay, isTrue);
    expect(m.step, EnrollStep.right);
  });

  test('tilting while turning is told to keep the head level', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.left) {
      u.frame(m);
    }
    expect(u.frame(m, poseOverride: _p(22, 25)).feedback, EnrollFeedback.offAxis);
  });

  test('hysteresis keeps the pose when the angle wobbles around the limit', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.left) {
      u.frame(m);
    }
    var advanced = false;
    for (var i = 0; i < 40; i++) {
      final yaw = i.isEven ? 13.0 : 10.5; // enter is 12, exit is 8
      u.yaw = yaw;
      u.frame(m, poseOverride: _p(yaw));
      if (m.step != EnrollStep.left) {
        advanced = true;
        break;
      }
    }
    expect(advanced, isTrue, reason: 'a wobbling pose must still be accepted');
  });

  test('a smaller movement is accepted only after the relax time', () {
    const limits = PoseThresholds();
    final m = EnrollmentMachine(limits);
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.left) {
      u.frame(m);
    }
    final t0 = u.now;
    while (u.now - t0 < limits.relaxAfterMillis - 1500) {
      u.yaw = 9.5; // between relaxed (8.4) and normal (12)
      u.frame(m, poseOverride: _p(9.5));
    }
    expect(m.step, EnrollStep.left);
    var advanced = false;
    for (var i = 0; i < 60; i++) {
      u.yaw = 9.5;
      u.frame(m, poseOverride: _p(9.5));
      if (m.step != EnrollStep.left) advanced = true;
    }
    expect(advanced, isTrue);
  });

  test('slow hint and manual capture appear on time', () {
    const limits = PoseThresholds();
    final m = EnrollmentMachine(limits);
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.left) {
      u.frame(m);
    }
    final start = u.now;
    int slowAt = -1, manualAt = -1;
    while (u.now - start < 20000) {
      u.yaw = 0;
      final last = u.frame(m, poseOverride: _neutral);
      if (last.slow && slowAt < 0) slowAt = u.now - start;
      if (last.manualCaptureAllowed && manualAt < 0) manualAt = u.now - start;
    }
    expect(slowAt, inInclusiveRange(limits.hintAfterMillis - 200, limits.hintAfterMillis + 300));
    expect(manualAt, inInclusiveRange(limits.manualAfterMillis - 200, limits.manualAfterMillis + 300));
    final pressed = u.frame(m, poseOverride: _neutral, manual: true);
    expect(pressed.readyToCapture || m.step != EnrollStep.left, isTrue);
  });

  test('manual capture is refused too early and without a face', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.left) {
      u.frame(m);
    }
    expect(u.frame(m, poseOverride: _neutral, manual: true).readyToCapture, isFalse);
    for (var i = 0; i < 170; i++) {
      u.frame(m, poseOverride: _neutral);
    }
    expect(u.frame(m, faceCount: 0, manual: true).readyToCapture, isFalse);
  });

  test('"Capture now" works on step 1 even when the face is not straight', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    final tilted = _p(0, 45); // beyond even the wide step-1 limit
    EnrollUpdate? last;
    for (var i = 0; i < 160; i++) {
      last = u.frame(m, poseOverride: tilted);
    }
    expect(last!.feedback, EnrollFeedback.notStraight);
    expect(last.manualCaptureAllowed, isTrue);
    u.frame(m, poseOverride: tilted, manual: true);
    expect(m.step, EnrollStep.left);
    final same = SimulatedUser(neutralPitch: 45, noiseDegrees: 0)..now = u.now;
    expect(same.runToEnd(m), isNotNull);
  });

  test('"Capture now" works on step 1 when the head never holds still', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    EnrollUpdate? last;
    for (var i = 0; i < 160; i++) {
      last = u.frame(m, poseOverride: _p(i.isEven ? 6 : -6));
    }
    expect(last!.feedback, EnrollFeedback.holdStill);
    u.frame(m, poseOverride: _p(6), manual: true);
    expect(m.step, EnrollStep.left);
  });

  test('retry step keeps finished steps and restarts the timer', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    while (m.step != EnrollStep.up) {
      u.frame(m);
    }
    final captured = m.capturedCount;
    for (var i = 0; i < 100; i++) {
      u.frame(m, poseOverride: _neutral);
    }
    m.retryStep(u.now);
    expect(m.step, EnrollStep.up);
    expect(m.capturedCount, captured);
    expect(u.frame(m, poseOverride: _neutral).stepElapsedMs, lessThan(500));
  });

  test('restart goes back to step one', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser();
    while (m.step != EnrollStep.up) {
      u.frame(m);
    }
    m.restart();
    expect(m.step, EnrollStep.front);
    expect(m.capturedCount, 0);
    expect(m.isFinished, isFalse);
    expect(SimulatedUser().runToEnd(m), isNotNull);
  });

  test('time while the app was paused is not counted', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser(noiseDegrees: 0);
    u.frame(m, poseOverride: _neutral);
    u.now += 120000; // two minutes in the background
    final after = u.frame(m, poseOverride: _neutral);
    expect(after.stepElapsedMs, lessThan(1000));
    expect(after.slow, isFalse);
  });

  test('samples are captured in step order', () {
    final m = EnrollmentMachine();
    final u = SimulatedUser();
    final order = <EnrollStep>[];
    for (var i = 0; i < 2000 && !m.isFinished; i++) {
      final step = m.step;
      final before = m.capturedCount;
      u.frame(m);
      if (m.capturedCount > before) order.add(step);
    }
    expect(order, [EnrollStep.front, EnrollStep.left, EnrollStep.right, EnrollStep.up, EnrollStep.down]);
  });

  test('simulated durations for the report', () {
    String run(String name, SimulatedUser u) {
      final ms = u.runToEnd(EnrollmentMachine(), limitMs: 120000);
      return '$name -> ${ms == null ? "DID NOT FINISH" : "${(ms / 1000).toStringAsFixed(1)} s"}';
    }

    // ignore: avoid_print
    print([
      run('ideal user', SimulatedUser()),
      run('phone held low', SimulatedUser(neutralYaw: 6, neutralPitch: 16)),
      run('slow careful user', SimulatedUser(turnDegrees: 15, tiltDegrees: 9, speedDegPerSecond: 12, reactionMs: 1500, noiseDegrees: 2.5)),
      run('only 3 pictures/s', SimulatedUser(fps: 3)),
      run('tiny movements', SimulatedUser(turnDegrees: 10, tiltDegrees: 6, speedDegPerSecond: 40, noiseDegrees: 1)),
    ].join('\n'));
  });
}
