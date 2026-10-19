# AI Privacy Guard

An Android app (Kotlin, Jetpack Compose) that watches the **front camera** while you use your phone and alerts you when **another person may be looking at your screen**. Everything runs on the phone. There is no INTERNET permission.

> **Please read "What was and was not verified" first.** The app was written and its logic was tested, but it has **not been built into an APK or run on a phone yet**, because the machine it was written on cannot reach Google's servers.

---

## 1. What was and was not verified

The build environment used to write this app blocks `dl.google.com` (Google Maven and the Android SDK). So `./gradlew assembleDebug` **could not be run**. This is what I could and could not check:

| Part | Status |
|---|---|
| Pure logic: cosine matching, head-pose rule, 3-of-5 filter, cooldown, enrollment steps, image-quality checks, AES-GCM encrypt/decrypt | **Compiled with Kotlin 2.4.21 and covered by 33 unit tests. All pass.** (`app/src/test/.../LogicTest.kt`) |
| `AppSettings`, `ProfileStore` (Keystore AES-GCM), `FaceEmbedder` (TFLite) | **Compiled** against the real Android 15 framework stubs and the real TensorFlow Lite 2.16.1 classes. Not run. |
| The face model | **Run** with a TFLite interpreter on sample photos (see section 4). |
| Gradle files (AGP 9.4.0), Compose screens, CameraX service, ML Kit code, notifications | **Written carefully against the documented APIs but NOT compiled.** Expect that your first build in Android Studio may show a few small errors. Send them to me and I will fix them. |
| Anything on a real phone (camera conflict, screen off, reboot, battery) | **Not tested.** Use the checklist in section 9. |

To run the logic tests yourself, no phone needed: `./gradlew testDebugUnitTest`.

---

## 2. Build and run

1. Install **Android Studio** (a recent version; AGP 9.4 needs JDK 17+, which Android Studio includes).
2. Open this folder (`Privacy-App`) in Android Studio. Let Gradle sync. If it asks to install **Android SDK Platform 36** or Build Tools, accept.
3. **Download the face model** (next section). This step is required.
4. Connect your phone (USB debugging on), press **Run**.

Command line: `./gradlew assembleDebug` (APK in `app/build/outputs/apk/debug/`).

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
* `compileSdk = 36` (needed by the newest AndroidX), `targetSdk = 35` (Android 15, as you asked), `minSdk = 26`.

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

Active, Paused (camera busy), Paused (screen off), Paused (battery low), Stopped, Permission missing, Setup needed. Every 5 seconds a watchdog re-checks. If CameraX reports the camera as in use, **or no picture arrives for 10 seconds**, the status becomes "Protection paused: camera in use by another app" and the app unbinds and re-binds the camera every 5 seconds until pictures flow again.

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
           (pure Kotlin, no Android, unit tested)
  data/    AesGcmBox.kt  ProfileStore.kt  AppSettings.kt
  face/    FaceEmbedder.kt (TFLite)   FaceFinder.kt (ML Kit + picture helpers)
  service/ PrivacyGuardService.kt  Notifier.kt  BootReceiver.kt  GuardState.kt
  ui/      Theme.kt  Components.kt  OnboardingScreen.kt  HomeScreen.kt  EnrollmentScreen.kt
           SettingsScreen.kt  InfoScreens.kt (About + Battery help)
  util/    Permissions.kt
app/src/test/java/com/example/privacyguard/LogicTest.kt
```

---

## 7. Test each module on your phone

**Module 1 - Setup and manifest.** Build and install. The app opens. In phone Settings > Apps > AI Privacy Guard > Permissions you see Camera and Notifications, and no Internet/Network entry.

**Module 2 - Onboarding and settings.** First start shows 4 short pages. On the last page tap "Allow" for camera and notifications. Try "Don't allow" twice: the card changes to "Android will not ask again" with an "Open app settings" button. Open Settings in the app: change sensitivity (the sliders move), switch vibration, move the cooldown slider, close and reopen the app: values are kept. Open "Battery help": it shows ON/OFF, and "Turn off battery optimization" opens the system dialog.

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

* **First build.** The Gradle/AGP 9.4/Compose/CameraX/ML Kit parts were never compiled (see section 1).
* **Sign of head angles.** I could not check whether ML Kit's yaw/pitch signs are mirrored for the front camera, so enrollment accepts either direction for "left" and requires the opposite for "right" (same for up/down). Both sides are captured either way, but the words "left" and "right" on screen may be swapped.
* **Accurate mode.** I use ML Kit's ACCURATE mode because I was not sure the up/down angle is reliable in FAST mode. If battery use is too high, you can try FAST in `FaceFinder.kt` and check that "facing" still behaves.
* **Frame rate.** The camera still produces about 30 frames per second; the app throws away all but 2-3 per second right away. That saves most of the CPU work, not the camera sensor's own power. A lower hardware frame rate can be set via Camera2 interop, but phones differ, so I did not add it.
* **Threshold values** are starting points, tested on only a few sample photos.
* **Model licence.** The tool repository is MIT, but I did not verify the licence of the training data of the model weights. Fine for a personal app; check before publishing the app.
