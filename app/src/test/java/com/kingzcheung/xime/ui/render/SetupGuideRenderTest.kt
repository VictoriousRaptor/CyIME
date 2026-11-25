package com.kingzcheung.xime.ui.render

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.CandidateModelCatalog
import com.kingzcheung.xime.model.DeviceModelProfiles
import com.kingzcheung.xime.model.DeviceModelTier
import com.kingzcheung.xime.model.DoublePinyinChoice
import com.kingzcheung.xime.model.SetupAnswers
import com.kingzcheung.xime.model.SetupFacts
import com.kingzcheung.xime.model.SetupGuide
import com.kingzcheung.xime.model.SetupGuidePlanner
import com.kingzcheung.xime.model.SetupSource
import com.kingzcheung.xime.model.VoiceUse
import com.kingzcheung.xime.speech.SpeechModelCatalog
import com.kingzcheung.xime.ui.settings.SetupGuideContent
import com.kingzcheung.xime.ui.settings.SetupMoreContent
import com.kingzcheung.xime.model.FeedbackChoice
import com.kingzcheung.xime.model.SetupMore
import com.kingzcheung.xime.model.SetupMoreChoices
import com.kingzcheung.xime.model.SetupMoreFacts
import com.kingzcheung.xime.model.TextSizeChoice
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「按设备推荐配置」：左为完整版新装、手机性能强、移动数据（有可删的、要下载的、用流量开关），
 * 右为点了“按推荐设置”之后。浅色、深色各一张。只在设置了 CYIME_RENDER_OUT 时出图。
 * 另有总会跑的检查：选项卡够大、选中的那张标成已选中、点另一张后选中跟着换（用户 10-30：选了哪个一目了然），
 * 出图时再存一张选中切换到一半的画面，看过渡是否放慢到看得清。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w880dp-h3200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupGuideRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val mb = 1024L * 1024

    private fun facts(): SetupFacts {
        val bundled = setOf(SpeechModelCatalog.SENSEVOICE,
            BuiltinModelCatalog.SPEAKER_MODEL, "ochwpro", CandidateModelCatalog.NGRAM)
        val sizes = mapOf(SpeechModelCatalog.SENSEVOICE to 228 * mb, SpeechModelCatalog.PARAFORMER to 226 * mb,
            SpeechModelCatalog.XASR to 136 * mb,
            BuiltinModelCatalog.SPEAKER_MODEL to 27 * mb, "ochwpro" to 7 * mb, CandidateModelCatalog.NGRAM to 203 * mb,
            CandidateModelCatalog.NGRAM_SMALL to 105 * mb)
        return SetupFacts(DeviceModelTier.ENHANCED, performanceTier = com.kingzcheung.xime.runtime.adaptation.PerformanceTier.HIGH,
            memoryBytes = 8 * 1024 * mb, cores = 8, maxCpuKHz = 3_200_000,
            soc = "Qualcomm SM8550", freeBytes = 45 * 1024 * mb, metered = true, language = "中文",
            installed = bundled.associateWith { sizes.getValue(it) }, inPackage = bundled, sizes = sizes,
            doublePinyinChoices = doublePinyins, shownDoublePinyin = doublePinyins.map { it.id }.toSet())
    }

    private val doublePinyins = listOf("double_pinyin_flypy" to "小鹤双拼" to "ul'pb", "double_pinyin_shoudao" to "首道双拼" to "eh'pc",
        "double_pinyin_ziranma" to "自然码" to "ud'pn", "double_pinyin_znabc" to "智能ABC双拼" to "vt'pm",
        "double_pinyin_jiajia" to "拼音加加" to "ih'pl", "double_pinyin_microsoft" to "微软双拼" to "ud'pn")
        .map { (idName, example) -> DoublePinyinChoice(idName.first, idName.second, example,
            lettersOnly = idName.first == "double_pinyin_microsoft") }

    private fun shot(out: String, name: String) {
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "setup-guide-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun chosenOptionIsObvious() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val facts = facts()
        rule.setContent {
            var answers by androidx.compose.runtime.remember {
                mutableStateOf(SetupAnswers(VoiceUse.OFTEN, t9 = false, caption = false))
            }
            val plan = SetupGuidePlanner.plan(facts, answers)
            XimeTheme(darkTheme = false) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    SetupGuideContent(facts, plan, plan.remove.map { it.id }.toSet(), allowMetered = false, applying = false,
                        result = null, firstRun = true, onAnswers = { answers = it }, onToggleRemove = { _, _ -> },
                        onAllowMetered = {}, onApply = {}, onSkip = {}, onDone = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("setup-voice:OFTEN").assertIsSelected()
        rule.onNodeWithTag("setup-voice:SOMETIMES").assertIsNotSelected()
        rule.onNodeWithTag("setup-t9:false").assertIsSelected()
        rule.onNodeWithTag("setup-t9:true").assertIsNotSelected()
        for (tag in listOf("setup-voice:OFTEN", "setup-voice:NEVER", "setup-t9:true", "setup-caption:false")) {
            // 引导里多了候选栏三张卡，后面的题要先滚到才量得出高度。
            val bounds = rule.onNodeWithTag(tag).performScrollTo().getBoundsInRoot()
            assertTrue("$tag 只有 ${bounds.bottom - bounds.top} 高", bounds.bottom - bounds.top >= 76.dp)
            assertTrue("$tag 不是整行宽", bounds.right - bounds.left >= 360.dp)
        }
        // 像用户一样点（时钟照常走）：点完另一张就成了已选中，原来那张取消。
        rule.onNodeWithTag("setup-voice:SOMETIMES").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-voice:SOMETIMES").assertIsSelected()
        rule.onNodeWithTag("setup-voice:OFTEN").assertIsNotSelected()
        // 打字方式：新装是全拼 26 键；键盘三张卡和别的题一样大，问题在设备情况上面。
        rule.onNodeWithTag("setup-typing:FULL_PINYIN").assertIsSelected()
        rule.onNodeWithTag("setup-keyboard:QWERTY").assertIsSelected()
        for (tag in listOf("setup-keyboard:QWERTY", "setup-keyboard:K14", "setup-keyboard:T9")) {
            val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag 只有 ${bounds.bottom - bounds.top} 高", bounds.bottom - bounds.top >= 76.dp)
        }
        rule.onNodeWithTag("setup-keyboard:K14").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-keyboard:K14").assertIsSelected()
        rule.onNodeWithTag("setup-keyboard:QWERTY").assertIsNotSelected()
        // 选了九键就不再问“也用九键打字吗”。
        rule.onNodeWithTag("setup-keyboard:T9").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-t9:true").assertDoesNotExist()
        rule.onNodeWithTag("setup-keyboard:QWERTY").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-t9:true").assertExists()
        // 分不清的读音可以多选，“都分得清”和它们互斥。
        rule.onNodeWithTag("setup-fuzzy:none").assertIsSelected()
        rule.onNodeWithTag("setup-fuzzy:NL").performScrollTo().performClick()
        rule.onNodeWithTag("setup-fuzzy:NASAL").performScrollTo().performClick()
        rule.waitForIdle()
        // 多选卡按复选框报“已勾选”。
        rule.onNodeWithTag("setup-fuzzy:NL").assertIsOn()
        rule.onNodeWithTag("setup-fuzzy:NASAL").assertIsOn()
        rule.onNodeWithTag("setup-fuzzy:FH").assertIsOff()
        rule.onNodeWithTag("setup-fuzzy:none").assertIsNotSelected()
        rule.onNodeWithTag("setup-hide-double-pinyin").assertIsOff()
        // 有内存焦虑吗（11-04）：没答过是“没有”；选“有”后不留的模型写明是为了省。
        rule.onNodeWithTag("setup-memory-saver:false").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("setup-memory-saver:true").assertIsNotSelected()
        val saverBounds = rule.onNodeWithTag("setup-memory-saver:true").getBoundsInRoot()
        assertTrue("内存焦虑卡只有 ${saverBounds.bottom - saverBounds.top} 高", saverBounds.bottom - saverBounds.top >= 76.dp)
        rule.onNodeWithTag("setup-memory-saver:true").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-memory-saver:true").assertIsSelected()
        val out = System.getenv("CYIME_RENDER_OUT")
        if (!out.isNullOrEmpty()) { File(out).mkdirs(); shot(out, "memory-saver") }
        rule.onNodeWithTag("setup-memory-saver:false").performScrollTo().performClick()
        rule.waitForIdle()
        if (!out.isNullOrEmpty()) shot(out, "full-pinyin-fuzzy")
        // 选双拼：出“用哪一种”，模糊音那题收起。
        rule.onNodeWithTag("setup-typing:DOUBLE_PINYIN").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-double-pinyin:double_pinyin_flypy").assertIsSelected()
        rule.onNodeWithTag("setup-fuzzy:NL").assertDoesNotExist()
        rule.onNodeWithTag("setup-double-pinyin:double_pinyin_ziranma").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-double-pinyin:double_pinyin_ziranma").assertIsSelected()
        if (!out.isNullOrEmpty()) shot(out, "double-pinyin")
        // 微软双拼只有 26 键：14 键、九键两张卡不出现，说明为什么。
        rule.onNodeWithTag("setup-double-pinyin:double_pinyin_microsoft").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-keyboard:QWERTY").assertIsSelected()
        rule.onNodeWithTag("setup-keyboard:K14").assertDoesNotExist()
        if (out.isNullOrEmpty()) return
        File(out).mkdirs()
        // 出图：停住时钟再点回第一张，存一张颜色、打勾按 CyMotion.GUIDE 变到一半的，再存一张变完的。
        // 不用触摸注入：时钟停住时滚动和触摸都等不到下一帧。
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("setup-voice:OFTEN").performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(com.kingzcheung.xime.ui.theme.CyMotion.GUIDE / 2L)
        shot(out, "switching-halfway")
        rule.mainClock.advanceTimeBy(com.kingzcheung.xime.ui.theme.CyMotion.GUIDE.toLong() + 200)
        shot(out, "switched")
        rule.mainClock.autoAdvance = true
    }

    /** 11-25 他：候选栏右边的键很重要，引导里要问——三张卡和设置页一样（默认、经典、右侧收起），点哪张答案就是哪套，全面屏手机说一句哪套顺手。 */
    @Test fun asksCandidateRightKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val facts = facts().copy(gestureNav = true)
        var last: SetupAnswers? = null
        rule.setContent {
            var answers by androidx.compose.runtime.remember { mutableStateOf(SetupGuidePlanner.suggest(facts)) }
            val plan = SetupGuidePlanner.plan(facts, answers)
            XimeTheme(darkTheme = false) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    SetupGuideContent(facts, plan, emptySet(), allowMetered = false, applying = false,
                        result = null, firstRun = true, onAnswers = { answers = it; last = it }, onToggleRemove = { _, _ -> },
                        onAllowMetered = {}, onApply = {}, onSkip = {}, onDone = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("candidate-right-key:expand").performScrollTo().assertIsSelected()
        rule.onNodeWithText("返回不好按", substring = true).assertExists()
        val top = listOf("expand", "close", "hide").map { rule.onNodeWithTag("candidate-right-key:$it").getBoundsInRoot().top }
        assertTrue("顺序应是默认、经典、右侧收起：$top", top == top.sortedBy { it.value })
        rule.onNodeWithTag("candidate-right-key:close").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("candidate-right-key:close").assertIsSelected()
        rule.onNodeWithTag("candidate-right-key:expand").assertIsNotSelected()
        assertTrue(last?.candidateRightKey == com.kingzcheung.xime.settings.CandidateRightKey.CLOSE)
    }

    @Test fun renderSetupGuide() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val facts = facts()
        val plan = SetupGuidePlanner.plan(facts, SetupAnswers(VoiceUse.OFTEN, t9 = false, caption = false))
        val dark = mutableStateOf(false)
        rule.setContent {
            XimeTheme(darkTheme = dark.value) {
                Row {
                    Box(Modifier.width(420.dp).fillMaxHeight()) {
                        SetupGuideContent(facts, plan, plan.remove.map { it.id }.toSet(), allowMetered = false, applying = false,
                            result = null, firstRun = true, onAnswers = {}, onToggleRemove = { _, _ -> }, onAllowMetered = {},
                            onApply = {}, onSkip = {}, onDone = {}, onOpenPerformance = {})
                    }
                    Spacer(Modifier.width(40.dp))
                    Box(Modifier.width(420.dp).fillMaxHeight()) {
                        SetupGuideContent(facts, plan, emptySet(), allowMetered = false, applying = false,
                            result = SetupGuide.Result(3, 249 * mb, 362 * mb, waitingForWifi = true, failed = emptyList(),
                                downloadingIds = plan.keep.filter { it.source == SetupSource.DOWNLOAD }.map { it.id }),
                            firstRun = true, onAnswers = {}, onToggleRemove = { _, _ -> }, onAllowMetered = {},
                            onApply = {}, onSkip = {}, onDone = {}, onOpenMore = {})
                    }
                }
            }
        }
        File(out!!).mkdirs()
        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark.value = theme == "dark" }
            rule.waitForIdle()
            shot(out, theme)
        }
    }

    private val moreNow = SetupMoreChoices(SpaceHoldAction.CURSOR, deleteSkipsAssociation = false, clearBeforeCursorOnly = false,
        textSize = TextSizeChoice.STANDARD, feedback = FeedbackChoice.SOUND_AND_VIBRATION, glass = false, autoPunctuation = false)

    /**
     * 「更多配置」：打开时每题选着现在的设置（不点就不改，按钮写「不改了」）；点了别的才变「好了」，
     * 只列改了的项。自己调过字号的多一张「保持现在的」，智能联想关着不问删除键那题。
     */
    @Test fun moreStartsFromCurrentSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val facts = SetupMoreFacts(moreNow)
        rule.setContent {
            var answers by androidx.compose.runtime.remember { mutableStateOf(facts.current) }
            XimeTheme(darkTheme = false) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    SetupMoreContent(facts, answers, result = null, onAnswers = { answers = it }, onBack = {}, onSkip = {},
                        onApply = {}, onDone = {}, onOpenRoute = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("setup-more-space:CURSOR").assertIsSelected()
        rule.onNodeWithTag("setup-more-space:VOICE_TOGGLE").assertIsNotSelected()
        rule.onNodeWithTag("setup-more-text:STANDARD").assertIsSelected()
        rule.onNodeWithTag("setup-more-text:CUSTOM").assertDoesNotExist()
        rule.onNodeWithTag("setup-more-feedback:SOUND_ONLY").assertDoesNotExist()
        rule.onNodeWithTag("setup-more-apply").performScrollTo().assertTextEquals("不改了")
        for (tag in listOf("setup-more-space:REPEAT", "setup-more-feedback:NONE", "setup-more-glass:true")) {
            val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag 只有 ${bounds.bottom - bounds.top} 高", bounds.bottom - bounds.top >= 76.dp)
        }
        rule.onNodeWithTag("setup-more-space:VOICE_TOGGLE").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("setup-more-space:VOICE_TOGGLE").assertIsSelected()
        rule.onNodeWithTag("setup-more-space:CURSOR").assertIsNotSelected()
        rule.onNodeWithTag("setup-more-apply").performScrollTo().assertTextEquals("好了")
        rule.onNodeWithTag("setup-more-open:clipboard_sync").assertExists()
        val out = System.getenv("CYIME_RENDER_OUT")
        if (!out.isNullOrEmpty()) { File(out).mkdirs(); shot(out, "more") }
    }

    @Test fun moreHidesQuestionsThatDoNotApply() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val now = moreNow.copy(textSize = SetupMore.textSizeOf(17, 1.0f), feedback = FeedbackChoice.SOUND_ONLY)
        val facts = SetupMoreFacts(now, sttEnabled = false, predictionEnabled = false, candidateSp = 17, keyScalePercent = 100)
        rule.setContent {
            XimeTheme(darkTheme = false) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    SetupMoreContent(facts, now, result = null, onAnswers = {}, onBack = {}, onSkip = {}, onApply = {}, onDone = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("setup-more-text:CUSTOM").assertIsSelected()
        rule.onNodeWithTag("setup-more-feedback:SOUND_ONLY").assertIsSelected()
        rule.onNodeWithTag("setup-more-delete:true").assertDoesNotExist()
        rule.onNodeWithTag("setup-more-punctuation:true").assertDoesNotExist()
        // 没给跳转（如测试、以后别处复用）就不列那几行。
        rule.onNodeWithTag("setup-more-open:clipboard_sync").assertDoesNotExist()
    }

    @Test fun renderSetupMore() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val facts = SetupMoreFacts(moreNow)
        val changed = moreNow.copy(spaceHold = SpaceHoldAction.VOICE_TOGGLE, textSize = TextSizeChoice.LARGE)
        val dark = mutableStateOf(false)
        rule.setContent {
            XimeTheme(darkTheme = dark.value) {
                Row {
                    Box(Modifier.width(420.dp).fillMaxHeight()) {
                        SetupMoreContent(facts, changed, result = null, onAnswers = {}, onBack = {}, onSkip = {}, onApply = {},
                            onDone = {}, onOpenRoute = {})
                    }
                    Spacer(Modifier.width(40.dp))
                    Box(Modifier.width(420.dp).fillMaxHeight()) {
                        SetupMoreContent(facts, changed, result = SetupMore.changes(moreNow, changed), onAnswers = {}, onBack = {},
                            onSkip = {}, onApply = {}, onDone = {})
                    }
                }
            }
        }
        File(out!!).mkdirs()
        for (theme in listOf("light", "dark")) {
            rule.runOnIdle { dark.value = theme == "dark" }
            rule.waitForIdle()
            shot(out, "more-$theme")
        }
    }
}
