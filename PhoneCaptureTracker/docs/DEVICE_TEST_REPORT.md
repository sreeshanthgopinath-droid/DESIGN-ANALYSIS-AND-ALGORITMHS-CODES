# Test report

Legend: **JVM** = automated on a JVM · **EMU** = emulator · **DEVICE** = physical Nothing Phone (3a) · **NOT RUN** = not executed yet.

## 1. Executed in the development container (2026-11-11)

| Suite | Environment | Result |
|---|---|---|
| `capture-core` unit tests: scheduler (slot alignment, IST half-hours, duplicate ticks, delayed execution, missed-slot cap, clock moving backwards, start/resume baseline, 24-hour randomized simulation), single-flight guard, state machine | JVM (Kotlin 2.2.21, JDK 21) | **PASS** (12 tests) |
| `capture-core` crypto: envelope round trip, unique nonces, record binding, tamper and wrong-key detection, plaintext rejection, recovery-code format and typo tolerance, Base32, keyring recovery on a "new device", wrong code, keyring extension, KDF minimum, SHA-256/MD5 vectors | JVM | **PASS** (12 tests) |
| `capture-core` policies: retention (local-only, cloud on, verified-upload clean-up, cloud expiry), retry/back-off, Wi-Fi-only default, storage thresholds, cloud path layout, path-injection rejection | JVM | **PASS** (10 tests) |
| `capture-core` summaries and formatting: day summary, share text (IST), DST day windows, byte/time formatters, image sizing, black-frame detection, failure-reason mapping | JVM | **PASS** (7 tests) |
| Syntax check of all 85 Kotlin/KTS files with the Kotlin 2.2.21 compiler front-end | JVM | **PASS** (0 syntax errors) |

**Total executed: 41 unit tests, 41 passed.**

## 2. Not executed yet

The Android SDK and Google's Maven repository were unreachable from the development container, so nothing that needs Android or AndroidX could be compiled or run.

| Suite / scenario | Environment | Result |
|---|---|---|
| `./gradlew assembleDebug` / `lint` | PC | NOT RUN |
| `:capture` JVM unit tests (`CaptureUnitTest`: camera error mapping, settings, category order, upload error classification) | JVM | NOT RUN |
| `CaptureRecordDaoTest` (Room) | EMU / DEVICE | NOT RUN |
| `EncryptionInstrumentedTest` (Keystore, encrypted files, recovery) | DEVICE | NOT RUN |
| `SchedulerInstrumentedTest` (cycle runner with real DB and encryption, fake sources) | EMU / DEVICE | NOT RUN |
| `DeviceCaptureInstrumentedTest` (front/rear camera, permission denied, MediaProjection without consent, upload worker in local-only mode) | DEVICE | NOT RUN |
| `AppSmokeTest` (Compose launch) | EMU / DEVICE | NOT RUN |
| Scenarios 1–14 in [DEVICE_TESTING_GUIDE.md](DEVICE_TESTING_GUIDE.md) | DEVICE | NOT RUN |

## 3. Physical device results

Fill this in on the Nothing Phone (3a):

| # | Scenario | Date | Result (front / rear / screen) | Notes |
|---|---|---|---|---|
| 1 | Phone unlocked | | | |
| 2 | Screen off | | | |
| 3 | Phone locked | | | |
| 4 | Unlocked after locking | | | |
| 5 | App minimised | | | |
| 6 | Process terminated | | | |
| 7 | Rebooted | | | |
| 8 | Battery saver | | | |
| 9 | Internet disconnected | | | |
| 10 | Internet restored | | | |
| 11 | Camera permission revoked | | | |
| 12 | Screen capture stopped by OS | | | |
| 13 | Cloud storage unavailable | | | |
| 14 | Local storage nearly full | | | |
