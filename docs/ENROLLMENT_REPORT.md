# Enrollment fix report: "stuck on the 5 steps"

> **Note (Flutter move):** this report describes the native Kotlin enrollment at commit `1370dc4`. The same state machine was ported 1:1 to Dart (`lib/logic/enrollment_machine.dart`), and the same simulated-user tests run there (`test/logic/enrollment_machine_test.dart`). The screen is now a Flutter screen (`lib/screens/enrollment_screen.dart`); the camera and the face vector stay in Kotlin (`bridge/FaceSession.kt`). Simulated durations were re-measured in Dart: ideal user 6.2 s, phone held low 6.2 s, slow careful user 13.8 s, 3 pictures/s 9.0 s, tiny movements 50.3 s. Still **not** measured on a phone.


Honest status: the new enrollment logic was written and tested **with simulated users on a computer**.
It has **not been run on a phone by me** and its real speed on a phone has **not been measured**.
The screen now shows the real numbers (time, pictures per second, milliseconds per picture), so you can measure it yourself.

## 1. Root cause

I read the whole path (camera frame -> ML Kit -> step rules -> screen) and ran the old rules on made-up angles.
There is no single bug; three defects together make a normal user get stuck. I cannot say which one hit you most
without your phone, but the first two are certain from the code.

| # | Defect in the old code | Proof (old code, synthetic angles) |
|---|---|---|
| 1 | **Absolute angles, no neutral pose.** Step 1 needed `|pitch| < 12` and `|yaw| < 12` measured from the camera axis. Phones are usually held below the face (pitch around 10-25 degrees). | Pitch 16: step 1 accepted = **false**. Pitch 11: accepted = true. A 5 degree difference decides whether you can start. |
| 2 | **UP/DOWN were absolute too.** UP needed `|pitch| >= 12` (either side), DOWN the opposite side with `|pitch| >= 12`. | With neutral pitch 16: UP "matched" at the neutral pose **without any movement**; DOWN needed pitch -12 = **28 degrees** of nodding. A 20 degree nod (pitch -4) was rejected. |
| 3 | **Stale frame queue.** A new detection was started every 300 ms without waiting for the previous one. ML Kit works through them one by one. | Simulation with assumed detection times: 150 ms and 300 ms are fine; **400 ms -> the result is 10 s old after 30 s; 600 ms -> 30 s old**. You then see feedback for a pose you left long ago, and it feels like the app does not understand your movement. (Real ML Kit time on your phone: not measured.) |
| 4 | Any one failed check (size, centre, roll, blur, dark) reset the 3-picture hold counter, and the screen only showed the instruction, never WHY it was waiting. | Code reading. |
| 5 | After step 5, an all-or-nothing consistency check (every picture >= 0.50 from the average, threshold from frontal photos only) could throw the whole enrollment away and restart at step 1. | Code reading. **Suspected, not proven.** Removed. |

## 2. How movement detection works now

* **Source:** ML Kit's head angles `headEulerAngleY` (turn), `headEulerAngleX` (nod) and `headEulerAngleZ` (roll). These are real ML Kit properties.
  Facial landmarks are **not** used: I did not need them and they would cost extra time. Face-box movement alone is never used.
* **Neutral pose first:** step 1 waits until the face is roughly straight (`|yaw| <= 25`, `|pitch| <= 35`, wide on purpose so a phone held at chest height does not block it) and the angles of the last pictures differ
  by at most 5 degrees. It then stores YOUR neutral yaw and pitch. All other steps measure the movement **relative to it**.
* **Needed movement (defaults, configurable in `PoseThresholds`):** turn 12 degrees, nod 8 degrees from your neutral pose. A comfortable turn is
  about 20-30 and a comfortable nod 10-20 degrees; ML Kit's noise is a few degrees. These numbers were **not calibrated on a real phone**.
* **Hysteresis:** a pose starts at 12 / 8 degrees and ends only below 8 / 5 degrees. A wobble around the limit does not flip the state.
* **Hold:** the pose must be held for 3 pictures and 0.4 s. One noisy picture never counts.
* **Direction / mirroring:** the sign of ML Kit's angles depends on how the image is mirrored, and I could not verify it on a phone. So LEFT (and UP)
  accept a movement to **either** side and remember it; RIGHT (and DOWN) need the **opposite** side. The camera pictures are never mirrored (only the
  preview is), so every measurement uses the same coordinates. The simulated tests run both sign conventions.
* **Rejected measurements:** no face, more than one face, face smaller than 22% or bigger than 90% of the picture width, roll above 25 degrees, face off-centre
  (step 1) or outside the picture (other steps), turning while nodding (off-axis above 15 degrees).

## 3. The five steps (explicit state machine, `logic/EnrollmentMachine.kt`)

`FRONT -> LEFT -> RIGHT -> UP -> DOWN -> finished`. Each step: big instruction, live meter (bar and degrees), green oval while the pose is held,
"Good! Hold still...", then an automatic capture and "Captured!". Face lost: only the hold counter of the current step is reset; finished steps stay finished.
Time while the app is paused (picture gap above 3 s) is not counted.

If a step is slow (time per step): **8 s** a hint appears; **12 s** a smaller movement is accepted (70%); **15 s** a "Capture now" button appears
(only with a valid single face; the sample must still pass the quality and same-face checks). Buttons: **Retry this step**, **Restart**, back = cancel.
The "Capture now" button is the fallback when head angles cannot be measured on some phone: it never fakes progress.

## 4. Responsiveness

* One picture is analysed at a time. While one is busy, newer camera pictures are **dropped** (also in the calibration screen and the protection service).
* One analysis thread; nothing heavy on the main thread. The recognition model is loaded **once**, on that thread, while the camera starts.
* The embedding (the slow part) is computed only when a sample is actually taken (5 times in total), not for every picture.
* Camera 640 x 480, ML Kit ACCURATE mode (as before). The screen is updated only when something visible changed (progress in 5% steps).
* The screen shows time, pictures per second and milliseconds per picture, and the log gets the total time ("Face profile created (seconds it took)").
* Camera, ML Kit and the model are released when the screen closes.

## 5. Data quality and safe saving

* A sample is taken only if the picture passes brightness and blur checks, the embedding has the right length and finite numbers, and it looks like the first
  (straight) sample (cosine >= 0.30; from the measurements: different people reach 0.30 in about 5% of pairs, same-session pictures should be well above).
  A bad sample repeats **its own step only**.
* The profile is saved to a temporary file, **read back and decrypted**, compared, and only then moved over the old file. If anything fails the old profile
  is untouched (the old code deleted it on a failed read-back).
* Enrollment is shown as successful only after that. Photos are never saved.

## 6. Tests

### Simulated (computer, all pass: 92 unit tests in total; 32 added for this fix and 5 tests of the removed old step rules deleted)
Closed-loop "simulated user" tests: ideal user; phone held low or high; both ML Kit sign conventions; slow user with small movements; very fast movement
(hold time still required); one noisy picture; face lost for 4 s; two faces; too small / too big / rolled / off-screen face; face turned away on step 1;
wrong direction on the second step; tilting while turning; wobble around the limit (hysteresis); relax timing; hint and manual-capture timing; manual capture
refused too early or without a face; Retry step vs Restart; paused app time; capture order; embedding validity and same-face rules; frame-rate meter;
safe file replace (old file survives failed verification).
Writing these found one real bug (two faces were reported as "no face"); fixed.

Simulated durations (NOT real timing): ideal user 6.1 s, phone held low 6.1 s, slow careful user 14.0 s, slow phone with 3 pictures/s 9.0 s,
very small movements (below the normal limit) 50.5 s.

### Requested test list

| # | Test | Result |
|---|---|---|
| 1 | Centred face, normal light | simulated: pass. **Device: not run** |
| 2 | Slow and fast left/right turns | simulated: pass. **Device: not run** |
| 3 | Up/down movements | simulated: pass. **Device: not run** |
| 4 | Mirrored front-camera preview | both angle-sign conventions simulated: pass. **Real mirroring: not run** |
| 5 | Face lost and returns | simulated: pass. **Device: not run** |
| 6 | Several faces | simulated: pass (and found a bug). **Device: not run** |
| 7 | Poor light / unreliable pose | brightness and blur checks unit-tested earlier; only the hint and "Capture now" fallback cover bad pose data. **Device: not run** |
| 8 | Camera permission denied | message and "Open app settings" button added. **Not run** |
| 9 | Pause / resume | paused time handling simulated: pass. Camera lifecycle: **not run** |
| 10 | Full enrollment, persistence after restart | safe-save and AES round trip unit-tested; Keystore persistence across an app restart: **not run** |

### Build
The new Compose/CameraX screen could not be compiled here (those libraries cannot be downloaded on this machine). It was checked for syntax only.
**Build it and send me any compiler error.**

## 7. Remaining limits

* Thresholds are defaults from reasoning, not from real phone measurements. If a step still feels hard or too easy, look at the meter: it shows your measured
  degrees. Send me those numbers and the picture rate.
* I did not verify ML Kit's angle sign convention; the either-side/opposite-side rule avoids depending on it, but the words "left/right/up/down" on screen may be swapped.
* Real ML Kit time per picture on your phone is unknown until you read it on the screen.
* The consistency guard 0.30 is a coarse "obviously not the same face" check, not a security feature.

## 8. Second review (after the first successful build)

Re-reading the built code found and fixed:

1. **"Capture now" was ignored on step 1** while the face counted as "not straight" or "not steady": the button appeared after 15 s
   but pressing it did nothing, so a user could still get stuck on step 1. Fixed: the button always captures when shown (the sample
   still has to pass the quality checks); the neutral pose is then the pose of that moment. 2 tests added.
2. **Step 1 still had a narrow absolute limit** (`|pitch| <= 25`). A phone held at chest height can give about 30 degrees, which would
   repeat the original problem. Widened to yaw 25 / pitch 35. Test added (neutral pitch 30 completes).
3. **Layout:** the full-width camera preview pushed the instruction, the feedback line and the movement meter below the screen edge,
   so the user would have to scroll while looking at the camera. Now the instruction, feedback and meter are above a 70% wide preview.
