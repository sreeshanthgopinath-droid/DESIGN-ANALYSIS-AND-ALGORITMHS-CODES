# Device testing guide — Nothing Phone (3a), Android 16

Run these on the **physical phone** and record every result in [DEVICE_TEST_REPORT.md](DEVICE_TEST_REPORT.md). Mark each result as *automated (JVM)*, *emulator* or *physical device*, and never mark a scenario as passed unless you observed it.

## Setup

1. Install the debug APK ([INSTALLATION.md](INSTALLATION.md)). Finish onboarding and create the encryption key.
2. Allow *Alarms & reminders* and set Battery to *Unrestricted*, except in test 8.
3. Do not change the phone clock to speed things up: the clock-backwards protection deliberately skips slots. Wait for the next :00 or :30 instead, or press **Stop** and then **Start capture**, which runs a cycle immediately.
4. Keep a log running during the tests:
   ```bash
   adb logcat -s PhoneCaptureTracker:V CameraX:W AndroidRuntime:E
   ```
5. After each test, check **History** (per-attempt status and reason) and **Diagnostics**.

## Automated tests

```bash
./gradlew test                       # JVM: capture-core (41 tests) + capture unit tests
./gradlew connectedAndroidTest       # on the phone: Room, Keystore/encryption, scheduler,
                                     # camera, MediaProjection (no consent), permission, upload worker
```

The camera instrumented tests pass if they get a real decodable JPEG **or** an honest failure reason (`CAMERA_BUSY`, `CAMERA_DISABLED`, …). Note which one you got.

## Scenarios

| # | Scenario | Steps | Expected result |
|---|---|---|---|
| 1 | **Phone unlocked** | Start capture; start screen sharing (*Entire screen*); keep using the phone through one :00/:30 | 3 attempts in that slot: Front ✓, Rear ✓, Screen ✓. Notification shows *Last HH:MM*. Camera privacy dot appears briefly. Images open in the Gallery. |
| 2 | **Screen turned off** (not locked: use a long screen timeout with *Lock after screen timeout* off, or Smart Lock) | Turn the screen off over a slot | Front/Rear: record **whatever actually happened** (✓ or a failure reason). Screen: `SKIPPED / SCREEN_OFF`. |
| 3 | **Phone locked** | Lock the phone over a slot | Screen sharing ends (Android 15 QPR1+): status *Ended*, notification "Screenshots paused". Screen: `FAILED / PROJECTION_STOPPED`. Front/Rear: record the real result. Do the photos work while locked? |
| 4 | **Unlocked after locking** | Unlock, open the app | Dashboard shows sharing *Ended*. Tap *Start screen sharing* → new consent dialog → next slot screenshot ✓. The app must not reopen the dialog by itself. |
| 5 | **App minimised** | Start, press Home, use other apps over 2 slots | Captures continue. The persistent notification is visible. |
| 6 | **App process terminated** | Start; `adb shell am kill com.phonecapture.tracker` (or swipe it away from Recents); wait over 1–2 slots; open the app | Notification "Phone capture interrupted" may appear. On opening, the service restarts; missed slots appear as `FAILED / MISSED_SCHEDULE` with no catch-up photos. Screen sharing needs a new consent. |
| 7 | **Phone rebooted** | Start; reboot; wait over a slot; unlock | "Phone capture interrupted" notification after boot. Opening the app resumes; the reboot gap is recorded as missed. |
| 8 | **Battery saver on** | Turn on Battery Saver; set Battery to *Optimised*; leave the phone idle for 2 hours | Record actual timing: are attempts late or missing? Late cycles must run only once per slot; missed slots must be recorded. |
| 9 | **Internet disconnected** | Cloud backup on; Airplane mode; let 2 slots pass | Captures continue locally. Uploads show *Upload pending*. Diagnostics: "Internet unavailable" or retries. |
| 10 | **Internet restored** | Turn Wi-Fi back on | Pending uploads finish; History shows *Phone + cloud · verified*. Firebase console: `.jpg.enc` objects under the expected folder; they cannot be opened as images (they are encrypted). |
| 11 | **Camera permission revoked** | *Settings → Apps → PhoneCaptureTracker → Permissions → Camera → Don't allow* | Android kills the app. The next attempt cannot run; the notification or Diagnostics show *Foreground service unavailable*. After you re-open the app, *Start* is blocked with "Camera permission is required". Grant it again, and capture resumes. |
| 12 | **Screen capture stopped by the OS** | Tap the status-bar screen-sharing chip → *Stop sharing* | `onStop` handled: status *Ended*, next screenshots `FAILED / PROJECTION_STOPPED`, the camera continues. |
| 13 | **Cloud storage unavailable** | Set the in-app cloud limit to 1 GB with usage above it, *or* revoke access by deploying deny-all Storage rules | Quota: uploads paused with "Cloud storage exhausted". Rules: `UPLOAD_FAILED` with "rejected (security rules / App Check)". Local captures continue. |
| 14 | **Local storage nearly full** | Fill storage until < 300 MB is free (e.g. `adb shell fallocate`-style large file in Downloads) | Attempts recorded as `FAILED / LOCAL_STORAGE_EXHAUSTED`; dashboard shows low storage. After freeing space, captures resume. |

## Extra checks

- **Recovery**: on a second phone or after clearing the app's data, sign in, enter the recovery code under *Settings → Encryption & recovery → Restore from cloud*, and open an old cloud capture in the Gallery (*Cloud only* → it downloads and decrypts).
- **Wrong recovery code** is rejected with a clear message.
- **Export** shows the unencrypted-copy warning, and the exported JPEG opens in Photos.
- **Deletion**: *Phone and cloud* removes the object from the Firebase console.
- **Rotation**: rotate to landscape in a video app during a slot; the screenshot is landscape with the correct size.
- **Secure app**: open a banking app during a slot; the screenshot is black with the note about secure content.
