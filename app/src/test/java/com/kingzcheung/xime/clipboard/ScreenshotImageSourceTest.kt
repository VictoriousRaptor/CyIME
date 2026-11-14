package com.kingzcheung.xime.clipboard

import org.junit.Assert.*
import org.junit.Test

class ScreenshotImageSourceTest {
    @Test fun fixedMediaImagesRemainDirectlyAddressableBeyondTheRecentQueryLimit() {
        val pins = (1..90).mapTo(linkedSetOf()) { "image:content://media/external/images/media/$it" }
        pins += "image:content://media/external_primary/images/media/91"
        val sources = ScreenshotImageSource.pinnedMediaImages(pins)
        assertEquals(91, sources.size)
        assertEquals((1L..91L).toList(), sources.map { it.id })
        assertEquals("content://media/external_primary/images/media/91", sources.last().uri)
        assertEquals(60, ScreenshotImageSource.MAX_QUERY_ROWS)
    }

    @Test fun recentPhotosFollowScreenshotsWithoutRepeatingThem() {
        fun image(id: Int, label: String) = ClipboardImage("content://media/external/images/media/$id", "image/jpeg", id.toLong(), label)
        val shots = listOf(image(9, "截图"), image(5, "截图"))
        val photos = (20 downTo 1).map { image(it, ScreenshotImageSource.PHOTO_LABEL) } + image(9, ScreenshotImageSource.PHOTO_LABEL)
        val shown = ScreenshotImageSource.withRecentPhotos(shots, photos)
        // A screenshot that is also among the newest photos stays one card, labelled as a screenshot.
        assertEquals(1, shown.count { it.uri.endsWith("/9") })
        assertEquals("截图", shown.first { it.uri.endsWith("/9") }.label)
        assertEquals(2 + ScreenshotImageSource.RECENT_PHOTO_LIMIT, shown.size)
        assertEquals(shots, ScreenshotImageSource.withRecentPhotos(shots, emptyList()))
    }

    @Test fun directPinnedLookupRejectsForeignOrMalformedMediaUris() {
        val sources = ScreenshotImageSource.pinnedMediaImages(setOf(
            "text:3", "image:content://other/external/images/media/3",
            "image:content://media/external/video/media/3", "image:content://media/internal/images/media/3",
            "image:content://media/external/images/media/-1", "image:content://media/external/images/media/0",
            "image:content://media/external/images/media/3?x=1", "image:content://media/external/images/media/3/4",
            "image:content://media/external/images/media/9999999999999999999999999999",
            "image:content://app.fileprovider/clipboard_images/saved.png",
        ))
        assertTrue(sources.isEmpty())
    }

    @Test fun samsungAndPixelScreenshotsRemainAvailable() {
        assertTrue(ScreenshotImageSource.matches("Screenshot_20261007_091220_ChatGPT.jpg", "Screenshots", "DCIM/Screenshots/"))
        assertTrue(ScreenshotImageSource.matches("Screenshot_20261007-091220.png", "Screenshots", "Pictures/Screenshots/"))
        assertTrue(ScreenshotImageSource.matches("Screenshot_20261007_091220.jpg", "My captures", "Pictures/My captures/"))
    }

    @Test fun cameraDownloadsAndOcrExportsDoNotBecomeClipboardEntries() {
        for ((name, path) in listOf(
            "20261007_091220.jpg" to "DCIM/Camera/",
            "IMG_20261007_091220.jpg" to "DCIM/Camera/",
            "PXL_20261007_091220123.jpg" to "DCIM/Camera/",
            "camera_screenshot_example.jpg" to "Download/",
            "Screenshot guide.png" to "Download/",
            "Screenshot_guide_20261007.jpg" to "Pictures/",
            "scan_20261007.jpg" to "Pictures/Scans/",
            "IMG_20261007.jpg" to "Pictures/MyScreenshotsBackup/",
        )) assertFalse("Must not import $path$name", ScreenshotImageSource.matches(name, path.trim('/').substringAfterLast('/'), path))
    }

    @Test fun folderEvidenceDoesNotDependOnExtensionOrFullScreenDimensions() {
        assertTrue(ScreenshotImageSource.matches("edited_crop.jpg", "Screenshots", null))
        assertTrue(ScreenshotImageSource.matches("renamed.webp", null, "Pictures/Screenshots/Edited/"))
        assertTrue(ScreenshotImageSource.matches(null, "截屏", null))
        assertTrue(ScreenshotImageSource.matches("1.jpg", "スクリーンショット", null))
    }

    @Test fun datedNamesWorkWithMissingPathMetadataAndLocalizedNames() {
        for (name in listOf("SCREENSHOT_20261007_091220.jpg", "Screen Shot 2026-10-07 at 09.12.20.png",
            "截屏_2026-10-07_091220.jpg", "スクリーンショット 2026-10-07 091220.png")) {
            assertTrue(name, ScreenshotImageSource.matches(name, null, null))
        }
        assertFalse(ScreenshotImageSource.matches(null, null, null))
        assertFalse(ScreenshotImageSource.matches("photo.jpg", null, null))
    }

    @Test fun oldAndroidQueryDoesNotRequestRelativePath() {
        val filter = ScreenshotImageSource.queryFilter("_display_name", "bucket_display_name", null)
        assertFalse(filter.selection.contains("relative_path"))
        assertEquals(filter.arguments.size, filter.selection.count { it == '?' })
    }

    @Test fun modernQueryIncludesPathEvidenceAndEscapesUnderscoreInNamePrefix() {
        val filter = ScreenshotImageSource.queryFilter("_display_name", "bucket_display_name", "relative_path")
        assertTrue(filter.selection.contains("relative_path"))
        assertTrue(filter.arguments.contains("%/screenshots/%"))
        assertTrue(filter.arguments.contains("screen\\_shot%"))
        assertEquals(filter.arguments.size, filter.selection.count { it == '?' })
    }
}
