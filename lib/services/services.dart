import 'package:flutter/widgets.dart';

import '../bridge/native_bridge.dart';
import 'permission_service.dart';

/// Builds the live camera picture. [onReady] must be called once the view exists (the native camera needs it).
typedef CameraPreviewBuilder = Widget Function(BuildContext context, VoidCallback onReady);

/// Everything the screens need from outside Dart. Tests pass fakes.
class Services {
  const Services({required this.bridge, required this.permissions, required this.previewBuilder});

  final NativeBridge bridge;
  final PermissionService permissions;
  final CameraPreviewBuilder previewBuilder;
}

class ServicesScope extends InheritedWidget {
  const ServicesScope({super.key, required this.services, required super.child});

  final Services services;

  /// For code that runs once (initState) and so cannot depend on the scope.
  static Services read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<ServicesScope>();
    assert(scope != null, 'ServicesScope is missing above this widget');
    return scope!.services;
  }

  static Services of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<ServicesScope>();
    assert(scope != null, 'ServicesScope is missing above this widget');
    return scope!.services;
  }

  @override
  bool updateShouldNotify(ServicesScope oldWidget) => services != oldWidget.services;
}
