package com.example.keywordalert

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Prefs
    private lateinit var root: LinearLayout
    private val df = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        Alarms.ensureChannel(this, p)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Watchdog.schedule(this)
        Watchdog.rebind(this)
        build()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
    }

    // ---------- screen ----------

    private fun build() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(48))
        }
        val now = System.currentTimeMillis()

        // Status
        header("Status")
        big(
            when {
                !listenerEnabled() -> "❌ Not reading messages yet. Tap the first ⚠️ below."
                !p.enabled -> "🔕 Alerts are switched off (section 2)."
                p.pausedUntil > now -> "⏸ Paused until ${df.format(Date(p.pausedUntil))}"
                p.keywords.isEmpty() -> "⚠️ No keywords set (section 2)."
                else -> "✅ Watching for: ${p.keywords.joinToString(", ")}"
            }
        )
        note(if (p.lastSeen > 0) "Last notification read: ${ago(p.lastSeen)}" else "No notifications read yet.")

        // 1. Setup
        header("1. Setup (tap any ⚠️)")
        status("Read incoming messages", listenerEnabled()) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            status(
                "Show alarm notifications",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            status("Exact alarm timing", getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            status("Full-screen alarm", getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        status(
            "Keep running in background",
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("📱  Allow auto-start (opens App info)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        note("On Realme, Oppo, OnePlus, Xiaomi or Vivo: in App info turn on Auto-launch / Allow background activity and set Battery to Unrestricted. Otherwise the phone may stop the app.")
        button("🔄  Reconnect message reader") {
            Watchdog.hardReconnect(this)
            toast("Reconnected. Send a test message.")
        }
        button("🔕  Let alarms ring in Do Not Disturb") {
            open(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.channelId(p))
            )
        }
        note("Turn on \"Override Do Not Disturb\" on the screen that opens. Do this again after changing sound or vibration.")
        button("🔔  Test alarm now") {
            Alarms.onMatch(this, "TEST", "This is a test alarm. Tap Done to stop it.", "Quiz Alert", "test.alarm")
            build()
        }

        // 2. Keywords
        header("2. Keywords")
        toggle("Alerts on", p.enabled) { p.enabled = it }
        label("Ring when a notification contains any of these:")
        editField(p.keywordsText, "QUIZ, DIGI CAMPUS") { p.keywordsText = it }
        note("Separate with commas. Saved automatically as you type. Capitals, spaces and dashes are ignored, so \"Quiz\", \"QUIZ\", \"quizzes\", \"Digi-Campus\" and \"digicampus\" all count.")
        label("But don't ring if it also contains (optional):")
        editField(p.excludeText, "e.g. result, marks, solution") { p.excludeText = it }
        choice(
            "How to match", listOf(0, 1),
            listOf("Anywhere in the text (recommended)", "Whole words only (quizzes won't match quiz)"),
            if (p.wholeWord) 1 else 0
        ) { p.wholeWord = it == 1 }
        toggle("Also check titles (sender, group or notification title)", p.matchTitles) { p.matchTitles = it }

        // 3. Try a message
        header("3. Try a message")
        val sample = EditText(this).apply { hint = "Type e.g. New quiz arrived"; textSize = 16f }
        root.addView(sample)
        val result = TextView(this).apply { textSize = 15f; setPadding(0, px(4), 0, px(4)) }
        button("Would this ring?") { result.text = checkText(sample.text.toString()) }
        root.addView(result)
        button("Ring as if this message just arrived") {
            val t = sample.text.toString().ifBlank { "Sample message" }
            val kw = KeywordMatcher.find(t, p.keywords, p.wholeWord)
            if (kw == null || KeywordMatcher.excluded(t, p.excludes) != null) {
                result.text = checkText(t)
            } else {
                Alarms.onMatch(this, kw.uppercase(), t, "Test message", "test.message")
                build()
            }
        }

        // 4. Sources
        header("4. Watch notifications from")
        check("SMS / text messages (incl. Truecaller)", p.watchSms) { p.watchSms = it }
        check("WhatsApp & WhatsApp Business", p.watchWhatsapp) { p.watchWhatsapp = it }
        check("Telegram", p.watchTelegram) { p.watchTelegram = it }
        check("Email (Gmail, Outlook)", p.watchEmail) { p.watchEmail = it }
        check("All other apps (college app, Classroom, etc.)", p.watchOther) { p.watchOther = it }
        check("Also check grouped summary notifications", p.checkSummaries) { p.checkSummaries = it }
        check("Also check persistent notifications (music, downloads, timers)", p.checkOngoing) { p.checkOngoing = it }

        // 5. Schedule
        header("5. Reminder schedule")
        note("A matching message rings straight away. These settings control the repeats.")
        val intervals = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 720)
        choice("Repeat every", intervals, intervals.map { Alarms.intervalLabel(it) }, p.intervalMin) { p.intervalMin = it }
        val maxes = listOf(0, 1, 2, 3, 5, 10, 24, 48)
        choice(
            "Keep reminding", maxes,
            maxes.map { if (it == 0) "Until I tap Done" else if (it == 1) "Only once (no repeats)" else "$it times in total" },
            p.maxRings
        ) { p.maxRings = it }
        val snoozes = listOf(1, 5, 10, 15, 20, 30, 60)
        choice("Snooze length", snoozes, snoozes.map { Alarms.intervalLabel(it) }, p.snoozeMin) { p.snoozeMin = it }
        choice(
            "When the same app sends another match", listOf(1, 0),
            listOf("Update the existing alarm and ring again", "Make a separate alarm for each message"),
            if (p.mergePerApp) 1 else 0
        ) { p.mergePerApp = it == 1 }
        val cooldowns = listOf(0, 5, 10, 20, 30, 60, 120)
        choice(
            "Ignore extra matches from the same app within", cooldowns,
            cooldowns.map { if (it == 0) "Off (ring for every one)" else Alarms.secondsLabel(it) }, p.cooldownSec
        ) { p.cooldownSec = it }

        // 6. Alarm style
        header("6. Alarm style")
        toggle("Alarm sound (keeps ringing until you react)", p.sound) {
            p.sound = it
            Alarms.settingsChanged(this)
        }
        button("🎵  Alarm tone: ${toneName()}") { pickTone() }
        toggle("Turn alarm volume to max when ringing", p.maxVolume) { p.maxVolume = it }
        toggle("Vibrate", p.vibrate) {
            p.vibrate = it
            Alarms.settingsChanged(this)
        }
        toggle("Strong vibration", p.strongVibe) {
            p.strongVibe = it
            Alarms.settingsChanged(this)
        }
        toggle("Full-screen alarm over lock screen", p.fullScreen) { p.fullScreen = it }
        val limits = listOf(0, 30, 60, 120, 300, 600)
        choice(
            "Stop ringing after", limits,
            limits.map { if (it == 0) "Never, ring until I react" else Alarms.secondsLabel(it) }, p.ringLimitSec
        ) { p.ringLimitSec = it }
        note("When ringing stops on its own, the reminder stays and rings again at the next repeat.")

        // 7. Quiet hours
        header("7. Quiet hours")
        toggle("Hold alarms during quiet hours", p.quietOn) { p.quietOn = it }
        button("From ${fmt(p.quietStart)}") { pickTime(p.quietStart) { p.quietStart = it } }
        button("To ${fmt(p.quietEnd)}") { pickTime(p.quietEnd) { p.quietEnd = it } }
        note("Alarms that fall in quiet hours ring when quiet hours end.")

        // 8. Pause
        header("8. Pause")
        if (p.pausedUntil > now) note("Paused until ${df.format(Date(p.pausedUntil))}. Messages are ignored until then.")
        button("Pause for 1 hour") { pauseFor(60) }
        button("Pause for 3 hours") { pauseFor(180) }
        button("Pause until 7 AM") {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            p.pausedUntil = c.timeInMillis
            build()
        }
        if (p.pausedUntil > now) button("▶️  Resume now") { p.pausedUntil = 0L; build() }

        // 9. Active reminders
        val list = p.reminders
        header("9. Active reminders (${list.size})")
        if (list.isEmpty()) note("None right now.")
        list.sortedByDescending { it.created }.forEach { r ->
            val next = if (r.nextAt > 0) "next ${df.format(Date(r.nextAt))}" else "no more alerts"
            card("${r.keyword} · ${r.app} · ${df.format(Date(r.created))}\n${r.text.take(200)}\nAlerts: ${r.rings} · $next")
            button("✅  Done") { Alarms.done(this, r.id); build() }
        }
        if (list.isNotEmpty()) button("Stop all reminders") { Alarms.stopAll(this); build() }

        // 10. Activity log
        val entries = ActivityLog.entries(p)
        header("10. Activity log")
        note("Shows what the app read and why it did or didn't ring. Stored only on this phone.")
        toggle("Keep a log", p.logOn) { p.logOn = it }
        toggle("Log every notification (not only keyword matches)", p.logAll) { p.logAll = it }
        button("🔃  Refresh") { build() }
        if (entries.isEmpty()) note("Nothing logged yet.")
        entries.take(60).forEach { e ->
            card("${df.format(Date(e.t))} · ${e.app}\n${e.text}\n→ ${e.result}")
        }
        if (entries.isNotEmpty()) button("Clear log") { ActivityLog.clear(p); build() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------- helpers ----------

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun checkText(t: String): String {
        if (t.isBlank()) return "Type a message first."
        val kw = KeywordMatcher.find(t, p.keywords, p.wholeWord)
            ?: return "❌ Would NOT ring: none of your keywords (${p.keywords.joinToString(", ")}) are in it."
        val ex = KeywordMatcher.excluded(t, p.excludes)
        if (ex != null) return "❌ Would NOT ring: it contains your excluded word \"$ex\"."
        return "✅ Would ring (matches \"$kw\")."
    }

    private fun pauseFor(min: Int) {
        p.pausedUntil = System.currentTimeMillis() + min * 60_000L
        build()
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000L
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            else -> df.format(Date(t))
        }
    }

    private fun header(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(22), 0, px(6))
    })

    private fun big(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(4), 0, px(4))
    })

    private fun label(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, px(10), 0, 0)
    })

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.7f
        setPadding(0, px(2), 0, px(6))
    })

    private fun card(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(px(10), px(10), px(10), px(10))
        setBackgroundColor(0x14000000)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = px(8)
    })

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun editField(value: String, hintText: String, onChange: (String) -> Unit) {
        val et = EditText(this)
        et.setText(value)
        et.hint = hintText
        et.textSize = 16f
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
        })
        root.addView(et)
    }

    private fun status(t: String, ok: Boolean, fix: () -> Unit) {
        if (ok) {
            root.addView(TextView(this).apply {
                text = "✅  $t"
                textSize = 15f
                setPadding(0, px(6), 0, px(6))
            })
        } else {
            button("⚠️  $t: tap to allow", fix)
        }
    }

    private fun toggle(t: String, on: Boolean, onChange: (Boolean) -> Unit) = root.addView(Switch(this).apply {
        text = t
        textSize = 15f
        isChecked = on
        setPadding(0, px(8), 0, px(8))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    })

    private fun check(t: String, on: Boolean, onChange: (Boolean) -> Unit) = root.addView(CheckBox(this).apply {
        text = t
        textSize = 15f
        isChecked = on
        setOnCheckedChangeListener { _, c -> onChange(c) }
    })

    private fun choice(label: String, values: List<Int>, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        label(label)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(values.indexOf(current).coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                onPick(values[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(sp)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                toast("Couldn't open settings on this phone")
            }
        }
    }

    private fun listenerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun fmt(min: Int): String {
        val h = min / 60
        val m = min % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.getDefault(), "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }

    private fun pickTime(current: Int, onSet: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onSet(h * 60 + m); build() }, current / 60, current % 60, false).show()
    }

    private fun toneName(): String = try {
        val uri = Alarms.toneUri(p)
        if (uri == null) "Default" else RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Default"
    } catch (e: Exception) {
        "Default"
    }

    private fun pickTone() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm tone")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarms.toneUri(p))
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_TONE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TONE && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            p.toneUri = uri?.toString()
            Alarms.settingsChanged(this)
        }
    }

    companion object {
        private const val REQ_TONE = 42
    }
}
