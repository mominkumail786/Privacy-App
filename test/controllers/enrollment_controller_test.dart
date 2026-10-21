import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/bridge/native_bridge.dart';
import 'package:privacyguard/controllers/enrollment_controller.dart';
import 'package:privacyguard/logic/enrollment_machine.dart';
import 'package:privacyguard/models/frame_event.dart';

import '../helpers/fake_bridge.dart';
import '../helpers/simulated_user.dart';

/// A face vector of length 1. With [near] it is the same "person" (small random differences).
List<double> _vector(int seed, {List<double>? near, double noise = 0.05}) {
  final r = math.Random(seed);
  final raw = List<double>.generate(
    192,
    (i) => near == null ? r.nextDouble() - 0.5 : near[i] + (r.nextDouble() - 0.5) * noise,
  );
  final len = math.sqrt(raw.fold<double>(0, (a, b) => a + b * b));
  return raw.map((x) => x / len).toList();
}

Future<void> _settle() async {
  for (var i = 0; i < 4; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

/// Drives the controller like the camera would: one picture after the other. The simulated user reacts to the
/// step of the SAME machine object the controller uses.
class _Camera {
  _Camera(this.bridge, this.user, this.machine);

  final FakeBridge bridge;
  final SimulatedUser user;
  final EnrollmentMachine machine;
  int frameId = 0;
  EnrollStep? _lastStep;
  int _stepSince = 0;

  Future<void> picture({int faces = 1, int latency = 90}) async {
    user.now += 1000 ~/ user.fps;
    if (machine.step != _lastStep) {
      _lastStep = machine.step;
      _stepSince = user.now;
    }
    if (user.now - _stepSince >= user.reactionMs) {
      final (gy, gp) = user.goal(machine.step);
      final maxMove = user.speedDegPerSecond / user.fps;
      user.yaw += (gy - user.yaw).clamp(-maxMove, maxMove).toDouble();
      user.pitch += (gp - user.pitch).clamp(-maxMove, maxMove).toDouble();
    }
    final pose = faces == 1 ? user.pose() : null;
    bridge.emit(CameraFrame(FrameEvent(
      frameId: ++frameId,
      timeMs: user.now,
      latencyMs: latency,
      faceCount: faces,
      pose: pose,
      facing: true,
      similarity: null,
    )));
    await _settle();
  }
}

void main() {
  late FakeBridge bridge;
  late EnrollmentMachine machine;
  late EnrollmentController controller;
  late SimulatedUser user;
  late _Camera camera;
  late List<double> owner;
  var disposed = false;

  setUp(() {
    disposed = false;
    bridge = FakeBridge();
    machine = EnrollmentMachine();
    controller = EnrollmentController(bridge, machine: machine);
    user = SimulatedUser(noiseDegrees: 0);
    camera = _Camera(bridge, user, machine);
    owner = _vector(1);
    var n = 0;
    // Native side: every capture returns a vector of the same person.
    bridge.onCapture = (_) async => CaptureResult.ok(_vector(100 + n++, near: owner));
  });

  tearDown(() async {
    if (!disposed) controller.dispose();
    await bridge.close();
  });

  test('start subscribes to the camera and asks the native side to start it', () async {
    await controller.start();
    expect(bridge.hasCameraListener, isTrue);
    expect(bridge.cameraRunning, isTrue);
    expect(bridge.computeSimilarity, isFalse);
  });

  test('a camera that cannot start shows a failure that stays', () async {
    bridge.cameraStartResult = false;
    await controller.start();
    expect(controller.ui.failure, EnrollFailure.cameraFailed);
  });

  test('a full enrollment saves ONE averaged profile and logs the time', () async {
    await controller.start();
    for (var i = 0; i < 600 && !controller.ui.finished; i++) {
      await camera.picture();
    }
    expect(controller.ui.finished, isTrue);
    expect(bridge.captureRequests.length, 5);
    expect(bridge.calls.where((c) => c == 'saveProfile').length, 1);
    final profile = bridge.savedProfile!;
    expect(profile.length, 192);
    expect(math.sqrt(profile.fold<double>(0, (a, b) => a + b * b)), closeTo(1, 1e-6));
    expect(bridge.calls, contains('addEvent:ENROLLMENT_DONE'));
    expect(bridge.cameraRunning, isFalse, reason: 'camera is released when done');
  });

  test('a quality problem repeats only its own step and shows the reason', () async {
    await controller.start();
    bridge.onCapture = (_) async => const CaptureResult.problem(SampleProblem.tooDark);
    for (var i = 0; i < 60; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 0);
    expect(controller.ui.step, EnrollStep.front);
    expect(controller.ui.rejected, EnrollReject.tooDark);
    var n = 0;
    bridge.onCapture = (_) async => CaptureResult.ok(_vector(200 + n++, near: owner));
    for (var i = 0; i < 40 && controller.ui.captured == 0; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 1);
  });

  test('a sample that does not look like the first one is refused', () async {
    await controller.start();
    var n = 0;
    bridge.onCapture = (_) async => CaptureResult.ok(_vector(300 + n++, near: owner));
    for (var i = 0; i < 60 && controller.ui.captured < 1; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 1);
    bridge.onCapture = (_) async => CaptureResult.ok(_vector(999));
    for (var i = 0; i < 80; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 1);
    expect(controller.ui.rejected, EnrollReject.notSame);
  });

  test('an invalid vector (NaN) is refused', () async {
    await controller.start();
    bridge.onCapture = (_) async => CaptureResult.ok(List<double>.filled(192, double.nan));
    for (var i = 0; i < 50; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 0);
    expect(controller.ui.rejected, EnrollReject.badSample);
  });

  test('a stale answer is ignored silently and the next picture asks again', () async {
    await controller.start();
    var calls = 0;
    var n = 0;
    bridge.onCapture = (_) async =>
        ++calls < 3 ? const CaptureResult.problem(SampleProblem.stale) : CaptureResult.ok(_vector(400 + n++, near: owner));
    for (var i = 0; i < 60 && controller.ui.captured == 0; i++) {
      await camera.picture();
    }
    expect(controller.ui.captured, 1);
    expect(controller.ui.rejected, EnrollReject.none);
  });

  test('pictures arriving while one is still being handled are dropped (no queue)', () async {
    await controller.start();
    final gate = Completer<CaptureResult>();
    bridge.onCapture = (_) => gate.future;
    for (var i = 0; i < 40 && bridge.captureRequests.isEmpty; i++) {
      await camera.picture();
    }
    expect(bridge.captureRequests.length, 1);
    for (var i = 0; i < 20; i++) {
      await camera.picture();
    }
    expect(bridge.captureRequests.length, 1, reason: 'no second capture while busy');
    gate.complete(CaptureResult.ok(_vector(7, near: owner)));
    await _settle();
    expect(controller.ui.captured, 1);
  });

  test('a failed save keeps the failure visible; Restart starts again', () async {
    bridge.saveResult = false;
    await controller.start();
    for (var i = 0; i < 600 && controller.ui.failure != EnrollFailure.saveFailed; i++) {
      await camera.picture();
    }
    expect(controller.ui.failure, EnrollFailure.saveFailed);
    expect(controller.ui.finished, isFalse);
    controller.restart();
    await camera.picture();
    expect(controller.ui.captured, 0);
    expect(controller.ui.failure, EnrollFailure.none);
  });

  test('ten detection errors in a row show the failure; a good picture clears it', () async {
    await controller.start();
    for (var i = 0; i < 10; i++) {
      bridge.emit(const CameraError(CameraProblem.detectionFailed));
      await _settle();
    }
    expect(controller.ui.failure, EnrollFailure.detectFailed);
    await camera.picture();
    expect(controller.ui.failure, EnrollFailure.none);
  });

  test('no face and two faces never capture', () async {
    await controller.start();
    for (var i = 0; i < 30; i++) {
      await camera.picture(faces: 0);
    }
    expect(controller.ui.feedback, EnrollFeedback.noFace);
    for (var i = 0; i < 30; i++) {
      await camera.picture(faces: 2);
    }
    expect(controller.ui.feedback, EnrollFeedback.multipleFaces);
    expect(bridge.captureRequests, isEmpty);
  });

  test('Capture now appears after the wait and then captures', () async {
    await controller.start();
    // A face that is never "straight": step 1 cannot complete by itself.
    const tilted = PoseSample(yaw: 0, pitch: 45, roll: 0, sizeRatio: 0.45, centerX: 0.5, centerY: 0.5);
    var t = 1000;
    Future<void> send() async {
      t += 100;
      bridge.emit(CameraFrame(FrameEvent(frameId: t, timeMs: t, latencyMs: 80, faceCount: 1, pose: tilted, facing: true, similarity: null)));
      await _settle();
    }

    for (var i = 0; i < 160; i++) {
      await send();
    }
    expect(controller.ui.manualAllowed, isTrue);
    controller.captureNow();
    await send();
    expect(controller.ui.captured, 1);
  });

  test('pause releases the camera, resume starts it again', () async {
    await controller.start();
    await controller.pauseCamera();
    expect(bridge.cameraRunning, isFalse);
    await controller.resumeCamera();
    expect(bridge.cameraRunning, isTrue);
  });

  test('dispose releases the camera', () async {
    await controller.start();
    controller.dispose();
    disposed = true;
    await _settle();
    expect(bridge.cameraRunning, isFalse);
  });
}
