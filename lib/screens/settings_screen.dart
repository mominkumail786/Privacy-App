import 'package:flutter/material.dart';

import '../controllers/settings_controller.dart';
import '../logic/thresholds.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/screen_scaffold.dart';
import 'battery_help_screen.dart';
import 'calibration_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final SettingsController _controller;

  @override
  void initState() {
    super.initState();
    _controller = SettingsController(ServicesScope.read(context).bridge)..load();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _confirmDelete() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text(S.deleteTitle),
        content: const Text(S.deleteBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text(S.cancel)),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text(S.deleteConfirm)),
        ],
      ),
    );
    if (yes != true) return;
    await _controller.deleteProfile(); // stops protection, erases the profile file and its Keystore key
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text(S.deleteDone)));
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final text = Theme.of(context).textTheme;
        final data = _controller.data;
        final t = data.thresholds;
        final levels = [
          (Sensitivity.low, S.sensLow),
          (Sensitivity.medium, S.sensMedium),
          (Sensitivity.high, S.sensHigh),
        ];
        return ScreenScaffold(
          title: S.settingsTitle,
          // "About and Developer" is the very last thing on the screen.
          footer: [_AboutAndDeveloper(version: _controller.version)],
          children: [
            // ----- Sensitivity
            Text(S.settingsSensitivity, style: text.titleLarge),
            for (final (level, label) in levels)
              _SensitivityTile(
                label: label,
                selected: data.sensitivity == level,
                onTap: () => _controller.chooseSensitivity(level),
              ),
            const Divider(),

            // ----- Advanced thresholds
            Text(S.settingsAdvanced, style: text.titleLarge),
            Text(S.settingsAdvancedNote, style: text.bodySmall),
            Text(S.advMatch(t.matchThreshold.toStringAsFixed(2))),
            Slider(
              value: t.matchThreshold.clamp(0.2, 0.8),
              min: 0.2,
              max: 0.8,
              onChanged: (v) => _controller.previewThresholds(t.copyWith(matchThreshold: (v * 100).round() / 100)),
              onChangeEnd: (_) => _controller.commitThresholds(),
            ),
            Text(S.advUncertain(t.uncertainMargin.toStringAsFixed(2), t.strangerBelow.toStringAsFixed(2))),
            Slider(
              value: t.uncertainMargin.clamp(0.0, 0.25),
              min: 0,
              max: 0.25,
              onChanged: (v) => _controller.previewThresholds(t.copyWith(uncertainMargin: (v * 100).round() / 100)),
              onChangeEnd: (_) => _controller.commitThresholds(),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text(S.settingsAlertUncertain),
              value: t.alertOnUncertain,
              onChanged: _controller.setAlertOnUncertain,
            ),
            Text(S.advAngle(t.maxHeadAngle.round())),
            Slider(
              value: t.maxHeadAngle.clamp(10, 60),
              min: 10,
              max: 60,
              onChanged: (v) => _controller.previewThresholds(t.copyWith(maxHeadAngle: v.roundToDouble())),
              onChangeEnd: (_) => _controller.commitThresholds(),
            ),
            Text(S.advFace((t.minFaceSize * 100).round())),
            Slider(
              value: t.minFaceSize.clamp(0.05, 0.4),
              min: 0.05,
              max: 0.4,
              onChanged: (v) => _controller.previewThresholds(t.copyWith(minFaceSize: (v * 100).round() / 100)),
              onChangeEnd: (_) => _controller.commitThresholds(),
            ),
            Text(S.advFrames(t.requiredCount, t.windowSize)),
            const Divider(),

            // ----- Alerts
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text(S.settingsVibration),
              value: data.vibration,
              onChanged: _controller.setVibration,
            ),
            Text(S.settingsCooldown(data.cooldownSeconds)),
            Slider(
              value: data.cooldownSeconds.toDouble().clamp(5, 300),
              min: 5,
              max: 300,
              divisions: 59, // steps of 5 seconds
              onChanged: (v) => _controller.previewCooldown((v / 5).round() * 5),
              onChangeEnd: (_) => _controller.commitCooldown(),
            ),
            const Divider(),

            // ----- Calibration, battery help, delete
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const CalibrationScreen())),
                child: const Text(S.settingsCalibrate),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const BatteryHelpScreen())),
                child: const Text(S.settingsBattery),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: FilledButton(onPressed: _confirmDelete, child: const Text(S.settingsDeleteProfile)),
            ),
          ],
        );
      },
    );
  }
}

/// "About and Developer" block at the very bottom of Settings.
/// Colours and fonts come from the app theme, so light and dark mode work automatically.
/// Text wraps instead of being cut off, so it also fits small screens.
class _AboutAndDeveloper extends StatelessWidget {
  const _AboutAndDeveloper({required this.version});

  final String version;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(),
        const SizedBox(height: 8),
        Text(S.aboutDeveloperTitle, style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        Text('${S.appName}  •  ${S.aboutVersion(version)}', style: theme.textTheme.bodyMedium?.copyWith(color: muted)),
        const SizedBox(height: 4),
        Text(S.aboutDeveloperSummary, style: theme.textTheme.bodySmall?.copyWith(color: muted)),
        // The developer credit: centred, small and quiet, with a short divider above it.
        Padding(
          padding: const EdgeInsets.only(top: 12, bottom: 8),
          child: Column(
            children: [
              const FractionallySizedBox(widthFactor: 0.4, child: Divider()),
              const SizedBox(height: 8),
              Text(
                S.developerCredit,
                key: const Key('developer-credit'),
                textAlign: TextAlign.center,
                style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                S.developerRole,
                key: const Key('developer-role'),
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium?.copyWith(fontStyle: FontStyle.italic, color: muted),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// One radio-style choice. Built from basic widgets so it works the same on every Flutter version.
class _SensitivityTile extends StatelessWidget {
  const _SensitivityTile({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      inMutuallyExclusiveGroup: true,
      checked: selected,
      button: true,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            children: [
              Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked, color: selected ? Theme.of(context).colorScheme.primary : null),
              const SizedBox(width: 12),
              Expanded(child: Text(label)),
            ],
          ),
        ),
      ),
    );
  }
}
