package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Message
import android.webkit.WebView
import java.util.UUID

class WebTab(
    private var url: String,
    private var title: String?,
    private var icon: Any? = null,
    private val headers: Map<String, String> = emptyMap(),
    private var webview: WebView? = null,
    private var resultMsg: Message? = null,
    var id: String = UUID.randomUUID().toString()
) {
    /** Long-press "open in background" must not change the visible tab. */
    var openInBackground: Boolean = false

    /** Focus the address bar once this blank tab is shown. */
    var pendingAddressFocus: Boolean = false

    /** window.open already started loading this WebView; do not load the URL again. */
    var deferLoadStarted: Boolean = false

    var restoredFromState: Boolean = false

    /** Saved WebView back-forward list, used after the live WebView is released. */
    var savedState: Bundle? = null

    var isLoading: Boolean = false

    var loadingProgress: Int = 0

    /**
     * Incremented when a new owner attaches or the tab explicitly destroys the
     * WebView, so a fragment that is going away cannot destroy a replacement.
     */
    var webViewGeneration: Int = 0

    companion object {
        @SuppressLint("StaticFieldLeak")
        val HOME_TAB = WebTab(
            "",
            "Home Tab",
            id = "home"
        )
    }

    fun getMessage(): Message? {
        return resultMsg
    }

    fun flushMessage() {
        resultMsg = null
    }

    fun getWebView(): WebView? {
        return this.webview
    }

    fun setWebView(webview: WebView?) {
        this.webview = webview
    }

    fun getHeaders(): Map<String, String>? {
        return this.headers
    }

    fun getUrl(): String {
        return this.url
    }

    fun setUrl(value: String) {
        this.url = value
    }

    fun getTitle(): String {
        return this.title ?: ""
    }

    fun setTitle(value: String?) {
        if (!value.isNullOrBlank()) {
            this.title = value
        }
    }

    fun getFavicon(): Any? {
        return icon
    }

    fun setFavicon(value: Any?) {
        if (value != null) {
            this.icon = value
        }
    }

    fun isHome(): Boolean {
        return this.id.contains("home")
    }


    override fun toString(): String {
        return "WebTab(url='$url', title=$title, icon=$icon, headers=$headers, webview=$webview, resultMsg=$resultMsg, id='$id')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebTab

        if (url != other.url) return false
        if (title != other.title) return false
        if (icon != other.icon) return false
        if (headers != other.headers) return false
        if (webview != other.webview) return false
        if (resultMsg != other.resultMsg) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + (webview?.hashCode() ?: 0)
        result = 31 * result + (resultMsg?.hashCode() ?: 0)
        result = 31 * result + (id?.hashCode() ?: 0)
        return result
    }
}