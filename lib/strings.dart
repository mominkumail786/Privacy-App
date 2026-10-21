import 'logic/enrollment_machine.dart';
import 'logic/other_person_detector.dart';
import 'models/guard_event.dart';
import 'models/guard_status.dart';

/// Every text the user sees, in one place (English).
class S {
  const S._();

  static const appName = 'AI Privacy Guard';
  static const back = 'Back';
  static const cancel = 'Cancel';

  // ---- Onboarding
  static const onbTitles = [
    'Welcome to AI Privacy Guard',
    'Your privacy comes first',
    'What it can and cannot do',
    'Two permissions',
  ];
  static const onbBodies = [
    'This app uses your front camera to warn you when another person may be looking at your phone screen while you use WhatsApp, Instagram, Chrome, games and other apps.',
    'Everything happens on your phone. The app has no internet permission, so it cannot upload anything.\n\nYour face is saved only as a list of numbers (not a photo), and it is encrypted.\n\nPhotos of other people are never saved: each camera picture is checked in memory and thrown away.\n\nThe Android camera indicator stays visible while the app watches.',
    'It only sees what the front camera sees. People beside you, behind you or outside the camera view cannot be detected.\n\nIt cannot tell where eyes are looking. It only checks which way a head is turned.\n\nIt can make mistakes, so never rely on it as your only protection.\n\nIf another app (for example a video call) takes the camera, protection pauses until the camera is free.',
    'We ask only for what is needed, and we explain why. You can also do this later from the main screen.',
  ];
  static const onbNext = 'Next';
  static const onbFinish = 'Finish';

  // ---- Permissions
  static const permCameraTitle = 'Camera';
  static const permCameraReason = 'Needed to see faces in front of your phone. Pictures are never saved or sent anywhere.';
  static const permNotifTitle = 'Notifications';
  static const permNotifReason = 'Needed to show that protection is running and to show the Privacy Alert.';
  static const permAllow = 'Allow';
  static const permAllowed = 'Allowed';
  static const permDeniedForever = 'Android will not ask again. Open the app settings, tap Permissions, and allow it there.';
  static const permOpenSettings = 'Open app settings';

  // ---- Status
  static String status(GuardStatus s) {
    switch (s) {
      case GuardStatus.active:
        return 'Active: watching the front camera';
      case GuardStatus.pausedCameraBusy:
        return 'Protection paused: camera in use by another app';
      case GuardStatus.pausedScreenOff:
        return 'Paused: screen is off';
      case GuardStatus.pausedLowBattery:
        return 'Paused: battery is low';
      case GuardStatus.degraded:
        return 'Protection degraded: face analysis keeps failing';
      case GuardStatus.stopped:
        return 'Stopped';
      case GuardStatus.permissionMissing:
        return 'Permission missing';
      case GuardStatus.setupNeeded:
        return 'Setup needed (see below)';
    }
  }

  // ---- Home
  static const statusLabel = 'Status';
  static const statusNote =
      'The status is re-checked every few seconds. Android can stop apps at any time, so protection is never guaranteed.';
  static const btnStart = 'Start Protection';
  static const btnStop = 'Stop';
  static const startBlocked = 'To start: allow the camera and notifications, and create your face profile.';
  static const setupTitle = 'Setup';
  static const setupProfileMissing = 'Face profile: not created yet';
  static const setupProfileOk = 'Face profile: saved on this phone (encrypted)';
  static const btnEnroll = 'Create my face profile';
  static const btnReenroll = 'Create the profile again';
  static const setupModelMissing =
      'The face model file is missing. Follow README.md, section "Download the face model", then build the app again.';
  static const setupStopFirst = 'Stop protection first. The camera can only be used by one screen at a time.';
  static const setupBatteryOn = 'Battery optimization is ON. Android may stop the app to save battery.';
  static const setupBatteryOff = 'Battery optimization is OFF for this app.';
  static const btnBattery = 'Battery help';
  static const btnSettings = 'Settings';
  static const btnAbout = 'About and limits';
  static const btnEventLog = 'Event log';

  static const detectTitle = 'Right now';
  static String detection(FrameState? s) {
    switch (s) {
      case null:
        return 'Waiting for the first picture...';
      case FrameState.noFace:
        return 'No face in view';
      case FrameState.notFacing:
        return 'Face seen, but nobody is looking at the screen';
      case FrameState.owner:
        return 'Owner detected';
      case FrameState.ownerAndOther:
        return 'Owner and another person detected';
      case FrameState.otherPerson:
        return 'Other person detected';
      case FrameState.uncertain:
        return 'Not sure who this is';
    }
  }

  static String detectScore(String score) => 'Best match to your face profile: $score';

  // ---- Enrollment
  static const enrollTitle = 'Face profile';
  static const enrollIntro =
      'Sit in a bright place and look at the camera. We take 5 pictures: straight, then a small turn to each side, then a small nod up and down. Small, natural movements are enough. Only a list of numbers is kept, encrypted. The pictures are NOT saved.';
  static String enrollStepFormat(int n) => 'Step $n of 5';
  static String enrollStep(EnrollStep s) {
    switch (s) {
      case EnrollStep.front:
        return 'Look straight at the camera and hold still';
      case EnrollStep.left:
        return 'Turn your head a little to the LEFT';
      case EnrollStep.right:
        return 'Now turn a little to the RIGHT';
      case EnrollStep.up:
        return 'Tilt your head a little UP';
      case EnrollStep.down:
        return 'Now tilt a little DOWN';
    }
  }

  static const enrollHintSides =
      'If left and right (or up and down) feel swapped, it does not matter: the second movement just has to go the opposite way of the first.';
  static String feedback(EnrollFeedback f) {
    switch (f) {
      case EnrollFeedback.starting:
        return 'Starting camera...';
      case EnrollFeedback.noFace:
        return 'No face found. Move into the picture.';
      case EnrollFeedback.multipleFaces:
        return 'Only one face please. Make sure nobody else is in view.';
      case EnrollFeedback.tooFar:
        return 'Move a bit closer.';
      case EnrollFeedback.tooClose:
        return 'Move a bit further away.';
      case EnrollFeedback.notCentered:
        return 'Move your face into the oval.';
      case EnrollFeedback.headRolled:
        return 'Keep your head upright.';
      case EnrollFeedback.notStraight:
        return 'Face the camera more directly.';
      case EnrollFeedback.moveMore:
        return 'Move a little more.';
      case EnrollFeedback.wrongDirection:
        return 'Other way: go to the opposite side of the last movement.';
      case EnrollFeedback.offAxis:
        return 'Move in one direction only: do not tilt while turning (or turn while nodding).';
      case EnrollFeedback.holdStill:
        return 'Good! Hold still...';
      case EnrollFeedback.ready:
        return 'Good!';
    }
  }

  static const enrollCaptured = 'Captured!';
  static const enrollSaving = 'Saving your encrypted profile...';
  static const enrollDark = 'Too dark. Move to a brighter place.';
  static const enrollBright = 'Too bright. Avoid strong light on your face.';
  static const enrollBlurry = 'Picture is blurry. Hold still.';
  static const enrollNotSame = 'That picture does not look like the first one. Only your face, please. Try again.';
  static const enrollBadSample = 'That picture could not be used. Try again.';
  static const enrollError = 'Something went wrong. Please try again.';
  static const enrollModelMissing = setupModelMissing;
  static const enrollNoPermission = 'Camera permission is needed to create the face profile.';
  static const enrollDetectFailed = 'Face detection keeps failing. Close this screen and open it again.';
  static const enrollSaveFailed =
      'The profile could not be saved safely. Your old profile (if any) was not changed. Press Restart to try again.';
  static String enrollMeter(int measured, int required) => 'Movement: $measured° of $required°';
  static const enrollSlowHint = 'Taking long? Move a little more, keep your whole face in the picture, and check the light.';
  static const enrollRelaxed = 'Easier mode: a smaller movement is accepted now.';
  static const enrollBtnRetry = 'Retry this step';
  static const enrollBtnRestart = 'Restart';
  static const enrollBtnManual = 'Capture now';
  static const enrollManualNote =
      'If the app cannot measure your head movement, you can take this picture by hand. It must still show your face clearly and look like the first picture.';
  static String enrollTime(int seconds) => 'Time: $seconds s';
  static String enrollPerf(int fps, int ms) => '$fps pictures/s, $ms ms each';

  // ---- Settings
  static const settingsTitle = 'Settings';
  static const settingsSensitivity = 'Sensitivity';
  static const sensLow = 'Low (fewer alerts)';
  static const sensMedium = 'Medium';
  static const sensHigh = 'High (more alerts)';
  static const settingsAdvanced = 'Advanced thresholds';
  static const settingsAdvancedNote = 'Choosing a sensitivity level above resets these numbers.';
  static String advMatch(String v) => 'Match threshold: $v (higher = stricter about who counts as you)';
  static String advUncertain(String margin, String stranger) =>
      'Uncertain zone: $margin wide. Below $stranger counts as a stranger.';
  static const settingsAlertUncertain = 'Also alert for faces I am not sure about';
  static String advAngle(int degrees) => 'Head angle limit: $degrees degrees';
  static String advFace(int percent) => 'Smallest face: $percent% of picture width';
  static String advFrames(int required, int window) => 'Alert when $required of the last $window pictures agree';
  static const settingsVibration = 'Vibrate on alert';
  static String settingsCooldown(int seconds) => 'Time between alerts: $seconds seconds';
  static const settingsCalibrate = 'Calibrate on this phone';
  static const settingsBattery = 'Battery optimization help';
  static const settingsDeleteProfile = 'Delete my face profile';
  static const deleteTitle = 'Delete face profile?';
  static const deleteBody =
      'This erases your encrypted face profile and its key from this phone, and stops protection. You can create a new profile later.';
  static const deleteConfirm = 'Delete';
  static const deleteDone = 'Face profile deleted.';

  // ---- About and Developer (bottom of Settings). The credit wording must stay exactly like this.
  static const aboutDeveloperTitle = 'About & Developer';
  static String aboutVersion(String v) => 'Version $v';
  static const aboutDeveloperSummary =
      'Watches the front camera and warns you when another person may be looking at your screen. Everything is processed on your phone.';
  static const developerCredit = 'Made by Nurbhanej Anvarali and Momin Kumail';
  static const developerRole = '2nd Year CE Student at LDCE, Ahmedabad';

  // ---- About and limits
  static const aboutTitle = 'About and limits';
  static const aboutParagraphs = [
    'People beside or behind the phone, or outside the camera view, cannot be detected.',
    'Eye gaze is not used. Eye tracking is not reliable on phone cameras, so the app only checks which way a head is turned. Someone facing the screen may not really be looking at it, and someone glancing sideways may be missed.',
    'The front camera can only be used by one app at a time. If you start a video call or open the camera, protection pauses and continues by itself when the camera is free.',
    'Protection pauses when the screen is off or the battery is low, and stops when you tap Stop. After the phone restarts, Android does not allow a camera service to start by itself. You get a notification: tap it to restart.',
    'Android shows a camera indicator (green dot) while the camera is used. This is a system rule and the app does not hide it.',
    'Privacy: no internet permission, no photos saved, nothing uploaded. Your face profile is a list of numbers, encrypted with a key that stays in Android Keystore.',
    'False alarms and missed alerts can happen, for example in dark rooms, with sunglasses or masks. Tune the sensitivity in Settings.',
    'No photo or video check (liveness). A photo or a video of the owner shown to the camera may be accepted as the owner. The app does not detect this.',
    'Recognition is not perfect. The match score depends on light and camera quality. When the app is not sure it says so and, by default, does not alert. You can calibrate on this phone in Settings.',
  ];

  // ---- Battery help
  static const batteryTitle = 'Battery optimization';
  static const batteryWhy =
      'Android saves battery by stopping apps that run in the background for a long time. That can stop Privacy Guard without warning. If you turn battery optimization off for this app, Android is less likely to stop it. The app then uses a little more battery.';
  static const batteryStatusOn = 'Battery optimization is ON for this app. Android may stop it.';
  static const batteryStatusOff = 'Battery optimization is OFF for this app. Good.';
  static const batteryBtnTurnOff = 'Turn off battery optimization';
  static const batteryBtnList = 'Open the battery optimization list';
  static const batteryBtnAppSettings = 'Open app settings';
  static const batteryOem =
      'Some phone brands (Xiaomi, Oppo, Vivo, Huawei, Samsung and others) add their own autostart or background-restriction switches. If protection keeps stopping, look for them in the app settings and allow them. Even then, nothing can promise uninterrupted monitoring.';

  // ---- Event log
  static const eventsTitle = 'Event log';
  static const eventsEmpty = 'No events yet.';
  static const btnClearLog = 'Clear log';
  static String event(EventType t) {
    switch (t) {
      case EventType.protectionStarted:
        return 'Protection started';
      case EventType.protectionStopped:
        return 'Protection stopped';
      case EventType.otherPersonAlert:
        return 'ALERT: other person (lowest match)';
      case EventType.uncertainAlert:
        return 'ALERT: uncertain face (lowest match)';
      case EventType.cameraBusy:
        return 'Camera in use by another app';
      case EventType.cameraResumed:
        return 'Camera back, protection active';
      case EventType.analysisFailing:
        return 'Face analysis is failing';
      case EventType.analysisRecovered:
        return 'Face analysis works again';
      case EventType.profileInvalid:
        return 'Face profile does not fit the model: please create it again';
      case EventType.enrollmentDone:
        return 'Face profile created (seconds it took)';
    }
  }

  // ---- Calibration
  static const calibTitle = 'Calibrate';
  static const calibIntro =
      'This measures how well the app recognises YOU on this phone, and (optional) how another person scores. Then it suggests the numbers. Step 1: look at the screen for about 10 seconds. Only you may be in view.';
  static const calibNeedProfile = 'Create your face profile first.';
  static const calibBtnOwner = 'Start: measure me';
  static const calibRecordingOwner = 'Measuring you. Look at the screen, move your head a little.';
  static const calibRecordingOther = 'Measuring the other person. Only they may be in view.';
  static const calibLook = 'Keep your face in view.';
  static const calibOnePerson = 'Exactly one person must be in view and looking at the screen.';
  static String calibSamples(int n, int total) => 'Pictures measured: $n of $total';
  static String calibLive(String v) => 'Match to your profile: $v';
  static const calibOtherPrompt =
      'Step 2 (recommended): hand the phone to someone else. They look at the screen for about 10 seconds. Without this step the app has to guess where strangers score.';
  static const calibBtnOther = 'Measure another person';
  static const calibBtnSkip = 'Skip';
  static const calibResultTitle = 'Result';
  static const calibQualityGood = 'Good: you and the other person are clearly apart on this phone.';
  static const calibQualityOverlap =
      'Warning: you and the other person score too close together. The app cannot tell you apart reliably here. Try better light, create the profile again, and keep the wide uncertain zone.';
  static const calibQualityOwnerOnly =
      'Only you were measured. The stranger side is an assumption, so the numbers are less safe. Measure another person for a better result.';
  static const calibQualityNoData = 'Not enough pictures were measured.';
  static String calibNumbers(String ownerLow, String otherHigh, String match, String stranger) =>
      'Your low end (5% of pictures): $ownerLow\nOther person high end (95% of pictures): $otherHigh\nSuggested: owner if match is $match or more, stranger if below $stranger.';
  static const calibBtnApply = 'Use these numbers';
  static const calibApplied = 'Applied';
  static const calibBtnAgain = 'Measure again';
  static const cameraFailed = 'The camera could not be started.';
}
