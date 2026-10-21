import 'package:permission_handler/permission_handler.dart' as ph;

/// The two permissions of the app.
enum AppPermission { camera, notifications }

/// Result of asking for a permission.
enum PermissionOutcome {
  granted,

  /// The user said no; Android will ask again next time.
  denied,

  /// Android will not show the question again. The user must allow it in the app settings.
  deniedForever,
}

abstract class PermissionService {
  Future<PermissionOutcome> request(AppPermission permission);
  Future<void> openAppSettings();
}

/// Uses the `permission_handler` plugin. Whether a permission is granted is read from the native side (snapshot),
/// so this class only asks.
class PermissionHandlerService implements PermissionService {
  const PermissionHandlerService();

  ph.Permission _map(AppPermission p) => p == AppPermission.camera ? ph.Permission.camera : ph.Permission.notification;

  @override
  Future<PermissionOutcome> request(AppPermission permission) async {
    final status = await _map(permission).request();
    if (status.isGranted || status.isLimited) return PermissionOutcome.granted;
    if (status.isPermanentlyDenied) return PermissionOutcome.deniedForever;
    return PermissionOutcome.denied;
  }

  @override
  Future<void> openAppSettings() async {
    await ph.openAppSettings();
  }
}
