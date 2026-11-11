package com.mythos.tracker

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records websites opened in Chrome and what you search in Chrome, the Google app and YouTube.
 * It only looks at Chrome's address bar and those apps' search boxes (limited in res/xml/web_capture.xml);
 * it never reads page contents, messages or passwords.
 */
class WebCaptureService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val typed = HashMap<String, String>()
    private val lastCheck = HashMap<String, Long>()
    private var lastVisit = ""
    private var lastSearch = ""
    private var lastSearchAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        try {
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                if (e.isPassword) return
                val id = e.source?.viewIdResourceName
                if (isSearchBox(pkg, id)) {
                    val text = e.text?.joinToString("")?.trim().orEmpty()
                    if (text.isNotEmpty()) typed[pkg] = text
                }
            } else {
                check(pkg)
            }
        } catch (_: Exception) {
            // Never let a reading problem crash the service.
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isSearchBox(pkg: String, id: String?): Boolean = when (pkg) {
        CHROME -> id == "$CHROME:id/url_bar" || id == "$CHROME:id/search_box_text"
        YOUTUBE -> id != null && id.contains("search", ignoreCase = true)
        GOOGLE -> true
        else -> false
    }

    private fun check(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - (lastCheck[pkg] ?: 0L) < 700) return
        lastCheck[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        if (pkg == CHROME) {
            val bar = root.findAccessibilityNodeInfosByViewId("$CHROME:id/url_bar").firstOrNull()
            if (bar == null) {
                flushTyped(pkg)
                return
            }
            if (bar.isFocused) return
            flushTyped(pkg)
            if (bar.isShowingHintText) return
            val url = bar.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty() && url != lastVisit) {
                lastVisit = url
                visit(url)
            }
        } else {
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focus == null || !focus.isEditable) flushTyped(pkg)
        }
    }

    private fun flushTyped(pkg: String) {
        val text = typed.remove(pkg) ?: return
        if (!text.contains(' ') && text.contains('.') && pkg == CHROME) return // a typed web address; recorded as a visit
        search(text, pkg)
    }

    private fun visit(url: String) {
        if (url.contains("google.") && url.contains("/search")) {
            val q = runCatching {
                Uri.parse(if (url.startsWith("http")) url else "https://$url").getQueryParameter("q")
            }.getOrNull()
            if (!q.isNullOrBlank()) search(q, CHROME)
        }
        store("web_visit", JSONObject().put("url", url).put("app", CHROME))
    }

    private fun search(query: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (query.equals(lastSearch, ignoreCase = true) && now - lastSearchAt < 120_000) return
        lastSearch = query
        lastSearchAt = now
        val where = when (pkg) {
            YOUTUBE -> "YouTube"
            GOOGLE -> "Google app"
            else -> "Chrome"
        }
        store("web_search", JSONObject().put("query", query).put("app", pkg).put("where", where))
    }

    private fun store(kind: String, payload: JSONObject) {
        val now = System.currentTimeMillis()
        val event = EventEntity(
            kind = kind,
            occurredAtMs = now,
            sourceKey = "$kind:$now:${payload.toString().hashCode()}",
            payloadJson = payload.toString(),
        )
        scope.launch { TrackerDatabase.get(applicationContext).events().insertAll(listOf(event)) }
    }

    companion object {
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val GOOGLE = "com.google.android.googlequicksearchbox"
    }
}
