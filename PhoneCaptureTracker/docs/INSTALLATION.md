# Installing on the Nothing Phone (3a) — Android 16

## 0. Get a ready-built debug APK (no computer setup needed)

1. Open the GitHub Actions run for the latest build: <https://github.com/sreeshanthgopinath-droid/DESIGN-ANALYSIS-AND-ALGORITMHS-CODES/actions/runs/43062590987> (or the **Actions → Debug APK** page of the repository).
2. Scroll to **Artifacts** and download **PhoneCaptureTracker-debug-apk** (a zip, about 16 MB). GitHub requires you to be signed in to download it.
3. Unzip it and copy `app-debug.apk` to the phone (USB, Google Drive, or any file transfer). On the phone, open the file in **Files**, allow **Install unknown apps** for the app you opened it from when asked, then tap **Install**. Then continue with section 4 below.

The APK is signed with the debug key, which is fine for personal use and testing. Build a signed release APK for anything wider ([RELEASE_SIGNING.md](RELEASE_SIGNING.md)).

## 1. Build the debug APK on your computer

1. Install **Android Studio** (Narwhal 2025.1 or newer). In *SDK Manager*, install **Android SDK Platform 36** and the latest **Build-Tools**.
2. *File → Open…* → choose the `PhoneCaptureTracker` folder (not the repository root). Let Gradle sync finish.
3. Optional: add `app/google-services.json` to enable cloud backup ([FIREBASE_SETUP.md](FIREBASE_SETUP.md)). Without it, the app runs in local-only mode.
4. Build the APK with *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, or from a terminal:
   ```bash
   cd PhoneCaptureTracker
   ./gradlew assembleDebug
   ```
   The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Prepare the phone

1. *Settings → About phone → Software info* → tap **Build number** 7 times to enable Developer options.
2. *Settings → System → Developer options* → enable **USB debugging**.
3. Connect the phone with a USB cable and accept the "Allow USB debugging?" prompt.

## 3. Install

```bash
adb devices                      # the phone must be listed as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or press **Run ▶** in Android Studio with the phone selected.

Without a computer connection, you can copy the APK to the phone and open it in Files. Allow "Install unknown apps" for the Files app when asked.

## 4. First run

1. Read the consent screen and tick both boxes. They cover what is captured and your responsibility towards other people's privacy.
2. Tap **Grant permissions** and allow **Camera** and **Notifications**.
3. Tap **Create my encryption key**. **Write down the recovery code** and keep it safe. It is shown once only, and the window blocks screenshots.
4. On **My day**, tap **Start capture**. The first capture runs immediately. After that, captures run at every :00 and :30.
5. For screenshots, tap **Start screen sharing**, then choose **Entire screen**, then **Start**. You must do this again after every lock (Android 15 QPR1 and later end screen sharing when the phone locks).

## 5. Make the schedule as reliable as Android allows

These settings reduce, but cannot eliminate, Android's background restrictions:

| Setting | Where on Nothing OS (Android 16) | Why |
|---|---|---|
| Battery: **Unrestricted** | *Settings → Apps → PhoneCaptureTracker → Battery → Unrestricted* | Stops Android from pausing the foreground service |
| **Alarms & reminders** | *Settings → Apps → Special app access → Alarms & reminders → PhoneCaptureTracker → Allow* (or the **Allow** button in the app) | Exact wake-ups at :00/:30 while the phone sleeps |
| Notifications on | *Settings → Notifications → App notifications* | The capture indicator and its **Stop** button must be visible |
| Battery saver off | Quick settings | Battery saver can delay alarms and background work |

Menu names can differ slightly between Nothing OS versions. The in-app *Settings → Permissions & reliability* section links straight to each screen.

## 6. Updating and uninstalling

- Update: `adb install -r app-debug.apk`. After an update, Android does not restart the camera service. Open the app and tap **Resume capture**; the time in between is recorded as missed.
- Uninstall: removes all local data and the Keystore key. Cloud data stays in your Firebase project until you delete it, either in the app (*Settings → Privacy & data*) or in the Firebase console.
