import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../bridge/method_channel_bridge.dart';

/// The live front-camera picture, drawn by an Android `PreviewView` (a native view inside Flutter).
/// [onReady] is called once the native view exists; only then can the native camera be started.
class NativeCameraPreview extends StatefulWidget {
  const NativeCameraPreview({super.key, required this.onReady});

  final VoidCallback onReady;

  /// Use this as `Services.previewBuilder` in the real app.
  static Widget build(BuildContext context, VoidCallback onReady) => NativeCameraPreview(onReady: onReady);

  @override
  State<NativeCameraPreview> createState() => _NativeCameraPreviewState();
}

class _NativeCameraPreviewState extends State<NativeCameraPreview> {
  @override
  void initState() {
    super.initState();
    if (defaultTargetPlatform != TargetPlatform.android) {
      // No native view on other platforms (for example when running the UI on a desktop). Nothing to wait for.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onReady();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const ColoredBox(color: Colors.black12, child: Center(child: Text('Camera preview: Android only')));
    }
    return AndroidView(
      viewType: MethodChannelBridge.previewViewType,
      layoutDirection: TextDirection.ltr,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (_) => widget.onReady(),
    );
  }
}

/// A placeholder preview for tests: reports "ready" right after the first frame.
Widget fakePreviewBuilder(BuildContext context, VoidCallback onReady) => _FakePreview(onReady: onReady);

class _FakePreview extends StatefulWidget {
  const _FakePreview({required this.onReady});
  final VoidCallback onReady;

  @override
  State<_FakePreview> createState() => _FakePreviewState();
}

class _FakePreviewState extends State<_FakePreview> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onReady();
    });
  }

  @override
  Widget build(BuildContext context) => const ColoredBox(color: Colors.black26, key: Key('fake-preview'));
}

/// Draws the oval guide on top of the camera picture; it turns green while the required pose is held.
class OvalOverlay extends StatelessWidget {
  const OvalOverlay({super.key, required this.good});

  final bool good;

  @override
  Widget build(BuildContext context) => CustomPaint(painter: _OvalPainter(good ? const Color(0xFF4CAF50) : Colors.white), size: Size.infinite);
}

class _OvalPainter extends CustomPainter {
  _OvalPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width * 0.62;
    final h = size.height * 0.62;
    final rect = Rect.fromLTWH((size.width - w) / 2, (size.height - h) / 2, w, h);
    canvas.drawOval(
      rect,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 6,
    );
  }

  @override
  bool shouldRepaint(_OvalPainter old) => old.color != color;
}
