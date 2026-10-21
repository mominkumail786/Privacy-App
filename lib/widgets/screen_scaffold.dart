import 'package:flutter/material.dart';

/// Common layout: app bar with title (and a back arrow on pushed screens) and a scrollable column.
/// [footer] is the very last thing on the screen (Settings uses it for the About and Developer block).
class ScreenScaffold extends StatelessWidget {
  const ScreenScaffold({super.key, required this.title, required this.children, this.footer, this.showBackButton = true});

  final String title;
  final List<Widget> children;
  final List<Widget>? footer;
  final bool showBackButton;

  @override
  Widget build(BuildContext context) {
    final all = <Widget>[...children, ...?footer];
    return Scaffold(
      appBar: AppBar(title: Text(title), automaticallyImplyLeading: showBackButton),
      body: SafeArea(
        child: ListView.separated(
          padding: const EdgeInsets.all(16),
          itemCount: all.length,
          separatorBuilder: (_, __) => const SizedBox(height: 12),
          itemBuilder: (_, i) => all[i],
        ),
      ),
    );
  }
}

/// A simple rounded card with padding and spaced children.
class InfoCard extends StatelessWidget {
  const InfoCard({super.key, required this.children, this.color});

  final List<Widget> children;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: color,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (var i = 0; i < children.length; i++) ...[if (i > 0) const SizedBox(height: 8), children[i]],
          ],
        ),
      ),
    );
  }
}

/// Green that is readable in light and dark mode.
Color goodColor(BuildContext context) =>
    Theme.of(context).brightness == Brightness.dark ? const Color(0xFF81C784) : const Color(0xFF2E7D32);
