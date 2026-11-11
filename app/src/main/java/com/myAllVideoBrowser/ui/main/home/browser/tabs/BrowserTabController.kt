package com.myAllVideoBrowser.ui.main.home.browser.tabs

import java.util.UUID

/**
 * Pure tab session model for the built-in browser.
 *
 * WebViews, cookies, and download workers stay outside this class. The
 * controller only decides which tab is current, how history and loading move,
 * whether a popup may become a tab, and which detached WebView can be released.
 * Cookies are the process-wide CookieManager and are never cleared here.
 * Download workers are not cancelled here.
 */
class BrowserTabController(
    private val maxLiveWebViews: Int = DEFAULT_MAX_LIVE_WEBVIEWS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val sessions = mutableListOf<Session>()
    private var currentIndex = 0

    init {
        sessions.add(
            Session(
                id = HOME_ID,
                url = "",
                title = "Home",
                isHome = true
            )
        )
    }

    fun snapshot(): BrowserTabsSnapshot {
        val tabs = sessions.map { it.toState() }
        return BrowserTabsSnapshot(
            tabs = tabs,
            currentIndex = currentIndex,
            currentId = sessions[currentIndex].id
        )
    }

    fun open(id: String = newId(), url: String, title: String?, mode: OpenMode): OpenResult {
        if (sessions.any { it.id == id }) {
            val existing = snapshot()
            return OpenResult(existing, id, switched = false)
        }
        val safeTitle = when {
            !title.isNullOrBlank() -> title
            url.isNotBlank() -> url
            else -> "New tab"
        }
        val session = Session(
            id = id,
            url = url,
            title = safeTitle,
            isHome = false,
            lastActiveAt = now()
        )
        if (isHttp(url)) {
            session.history.add(url)
            session.historyIndex = 0
        }
        sessions.add(session)
        val switched = mode == OpenMode.FOREGROUND
        if (switched) {
            currentIndex = sessions.lastIndex
            session.lastActiveAt = now()
        } else {
            sessions[currentIndex].lastActiveAt = now()
        }
        return OpenResult(snapshot(), id, switched)
    }

    fun select(tabId: String): BrowserTabsSnapshot? {
        val index = sessions.indexOfFirst { it.id == tabId }
        if (index < 0) return null
        currentIndex = index
        sessions[index].lastActiveAt = now()
        return snapshot()
    }

    /**
     * Closing the home tab is refused. Closing any other tab drops that tab's
     * page state and WebView, and does not touch the shared cookie jar or
     * in-flight downloads.
     */
    fun close(tabId: String): CloseResult? {
        val index = sessions.indexOfFirst { it.id == tabId }
        if (index < 0) return null
        val session = sessions[index]
        if (session.isHome) return null
        val closedWasCurrent = index == currentIndex
        sessions.removeAt(index)
        currentIndex = when {
            closedWasCurrent && index >= sessions.size -> sessions.lastIndex
            closedWasCurrent -> index.coerceAtMost(sessions.lastIndex)
            index < currentIndex -> currentIndex - 1
            else -> currentIndex
        }.coerceAtLeast(0)
        val snap = snapshot()
        return CloseResult(
            snapshot = snap,
            closedId = tabId,
            closedWasCurrent = closedWasCurrent,
            landedOnHome = sessions[currentIndex].isHome,
            clearsCookies = false,
            cancelsDownloads = false,
            releaseWebView = true
        )
    }

    fun navigate(tabId: String, url: String, title: String?) {
        val session = find(tabId) ?: return
        if (!isHttp(url)) return
        if (title != null && title.isNotBlank() && !title.equals("about:blank", true)) {
            session.title = title
        }
        session.url = url
        val history = session.history
        val index = session.historyIndex
        when {
            index in history.indices && history[index] == url -> Unit
            index > 0 && history[index - 1] == url -> session.historyIndex = index - 1
            index >= 0 && index < history.lastIndex && history[index + 1] == url ->
                session.historyIndex = index + 1
            else -> {
                while (history.size > session.historyIndex + 1) {
                    history.removeAt(history.lastIndex)
                }
                history.add(url)
                session.historyIndex = history.lastIndex
            }
        }
        session.url = history.getOrElse(session.historyIndex) { url }
    }

    fun setLoading(tabId: String, progress: Int, loading: Boolean) {
        val session = find(tabId) ?: return
        session.progress = progress.coerceIn(0, 100)
        session.isLoading = loading
    }

    fun recordDetection(tabId: String, videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val session = find(tabId) ?: return false
        if (session.isHome) return false
        if (session.detectedVideoIds.contains(videoId)) return false
        session.detectedVideoIds.add(videoId)
        return true
    }

    fun back(tabId: String, webViewCanGoBack: Boolean?): BackAction {
        val session = find(tabId) ?: return BackAction.EXIT_APP
        if (session.isHome) return BackAction.EXIT_APP
        val canGoBack = webViewCanGoBack ?: (session.historyIndex > 0)
        if (canGoBack) return BackAction.HISTORY
        val webTabs = sessions.count { !it.isHome }
        return if (webTabs <= 1) BackAction.CLOSE_LAST_TO_HOME else BackAction.CLOSE_TAB
    }

    fun shouldGoForward(tabId: String, webViewCanGoForward: Boolean?): Boolean {
        val session = find(tabId) ?: return false
        if (session.isHome) return false
        return webViewCanGoForward ?: (
            session.historyIndex >= 0 && session.historyIndex < session.history.lastIndex
        )
    }

    fun markWebViewLive(tabId: String, live: Boolean) {
        find(tabId)?.webViewLive = live
    }

    fun mediaDisposition(tabId: String): WebViewDisposition {
        val current = sessions.getOrNull(currentIndex) ?: return WebViewDisposition.PAUSE
        return if (current.id == tabId) WebViewDisposition.RESUME else WebViewDisposition.PAUSE
    }

    /**
     * Called when a tab's fragment is leaving the screen but the tab itself
     * stays open. The current tab is never released. Background WebViews beyond
     * [maxLiveWebViews] (including the visible one) are released oldest-first.
     */
    fun dispositionOnDetach(tabId: String): WebViewDisposition {
        val current = sessions.getOrNull(currentIndex)
        if (current?.id == tabId) return WebViewDisposition.PAUSE
        val backgroundLive = sessions.filter { it.webViewLive && it.id != current?.id && !it.isHome }
        val budget = (maxLiveWebViews - 1).coerceAtLeast(0)
        if (backgroundLive.size <= budget) return WebViewDisposition.PAUSE
        val oldest = backgroundLive.minByOrNull { it.lastActiveAt } ?: return WebViewDisposition.PAUSE
        return if (oldest.id == tabId) WebViewDisposition.RELEASE else WebViewDisposition.PAUSE
    }

    private fun find(tabId: String): Session? = sessions.find { it.id == tabId }

    private fun isHttp(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private class Session(
        val id: String,
        var url: String,
        var title: String,
        val isHome: Boolean,
        val history: MutableList<String> = mutableListOf(),
        var historyIndex: Int = -1,
        var isLoading: Boolean = false,
        var progress: Int = 0,
        val detectedVideoIds: MutableList<String> = mutableListOf(),
        var webViewLive: Boolean = false,
        var lastActiveAt: Long = 0L
    ) {
        fun toState(): TabState {
            return TabState(
                id = id,
                url = url,
                title = title,
                isHome = isHome,
                isLoading = isLoading,
                progress = progress,
                history = history.toList(),
                historyIndex = historyIndex,
                detectedVideoIds = detectedVideoIds.toList(),
                webViewLive = webViewLive
            )
        }
    }

    companion object {
        const val HOME_ID = "home"
        const val DEFAULT_MAX_LIVE_WEBVIEWS = 3
    }
}

enum class OpenMode {
    FOREGROUND,
    BACKGROUND
}

enum class BackAction {
    HISTORY,
    CLOSE_TAB,
    CLOSE_LAST_TO_HOME,
    EXIT_APP
}

enum class WebViewDisposition {
    RESUME,
    PAUSE,
    RELEASE
}

data class TabState(
    val id: String,
    val url: String,
    val title: String,
    val isHome: Boolean,
    val isLoading: Boolean,
    val progress: Int,
    val history: List<String>,
    val historyIndex: Int,
    val detectedVideoIds: List<String>,
    val webViewLive: Boolean
)

data class BrowserTabsSnapshot(
    val tabs: List<TabState>,
    val currentIndex: Int,
    val currentId: String
)

data class OpenResult(
    val snapshot: BrowserTabsSnapshot,
    val openedId: String,
    val switched: Boolean
)

data class CloseResult(
    val snapshot: BrowserTabsSnapshot,
    val closedId: String,
    val closedWasCurrent: Boolean,
    val landedOnHome: Boolean,
    val clearsCookies: Boolean,
    val cancelsDownloads: Boolean,
    val releaseWebView: Boolean
)

data class PopupRequest(
    val url: String?,
    val isUserGesture: Boolean,
    val sourceUrl: String?
)

enum class PopupVerdict {
    BLOCK,
    OPEN_FOREGROUND,
    DEFER_UNTIL_URL
}

/**
 * Gate for target=_blank and window.open.
 *
 * Unsolicited popups stay blocked. A new tab is opened only for a user gesture
 * whose main-frame URL is http(s) and is not classified as an ad. When the URL
 * is not known yet, the caller must defer and run this same gate before the
 * provisional page becomes a visible tab.
 */
object PopupGate {
    fun decide(
        request: PopupRequest,
        adBlockEnabled: Boolean,
        isChallenge: (String) -> Boolean = { false },
        isAd: (url: String, sourceUrl: String) -> Boolean
    ): PopupVerdict {
        if (!request.isUserGesture) return PopupVerdict.BLOCK
        val url = request.url?.trim().orEmpty()
        if (url.isEmpty()) return PopupVerdict.DEFER_UNTIL_URL
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return PopupVerdict.BLOCK
        }
        if (isChallenge(url)) return PopupVerdict.OPEN_FOREGROUND
        if (adBlockEnabled && isAd(url, request.sourceUrl?.trim().orEmpty())) {
            return PopupVerdict.BLOCK
        }
        return PopupVerdict.OPEN_FOREGROUND
    }
}

/**
 * Service-worker detections are global. They are delivered only to the tab
 * that owned the page when the result was published, and a repeated event id
 * is ignored so a sticky observer cannot copy the hit into another tab.
 */
interface TabSessionSink {
    fun onNavigated(tabId: String, url: String, title: String?)
    fun onLoading(tabId: String, progress: Int, loading: Boolean)
}

object DetectionRouter {
    fun shouldApply(
        ownerTabId: String?,
        subscriberTabId: String,
        eventId: Long,
        lastAppliedEventId: Long
    ): Boolean {
        if (ownerTabId.isNullOrBlank()) return false
        if (ownerTabId != subscriberTabId) return false
        if (eventId == lastAppliedEventId) return false
        return true
    }
}
