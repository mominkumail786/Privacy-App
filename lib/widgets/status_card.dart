import 'package:flutter/material.dart';

import '../models/guard_status.dart';
import '../strings.dart';

/// The real protection status. Never more optimistic than what the native service reports.
class StatusCard extends StatelessWidget {
  const StatusCard({super.key, required this.snapshot});

  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final status = snapshot.shownStatus;
    final background = switch (status) {
      GuardStatus.active => scheme.primaryContainer,
      GuardStatus.pausedCameraBusy || GuardStatus.pausedScreenOff || GuardStatus.pausedLowBattery => scheme.tertiaryContainer,
      GuardStatus.stopped => scheme.surfaceContainerHighest,
      GuardStatus.permissionMissing || GuardStatus.setupNeeded || GuardStatus.degraded => scheme.errorContainer,
    };
    final score = snapshot.lastScore;
    return Card(
      color: background,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(S.statusLabel, style: text.labelLarge),
            const SizedBox(height: 6),
            Text(S.status(status), style: text.titleLarge, key: const Key('status-text')),
            if (status == GuardStatus.active) ...[
              const SizedBox(height: 6),
              // The live result of the last checked picture. Numbers only, never pictures.
              Text('${S.detectTitle}: ${S.detection(snapshot.detection)}', style: text.titleMedium, key: const Key('detection-text')),
              if (score != null) Text(S.detectScore(score.toStringAsFixed(2)), style: text.bodySmall),
            ],
            const SizedBox(height: 6),
            Text(S.statusNote, style: text.bodySmall),
          ],
        ),
      ),
    );
  }
}
