# PhoneCaptureTracker

A transparent, user-controlled Android app (Kotlin + Jetpack Compose) that **tries** to capture, every 30 minutes:

| Stream | How | Android rule that applies |
|---|---|---|
| Front camera photo | CameraX `ImageCapture`, JPEG q≈85 | Camera foreground service started while the app is visible; privacy indicator shown |
| Rear camera photo | CameraX `DEFAULT_BACK_CAMERA` (main logical camera) | Same; cameras are used one after another, never at once |
| Entire screen | MediaProjection + VirtualDisplay + ImageReader | Explicit system consent for every session; Android 15 QPR1+ ends the session when the phone locks |

Every attempt is recorded with its real result: **SUCCESS**, **FAILED** or **SKIPPED**, plus a reason. A failed capture is never shown as a success, and missed slots are recorded without taking catch-up photos later.

The capture features live in a reusable Android library (`:capture`), so they can be added to **Mythos Tracker**. See [docs/MYTHOS_TRACKER_INTEGRATION.md](docs/MYTHOS_TRACKER_INTEGRATION.md).

---

## Verification status (read this first)

| What | Status |
|---|---|
| `:capture-core` (scheduler, AES-256-GCM envelope, recovery keyring, retention/upload/storage policies, summaries) | **Compiled and unit-tested in the development container: 41/41 JVM tests pass** (Kotlin 2.2.21, JDK 21) |
| All 85 Kotlin/KTS files | **Parsed with the Kotlin 2.2.21 compiler front-end: 0 syntax errors** |
| `:capture` and `:app` Android modules | **Compiled: `./gradlew :app:assembleDebug` succeeds on GitHub Actions** (run [43062590987](https://github.com/sreeshanthgopinath-droid/DESIGN-ANALYSIS-AND-ALGORITMHS-CODES/actions/runs/43062590987), commit `af06d80`). The development container cannot reach Google's Android repositories, so CI is the build environment. |
| Debug APK | **Built by CI**: artifact `PhoneCaptureTracker-debug-apk` on the run above. Download instructions: [docs/INSTALLATION.md](docs/INSTALLATION.md). |
| `./gradlew lint`, Android unit tests, instrumented tests | **Not run yet** |
| Emulator tests | **Not run** |
| Physical Nothing Phone (3a) tests | **Not run.** Use [docs/DEVICE_TESTING_GUIDE.md](docs/DEVICE_TESTING_GUIDE.md) and record results in [docs/DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md). |

No result in this repository claims a device test that did not happen.

---

## Build

Requirements: Android Studio (Narwhal 2025.1 or newer) or JDK 17+, Android SDK Platform 36.

```bash
cd PhoneCaptureTracker
./gradlew test             # JVM unit tests (capture-core + capture)
./gradlew lint             # Android lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest   # instrumented tests on a connected phone/emulator
```

Without `app/google-services.json` the app builds in **local-only mode** (no cloud, no account). To enable encrypted cloud backup, follow [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md). Release builds: [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Documentation

| Document | Contents |
|---|---|
| [INSTALLATION.md](docs/INSTALLATION.md) | Installing on the Nothing Phone (3a), first run, Nothing OS battery settings |
| [PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission, why it is needed, how to grant it, what is *not* requested |
| [FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md) | Activating cloud sync with your own Firebase project and deploying the rules |
| [PRIVACY_AND_SECURITY.md](docs/PRIVACY_AND_SECURITY.md) | Encryption design, key recovery, data flows, deletion, privacy obligations |
| [DEVICE_TESTING_GUIDE.md](docs/DEVICE_TESTING_GUIDE.md) | The 14 device scenarios, with exact steps and expected results |
| [DEVICE_TEST_REPORT.md](docs/DEVICE_TEST_REPORT.md) | Test report: what was automated, what remains to be run on the phone |
| [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | What Android 16 does not allow, and how the app behaves instead |
| [RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) | Creating a signing key and building a release APK |
| [MYTHOS_TRACKER_INTEGRATION.md](docs/MYTHOS_TRACKER_INTEGRATION.md) | Adding the capture module to Mythos Tracker |

## Architecture

```
PhoneCaptureTracker/
├── capture-core/   Pure Kotlin – no Android. Unit-tested on any JVM.
│   ├── schedule/   CaptureSchedule (30-min slots), SchedulePlanner (no catch-up, missed slots),
│   │               ScheduleStateMachine, SingleFlight (no overlapping cycles)
│   ├── crypto/     AesGcm, EncryptedEnvelope (PCTE v1 file format), RecoveryCode, RecoveryKeyring/Vault
│   ├── policy/     RetentionPolicy, UploadRetryPolicy/UploadPolicy, StoragePolicy
│   ├── cloud/      CloudPaths (PhoneCaptureTracker/<uid>/<yyyy>/<MM>/<dd>/<Front|Rear|Screenshots>/)
│   └── stats, time, text, image, model
├── capture/        Android library – every feature, embeddable in Mythos Tracker
│   ├── camera/     CameraCaptureController, FrontCameraController, RearCameraController, JpegProcessor
│   ├── projection/ ProjectionController, ScreenshotCapture, ProjectionService (FGS: mediaProjection)
│   ├── service/    CameraCaptureService (FGS: camera, owns the schedule), CaptureController,
│   │               CaptureNotifications, DeviceStateReader
│   ├── schedule/   CaptureCycleRunner, CaptureAlarmScheduler (exact alarms), receivers
│   ├── data/       Room (CaptureRecordEntity, DiagnosticLogEntity), settings, schedule state
│   ├── security/   KeystoreKeyWrapper, VaultManager, EncryptedFileStore
│   ├── cloud/      FirebaseGateway, CloudUploader (verify size+MD5), UploadWorker (WorkManager),
│   │               CaptureDataManager (deletion, retention, keyring sync, account deletion)
│   ├── diagnostics/ DiagnosticLogger
│   ├── maintenance/ MaintenanceWorker (daily retention + clean-up)
│   ├── ui/         Compose: dashboard, gallery (+calendar, viewer, export), history, settings,
│   │               onboarding/consent, diagnostics
│   ├── mythos/     MythosCaptureDayCard, timeline entries, share text for Mythos Tracker
│   └── CaptureGraph.kt   composition root (manual constructor injection)
├── app/            Thin application: PhoneCaptureApp, MainActivity, App Check setup
├── firebase/       storage.rules, firestore.rules, firebase.json
└── docs/
```

**Dependency injection.** `CaptureGraph` is a composition root: every class receives its collaborators through its constructor (`CaptureCycleRunner` takes a `CaptureSources` interface, so tests swap in fakes). No DI framework is imposed on a host app such as Mythos Tracker.

**Capture cycle** (`CaptureCycleRunner.tick`): single-flight guard → planner decides the slot → missed slots recorded as `FAILED / MISSED_SCHEDULE` → free-space check → front → rear → screen (sequential) → each result encrypted and stored, or recorded as a failure with a reason → upload queue triggered → next exact alarm set.

**Triggers.** `CameraCaptureService` (foreground, type `camera`) runs an in-process timer and sets an exact allow-while-idle alarm for the next `:00`/`:30`. Both triggers call the same runner, and duplicates in a slot are ignored, both by the planner and by a unique `(slot, category)` index in Room.

**Uploads.** WorkManager only drives the upload queue (network constraints, back-off, persistence). It is never used to start the camera or the screen capture.

## Pinned versions

Kotlin 2.2.21 · KSP 2.2.21-2.0.4 · Gradle 8.14.3 · AGP 8.11.1 · compile/target SDK 36 · min SDK 30 · Compose BOM 2025.06.00 · CameraX 1.4.2 · Room 2.7.2 · WorkManager 2.10.2 · Lifecycle 2.9.1 · Firebase BoM 34.0.0 · coroutines 1.10.2. Kotlin, KSP and coroutines were verified on Maven Central. The Google-hosted libraries are published stable releases that could not be re-checked from the container. If Android Studio reports that one cannot be resolved, use the version it suggests.
