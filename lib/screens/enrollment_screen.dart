import 'package:flutter/material.dart';

import '../controllers/enrollment_controller.dart';
import '../logic/enrollment_machine.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/camera_preview.dart';
import '../widgets/screen_scaffold.dart';

/// Face enrollment: 5 pictures (straight, left, right, up, down) -> one averaged, encrypted profile.
/// The movement rules are in `logic/enrollment_machine.dart` (tested without a phone).
class EnrollmentScreen extends StatefulWidget {
  const EnrollmentScreen({super.key});

  @override
  State<EnrollmentScreen> createState() => _EnrollmentScreenState();
}

enum _Gate { loading, ok, noPermission, noModel }

class _EnrollmentScreenState extends State<EnrollmentScreen> {
  _Gate _gate = _Gate.loading;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final s = await ServicesScope.read(context).bridge.getSnapshot();
    if (!mounted) return;
    setState(() => _gate = !s.cameraGranted ? _Gate.noPermission : (!s.modelAvailable ? _Gate.noModel : _Gate.ok));
  }

  @override
  Widget build(BuildContext context) {
    switch (_gate) {
      case _Gate.loading:
        return const ScreenScaffold(title: S.enrollTitle, children: [Center(child: CircularProgressIndicator())]);
      case _Gate.noPermission:
        return ScreenScaffold(title: S.enrollTitle, children: [
          Text(S.enrollNoPermission, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(
              onPressed: ServicesScope.of(context).permissions.openAppSettings,
              child: const Text(S.permOpenSettings),
            ),
          ),
        ]);
      case _Gate.noModel:
        return ScreenScaffold(title: S.enrollTitle, children: [
          Text(S.enrollModelMissing, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ]);
      case _Gate.ok:
        return const _EnrollmentCamera();
    }
  }
}

class _EnrollmentCamera extends StatefulWidget {
  const _EnrollmentCamera();

  @override
  State<_EnrollmentCamera> createState() => _EnrollmentCameraState();
}

class _EnrollmentCameraState extends State<_EnrollmentCamera> with WidgetsBindingObserver {
  late final EnrollmentController _controller;
  bool _popped = false;

  @override
  void initState() {
    super.initState();
    _controller = EnrollmentController(ServicesScope.read(context).bridge);
    _controller.addListener(_maybeFinish);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_maybeFinish);
    _controller.dispose(); // also releases the camera
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) _controller.pauseCamera();
    if (state == AppLifecycleState.resumed) _controller.resumeCamera();
  }

  void _maybeFinish() {
    if (_controller.ui.finished && !_popped) {
      _popped = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.of(context).pop(true);
      });
    }
  }

  static String _rejectText(EnrollReject r) {
    switch (r) {
      case EnrollReject.tooDark:
        return S.enrollDark;
      case EnrollReject.tooBright:
        return S.enrollBright;
      case EnrollReject.blurry:
        return S.enrollBlurry;
      case EnrollReject.notSame:
        return S.enrollNotSame;
      case EnrollReject.badSample:
        return S.enrollBadSample;
      case EnrollReject.starting:
        return S.feedback(EnrollFeedback.starting);
      case EnrollReject.none:
        return '';
    }
  }

  static String _failureText(EnrollFailure f) {
    switch (f) {
      case EnrollFailure.detectFailed:
        return S.enrollDetectFailed;
      case EnrollFailure.saveFailed:
        return S.enrollSaveFailed;
      case EnrollFailure.cameraFailed:
        return S.cameraFailed;
      case EnrollFailure.none:
        return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final services = ServicesScope.of(context);
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final ui = _controller.ui;
        final theme = Theme.of(context);
        final text = theme.textTheme;
        final good = ui.feedback == EnrollFeedback.ready || ui.feedback == EnrollFeedback.holdStill;
        const stepCount = 5;
        final shownStep = (ui.captured + 1).clamp(1, stepCount);

        Widget feedbackLine;
        if (ui.flash) {
          feedbackLine = Text(S.enrollCaptured, style: text.titleMedium?.copyWith(color: goodColor(context)), key: const Key('feedback'));
        } else if (ui.rejected != EnrollReject.none) {
          feedbackLine = Text(_rejectText(ui.rejected), style: text.bodyLarge?.copyWith(color: theme.colorScheme.error), key: const Key('feedback'));
        } else {
          feedbackLine = Text(
            S.feedback(ui.feedback),
            style: text.bodyLarge?.copyWith(color: good ? goodColor(context) : null),
            key: const Key('feedback'),
          );
        }

        return ScreenScaffold(
          title: S.enrollTitle,
          children: [
            // Everything the user must read is ABOVE the camera, so it is visible without scrolling.
            Text(S.enrollStepFormat(shownStep), style: text.labelLarge, key: const Key('step-label')),
            LinearProgressIndicator(value: ui.captured / stepCount),
            if (ui.saving)
              Text(S.enrollSaving, style: text.titleMedium, key: const Key('saving'))
            else ...[
              Text(S.enrollStep(ui.step), style: text.titleLarge, key: const Key('instruction')),
              feedbackLine,
              LinearProgressIndicator(value: ui.progress),
              if (ui.step != EnrollStep.front) Text(S.enrollMeter(ui.measured, ui.required), style: text.bodySmall),
            ],

            // Camera preview (70% wide, so the text above stays on screen). The oval turns green while the pose is held.
            Center(
              child: FractionallySizedBox(
                widthFactor: 0.7,
                child: AspectRatio(
                  aspectRatio: 3 / 4,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(16),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        services.previewBuilder(context, _controller.start),
                        OvalOverlay(good: ui.poseOk),
                      ],
                    ),
                  ),
                ),
              ),
            ),

            if (!ui.saving && ui.slow) Text(S.enrollSlowHint, style: text.bodyMedium),
            if (!ui.saving && ui.relaxed) Text(S.enrollRelaxed, style: text.bodySmall),
            if (ui.manualAllowed) ...[
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton(onPressed: _controller.captureNow, child: const Text(S.enrollBtnManual)),
              ),
              Text(S.enrollManualNote, style: text.bodySmall),
            ],
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                OutlinedButton(onPressed: _controller.retryStep, child: const Text(S.enrollBtnRetry)),
                OutlinedButton(onPressed: _controller.restart, child: const Text(S.enrollBtnRestart)),
              ],
            ),

            // Real numbers measured on this phone while enrolling.
            Text('${S.enrollTime(ui.seconds)}   |   ${S.enrollPerf(ui.fps, ui.latencyMs)}', style: text.bodySmall, key: const Key('perf')),
            if (ui.failure != EnrollFailure.none)
              Text(_failureText(ui.failure), style: TextStyle(color: theme.colorScheme.error), key: const Key('failure')),
            Text(S.enrollIntro, style: text.bodySmall),
            Text(S.enrollHintSides, style: text.bodySmall),
          ],
        );
      },
    );
  }
}
