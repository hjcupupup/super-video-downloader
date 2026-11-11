package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionTabListener
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.component.widget.NestedWebView
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.CurrentTabIndexProvider
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebChromeClient
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebViewClient
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.HOME_TAB_INDEX
import com.myAllVideoBrowser.ui.main.home.browser.HistoryProvider
import com.myAllVideoBrowser.ui.main.home.browser.PageTabProvider
import com.myAllVideoBrowser.ui.main.home.browser.TAB_INDEX_KEY
import com.myAllVideoBrowser.ui.main.home.browser.TabManagerProvider
import com.myAllVideoBrowser.ui.main.home.browser.WebPostBridge
import com.myAllVideoBrowser.ui.main.home.browser.WorkerEventProvider
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.home.browser.disableWebRtcJs
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileNameCleaner
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

class WebTabFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = WebTabFragment()
    }

    private lateinit var suggestionAdapter: TabSuggestionAdapter

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var adBlockEngine: AdBlockEngine

    private lateinit var dataBinding: FragmentWebTabBinding

    private lateinit var tabManagerProvider: TabManagerProvider

    private lateinit var pageTabProvider: PageTabProvider

    private lateinit var historyProvider: HistoryProvider

    private lateinit var workerEventProvider: WorkerEventProvider

    private lateinit var currentTabIndexProvider: CurrentTabIndexProvider

    private lateinit var tabViewModel: WebTabViewModel

    private lateinit var videoDetectionTabViewModel: VideoDetectionTabViewModel

    private lateinit var webTab: WebTab

    private var videoToast: Toast? = null

    private var lastWorkerEventId = -1L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val thisTabIndex = requireArguments().getInt(TAB_INDEX_KEY)

        tabManagerProvider = mainActivity.mainViewModel.browserServicesProvider!!
        pageTabProvider = mainActivity.mainViewModel.browserServicesProvider!!
        historyProvider = mainActivity.mainViewModel.browserServicesProvider!!
        workerEventProvider = mainActivity.mainViewModel.browserServicesProvider!!
        currentTabIndexProvider = mainActivity.mainViewModel.browserServicesProvider!!

        webTab = pageTabProvider.getPageTab(thisTabIndex)
        val browserViewModel = ViewModelProvider(
            requireParentFragment(),
            viewModelFactory
        )[com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel::class.java]
        val tabStore = browserViewModel.tabStores.store(webTab.id)
        tabViewModel = ViewModelProvider(tabStore, viewModelFactory)[WebTabViewModel::class]
        videoDetectionTabViewModel =
            ViewModelProvider(tabStore, viewModelFactory)[VideoDetectionTabViewModel::class]
        browserViewModel.tabStores.rememberDetection(webTab.id, videoDetectionTabViewModel)
        videoDetectionTabViewModel.settingsModel = mainActivity.settingsViewModel
        videoDetectionTabViewModel.webTabModel = tabViewModel
        videoDetectionTabViewModel.onVideoAccepted = { info ->
            browserViewModel.onDetected(webTab.id, info.id)
        }
        if (videoDetectionTabViewModel.initialUrl.isEmpty()) {
            videoDetectionTabViewModel.initialUrl = webTab.getUrl()
        }

        tabViewModel.openPageEvent = tabManagerProvider.getOpenTabEvent()
        tabViewModel.closePageEvent = tabManagerProvider.getCloseTabEvent()
        tabViewModel.thisTabIndex.set(thisTabIndex)
        tabViewModel.tabId = webTab.id
        tabViewModel.sessionSink = browserViewModel
        tabViewModel.start()
        videoDetectionTabViewModel.start()

        AppLogger.d("onCreate Webview::::::::: ${webTab.getUrl()} $savedInstanceState")
        suggestionAdapter =
            TabSuggestionAdapter(requireContext(), mutableListOf(), suggestionListener)

        recreateWebView(savedInstanceState)

        dataBinding = FragmentWebTabBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserMenuButton, false)

            viewModel = tabViewModel
            browserMenuListener = tabListener
            settingsViewModel = mainActivity.settingsViewModel
            videoTabVModel = videoDetectionTabViewModel

            etSearch.setAdapter(suggestionAdapter)
            etSearch.addTextChangedListener(onInputTabChangeListener)
            this.etSearch.imeOptions = EditorInfo.IME_ACTION_DONE
            this.etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    this.etSearch.clearFocus()
                    viewModel?.viewModelScope?.launch {
                        delay(400)
                        tabViewModel.loadPage((this@apply.etSearch as EditText).text.toString())
                    }
                    false
                } else false
            }

            ivCloseTab.clipToOutline = true
            ivGoForward.clipToOutline = true
            ivGoBack.clipToOutline = true
            ivCloseRefresh.clipToOutline = true

            Glide.with(this@WebTabFragment).asGif().load(R.drawable.loading_floating)
                .into(loadingWavy)
            loadingWavy.clipToOutline = true

            configureWebView(this)
        }

        tabViewModel.userAgent.set(
            webTab.getWebView()?.settings?.userAgentString
                ?: BrowserFragment.MOBILE_USER_AGENT
        )

        val message = webTab.getMessage()
        if (message != null) {
            message.sendToTarget()
            webTab.flushMessage()
        } else if (!webTab.restoredFromState && !webTab.deferLoadStarted) {
            val currentUrl = webTab.getWebView()?.url
            val target = webTab.getUrl()
            if ((currentUrl.isNullOrBlank() || currentUrl == "about:blank") && target.startsWith("http")) {
                tabViewModel.loadPage(target)
            }
        }
        webTab.restoredFromState = false
        if (webTab.pendingAddressFocus) {
            webTab.pendingAddressFocus = false
            tabViewModel.changeTabFocus(true)
        }
        val currentId = browserViewModel.currentWebTab()?.id
        if (webTab.id == currentId) {
            webTab.getWebView()?.onResume()
        } else {
            webTab.getWebView()?.onPause()
        }

        return dataBinding.root
    }

    override fun shareWebLink() {
        val link = webTab.getWebView()?.url
        if (link != null) {
            shareLink(link)
        }
    }

    override fun bookmarkCurrentUrl() {
        val webview = webTab.getWebView()
        val url = webview?.url
        val favicon = webview?.favicon
        val name = webview?.title

        if (url == null) {
            return
        }

        mainActivity.mainViewModel.bookmark(
            url,
            name ?: Uri.parse(url).host.toString(),
            favicon
        )
    }

    override fun setIsDesktop(isDesktop: Boolean) {
        super.setIsDesktop(isDesktop)
        setUserAgentIsDesktop(isDesktop)
        webTab.getWebView()?.reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (!outState.isEmpty) {
            webTab.getWebView()?.saveState(outState)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null && !savedInstanceState.isEmpty) {
            webTab.getWebView()?.restoreState(savedInstanceState)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        handleIndexChangeEvent()
        handleLoadPageEvent()
        handleChangeTabFocusEvent()
        handleWorkerEvent()
        handleOpenDetectedVideos()
        handleVideoPushed()
        tabViewModel.start()
        videoDetectionTabViewModel.start()
    }

    override fun onPause() {
        AppLogger.d("onPause Webview::::::::: ${webTab.getUrl()}")
        super.onPause()
        onWebViewPause()
    }

    override fun onResume() {
        AppLogger.d("onResume Webview::::::::: ${webTab.getUrl()}")
        super.onResume()
        onWebViewResume()
    }

    override fun onDestroy() {
        AppLogger.d("onDestroy Webview::::::::: ${webTab.getUrl()}")
        retainOrReleaseWebView()
        tabManagerProvider.getTabsListChangeEvent()
            .removeOnPropertyChangedCallback(tabsListChangeListener)
        super.onDestroy()
    }

    fun hideCustomViewIfShowing(): Boolean {
        if (!::dataBinding.isInitialized) return false
        if (dataBinding.customView.visibility != View.VISIBLE) return false
        webTab.getWebView()?.webChromeClient?.onHideCustomView()
        return true
    }

    private fun handleOpenDetectedVideos() {
        videoDetectionTabViewModel.showDetectedVideosEvent.observe(viewLifecycleOwner) {
            navigateToDownloads()
        }
    }

    private fun handleVideoPushed() {
        videoDetectionTabViewModel.videoPushedEvent.observe(viewLifecycleOwner) {
            onVideoPushed()
        }
    }

    private fun onVideoPushed() {
        showToastVideoFound()

        val isDownloadsVisible = isDetectedVideosTabFragmentVisible()
        val isCond = !tabViewModel.isDownloadDialogShown.get() && !isDownloadsVisible
        if (context != null && mainActivity.settingsViewModel.getVideoAlertState()
                .get() && isCond
        ) {
            lifecycleScope.launch(Dispatchers.Main) {
                showAlertVideoFound()
            }
        }
    }

    private fun onVideoPreviewPropagate(
        videoInfo: VideoInfo, format: String, isForce: Boolean
    ) {
        AppLogger.d(
            "onPreviewVideo: ${videoInfo.formats}  $format"
        )
        // start your activity by passing the intent
        startActivity(
            Intent(
                requireContext(), VideoPlayerActivity::class.java
            ).apply {
                // you can add values(if any) to pass to the next class or avoid using `.apply`
                val currFormat = videoInfo.formats.allFormats().filter {
                    it.format?.contains(
                        format
                    ) ?: false
                }

                putExtra(VideoPlayerFragment.VIDEO_NAME, videoInfo.title)
                if (currFormat.isNotEmpty()) {
                    val headers = currFormat.first().httpHeaders?.let {
                        JSONObject(
                            currFormat.first().httpHeaders ?: emptyMap<String, String>()
                        ).toString()
                    } ?: "{}"

                    putExtra(
                        VideoPlayerFragment.VIDEO_URL, currFormat.first().url
                    )
                    val headersFinal = if (isForce) "{}" else headers
                    putExtra(
                        VideoPlayerFragment.VIDEO_HEADERS, headersFinal
                    )
                }
            })
    }

    private fun onVideoDownloadPropagate(
        videoInfo: VideoInfo, videoTitle: String, format: String
    ) {
        val info = videoInfo.copy(
            id = UUID.randomUUID().toString(),
            title = FileNameCleaner.cleanFileName(videoTitle),
            formats = VideFormatEntityList(videoInfo.formats.allFormats().filter {
                it.format?.contains(
                    format
                ) ?: false
            })
        )

        mainActivity.mainViewModel.downloadVideoEvent.value = info

        context?.let {
            Toast.makeText(
                it, it.getString(R.string.download_started), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var boundWebViewGeneration = 0

    private fun recreateWebView(savedInstanceState: Bundle?) {
        try {
            if (webTab.getWebView() == null) {
                webTab.setWebView(NestedWebView(requireContext()))
                val state = webTab.savedState ?: savedInstanceState
                if (state != null && !state.isEmpty) {
                    webTab.getWebView()?.restoreState(state)
                    webTab.savedState = null
                    webTab.restoredFromState = true
                }
            } else {
                (webTab.getWebView()?.parent as? ViewGroup)?.removeView(webTab.getWebView())
            }
            boundWebViewGeneration = ++webTab.webViewGeneration
            ViewModelProvider(requireParentFragment(), viewModelFactory)
                .get(com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel::class.java)
                .markWebViewLive(webTab.id, true)
        } catch (e: Exception) {
            AppLogger.e("Failed to recreate WebView: ${e.message}")
            context?.let {
                Toast.makeText(
                    it,
                    "WebView provider not available. Please ensure Android System WebView is installed and enabled.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(fragmentWebTabBinding: FragmentWebTabBinding) {
        val currentWebView = this.webTab.getWebView() ?: return

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                currentWebView,
                disableWebRtcJs,
                setOf("*")
            )
        }

        val webViewClient = CustomWebViewClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            videoDetectionTabViewModel,
            historyProvider.getHistoryVModel(),
            okHttpProxyClient,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            proxyController,
            adBlockEngine
        )

        val chromeClient = CustomWebChromeClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            fragmentWebTabBinding,
            appUtil,
            mainActivity,
            adBlockEngine
        )

        currentWebView.webChromeClient = chromeClient
        currentWebView.webViewClient = webViewClient

        val webSettings = currentWebView.settings

        currentWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        currentWebView.isScrollbarFadingEnabled = true

        webSettings.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            allowContentAccess = true
            allowFileAccess = true
            offscreenPreRaster = false
            displayZoomControls = false
            builtInZoomControls = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            useWideViewPort = true
            domStorageEnabled = true
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            if (mainActivity.settingsViewModel.isDesktopMode.get()) {
                userAgentString = BrowserFragment.DESKTOP_USER_AGENT
            }
        }
        currentWebView.setOnCreateContextMenuListener { menu, v, _ ->
            val webView = v as WebView
            val hitTestResult = webView.hitTestResult

            if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                val url = hitTestResult.extra
                if (!url.isNullOrBlank()) {
                    menu.setHeaderTitle(url)
                    menu.add(0, 1, 0, getString(R.string.open_in_new_tab)).setOnMenuItemClickListener {
                        openLinkFromPage(url, background = false)
                        true
                    }
                    menu.add(0, 2, 1, getString(R.string.open_in_background_tab)).setOnMenuItemClickListener {
                        openLinkFromPage(url, background = true)
                        true
                    }
                }
            }
        }

        currentWebView.addJavascriptInterface(
            shouldInterceptPostRequests,
            WebPostBridge.BRIDGE_NAME
        )

        fragmentWebTabBinding.webviewContainer.addView(
            currentWebView,
            LinearLayout.LayoutParams(-1, -1)
        )
    }

    private val onInputTabChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()

            tabViewModel.showTabSuggestions()
            tabViewModel.tabPublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    private val suggestionListener = object : SuggestionTabListener {
        override fun onItemClicked(suggestion: HistoryItem) {
            tabViewModel.loadPage(suggestion.url)
        }
    }

    private fun handleChangeTabFocusEvent() {
        var value = -1
        tabViewModel.changeTabFocusEvent.observe(viewLifecycleOwner) { isFocus ->
            isFocus.let {
                if (it) {
                    val oldValue = value
                    val start = dataBinding.etSearch.selectionStart
                    val end = dataBinding.etSearch.selectionEnd
                    value = (start + end) / 2
                    if (oldValue == value) {
                        dataBinding.etSearch.selectAll()

                    }
                    tabViewModel.isTabInputFocused.set(true)
                    appUtil.showSoftKeyboard(dataBinding.etSearch)
                } else {
                    tabViewModel.isTabInputFocused.set(false)
                    appUtil.hideSoftKeyboard(
                        dataBinding.etSearch
                    )
                }
            }
        }
    }

    private fun handleLoadPageEvent() {
        tabViewModel.loadPageEvent.observe(viewLifecycleOwner) { tab ->
            if (tab.getUrl().startsWith("http")) {
                webTab.getWebView()?.stopLoading()
                webTab.getWebView()?.loadUrl(tab.getUrl())
            }
        }
    }

    private fun handleWorkerEvent() {
        workerEventProvider.getWorkerM3u8MpdEvent().observe(viewLifecycleOwner) { event ->
            if (!com.myAllVideoBrowser.ui.main.home.browser.tabs.DetectionRouter.shouldApply(
                    event.ownerTabId,
                    webTab.id,
                    event.eventId,
                    lastWorkerEventId
                )
            ) {
                return@observe
            }
            lastWorkerEventId = event.eventId
            val state = event.state
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                lifecycleScope.launch(Dispatchers.IO) {
                    videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
                }
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.remove("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
            }
            if (state is DownloadButtonStateLoading) {
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.add("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateLoading())
            }
            if (state is DownloadButtonStateCanNotDownload) {
                val loadings = videoDetectionTabViewModel.m3u8LoadingList.get()
                loadings?.remove("m3u8")
                videoDetectionTabViewModel.m3u8LoadingList.set(loadings?.toMutableSet())
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateCanNotDownload())
            }
        }

        workerEventProvider.getWorkerMP4Event().observe(viewLifecycleOwner) { state ->
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                AppLogger.d("Worker MP4 event CanDownload: ${state.info}")
                lifecycleScope.launch(Dispatchers.IO) {
                    videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
                }
            } else {
                AppLogger.d("Worker MP4 event state: $state")
            }
        }
    }

    private fun handleIndexChangeEvent() {
        tabManagerProvider.getTabsListChangeEvent()
            .addOnPropertyChangedCallback(tabsListChangeListener)
    }

    private val tabsListChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val tabs = tabManagerProvider.getTabsListChangeEvent().get()
            val webTab = tabs?.find { it.id == webTab.id }
            val index = tabs?.indexOf(webTab)
            if (index != null && index in tabs.indices) {
                tabViewModel.thisTabIndex.set(index)
            }
        }
    }

    private fun onWebViewPause() {
        val webView = webTab.getWebView() ?: return
        webView.onPause()
        webView.evaluateJavascript(BrowserFragment.PAUSE_MEDIA_JS, null)
    }

    private fun onWebViewResume() {
        webTab.getWebView()?.onResume()
    }

    private val tabListener = object : BrowserListener {
        override fun onBrowserMenuClicked() {
            showPopupMenu()
        }

        override fun onBrowserReloadClicked() {
            var url = webTab.getWebView()?.url
            var urlWasChange = false

            if (url?.contains("m.facebook") == true) {
                url = url.replace("m.facebook", "www.facebook")
                urlWasChange = true
                val isDesktop = mainActivity.settingsViewModel.isDesktopMode.get()
                if (!isDesktop) {
                    mainActivity.settingsViewModel.setIsDesktopMode(true)
                }
            }

            val userAgent =
                webTab.getWebView()?.settings?.userAgentString ?: tabViewModel.userAgent.get()
                ?: BrowserFragment.MOBILE_USER_AGENT
            if (url != null) {
                videoDetectionTabViewModel.viewModelScope.launch(videoDetectionTabViewModel.executorReload) {
                    videoDetectionTabViewModel.onReloadPage(url, userAgent)
                }

                if (url.contains("www.facebook") && urlWasChange) {
                    tabViewModel.openPage(url)
                    tabViewModel.closeTab(webTab)
                } else {
                    tabViewModel.onPageReload(webTab.getWebView())
                }
            }
        }


        override fun onTabCloseClicked() {
            tabViewModel.closeTab(webTab)
            videoDetectionTabViewModel.cancelAllCheckJobs()
        }

        override fun onBrowserStopClicked() {
            tabViewModel.onPageStop(webTab.getWebView())
        }

        override fun onBrowserBackClicked() {
            (parentFragment as? BrowserFragment)?.handleBrowserBack(fromSystem = false)
        }

        override fun onBrowserForwardClicked() {
            (parentFragment as? BrowserFragment)?.handleBrowserForward()
        }
        
        override fun onShowLogsClicked() {
            navigateToDetectionLogs()
        }
    }

    private fun getWebViewClientCompat(webView: WebView?): CustomWebViewClient? {
        return try {
            val getWebViewClientMethod = WebView::class.java.getMethod("getWebViewClient")
            val client = getWebViewClientMethod.invoke(webView) as? CustomWebViewClient
            client
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun showAlertVideoFound() {
        if (!tabViewModel.isDownloadDialogShown.get()) {
            tabViewModel.isDownloadDialogShown.set(true)
            val client = getWebViewClientCompat(webTab.getWebView())

            client?.videoAlert =
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.video_found)
            client?.videoAlert?.setOnDismissListener {
                client.videoAlert = null
            }
            client?.videoAlert?.setMessage(R.string.whatshould)?.setPositiveButton(
                R.string.view
            ) { dialog, _ ->
                navigateToDownloads()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNeutralButton(R.string.dontshow) { dialog, _ ->
                mainActivity.settingsViewModel.setShowVideoAlertOff()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNegativeButton(R.string.all_text_cancel) { dialog, _ ->
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.show()
        }
    }

    private fun openLinkFromPage(url: String, background: Boolean) {
        val source = webTab.getWebView()?.url ?: webTab.getUrl()
        val verdict = com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupGate.decide(
            request = com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupRequest(
                url = url,
                isUserGesture = true,
                sourceUrl = source
            ),
            adBlockEnabled = mainActivity.settingsViewModel.isAdBlockOn.get() == true,
            isChallenge = { CustomWebViewClient.isBotChallengeOrCaptcha(it) },
            isAd = { candidate, page -> adBlockEngine.isAd(candidate, page, "main_frame") }
        )
        if (verdict != com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupVerdict.OPEN_FOREGROUND) {
            Toast.makeText(requireContext(), R.string.popup_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = WebTab(url, url)
        tab.openInBackground = background
        tabViewModel.openPageEvent.value = tab
        if (background) {
            Toast.makeText(requireContext(), R.string.opened_in_background, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUserAgentIsDesktop(isDesktop: Boolean) {
        val settings = webTab.getWebView()?.settings
        if (isDesktop) {
            settings?.userAgentString = BrowserFragment.DESKTOP_USER_AGENT
        } else {
            settings?.userAgentString = null
        }
    }

    private fun showToastVideoFound() {
        val context = context

        if (context != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                videoToast?.cancel()
                videoToast = Toast.makeText(
                    context, context.getString(R.string.video_found), Toast.LENGTH_SHORT
                )
                videoToast?.show()
            }, 1)
        }
    }

    private fun retainOrReleaseWebView() {
        val webView = webTab.getWebView() ?: return
        if (boundWebViewGeneration != webTab.webViewGeneration) return
        val browserViewModel = runCatching {
            ViewModelProvider(requireParentFragment(), viewModelFactory)
                .get(com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel::class.java)
        }.getOrNull()
        val tabStillOpen = browserViewModel?.findTab(webTab.id) != null
        val finishing = activity?.isFinishing == true
        val changingConfig = activity?.isChangingConfigurations == true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.onPause()
        val release = finishing || !tabStillOpen || changingConfig ||
            browserViewModel?.dispositionOnDetach(webTab.id) ==
            com.myAllVideoBrowser.ui.main.home.browser.tabs.WebViewDisposition.RELEASE
        if (!release && tabStillOpen) {
            browserViewModel?.markWebViewLive(webTab.id, true)
            return
        }
        if (tabStillOpen && !finishing) {
            val state = Bundle()
            webView.saveState(state)
            webTab.savedState = state
        }
        webTab.webViewGeneration++
        webTab.setWebView(null)
        browserViewModel?.markWebViewLive(webTab.id, false)
        try {
            webView.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun navigateToDownloads() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                val fragment = DetectedVideosTabFragment.newInstance()
                fragment.detectedVideosTabViewModel = videoDetectionTabViewModel
                fragment.candidateFormatListener = downloadListener
                transaction.add(it.id, fragment, "DOWNLOADS_TAB")
                transaction.addToBackStack("DOWNLOADS_TAB")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (_: ClassCastException) {
            AppLogger.e("Can't get the fragment manager with this")
        }
    }

    private fun isDetectedVideosTabFragmentVisible(): Boolean {
        val fragmentManager = requireActivity().supportFragmentManager
        val fragment =
            fragmentManager.findFragmentByTag("DOWNLOADS_TAB") as? DetectedVideosTabFragment
        return fragment != null && fragment.isAdded && fragment.isVisible && fragment.isResumed
    }

    private val downloadListener = object : DownloadTabListener {
        override fun onCancel() {
            mainActivity.supportFragmentManager.popBackStack()
        }

        override fun onPreviewVideo(
            videoInfo: VideoInfo, format: String, isForce: Boolean
        ) {
            onVideoPreviewPropagate(videoInfo, format, isForce)
        }

        override fun onDownloadVideo(
            videoInfo: VideoInfo, format: String, videoTitle: String
        ) {
            onVideoDownloadPropagate(videoInfo, videoTitle, format)
        }

        override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
            val formats =
                videoDetectionTabViewModel.selectedFormats.get()?.toMutableMap() ?: mutableMapOf()
            formats[videoInfo.id] = format
            videoDetectionTabViewModel.selectedFormats.set(formats)
        }

        override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
            val foundFormat = videoInfo.formats.allFormats().find { thisFormat ->
                thisFormat.format?.contains(format) == true
            }
            if (foundFormat == null) {
                return false
            }

            ShareCompat.IntentBuilder(mainActivity).setType("text/plain")
                .setChooserTitle("Share Link")
                .setText(foundFormat.url).startChooser()
            return true
        }
    }

    private val shouldInterceptPostRequests = WebPostBridge { url, body ->
        if (CustomWebViewClient.isBotChallengeOrCaptcha(url)) {
            return@WebPostBridge false
        }

        val isAdBlockOn = mainActivity.settingsViewModel.isAdBlockOn.get()
        val isAd = isAdBlockOn &&
                adBlockEngine.isAd(
                    url,
                    tabViewModel.getTabTextInput().get() ?: "",
                    "xmlhttprequest"
                )
        if (isAd) {
            AppLogger.d("AdBlock (POST): Blocked $url")
        }
        isAd
    }
}
