# Release APK and signing

Debug builds are signed automatically with Android Studio's debug key. A **release** build needs your own signing key. **Keep that key private and back it up**: updates to an installed release app must be signed with the same key.

## 1. Create a signing key (once)

```bash
keytool -genkeypair -v \
  -keystore ~/keys/phonecapture-release.jks \
  -alias phonecapture -keyalg RSA -keysize 4096 -validity 10000
```

Store the `.jks` file and its passwords in a password manager. Never put the key file in the repository.

## 2. Tell Gradle where it is

Create `PhoneCaptureTracker/keystore.properties`. It is git-ignored:

```properties
storeFile=/home/you/keys/phonecapture-release.jks
storePassword=••••••
keyAlias=phonecapture
keyPassword=••••••
```

`app/build.gradle.kts` reads this file only if it exists. Without it, `assembleRelease` produces an **unsigned** APK, which cannot be installed.

## 3. Build and verify

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk  (R8-minified, resources shrunk)
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 4. Firebase / App Check

- Add the release key's **SHA-256** to the Firebase Android app (*Project settings*).
- Release builds use the **Play Integrity** App Check provider. It passes only for installs from Google Play, so upload an App Bundle (`./gradlew bundleRelease`) to the Play Console **internal testing** track and install from there. For a sideloaded release APK, do not enforce App Check.

## 5. Before every release

- `./gradlew test lint` must pass.
- Run the scenarios in [DEVICE_TESTING_GUIDE.md](DEVICE_TESTING_GUIDE.md) on the phone and update [DEVICE_TEST_REPORT.md](DEVICE_TEST_REPORT.md).
- Increase `versionCode` / `versionName` in `app/build.gradle.kts`.
