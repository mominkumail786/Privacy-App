import 'package:flutter/material.dart';

import '../strings.dart';
import '../widgets/screen_scaffold.dart';

/// Honest explanation of what the app can and cannot do.
class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ScreenScaffold(
      title: S.aboutTitle,
      children: [for (final p in S.aboutParagraphs) Text(p, style: Theme.of(context).textTheme.bodyLarge)],
    );
  }
}
