import 'package:flutter/foundation.dart';

import '../bridge/native_bridge.dart';
import '../models/guard_event.dart';

class EventLogController extends ChangeNotifier {
  EventLogController(this._bridge);

  final NativeBridge _bridge;
  List<GuardEvent> events = const [];
  bool loaded = false;

  Future<void> load() async {
    events = await _bridge.getEvents();
    loaded = true;
    notifyListeners();
  }

  Future<void> clear() async {
    await _bridge.clearEvents();
    events = const [];
    notifyListeners();
  }
}
