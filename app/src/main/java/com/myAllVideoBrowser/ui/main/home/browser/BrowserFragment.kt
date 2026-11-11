package com.myAllVideoBrowser.ui.main.home.browser

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.*
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.GravityCompat
import androidx.databinding.Observable
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentBrowserBinding
import com.myAllVideoBrowser.ui.component.adapter.WebTabsAdapter
import com.myAllVideoBrowser.ui.component.adapter.WebTabsListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.GlobalVideoDetectionModel
import com.myAllVideoBrowser.ui.main.home.browser.homeTab.BrowserHomeFragment
import com.myAllVideoBrowser.ui.main.home.browser.tabs.BackAction
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFragment
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.*
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


interface BrowserServicesProvider : TabManagerProvider, PageTabProvider, HistoryProvider,
    WorkerEventProvider, CurrentTabIndexProvider

interface TabManagerProvider {
    fun getOpenTabEvent(): SingleLiveEvent<WebTab>

    fun getCloseTabEvent(): SingleLiveEvent<WebTab>

    fun getUpdateTabEvent(): SingleLiveEvent<WebTab>

    fun getTabsListChangeEvent(): ObservableField<List<WebTab>>
}

interface PageTabProvider {
    fun getPageTab(position: Int): WebTab
}

interface HistoryProvider {
    fun getHistoryVModel(): HistoryViewModel
}

interface WorkerEventProvider {
    fun getWorkerM3u8MpdEvent(): MutableLiveData<WorkerDetectionEvent>

    fun getWorkerMP4Event(): MutableLiveData<DownloadButtonState>
}

interface CurrentTabIndexProvider {
    fun getCurrentTabIndex(): ObservableInt
}

const val HOME_TAB_INDEX = 0

const val TAB_INDEX_KEY = "TAB_INDEX_KEY"

//@OpenForTesting
class BrowserFragment : BaseFragment(), BrowserServicesProvider {

    companion object {
        fun newInstance() = BrowserFragment()
        const val PAUSE_MEDIA_JS =
            "(function(){var nodes=document.querySelectorAll('video,audio');for(var i=0;i<nodes.length;i++){try{nodes[i].pause();}catch(e){}}})();"

        var DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // TODO different agents for different androids
        var MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var tabsAdapter: TabsFragmentStateAdapter

    private lateinit var drawerAdapter: WebTabsAdapter

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    @VisibleForTesting
    internal lateinit var dataBinding: FragmentBrowserBinding

    private lateinit var browserViewModel: BrowserViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var historyModel: HistoryViewModel

    private lateinit var settingsModel: SettingsViewModel

    private lateinit var videoDetectionModel: GlobalVideoDetectionModel

    private val compositeDisposable = CompositeDisposable()

    private var backPressedOnce = false

    private val buttonStateCallback = object :
        Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            lifecycleScope.launch(Dispatchers.Main) {
                val state = videoDetectionModel.downloadButtonState.get() ?: return@launch
                browserViewModel.workerM3u8MpdEvent.value =
                    browserViewModel.nextWorkerDetection(state)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        AppLogger.d("Permissions for writing isGranted: $isGranted")
    }

    private val serviceWorkerClient = object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            if (CustomWebViewClient.isBotChallengeOrCaptcha(url)) {
                return super.shouldInterceptRequest(request)
            }

            if (!::settingsModel.isInitialized || !::videoDetectionModel.isInitialized) {
                return super.shouldInterceptRequest(request)
            }

            val isM3u8Check = settingsModel.isCheckIfEveryRequestOnM3u8.get()
            val isMp4Check = settingsModel.getIsCheckEveryRequestOnMp4Video().get()
            val isCheckOnAudio = settingsModel.isCheckOnAudio.get()

            if (isM3u8Check || isMp4Check) {
                val requestWithCookies = request.let { resourceRequest ->
                    try {
                        CookieUtils.webResourceRequestToOkHttpRequest(
                            resourceRequest
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }

                val contentType = VideoUtils.getContentTypeByUrl(
                    url, requestWithCookies?.headers, okHttpProxyClient
                )

                if (contentType == ContentType.MPD || contentType == ContentType.M3U8 || url.contains(
                        ".m3u8"
                    ) || url.contains(
                        ".mpd"
                    ) || url.contains(".txt")
                ) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (requestWithCookies != null && isM3u8Check) {
                            videoDetectionModel.verifyLinkStatus(requestWithCookies, "", true)
                        }
                    }
                } else if (contentType == ContentType.VIDEO && isMp4Check || contentType == ContentType.AUDIO && isCheckOnAudio) {
                    videoDetectionModel.checkRegularVideoOrAudio(
                        requestWithCookies,
                        isCheckOnAudio,
                        isMp4Check
                    )
                }
            }

            return super.shouldInterceptRequest(request)
        }
    }

    inner class TabsFragmentStateAdapter(private var webTabsRoutes: List<WebTab>) :
        FragmentStateAdapter(this) {
        fun setRoutes(newRoutes: List<WebTab>) {
            webTabsRoutes = newRoutes

            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = webTabsRoutes.size

        override fun getItemId(position: Int): Long {
            return webTabsRoutes[position].id.hashCode().toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            val webTab = webTabsRoutes.find { it.id.hashCode().toLong() == itemId }
            return webTab != null
        }

        override fun createFragment(position: Int): Fragment {
            if (position == HOME_TAB_INDEX) {
                return createHomeTabFragment()
            }

            return createTabFragment(position)
        }
    }

    private fun createHomeTabFragment(): Fragment {
        return BrowserHomeFragment.newInstance()
    }

    override fun getOpenTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.openPageEvent
    }

    override fun getCloseTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.closePageEvent
    }

    override fun getUpdateTabEvent(): SingleLiveEvent<WebTab> {
        return browserViewModel.updateWebTabEvent
    }

    override fun getTabsListChangeEvent(): ObservableField<List<WebTab>> {
        return browserViewModel.tabs
    }

    override fun getPageTab(position: Int): WebTab {
        val list = browserViewModel.tabs.get() ?: listOf(WebTab.HOME_TAB)
        if (position in list.indices) {
            return list[position]
        }
        return WebTab("error", "error")
    }

    private fun createTabFragment(index: Int): Fragment {
        val fragment = WebTabFragment.newInstance().apply {
            val args = Bundle().apply {
                putInt(TAB_INDEX_KEY, index)
            }
            arguments = args
        }

        return fragment
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        browserViewModel = ViewModelProvider(this, viewModelFactory)[BrowserViewModel::class.java]
        historyModel = mainActivity.historyViewModel
        videoDetectionModel =
            ViewModelProvider(this, viewModelFactory)[GlobalVideoDetectionModel::class.java]

        videoDetectionModel.settingsModel = mainActivity.settingsViewModel
        browserViewModel.settingsModel = mainActivity.settingsViewModel
        settingsModel = mainActivity.settingsViewModel

        if (ProxyManager.isProxySupported()) {
            try {
                val swController = ServiceWorkerController.getInstance()
                swController.setServiceWorkerClient(serviceWorkerClient)
                swController.serviceWorkerWebSettings.allowContentAccess = true
            } catch (t: Throwable) {
                AppLogger.e("ServiceWorker initialization failed: ${t.message}")
            }
        } else {
            AppLogger.d("Skipping ServiceWorker init: WebView version too old.")
        }

        mainActivity.mainViewModel.browserServicesProvider = this

        tabsAdapter = TabsFragmentStateAdapter(emptyList())

        drawerAdapter = WebTabsAdapter(emptyList(), tabsListener)

        val webTabsManagerLayout =
            WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        webTabsManagerLayout.reverseLayout = true

        val color = getThemeBackgroundColor()

        dataBinding = FragmentBrowserBinding.inflate(inflater, container, false).apply {
            this.viewPager.adapter = tabsAdapter
            this.viewPager.setSwipeThreshold(500)
            this.viewPager.setOnGoThroughListener(onGoThroughListener)
            this.viewPager.isUserInputEnabled = false
            this.tabsList.layoutManager = webTabsManagerLayout
            this.tabsList.adapter = drawerAdapter
            this.drawerLayoutContent.setBackgroundColor(color)

            this.viewModel = browserViewModel
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            onBackPressed()
        }

        videoDetectionModel.downloadButtonState.addOnPropertyChangedCallback(buttonStateCallback)


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    mainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        browserViewModel.start()
        handlePressWebTabEvent()
        handleOpenTabEvent()
        handleCloseWebTabEventEvent()
        handleOpenNavDrawerEvent()
        handleUpdateWebTabEventEvent()
        handleBrowserBarEvents()
        checkIsPowerSaveMode()
    }

    override fun onDestroyView() {
        videoDetectionModel.downloadButtonState.removeOnPropertyChangedCallback(buttonStateCallback)
        super.onDestroyView()
        browserViewModel.stop()
        videoDetectionModel.stop()
        compositeDisposable.clear()
    }

    override fun getHistoryVModel(): HistoryViewModel {
        return this.historyModel
    }

    override fun getWorkerM3u8MpdEvent(): MutableLiveData<WorkerDetectionEvent> {
        return browserViewModel.workerM3u8MpdEvent
    }

    override fun getWorkerMP4Event(): MutableLiveData<DownloadButtonState> {
        return browserViewModel.workerMP4Event
    }

    override fun getCurrentTabIndex(): ObservableInt {
        return browserViewModel.currentTab
    }

    private val tabsListener = object : WebTabsListener {
        override fun onCloseTabClicked(webTab: WebTab) {

            browserViewModel.closePageEvent.value = webTab
        }

        override fun onSelectTabClicked(webTab: WebTab) {
            browserViewModel.selectWebTabEvent.value = webTab
        }
    }

    private fun handlePressWebTabEvent() {
        browserViewModel.selectWebTabEvent.observe(viewLifecycleOwner) { webTab ->
            browserViewModel.selectTab(webTab)
            showCurrentTab()
            if (dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                dataBinding.drawerLayout.close()
            }
        }
    }

    private fun handleOpenTabEvent() {
        browserViewModel.openPageEvent.observe(viewLifecycleOwner) { webTab ->
            if (webTab.id == WebTab.HOME_TAB.id) {
                browserViewModel.selectTab(WebTab.HOME_TAB)
                showCurrentTab()
                return@observe
            }
            browserViewModel.addTab(webTab)
            showCurrentTab()
        }
    }

    private fun handleCloseWebTabEventEvent() {
        browserViewModel.closePageEvent.observe(viewLifecycleOwner) { webTab ->
            if (webTab.isHome()) return@observe
            releaseClosedTab(webTab)
            browserViewModel.closeTab(webTab)
            showCurrentTab()
        }
    }

    private fun handleUpdateWebTabEventEvent() {
        browserViewModel.updateWebTabEvent.observe(viewLifecycleOwner) { incoming ->
            val existing = browserViewModel.findTab(incoming.id) ?: return@observe
            incoming.getFavicon()?.let { existing.setFavicon(it) }
            incoming.getWebView()?.let { existing.setWebView(it) }
            if (incoming.getTitle().isNotEmpty()) {
                existing.setTitle(incoming.getTitle())
            }
            if (incoming.getUrl().startsWith("http")) {
                browserViewModel.onNavigated(existing.id, incoming.getUrl(), incoming.getTitle())
            } else {
                browserViewModel.tabs.set(browserViewModel.tabs.get().orEmpty().toList())
            }
            dataBinding.viewPager.currentItem = browserViewModel.currentTab.get()
        }
    }

    private fun handleBrowserBarEvents() {
        browserViewModel.bottomBackEvent.observe(viewLifecycleOwner) {
            handleBrowserBack(fromSystem = false)
        }
        browserViewModel.bottomForwardEvent.observe(viewLifecycleOwner) {
            handleBrowserForward()
        }
        browserViewModel.newTabEvent.observe(viewLifecycleOwner) {
            if (dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                dataBinding.drawerLayout.close()
            }
            openBlankTab()
        }
    }

    fun openBlankTab() {
        val tab = WebTab("", getString(R.string.new_tab_title))
        tab.pendingAddressFocus = true
        browserViewModel.openPageEvent.value = tab
    }

    fun handleBrowserBack(fromSystem: Boolean) {
        if (requireActivity().supportFragmentManager.backStackEntryCount > 0) {
            requireActivity().supportFragmentManager.popBackStack()
            return
        }
        val resumedTab = childFragmentManager.fragments
            .filterIsInstance<WebTabFragment>()
            .firstOrNull { it.isResumed }
        if (resumedTab?.hideCustomViewIfShowing() == true) {
            return
        }

        val rootPagerIndex = mainActivity.mainViewModel.currentItem.get() ?: 0
        if (rootPagerIndex > 0) {
            mainActivity.mainViewModel.currentItem.set(HOME_TAB_INDEX)
            return
        }

        val current = browserViewModel.currentWebTab()
        val webView = current?.getWebView()
        val canGoBack = when {
            current == null || current.isHome() -> false
            webView != null -> webView.canGoBack()
            else -> null
        }
        when (browserViewModel.backAction(current?.id, canGoBack)) {
            BackAction.HISTORY -> {
                if (webView != null && webView.canGoBack()) {
                    current?.id?.let { browserViewModel.tabStores.detection(it)?.cancelAllCheckJobs() }
                    webView.goBack()
                }
            }

            BackAction.CLOSE_TAB, BackAction.CLOSE_LAST_TO_HOME -> {
                if (current != null) {
                    browserViewModel.closePageEvent.value = current
                }
            }

            BackAction.EXIT_APP -> {
                if (!fromSystem) return
                if (backPressedOnce) {
                    requireActivity().finish()
                    return
                }
                backPressedOnce = true
                Toast.makeText(
                    requireContext(),
                    R.string.press_back_again_to_exit,
                    Toast.LENGTH_SHORT
                ).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    backPressedOnce = false
                }, 2000)
            }
        }
    }

    fun handleBrowserForward() {
        val current = browserViewModel.currentWebTab() ?: return
        val webView = current.getWebView() ?: return
        if (webView.canGoForward()) {
            browserViewModel.tabStores.detection(current.id)?.cancelAllCheckJobs()
            webView.goForward()
        }
    }

    /**
     * Drop the closed tab's WebView and its detection ViewModel.
     * The shared CookieManager and WorkManager downloads are left alone.
     */
    private fun releaseClosedTab(webTab: WebTab) {
        webTab.webViewGeneration++
        val webView = webTab.getWebView()
        webTab.setWebView(null)
        webTab.savedState = null
        if (webView != null) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.onPause()
            try {
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
        browserViewModel.markWebViewLive(webTab.id, false)
        browserViewModel.tabStores.detection(webTab.id)?.cancelAllCheckJobs()
        browserViewModel.tabStores.clear(webTab.id)
    }

    private fun showCurrentTab() {
        val index = browserViewModel.currentTab.get().coerceAtLeast(0)
        dataBinding.viewPager.currentItem = index
        applyMediaPolicy()
    }

    private fun applyMediaPolicy() {
        val currentId = browserViewModel.currentWebTab()?.id
        browserViewModel.tabs.get().orEmpty().forEach { tab ->
            val webView = tab.getWebView() ?: return@forEach
            if (tab.id == currentId) {
                webView.onResume()
            } else {
                pauseBackgroundWebView(webView)
            }
        }
    }

    private fun pauseBackgroundWebView(webView: android.webkit.WebView) {
        webView.onPause()
        // onPause does not reliably stop HTML media, and pauseTimers() would freeze every tab.
        webView.evaluateJavascript(PAUSE_MEDIA_JS, null)
    }

    private fun showTabsSheet() {
        if (!isAdded) return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_browser_tabs, null)
        val list = sheet.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.sheet_tabs_list)
        var sheetAdapter: WebTabsAdapter? = null
        val sheetListener = object : WebTabsListener {
            override fun onCloseTabClicked(webTab: WebTab) {
                browserViewModel.closePageEvent.value = webTab
                val updated = browserViewModel.tabs.get().orEmpty()
                sheetAdapter?.setData(updated)
                sheetAdapter?.setSelectedTab(browserViewModel.currentTab.get())
                if (updated.size <= 1) {
                    dialog.dismiss()
                }
            }

            override fun onSelectTabClicked(webTab: WebTab) {
                browserViewModel.selectWebTabEvent.value = webTab
                dialog.dismiss()
            }
        }
        sheetAdapter = WebTabsAdapter(browserViewModel.tabs.get().orEmpty(), sheetListener)
        sheetAdapter?.setSelectedTab(browserViewModel.currentTab.get())
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = sheetAdapter
        sheet.findViewById<View>(R.id.sheet_new_tab).setOnClickListener {
            dialog.dismiss()
            openBlankTab()
        }
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun handleOpenNavDrawerEvent() {
        mainViewModel.openNavDrawerEvent.observe(viewLifecycleOwner) {
            val isOpened = dataBinding.drawerLayout.isDrawerOpen(GravityCompat.START)
            if (isOpened) {
                dataBinding.drawerLayout.close()
            } else {
                dataBinding.drawerLayout.open()
            }
        }
    }

    private fun checkIsPowerSaveMode() {
        val context = this.requireContext()
        val pwManager = getSystemService(context, PowerManager::class.java)
        if (pwManager?.isPowerSaveMode == true) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.warning)
                .setMessage(R.string.powerSave).setPositiveButton(
                    R.string.ok
                ) { dialog, _ ->
                    dialog.dismiss()
                }.show()
        }
    }

    private fun onBackPressed() {
        handleBrowserBack(fromSystem = true)
    }

    private val onGoThroughListener = object : OnGoThroughListener {
        override fun onRightGoThrough() {
            val currentTabIndex = browserViewModel.currentTab.get()
            if (currentTabIndex == 0) {
                mainViewModel.currentItem.set((mainViewModel.currentItem.get() ?: 0) + 1)
            }
        }
    }
}
