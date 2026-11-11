package com.myAllVideoBrowser.ui.main.home.browser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabControllerTest {

    private var clock = 0L

    private fun controller(maxLive: Int = 3) = BrowserTabController(
        maxLiveWebViews = maxLive,
        now = { ++clock }
    )

    @Test
    fun foregroundOpenSwitchesAndBackgroundOpenDoesNot() {
        val tabs = controller()
        val first = tabs.open(id = "a", url = "https://a.example/one", title = "A", mode = OpenMode.FOREGROUND)
        assertTrue(first.switched)
        assertEquals("a", first.snapshot.currentId)

        val background = tabs.open(
            id = "b",
            url = "https://b.example/two",
            title = "B",
            mode = OpenMode.BACKGROUND
        )
        assertFalse(background.switched)
        assertEquals("a", background.snapshot.currentId)
        assertEquals(listOf("home", "a", "b"), background.snapshot.tabs.map { it.id })
        assertEquals("https://b.example/two", background.snapshot.tabs.first { it.id == "b" }.url)
    }

    @Test
    fun eachTabKeepsItsOwnHistoryAndLoadingState() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/1", title = "A", mode = OpenMode.FOREGROUND)
        tabs.navigate("a", "https://a.example/2", "A2")
        tabs.setLoading("a", 40, true)

        tabs.open(id = "b", url = "https://b.example/1", title = "B", mode = OpenMode.FOREGROUND)
        tabs.navigate("b", "https://b.example/2", "B2")
        tabs.setLoading("b", 15, true)

        tabs.select("a")
        val snap = tabs.snapshot()
        val a = snap.tabs.first { it.id == "a" }
        val b = snap.tabs.first { it.id == "b" }
        assertEquals("a", snap.currentId)
        assertEquals(listOf("https://a.example/1", "https://a.example/2"), a.history)
        assertEquals(1, a.historyIndex)
        assertEquals(40, a.progress)
        assertTrue(a.isLoading)
        assertEquals(listOf("https://b.example/1", "https://b.example/2"), b.history)
        assertEquals(1, b.historyIndex)
        assertEquals(15, b.progress)
        assertTrue(b.isLoading)
    }

    @Test
    fun backAndForwardStayInsideTheTabThatMoved() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/1", title = "A", mode = OpenMode.FOREGROUND)
        tabs.navigate("a", "https://a.example/2", "A2")
        tabs.navigate("a", "https://a.example/3", "A3")
        tabs.open(id = "b", url = "https://b.example/1", title = "B", mode = OpenMode.BACKGROUND)

        assertEquals(BackAction.HISTORY, tabs.back("a", webViewCanGoBack = true))
        tabs.navigate("a", "https://a.example/2", "A2")
        assertTrue(tabs.shouldGoForward("a", webViewCanGoForward = null))
        tabs.navigate("a", "https://a.example/3", "A3")

        val snap = tabs.snapshot()
        assertEquals(2, snap.tabs.first { it.id == "a" }.historyIndex)
        assertEquals(listOf("https://b.example/1"), snap.tabs.first { it.id == "b" }.history)
        assertEquals(0, snap.tabs.first { it.id == "b" }.historyIndex)
    }

    @Test
    fun backAtDocumentStartClosesCurrentOrLastTabWithoutExitingFromAPage() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/", title = "A", mode = OpenMode.FOREGROUND)
        tabs.open(id = "b", url = "https://b.example/", title = "B", mode = OpenMode.FOREGROUND)

        assertEquals(BackAction.CLOSE_TAB, tabs.back("b", webViewCanGoBack = false))
        val closedB = tabs.close("b")
        assertEquals("a", closedB!!.snapshot.currentId)
        assertFalse(closedB.landedOnHome)

        assertEquals(BackAction.CLOSE_LAST_TO_HOME, tabs.back("a", webViewCanGoBack = false))
        val closedA = tabs.close("a")
        assertTrue(closedA!!.landedOnHome)
        assertEquals("home", closedA.snapshot.currentId)
        assertEquals(BackAction.EXIT_APP, tabs.back("home", webViewCanGoBack = null))
    }

    @Test
    fun webViewHistoryWinsWhenTheViewIsStillAlive() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/1", title = "A", mode = OpenMode.FOREGROUND)
        tabs.navigate("a", "https://a.example/2", "A2")
        assertEquals(BackAction.HISTORY, tabs.back("a", webViewCanGoBack = null))
        assertEquals(BackAction.CLOSE_LAST_TO_HOME, tabs.back("a", webViewCanGoBack = false))
    }

    @Test
    fun closeCurrentBackgroundAndLastTab() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/", title = "A", mode = OpenMode.FOREGROUND)
        tabs.open(id = "b", url = "https://b.example/", title = "B", mode = OpenMode.FOREGROUND)
        tabs.open(id = "c", url = "https://c.example/", title = "C", mode = OpenMode.FOREGROUND)
        tabs.select("b")

        val closedBackground = tabs.close("a")
        assertEquals("b", closedBackground!!.snapshot.currentId)
        assertEquals(listOf("home", "b", "c"), closedBackground.snapshot.tabs.map { it.id })
        assertFalse(closedBackground.clearsCookies)
        assertFalse(closedBackground.cancelsDownloads)
        assertTrue(closedBackground.releaseWebView)

        val closedCurrent = tabs.close("b")
        assertEquals("c", closedCurrent!!.snapshot.currentId)
        assertEquals(listOf("home", "c"), closedCurrent.snapshot.tabs.map { it.id })

        val closedLast = tabs.close("c")
        assertTrue(closedLast!!.landedOnHome)
        assertEquals(listOf("home"), closedLast.snapshot.tabs.map { it.id })
        assertNull(tabs.close("home"))
    }

    @Test
    fun closingTheLastTabInTheStripSelectsThePreviousTab() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/", title = "A", mode = OpenMode.FOREGROUND)
        tabs.open(id = "b", url = "https://b.example/", title = "B", mode = OpenMode.FOREGROUND)
        val closed = tabs.close("b")
        assertEquals("a", closed!!.snapshot.currentId)
    }

    @Test
    fun detectionsBelongToTheTabThatRecordedThem() {
        val tabs = controller()
        tabs.open(id = "a", url = "https://a.example/", title = "A", mode = OpenMode.FOREGROUND)
        tabs.open(id = "b", url = "https://b.example/", title = "B", mode = OpenMode.BACKGROUND)
        assertTrue(tabs.recordDetection("a", "video-a"))
        assertTrue(tabs.recordDetection("b", "video-b"))
        assertFalse(tabs.recordDetection("a", "video-a"))
        assertFalse(tabs.recordDetection("home", "video-home"))
        assertFalse(tabs.recordDetection("missing", "video-x"))

        val snap = tabs.snapshot()
        assertEquals(listOf("video-a"), snap.tabs.first { it.id == "a" }.detectedVideoIds)
        assertEquals(listOf("video-b"), snap.tabs.first { it.id == "b" }.detectedVideoIds)
        assertTrue(snap.tabs.first { it.id == "home" }.detectedVideoIds.isEmpty())
    }

    @Test
    fun serviceWorkerDetectionIsNotCopiedOntoOtherTabs() {
        assertTrue(DetectionRouter.shouldApply("a", "a", eventId = 3, lastAppliedEventId = 2))
        assertFalse(DetectionRouter.shouldApply("a", "b", eventId = 3, lastAppliedEventId = -1))
        assertFalse(DetectionRouter.shouldApply(null, "a", eventId = 3, lastAppliedEventId = -1))
        assertFalse(DetectionRouter.shouldApply("a", "a", eventId = 3, lastAppliedEventId = 3))
    }

    @Test
    fun popupGateKeepsAdPopupsBlockedAndAllowsRealLinks() {
        val notAd = { _: String, _: String -> false }
        val ads = { url: String, _: String -> url.contains("ads.example") }

        assertEquals(
            PopupVerdict.BLOCK,
            PopupGate.decide(PopupRequest("https://example.com", false, "https://site"), true, isAd = notAd)
        )
        assertEquals(
            PopupVerdict.BLOCK,
            PopupGate.decide(PopupRequest("javascript:alert(1)", true, "https://site"), true, isAd = notAd)
        )
        assertEquals(
            PopupVerdict.BLOCK,
            PopupGate.decide(PopupRequest("intent://scan/#Intent;end", true, "https://site"), true, isAd = notAd)
        )
        assertEquals(
            PopupVerdict.BLOCK,
            PopupGate.decide(PopupRequest("https://ads.example/pop", true, "https://site"), true, isAd = ads)
        )
        assertEquals(
            PopupVerdict.OPEN_FOREGROUND,
            PopupGate.decide(PopupRequest("https://videos.example/watch", true, "https://site"), true, isAd = ads)
        )
        assertEquals(
            PopupVerdict.DEFER_UNTIL_URL,
            PopupGate.decide(PopupRequest(null, true, "https://site"), true, isAd = ads)
        )
        assertEquals(
            PopupVerdict.OPEN_FOREGROUND,
            PopupGate.decide(
                PopupRequest("https://challenges.example/captcha", true, "https://site"),
                adBlockEnabled = true,
                isChallenge = { it.contains("captcha") },
                isAd = { _, _ -> true }
            )
        )
        assertEquals(
            PopupVerdict.OPEN_FOREGROUND,
            PopupGate.decide(PopupRequest("https://ads.example/pop", true, "https://site"), false, isAd = ads)
        )
        assertEquals(
            PopupVerdict.BLOCK,
            PopupGate.decide(PopupRequest("https://example.com", false, "https://site"), false, isAd = notAd)
        )
    }

    @Test
    fun deferredPopupStillHasToPassTheSameGate() {
        val first = PopupGate.decide(
            PopupRequest(url = null, isUserGesture = true, sourceUrl = "https://site"),
            adBlockEnabled = true,
            isAd = { _, _ -> false }
        )
        assertEquals(PopupVerdict.DEFER_UNTIL_URL, first)

        val revealedAd = PopupGate.decide(
            PopupRequest("https://ads.example/landing", true, "https://site"),
            adBlockEnabled = true,
            isAd = { url, _ -> url.contains("ads.example") }
        )
        assertEquals(PopupVerdict.BLOCK, revealedAd)

        val revealedPage = PopupGate.decide(
            PopupRequest("https://videos.example/new", true, "https://site"),
            adBlockEnabled = true,
            isAd = { url, _ -> url.contains("ads.example") }
        )
        assertEquals(PopupVerdict.OPEN_FOREGROUND, revealedPage)
    }

    @Test
    fun backgroundMediaPausesAndOldestDetachedWebViewIsReleased() {
        val tabs = controller(maxLive = 3)
        tabs.open(id = "a", url = "https://a.example/", title = "A", mode = OpenMode.FOREGROUND)
        tabs.open(id = "b", url = "https://b.example/", title = "B", mode = OpenMode.FOREGROUND)
        tabs.open(id = "c", url = "https://c.example/", title = "C", mode = OpenMode.FOREGROUND)
        tabs.open(id = "d", url = "https://d.example/", title = "D", mode = OpenMode.FOREGROUND)
        listOf("a", "b", "c", "d").forEach { tabs.markWebViewLive(it, true) }

        assertEquals(WebViewDisposition.RESUME, tabs.mediaDisposition("d"))
        assertEquals(WebViewDisposition.PAUSE, tabs.mediaDisposition("a"))
        assertEquals(WebViewDisposition.PAUSE, tabs.mediaDisposition("c"))
        assertEquals(WebViewDisposition.RELEASE, tabs.dispositionOnDetach("a"))
        assertEquals(WebViewDisposition.PAUSE, tabs.dispositionOnDetach("d"))

        tabs.markWebViewLive("a", false)
        assertEquals(WebViewDisposition.PAUSE, tabs.dispositionOnDetach("b"))
    }

    @Test
    fun blankTabHasNoHistoryUntilTheUserNavigates() {
        val tabs = controller()
        val opened = tabs.open(id = "blank", url = "", title = "New tab", mode = OpenMode.FOREGROUND)
        val blank = opened.snapshot.tabs.first { it.id == "blank" }
        assertTrue(blank.history.isEmpty())
        assertEquals(BackAction.CLOSE_LAST_TO_HOME, tabs.back("blank", webViewCanGoBack = null))
        tabs.navigate("blank", "https://duckduckgo.com/?q=cats", "cats")
        assertEquals(listOf("https://duckduckgo.com/?q=cats"), tabs.snapshot().tabs.first { it.id == "blank" }.history)
    }
}
