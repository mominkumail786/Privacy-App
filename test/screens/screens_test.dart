import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/bridge/native_bridge.dart';
import 'package:privacyguard/logic/enrollment_machine.dart';
import 'package:privacyguard/logic/other_person_detector.dart';
import 'package:privacyguard/logic/thresholds.dart';
import 'package:privacyguard/models/frame_event.dart';
import 'package:privacyguard/models/guard_event.dart';
import 'package:privacyguard/models/guard_status.dart';
import 'package:privacyguard/screens/about_screen.dart';
import 'package:privacyguard/screens/battery_help_screen.dart';
import 'package:privacyguard/screens/calibration_screen.dart';
import 'package:privacyguard/screens/enrollment_screen.dart';
import 'package:privacyguard/screens/event_log_screen.dart';
import 'package:privacyguard/screens/settings_screen.dart';
import 'package:privacyguard/services/permission_service.dart';
import 'package:privacyguard/strings.dart';

import '../helpers/fake_bridge.dart';
import '../helpers/pump_app.dart';

void main() {
  group('first start and home', () {
    testWidgets('onboarding is shown once, then the home screen', (tester) async {
      final bridge = FakeBridge(onboardingDone: false, cameraGranted: false, notificationsGranted: false);
      final permissions = await pumpApp(tester, bridge);
      expect(find.text(S.onbTitles[0]), findsOneWidget);
      for (var page = 1; page <= 3; page++) {
        await tester.tap(find.text(S.onbNext));
        await tester.pumpAndSettle();
        expect(find.text(S.onbTitles[page]), findsOneWidget);
      }
      // permissions are asked only on the last page, each with its reason
      expect(find.text(S.permCameraReason), findsOneWidget);
      expect(permissions.requested, isEmpty);
      await tester.tap(find.text(S.permAllow).first);
      await tester.pumpAndSettle();
      expect(permissions.requested, [AppPermission.camera]);
      await tester.tap(find.text(S.onbFinish));
      await tester.pumpAndSettle();
      expect(bridge.onboardingDone, isTrue);
      expect(find.text(S.btnStart), findsOneWidget);
    });

    testWidgets('onboarding is skipped when it was done before', (tester) async {
      await pumpApp(tester, FakeBridge());
      expect(find.text(S.onbTitles[0]), findsNothing);
      expect(find.text(S.btnStart), findsOneWidget);
    });

    testWidgets('permission denied forever shows how to fix it', (tester) async {
      final bridge = FakeBridge(onboardingDone: false, cameraGranted: false);
      final permissions = await pumpApp(tester, bridge);
      for (var i = 0; i < 3; i++) {
        await tester.tap(find.text(S.onbNext));
        await tester.pumpAndSettle();
      }
      permissions.nextOutcome = PermissionOutcome.deniedForever;
      await tester.tap(find.text(S.permAllow).first);
      await tester.pumpAndSettle();
      expect(find.text(S.permDeniedForever), findsOneWidget);
      await tester.tap(find.text(S.permOpenSettings));
      expect(permissions.settingsOpened, 1);
    });

    testWidgets('stopped: Start enabled, Stop disabled', (tester) async {
      await pumpApp(tester, FakeBridge());
      expect(find.text(S.status(GuardStatus.stopped)), findsOneWidget);
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, S.btnStart)).onPressed, isNotNull);
      expect(tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, S.btnStop)).onPressed, isNull);
    });

    testWidgets('Start asks the native service and the real status is shown', (tester) async {
      final bridge = FakeBridge();
      await pumpApp(tester, bridge);
      await tester.tap(find.text(S.btnStart));
      await tester.pumpAndSettle();
      expect(bridge.calls, contains('startProtection'));
      expect(find.text(S.status(GuardStatus.active)), findsOneWidget);
      expect(find.text('${S.detectTitle}: ${S.detection(null)}'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, S.btnStart)).onPressed, isNull);
      await tester.tap(find.text(S.btnStop));
      await tester.pumpAndSettle();
      expect(find.text(S.status(GuardStatus.stopped)), findsOneWidget);
    });

    testWidgets('start is blocked without a profile, with the reason', (tester) async {
      await pumpApp(tester, FakeBridge(hasProfile: false));
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, S.btnStart)).onPressed, isNull);
      expect(find.text(S.startBlocked), findsOneWidget);
      expect(find.text(S.setupProfileMissing), findsOneWidget);
      expect(find.text(S.btnEnroll), findsOneWidget);
    });

    testWidgets('a missing camera permission is a permission card and "Permission missing"', (tester) async {
      await pumpApp(tester, FakeBridge(cameraGranted: false));
      expect(find.text(S.status(GuardStatus.permissionMissing)), findsOneWidget);
      expect(find.text(S.permCameraReason), findsOneWidget);
    });

    testWidgets('a missing face model is reported', (tester) async {
      await pumpApp(tester, FakeBridge(modelAvailable: false));
      expect(find.text(S.setupModelMissing), findsOneWidget);
    });

    testWidgets('paused by another app and degraded are shown honestly (never "Active")', (tester) async {
      final bridge = FakeBridge(status: GuardStatus.pausedCameraBusy);
      await pumpApp(tester, bridge);
      expect(find.text(S.status(GuardStatus.pausedCameraBusy)), findsOneWidget);
      expect(find.byKey(const Key('detection-text')), findsNothing);
    });

    testWidgets('live detection is shown while active', (tester) async {
      final bridge = FakeBridge(status: GuardStatus.active)
        ..detection = FrameState.otherPerson
        ..lastScore = 0.123;
      await pumpApp(tester, bridge);
      expect(find.text('${S.detectTitle}: ${S.detection(FrameState.otherPerson)}'), findsOneWidget);
      expect(find.text(S.detectScore('0.12')), findsOneWidget);
    });

    testWidgets('enrolling is disabled while protection runs (one camera user at a time)', (tester) async {
      await pumpApp(tester, FakeBridge(status: GuardStatus.active));
      expect(find.text(S.setupStopFirst), findsOneWidget);
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, S.btnReenroll)).onPressed, isNull);
    });

    testWidgets('battery optimisation state is shown', (tester) async {
      await pumpApp(tester, FakeBridge(batteryOptimized: true));
      expect(find.text(S.setupBatteryOn), findsOneWidget);
    });

    testWidgets('the screens open from the home screen', (tester) async {
      await pumpApp(tester, FakeBridge());
      await tester.tap(find.text(S.btnAbout));
      await tester.pumpAndSettle();
      expect(find.text(S.aboutParagraphs[0]), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.text(S.btnEventLog));
      await tester.pumpAndSettle();
      expect(find.text(S.eventsEmpty), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.text(S.btnSettings));
      await tester.pumpAndSettle();
      expect(find.text(S.settingsSensitivity), findsOneWidget);
    });
  });

  group('settings', () {
    testWidgets('the developer credit is at the very bottom, with the exact wording', (tester) async {
      await pumpScreen(tester, FakeBridge(), const SettingsScreen());
      final credit = find.byKey(const Key('developer-credit'));
      await tester.scrollUntilVisible(credit, 400, scrollable: find.byType(Scrollable).first);
      expect((tester.widget<Text>(credit)).data, 'Made by Nurbhanej Anvarali and Momin Kumail');
      expect((tester.widget<Text>(find.byKey(const Key('developer-role')))).data, '2nd Year CE Student at LDCE, Ahmedabad');
      expect(find.text('Made by Nurbhanej Anvarali and Momin Kumail'), findsOneWidget);
      expect(find.text('2nd Year CE Student at LDCE, Ahmedabad'), findsOneWidget);
      expect(find.text(S.aboutDeveloperTitle), findsOneWidget);
      expect(find.textContaining('Version 1.2.3'), findsOneWidget);
      // it comes after every setting, including the Delete button
      final deleteButton = find.text(S.settingsDeleteProfile);
      expect(tester.getTopLeft(credit).dy, greaterThan(tester.getTopLeft(deleteButton).dy));
      // nothing comes after the role line
      final role = tester.getBottomLeft(find.byKey(const Key('developer-role'))).dy;
      for (final e in tester.widgetList<Text>(find.byType(Text))) {
        final f = find.byWidget(e);
        if (f.evaluate().isEmpty) continue;
        expect(tester.getTopLeft(f.first).dy <= role, isTrue, reason: 'nothing below the credit: ${e.data}');
      }
    });

    for (final mode in [ThemeMode.light, ThemeMode.dark]) {
      testWidgets('the credit fits a small screen without overflow ($mode)', (tester) async {
        await pumpScreen(tester, FakeBridge(), const SettingsScreen(), size: const Size(320, 480), mode: mode);
        await tester.scrollUntilVisible(find.byKey(const Key('developer-credit')), 400, scrollable: find.byType(Scrollable).first);
        expect(tester.takeException(), isNull);
        expect(find.byKey(const Key('developer-credit')), findsOneWidget);
      });
    }

    testWidgets('picking a sensitivity level resets the numbers', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const SettingsScreen());
      await tester.tap(find.text(S.sensHigh));
      await tester.pumpAndSettle();
      expect(bridge.calls, contains('applySensitivity:high'));
      expect(bridge.settings.thresholds.matchThreshold, Thresholds.forSensitivity(Sensitivity.high).matchThreshold);
    });

    testWidgets('vibration switch is saved', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const SettingsScreen());
      await tester.scrollUntilVisible(find.text(S.settingsVibration), 300, scrollable: find.byType(Scrollable).first);
      await tester.tap(find.text(S.settingsVibration));
      await tester.pumpAndSettle();
      expect(bridge.settings.vibration, isFalse);
    });

    testWidgets('deleting the profile asks first, and cancel keeps it', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const SettingsScreen());
      await tester.scrollUntilVisible(find.text(S.settingsDeleteProfile), 300, scrollable: find.byType(Scrollable).first);
      await tester.tap(find.text(S.settingsDeleteProfile));
      await tester.pumpAndSettle();
      expect(find.text(S.deleteTitle), findsOneWidget);
      await tester.tap(find.text(S.cancel));
      await tester.pumpAndSettle();
      expect(bridge.hasProfile, isTrue);
      await tester.tap(find.text(S.settingsDeleteProfile));
      await tester.pumpAndSettle();
      await tester.tap(find.text(S.deleteConfirm));
      await tester.pumpAndSettle();
      expect(bridge.hasProfile, isFalse);
      expect(find.text(S.deleteDone), findsOneWidget);
    });
  });

  group('info screens', () {
    testWidgets('about shows all limits, including no liveness and no eye gaze', (tester) async {
      await pumpScreen(tester, FakeBridge(), const AboutScreen());
      for (final p in S.aboutParagraphs) {
        await tester.scrollUntilVisible(find.text(p), 300, scrollable: find.byType(Scrollable).first);
        expect(find.text(p), findsOneWidget);
      }
      expect(S.aboutParagraphs.join(' '), allOf(contains('Eye gaze is not used'), contains('liveness')));
    });

    testWidgets('battery help offers the system dialog only while optimisation is on', (tester) async {
      final bridge = FakeBridge(batteryOptimized: true);
      await pumpScreen(tester, bridge, const BatteryHelpScreen());
      await tester.tap(find.text(S.batteryBtnTurnOff));
      expect(bridge.calls, contains('requestIgnoreBattery'));
      await tester.tap(find.text(S.batteryBtnList));
      expect(bridge.calls, contains('openBatteryList'));
      bridge.batteryOptimized = false;
      await pumpScreen(tester, bridge, const BatteryHelpScreen());
      expect(find.text(S.batteryBtnTurnOff), findsNothing);
      expect(find.text(S.batteryStatusOff), findsOneWidget);
    });

    testWidgets('event log lists events newest first, and can be cleared', (tester) async {
      final bridge = FakeBridge();
      await bridge.addEvent(EventType.protectionStarted);
      await bridge.addEvent(EventType.otherPersonAlert, score: 0.2);
      await pumpScreen(tester, bridge, const EventLogScreen());
      expect(find.textContaining(S.event(EventType.otherPersonAlert)), findsOneWidget);
      expect(find.textContaining('(0.20)'), findsOneWidget);
      expect(find.textContaining('02 Jan 03:04:05'), findsNWidgets(2));
      await tester.tap(find.text(S.btnClearLog));
      await tester.pumpAndSettle();
      expect(find.text(S.eventsEmpty), findsOneWidget);
    });
  });

  group('enrollment screen', () {
    testWidgets('a missing camera permission is explained instead of a black screen', (tester) async {
      final bridge = FakeBridge(cameraGranted: false);
      final permissions = await pumpScreen(tester, bridge, const EnrollmentScreen());
      expect(find.text(S.enrollNoPermission), findsOneWidget);
      await tester.tap(find.text(S.permOpenSettings));
      expect(permissions.settingsOpened, 1);
      expect(bridge.cameraStarts, 0);
    });

    testWidgets('a missing model is reported and the camera is not started', (tester) async {
      final bridge = FakeBridge(modelAvailable: false);
      await pumpScreen(tester, bridge, const EnrollmentScreen());
      expect(find.text(S.enrollModelMissing), findsOneWidget);
      expect(bridge.cameraStarts, 0);
    });

    testWidgets('the camera starts once the preview exists, and instruction, feedback and meter are above the preview', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const EnrollmentScreen());
      expect(bridge.cameraStarts, 1);
      expect(find.text(S.enrollStepFormat(1)), findsOneWidget);
      final preview = tester.getTopLeft(find.byKey(const Key('fake-preview'))).dy;
      // everything the user must read while looking at the camera is above the preview and on screen without scrolling
      for (final key in const ['step-label', 'instruction', 'feedback']) {
        final box = tester.getBottomLeft(find.byKey(Key(key)));
        expect(box.dy, lessThanOrEqualTo(preview), reason: key);
        expect(box.dy, lessThan(891));
      }
      // preview is 70% of the width
      final width = tester.getSize(find.byKey(const Key('fake-preview'))).width;
      expect(width / 411, closeTo(0.7 * (411 - 32) / 411, 0.01));
    });

    testWidgets('shows live feedback from the camera messages', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const EnrollmentScreen());
      bridge.emit(const CameraFrame(FrameEvent(frameId: 1, timeMs: 1000, latencyMs: 80, faceCount: 0, pose: null, facing: false, similarity: null)));
      await flush(tester);
      expect(find.text(S.feedback(EnrollFeedback.noFace)), findsOneWidget);
      bridge.emit(const CameraFrame(FrameEvent(frameId: 2, timeMs: 1100, latencyMs: 80, faceCount: 2, pose: null, facing: false, similarity: null)));
      await flush(tester);
      expect(find.text(S.feedback(EnrollFeedback.multipleFaces)), findsOneWidget);
      expect(find.textContaining('pictures/s'), findsOneWidget);
    });

    testWidgets('a failing detector is reported', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const EnrollmentScreen());
      for (var i = 0; i < 10; i++) {
        bridge.emit(const CameraError(CameraProblem.detectionFailed));
        await tester.pump();
      }
      await flush(tester);
      expect(find.text(S.enrollDetectFailed), findsOneWidget);
    });

    testWidgets('fits a small screen (scrolls instead of overflowing)', (tester) async {
      await pumpScreen(tester, FakeBridge(), const EnrollmentScreen(), size: const Size(320, 480));
      expect(tester.takeException(), isNull);
    });

    testWidgets('leaving the screen releases the camera', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const EnrollmentScreen());
      expect(bridge.cameraRunning, isTrue);
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      expect(bridge.cameraRunning, isFalse);
    });
  });

  group('calibration screen', () {
    testWidgets('needs a profile', (tester) async {
      await pumpScreen(tester, FakeBridge(hasProfile: false), const CalibrationScreen());
      expect(find.text(S.calibNeedProfile), findsOneWidget);
    });

    testWidgets('cannot run while protection uses the camera', (tester) async {
      final bridge = FakeBridge(status: GuardStatus.active);
      await pumpScreen(tester, bridge, const CalibrationScreen());
      expect(find.text(S.setupStopFirst), findsOneWidget);
      expect(bridge.cameraStarts, 0);
    });

    testWidgets('measuring owner then skipping gives a result that can be applied', (tester) async {
      final bridge = FakeBridge();
      await pumpScreen(tester, bridge, const CalibrationScreen());
      expect(bridge.computeSimilarity, isTrue);
      await tester.tap(find.text(S.calibBtnOwner));
      await tester.pump();
      const pose = PoseSample(yaw: 0, pitch: 0, roll: 0, sizeRatio: 0.4, centerX: 0.5, centerY: 0.5);
      for (var i = 0; i < 30; i++) {
        bridge.emit(CameraFrame(FrameEvent(frameId: i, timeMs: 1000 + i * 400, latencyMs: 50, faceCount: 1, pose: pose, facing: true, similarity: 0.7 + (i % 4) * 0.01)));
        await tester.pump();
      }
      await flush(tester);
      expect(find.text(S.calibOtherPrompt), findsOneWidget);
      await tester.tap(find.text(S.calibBtnSkip));
      await tester.pump();
      await tester.scrollUntilVisible(find.text(S.calibBtnApply), 300, scrollable: find.byType(Scrollable).first);
      expect(find.text(S.calibQualityOwnerOnly), findsOneWidget);
      await tester.tap(find.text(S.calibBtnApply));
      await tester.pumpAndSettle();
      expect(find.text(S.calibApplied), findsOneWidget);
      expect(bridge.calls, contains('setThresholds'));
    });
  });
}
