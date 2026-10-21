import 'package:flutter/material.dart';

import 'app.dart';
import 'bridge/method_channel_bridge.dart';
import 'services/permission_service.dart';
import 'services/services.dart';
import 'widgets/camera_preview.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    PrivacyGuardApp(
      services: Services(
        bridge: MethodChannelBridge(),
        permissions: const PermissionHandlerService(),
        previewBuilder: NativeCameraPreview.build,
      ),
    ),
  );
}
