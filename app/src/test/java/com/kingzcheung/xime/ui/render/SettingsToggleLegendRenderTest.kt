package com.kingzcheung.xime.ui.render

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.settings.AutoSpaceLegend
import com.kingzcheung.xime.ui.settings.CandidateExtraLegend
import com.kingzcheung.xime.ui.settings.CandidateSpacingLegend
import com.kingzcheung.xime.ui.settings.DoublePinyinAbbreviationLegend
import com.kingzcheung.xime.ui.settings.EmailSuffixLegend
import com.kingzcheung.xime.ui.settings.EvenMergedKeysLegend
import com.kingzcheung.xime.ui.settings.SemicolonOnShiftLegend
import com.kingzcheung.xime.ui.settings.ShiftSyllableSplitLegend
import com.kingzcheung.xime.ui.settings.SplitRightGLegend
import com.kingzcheung.xime.ui.settings.SyllableSplitKeyLegend
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 开关下面的图例（11-02 限宽卡片，只画当前效果）：开关关着时不画多出来的那一项，开着时画出来。
 * 设置了 CYIME_RENDER_OUT 时，左右两栏（关／开）各出浅色、深色一张，手机宽 390dp。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1720dp-h1900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsToggleLegendRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
    fun oneTraditionalSwitchCoversZhuyin() {
        val context = rule.activity
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(context)
        fun prefs() = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context)
        val raw = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
        raw.edit().putBoolean(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.LEGACY_ZHUYIN_SIMPLIFIED_KEY, true).commit()
        rule.setContent {
            XimeTheme(darkTheme = true) {
                com.kingzcheung.xime.ui.settings.ChineseInputSettingsContent(onBack = {})
            }
        }
        // 注音不再单独一行，「繁体输出」说明里写上注音。
        assertEquals(0, rule.onAllNodesWithText("注音输出简体").fetchSemanticsNodes().size)
        assertEquals(false, prefs().traditionalOutput)
        rule.onNodeWithText("繁体输出").performClick()
        rule.runOnIdle {
            assertEquals(true, prefs().traditionalOutput)
            // 旧键保存时清掉。
            assertEquals(false, raw.contains(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.LEGACY_ZHUYIN_SIMPLIFIED_KEY))
            System.getenv("CYIME_RENDER_OUT")?.let { path ->
                val view = context.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(path, "zhuyin-output.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        rule.onNodeWithText("繁体输出").performClick()
        rule.runOnIdle { assertEquals(false, prefs().traditionalOutput) }
    }

    @Test
    @Config(qualifiers = "w390dp-h840dp-mdpi")
    fun longSentenceChoiceRequiresBothFilesAndSavesTheSwitch() {
        val context = rule.activity
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(context)
        val dir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context).apply { mkdirs() }
        val lexicon = File(dir, "cylm_lexicon.cylx")
        val model = File(dir, "cylm_model.lm")
        val generation = mutableStateOf(0)
        lexicon.createNewFile()
        try {
            rule.setContent {
                XimeTheme(darkTheme = false) {
                    androidx.compose.runtime.key(generation.value) {
                        com.kingzcheung.xime.ui.settings.ChineseInputSettingsContent(onBack = {})
                    }
                }
            }
            val label = "全拼长句备选"
            rule.onNodeWithText(label).assertDoesNotExist()
            rule.runOnIdle { model.createNewFile(); generation.value++ }
            fun enabled() = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context).cylmLongAlternative
            assertEquals(false, enabled())
            rule.onNodeWithText(label).performClick()
            rule.runOnIdle { assertEquals(true, enabled()) }
            rule.onNodeWithText(label).performClick()
            rule.runOnIdle { assertEquals(false, enabled()) }
            rule.onNodeWithText(label).performClick()
            rule.runOnIdle { assertEquals(true, enabled()); generation.value++ }
            rule.waitForIdle()
            System.getenv("CYIME_RENDER_OUT")?.let { path ->
                val root = context.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
                File(path, "cylm-long-alternative.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            rule.onNodeWithText(label).performClick()
            rule.runOnIdle { assertEquals(false, enabled()) }
        } finally {
            lexicon.delete()
            model.delete()
        }
    }

    @Composable
    private fun AllLegends(on: Boolean) = Column(Modifier.width(390.dp).fillMaxHeight()
        .background(MaterialTheme.colorScheme.surfaceContainerLow), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SyllableSplitKeyLegend(on, comma = true, period = false, reversed = false)
        ShiftSyllableSplitLegend(on)
        SemicolonOnShiftLegend(on)
        DoublePinyinAbbreviationLegend(on)
        CandidateExtraLegend("rare-characters-legend", "gui", listOf("鬼", "贵", "归"), "槻", on, skipped = true)
        CandidateExtraLegend("datetime-candidates-legend", "shijian", listOf("时间"), "15:35", on)
        CandidateExtraLegend("candidate-emoji-legend", "kaixin", listOf("开心"), "😄", on)
        CandidateExtraLegend("symbol-names-legend", "jiahao", listOf("加号"), "+", on)
        CandidateExtraLegend("greek-letters-legend", "pi", listOf("批", "皮"), "π", on)
        AutoSpaceLegend("english-auto-space-legend", "点 world", "hello world", "|", on)
        AutoSpaceLegend("english-punctuation-space-legend", "打 ,", "Hello,", "|", on)
        EmailSuffixLegend(on)
        EvenMergedKeysLegend(on)
        SplitRightGLegend(on)
        CandidateSpacingLegend(on)
    }

    @Test fun legendsShowTheAddedItemOnlyWhenTheirSwitchIsOn() {
        val on = mutableStateOf(false)
        rule.setContent { XimeTheme(darkTheme = false) { AllLegends(on.value) } }
        val added = listOf("槻", "15:35", "😄", "+", "π", "微信支付", "qq.com", "分词", "␣")
        fun count(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
        fun dividers() = rule.onAllNodesWithTag("candidate-spacing-legend-divider", useUnmergedTree = true)
            .fetchSemanticsNodes().size
        added.forEach { assertEquals("关着时不该有 $it", 0, count(it)) }
        assertEquals("候选词间距加宽关着时不画竖线", 0, dividers())
        rule.runOnIdle { on.value = true }
        rule.waitForIdle()
        added.forEach { assertEquals("开着时应有 $it", if (it == "␣") 2 else 1, count(it)) }
        // 五个候选之间四条浅色竖线（11-09，像 Gboard）。
        assertEquals(4, dividers())
        // 每个图例都在限宽卡片里（SettingsLegend 打的 tag）。
        listOf("syllable-split-key-legend", "shift-syllable-split-legend", "semicolon-on-shift-legend",
            "double-pinyin-abbreviation-legend", "even-merged-keys-legend", "split-right-g-legend",
            "candidate-spacing-legend", "email-suffix-legend").forEach {
            assertEquals(it, 1, rule.onAllNodesWithTag(it).fetchSemanticsNodes().size)
        }
    }

    /** 11-02 挪过位置的几页：语言与方案（只留入口）、中文输入、英文输入、候选词（四组）。左右并排，浅色、深色各一张。 */
    @Test fun renderMovedPages() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.kingzcheung.xime.ui.theme.KeyboardThemes.initFromConfig(context)
        // 同 SettingsSwitchStatesRenderTest：语言页在后台线程读布局，状态先在全局快照里建好。
        com.kingzcheung.xime.settings.CustomKeyboardLayouts.load(context)
        com.kingzcheung.xime.settings.LetterLayouts.load(context)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val dark = mutableStateOf(false)
        rule.setContent {
            XimeTheme(darkTheme = dark.value) {
                Row {
                    androidx.compose.foundation.layout.Box(Modifier.width(420.dp).fillMaxHeight()) {
                        com.kingzcheung.xime.ui.settings.LanguageSettingsContent(onBack = {})
                    }
                    androidx.compose.foundation.layout.Box(Modifier.width(420.dp).fillMaxHeight()) {
                        com.kingzcheung.xime.ui.settings.ChineseInputSettingsContent(onBack = {})
                    }
                    androidx.compose.foundation.layout.Box(Modifier.width(420.dp).fillMaxHeight()) {
                        com.kingzcheung.xime.ui.settings.EnglishInputSettingsContent(onBack = {})
                    }
                    androidx.compose.foundation.layout.Box(Modifier.width(420.dp).fillMaxHeight()) {
                        com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates")
                    }
                }
            }
        }
        File(out!!).mkdirs()
        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark.value = theme == "dark" }
            rule.waitForIdle()
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "settings-moved-pages-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun renderLegends() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        val dark = mutableStateOf(false)
        rule.setContent {
            XimeTheme(darkTheme = dark.value) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AllLegends(on = false)
                    Spacer(Modifier.width(40.dp))
                    AllLegends(on = true)
                }
            }
        }
        File(out!!).mkdirs()
        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark.value = theme == "dark" }
            rule.waitForIdle()
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
            File(out, "toggle-legends-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
