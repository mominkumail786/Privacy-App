import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/bridge/native_bridge.dart';
import 'package:privacyguard/controllers/app_controller.dart';
import 'package:privacyguard/controllers/calibration_controller.dart';
import 'package:privacyguard/controllers/event_log_controller.dart';
import 'package:privacyguard/controllers/settings_controller.dart';
import 'package:privacyguard/logic/enrollment_machine.dart';
import 'package:privacyguard/logic/other_person_detector.dart';
import 'package:privacyguard/logic/threshold_calibrator.dart';
import 'package:privacyguard/logic/thresholds.dart';
import 'package:privacyguard/models/frame_event.dart';
import 'package:privacyguard/models/guard_event.dart';
import 'package:privacyguard/models/guard_status.dart';

import '../helpers/fake_bridge.dart';

Future<void> _settle() async {
  for (var i = 0; i < 4; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

const _pose = PoseSample(yaw: 0, pitch: 0, roll: 0, sizeRatio: 0.4, centerX: 0.5, centerY: 0.5);

CameraFrame _frame(int t, {double? similarity, int faces = 1, bool facing = true}) => CameraFrame(FrameEvent(
      frameId: t,
      timeMs: t,
      latencyMs: 50,
      faceCount: faces,
      pose: faces == 1 ? _pose : null,
      facing: facing,
      similarity: similarity,
    ));

void main() {
  group('GuardSnapshot', () {
    test('camera permission taken away while stopped shows "Permission missing"', () {
      final s = GuardSnapshot.fromMap({'status': 'STOPPED', 'cameraGranted': false, 'notificationsGranted': true, 'hasProfile': true});
      expect(s.shownStatus, GuardStatus.permissionMissing);
    });

    test('a running service is never shown as permission missing', () {
      final s = GuardSnapshot.fromMap({'status': 'PAUSED_CAMERA_BUSY', 'cameraGranted': false});
      expect(s.shownStatus, GuardStatus.pausedCameraBusy);
      expect(s.running, isTrue);
    });

    test('unknown or missing status is "stopped" (the safe truth)', () {
      expect(GuardStatus.parse('SOMETHING_NEW'), GuardStatus.stopped);
      expect(GuardStatus.parse(null), GuardStatus.stopped);
    });

    test('degraded counts as running but is not "active"', () {
      expect(GuardStatus.degraded.isRunning, isTrue);
      expect(GuardStatus.degraded == GuardStatus.active, isFalse);
    });

    test('canStart needs everything', () {
      GuardSnapshot make({bool cam = true, bool notif = true, bool profile = true, bool model = true, GuardStatus st = GuardStatus.stopped}) =>
          GuardSnapshot(
            status: st, detection: null, lastScore: null, cameraGranted: cam, notificationsGranted: notif,
            hasProfile: profile, modelAvailable: model, batteryOptimized: false,
          );
      expect(make().canStart, isTrue);
      expect(make(cam: false).canStart, isFalse);
      expect(make(notif: false).canStart, isFalse);
      expect(make(profile: false).canStart, isFalse);
      expect(make(model: false).canStart, isFalse);
      expect(make(st: GuardStatus.active).canStart, isFalse);
    });

    test('detection names from the native side are parsed', () {
      final s = GuardSnapshot.fromMap({'status': 'ACTIVE', 'detection': 'OWNER_AND_OTHER', 'lastScore': 0.41});
      expect(s.detection, FrameState.ownerAndOther);
      expect(s.lastScore, 0.41);
    });
  });

  group('AppController', () {
    test('refresh reads the real state; start/stop go through the bridge', () async {
      final bridge = FakeBridge();
      final c = AppController(bridge, pollEvery: const Duration(hours: 1));
      await c.start();
      expect(c.snapshot.status, GuardStatus.stopped);
      expect(c.loaded, isTrue);
      await c.startProtection();
      expect(c.snapshot.status, GuardStatus.active);
      await c.stopProtection();
      expect(c.snapshot.status, GuardStatus.stopped);
      expect(bridge.calls, containsAllInOrder(['startProtection', 'stopProtection']));
      c.dispose();
    });

    test('start is refused when something is missing', () async {
      final bridge = FakeBridge(hasProfile: false);
      final c = AppController(bridge, pollEvery: const Duration(hours: 1));
      await c.start();
      await c.startProtection();
      expect(bridge.calls.contains('startProtection'), isFalse);
      c.dispose();
    });

    test('"Tap to restart" starts protection once everything is in place', () async {
      final bridge = FakeBridge()..autoStartPending = true;
      final c = AppController(bridge, pollEvery: const Duration(hours: 1));
      await c.start();
      expect(bridge.calls, contains('startProtection'));
      expect(c.snapshot.status, GuardStatus.active);
      c.dispose();
    });

    test('"Tap to restart" does not start when the profile is missing, and is consumed', () async {
      final bridge = FakeBridge(hasProfile: false)..autoStartPending = true;
      final c = AppController(bridge, pollEvery: const Duration(hours: 1));
      await c.start();
      expect(bridge.calls.contains('startProtection'), isFalse);
      expect(bridge.autoStartPending, isFalse);
      c.dispose();
    });

    test('the status changes when the native side changes it (e.g. camera taken by a call)', () async {
      final bridge = FakeBridge(status: GuardStatus.active);
      final c = AppController(bridge, pollEvery: const Duration(hours: 1));
      await c.start();
      expect(c.snapshot.status, GuardStatus.active);
      bridge.status = GuardStatus.pausedCameraBusy;
      await c.refresh();
      expect(c.snapshot.status, GuardStatus.pausedCameraBusy);
      c.dispose();
    });
  });

  group('SettingsController', () {
    test('choosing a sensitivity overwrites the numbers but keeps "alert on uncertain"', () async {
      final bridge = FakeBridge();
      bridge.settings = bridge.settings.copyWith(thresholds: bridge.settings.thresholds.copyWith(alertOnUncertain: true));
      final c = SettingsController(bridge);
      await c.load();
      await c.chooseSensitivity(Sensitivity.high);
      expect(c.data.sensitivity, Sensitivity.high);
      expect(c.data.thresholds.matchThreshold, Thresholds.forSensitivity(Sensitivity.high).matchThreshold);
      expect(c.data.thresholds.alertOnUncertain, isTrue);
    });

    test('sliders only save when released', () async {
      final bridge = FakeBridge();
      final c = SettingsController(bridge);
      await c.load();
      c.previewThresholds(c.data.thresholds.copyWith(matchThreshold: 0.55));
      c.previewCooldown(60);
      expect(bridge.calls.where((x) => x == 'setThresholds' || x.startsWith('setCooldown')), isEmpty);
      await c.commitThresholds();
      await c.commitCooldown();
      expect(bridge.settings.thresholds.matchThreshold, 0.55);
      expect(bridge.settings.cooldownSeconds, 60);
    });

    test('loads the version', () async {
      final c = SettingsController(FakeBridge());
      await c.load();
      expect(c.version, '1.2.3');
    });

    test('delete profile goes to the native side', () async {
      final bridge = FakeBridge();
      await SettingsController(bridge).deleteProfile();
      expect(bridge.hasProfile, isFalse);
    });
  });

  group('EventLogController', () {
    test('lists newest first and clears', () async {
      final bridge = FakeBridge();
      await bridge.addEvent(EventType.protectionStarted);
      await bridge.addEvent(EventType.otherPersonAlert, score: 0.21);
      final c = EventLogController(bridge);
      await c.load();
      expect(c.events.first.type, EventType.otherPersonAlert);
      await c.clear();
      expect(c.events, isEmpty);
    });

    test('unknown entries are skipped and event names round-trip', () {
      expect(GuardEvent.fromMap({'time': 5, 'type': 'FROM_THE_FUTURE'}), isNull);
      for (final t in EventType.values) {
        expect(EventType.parse(t.nativeName), t);
      }
    });
  });

  group('CalibrationController', () {
    late FakeBridge bridge;
    late CalibrationController c;
    setUp(() {
      bridge = FakeBridge();
      c = CalibrationController(bridge);
    });
    tearDown(() async {
      c.dispose();
      await bridge.close();
    });

    Future<void> feed(int from, int count, double base, {int gap = 350}) async {
      for (var i = 0; i < count; i++) {
        bridge.emit(_frame(from + i * gap, similarity: base + (i % 5) * 0.01));
        await _settle();
      }
    }

    test('asks the native side for live similarity', () async {
      await c.start();
      expect(bridge.computeSimilarity, isTrue);
    });

    test('measures the owner, then another person, then suggests thresholds', () async {
      await c.start();
      c.beginOwner();
      await feed(1000, CalibrationController.targetSamples, 0.70);
      expect(c.phase, CalibPhase.askOther);
      c.beginOther();
      await feed(100000, CalibrationController.targetSamples, 0.05);
      expect(c.phase, CalibPhase.result);
      expect(c.result!.quality, CalibrationQuality.good);
      expect(c.result!.matchThreshold, inInclusiveRange(0.25, 0.70));
    });

    test('skipping the other person gives an owner-only result', () async {
      await c.start();
      c.beginOwner();
      await feed(1000, CalibrationController.targetSamples, 0.70);
      c.skipOther();
      expect(c.result!.quality, CalibrationQuality.ownerOnly);
    });

    test('pictures arriving faster than 3 per second are skipped', () async {
      await c.start();
      c.beginOwner();
      await feed(1000, 10, 0.7, gap: 50);
      expect(c.count, lessThan(4));
    });

    test('two people in view, or nobody facing the screen, are not measured and show the hint', () async {
      await c.start();
      c.beginOwner();
      bridge.emit(_frame(1000, faces: 2));
      await _settle();
      expect(c.count, 0);
      expect(c.hint, CalibHint.onePerson);
      bridge.emit(_frame(2000, similarity: 0.8, facing: false));
      await _settle();
      expect(c.count, 0);
      bridge.emit(_frame(3000, similarity: 0.8));
      await _settle();
      expect(c.count, 1);
      expect(c.hint, CalibHint.look);
    });

    test('nothing is recorded before the user starts', () async {
      await c.start();
      await feed(1000, 5, 0.7);
      expect(c.count, 0);
    });

    test('apply saves match threshold and margin, and keeps the other numbers', () async {
      await c.start();
      bridge.settings = bridge.settings.copyWith(thresholds: bridge.settings.thresholds.copyWith(maxHeadAngle: 44));
      c.beginOwner();
      await feed(1000, CalibrationController.targetSamples, 0.70);
      c.skipOther();
      await c.apply();
      expect(c.applied, isTrue);
      expect(bridge.settings.thresholds.maxHeadAngle, 44);
      expect(bridge.settings.thresholds.matchThreshold, c.result!.matchThreshold);
      expect(bridge.settings.thresholds.uncertainMargin, c.result!.uncertainMargin);
    });

    test('measure again goes back to the start', () async {
      await c.start();
      c.beginOwner();
      await feed(1000, CalibrationController.targetSamples, 0.7);
      c.skipOther();
      c.measureAgain();
      expect(c.phase, CalibPhase.intro);
      expect(c.result, isNull);
    });
  });

  group('CaptureResult / FrameEvent parsing', () {
    test('capture result maps problems', () {
      expect(CaptureResult.fromMap({'ok': false, 'problem': 'TOO_DARK'}).problem, SampleProblem.tooDark);
      expect(CaptureResult.fromMap({'ok': false, 'problem': 'STALE'}).problem, SampleProblem.stale);
      expect(CaptureResult.fromMap({'ok': false}).problem, SampleProblem.failed);
      expect(CaptureResult.fromMap({'ok': true, 'embedding': [0.5, 0.5]}).embedding, [0.5, 0.5]);
    });

    test('frame event with several faces has no pose', () {
      final e = FrameEvent.fromMap({'frameId': 3, 't': 100, 'faces': 2, 'pose': {'yaw': 1}, 'latencyMs': 7});
      expect(e!.pose, isNull);
      expect(e.faceCount, 2);
    });

    test('a broken frame message is ignored', () {
      expect(FrameEvent.fromMap({'faces': 1}), isNull);
    });
  });

  test('CameraFrame / CameraError are the only message kinds', () {
    const CameraMessage m = CameraError(CameraProblem.cameraFailed);
    expect(m is CameraError, isTrue);
  });
}
