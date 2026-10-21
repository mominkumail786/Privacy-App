import 'package:flutter/material.dart';

import '../controllers/app_controller.dart';
import '../services/permission_service.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/permission_card.dart';
import '../widgets/screen_scaffold.dart';
import '../widgets/status_card.dart';
import 'about_screen.dart';
import 'battery_help_screen.dart';
import 'enrollment_screen.dart';
import 'event_log_screen.dart';
import 'settings_screen.dart';

/// The main screen: real protection status, start/stop and the setup steps.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  late final AppController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AppController(ServicesScope.read(context).bridge)..start();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from the system settings, from a notification tap, or from another app: re-read everything.
    if (state == AppLifecycleState.resumed) _controller.refresh();
  }

  Future<void> _open(Widget screen) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) _controller.refresh();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final s = _controller.snapshot;
        final theme = Theme.of(context);
        return ScreenScaffold(
          title: S.appName,
          showBackButton: false,
          children: [
            StatusCard(snapshot: s),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                FilledButton(
                  onPressed: s.canStart ? _controller.startProtection : null,
                  child: const Text(S.btnStart),
                ),
                OutlinedButton(
                  onPressed: s.running ? _controller.stopProtection : null,
                  child: const Text(S.btnStop),
                ),
              ],
            ),
            if (!s.canStart && !s.running) Text(S.startBlocked, style: theme.textTheme.bodyMedium),
            Text(S.setupTitle, style: theme.textTheme.titleLarge),
            if (!s.cameraGranted)
              PermissionCard(
                permission: AppPermission.camera,
                title: S.permCameraTitle,
                reason: S.permCameraReason,
                granted: false,
                onChanged: _controller.refresh,
              ),
            if (!s.notificationsGranted)
              PermissionCard(
                permission: AppPermission.notifications,
                title: S.permNotifTitle,
                reason: S.permNotifReason,
                granted: false,
                onChanged: _controller.refresh,
              ),
            InfoCard(children: [
              if (!s.modelAvailable) Text(S.setupModelMissing, style: TextStyle(color: theme.colorScheme.error)),
              Text(s.hasProfile ? S.setupProfileOk : S.setupProfileMissing),
              if (s.running) const Text(S.setupStopFirst),
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton(
                  onPressed: (s.cameraGranted && s.modelAvailable && !s.running) ? () => _open(const EnrollmentScreen()) : null,
                  child: Text(s.hasProfile ? S.btnReenroll : S.btnEnroll),
                ),
              ),
            ]),
            InfoCard(children: [
              Text(s.batteryOptimized ? S.setupBatteryOn : S.setupBatteryOff),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton(onPressed: () => _open(const BatteryHelpScreen()), child: const Text(S.btnBattery)),
              ),
            ]),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                OutlinedButton(onPressed: () => _open(const SettingsScreen()), child: const Text(S.btnSettings)),
                OutlinedButton(onPressed: () => _open(const EventLogScreen()), child: const Text(S.btnEventLog)),
              ],
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(onPressed: () => _open(const AboutScreen()), child: const Text(S.btnAbout)),
            ),
          ],
        );
      },
    );
  }
}
