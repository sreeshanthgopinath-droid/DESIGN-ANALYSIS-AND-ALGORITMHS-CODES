# Mythos Tracker — architecture

A phone-only life logger. Everything is captured, stored and shown on the device. The optional
cloud server is a backup, not a requirement.

## Layers
1. **Capture** — small, independent collectors, each isolated so one failure never stops the others.
   - *Background, every ~15 min (WorkManager `CollectWorker`):* app usage, call log, bank SMS, photo/video metadata.
   - *Always-on foreground service (`LocationService`):* a GPS fix every 15 minutes, with a visible notification (Android blocks silent background location otherwise).
   - *Notification listener (`NotificationCaptureService`):* payment alerts (amount/payee) and the YouTube video playing now, with real watch time.
   - *Accessibility (`WebCaptureService`):* Chrome addresses and searches in Chrome/Google/YouTube — address bar and search boxes only, never page content.
   - *Manual (`AddScreen`, `ShareActivity`):* diary entries, call recordings and any shared file.
2. **Store** — one Room table `events(kind, occurred_at_ms, source_key, payload_json)`, unique on `(kind, source_key)` so re-reads never duplicate. Files kept privately in `FileStore`.
3. **Understand** — `DayRepo` fuses a day from all sources (screen time to the second from Android's own usage log, payments joined to the place you were, stays clustered from GPS). `DayInsights` answers questions on-device.
4. **Show** — Compose tabs: **Day** (summary + screen-time bars + photo strip + filterable timeline), **Map** (Strava-style trail), **Ask** (local Q&A), **Add**, **Settings**.
5. **Backup (optional)** — `SyncWorker` uploads to the Mythos server; the phone works fully without it.

## Principles
- **Resumable:** every collector keeps a high-water mark and advances it only after a successful save.
- **Private by default:** nothing leaves the phone unless a server is configured. The Ask engine is pure on-device rules.
- **Honest about the OS:** background camera and silent screenshots are blocked by Android and are not faked.
- **Degrades gracefully:** a missing permission disables one source; the rest keep working.

## Known limits (by platform, not by choice)
- Background work wakes roughly every 15 minutes; capture is not second-by-second except app usage (read from the system log).
- Old YouTube/Chrome/Search history exists only in Google Takeout; the app captures new activity going forward.
- Sleep/wake are estimated from nighttime inactivity, so they improve with use.
