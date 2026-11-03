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
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Prefs
    private lateinit var root: LinearLayout

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
            setPadding(px(20), px(12), px(20), px(40))
        }

        header("1. Setup (tap any ⚠️)")
        status("Read incoming messages", listenerEnabled()) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            status(
                "Show alarm notifications",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) { open(appNotificationSettings()) }
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
        button("🔕  Let alarms ring in Do Not Disturb") {
            open(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.channelId(p))
            )
        }
        note("Turn on \"Override Do Not Disturb\" on the screen that opens.")
        button("🔔  Test alarm now") {
            Alarms.addReminder(this, "TEST", "This is a test alarm. Tap Done to stop it.", "Quiz Alert")
            toast("Test alarm sent")
            build()
        }

        header("2. Keywords")
        toggle("Alerts on", p.enabled) { p.enabled = it }
        val kw = EditText(this).apply {
            setText(p.keywordsText)
            hint = "QUIZ, DIGI CAMPUS"
            textSize = 16f
        }
        root.addView(kw)
        button("Save keywords") {
            p.keywordsText = kw.text.toString()
            toast("Saved: ${p.keywords.joinToString(", ")}")
        }
        note("Separate keywords with commas. Capitals, spaces and dashes are ignored, so \"Digi-Campus\" and \"digicampus\" also count.")

        header("3. Watch messages from")
        check("SMS / text messages (incl. Truecaller)", p.watchSms) { p.watchSms = it }
        check("WhatsApp & WhatsApp Business", p.watchWhatsapp) { p.watchWhatsapp = it }
        check("Telegram", p.watchTelegram) { p.watchTelegram = it }
        check("Email (Gmail, Outlook)", p.watchEmail) { p.watchEmail = it }
        check("All other apps", p.watchOther) { p.watchOther = it }

        header("4. Reminder schedule")
        val intervals = listOf(15, 30, 60, 120, 180, 360)
        choice("Remind me every", intervals, intervals.map { Alarms.intervalLabel(it) }, p.intervalMin) {
            p.intervalMin = it
        }
        val maxes = listOf(0, 1, 3, 5, 10, 24)
        choice(
            "Keep reminding",
            maxes,
            maxes.map { if (it == 0) "Until I tap Done" else if (it == 1) "Only once" else "$it times" },
            p.maxRings
        ) { p.maxRings = it }
        val snoozes = listOf(5, 10, 15, 30, 60)
        choice("Snooze length", snoozes, snoozes.map { Alarms.intervalLabel(it) }, p.snoozeMin) {
            p.snoozeMin = it
        }

        header("5. Alarm style")
        toggle("Alarm sound (keeps ringing until you react)", p.sound) {
            p.sound = it
            Alarms.settingsChanged(this)
        }
        button("🎵  Alarm tone: ${toneName()}") { pickTone() }
        toggle("Vibrate", p.vibrate) {
            p.vibrate = it
            Alarms.settingsChanged(this)
        }
        toggle("Full-screen alarm over lock screen", p.fullScreen) { p.fullScreen = it }

        header("6. Quiet hours")
        toggle("Pause alarms during quiet hours", p.quietOn) { p.quietOn = it }
        button("From ${fmt(p.quietStart)}") { pickTime(p.quietStart) { p.quietStart = it } }
        button("To ${fmt(p.quietEnd)}") { pickTime(p.quietEnd) { p.quietEnd = it } }
        note("Alarms that fall in quiet hours ring when quiet hours end.")

        val list = p.reminders
        header("7. Active reminders (${list.size})")
        if (list.isEmpty()) note("None right now.")
        val df = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())
        list.sortedByDescending { it.created }.forEach { r ->
            val next = if (r.nextAt > 0) "next ${df.format(Date(r.nextAt))}" else "no more alerts"
            root.addView(TextView(this).apply {
                text = "${r.keyword} · ${r.app} · ${df.format(Date(r.created))}\n${r.text.take(200)}\nAlerts: ${r.rings} · $next"
                textSize = 14f
                setPadding(px(10), px(10), px(10), px(4))
                setBackgroundColor(0x14000000)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(8)
            })
            button("✅  Done") { Alarms.done(this, r.id); build() }
        }
        if (list.isNotEmpty()) button("Stop all reminders") { Alarms.stopAll(this); build() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------- helpers ----------

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(22), 0, px(6))
    })

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.7f
        setPadding(0, px(2), 0, px(6))
    })

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun status(t: String, ok: Boolean, fix: () -> Unit) {
        if (ok) {
            root.addView(TextView(this).apply {
                text = "✅  $t"
                textSize = 15f
                setPadding(0, px(6), 0, px(6))
            })
        } else {
            button("⚠️  $t — tap to allow", fix)
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
        root.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, px(10), 0, 0)
        })
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
            } catch (_: Exception) {
                toast("Couldn't open settings on this phone")
            }
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

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
