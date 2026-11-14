package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.BackgroundMotionPreferences
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.CustomImageTheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 主题与外观 › 背景图片（11-13 重做）：用到的图一排看得见，每张有 ×，点第一张调整或换一张；
 * 原来分开的「使用背景图片」「调整图片」「删除图片」「清空」「动态与轮播」不再单独出现，功能都还在。
 * 设了 CYIME_RENDER_OUT=<目录> 时另出两张整页截图（深色，图片排和动态背景各一张）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "zh-rCN-w411dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundImageSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val themesDir get() = File(SchemaManager.getRimeDir(context), "themes")

    @After fun clear() {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove("custom_theme_image_src").remove("custom_theme_image_base").remove("custom_theme_image_placement")
            .remove(BackgroundMotionPreferences.KEY_SLIDES).remove(BackgroundMotionPreferences.KEY_ENABLED).commit()
        themesDir.listFiles()?.filter { it.name.startsWith("custom-keyboard-bg-") }?.forEach { it.delete() }
    }

    @Test fun hintSaysTheNextStepForEachState() {
        assertTrue(backgroundImageHint(1, imageOn = true, motionOn = false).contains("＋"))
        assertTrue(backgroundImageHint(3, imageOn = true, motionOn = false).contains("「动态背景」"))
        assertTrue(backgroundImageHint(3, imageOn = true, motionOn = true).contains("换下一张"))
        assertTrue(backgroundImageHint(3, imageOn = false, motionOn = true).contains("图片留着"))
    }

    @Test fun everyImageHasItsOwnRemoveAndTheFirstOneAdjustsOrReplaces() {
        val calls = mutableListOf<String>()
        val slides = listOf("themes/custom-keyboard-bg-slide-a.jpg", "themes/custom-keyboard-bg-slide-b.jpg")
        rule.setContent {
            XimeTheme(darkTheme = true) {
                Column {
                    BackgroundImageStrip(main = "themes/custom-keyboard-bg-1.jpg", slides = slides, imageOn = true,
                        motionOn = false, onAdjust = { calls += "adjust" }, onReplace = { calls += "replace" },
                        onRemoveMain = { calls += "remove-main" }, onAdd = { calls += "add" },
                        onRemoveSlide = { calls += "remove:$it" })
                }
            }
        }
        rule.onNodeWithTag("background-slide-remove:themes/custom-keyboard-bg-slide-b.jpg").performClick()
        rule.onNodeWithTag("background-slide-remove:themes/custom-keyboard-bg-1.jpg").performClick()
        rule.onNodeWithTag("background-slides-add").performClick()
        rule.onNodeWithTag("background-slide:themes/custom-keyboard-bg-1.jpg").performClick()
        rule.onNodeWithTag("background-image-adjust").performClick()
        rule.onNodeWithTag("background-slide:themes/custom-keyboard-bg-1.jpg").performClick()
        rule.onNodeWithTag("background-image-replace").performClick()
        rule.waitForIdle()
        assertEquals(listOf("remove:themes/custom-keyboard-bg-slide-b.jpg", "remove-main", "add", "adjust", "replace"), calls)
        rule.onNodeWithText("共 3 张，打开下面的「动态背景」才会轮流换").assertExists()
    }

    @Test fun noAddTileOnceNineImagesAreSet() {
        val slides = (1..BackgroundMotionPreferences.MAX_SLIDES).map { "themes/custom-keyboard-bg-slide-$it.jpg" }
        rule.setContent {
            XimeTheme(darkTheme = false) {
                Column {
                    BackgroundImageStrip(main = "themes/custom-keyboard-bg-1.jpg", slides = slides, imageOn = true,
                        motionOn = true, onAdjust = {}, onReplace = {}, onRemoveMain = {}, onAdd = {}, onRemoveSlide = {})
                }
            }
        }
        rule.onAllNodesWithTag("background-slides-add").assertCountEquals(0)
    }

    /** 整页：图片、遮罩、动态背景在同一张卡里，旧的重复项不再出现。 */
    @Test fun themePageShowsImagesTogetherWithoutTheOldDuplicateRows() {
        prepareImages()
        // 全量跑时页面读到的主题不是刚写的「我的图片」（单独跑这个类不会），图片排在、遮罩不在。
        // 页面的 ViewModel 按 Activity 取同一个实例：先拿到它，直接把主题和主题表设好，不靠它构造时读设置的时机。
        val viewModel = androidx.lifecycle.ViewModelProvider(rule.activity)[ThemeSettingsViewModel::class.java]
        println("BackgroundImageSettingsTest: stored keyboard_theme = ${SettingsPreferences.getKeyboardTheme(context)}, " +
            "view model = ${viewModel.uiState.value.colorTheme}")
        rule.runOnIdle {
            viewModel.refreshThemes()
            viewModel.setColorTheme(CustomImageTheme.ID)
        }
        SettingsSearchFocus.open(SettingsSearchIndex.first { it.title == "用自己的图片" })
        rule.mainClock.autoAdvance = false
        rule.setContent { XimeTheme(darkTheme = true, themeId = CustomImageTheme.ID) { ThemeSettingsContent(onBack = {}) } }
        settle()
        listOf("themes/custom-keyboard-bg-1.jpg", "themes/custom-keyboard-bg-slide-a.jpg", "themes/custom-keyboard-bg-slide-b.jpg")
            .forEach { rule.onNodeWithTag("background-slide:$it").assertExists() }
        rule.onNodeWithTag("background-slides-add").assertExists()
        // 图片开着、动态背景开着时的提示；不对说明页面拿到的主题不是「我的图片」。
        rule.onNodeWithText("共 3 张，背景每动完一圈换下一张；点第一张调整位置").assertExists()
        listOf("遮罩", "动态背景", "组合").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag("background-motion-speed:FAST").assertExists()
        listOf("使用背景图片", "调整图片", "删除图片", "清空", "动态与轮播", "轮换").forEach {
            rule.onAllNodesWithText(it).assertCountEquals(0)
        }
        render("background-image-1-images.png")
        rule.runOnIdle { SettingsSearchFocus.open(SettingsSearchIndex.first { it.title == "动态背景" }) }
        settle()
        render("background-image-2-motion.png")
    }

    private fun prepareImages() {
        KeyboardThemes.initFromConfig(context)
        themesDir.mkdirs()
        fun write(name: String, from: Int, to: Int) {
            val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 640f, 400f, Paint().apply {
                shader = LinearGradient(0f, 0f, 640f, 400f, from, to, Shader.TileMode.CLAMP)
            })
            File(themesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        write("custom-keyboard-bg-1.jpg", 0xFF3B4C9B.toInt(), 0xFFE58A5C.toInt())
        write("custom-keyboard-bg-slide-a.jpg", 0xFFB23A48.toInt(), 0xFFF2E3C6.toInt())
        write("custom-keyboard-bg-slide-b.jpg", 0xFF1F6F5C.toInt(), 0xFFD9E8A0.toInt())
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString("custom_theme_image_src", "themes/custom-keyboard-bg-1.jpg")
            .putString("custom_theme_image_base", "soft_blue").commit()
        BackgroundMotionPreferences.setSlides(context,
            listOf("themes/custom-keyboard-bg-slide-a.jpg", "themes/custom-keyboard-bg-slide-b.jpg"))
        BackgroundMotionPreferences.setEnabled(context, true)
        KeyboardThemes.reload(context)
        SettingsPreferences.setKeyboardTheme(context, CustomImageTheme.ID)
    }

    /** 跳转、高亮走完；小图在 IO 线程解码，多等几轮让它们画上。 */
    private fun settle() {
        rule.mainClock.advanceTimeBy(4_000)
        repeat(5) {
            rule.waitForIdle()
            Thread.sleep(200)
            rule.mainClock.advanceTimeBy(100)
        }
        rule.waitForIdle()
    }

    private fun render(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT")?.takeIf { it.isNotEmpty() } ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(Canvas(bitmap)) }
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
