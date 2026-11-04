# Mythos Tracker (Android)

Collects phone activity, location and photo/video metadata in the background and uploads it to your laptop memory server.

## What it collects

| Source | How | Permission |
|---|---|---|
| App sessions (which app, start, end, duration) | `UsageStatsManager` | Usage access (system settings) |
| Location fix every ~15 min | Fused location | Location, "Allow all the time" |
| Photo and video metadata (time, file name, URI; GPS tag for photos only, read from EXIF) | `MediaStore` | Photos and videos, plus media location |

Media files are never copied; only metadata is sent.

## Get the app onto the phone (no Android Studio needed)

The project builds itself on GitHub. After the code is pushed, the **Build APK** workflow produces `MythosTracker.apk` and attaches it to a Release.

1. On the phone, open the repository's **Releases** page and download `MythosTracker.apk`.
2. Open the downloaded file. Android will ask to allow installs from your browser or Files app. Allow it for this install.
3. If Play Protect warns about an unknown app, choose **Install anyway**. It is a debug build signed with a debug key.

To build it yourself instead, open the folder in Android Studio (JDK 17, Android SDK 35) and run it on a device with Android 8.0 (API 26) or newer.

## First run on the phone

1. Enter the server URL (for example `http://100.x.y.z:8787` using the laptop's Tailscale address) and an access token, then tap **Save and sync**.
2. Tap **Allow** for each permission. Set location to **Allow all the time**.
3. For usage access, tap **Allow**, find Mythos Tracker in the list and turn it on.
4. Tap **Allow** on "Unrestricted battery". Some phone brands also have their own battery manager. Set this app to unrestricted there too.

Collection runs every 15 minutes through WorkManager, and it keeps running after the app is closed. Events are stored on the phone first, so they are kept while the laptop is offline and uploaded when it returns.

## Server contract (for the laptop backend)

`POST {serverUrl}/api/events` with header `Authorization: Bearer <token>`:

```json
{
  "device_id": "uuid",
  "events": [
    {
      "kind": "app_session | location | photo | video",
      "occurred_at_ms": 1767000000000,
      "source_key": "app:com.example:1767000000000",
      "payload": { "...": "kind-specific fields" }
    }
  ]
}
```

- Respond with any 2xx code only after the batch is stored. The app marks events as synced only then.
- Dedupe on `(kind, source_key)`. The app may resend a batch after a failure.
- Payloads by kind:
  - `app_session`: `package`, `start_ms`, `end_ms`, `duration_ms`
  - `location`: `lat`, `lng`, `accuracy_m`
  - `photo` / `video`: `uri`, `display_name`, and optionally `lat`, `lng`

## Known limits

- Android allows at most one background run every 15 minutes. Location is a single fix per run, not a continuous track.
- Usage events are kept by Android only for a limited time, so the app reads at most 7 days back.
- Some manufacturers kill background work aggressively. If the pending count keeps growing with no syncs, check the battery setting above.
- The access token is stored in plain app-private SharedPreferences.
- Call recordings, Nothing Essential Space notes, PhonePe and Google data are not part of this app yet.
