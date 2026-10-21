import 'thresholds.dart';

/// What the face detector tells us about one face (numbers only).
class FacePose {
  const FacePose({required this.sizeRatio, required this.yaw, required this.pitch});

  /// Face width / picture width.
  final double sizeRatio;

  /// Head turn left/right in degrees. 0 = looking straight at the camera.
  final double yaw;

  /// Head tilt up/down in degrees. 0 = straight.
  final double pitch;
}

/// Verdict for one face.
enum FaceVerdict { owner, uncertain, stranger }

/// What the app understood about one whole camera picture. The names match the native Kotlin enum.
enum FrameState {
  noFace('NO_FACE'),
  notFacing('NOT_FACING'),
  owner('OWNER'),
  ownerAndOther('OWNER_AND_OTHER'),
  otherPerson('OTHER_PERSON'),
  uncertain('UNCERTAIN');

  const FrameState(this.nativeName);
  final String nativeName;

  static FrameState? parse(Object? value) {
    for (final s in FrameState.values) {
      if (s.nativeName == value) return s;
    }
    return null;
  }
}

/// The per-face rules of the native detector, used by the calibration screen and in tests.
///
/// EYE GAZE IS NOT USED. Phone front cameras are too small and too low-resolution to tell reliably where the
/// eyes look, so only the head direction counts.
class OtherPersonDetector {
  const OtherPersonDetector(this.thresholds);

  final Thresholds thresholds;

  /// True if this face is big enough and its head points towards the screen.
  bool isFacingScreen(FacePose face) {
    if (face.sizeRatio < thresholds.minFaceSize) return false;
    return face.yaw.abs() <= thresholds.maxHeadAngle && face.pitch.abs() <= thresholds.maxHeadAngle;
  }

  /// Owner, stranger, or not sure, from the similarity to the owner's profile.
  FaceVerdict verdict(double similarity) {
    if (similarity >= thresholds.matchThreshold) return FaceVerdict.owner;
    if (similarity < thresholds.strangerBelow) return FaceVerdict.stranger;
    return FaceVerdict.uncertain;
  }
}
