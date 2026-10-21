import 'package:flutter/material.dart';

import '../controllers/event_log_controller.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/screen_scaffold.dart';

/// The last events: alerts, camera busy, errors. Only time, kind and a similarity number. No pictures.
class EventLogScreen extends StatefulWidget {
  const EventLogScreen({super.key});

  @override
  State<EventLogScreen> createState() => _EventLogScreenState();
}

class _EventLogScreenState extends State<EventLogScreen> {
  late final EventLogController _controller;

  @override
  void initState() {
    super.initState();
    _controller = EventLogController(ServicesScope.read(context).bridge)..load();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
  static const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  static String formatTime(int millis) {
    final t = DateTime.fromMillisecondsSinceEpoch(millis);
    return '${_two(t.day)} ${_months[t.month - 1]} ${_two(t.hour)}:${_two(t.minute)}:${_two(t.second)}';
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final events = _controller.events;
        return ScreenScaffold(
          title: S.eventsTitle,
          children: [
            if (_controller.loaded && events.isEmpty) const Text(S.eventsEmpty),
            for (final e in events)
              Text(
                '${formatTime(e.timeMillis)}   ${S.event(e.type)}${e.score == null ? '' : '  (${e.score!.toStringAsFixed(2)})'}',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(onPressed: _controller.clear, child: const Text(S.btnClearLog)),
            ),
          ],
        );
      },
    );
  }
}
