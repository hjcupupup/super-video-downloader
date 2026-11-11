package com.myAllVideoBrowser.ui.main.home.browser.tabs

/**
 * Decides which URL a long-press may open.
 *
 * WebView's [android.webkit.WebView.HitTestResult.getExtra] for an image inside
 * a link is the image address, not the link. The page address has to come from
 * the anchor href (requestFocusNodeHref "url"). An image address is offered
 * only as a separate action, and a bare image never invents a page URL.
 */
object LongPressLinkResolver {
    const val IMAGE_TYPE = 5
    const val SRC_ANCHOR_TYPE = 7
    const val SRC_IMAGE_ANCHOR_TYPE = 8

    data class Target(
        val pageUrl: String?,
        val imageUrl: String?,
    ) {
        val offersPage: Boolean
            get() = !pageUrl.isNullOrBlank()

        /** Shown only when the image is a different address from the page link. */
        val offersImage: Boolean
            get() = !imageUrl.isNullOrBlank() && imageUrl != pageUrl
    }

    fun resolve(
        hitType: Int,
        hitExtra: String?,
        focusHref: String?,
        focusImageSrc: String?,
        imageRef: String?,
    ): Target {
        val href = httpUrl(focusHref)
        val image = httpUrl(focusImageSrc) ?: httpUrl(imageRef)
        val extra = httpUrl(hitExtra)
        val pageUrl = when {
            href != null -> href
            // A plain text link reports its href in the hit extra.
            hitType == SRC_ANCHOR_TYPE -> extra
            else -> null
        }
        val imageUrl = when {
            image != null -> image
            // Image hits, including an image wrapped by a link, put the bitmap
            // address in the hit extra. That must not become the page URL.
            hitType == IMAGE_TYPE || hitType == SRC_IMAGE_ANCHOR_TYPE -> extra
            else -> null
        }
        return Target(pageUrl = pageUrl, imageUrl = imageUrl)
    }

    fun httpUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        return null
    }
}
