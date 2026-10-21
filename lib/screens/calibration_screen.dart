import 'package:flutter/material.dart';

import '../controllers/calibration_controller.dart';
import '../logic/threshold_calibrator.dart';
import '../services/services.dart';
import '../strings.dart';
import '../widgets/screen_scaffold.dart';

/// Measures the owner's (and optionally another person's) similarity scores on THIS phone and suggests thresholds.
class CalibrationScreen extends StatefulWidget {
  const CalibrationScreen({super.key});

  @override
  State<CalibrationScreen> createState() => _CalibrationScreenState();
}

enum _Gate { loading, ok, noModel, noProfile, running }

class _CalibrationScreenState extends State<CalibrationScreen> {
  _Gate _gate = _Gate.loading;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final s = await ServicesScope.read(context).bridge.getSnapshot();
    if (!mounted) return;
    setState(() {
      if (!s.modelAvailable) {
        _gate = _Gate.noModel;
      } else if (!s.hasProfile) {
        _gate = _Gate.noProfile;
      } else if (s.running) {
        _gate = _Gate.running; // the camera is used by the protection service
      } else {
        _gate = _Gate.ok;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final error = TextStyle(color: Theme.of(context).colorScheme.error);
    switch (_gate) {
      case _Gate.loading:
        return const ScreenScaffold(title: S.calibTitle, children: [Center(child: CircularProgressIndicator())]);
      case _Gate.noModel:
        return ScreenScaffold(title: S.calibTitle, children: [Text(S.enrollModelMissing, style: error)]);
      case _Gate.noProfile:
        return ScreenScaffold(title: S.calibTitle, children: [Text(S.calibNeedProfile, style: error)]);
      case _Gate.running:
        return ScreenScaffold(title: S.calibTitle, children: [Text(S.setupStopFirst, style: error)]);
      case _Gate.ok:
        return const _CalibrationCamera();
    }
  }
}

class _CalibrationCamera extends StatefulWidget {
  const _CalibrationCamera();

  @override
  State<_CalibrationCamera> createState() => _CalibrationCameraState();
}

class _CalibrationCameraState extends State<_CalibrationCamera> with WidgetsBindingObserver {
  late final CalibrationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = CalibrationController(ServicesScope.read(context).bridge);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose(); // also releases the camera
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) _controller.pauseCamera();
    if (state == AppLifecycleState.resumed) _controller.resumeCamera();
  }

  static String _two(double? v) => v == null ? '-' : v.toStringAsFixed(2);

  @override
  Widget build(BuildContext context) {
    final services = ServicesScope.of(context);
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        final c = _controller;
        final theme = Theme.of(context);
        final text = theme.textTheme;
        return ScreenScaffold(
          title: S.calibTitle,
          children: [
            AspectRatio(
              aspectRatio: 3 / 4,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(16),
                child: services.previewBuilder(context, c.start),
              ),
            ),
            ..._phase(context, c, text),
            if (c.failed) Text(S.enrollError, style: TextStyle(color: theme.colorScheme.error)),
          ],
        );
      },
    );
  }

  List<Widget> _phase(BuildContext context, CalibrationController c, TextTheme text) {
    switch (c.phase) {
      case CalibPhase.intro:
        return [
          Text(S.calibIntro, style: text.bodyMedium),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(onPressed: c.beginOwner, child: const Text(S.calibBtnOwner)),
          ),
        ];
      case CalibPhase.owner:
      case CalibPhase.other:
        return [
          Text(c.phase == CalibPhase.owner ? S.calibRecordingOwner : S.calibRecordingOther, style: text.titleMedium),
          LinearProgressIndicator(value: c.count / CalibrationController.targetSamples),
          Text(S.calibSamples(c.count, CalibrationController.targetSamples), key: const Key('calib-count')),
          if (c.liveScore != null) Text(S.calibLive(c.liveScore!.toStringAsFixed(2))),
          Text(c.hint == CalibHint.look ? S.calibLook : S.calibOnePerson, style: text.bodyMedium, key: const Key('calib-hint')),
        ];
      case CalibPhase.askOther:
        return [
          Text(S.calibOtherPrompt, style: text.bodyMedium),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              FilledButton(onPressed: c.beginOther, child: const Text(S.calibBtnOther)),
              OutlinedButton(onPressed: c.skipOther, child: const Text(S.calibBtnSkip)),
            ],
          ),
        ];
      case CalibPhase.result:
        final r = c.result;
        final match = r?.matchThreshold;
        final margin = r?.uncertainMargin;
        return [
          if (r != null) ...[
            Text(S.calibResultTitle, style: text.titleLarge),
            Text(_quality(r.quality), style: text.bodyMedium),
            if (match != null && margin != null) ...[
              Text(
                S.calibNumbers(_two(r.ownerLow), _two(r.otherHigh), match.toStringAsFixed(2), (match - margin).toStringAsFixed(2)),
                style: text.bodyMedium,
                key: const Key('calib-numbers'),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton(onPressed: c.applied ? null : c.apply, child: Text(c.applied ? S.calibApplied : S.calibBtnApply)),
              ),
            ],
          ],
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton(onPressed: c.measureAgain, child: const Text(S.calibBtnAgain)),
          ),
        ];
    }
  }

  static String _quality(CalibrationQuality q) {
    switch (q) {
      case CalibrationQuality.good:
        return S.calibQualityGood;
      case CalibrationQuality.overlap:
        return S.calibQualityOverlap;
      case CalibrationQuality.ownerOnly:
        return S.calibQualityOwnerOnly;
      case CalibrationQuality.notEnoughData:
        return S.calibQualityNoData;
    }
  }
}
