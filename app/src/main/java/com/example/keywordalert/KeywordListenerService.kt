package com.example.keywordalert

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object KeywordMatcher {
    // Ignore case, spaces and punctuation: "Digi-Campus", "digicampus" and "DIGI CAMPUS" all match.
    fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun words(s: String) =
        s.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun find(text: String, keywords: List<String>, wholeWord: Boolean): String? {
        if (!wholeWord) {
            val t = norm(text)
            return keywords.firstOrNull { val k = norm(it); k.isNotEmpty() && t.contains(k) }
        }
        val w = words(text)
        return keywords.firstOrNull { kw ->
            val k = words(kw)
            k.isNotEmpty() && (0..(w.size - k.size)).any { i -> k.indices.all { j -> w[i + j] == k[j] } }
        }
    }

    fun excluded(text: String, excludes: List<String>): String? {
        val t = norm(text)
        return excludes.firstOrNull { val k = norm(it); k.isNotEmpty() && t.contains(k) }
    }
}

object AppFilter {
    private val SMS = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.android.messaging", "com.oneplus.mms", "com.truecaller", "com.coloros.mms",
        "com.oplus.mms", "com.miui.mms", "com.xiaomi.mms"
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

data class Item(val id: String, val matchText: String, val display: String)

object NotificationText {
    private fun h(s: String) = Integer.toHexString(KeywordMatcher.norm(s).hashCode())

    /** Returns the title and one item per message (chat apps) or per line / whole text (other apps). */
    @Suppress("DEPRECATION")
    fun items(sbn: StatusBarNotification, matchTitles: Boolean): Pair<String, List<Item>> {
        val n = sbn.notification
        val e = n.extras ?: Bundle()
        fun cs(k: String) = try { e.getCharSequence(k)?.toString()?.trim().orEmpty() } catch (x: Exception) { "" }

        val title = listOf(
            cs(Notification.EXTRA_TITLE), cs(Notification.EXTRA_TITLE_BIG), cs(Notification.EXTRA_CONVERSATION_TITLE)
        ).filter { it.isNotEmpty() }.distinct().joinToString(" · ")

        val items = ArrayList<Item>()

        // Chat apps (WhatsApp, Messages, Telegram...) list each message with its own arrival time.
        val msgs = try { e.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (x: Exception) { null }
        if (msgs != null) {
            for (m in msgs) {
                val b = m as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val time = b.getLong("time")
                var sender = b.getCharSequence("sender")?.toString().orEmpty()
                if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    sender = (b.get("sender_person") as? Person)?.name?.toString().orEmpty()
                }
                val match = if (matchTitles) "$title $sender $text" else text
                val display = if (sender.isNotEmpty()) "$sender: $text" else text
                items.add(Item("m|$time|${h(text)}", match, display))
            }
        }
        if (items.isNotEmpty()) return Pair(title, items)

        // Other apps: check every line, then the full text.
        val lines = (try { e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) } catch (x: Exception) { null })
            ?.map { it?.toString()?.trim().orEmpty() }?.filter { it.isNotEmpty() } ?: emptyList()
        for (line in lines) {
            items.add(Item("l|${h(line)}", if (matchTitles) "$title $line" else line, line))
        }

        val parts = ArrayList<String>()
        fun add(s: String) {
            if (s.isNotEmpty() && parts.none { it.contains(s) } && lines.none { it.contains(s) }) parts.add(s)
        }
        add(cs(Notification.EXTRA_BIG_TEXT))
        add(cs(Notification.EXTRA_TEXT))
        val core = parts.joinToString("\n")
        add(cs(Notification.EXTRA_SUB_TEXT))
        add(cs(Notification.EXTRA_SUMMARY_TEXT))
        add(cs(Notification.EXTRA_INFO_TEXT))
        add(n.tickerText?.toString()?.trim().orEmpty())
        val body = parts.joinToString("\n")
        val match = if (matchTitles) "$title\n$body" else body
        if (match.isNotBlank()) items.add(Item("c|${h(title + "\n" + core)}", match, body.ifEmpty { title }))

        return Pair(title, items)
    }
}

class KeywordListenerService : NotificationListenerService() {

    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Catch matching messages that arrived while the reader was off.
        try {
            val active = activeNotifications ?: emptyArray()
            val p = Prefs(this)
            val seen = SeenStore.load(p)
            val keys = active.map { it.key }.toSet()
            val iter = seen.keys()
            val gone = ArrayList<String>()
            while (iter.hasNext()) { val k = iter.next(); if (k !in keys) gone.add(k) }
            gone.forEach { SeenStore.markRemoved(seen, it) }
            p.seenJson = seen.toString()
            active.forEach { handleSafe(it, startup = true) }
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Watchdog.rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleSafe(sbn, startup = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            val p = Prefs(this)
            val seen = SeenStore.load(p)
            SeenStore.markRemoved(seen, sbn.key)
            p.seenJson = seen.toString()
        } catch (e: Exception) {
        }
    }

    private fun handleSafe(sbn: StatusBarNotification, startup: Boolean) {
        try {
            handle(sbn, startup)
        } catch (e: Exception) {
            ActivityLog.add(this, appLabel(sbn.packageName), "", "error: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification, startup: Boolean) {
        if (sbn.packageName == packageName) return
        val p = Prefs(this)
        if (!startup) p.lastSeen = System.currentTimeMillis()

        val n = sbn.notification
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val (title, items) = NotificationText.items(sbn, p.matchTitles)
        val app = appLabel(sbn.packageName)
        val keywords = p.keywords

        val seen = SeenStore.load(p)
        SeenStore.markPresent(seen, sbn.key)
        var hit: Item? = null
        var hitKw: String? = null
        var reason = "no keyword"
        for (item in items) {
            val kw = KeywordMatcher.find(item.matchText, keywords, p.wholeWord) ?: continue
            val ex = KeywordMatcher.excluded(item.matchText, p.excludes)
            if (ex != null) {
                if (hit == null) reason = "has \"$kw\" but also excluded word \"$ex\""
                continue
            }
            if (SeenStore.has(seen, sbn.key, item.id)) {
                if (hit == null) reason = "old message, already alerted"
                continue
            }
            SeenStore.add(seen, sbn.key, item.id)
            if (hit == null) { hit = item; hitKw = kw }
        }
        p.seenJson = seen.toString()

        val shown = hit?.display ?: items.lastOrNull()?.display ?: ""
        val preview = if (title.isNotEmpty()) "$title: $shown" else shown

        val h = hit
        val kw = hitKw
        if (h == null || kw == null) {
            val keywordSeen = reason != "no keyword"
            if (!startup && (keywordSeen || (p.logAll && !ongoing && !summary))) {
                ActivityLog.add(this, app, preview, if (keywordSeen) "not rung: $reason" else "no keyword")
            }
            return
        }

        val result = when {
            !p.enabled -> "not rung: alerts are switched off"
            p.pausedUntil > System.currentTimeMillis() -> "not rung: alerts are paused"
            !AppFilter.watches(this, p, sbn.packageName) -> "not rung: this app type is unticked"
            summary && !p.checkSummaries -> "not rung: grouped summary (option is off)"
            ongoing && !p.checkOngoing -> "not rung: persistent notification (option is off)"
            else -> {
                val display = listOf(title, h.display).filter { it.isNotEmpty() }.distinct().joinToString("\n")
                Alarms.onMatch(this, kw.uppercase(), display, app, sbn.packageName)
            }
        }
        ActivityLog.add(this, app, preview, if (startup) "$result (found when reader started)" else result)
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
