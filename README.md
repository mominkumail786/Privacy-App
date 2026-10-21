# AI Privacy Guard (Flutter)

An Android app that watches the **front camera** while you use your phone and alerts you when **another person may be looking at your screen**. Everything runs on the phone. There is no INTERNET permission in release and profile builds.

The app is now a **Flutter / Dart** project. The screens and all the decision logic (settings, enrollment steps, calibration, event log, About & Developer credit) are Dart. The background camera service, camera, face detection, face model and encrypted storage stay in Kotlin, because Flutter cannot run a camera in the background on Android 14/15. Read `docs/FLUTTER_MIGRATION.md` for what lives where and why.

> **Status, honestly:** on GitHub's build machine (GitHub Actions, `.github/workflows/flutter.yml`, commit `51348b1`) `flutter analyze`, the **128 Dart tests**, the **Kotlin unit tests** and **`flutter build apk --debug`** all succeeded **with both the newest stable Flutter and Flutter 3.32.5**, so the project **compiles into an APK** including the new Kotlin bridge. **Nothing has been run on a phone yet**: the camera preview inside Flutter and enrollment on a real face are untested. The last version that was tested on a phone is the native Kotlin/Compose app at commit `1370dc4`.

---

## 1. What was and was not verified

| Part | Status |
|---|---|
| Dart logic (thresholds, matching, enrollment state machine, calibrator, controllers, all screens) | **128 tests pass** (`flutter test`), including 25 simulated-user enrollment tests, the exact developer credit text and small-screen layout. `flutter analyze`: no issues. |
| Dart presets equal the Kotlin presets | A test reads `Thresholds.kt` and compares the numbers. |
| Kotlin logic (matching, filter, cooldown, image quality, AES-GCM, safe file save) | **53 unit tests pass** in a plain JVM project (`android/app/src/test/kotlin/...`). |
| Kotlin bridge (`PrivacyGuardPlugin`, `FaceSession`, `CameraPreviewFactory`, `MainActivity`) | **Compiles** against the real Flutter and CameraX libraries (it is part of the APK built on GitHub Actions). Behaviour is untested. |
| `flutter build apk --debug`, Gradle, AGP 9, `permission_handler` | **Succeeded on GitHub Actions** (Ubuntu, JDK 21) with Flutter stable (21 Oct 2026) and with Flutter 3.32.5. Not yet on your Windows PC. The Kotlin unit tests also ran there and passed. |
| Camera preview inside Flutter (`AndroidView` + CameraX `PreviewView`), camera session, enrollment on a real face | **Not run on any phone.** |
| Foreground service, notifications, boot "Tap to restart", battery, ML Kit angles | Unchanged Kotlin code. It was installed and tested by the owner as the native app (commit `1370dc4`). Not re-tested after the move. |
| Face model | Run with a TFLite interpreter on sample photos (section 4). |

Not checked by anyone yet: other phone brands, phones with 16 KB memory pages (section 3), long-term battery use.

---

## 2. Build and run (Windows, step by step)

You need:

* **Flutter SDK 3.27 or newer** (`flutter --version`). Built successfully with Flutter 3.32.5 and with the newest stable. Dart 3.6 comes with it.
* **Android Studio** with **Android SDK Platform 37** and Build Tools. (Open the project once in Android Studio, or use SDK Manager.)
* Java 17 or newer. The one in Android Studio is fine.

```powershell
git clone https://github.com/mominkumail786/privacy-app.git      # or unzip the downloaded zip
cd privacy-app
git checkout claude/youthful-ramanujan-4t2puk

flutter config --jdk-dir "C:\Program Files\Android\Android Studio\jbr"   # once; fixes "invalid JAVA_HOME"
flutter doctor --android-licenses                                         # once; press y
flutter pub get

powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1       # face model, see below
flutter build apk --debug
```

The APK is `build\app\outputs\flutter-apk\app-debug.apk`. To install on a connected phone with USB debugging: `flutter run`.

If Flutter says it cannot find the Android SDK: `flutter config --android-sdk "C:\Users\USER\AppData\Local\Android\Sdk"`.

### Debug and release builds

Flutter adds the `INTERNET` permission to **debug** builds (hot reload needs it). `android/app/src/main/AndroidManifest.xml` removes it for **release** and **profile** builds (`tools:node="remove"`). For the version you really use, build `flutter build apk --release` (it is signed with the debug key unless you add your own signing; that is fine for personal use). Check the result with the command in section 9.

### Download the face model

The app needs one model file that is **not** stored in this repository (it is third-party, and I could not verify the licence of the data it was trained on).

* **File:** `MobileFaceNet.tflite` (5,233,396 bytes, SHA-256 `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`)
* **Link:** https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/MobileFaceNet.tflite
* **Put it here:** `android/app/src/main/assets/MobileFaceNet.tflite` (create the `assets` folder if needed)

Easiest: `bash scripts/download_model.sh` (Mac/Linux/Git Bash) or `powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1` (Windows). Both check the SHA-256. Then rebuild. If the file is missing, the app still opens and says "model file is missing".

### Build on GitHub instead

`.github/workflows/flutter.yml` runs analyze, the Dart tests, the Kotlin tests and builds a debug APK on a GitHub machine that has the Android SDK. The result (green or red) is the first independent answer to "does it build?". The APK is attached to the run as an artifact.

---

## 3. Versions used

| Part | Version |
|---|---|
| Android build tools | **Gradle 8.14.3, AGP 8.11.1, Kotlin 2.2.20** (`android/settings.gradle.kts`, `android/gradle/wrapper/gradle-wrapper.properties`). Chosen so that old (3.32) and new Flutter both work, see `docs/FLUTTER_MIGRATION.md`, "Toolchain". |
| Dart packages | `permission_handler ^12.0.1` (only to ask for camera/notification permission) |
| CameraX | 1.4.2 |
| ML Kit face detection | 16.1.7 |
| TensorFlow Lite | 2.16.1 |
| AndroidX core / lifecycle service | 1.16.0 / 2.9.2 |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 35 (Android 15) / 26 |

* **TensorFlow Lite / LiteRT.** `org.tensorflow:tensorflow-lite:2.17.0` is only a redirect to Google's new LiteRT library. I used 2.16.1, the last release with a real file on Maven Central. Its native libraries are **4 KB page aligned**: fine on almost all Android 15 phones, but on a phone running with 16 KB memory pages the library may fail to load. Then switch to LiteRT.
* **Older libraries on purpose.** The newest AndroidX libraries (core 1.19, CameraX 1.6) need AGP 9.1, and `permission_handler` 13+ needs AGP 9 too, but Flutter 3.32's own Gradle plugin does not compile under Gradle 9. So the project uses CameraX 1.4.2 / core 1.16 / `permission_handler` 12 (found by CI). The native Kotlin/Compose app tested on a phone (commit `1370dc4`) used CameraX 1.6.2, so camera behaviour with 1.4.2 is not yet checked on a phone.

---

## 4. The face model, in detail

I ran the model on my computer with a TFLite interpreter:

* **Input:** `float32 [2, 112, 112, 3]`, RGB, each value `(pixel - 127.5) / 128`. The batch size of **2 is fixed inside this model**, so the app puts the same face in both slots and uses the first result.
* **Output:** `float32 [2, 192]`; the app uses the first row (192 numbers) scaled to length 1.
* **Sanity test** on 5 public photos: same person **0.76 to 0.94**, different people **-0.19 to 0.16**. See `docs/RECOGNITION_REPORT.md` for the larger measurement and the three sensitivity presets.

---

## 5. How the app works

```
Flutter screens (Dart)  <--MethodChannel / EventChannel-->  Kotlin
                                                              |
 protection:   PrivacyGuardService (foreground service, type camera)
               CameraX frame (2-3 per second) -> ML Kit faces + head angles
               -> MobileFaceNet 192 numbers -> cosine similarity with YOUR profile
               -> owner / uncertain / stranger -> 3-of-5 filter -> cooldown -> notification + vibration

 enrollment:   FaceSession (CameraX + ML Kit)  --one small message per picture-->  Dart EnrollmentMachine
 calibration:  (head angles, size, position)       Dart decides the next step; when it says "capture",
                                                   Kotlin turns THAT picture into 192 numbers (never a photo)
```

* **Eye gaze is NOT used**, on purpose. Only the head direction is checked.
* Only numbers cross from Kotlin to Dart: head angles, sizes, face vectors, flags. **Pictures never leave Kotlin** (the live preview is a native view).
* One picture is analysed at a time; newer pictures are dropped (no queue of old pictures) on both sides.

### Status the screen shows (the real one)

Active, Paused (camera busy), Paused (screen off), Paused (battery low), **Protection degraded** (face analysis keeps failing), Stopped, Permission missing. While Active it also shows what the last picture contained (owner, other person, owner and another person, not sure, face seen but nobody looking, no face) with the best match number. The status is re-read every 2 seconds from the native service, so it is never more optimistic than the truth.

### Face profile storage

The averaged 192 numbers are encrypted with **AES-256-GCM**; the key is created in the **Android Keystore**. The file is written to a temporary name, read back and decrypted, compared, and only then moved over the old one. "Delete my face profile" overwrites the file with zeros, deletes it and deletes the Keystore key. Photos are never saved. `allowBackup="false"`.

---

## 6. File list

```
pubspec.yaml, analysis_options.yaml
lib/
  main.dart, app.dart, strings.dart              start, theme, every user-visible text
  logic/        thresholds, face_math, other_person_detector, enrollment_machine, enrollment_check,
                threshold_calibrator, frame_rate_meter                 (pure Dart, unit tested)
  models/       guard_status (snapshot), guard_event, app_settings, frame_event
  bridge/       native_bridge.dart (interface), method_channel_bridge.dart (real)
  services/     permission_service.dart, services.dart (what the screens may use)
  controllers/  app, settings, event_log, enrollment, calibration
  screens/      onboarding, home, enrollment, calibration, settings (+ About & Developer), about, battery_help, event_log
  widgets/      screen_scaffold, status_card, permission_card, camera_preview (native view + oval)
test/           logic/, controllers/, screens/, helpers/ (fake native side, simulated user)
android/app/src/main/
  AndroidManifest.xml, res/ (notification strings, icons)
  assets/MobileFaceNet.tflite                    (you download it)
  kotlin/com/example/privacyguard/
    MainActivity.kt                              registers the bridge, "Tap to restart" flag
    bridge/   PrivacyGuardPlugin.kt  FaceSession.kt  CameraPreviewFactory.kt
    service/  PrivacyGuardService.kt  Notifier.kt  BootReceiver.kt  GuardState.kt
    data/     AesGcmBox.kt  ProfileStore.kt  SafeFile.kt  AppSettings.kt  EventLogStore.kt
    face/     FaceEmbedder.kt (TFLite)  FaceFinder.kt (ML Kit + picture helpers)
    logic/    Thresholds, FaceMath, OtherPersonDetector, TemporalFilter, ImageQuality, Events
    util/     Permissions.kt
android/app/src/test/kotlin/...                  Kotlin unit tests (53)
docs/         FLUTTER_MIGRATION.md  RECOGNITION_REPORT.md  ENROLLMENT_REPORT.md
tools/threshold_experiment/                      scripts that produced the threshold numbers
scripts/download_model.sh, download_model.ps1
.github/workflows/flutter.yml                    CI
```

---

## 7. Test each part on your phone

**Setup and manifest.** Build and install. In phone Settings > Apps > AI Privacy Guard > Permissions you see Camera and Notifications. (Release build: no Internet entry.)

**Onboarding and settings.** First start shows 4 short pages. On the last page tap "Allow" for camera and notifications. Deny twice: the card changes to "Android will not ask again" with an "Open app settings" button. In Settings change sensitivity, vibration and cooldown, close and reopen: values are kept. The very bottom of Settings shows "About & Developer", the version and the credit "Made by Nurbhanej Anvarali and Momin Kumail / 2nd Year CE Student at LDCE, Ahmedabad".

**Face enrollment (new Flutter screen, never run on a phone).** Main screen > "Create my face profile". The camera picture must appear (if the box stays black, tell me: that is the platform-view part). Follow the 5 steps. The screen shows the time, pictures per second and milliseconds per picture: **please send me those numbers**. If a step is slow, hints, an easier mode and a "Capture now" button appear.

**Detection.** Create a profile, tap Start. The status card shows what the last picture contained and the best match. Look at the phone: best match should be clearly above the match threshold. A friend looking at the camera should be clearly below it.

**Service.** Start: notification with a Stop button, camera indicator, status "Active". Press Home, open WhatsApp: it keeps running.

**Alerts.** A friend looks at the screen from behind your shoulder for about 2 seconds: alert and vibration. 30 s cooldown.

---

## 8. Tuning

| Problem | Try |
|---|---|
| Alerts for you | Settings > Calibrate on this phone. Or lower the **match threshold**. Re-create the profile in good light. |
| Friends are not detected | Raise the match threshold, or calibrate with the friend. |
| Alerts when someone far away passes | Raise the **smallest face**. |
| Missed alerts when someone is at an angle | Raise the **head angle limit**. |
| Too many one-off alerts | Low sensitivity (4 of 5 pictures) or a longer cooldown. |

---

## 9. Final test checklist

- [x] **Build:** `flutter build apk --debug` succeeded on GitHub Actions. Repeat on your PC.
- [ ] **Camera preview in Flutter:** enrollment shows the live front camera in the box.
- [ ] **Enrollment:** completes; note seconds, pictures/s and ms. Restart the app: "Face profile: saved".
- [ ] **Camera conflict:** protection Active, start a video call: "Protection paused: camera in use by another app" within 5-10 s, back to "Active" afterwards.
- [ ] **Screen off / on, phone restart ("Tap to restart" notification), low battery (< 15 %)**: as before.
- [ ] **Battery optimisation:** the Battery help screen shows ON/OFF and the system dialog opens.
- [ ] **False positives (most important):** 30 minutes of normal use, count unwanted alerts. Check the event log.
- [ ] **Other person flagged:** a friend looks from the side for several seconds: alert.
- [ ] **No internet (release build only):** `aapt2 dump permissions build\app\outputs\flutter-apk\app-release.apk` (from Android SDK build-tools) must not list `INTERNET`.
- [ ] **Photo test (expected to FAIL):** a photo of yourself on another phone may be accepted as you (no liveness check).
- [ ] **Delete profile:** Settings > Delete; Start must refuse until a new profile exists.
- [ ] **Privacy:** `adb shell run-as com.example.privacyguard ls files`: only `face_profile.enc` and `events.log`, no images (debug build).

---

## 10. What Android 15 does NOT allow (and what I did instead)

| You asked for | Reality on Android 15 | What the app does |
|---|---|---|
| Camera service starts by itself after a restart | Not allowed (camera foreground services cannot start from `BOOT_COMPLETED`) | Boot receiver only shows "Tap to restart Privacy Guard". |
| Restart automatically if Android kills the service | Android 14/15 refuses to start a camera service from the background | "Tap to restart" notification. |
| Keep the camera when another app needs it | The app in front has priority | Status "paused: camera in use by another app"; retries every 5 s. |
| Hide the camera indicator | Not possible and not attempted | The green dot always shows. |
| Uninterrupted monitoring | No app can promise this | The status shows the real state; Battery help explains the settings that help. |
| Eye gaze | Not reliable on phone cameras | Not used. Head direction only. |
| **Run the camera in the background from Dart only** | Flutter code does not run reliably when the app is not visible | The service is Kotlin; Dart is only the UI. |
| Detect people beside / behind the phone | The camera cannot see them | Explained in the About screen. |

## 11. Things I am not sure about

* **The camera inside a Flutter platform view** (`PreviewView` in COMPATIBLE/TextureView mode) is untested on a phone. It compiles, but a black preview box is the most likely first problem. The build on your own PC may also differ from GitHub's (Flutter or Android SDK version).
* **Sign of head angles.** I could not check whether ML Kit's yaw/pitch signs are mirrored for the front camera, so enrollment accepts either direction for "left" and requires the opposite for "right" (same for up/down). The words "left"/"right" on screen may be swapped.
* **Enrollment thresholds** (12 degrees turn, 8 degrees nod) come from reasoning and simulation, not from measurements on a phone.
* **Accurate mode** of ML Kit is used for reliable angles; FAST mode may save battery.
* **Model licence.** I did not verify the licence of the training data of the model weights. Fine for a personal app; check before publishing.
* **No liveness check:** a photo or video of the owner can be accepted as the owner.
