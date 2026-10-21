import 'package:flutter/material.dart';

import 'screens/home_screen.dart';
import 'screens/onboarding_screen.dart';
import 'services/services.dart';
import 'strings.dart';

/// The whole app. Light and dark mode follow the phone's setting.
class PrivacyGuardApp extends StatelessWidget {
  const PrivacyGuardApp({super.key, required this.services});

  final Services services;

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF1565C0);
    return ServicesScope(
      services: services,
      child: MaterialApp(
        title: S.appName,
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: seed), useMaterial3: true),
        darkTheme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: seed, brightness: Brightness.dark),
          useMaterial3: true,
        ),
        home: const _Root(),
      ),
    );
  }
}

/// Shows the onboarding once, then the home screen.
class _Root extends StatefulWidget {
  const _Root();

  @override
  State<_Root> createState() => _RootState();
}

class _RootState extends State<_Root> {
  bool? _onboardingDone;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final settings = await ServicesScope.read(context).bridge.getSettings();
    if (mounted) setState(() => _onboardingDone = settings.onboardingDone);
  }

  Future<void> _finishOnboarding() async {
    await ServicesScope.read(context).bridge.setOnboardingDone(true);
    if (mounted) setState(() => _onboardingDone = true);
  }

  @override
  Widget build(BuildContext context) {
    final done = _onboardingDone;
    if (done == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    return done ? const HomeScreen() : OnboardingScreen(onFinished: _finishOnboarding);
  }
}
