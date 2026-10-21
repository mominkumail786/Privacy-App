import 'package:flutter/material.dart';

import '../models/guard_status.dart';
import '../services/permission_service.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/permission_card.dart';
import '../widgets/screen_scaffold.dart';

/// Four short pages. Permissions are only asked on the last page, each with its reason.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.onFinished});

  final VoidCallback onFinished;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> with WidgetsBindingObserver {
  static const int _lastPage = 3;
  int _page = 0;
  GuardSnapshot _snapshot = GuardSnapshot.initial;

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
    // The user may come back from the system settings after allowing a permission there.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final s = await ServicesScope.read(context).bridge.getSnapshot();
    if (mounted) setState(() => _snapshot = s);
  }

  @override
  Widget build(BuildContext context) {
    return ScreenScaffold(
      title: S.onbTitles[_page],
      showBackButton: false,
      children: [
        Text(S.onbBodies[_page], style: Theme.of(context).textTheme.bodyLarge),
        if (_page == _lastPage) ...[
          PermissionCard(
            permission: AppPermission.camera,
            title: S.permCameraTitle,
            reason: S.permCameraReason,
            granted: _snapshot.cameraGranted,
            onChanged: _refresh,
          ),
          PermissionCard(
            permission: AppPermission.notifications,
            title: S.permNotifTitle,
            reason: S.permNotifReason,
            granted: _snapshot.notificationsGranted,
            onChanged: _refresh,
          ),
        ],
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            if (_page > 0) OutlinedButton(onPressed: () => setState(() => _page--), child: const Text(S.back)),
            if (_page < _lastPage)
              FilledButton(onPressed: () => setState(() => _page++), child: const Text(S.onbNext))
            else
              FilledButton(onPressed: widget.onFinished, child: const Text(S.onbFinish)),
          ],
        ),
      ],
    );
  }
}
