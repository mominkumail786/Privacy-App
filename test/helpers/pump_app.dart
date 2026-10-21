import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/app.dart';
import 'package:privacyguard/services/services.dart';
import 'package:privacyguard/widgets/camera_preview.dart';

import 'fake_bridge.dart';

/// Delivers pending stream events, then draws the result (events arrive in microtasks after the frame).
Future<void> flush(WidgetTester tester) async {
  await tester.pump();
  await tester.pump();
}

/// The whole app with a fake native side. The camera preview is a plain box.
Future<FakePermissions> pumpApp(WidgetTester tester, FakeBridge bridge, {Size size = const Size(411, 891)}) async {
  tester.view.physicalSize = size * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final permissions = FakePermissions(bridge);
  await tester.pumpWidget(PrivacyGuardApp(
    services: Services(bridge: bridge, permissions: permissions, previewBuilder: fakePreviewBuilder),
  ));
  await tester.pumpAndSettle();
  return permissions;
}

/// A single screen inside the scope, for tests that do not need the root logic.
Future<FakePermissions> pumpScreen(WidgetTester tester, FakeBridge bridge, Widget screen, {Size size = const Size(411, 891), ThemeMode mode = ThemeMode.light}) async {
  tester.view.physicalSize = size * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final permissions = FakePermissions(bridge);
  await tester.pumpWidget(const SizedBox()); // drop any earlier screen, so no State is reused
  await tester.pumpWidget(ServicesScope(
    services: Services(bridge: bridge, permissions: permissions, previewBuilder: fakePreviewBuilder),
    child: MaterialApp(
      themeMode: mode,
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue), useMaterial3: true),
      darkTheme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue, brightness: Brightness.dark), useMaterial3: true),
      home: screen,
    ),
  ));
  await tester.pumpAndSettle();
  return permissions;
}
