import 'package:flutter_test/flutter_test.dart';
import 'package:privacyguard/logic/face_math.dart';
import 'package:privacyguard/logic/other_person_detector.dart';
import 'package:privacyguard/logic/thresholds.dart';

void main() {
  group('FaceMath', () {
    test('cosine of the same direction is one', () {
      expect(FaceMath.cosineSimilarity([1, 2, 3], [2, 4, 6]), closeTo(1, 1e-9));
    });
    test('cosine of perpendicular vectors is zero', () {
      expect(FaceMath.cosineSimilarity([1, 0], [0, 1]), closeTo(0, 1e-9));
    });
    test('cosine of opposite vectors is minus one', () {
      expect(FaceMath.cosineSimilarity([1, 1], [-1, -1]), closeTo(-1, 1e-9));
    });
    test('a zero vector gives zero, not NaN', () {
      expect(FaceMath.cosineSimilarity([0, 0], [1, 1]), 0);
    });
    test('different lengths are refused', () {
      expect(() => FaceMath.cosineSimilarity([1], [1, 2]), throwsArgumentError);
    });
    test('the average profile has length one and counts every input the same', () {
      final p = FaceMath.averageProfile([
        [3, 0],
        [0, 4],
      ]);
      expect(p[0] * p[0] + p[1] * p[1], closeTo(1, 1e-9));
      expect(p[0], closeTo(p[1], 1e-9));
    });
  });

  group('OtherPersonDetector', () {
    final t = Thresholds.forSensitivity(Sensitivity.medium);
    final d = OtherPersonDetector(t);

    test('verdict boundaries: owner, uncertain, stranger', () {
      expect(d.verdict(t.matchThreshold), FaceVerdict.owner);
      expect(d.verdict(0.9), FaceVerdict.owner);
      expect(d.verdict(t.matchThreshold - 0.01), FaceVerdict.uncertain);
      expect(d.verdict(t.strangerBelow), FaceVerdict.uncertain);
      expect(d.verdict(t.strangerBelow - 0.01), FaceVerdict.stranger);
    });

    test('a big face looking at the screen is facing', () {
      expect(d.isFacingScreen(const FacePose(sizeRatio: 0.3, yaw: 5, pitch: -5)), isTrue);
    });
    test('a face turned away is not facing', () {
      expect(d.isFacingScreen(FacePose(sizeRatio: 0.3, yaw: t.maxHeadAngle + 5, pitch: 0)), isFalse);
      expect(d.isFacingScreen(FacePose(sizeRatio: 0.3, yaw: 0, pitch: -(t.maxHeadAngle + 5))), isFalse);
    });
    test('a tiny far-away face is not facing', () {
      expect(d.isFacingScreen(FacePose(sizeRatio: t.minFaceSize / 2, yaw: 0, pitch: 0)), isFalse);
    });
  });

  group('FrameState', () {
    test('parses the names sent by the native side', () {
      expect(FrameState.parse('OWNER_AND_OTHER'), FrameState.ownerAndOther);
      expect(FrameState.parse('NO_FACE'), FrameState.noFace);
      expect(FrameState.parse('something else'), isNull);
      expect(FrameState.parse(null), isNull);
    });
  });
}
