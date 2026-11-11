package com.kingzcheung.xime.ui.render

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「候选栏取消按钮」默认打开（用户 10-31），手动关过的不变；开关下面的图例随开关变化。
 * [renderLegend] 只在设置了 CYIME_RENDER_OUT=<目录> 时出图：开、关各一张，玻璃、不透明各一张，
 * 每张横排浅色、深色、纯白、纯黑四个主题，手机大小的候选词页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateCancelSettingRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val themes = listOf(Triple("soft_blue", false, "浅色"), Triple("soft_blue", true, "深色"),
        Triple("pure_black", false, "纯白"), Triple("pure_black", true, "纯黑"))

    @Test fun defaultIsOnAndExplicitOffStays() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON).commit()
        assertTrue("没设置过：默认打开", SettingsPreferences.shouldShowCandidateCancelButton(context))
        // 老用户手动关过（存着 false）：升级后仍是关。
        prefs.edit().putBoolean(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON, false).commit()
        assertFalse(SettingsPreferences.shouldShowCandidateCancelButton(context))
    }

    @Test fun legendFollowsTheSwitch() {
        SettingsPreferences.getPrefsPublic(context).edit().remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON).commit()
        KeyboardThemes.initFromConfig(context)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") } }
        rule.waitForIdle()
        // 这一行最外层是设置搜索的定位修饰（它的 testTag 在前，同一节点上前面的生效），两个名字都认。
        val tagged = hasTestTag("candidate-cancel-setting") or hasTestTag("settings-search-anchor:候选栏取消按钮")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(tagged)
        val setting = rule.onNode(tagged)
        setting.assertIsOn()
        rule.onNodeWithText("↑ 点它取消当前输入", useUnmergedTree = true).assertExists()
        setting.performClick().assertIsOff()
        rule.onNodeWithText("候选词从最左边开始", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("↑ 点它取消当前输入", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(SettingsPreferences.shouldShowCandidateCancelButton(context))
    }

    @Test fun doublePinyinDisplayUsesTheSharedPreference() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().remove(SpellingPatches.DOUBLE_PINYIN_RAW_PREEDIT_KEY).commit()
        assertTrue(SpellingPatches.doublePinyinRawPreedit(context))
        KeyboardThemes.initFromConfig(context)
        rule.setContent { XimeTheme(darkTheme = false, themeId = "soft_blue") {
            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") } }
        val settingTag = hasTestTag("settings-search-anchor:双拼编码显示全拼")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(settingTag)
        val setting = rule.onNode(isToggleable() and hasAnyAncestor(settingTag))
        setting.assertIsOff()
        setting.performClick()
        setting.assertIsOn()
        rule.runOnIdle { assertFalse(SpellingPatches.doublePinyinRawPreedit(context)) }
        rule.runOnIdle { SpellingPatches.setDoublePinyinRawPreedit(context, true) }
        setting.assertIsOff()
        rule.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp() }
        val view = rule.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(frame)) }
        File("build/reports/double-pinyin-candidate-setting.png").apply { parentFile?.mkdirs() }
            .outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        frame.recycle()
    }

    @Test fun renderLegend() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        KeyboardThemes.initFromConfig(context)
        com.kingzcheung.xime.ui.keyboard.AppFonts.initialize(context)
        File(out!!).mkdirs()
        val frames = listOf(true, false).flatMap { show -> listOf(false, true).flatMap { glass -> themes.map { Triple(show, glass, it) } } }
        val shown = mutableIntStateOf(-1)
        rule.setContent {
            val index = shown.intValue
            if (index >= 0) {
                val theme = frames[index].third
                Box(Modifier.size(360.dp, 760.dp).background(Color.Gray)) {
                    // 换一帧重建页面，开关状态从设置里重新读。
                    androidx.compose.runtime.key(index) {
                        XimeTheme(darkTheme = theme.second, themeId = theme.first) {
                            LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates")
                        }
                    }
                }
            }
        }
        val root = rule.activity.window.decorView
        var sheet: Bitmap? = null
        frames.forEachIndexed { index, (show, glass, _) ->
            val column = index % themes.size
            rule.runOnIdle {
                FrostedGlassPreferences.saveProfiles(context, FrostedGlassPreferences.readProfiles(context).withEnabled(glass))
                SettingsPreferences.setShowCandidateCancelButton(context, show)
                shown.intValue = index
            }
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            val frame = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(frame)) }
            if (column == 0) sheet = Bitmap.createBitmap(frame.width * themes.size + 8 * (themes.size - 1), frame.height,
                Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.DKGRAY) }
            android.graphics.Canvas(sheet!!).drawBitmap(frame, (column * (frame.width + 8)).toFloat(), 0f, null)
            frame.recycle()
            if (column == themes.size - 1) {
                File(out, "candidate-cancel-${if (show) "on" else "off"}-${if (glass) "glass" else "solid"}.png").outputStream()
                    .use { sheet!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                sheet!!.recycle()
            }
        }
    }
}
