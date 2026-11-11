# Known limitations (Android 16, Nothing Phone 3a)

These are platform rules, not bugs. The app follows them, records the outcome, and tells you what happened.

| # | Limitation | What the app does |
|---|---|---|
| 1 | **Screen sharing ends when the phone locks** (Android 15 QPR1 and later) | `MediaProjection.Callback.onStop` releases everything and the status becomes *Ended*. Each later attempt is recorded as `FAILED / PROJECTION_STOPPED` until you open the app and approve a new session. The consent dialog is never reopened automatically. |
| 2 | **One consent per session.** A consent token cannot be reused, and a session cannot survive a process restart. | `ProjectionService` is `START_NOT_STICKY`. After Android kills the app, screenshots need a new consent. |
| 3 | **Screenshots while locked or with the screen off** | Recorded as `SKIPPED / SCREEN_LOCKED` or `SKIPPED / SCREEN_OFF`; the lock screen is not captured. |
| 4 | **Secure windows** (banking, DRM video, password fields) appear black in screen captures | The frame is kept, honestly, with the note "Mostly black: the visible app may block screenshots". |
| 5 | **Camera from the background.** Camera access requires a foreground service of type `camera` started while the app is visible. Android does not guarantee camera access while locked; OEM policy, the camera privacy toggle, or another app can block it. | Attempts are made and the real result is recorded (`CAMERA_DISABLED`, `CAMERA_BUSY`, timeout…). Locked-screen camera behaviour **must be verified on your phone** with the testing guide. |
| 6 | **The camera service cannot start in the background**, at boot (Android 15+), or after a process kill on Android 14+ | A notification asks you to open the app. When you do, the service restarts and the gap is recorded as `MISSED_SCHEDULE`. No catch-up photos are taken. |
| 7 | **Doze and battery saver** can delay alarms, especially without "Alarms & reminders" (exact alarms) | Exact allow-while-idle alarms are used when allowed; otherwise inexact alarms are used and the dashboard warns you. A late cycle still runs once for its slot only. |
| 8 | **Android 14+ lets users swipe away foreground-service notifications** | The service keeps running and remains visible in the system Task Manager. The app also shows its state on the dashboard. |
| 9 | **Physical rear lenses** (the 50 MP main, 50 MP telephoto and 8 MP ultra-wide cameras) are not guaranteed to be individually accessible | Only CameraX's default back camera (the main logical camera) is used. Resolution is capped (default long edge 2560 px) for balanced quality and size. |
| 10 | **Play Integrity** gives no passing verdict for sideloaded release APKs | Use Play's internal testing track for App Check enforcement, or do not enforce App Check. |
| 11 | **Recovery code lost** | Cloud images cannot be decrypted by anyone. Images on the phone stay readable while the phone's Keystore key exists. |
| 12 | **Phone off, rebooting, or out of battery** | Nothing can run. The gap is recorded as missed (individually for up to 2 days, then as a count in the diagnostics). |
| 13 | **Clock changed backwards** | Duplicate slots are skipped; no missed attempts are invented. |
| 14 | **Cloud storage usage** is calculated from this phone's records | Objects uploaded by another phone are not counted until that phone's records exist here. |
| 15 | **Not yet compiled or device-tested.** The Android modules could not be built in the development container (no access to Google's Maven repository). | See the verification table in [../README.md](../README.md). Build on your PC and report any error. |
