import 'package:flutter/material.dart';

import '../services/permission_service.dart';
import '../services/services.dart';
import '../strings.dart';

/// Explains why a permission is needed, then asks for it.
/// Handles "denied" (can ask again) and "denied permanently" (must go to the app settings).
class PermissionCard extends StatefulWidget {
  const PermissionCard({
    super.key,
    required this.permission,
    required this.title,
    required this.reason,
    required this.granted,
    required this.onChanged,
  });

  final AppPermission permission;
  final String title;
  final String reason;
  final bool granted;
  final VoidCallback onChanged;

  @override
  State<PermissionCard> createState() => _PermissionCardState();
}

class _PermissionCardState extends State<PermissionCard> {
  bool _deniedForever = false;

  @override
  Widget build(BuildContext context) {
    final permissions = ServicesScope.of(context).permissions;
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(widget.reason),
            const SizedBox(height: 8),
            if (widget.granted)
              const Text('✓ ${S.permAllowed}')
            else if (_deniedForever) ...[
              const Text(S.permDeniedForever),
              const SizedBox(height: 8),
              FilledButton(onPressed: permissions.openAppSettings, child: const Text(S.permOpenSettings)),
            ] else
              FilledButton(
                onPressed: () async {
                  final outcome = await permissions.request(widget.permission);
                  if (!mounted) return;
                  setState(() => _deniedForever = outcome == PermissionOutcome.deniedForever);
                  widget.onChanged();
                },
                child: const Text(S.permAllow),
              ),
          ],
        ),
      ),
    );
  }
}
