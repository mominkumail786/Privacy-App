import 'package:flutter/material.dart';

import '../services/services.dart';
import '../strings.dart';
import '../widgets/screen_scaffold.dart';

/// Explains battery optimization in simple words and opens the correct system screen.
class BatteryHelpScreen extends StatefulWidget {
  const BatteryHelpScreen({super.key});

  @override
  State<BatteryHelpScreen> createState() => _BatteryHelpScreenState();
}

class _BatteryHelpScreenState extends State<BatteryHelpScreen> with WidgetsBindingObserver {
  bool _optimized = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from the system dialog: read the new state.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final s = await ServicesScope.read(context).bridge.getSnapshot();
    if (mounted) setState(() => _optimized = s.batteryOptimized);
  }

  @override
  Widget build(BuildContext context) {
    final services = ServicesScope.of(context);
    final text = Theme.of(context).textTheme;
    return ScreenScaffold(
      title: S.batteryTitle,
      children: [
        Text(S.batteryWhy, style: text.bodyLarge),
        Text(_optimized ? S.batteryStatusOn : S.batteryStatusOff, style: text.titleMedium),
        if (_optimized)
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(
              onPressed: services.bridge.requestIgnoreBatteryOptimizations,
              child: const Text(S.batteryBtnTurnOff),
            ),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton(onPressed: services.bridge.openBatteryList, child: const Text(S.batteryBtnList)),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton(onPressed: services.permissions.openAppSettings, child: const Text(S.batteryBtnAppSettings)),
        ),
        Text(S.batteryOem, style: text.bodyMedium),
      ],
    );
  }
}
