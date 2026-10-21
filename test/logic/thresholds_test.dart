import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/logic/thresholds.dart';

void main() {
  test('sensitivity levels are ordered (higher = more alerts)', () {
    final low = Thresholds.forSensitivity(Sensitivity.low);
    final high = Thresholds.forSensitivity(Sensitivity.high);
    expect(high.maxHeadAngle, greaterThan(low.maxHeadAngle));
    expect(high.minFaceSize, lessThan(low.minFaceSize));
    expect(high.requiredCount, lessThanOrEqualTo(low.requiredCount));
    expect(high.matchThreshold, greaterThan(low.matchThreshold));
  });

  test('every preset has a "not sure" zone', () {
    for (final s in Sensitivity.values) {
      final t = Thresholds.forSensitivity(s);
      expect(t.strangerBelow, lessThan(t.matchThreshold));
    }
  });

  test('map round trip keeps every value', () {
    final t = Thresholds.forSensitivity(Sensitivity.high).copyWith(alertOnUncertain: true, matchThreshold: 0.55);
    expect(Thresholds.fromMap(t.toMap()), t);
  });

  test('missing map keys fall back to the medium preset', () {
    expect(Thresholds.fromMap(const {}), Thresholds.forSensitivity(Sensitivity.medium));
  });

  // The native Kotlin service and this Dart code must use the SAME presets. This test reads the Kotlin file.
  test('Dart presets are identical to the Kotlin presets', () {
    final kotlin = File('android/app/src/main/kotlin/com/example/privacyguard/logic/Thresholds.kt').readAsStringSync();
    final pattern = RegExp(
        r'Sensitivity\.(LOW|MEDIUM|HIGH) -> Thresholds\(([\d.]+)f, ([\d.]+)f, (true|false), ([\d.]+)f, ([\d.]+)f, (\d+), (\d+)\)');
    final matches = pattern.allMatches(kotlin).toList();
    expect(matches.length, 3, reason: 'could not find the 3 presets in Thresholds.kt');
    for (final m in matches) {
      final level = Sensitivity.values.firstWhere((s) => s.name.toUpperCase() == m.group(1));
      final dart = Thresholds.forSensitivity(level);
      expect(dart.matchThreshold, double.parse(m.group(2)!), reason: '$level matchThreshold');
      expect(dart.uncertainMargin, double.parse(m.group(3)!), reason: '$level uncertainMargin');
      expect(dart.alertOnUncertain, m.group(4) == 'true', reason: '$level alertOnUncertain');
      expect(dart.maxHeadAngle, double.parse(m.group(5)!), reason: '$level maxHeadAngle');
      expect(dart.minFaceSize, double.parse(m.group(6)!), reason: '$level minFaceSize');
      expect(dart.windowSize, int.parse(m.group(7)!), reason: '$level windowSize');
      expect(dart.requiredCount, int.parse(m.group(8)!), reason: '$level requiredCount');
    }
  });
}
