package com.example.keywordalert

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object KeywordMatcher {
    // Ignore case, spaces and punctuation: "Digi-Campus", "digicampus" and "DIGI CAMPUS" all match.
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    fun find(text: String, keywords: List<String>): String? {
        val t = norm(text)
        return keywords.firstOrNull { val k = norm(it); k.isNotEmpty() && t.contains(k) }
    }
}

object AppFilter {
    private val SMS = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.android.messaging", "com.oneplus.mms", "com.truecaller"
    )
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val TELEGRAM = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus"
    )
    private val EMAIL = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail", "com.samsung.android.email.provider"
    )

    fun watches(ctx: Context, p: Prefs, pkg: String): Boolean {
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (e: Exception) { null }
        return when {
            pkg in SMS || pkg == defaultSms -> p.watchSms
            pkg in WHATSAPP -> p.watchWhatsapp
            pkg in TELEGRAM -> p.watchTelegram
            pkg in EMAIL -> p.watchEmail
            else -> p.watchOther
        }
    }
}

class KeywordListenerService : NotificationListenerService() {

    // Messaging apps re-post the same text when new messages arrive; don't alarm twice for it.
    private val seen = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val p = Prefs(this)
        if (!p.enabled) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (!AppFilter.watches(this, p, sbn.packageName)) return

        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val parts = ArrayList<String>()
        fun addPart(s: String?) {
            val t = s?.trim().orEmpty()
            if (t.isNotEmpty() && parts.none { it.contains(t) }) parts.add(t)
        }
        addPart(e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        addPart(e.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { addPart(it?.toString()) }

        val keywords = p.keywords
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > DEDUPE_MS }

        var hit: String? = null
        val candidates = if (title.isNotEmpty()) parts + title else parts
        for (c in candidates) {
            val kw = KeywordMatcher.find(c, keywords) ?: continue
            val key = sbn.packageName + "|" + c
            if (seen.containsKey(key)) continue
            seen[key] = now
            if (hit == null) hit = kw
        }
        val keyword = hit ?: return

        val display = (listOf(title) + parts.take(4)).filter { it.isNotEmpty() }.distinct().joinToString("\n")
        Alarms.addReminder(this, keyword.uppercase(), display, appLabel(sbn.packageName))
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private const val DEDUPE_MS = 3 * 60 * 60 * 1000L
    }
}
