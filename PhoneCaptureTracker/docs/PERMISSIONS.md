# Permissions

| Permission | Type | Why | How it is granted |
|---|---|---|---|
| `CAMERA` | Runtime (while-in-use) | Front and rear photos | Onboarding → *Grant permissions*, or *Settings → Permissions & reliability* |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` | Normal | Runs `CameraCaptureService` with type `camera` | Automatic at install |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Normal | Runs `ProjectionService` with type `mediaProjection` | Automatic at install |
| *Screen capture consent* | System dialog per session | Screenshots of the entire screen | Tap *Start screen sharing* → choose *Entire screen* → *Start* |
| `POST_NOTIFICATIONS` | Runtime (Android 13+) | Persistent capture indicator with Pause/Stop buttons | Onboarding, or system notification settings |
| `SCHEDULE_EXACT_ALARM` | Special app access | Wake the phone at :00/:30 | *Settings → Apps → Special app access → Alarms & reminders* |
| `WAKE_LOCK` | Normal | Keeps the CPU awake for the few seconds of a capture cycle | Automatic |
| `RECEIVE_BOOT_COMPLETED` | Normal | After a reboot, tells you capture is paused (it cannot restart the camera itself) | Automatic |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Normal | Encrypted cloud upload and Wi-Fi-only checks | Automatic |

## Deliberately not requested

The app manifest **removes** these, even if a library tries to add them:

- `RECORD_AUDIO`: no audio and no video is ever recorded.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`: no location.
- `READ_PHONE_STATE`: no IMEI, serial number or phone identifiers.
- `com.google.android.gms.permission.AD_ID`: no advertising ID.

No accessibility service, no root, no hidden overlay, and no workaround for camera or screen restrictions is used.

## Revoking

- **Camera** revoked in system settings: Android kills the app process. On the next attempt the capture service cannot restart and logs "Foreground service unavailable". After you re-open the app, attempts are recorded as `FAILED / CAMERA_PERMISSION_DENIED` until you grant the permission again.
- **Screen sharing**: tap the status-bar chip or *Stop sharing*. Android ends the session, and screenshots are recorded as `FAILED / PROJECTION_STOPPED` until you start a new session in the app.
- **Notifications** blocked: capture still runs, but the dashboard warns you because the indicator is hidden.
