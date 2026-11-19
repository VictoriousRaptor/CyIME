package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.ClipboardImage
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks visible pixels as well as semantics: an exiting lazy item can outlive its semantics. */
class ClipboardCategoryVisualIsolationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rapidlySwitchingTextAndImageCategoriesDoesNotLeaveOutgoingCards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("clipboard-category-ghosting-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(255, 12, 12))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val image = ClipboardImage(Uri.fromFile(file).toString(), "image/png", 100L)
        val imageTag = "clipboard-card:image:${image.uri}"
        val textTag = "clipboard-card:text:1"
        val sentinel = "TEXT_CATEGORY_ONLY_SENTINEL"

        try {
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 450.dp).testTag("fixture")) {
                        ClipboardBoardView(
                            textItems = listOf(ClipboardItem(1L, sentinel, timestamp = 200L)),
                            images = listOf(image),
                            initialImages = false, expanded = true, pins = emptySet(),
                            photoAccess = false, failure = null,
                            onBack = {}, onQuickSend = {}, onSelectText = {}, onSelectImage = {},
                            onSplit = { _, _ -> }, onAddQuick = {}, onPinsChange = {},
                            onRemove = {}, onPhotoAccess = {}, onPick = {},
                            onSystemPaste = {}, onShare = { _, _ -> }, onPullRemote = null,
                        )
                    }
                }
            }

            rule.onNodeWithTag("clipboard-filter:IMAGE").performScrollTo().performClick()
            // Ensure the synthetic thumbnail has decoded, not merely been composed.
            rule.waitUntil(10_000) { redPixelCount() > 100 }
            rule.onNodeWithTag(imageTag, useUnmergedTree = true).assertExists()

            // Capture the first transition frames; waiting for idle after each switch
            // would hide the regression by letting the exiting fade finish.
            rule.mainClock.autoAdvance = false
            repeat(3) {
                rule.onNodeWithTag("clipboard-filter:TEXT").performScrollTo().performClick()
                rule.mainClock.advanceTimeByFrame()
                rule.onNodeWithTag(textTag, useUnmergedTree = true).assertExists()
                rule.onNodeWithTag(imageTag, useUnmergedTree = true).assertDoesNotExist()
                assertTrue("旧图片像素不能残留到文本分类", redPixelCount() == 0)

                rule.onNodeWithTag("clipboard-filter:IMAGE").performScrollTo().performClick()
                rule.mainClock.advanceTimeByFrame()
                rule.onNodeWithTag(imageTag, useUnmergedTree = true).assertExists()
                rule.onNodeWithTag(textTag, useUnmergedTree = true).assertDoesNotExist()
                rule.onNodeWithText(sentinel).assertDoesNotExist()
            }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            file.delete()
        }
    }

    /**
     * Models the first opt-in: text-only history, then screenshots, then gallery photos.
     * The synthetic feeds avoid touching the device's real clipboard or photo library.
     */
    @Test fun enablingScreenshotAndGalleryFeedsDoesNotCarryOldPixelsIntoTextCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun imageFixture(prefix: String, color: Int, timestamp: Long, label: String): Pair<File, ClipboardImage> {
            val file = File.createTempFile(prefix, ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            return file to ClipboardImage(Uri.fromFile(file).toString(), "image/png", timestamp, label)
        }

        val (screenshotFile, screenshot) = imageFixture(
            "clipboard-screenshot-", Color.rgb(255, 12, 12), 400L, "截图")
        val (galleryFile, galleryPhoto) = imageFixture(
            "clipboard-gallery-", Color.rgb(12, 12, 255), 500L, "照片")
        val screenshotsEnabled = mutableStateOf(false)
        val galleryEnabled = mutableStateOf(false)
        val feed = mutableStateOf<List<ClipboardImage>>(emptyList())
        val textTag = "clipboard-card:text:81"
        val screenshotTag = "clipboard-card:image:${screenshot.uri}"
        val galleryTag = "clipboard-card:image:${galleryPhoto.uri}"

        try {
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 450.dp)) {
                        ClipboardBoardView(
                            textItems = listOf(ClipboardItem(81L, "TEXT_ONLY_START", timestamp = 200L)),
                            images = feed.value, initialImages = false, expanded = true,
                            pins = emptySet(), photoAccess = screenshotsEnabled.value, failure = null,
                            onBack = {}, onQuickSend = {}, onSelectText = {}, onSelectImage = {},
                            onSplit = { _, _ -> }, onAddQuick = {}, onPinsChange = {},
                            onRemove = {}, onPhotoAccess = {}, onPick = {},
                            onSystemPaste = {}, onShare = { _, _ -> }, onPullRemote = null,
                            // In list mode text is one column, while IMAGE remains a masonry grid.
                            listLayout = true, galleryPhotosEnabled = galleryEnabled.value,
                        )
                    }
                }
            }
            rule.onNodeWithTag(textTag, useUnmergedTree = true).assertExists()
            rule.runOnIdle {
                screenshotsEnabled.value = true
                feed.value = listOf(screenshot)
            }
            rule.waitUntil(10_000) { redPixelCount() > 100 }
            rule.runOnIdle {
                galleryEnabled.value = true
                feed.value = listOf(galleryPhoto, screenshot)
            }
            rule.waitUntil(10_000) { bluePixelCount() > 100 }
            rule.onNodeWithTag("clipboard-filter:IMAGE").performScrollTo().performClick()
            rule.onNodeWithTag(screenshotTag, useUnmergedTree = true).assertExists()
            rule.onNodeWithTag(galleryTag, useUnmergedTree = true).assertExists()

            // The real failure could persist longer than a normal 180 ms exit transition.
            rule.mainClock.autoAdvance = false
            repeat(3) {
                rule.onNodeWithTag("clipboard-filter:TEXT").performScrollTo().performClick()
                rule.mainClock.advanceTimeByFrame()
                rule.onNodeWithTag(textTag, useUnmergedTree = true).assertExists()
                assertTrue("图片离场第一帧不能覆盖文本", redPixelCount() == 0 && bluePixelCount() == 0)
                rule.mainClock.advanceTimeBy(450L)
                assertTrue("图片残影不能持续到动画结束后", redPixelCount() == 0 && bluePixelCount() == 0)

                rule.onNodeWithTag("clipboard-filter:IMAGE").performScrollTo().performClick()
                rule.mainClock.advanceTimeByFrame()
                rule.onNodeWithTag(screenshotTag, useUnmergedTree = true).assertExists()
                rule.onNodeWithTag(galleryTag, useUnmergedTree = true).assertExists()
                rule.onNodeWithTag(textTag, useUnmergedTree = true).assertDoesNotExist()
            }

            // A source switch inside the same category must drop the outgoing photo too.
            rule.runOnIdle {
                galleryEnabled.value = false
                feed.value = listOf(screenshot)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag(galleryTag, useUnmergedTree = true).assertDoesNotExist()
            assertTrue("关闭相册来源后蓝色照片不能残留", bluePixelCount() == 0)
        } finally {
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            screenshotFile.delete()
            galleryFile.delete()
        }
    }

    private fun bluePixelCount(): Int {
        val snapshot = rule.onNodeWithTag("clipboard-records-region").captureToImage().asAndroidBitmap()
        var count = 0
        for (y in 0 until snapshot.height step 4) {
            for (x in 0 until snapshot.width step 4) {
                val color = snapshot.getPixel(x, y)
                if (Color.blue(color) > 205 && Color.red(color) < 90 && Color.green(color) < 90) count++
            }
        }
        return count
    }

    private fun redPixelCount(): Int {
        val snapshot = rule.onNodeWithTag("clipboard-records-region").captureToImage().asAndroidBitmap()
        var count = 0
        for (y in 0 until snapshot.height step 4) {
            for (x in 0 until snapshot.width step 4) {
                val color = snapshot.getPixel(x, y)
                if (Color.red(color) > 205 && Color.green(color) < 90 && Color.blue(color) < 90) count++
            }
        }
        return count
    }
}
