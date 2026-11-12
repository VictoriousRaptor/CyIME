package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.ClipboardCard
import com.kingzcheung.xime.clipboard.ClipboardImage
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Runs the production board long-press path, including its real action callbacks. */
class ClipboardBoardContextMenuTest {
    @get:Rule val rule = createComposeRule()
    private val text = ClipboardItem(12, "保留完整的剪贴板文字", timestamp = 20)
    private val textRecords = mutableStateOf(listOf(text))
    private lateinit var image: ClipboardImage
    private lateinit var imageFile: File
    private val darkAppearance = mutableStateOf(true)
    private val glassEnabled = mutableStateOf(true)
    private val pins = mutableStateOf(emptySet<String>())
    private var split: Pair<String, Long>? = null
    private var edited: Triple<Long, String, String>? = null
    private var removed = emptyList<ClipboardCard>()
    private var pasted = 0
    private val boardWidth = mutableStateOf(360)
    private val boardHeight = mutableStateOf(400)
    private val boardFontScale = mutableStateOf(1f)

    @Before fun createLoadableImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        imageFile = File.createTempFile("clipboard-menu-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = IMAGE_RED
            canvas.drawRect(0f, 0f, 80f, 120f, paint)
            paint.color = IMAGE_TEAL
            canvas.drawRect(80f, 0f, 160f, 120f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 18f
            paint.isFakeBoldText = true
            canvas.drawText("CyIME", 16f, 42f, paint)
            canvas.drawText("IMAGE", 16f, 70f, paint)
            imageFile.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        image = ClipboardImage(Uri.fromFile(imageFile).toString(), "image/png", 10, "测试图片")
    }

    @After fun removeOnlyTheTestImage() {
        if (::imageFile.isInitialized && imageFile.exists()) {
            assertTrue("test image must be removed", imageFile.delete())
        }
    }

    // As in XIME and the earliest CyIME: the board dims, the record stays in place and the action
    // card opens right beside it (placement across columns and widths: ClipboardAnchoredMenuTest).
    @Test fun textActionsOpenBesideThePressedRecord() {
        showBoard()
        val toolbar = bounds("clipboard-more")
        val record = bounds("clipboard-card:text:12")
        open("text:12")
        rule.onNodeWithText("记录操作").assertDoesNotExist()
        val menu = bounds("clipboard-item-menu")
        assertTrue("menu beside its record: $menu vs $record", menu.overlaps(record) ||
            kotlin.math.abs(menu.left - record.right) <= 12f || kotlin.math.abs(record.left - menu.right) <= 12f)
        assertEquals(toolbar, bounds("clipboard-more"))
        assertEquals(record, bounds("clipboard-card:text:12"))
        rule.onNodeWithText("分词").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(text.text to text.id, split) }
        rule.onNodeWithTag("clipboard-item-menu").assertDoesNotExist()
        open("text:12")
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(Triple(text.id, text.text, text.code), edited) }
    }

    @Test fun imageUsesTheSameAnchoredMenuAndKeepsPinAndConfirmedDelete() {
        showBoard(dark = false)
        val key = "image:${image.uri}"
        val original = rule.onNodeWithTag("clipboard-card:$key")
        awaitImage(original) { containsFixtureColors(it) }
        val record = bounds("clipboard-card:$key")
        open(key)
        assertEquals(record, bounds("clipboard-card:$key"))
        for (label in listOf("分词", "快捷", "编辑")) rule.onNodeWithText(label).assertDoesNotExist()
        rule.onNodeWithText("固定").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(setOf(key), pins.value) }
        open(key)
        rule.onNodeWithText("取消固定").assertExists()
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(removed.isEmpty()) }
        rule.onNodeWithText("删除 1 条图片记录？").assertExists()
        rule.onNodeWithText("只移除剪贴板记录，保留原始图片。").assertExists()
        rule.onNodeWithTag("clipboard-confirm-delete").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(ClipboardCard.Image(image)), removed); assertTrue(pins.value.isEmpty()) }
    }

    @Test fun shortLargeTextPanelKeepsEveryActionReachableAndLeavesTheRecordInPlace() {
        showBoard(width = 240, height = 280, fontScale = 2f)
        val record = bounds("clipboard-card:text:12")
        open("text:12")
        val board = bounds("clipboard-board")
        for (label in listOf("固定", "分词", "编辑", "多选", "删除")) {
            val row = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertInside(board, row, label)
            assertEquals(record, bounds("clipboard-card:text:12"))
        }
        rule.onNodeWithText("多选").performScrollTo().performClick()
        rule.onNodeWithText("已选 1").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-item-menu").assertDoesNotExist()
    }

    @Test fun defaultPhoneHeightKeepsTheRecordVisibleAndAllActionsReachable() {
        showBoard(width = 360, height = 232)
        pins.value = setOf("text:12")
        rule.waitForIdle()
        val record = bounds("clipboard-card:text:12")
        open("text:12")
        val menu = bounds("clipboard-item-menu")
        assertEquals(record, bounds("clipboard-card:text:12"))
        for (label in listOf("取消固定", "分词", "编辑", "多选", "删除")) {
            val row = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertInside(menu, row, label)
        }
        saveBoard("clipboard-context-phone-compact.png")
    }

    @Test fun wideMenusUseFreeSpaceAndTheMoreButtonAnchorWithoutMovingToolbar() {
        showBoard(width = 800, height = 360)
        val toolbar = bounds("clipboard-controls")
        val textRecord = bounds("clipboard-card:text:12")
        open("text:12")
        assertEquals(toolbar, bounds("clipboard-controls"))
        saveBoard("clipboard-context-wide-compact.png")
        dismissOverRecord("clipboard-item-menu", textRecord)
        rule.onNodeWithTag("clipboard-more").performClick()
        val button = bounds("clipboard-more")
        val menu = bounds("clipboard-more-menu")
        assertTrue("settings menu follows longest label", menu.width < 200f)
        assertTrue("more menu opens below its button when the space is free", menu.top >= button.bottom)
        assertEquals(button.right, menu.right, 1f)
        assertEquals(toolbar, bounds("clipboard-controls"))
        for (tag in listOf("clipboard-select", "clipboard-pick", "clipboard-photos", "clipboard-clear", "clipboard-close-menu")) {
            assertInside(menu, rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot, tag)
        }
        saveBoard("clipboard-more-wide-compact.png")
    }

    @Test fun textConfirmationHasNoImageAdviceAndOutsideTapOnlyDismisses() {
        showBoard()
        val record = bounds("clipboard-card:text:12")
        open("text:12")
        dismissOverRecord("clipboard-item-menu", record)
        open("text:12")
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.onNodeWithText("删除 1 条文本记录？").assertExists()
        rule.onNodeWithText("原始图片", substring = true).assertDoesNotExist()
        rule.onNodeWithText("相册", substring = true).assertDoesNotExist()
        rule.runOnIdle { assertTrue(removed.isEmpty()) }
        dismissOverRecord("clipboard-delete-menu", record)
        rule.runOnIdle { assertTrue(removed.isEmpty()); assertEquals(0, pasted) }
        rule.onNodeWithTag("clipboard-more").performClick()
        dismissOverRecord("clipboard-more-menu", record)
        rule.runOnIdle { assertTrue(removed.isEmpty()); assertEquals(0, pasted) }
    }

    @Test fun mixedSelectionNamesBothKindsAndKeepsDeletionAsASecondStep() {
        showBoard()
        rule.onNodeWithTag("clipboard-more").performClick()
        rule.onNodeWithTag("clipboard-select").performScrollTo().performClick()
        // No fixed records here: the one select-all row above the records takes both.
        rule.onNodeWithTag("clipboard-select-recent").performClick()
        rule.onNodeWithTag("clipboard-delete").performClick()
        rule.onNodeWithText("删除 1 条文本和 1 条图片记录？").assertExists()
        rule.onNodeWithText("只移除剪贴板记录，保留原始图片。").assertExists()
        rule.runOnIdle { assertTrue(removed.isEmpty()); assertEquals(0, pasted) }
        rule.onNodeWithTag("clipboard-cancel-delete").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(removed.isEmpty()) }
    }

    @Test fun allThreeMenusRenderOneGlassSurfaceInBothAppearances() {
        showBoard(width = 480, height = 400)
        for (dark in listOf(true, false)) {
            val appearance = if (dark) "dark" else "light"
            rule.runOnIdle { darkAppearance.value = dark; glassEnabled.value = true }
            val record = bounds("clipboard-card:text:12")
            open("text:12")
            assertGlassMaterial(rule.onNodeWithTag("clipboard-item-menu"), "$appearance item menu")
            saveBoard("clipboard-context-$appearance-text.png")
            dismissOverRecord("clipboard-item-menu", record)
            rule.onNodeWithTag("clipboard-more").performClick()
            assertGlassMaterial(rule.onNodeWithTag("clipboard-more-menu"), "$appearance more menu")
            saveBoard("clipboard-context-$appearance-more.png")
            dismissOverRecord("clipboard-more-menu", record)
            open("image:${image.uri}")
            rule.onNodeWithText("删除").performScrollTo().performClick()
            assertGlassMaterial(rule.onNodeWithTag("clipboard-delete-menu"), "$appearance confirmation")
            saveBoard("clipboard-context-$appearance-delete.png")
            rule.onNodeWithTag("clipboard-cancel-delete").performScrollTo().performClick()
            rule.runOnIdle { assertTrue(removed.isEmpty()) }
        }
    }

    @Test fun resizingOrChangingFontScaleDismissesStaleAnchorsWithoutDeleting() {
        showBoard()
        open("text:12")
        rule.runOnIdle { boardHeight.value = 340 }
        rule.onNodeWithTag("clipboard-item-menu").assertDoesNotExist()
        open("text:12")
        rule.runOnIdle { boardFontScale.value = 1.3f }
        rule.onNodeWithTag("clipboard-item-menu").assertDoesNotExist()
        rule.onNodeWithTag("clipboard-more").performClick()
        rule.runOnIdle { boardWidth.value = 480 }
        rule.onNodeWithTag("clipboard-more-menu").assertDoesNotExist()
        open("text:12")
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.onNodeWithTag("clipboard-delete-menu").assertIsDisplayed()
        rule.runOnIdle { boardHeight.value = 400 }
        rule.onNodeWithTag("clipboard-delete-menu").assertDoesNotExist()
        rule.runOnIdle { assertTrue(removed.isEmpty()); assertEquals(0, pasted) }
    }

    @Test fun backgroundUpdatesDismissAllMenusBeforeTheirSourceRectanglesBecomeStale() {
        showBoard()
        open("text:12")
        rule.runOnIdle { textRecords.value = listOf(ClipboardItem(13, "新复制的记录", timestamp = 30), text) }
        rule.onNodeWithTag("clipboard-item-menu").assertDoesNotExist()
        rule.onNodeWithTag("clipboard-more").performClick()
        rule.runOnIdle { textRecords.value = listOf(text.copy(text = "更新后内容", timestamp = 40)) }
        rule.onNodeWithTag("clipboard-more-menu").assertDoesNotExist()
        open("text:12")
        rule.onNodeWithText("删除").performScrollTo().performClick()
        rule.onNodeWithTag("clipboard-delete-menu").assertIsDisplayed()
        rule.runOnIdle { textRecords.value = listOf(text.copy(timestamp = 50)) }
        rule.onNodeWithTag("clipboard-delete-menu").assertDoesNotExist()
        rule.runOnIdle { assertTrue(removed.isEmpty()); assertEquals(0, pasted) }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertInside(container: Rect, child: Rect, name: String) {
        assertTrue("$name must remain inside the panel/menu", child.left >= container.left - 1f &&
            child.right <= container.right + 1f && child.top >= container.top - 1f && child.bottom <= container.bottom + 1f)
    }

    private fun dismissOverRecord(menu: String, record: Rect) {
        assertFalse("dismissal probe must be outside the menu", bounds(menu).contains(record.center))
        val layer = rule.onNodeWithTag("$menu-dismiss")
        val origin = layer.fetchSemanticsNode().boundsInRoot.topLeft
        layer.performTouchInput { click(record.center - origin) }
        rule.onNodeWithTag(menu).assertDoesNotExist()
        rule.runOnIdle { assertEquals("outside tap must not paste underlying record", 0, pasted); assertTrue(removed.isEmpty()) }
    }

    private fun open(key: String) {
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag("clipboard-card:$key"))
        rule.onNodeWithTag("clipboard-card:$key").performTouchInput { longClick() }
        rule.onNodeWithTag("clipboard-item-menu").assertIsDisplayed()
    }

    private fun showBoard(width: Int = 360, height: Int = 400, fontScale: Float = 1f, dark: Boolean = true) {
        darkAppearance.value = dark
        glassEnabled.value = true
        boardWidth.value = width; boardHeight.value = height; boardFontScale.value = fontScale
        rule.setContent {
            val isDark = darkAppearance.value
            CompositionLocalProvider(
                LocalDensity provides Density(1f, boardFontScale.value),
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = FrostedGlassConfig.defaults(isDark, enabled = glassEnabled.value)),
            ) {
                MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.requiredSize(boardWidth.value.dp, boardHeight.value.dp)) {
                        ClipboardBoardView(
                            textItems = textRecords.value, images = listOf(image), initialImages = false,
                            expanded = false, pins = pins.value, photoAccess = false, failure = null,
                            onBack = {}, onQuickSend = {}, onSelectText = { pasted++ }, onSelectImage = { pasted++ },
                            onSplit = { value, id -> split = value to id }, onAddQuick = {},
                            onPinsChange = { pins.value = it }, onRemove = { removed = it },
                            onPhotoAccess = {}, onPick = {}, onSystemPaste = {}, onShare = { _, _ -> },
                            onPullRemote = null,
                            onEditText = { id, value, code -> edited = Triple(id, value, code) },
                        )
                    }
                }
            }
        }
    }

    private fun assertGlassMaterial(node: SemanticsNodeInteraction, description: String) {
        rule.runOnIdle { glassEnabled.value = false }
        val plain = node.captureToImage().asAndroidBitmap()
        rule.runOnIdle { glassEnabled.value = true }
        val glass = awaitImage(node) { candidate ->
            candidate.width == plain.width && candidate.height == plain.height &&
                changedBackgroundFraction(plain, candidate) > 0.75f
        }
        assertEquals("$description must retain its width", plain.width, glass.width)
        assertEquals("$description must retain its height", plain.height, glass.height)
        assertTrue("$description must replace its solid background, not just recolor its labels",
            changedBackgroundFraction(plain, glass) > 0.75f)
    }

    /** Right padding is empty in both the long-press overlay and the confirmation card. */
    private fun changedBackgroundFraction(plain: Bitmap, glass: Bitmap): Float {
        var changed = 0
        var samples = 0
        for (y in plain.height / 4 until plain.height * 3 / 4 step 3) {
            for (x in plain.width - 8 until plain.width - 3) {
                if (distance(plain.getPixel(x, y), glass.getPixel(x, y)) > 6) changed++
                samples++
            }
        }
        return if (samples == 0) 0f else changed.toFloat() / samples
    }

    private fun containsFixtureColors(bitmap: Bitmap): Boolean {
        var red = 0
        var teal = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            val pixel = bitmap.getPixel(x, y)
            if (distance(pixel, IMAGE_RED) < 15) red++
            if (distance(pixel, IMAGE_TEAL) < 15) teal++
        }
        return red >= 20 && teal >= 20
    }

    private fun distance(a: Int, b: Int) = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
    )

    private fun awaitImage(node: SemanticsNodeInteraction, condition: (Bitmap) -> Boolean): Bitmap {
        var result: Bitmap? = null
        rule.waitUntil(timeoutMillis = 15_000) {
            val current = node.captureToImage().asAndroidBitmap()
            if (condition(current)) { result = current; true } else false
        }
        return checkNotNull(result)
    }

    private fun saveBoard(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "clipboard-context-glass").apply { mkdirs() }
        File(directory, name).outputStream().use {
            assertTrue(rule.onNodeWithTag("clipboard-board").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private companion object {
        val IMAGE_RED: Int = android.graphics.Color.rgb(232, 48, 80)
        val IMAGE_TEAL: Int = android.graphics.Color.rgb(16, 184, 160)
    }
}
