package com.myAllVideoBrowser.ui.main.home.browser

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.component.widget.NestedWebView
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupGate
import com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupRequest
import com.myAllVideoBrowser.ui.main.home.browser.tabs.PopupVerdict
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import java.util.concurrent.atomic.AtomicBoolean

class CustomWebChromeClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val dataBinding: FragmentWebTabBinding,
    private val appUtil: AppUtil,
    private val mainActivity: MainActivity,
    private val adBlockEngine: AdBlockEngine
) : WebChromeClient() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onPermissionRequest(request: PermissionRequest?) {
        val resources = request?.resources
        if (resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true ||
            resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
        ) {
            request.deny()
        } else {
            super.onPermissionRequest(request)
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (view == null || resultMsg == null) {
            return false
        }
        val hitTestResult = view.hitTestResult
        val anchorUrl = if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            hitTestResult.extra
        } else {
            null
        }
        val verdict = decidePopup(anchorUrl, isUserGesture, view.url)
        AppLogger.d("ON_CREATE_WINDOW verdict=$verdict url=$anchorUrl gesture=$isUserGesture dialog=$isDialog")
        if (verdict == PopupVerdict.BLOCK) {
            return false
        }

        return try {
            val transport = resultMsg.obj as WebView.WebViewTransport
            val newWebView = NestedWebView(view.context)
            if (verdict == PopupVerdict.DEFER_UNTIL_URL) {
                armProvisionalPopup(view, newWebView)
            }
            transport.webView = newWebView
            if (verdict == PopupVerdict.OPEN_FOREGROUND) {
                tabViewModel.openPageEvent.value = WebTab(
                    webview = newWebView,
                    resultMsg = resultMsg,
                    url = anchorUrl ?: "",
                    title = "Loading...",
                    icon = null
                )
            } else {
                // The URL is not known yet. Deliver it to the provisional client
                // and only then create a tab, so an ad cannot skip the gate.
                resultMsg.sendToTarget()
            }
            true
        } catch (e: Exception) {
            AppLogger.e("Failed to create new WebView window: ${e.message}")
            Toast.makeText(view.context, "WebView provider not available.", Toast.LENGTH_SHORT)
                .show()
            false
        }
    }

    private fun decidePopup(url: String?, isUserGesture: Boolean, sourceUrl: String?): PopupVerdict {
        return PopupGate.decide(
            request = PopupRequest(url, isUserGesture, sourceUrl),
            adBlockEnabled = settingsViewModel.isAdBlockOn.get() == true,
            isChallenge = { CustomWebViewClient.isBotChallengeOrCaptcha(it) },
            isAd = { candidate, source ->
                adBlockEngine.isAd(candidate, source, "main_frame")
            }
        )
    }

    private fun armProvisionalPopup(opener: WebView, popup: WebView) {
        val committed = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        val sourceUrl = opener.url
        popup.settings.javaScriptEnabled = true
        popup.settings.domStorageEnabled = true
        popup.settings.setSupportMultipleWindows(false)
        popup.settings.javaScriptCanOpenWindowsAutomatically = false
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url?.toString().orEmpty()
                return !acceptProvisionalUrl(sourceUrl, popup, url, committed, abandoned)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                if (url.isBlank() || CustomWebViewClient.isBotChallengeOrCaptcha(url)) {
                    return super.shouldInterceptRequest(view, request)
                }
                val resourceType = if (request.isForMainFrame) "main_frame" else "other"
                val blocked = settingsViewModel.isAdBlockOn.get() == true &&
                    adBlockEngine.isAd(url, sourceUrl ?: "", resourceType)
                if (!blocked) {
                    if (request.isForMainFrame) {
                        acceptProvisionalUrl(sourceUrl, popup, url, committed, abandoned)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                if (request.isForMainFrame && !committed.get()) {
                    abandonProvisional(popup, abandoned, committed)
                }
                return CustomWebViewClient.emptyResponse()
            }
        }
    }

    private fun acceptProvisionalUrl(
        sourceUrl: String?,
        popup: WebView,
        url: String,
        committed: AtomicBoolean,
        abandoned: AtomicBoolean
    ): Boolean {
        if (abandoned.get()) return false
        val verdict = decidePopup(url, isUserGesture = true, sourceUrl = sourceUrl)
        if (verdict != PopupVerdict.OPEN_FOREGROUND) {
            if (!committed.get()) {
                abandonProvisional(popup, abandoned, committed)
            }
            return false
        }
        if (!committed.compareAndSet(false, true)) return true
        mainHandler.post {
            if (abandoned.get()) return@post
            val tab = WebTab(url, url, webview = popup, resultMsg = null)
            tab.deferLoadStarted = true
            tabViewModel.openPageEvent.value = tab
        }
        return true
    }

    private fun abandonProvisional(
        popup: WebView,
        abandoned: AtomicBoolean,
        committed: AtomicBoolean
    ) {
        if (committed.get()) return
        if (!abandoned.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                popup.stopLoading()
                popup.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val headers = pageTab.getHeaders() ?: emptyMap()
        val updateTab = WebTab(
            pageTab.getUrl(),
            pageTab.getTitle(),
            icon ?: pageTab.getFavicon(),
            headers,
            view,
            id = pageTab.id
        )
        updateTabEvent.value = updateTab
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        tabViewModel.setProgress(newProgress)
        if (newProgress == 100) {
            tabViewModel.isShowProgress.set(false)
        } else {
            tabViewModel.isShowProgress.set(true)
        }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        super.onShowCustomView(view, callback)
        (mainActivity).requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        dataBinding.webviewContainer.visibility = View.GONE
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar).visibility =
            View.GONE
        (mainActivity).window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.addView(view)
        appUtil.hideSystemUI(mainActivity.window, dataBinding.customView)
        dataBinding.customView.visibility = View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.GONE
    }

    override fun onHideCustomView() {
        super.onHideCustomView()
        dataBinding.customView.removeAllViews()
        dataBinding.webviewContainer.visibility = View.VISIBLE
        dataBinding.customView.visibility = View.GONE
        (mainActivity).window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataBinding.customView.rootView.findViewById<View>(R.id.bottom_bar).visibility =
            View.VISIBLE
        dataBinding.containerBrowser.visibility =
            View.VISIBLE
        mainActivity.requestedOrientation = if (settingsViewModel.isLockPortrait.get()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        appUtil.showSystemUI(mainActivity.window, dataBinding.customView)
    }
}
