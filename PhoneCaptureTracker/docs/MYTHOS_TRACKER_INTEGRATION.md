# Adding phone captures to Mythos Tracker

Mythos Tracker's source code is not in this repository or anywhere this build environment could reach, so it could not be edited directly. Every capture feature was therefore built as a **self-contained Android library (`:capture`)** with a ready-made integration API, designed to match Mythos Tracker's *My day / Add / Settings* look. If you push the Mythos Tracker source to GitHub and add that repository to a Claude Code session, the wiring below can be done for you.

## What Mythos Tracker gets

| Mythos Tracker place | What to add | Provided by |
|---|---|---|
| **My day** summary grid (Screen time · Unlocks · … · Photos) | Front / Rear / Screens / Failed / Missed / Uploaded counts | `MythosCaptureDayCard` or `MythosCaptureIntegration.daySummary()` |
| **My day** cards ("Screen time by app", "Photos & videos") | A "Phone captures" card with status dot, counts and encrypted thumbnails | `MythosCaptureDayCard(date, onOpenCaptures)` |
| **Timeline** (rows like `00:12:18 [Apps] Gesture Lock Screen`) | Rows like `10:30:02 [Front] Photo saved · 412 KB · Phone + cloud` or `10:30:05 [Screen] Capture skipped · The phone was locked…` | `MythosCaptureIntegration.timeline(context, date)` → `CaptureTimelineEntry` |
| **Filter chips** (All · Apps 202 · YouTube 0 · Web 0 · Money) | `Captures 24` | `MythosCaptureIntegration.chipLabel(summary)` |
| **Share day as text** | The capture section of the day report | `MythosCaptureIntegration.shareText(context, date)` |
| **Settings** | Capture, permission, cloud, encryption and retention settings | `SettingsScreen(...)` from `capture.ui.settings`, or the whole `CaptureApp()` |
| Full capture UI (dashboard, gallery, history, diagnostics, onboarding/consent) | One navigation destination | `CaptureApp()` |

## Step by step

### 1. Copy the modules

Copy `PhoneCaptureTracker/capture` and `PhoneCaptureTracker/capture-core` into the Mythos Tracker project root, next to its `app/` folder.

### 2. `settings.gradle.kts`

```kotlin
include(":app", ":capture", ":capture-core")
```

### 3. Version catalog and plugins

The two modules use `libs.*` aliases. Copy the missing `[versions]`, `[libraries]` and `[plugins]` entries from `PhoneCaptureTracker/gradle/libs.versions.toml` into Mythos's `gradle/libs.versions.toml`. Keep Mythos's own versions where both define the same library; any mutually compatible recent versions work. Then make sure Mythos's root `build.gradle.kts` declares:

```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    // plus the plugins Mythos already has
}
```

### 4. `app/build.gradle.kts` of Mythos Tracker

```kotlin
android { defaultConfig { minSdk = 30 } }   // the capture module needs Android 11+
dependencies { implementation(project(":capture")) }
```

### 5. Application class

```kotlin
class MythosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MythosCaptureIntegration.initialize(this)   // channels, daily clean-up, upload schedule
        // … Mythos's existing setup …
    }
}
```

### 6. "My day" card

```kotlin
import com.phonecapture.tracker.capture.mythos.MythosCaptureDayCard

// inside the My-day LazyColumn, e.g. after "Screen time by app":
item {
    MythosCaptureDayCard(
        date = selectedDate,                       // the date shown by ‹ Prev / Today / Next ›
        onOpenCaptures = { navController.navigate("captures") },
    )
}
```

### 7. Timeline rows and chip

`TimelineItem` below stands for **Mythos Tracker's own timeline row type**. Map the fields onto it:

```kotlin
val context = LocalContext.current
val captureEntries by remember(selectedDate) {
    MythosCaptureIntegration.timeline(context, selectedDate)
}.collectAsStateWithLifecycle(initialValue = emptyList())

val merged = (existingTimelineItems + captureEntries.map { e ->
    TimelineItem(
        startMillis = e.timeUtcMillis,          // UTC millis – format in local time like the other rows
        endMillis = null,                        // captures are instants, not ranges
        tag = e.tag,                             // "Front" / "Rear" / "Screen"
        tagColor = Color(e.tagColorArgb),
        title = e.title,                         // "Photo saved", "Capture failed", "Missed capture" …
        subtitle = e.subtitle,                   // size + storage, or the honest failure reason
    )
}).sortedBy { it.startMillis }
```

For the chip row:

```kotlin
val captureSummary by remember(selectedDate) {
    MythosCaptureIntegration.daySummary(context, selectedDate)
}.collectAsStateWithLifecycle(initialValue = DaySummary.EMPTY)
FilterChip(selected = filter == "captures", onClick = { filter = "captures" },
           label = { Text(MythosCaptureIntegration.chipLabel(captureSummary)) })
```

### 8. Share day as text

```kotlin
val text = mythosDayText + "\n\n" + MythosCaptureIntegration.shareText(context, selectedDate)
```

### 9. Navigation destination and Settings

```kotlin
composable("captures") { CaptureApp() }   // com.phonecapture.tracker.capture.ui.CaptureApp
```

In Mythos's **Settings** tab, add a row "Phone captures" that navigates to `"captures"`. Its own Settings tab holds all capture options. The first time it opens, it shows the consent and encryption onboarding.

## Things to check in Mythos Tracker

- **Permissions**: the library manifest adds CAMERA, the camera and media-projection foreground-service permissions, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, WAKE_LOCK, RECEIVE_BOOT_COMPLETED and INTERNET/ACCESS_NETWORK_STATE. It does **not** remove anything Mythos needs, such as the location permission behind "Places". The `tools:node="remove"` lines live only in PhoneCaptureTracker's `:app` manifest.
- **No clashes**: separate database file (`phone_capture_tracker.db`), notification channels and IDs prefixed `pct_` / `73xx`, resources prefixed `pct_`, preference files prefixed `pct_`, own Keystore alias.
- **WorkManager**: if Mythos defines a custom `Configuration.Provider` with a custom `WorkerFactory`, let it return `null` for unknown classes. WorkManager then creates `UploadWorker` and `MaintenanceWorker` through their default `(Context, WorkerParameters)` constructors.
- **Hilt or another DI framework**: no conflict. The capture module has its own composition root (`CaptureGraph.get(context)`).
- **Firebase**: if Mythos already uses Firebase, the capture module uses the same default `FirebaseApp`. **Merge** `firebase/storage.rules` and `firebase/firestore.rules` into Mythos's existing rules rather than replacing them.
- **Theme**: the capture screens use `MaterialTheme` colours, so they follow Mythos's theme automatically. `CaptureTheme` is only needed in the standalone app.
