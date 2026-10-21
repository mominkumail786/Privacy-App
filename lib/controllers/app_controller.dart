import 'dart:async';

import 'package:flutter/foundation.dart';

import '../bridge/native_bridge.dart';
import '../models/guard_status.dart';

/// State of the home screen: the real status of the native protection service, re-read every few seconds.
class AppController extends ChangeNotifier {
  AppController(this._bridge, {this.pollEvery = const Duration(seconds: 2)});

  final NativeBridge _bridge;
  final Duration pollEvery;

  GuardSnapshot snapshot = GuardSnapshot.initial;
  bool loaded = false;
  Timer? _timer;
  bool _refreshing = false;
  bool _disposed = false;

  /// Reads the state now and keeps reading it while the home screen is shown.
  Future<void> start() async {
    _timer ??= Timer.periodic(pollEvery, (_) => refresh());
    await refresh();
  }

  Future<void> refresh() async {
    if (_refreshing || _disposed) return;
    _refreshing = true;
    try {
      var next = await _bridge.getSnapshot();
      // After a phone restart the user taps "Tap to restart". The app is visible now, so starting is allowed.
      if (await _bridge.consumeAutoStart() && next.canStart) {
        await _bridge.startProtection();
        next = await _bridge.getSnapshot();
      }
      if (_disposed) return;
      snapshot = next;
      loaded = true;
      notifyListeners();
    } catch (e) {
      // The bridge failed. Keep the last known state; the next poll tries again.
      debugPrint('Status refresh failed: $e');
    } finally {
      _refreshing = false;
    }
  }

  Future<void> startProtection() async {
    if (!snapshot.canStart) return;
    await _bridge.startProtection();
    await refresh();
  }

  Future<void> stopProtection() async {
    await _bridge.stopProtection();
    await refresh();
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    super.dispose();
  }
}
