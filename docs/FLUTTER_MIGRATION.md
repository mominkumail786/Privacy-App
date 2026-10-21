# Moving the project to Flutter / Dart

## What you asked for and what was possible

You asked to move the whole project to Flutter and Dart. **Everything that can be Dart is Dart.** One part cannot:

> The protection has to keep watching the camera while you use WhatsApp, games or while the screen is locked. On Android 14 and 15 that is only allowed from a **foreground service of type `camera`**. Flutter/Dart code runs inside the app's UI engine and cannot be the service. A Flutter plugin for the camera also only works while the app is visible.

So the app is a **hybrid**: Dart for everything you see and decide, Kotlin for the four things Android forces to be native.

## What is Dart now

| Area | Dart file(s) | Tests |
|---|---|---|
| Sensitivity presets, thresholds, the 3 verdicts (owner / not sure / stranger) | `lib/logic/thresholds.dart`, `other_person_detector.dart`, `face_math.dart` | `test/logic/thresholds_test.dart` (also compares the numbers with the Kotlin file), `face_math_and_detector_test.dart` |
| **Enrollment**: five-step state machine (neutral pose, relative angles, hysteresis, hold time, hint / easier mode / manual capture, pause gap) | `lib/logic/enrollment_machine.dart` | `test/logic/enrollment_machine_test.dart` (24 tests with a simulated user, both angle-sign conventions) |
| Enrollment sample checks and final profile check | `lib/logic/enrollment_check.dart` | `enrollment_check_and_calibrator_test.dart` |
| **Calibration** (suggests thresholds from measured scores) | `lib/logic/threshold_calibrator.dart`, `lib/controllers/calibration_controller.dart` | same + `test/controllers/` |
| Enrollment flow (drop stale pictures, one at a time, reject bad samples, safe save) | `lib/controllers/enrollment_controller.dart` | `test/controllers/enrollment_controller_test.dart` (14) |
| Settings, event log, home status, "Tap to restart" | `lib/controllers/*.dart` | `test/controllers/other_controllers_test.dart` |
| All screens, the About & Developer credit at the very bottom of Settings | `lib/screens/*.dart`, `lib/widgets/*.dart`, `lib/strings.dart` | `test/screens/screens_test.dart` (credit wording and position, small screens, dark mode, permissions flow) |

## What stays Kotlin, and why

| Kotlin | Why it cannot be Dart |
|---|---|
| `service/PrivacyGuardService.kt`, `Notifier.kt`, `BootReceiver.kt`, `GuardState.kt` | Android requires a native foreground service for background camera use, the boot receiver and the notifications. |
| `face/FaceFinder.kt` (ML Kit), `face/FaceEmbedder.kt` (TensorFlow Lite) | They need the camera frames in native memory; sending every picture to Dart would be slow and would move pictures across a bridge. |
| `data/ProfileStore.kt`, `AesGcmBox.kt`, `SafeFile.kt` (Android Keystore) | The key must live in the Keystore. |
| `logic/*.kt` | The **service** needs the thresholds, the temporal filter, the cooldown and the verdict rules while the app is closed, so these exist twice. The presets are checked against each other by a test. |
| `bridge/*.kt` (new) | Connects Dart to the above. |

## The bridge

* `MethodChannel "privacyguard/control"`: `getSnapshot`, `startProtection`, `stopProtection`, `consumeAutoStart`, `getSettings`, `updateSettings`, `applySensitivity`, `getEvents`, `clearEvents`, `addEvent`, `deleteProfile`, `saveProfile`, `requestIgnoreBatteryOptimizations`, `openBatteryList`, `appVersion`, `startCamera`, `stopCamera`, `captureSample`.
* `EventChannel "privacyguard/camera"`: one small map per analysed picture: `frameId`, time, number of faces, head angles, face size and position (only if exactly one face), and in calibration mode whether that face looks at the screen and its similarity to your profile. **No pixels.**
* Platform view `"privacyguard/camera_preview"`: the live camera picture is a native `PreviewView` (TextureView mode) shown inside Flutter.
* Dart code only knows the `NativeBridge` interface (`lib/bridge/native_bridge.dart`). The tests use an in-memory fake, so every screen and controller is tested without a phone.

How enrollment works across the bridge: Kotlin sends the measurement of each picture; the Dart `EnrollmentMachine` decides; when it says "capture", Dart calls `captureSample(frameId)`; Kotlin checks brightness / sharpness of **that** picture, makes the 192 numbers and returns them; Dart checks them against the first sample and, after five, averages them and calls `saveProfile`. Kotlin saves encrypted, reads back, decrypts and compares before answering "true".

## Things that behave differently from the native app

* Navigation is Flutter's standard stack (Back goes to the previous screen).
* The camera in enrollment and calibration is released when the app goes to the background and started again on return.
* Permissions are requested with the `permission_handler` plugin. Whether they are granted is always read from the native side.
* The About screen text and all other strings are unchanged (`lib/strings.dart`).

## Verification status

Dart: `flutter analyze` clean, 128 tests pass. Kotlin pure logic: 53 tests pass. Kotlin bridge: compiled only against hand-written stand-ins. **Not done: a real Gradle build, running on a phone.** See README section 1.

## Last native version

The native Kotlin/Compose app, as built and tested on a phone, is commit `1370dc4` on branch `claude/youthful-ramanujan-4t2puk`.
