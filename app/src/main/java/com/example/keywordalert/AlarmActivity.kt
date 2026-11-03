package com.example.keywordalert

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen alarm shown over the lock screen. */
class AlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val id = intent.getIntExtra(Alarms.EXTRA_ID, -1)
        val p = Prefs(this)
        val r = p.reminders.find { it.id == id }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(48), px(24), px(24))
        }
        fun line(t: String, size: Float, bold: Boolean = false) = box.addView(TextView(this).apply {
            text = t
            textSize = size
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(8), 0, px(8))
        })
        fun button(t: String, onClick: () -> Unit) = box.addView(Button(this).apply {
            text = t
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64)).apply { topMargin = px(12) })

        if (r == null) {
            line("This reminder has already been cleared.", 18f)
            button("Close") { finish() }
        } else {
            val time = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault()).format(Date(r.created))
            line("⏰", 56f)
            line(r.keyword, 30f, bold = true)
            line("From ${r.app} · $time", 15f)
            line(r.text, 18f)
            line(
                if (r.nextAt > 0) "Reminds again every ${Alarms.intervalLabel(p.intervalMin)} until you tap Done"
                else "This was the last alert",
                14f
            )
            button("✅  Done, stop reminding me") { Alarms.done(this, r.id); finish() }
            button("😴  Snooze ${p.snoozeMin} min") { Alarms.snooze(this, r.id); finish() }
            button("🔇  Silence now, remind me later") { Alarms.silence(this, r.id); finish() }
            button("Open Quiz Alert") {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        setContentView(ScrollView(this).apply { addView(box) })
    }
}
