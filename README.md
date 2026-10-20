# AI Privacy Guard

An Android app (Kotlin, Jetpack Compose) that watches the **front camera** while you use your phone and alerts you when **another person may be looking at your screen**. Everything runs on the phone. There is no INTERNET permission.

> **Status:** the app builds with `./gradlew assembleDebug` (AGP 9.4.0, Gradle 9.6.0, `compileSdk = 37`), was installed on a real Android 15 phone and the owner reported that it worked. See "What was and was not verified" for exactly what that does and does not cover.
>
> **Recognition update:** the recognition and alert logic was audited and improved (uncertain zone, calibration on your phone, enrollment verification, degraded-state detection, event log). Read `docs/RECOGNITION_REPORT.md` for the findings, the measured threshold numbers and an honest list of tests that were run and not run. **That update has not been built yet: build it and tell me about any compiler error.**

---

## 1. What was and was not verified

The code was written on a machine that could not reach Google's servers (`dl.google.com`), so it was first checked only partly. It was then built and run on the owner's own computer and phone. This is the honest picture:

| Part | Status |
|---|---|
| Pure logic: cosine matching, head-pose rule, 3-of-5 filter, cooldown, enrollment steps, image-quality checks, AES-GCM encrypt/decrypt | **65 unit tests, all pass** (`app/src/test/.../LogicTest.kt`, `RecognitionPolicyTest.kt`). |
| The whole app (Gradle files, Compose screens, CameraX service, ML Kit, TFLite) | **Builds successfully** on the owner's computer. The only build error found was `compileSdk = 36`, which the current AndroidX libraries reject; it is now `37`. |
| The face model | **Run** with a TFLite interpreter on sample photos (see section 4). |
| Running on a real phone | **Installed and tested by the owner on an Android 15 phone, who reported that everything worked.** The result of each single checklist item in section 9 was not written down, so treat section 9 as the list to re-check, especially camera conflict, phone restart, battery use over an hour and false alarms in your own daily use. |

Not checked by anyone yet (as far as this repository knows): behaviour on other phone brands (some kill background apps aggressively), phones with 16 KB memory pages (see section 3), and long-term battery use.

To run the logic tests yourself, no phone needed: `./gradlew testDebugUnitTest`.

---

## 2. Build and run

1. Install **Android Studio** (a recent version; AGP 9.4 needs JDK 17+, which Android Studio includes).
2. Open this folder (`Privacy-App`) in Android Studio. Let Gradle sync. If it asks to install **Android SDK Platform 37** or Build Tools, accept.
3. **Download the face model** (next section). This step is required.
4. Connect your phone (USB debugging on), press **Run**.

Command line: `./gradlew assembleDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`. The first build downloads libraries and the Android SDK Platform 37, so it takes several minutes.

**Windows tip:** if `gradlew` says `JAVA_HOME is set to an invalid directory`, point it to the Java that comes with Android Studio, for this PowerShell window only:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleDebug
```

(Old Java 11 is too old for this project; it needs Java 17 or newer.)

### Download the face model

The app needs one model file that is **not** stored in this repository (it is third-party, and I could not verify the licence of the data it was trained on, so I did not bundle it).

* **File:** `MobileFaceNet.tflite` (5,233,396 bytes, SHA-256 `d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`)
* **Link:** https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/MobileFaceNet.tflite
* **Put it here:** `app/src/main/assets/MobileFaceNet.tflite` (create the `assets` folder if it does not exist)

Easiest way, from the project folder:

* Mac / Linux / Git Bash: `bash scripts/download_model.sh`
* Windows PowerShell: `powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1`

The scripts download the file and check the SHA-256. Or download the link in your browser and copy the file by hand. Then rebuild.

If the file is missing the app still opens and shows "model file is missing" instead of crashing.

---

## 3. Versions used

Checked on 19 Oct 2026:

| Library | Version | Where I checked |
|---|---|---|
| Android Gradle Plugin | 9.4.0 (needs Gradle 9.6.0, JDK 17) | developer.android.com release notes |
| Gradle (wrapper) | 9.6.0 | AGP 9.4 compatibility table |
| Kotlin + Compose compiler plugin | 2.4.21 | Maven Central (latest stable) |
| Compose BOM | 2026.09.00 | developer.android.com BOM mapping |
| CameraX | 1.6.2 (latest stable; 1.7.0 is alpha) | developer.android.com |
| Lifecycle (`lifecycle-service`) | 2.11.0 | developer.android.com |
| Activity Compose / Core KTX | 1.13.0 / 1.19.1 | developer.android.com |
| ML Kit face detection | 16.1.7 | Google's ML Kit release notes, as shown in search results (the page itself was blocked) |
| TensorFlow Lite | 2.16.1 | Maven Central |

Notes you should know:

* **AGP 9 has Kotlin built in.** That is why there is no `kotlin-android` plugin in the Gradle files. AGP 9 uses Kotlin 2.2.10 by default; the top-level `build.gradle.kts` requests Kotlin 2.4.21 in a `buildscript` block, exactly as the AGP 9 release notes describe. If you change the Kotlin version, change it in **both** `build.gradle.kts` and `gradle/libs.versions.toml` (the Compose compiler plugin must match). If Android Studio complains about a version mix, use its "Upgrade" suggestion.
* **TensorFlow Lite / LiteRT.** `org.tensorflow:tensorflow-lite:2.17.0` is only a redirect to Google's new LiteRT library (`com.google.ai.edge.litert:litert:1.0.1`). I could not inspect LiteRT (Google Maven is blocked), so I used 2.16.1, the last release with a real file on Maven Central, and checked my code against its classes. Its native libraries are **4 KB page aligned**. This is fine on almost all Android 15 phones, but on a phone running with 16 KB memory pages the library may fail to load. If that ever happens, switch to LiteRT.
* `compileSdk = 37` (the newest AndroidX libraries require it; this was confirmed by a real build), `targetSdk = 35` (Android 15, as you asked), `minSdk = 26`.

---

## 4. The face model, in detail

I downloaded the model and ran it on my computer with a TFLite interpreter:

* **Input:** `float32 [2, 112, 112, 3]`, RGB, each value `(pixel - 127.5) / 128`. The batch size of **2 is fixed inside this model**, so the app puts the same face in both slots and uses the first result. (I checked: the second slot does not change the first result.)
* **Output:** `float32 [2, 192]`, the app uses the first row (192 numbers) and scales it to length 1.
* **Sanity test** on 5 public photos: same person in different photos gave cosine similarity **0.76 to 0.94**; different people gave **-0.19 to 0.16**. A mirrored photo scored 0.98 and a darkened one 0.98. So a match threshold around 0.35 to 0.55 is reasonable. These were only a handful of photos, so please tune it on your own face (section 8).

---

## 5. How the app works

```
CameraX frame (about 2-3 per second, the rest dropped)
  -> upright Bitmap (memory only)
  -> ML Kit: find faces + head angles + size
  -> for each face that is big enough AND turned towards the screen (head angle limit):
         MobileFaceNet -> 192 numbers -> cosine similarity with YOUR profile
  -> face below the match threshold = "other person looking"
  -> temporal filter (for example 3 of the last 5 pictures)
  -> cooldown (default 30 s)
  -> high-priority notification "Privacy Alert: Someone may be looking at your screen." + vibration
  -> Bitmap is recycled. Nothing is saved.
```

* **Eye gaze is NOT used**, on purpose. See `GazeEstimator` in `logic/OtherPersonDetector.kt`: it is the one place to plug in gaze later. It always answers "unknown" now.
* A visible face is not treated as "looking": the head must be inside the head-angle limit (yaw and pitch).
* Embeddings are only calculated for faces that face the screen, and for at most 3 faces per picture, to save battery.

### Status the screen shows (the real one)

Active, Paused (camera busy), Paused (screen off), Paused (battery low), **Protection degraded** (face analysis keeps failing), Stopped, Permission missing, Setup needed.

While Active, the main screen also shows what the last picture contained: **Owner detected**, **Other person detected**, **Owner and another person detected**, **Not sure who this is**, **Face seen but nobody looking at the screen**, or **No face in view**, with the best match number. Every face gets one of three verdicts: owner (match >= threshold), uncertain (in the uncertain zone below it, never claimed as owner or stranger, no alert by default) or stranger (below `match - uncertain zone`).

**Settings > Calibrate on this phone** measures your own scores (about 10 seconds) and optionally another person's, and suggests the match threshold from those numbers. **Event log** shows alerts, camera busy and errors with times and match numbers (no pictures). Every 5 seconds a watchdog re-checks. If CameraX reports the camera as in use, **or no picture arrives for 10 seconds**, the status becomes "Protection paused: camera in use by another app" and the app unbinds and re-binds the camera every 5 seconds until pictures flow again.

### Face profile storage

`ProfileStore`: the averaged 192 numbers are encrypted with **AES-256-GCM**; the key is created in the **Android Keystore** and never leaves it. File: `files/face_profile.enc`. "Delete my face profile" overwrites the file with zeros, deletes it, and deletes the Keystore key. Face photos are never saved. `android:allowBackup="false"` so the file is not copied into cloud backups.

---

## 6. File list

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
gradlew, gradlew.bat, gradle/wrapper/*            Gradle 9.6.0 wrapper
scripts/download_model.sh, download_model.ps1     downloads + checks the face model
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/                                 strings.xml, themes, launcher icon, notification icon
app/src/main/java/com/example/privacyguard/
  MainActivity.kt                                 switches between screens, handles "Tap to restart"
  logic/   Thresholds.kt  FaceMath.kt  OtherPersonDetector.kt  TemporalFilter.kt  EnrollmentSession.kt
           Verification.kt (enrollment check, threshold calibrator, failure monitor, event log format)
           (pure Kotlin, no Android, unit tested)
  data/    AesGcmBox.kt  ProfileStore.kt  AppSettings.kt  EventLogStore.kt
  face/    FaceEmbedder.kt (TFLite)   FaceFinder.kt (ML Kit + picture helpers)
  service/ PrivacyGuardService.kt  Notifier.kt  BootReceiver.kt  GuardState.kt
  ui/      Theme.kt  Components.kt  OnboardingScreen.kt  HomeScreen.kt  EnrollmentScreen.kt
           CalibrationScreen.kt  SettingsScreen.kt  InfoScreens.kt (About + Battery help + Event log)
  util/    Permissions.kt
app/src/test/java/com/example/privacyguard/LogicTest.kt, RecognitionPolicyTest.kt, DeveloperCreditTest.kt
docs/RECOGNITION_REPORT.md                        audit, measured thresholds, test status
tools/threshold_experiment/                       scripts that produced the threshold numbers
```

---

## 7. Test each module on your phone

**Module 1 - Setup and manifest.** Build and install. The app opens. In phone Settings > Apps > AI Privacy Guard > Permissions you see Camera and Notifications, and no Internet/Network entry.

**Module 2 - Onboarding and settings.** First start shows 4 short pages. On the last page tap "Allow" for camera and notifications. Try "Don't allow" twice: the card changes to "Android will not ask again" with an "Open app settings" button. Open Settings in the app: change sensitivity (the sliders move), switch vibration, move the cooldown slider, close and reopen the app: values are kept. The bottom of Settings has an "About & Developer" block with the app version and the developer credit. Open "Battery help": it shows ON/OFF, and "Turn off battery optimization" opens the system dialog.

**Module 3 - Face enrollment.** Make sure the model is downloaded. Main screen > "Create my face profile". Follow the 5 steps; each picture is taken by itself after you hold still. Try covering the camera or putting a second face in view: you get a message and no picture. At the end the main screen says "Face profile: saved". Then Settings > "Delete my face profile" > Delete: the main screen says "not created yet" again.

**Module 4 - Detection and matching.** Create a profile, tap Start. On the main screen "Last checked picture" shows e.g. `faces 1, facing 1, best match 0.82`. Look at the phone: best match should be well above 0.45. Ask a friend to look at the camera: their best match should be below 0.3.

**Module 5 - Foreground service.** Tap "Start Protection": a notification with a Stop button appears and the camera indicator (green dot) shows. Status says "Active". Press Home, open WhatsApp, pull down the notification, it is still there. Tap Stop in the notification: status is "Stopped".

**Module 6 - Head pose and alerts.** With protection on, have a friend stand behind your shoulder and look at the screen for about 2 seconds: you get the alert and a vibration. If they look away (head turned more than the angle limit), there should be no alert. Check the 30 second cooldown.

**Module 7 - Tuning.** Section 8.

---

## 8. Tuning

Everything is in Settings, or in `Thresholds.forSensitivity` for the presets.

| Problem | Try |
|---|---|
| Alerts for you (your own face is seen as "other") | Lower the **match threshold** (e.g. 0.35). Re-create the profile in good light. Check "best match" while you look at the phone. |
| Friends are not detected as "other" | Raise the match threshold. |
| Alerts when someone is far away or just passing | Raise the **minimum face size**. |
| Missed alerts when someone is at an angle | Raise the **head angle limit**. |
| Too many one-off alerts | Use Low sensitivity (4 of 5 pictures), or increase the cooldown. |

---

## 9. Final test checklist

- [ ] **Camera conflict:** protection Active. Start a WhatsApp video call (or open the Camera app). Within about 5-10 seconds the status must say "Protection paused: camera in use by another app". End the call: within about 5-10 seconds the status must return to "Active" by itself. (If it does not, tell me which phone brand.)
- [ ] **Screen off / on:** lock the phone, wait 30 s, unlock. Status "Paused: screen is off" while locked, "Active" after unlocking, camera indicator gone while locked.
- [ ] **Phone restart:** with protection on, restart the phone. After unlocking you must see the notification "Tap to restart Privacy Guard" (and the camera must NOT start by itself). Tap it: the app opens and starts protection.
- [ ] **Battery optimization ON:** leave it ON, run protection for 30 minutes with the screen on and the phone in another app. Note whether it keeps running. Then turn it OFF in the Battery help screen and repeat.
- [ ] **Permissions:** Start, then remove the camera permission in system settings. The app is stopped by Android; reopening shows "Permission missing".
- [ ] **Low battery:** below 15 percent and not charging the status must show "Paused: battery is low"; plugging in the charger resumes it.
- [ ] **Landscape games:** hold the phone sideways in a game; with only you in front, there must be no alerts, and a friend looking in should still be detected.
- [ ] **False positives (most important):** use the phone for 30 minutes normally: bed, desk, walking, in dim light. Count the alerts you did not want. Try glasses, a hat, bad light. If there are too many, tune (section 8).
- [ ] **Missed detections:** friend behind you, friend beside you (not detectable: see below), friend across a table.
- [ ] **Battery use after 1 hour:** note the battery % before; run protection for 1 hour with normal use; then check Settings > Battery > AI Privacy Guard. Tell me the number. Expect noticeable use, because the camera and ML run continuously.
- [ ] **Calibration:** Settings > Calibrate on this phone. Measure yourself, then a friend. Write down the result (good / overlap) and apply it. If it says "overlap", improve the light or create the profile again, and do not trust the alerts.
- [ ] **Owner never flagged:** with the default and with the calibrated numbers, use the phone normally for 30 minutes. The Event log must show no "ALERT: other person" caused by you. Check the "Best match" number on the main screen: it should stay clearly above the owner threshold.
- [ ] **Other person flagged:** a friend looks at the screen from the side (head turned less than the head angle limit) for several seconds: "Other person detected" and an alert. Repeat with you and the friend in the picture together.
- [ ] **No internet:** run the `aapt2 dump permissions` command from `docs/RECOGNITION_REPORT.md` section 6. `INTERNET` must not appear.
- [ ] **Photo test (expected to FAIL):** show a photo of yourself on another phone to the camera. The app has no liveness check and may treat it as you. Note what happens.
- [ ] **Delete profile:** Settings > Delete. Try Start Protection: it must refuse until you create a new profile.
- [ ] **Privacy:** Settings > Apps > AI Privacy Guard shows no internet/network permission. Open the phone's file manager or `adb shell run-as com.example.privacyguard ls files`: only `face_profile.enc` exists, no images.

---

## 10. What Android 15 does NOT allow (and what I did instead)

| You asked for | Reality on Android 15 | What the app does |
|---|---|---|
| Camera service starts by itself after a restart | Not allowed (camera foreground services cannot start from `BOOT_COMPLETED`) | Boot receiver only shows "Tap to restart Privacy Guard". Tapping opens the app, which starts the service. |
| Restart automatically if Android kills the service | Android 14/15 refuses to start a camera service from the background | The service shows "Tap to restart" instead. (If the whole process is killed with no callback, nothing can be shown. Check the status when you next open the app.) |
| Keep the camera when another app needs it | The app in front has priority; the camera is taken away | Status "paused: camera in use by another app"; retries every 5 s and resumes by itself. |
| Hide the camera indicator | Not possible and not attempted | The green dot always shows while the camera is used. |
| Uninterrupted monitoring | No app can promise this; phone makers often kill background apps | The status shows the real state. Battery help screen explains the settings that help. |
| Eye gaze | Not reliable on phone cameras | Not used. Head direction only. A placeholder (`GazeEstimator`) exists. |
| Detect people beside / behind the phone | The camera cannot see them | Explained in the About screen. |

## 11. Things I am not sure about

* **Test coverage.** The app builds and was reported working on one phone, but only the pure logic has automatic tests. Camera, service and alert behaviour have been checked by hand only, on one phone (see section 1).
* **Sign of head angles.** I could not check whether ML Kit's yaw/pitch signs are mirrored for the front camera, so enrollment accepts either direction for "left" and requires the opposite for "right" (same for up/down). Both sides are captured either way, but the words "left" and "right" on screen may be swapped.
* **Accurate mode.** I use ML Kit's ACCURATE mode because I was not sure the up/down angle is reliable in FAST mode. If battery use is too high, you can try FAST in `FaceFinder.kt` and check that "facing" still behaves.
* **Frame rate.** The camera still produces about 30 frames per second; the app throws away all but 2-3 per second right away. That saves most of the CPU work, not the camera sensor's own power. A lower hardware frame rate can be set via Camera2 interop, but phones differ, so I did not add it.
* **Threshold values** are starting points, tested on only a few sample photos.
* **Model licence.** The tool repository is MIT, but I did not verify the licence of the training data of the model weights. Fine for a personal app; check before publishing the app.
