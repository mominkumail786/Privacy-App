# Recognition and alert audit: report

Scope: "recognise the enrolled owner, alert only for a different person". This report separates what was
**measured**, what was **changed in code**, and what is **still unverified** because it needs the phone.

## 1. What the project really is

* **Kotlin + Jetpack Compose Android app**, not Flutter/Dart (there is no `pubspec.yaml`). All work below is in the existing Kotlin code.
* It already had a **real recognition pipeline**, not just detection:
  `CameraX frame -> ML Kit face detection (box + head angles) -> square crop -> MobileFaceNet (TFLite, 192 numbers) -> cosine similarity with the encrypted owner profile -> threshold -> N-of-M filter -> cooldown -> notification`.
* Detection alone is never used as identity: a face counts as "owner" only through the similarity score.

## 2. Findings from tracing the data flow

| # | Finding | Why it matters | Status |
|---|---|---|---|
| D1 | `handleFaces` swallowed every exception (log only). A model/profile size mismatch (`cosineSimilarity` throws) or ML Kit failure made recognition stop **while the screen still said "Active"**. | Violates "monitoring must not silently stop while the UI claims protection is active". | **Fixed**: 5 failures in a row -> status "Protection degraded", event logged, recovery detected. Profile length is checked against the model at start. |
| D2 | Decision was binary at one threshold (0.45). No "uncertain" result, no per-state display. | Poor light turns the owner into a "stranger" or a stranger into an "owner". | **Fixed**: three verdicts (owner / uncertain / stranger), configurable policy for uncertain faces (default: show "not sure", no alert), live state on the main screen. |
| D3 | The default match threshold 0.45 came from 5 sample photos. | See section 3: on 25 labelled photos it rejected 15.8% of same-person pairs. A threshold that is too strict makes the **owner** trigger alerts. | **Changed** to measured presets (Low 0.35 / Medium 0.40 / High 0.50, each with an uncertain zone of 0.10) and added **Settings > Calibrate on this phone**. Old installs are reset to the new preset once (settings version 2). |
| D4 | Enrollment saved whatever 5 pictures it got. No check that they were the same person, no read-back, no oval guide, no centring check. | A bad enrollment gives a bad profile for ever. | **Fixed**: oval guide, face must be centred, the 5 pictures must agree with their average (>= 0.50), the profile is saved, **read back and compared** before enrollment counts as successful. If pictures disagree, nothing is saved and enrollment restarts. |
| D5 | No event log. | You could not see why an alert did or did not happen. | **Added**: Event log screen (time, kind, similarity number; no pictures). |
| D6 | Nothing stopped a library from adding the INTERNET permission to the merged manifest. | "Biometric data never leaves the phone" was only a promise in a comment. | **Hardened**: INTERNET is removed in the manifest (`tools:node="remove"`). **You must verify it on your build** (section 6). |

**What I could not determine:** the exact failure you saw on your phone. I cannot run the app. D1-D3 are the
defects that can make "owner recognised / stranger alerted" go wrong, and the live state, event log and
calibration screen are there so the real cause shows up on your phone with numbers. If it still misbehaves, send me
the Event log lines and the "Best match" numbers you see for yourself and for the other person.

## 3. How the thresholds were chosen (measured, not invented)

Real MobileFaceNet model, 25 public photos (MIT-licensed `deepface` test set), 38 same-person and 262
different-person pairs, same crop/preprocessing as the app (RGB, `(x-127.5)/128`). Reproduce with `tools/threshold_experiment/`.

| Condition | Same person (min / 5% / median) | Different people (median / 95% / max) | Best single threshold |
|---|---|---|---|
| Clean photos | 0.29 / 0.38 / 0.71 | 0.08 / 0.30 / 0.41 | **0.35** (owner rejected 2.6%, stranger accepted 1.1%) |
| Simulated poor phone camera (small, dark, noisy, blurred, JPEG) | 0.11 / 0.25 / 0.58 | 0.18 / 0.51 / 0.68 | 0.49 (owner rejected 15.8%, stranger accepted 7.3%) |

* The old default 0.45 on clean photos: owner rejected **15.8%**, strangers accepted 0%.
* In poor conditions the two groups **overlap**. No single threshold is safe, which is why the app has an
  "uncertain" zone and why calibration on your own phone matters. These pairs are different photos of the same
  person (harder than a live frame against your own enrollment), so real owner scores should be higher; **measure it with Calibrate**.
* Enrollment check 0.50: every 3-8 photo set of one person had each picture at >= 0.56 from the average; sets with a different
  person had median 0.51. Only 2 people in the data had 3+ photos, so this catches obvious mistakes only.

Limits: celebrity photos, Haar-detected crops (the app uses ML Kit), simulated degradation, no real phone camera, no spoofing data.

## 4. Behaviour now

| Situation | Result |
|---|---|
| Owner only | state "Owner detected", no alert |
| Different person, confidently below `match - margin` | alert after the N-of-M confirmation, then cooldown |
| Owner and another person | each face judged separately: owner ok, the other person alerts |
| Face between the two limits | "Not sure who this is", no alert unless you switch on "Also alert for faces I am not sure about" |
| No face / nobody looking at the screen | no alert |
| Camera taken by another app | status "paused: camera in use", never an alert, resumes by itself |
| Analysis keeps failing | status "Protection degraded", never an alert |

## 5. Tests

### Run here, all pass (62 JUnit tests on the JVM, Kotlin 2.4.21)

Owner/uncertain/stranger verdicts and their boundaries; owner + stranger together; no face; faces turned away or too small;
uncertain policy switch; owner for 10 minutes with 1500 frames gives no alert; stranger needs the confirmation frames;
one bad frame gives no alert; stranger for 5 minutes gives exactly one alert per cooldown (10), not one per frame; face lost and
found; stranger who leaves and returns; enrollment consistency (accepts consistent, rejects a different person, rejects too
few samples, restart); threshold calibrator on synthetic score sets; failure monitor; event-log format; AES-GCM
round trip, tamper detection, wrong key; image-quality checks; plus the earlier 33 tests.
These tests use **synthetic similarity values**. They prove the decision logic, not that the model recognises faces.

### Run here, not unit tests

* Real model on 25 photos (section 3) and the preprocessing/batch-slot behaviour of the model file.
* `AppSettings`, `ProfileStore`, `FaceEmbedder` compile against Android 15 stubs and TFLite 2.16.1 classes.

### NOT run (needs your phone, camera, or real people)

| Test | Status |
|---|---|
| 1 First enrollment with a clear face | not run (device) |
| 2 Enrollment survives app restart | not run on device. The encrypted save/read-back code exists and AES-GCM is unit-tested; Keystore persistence is untested |
| 3 Owner during monitoring gives no alert | logic tested with synthetic scores; **real faces not run** |
| 4 Different person gives an alert | logic tested; **real person not run**. Stranger scores measured only offline |
| 5 Owner and another person together | logic tested; real people not run |
| 6 No face | logic tested; device not run |
| 7 Poor light / partly hidden face | uncertain-zone logic tested; offline degraded photos overlap (section 3); device not run |
| 8 Face lost and found | logic tested; device not run |
| 9 Camera permission denied / revoked | code paths exist; not run |
| 10 Model loading fails / enrollment missing | UI messages and profile-size check exist; not run |
| 11 No alert storm | logic tested (cooldown); device not run |
| **12 Printed photo / replayed video** | **no liveness or anti-spoofing is implemented. A photo or video of the owner may be accepted as the owner. Not tested.** |
| 13 Restart, pause/resume, cleanup | not run |
| 14 No biometric data leaves the device | no network code in the app; INTERNET removed in the manifest; **merged manifest not inspected** (see below) |

The Compose screens, calibration screen and service changes in this update were checked for syntax only (no syntax errors)
because the Compose/CameraX libraries cannot be downloaded on the machine they were written on. **Please build and send me any compiler error.**

## 6. Verify "no internet" on your own build

After `.\gradlew assembleDebug`, list the permissions in the real APK (replace the build-tools version with yours):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\aapt2.exe" dump permissions app\build\outputs\apk\debug\app-debug.apk
```

`android.permission.INTERNET` must NOT be in the list. Also: phone Settings > Apps > AI Privacy Guard > Permissions shows no "Internet".

## 7. Remaining limitations

* No liveness/anti-spoofing: a photo or video can fool it.
* MobileFaceNet is a small model. Light, camera quality, glasses and masks change the score. Calibrate on your phone.
* People beside or behind the phone are invisible to the camera. Eye gaze is not used.
* TensorFlow Lite 2.16.1 native libraries are 4 KB page aligned (see README, Versions).
* Android may stop background apps on some phones; the status shows the real state but cannot prevent it.
