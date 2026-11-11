package com.myAllVideoBrowser.ui.main.home.browser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressLinkResolverTest {
    private val page = "https://videos.example/watch/beacon-1"
    private val thumb = "https://cdn.example/thumbs/beacon-1.jpg"

    @Test
    fun imageInsideLinkUsesAnchorHrefForThePageAndImgSrcForTheImage() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            hitExtra = thumb,
            focusHref = page,
            focusImageSrc = thumb,
            imageRef = thumb,
        )
        assertEquals(page, target.pageUrl)
        assertEquals(thumb, target.imageUrl)
        assertTrue(target.offersPage)
        assertTrue(target.offersImage)
    }

    @Test
    fun hitExtraImageIsNotPromotedToAPageWhenTheAnchorHrefIsMissing() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            hitExtra = thumb,
            focusHref = null,
            focusImageSrc = null,
            imageRef = thumb,
        )
        assertNull(target.pageUrl)
        assertEquals(thumb, target.imageUrl)
        assertFalse(target.offersPage)
        assertTrue(target.offersImage)
    }

    @Test
    fun bareImageDoesNotInventAPageUrl() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.IMAGE_TYPE,
            hitExtra = thumb,
            focusHref = "",
            focusImageSrc = null,
            imageRef = thumb,
        )
        assertNull(target.pageUrl)
        assertEquals(thumb, target.imageUrl)
        assertFalse(target.offersPage)
    }

    @Test
    fun textLinkHasNoImageAction() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_ANCHOR_TYPE,
            hitExtra = page,
            focusHref = page,
            focusImageSrc = null,
            imageRef = null,
        )
        assertEquals(page, target.pageUrl)
        assertNull(target.imageUrl)
        assertTrue(target.offersPage)
        assertFalse(target.offersImage)
    }

    @Test
    fun textLinkFallsBackToHitExtraWhenFocusHrefIsLate() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_ANCHOR_TYPE,
            hitExtra = page,
            focusHref = null,
            focusImageSrc = null,
            imageRef = null,
        )
        assertEquals(page, target.pageUrl)
        assertFalse(target.offersImage)
    }

    @Test
    fun linkThatPointsAtTheImageItselfIsNotSplitIntoAFakePage() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            hitExtra = thumb,
            focusHref = thumb,
            focusImageSrc = thumb,
            imageRef = thumb,
        )
        assertEquals(thumb, target.pageUrl)
        assertFalse(target.offersImage)
    }

    @Test
    fun nonHttpHrefIsIgnored() {
        val target = LongPressLinkResolver.resolve(
            hitType = LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            hitExtra = thumb,
            focusHref = "javascript:void(0)",
            focusImageSrc = thumb,
            imageRef = thumb,
        )
        assertNull(target.pageUrl)
        assertEquals(thumb, target.imageUrl)
        assertTrue(target.offersImage)
    }

    @Test
    fun severalImagesShareTheCardHrefButKeepTheirOwnImageAddress() {
        val first = LongPressLinkResolver.resolve(
            LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            "https://cdn.example/card/1.jpg",
            page,
            "https://cdn.example/card/1.jpg",
            "https://cdn.example/card/1.jpg",
        )
        val second = LongPressLinkResolver.resolve(
            LongPressLinkResolver.SRC_IMAGE_ANCHOR_TYPE,
            "https://cdn.example/card/2.jpg",
            page,
            "https://cdn.example/card/2.jpg",
            "https://cdn.example/card/2.jpg",
        )
        assertEquals(page, first.pageUrl)
        assertEquals(page, second.pageUrl)
        assertEquals("https://cdn.example/card/1.jpg", first.imageUrl)
        assertEquals("https://cdn.example/card/2.jpg", second.imageUrl)
    }
}
