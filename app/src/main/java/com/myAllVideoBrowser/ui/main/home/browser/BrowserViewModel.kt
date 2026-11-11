package com.myAllVideoBrowser.ui.main.home.browser

import androidx.databinding.*
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.home.browser.tabs.BackAction
import com.myAllVideoBrowser.ui.main.home.browser.tabs.BrowserTabController
import com.myAllVideoBrowser.ui.main.home.browser.tabs.BrowserTabsSnapshot
import com.myAllVideoBrowser.ui.main.home.browser.tabs.CloseResult
import com.myAllVideoBrowser.ui.main.home.browser.tabs.OpenMode
import com.myAllVideoBrowser.ui.main.home.browser.tabs.TabSessionSink
import com.myAllVideoBrowser.ui.main.home.browser.tabs.WebViewDisposition
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import javax.inject.Inject

//@OpenForTesting
class BrowserViewModel @Inject constructor() : BaseViewModel(), TabSessionSink {

    companion object {
        const val SEARCH_URL = "https://duckduckgo.com/?t=ffab&q=%s"

        var instance: BrowserViewModel? = null
    }

    var settingsModel: SettingsViewModel? = null

    val openPageEvent = SingleLiveEvent<WebTab>()

    val closePageEvent = SingleLiveEvent<WebTab>()

    val selectWebTabEvent = SingleLiveEvent<WebTab>()

    val updateWebTabEvent = SingleLiveEvent<WebTab>()

    val workerM3u8MpdEvent = MutableLiveData<WorkerDetectionEvent>()

    val workerMP4Event = MutableLiveData<DownloadButtonState>()

    val progress = ObservableInt(0)

    val tabs = ObservableField(listOf(WebTab.HOME_TAB))

    val currentTab = ObservableInt(HOME_TAB_INDEX)

    val tabCount = ObservableInt(1)

    val bottomBackEvent = SingleLiveEvent<Unit?>()

    val bottomForwardEvent = SingleLiveEvent<Unit?>()

    val showTabsEvent = SingleLiveEvent<Unit?>()

    val newTabEvent = SingleLiveEvent<Unit?>()

    val tabController = BrowserTabController()

    val tabStores = TabViewModelRegistry()

    private val tabById = linkedMapOf(WebTab.HOME_TAB.id to WebTab.HOME_TAB)

    private var detectionEventSeq = 0L

    fun onBottomBackClicked() {
        bottomBackEvent.call()
    }

    fun onBottomForwardClicked() {
        bottomForwardEvent.call()
    }

    fun onShowTabsClicked() {
        showTabsEvent.call()
    }

    fun onNewTabClicked() {
        newTabEvent.call()
    }

    fun addTab(webTab: WebTab) {
        val mode = if (webTab.openInBackground) OpenMode.BACKGROUND else OpenMode.FOREGROUND
        val result = tabController.open(
            id = webTab.id,
            url = webTab.getUrl(),
            title = webTab.getTitle(),
            mode = mode
        )
        tabById[webTab.id] = webTab
        publish(result.snapshot)
    }

    fun selectTab(webTab: WebTab) {
        val snapshot = tabController.select(webTab.id) ?: return
        publish(snapshot)
    }

    fun closeTab(webTab: WebTab): CloseResult? {
        val result = tabController.close(webTab.id) ?: return null
        tabById.remove(webTab.id)
        publish(result.snapshot)
        return result
    }

    override fun onNavigated(tabId: String, url: String, title: String?) {
        tabController.navigate(tabId, url, title)
        publish(tabController.snapshot())
    }

    override fun onLoading(tabId: String, progress: Int, loading: Boolean) {
        tabController.setLoading(tabId, progress, loading)
        tabById[tabId]?.let { tab ->
            tab.isLoading = loading
            tab.loadingProgress = progress
        }
    }

    fun onDetected(tabId: String, videoId: String) {
        tabController.recordDetection(tabId, videoId)
    }

    fun findTab(tabId: String): WebTab? = tabById[tabId]

    fun currentWebTab(): WebTab? = tabById[tabController.snapshot().currentId]

    fun backAction(tabId: String?, webViewCanGoBack: Boolean?): BackAction {
        if (tabId == null) return BackAction.EXIT_APP
        return tabController.back(tabId, webViewCanGoBack)
    }

    fun mediaDisposition(tabId: String): WebViewDisposition {
        return tabController.mediaDisposition(tabId)
    }

    fun dispositionOnDetach(tabId: String): WebViewDisposition {
        return tabController.dispositionOnDetach(tabId)
    }

    fun markWebViewLive(tabId: String, live: Boolean) {
        tabController.markWebViewLive(tabId, live)
    }

    fun nextWorkerDetection(state: DownloadButtonState): WorkerDetectionEvent {
        val current = currentWebTab()
        val owner = if (current == null || current.isHome()) null else current.id
        detectionEventSeq += 1
        return WorkerDetectionEvent(owner, state, detectionEventSeq)
    }

    private fun publish(snapshot: BrowserTabsSnapshot) {
        val ordered = snapshot.tabs.map { state ->
            val tab = tabById.getOrPut(state.id) {
                WebTab(state.url, state.title, id = state.id)
            }
            if (!tab.isHome()) {
                tab.setUrl(state.url)
                tab.setTitle(state.title)
                tab.isLoading = state.isLoading
                tab.loadingProgress = state.progress
            }
            tab
        }
        tabs.set(ordered.toList())
        currentTab.set(snapshot.currentIndex)
        tabCount.set(ordered.size)
    }

    override fun start() {
        instance = this
    }

    override fun stop() {
        instance = null
    }
}

data class WorkerDetectionEvent(
    val ownerTabId: String?,
    val state: DownloadButtonState,
    val eventId: Long
)

class TabViewModelRegistry {
    private val stores = HashMap<String, ViewModelStore>()
    private val detection = HashMap<String, VideoDetectionTabViewModel>()

    fun store(tabId: String): ViewModelStore {
        return stores.getOrPut(tabId) { ViewModelStore() }
    }

    fun rememberDetection(tabId: String, viewModel: VideoDetectionTabViewModel) {
        detection[tabId] = viewModel
    }

    fun detection(tabId: String): VideoDetectionTabViewModel? = detection[tabId]

    fun clear(tabId: String) {
        detection.remove(tabId)
        stores.remove(tabId)?.clear()
    }
}

abstract class DownloadButtonState

class DownloadButtonStateLoading : DownloadButtonState()

class DownloadButtonStateCanDownload(val info: VideoInfo?) : DownloadButtonState()
class DownloadButtonStateCanNotDownload : DownloadButtonState()