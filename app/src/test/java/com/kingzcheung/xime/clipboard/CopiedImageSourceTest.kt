package com.kingzcheung.xime.clipboard

import org.junit.Assert.*
import org.junit.Test

class CopiedImageSourceTest {
    @Test fun anExplicitlyCopiedCameraPhotoDoesNotNeedScreenshotMetadata() {
        val photo = CopiedImageSource("content://media/external/images/media/42", "image/jpeg")
        assertEquals(photo, copiedImageSource(listOf(photo), "image/jpeg"))
    }

    @Test fun aMixedClipFindsTheImageInsteadOfTreatingItsFirstTextUriAsAnImage() {
        val text = CopiedImageSource("content://notes/1", "text/plain")
        val image = CopiedImageSource("content://screenshots/2", "image/png")
        assertEquals(image, copiedImageSource(listOf(text, image), "image/png"))
    }
    @Test fun genericClipDescriptionStillAcceptsAnImageFromItsProvider() {
        val image = CopiedImageSource("content://screenshots/2", "image/jpeg")
        assertEquals(image, copiedImageSource(listOf(image), null))
    }
    @Test fun temporarilyUnavailableMimeCanUseTheExplicitImageDescription() {
        val unavailable = CopiedImageSource("content://screenshots/2", null)
        assertEquals(unavailable, copiedImageSource(listOf(unavailable), "image/png"))
        assertNull(copiedImageSource(listOf(unavailable), null))
        assertNull(copiedImageSource(listOf(CopiedImageSource("file:///private/screenshot.png", "image/png")), "image/png"))
        assertNull(copiedImageSource(listOf(CopiedImageSource("content://notes/1", "text/plain")), "image/png"))
    }

    @Test fun aMultiImageCopyKeepsEveryImageInOrder() {
        val first = CopiedImageSource("content://media/external/images/media/1", "image/jpeg")
        val text = CopiedImageSource("content://notes/1", "text/plain")
        val second = CopiedImageSource("content://media/external/images/media/2", "image/png")
        val unresolved = CopiedImageSource("content://media/external/images/media/3", null)
        assertEquals(listOf(first, second, unresolved), copiedImageSources(listOf(first, text, second, unresolved), "image/jpeg"))
        assertEquals(listOf(first, second), copiedImageSources(listOf(first, text, second, unresolved), null))
        assertEquals(first, copiedImageSource(listOf(text, first, second), "image/jpeg"))
        assertEquals(listOf(first), copiedImageSources(listOf(first, first), "image/jpeg"))
    }
}
