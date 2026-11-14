package com.kingzcheung.xime.ui.render

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.handwriting.training.ContributePromptDeck
import com.kingzcheung.xime.handwriting.training.HandwritingContribution
import com.kingzcheung.xime.handwriting.training.TrainingPoint
import com.kingzcheung.xime.handwriting.training.TrainingPromptType
import com.kingzcheung.xime.handwriting.training.TrainingSample
import com.kingzcheung.xime.handwriting.training.TrainingSampleStore
import com.kingzcheung.xime.handwriting.training.TrainingStroke
import com.kingzcheung.xime.ui.settings.textPrompts
import com.kingzcheung.xime.ui.settings.HandwritingSettingsContent
import com.kingzcheung.xime.ui.settings.HandwritingTrainingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「手写训练」「贡献手写样本」「手写数据采集」合成一个入口（用户 11-05：两个一样的界面）。
 * 自用包：手写页只有「手写训练」；贡献题在题库菜单最后，来回切换并记住上次停在哪边；
 * 原数据采集变成「自己的文字」题库：粘贴一段话逐字写，样本存进训练样本，可删某个字的样本。
 * 公开包：手写页只有「贡献手写样本」，写字页没有题库菜单。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h860dp-xhdpi")
class HandwritingTrainingMergeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun selfBuildHasOneWritingEntry() {
        rule.setContent { MaterialTheme { HandwritingSettingsContent(onBack = {}, devTools = true) } }
        rule.onNodeWithText("手写训练").assertExists()
        rule.onAllNodesWithText("手写数据采集").assertCountEquals(0)
        rule.onAllNodesWithText("贡献手写样本").assertCountEquals(0)
        rule.onAllNodesWithText("手写训练模式").assertCountEquals(0)
    }

    @Test fun publicBuildKeepsOnlyContribute() {
        rule.setContent { MaterialTheme { HandwritingSettingsContent(onBack = {}, devTools = false) } }
        rule.onNodeWithText("贡献手写样本").assertExists()
        rule.onAllNodesWithText("手写训练").assertCountEquals(0)
        rule.onAllNodesWithText("手写数据采集").assertCountEquals(0)
    }

    @Test fun publicBuildHasNoBankMenu() {
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, contribute = true, devTools = false) } }
        rule.waitForIdle()
        rule.onNodeWithText("贡献手写样本").assertExists()
        rule.onNodeWithContentDescription("切换题库").assertDoesNotExist()
    }

    @Test fun contributeShowsHowToAndUploadButtonUpFront() {
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, contribute = true, devTools = false) } }
        rule.waitForIdle()
        // 不用翻右上角菜单：顶栏下面一行说怎么写，右边就是「上传」，还没写时不能点
        rule.onNodeWithTag("contribute-status").assertExists()
        rule.onNodeWithText("照着上面的字写在框里", substring = true).assertExists()
        rule.onNodeWithTag("contribute-upload").assertIsNotEnabled()
    }

    /** 用户 11-12：贡献样本要能自己选写什么——随机、只写某一类，或者自己输入的字词。 */
    @Test fun contributeLetsWriterChooseWhatToWrite() {
        HandwritingContribution.setConsented(context)
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, contribute = true, devTools = false) } }
        rule.waitForIdle()
        rule.onNodeWithTag("contribute-choice").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("自己输入").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("contribute-own-input").performTextInput("龘 你好")
        rule.onNodeWithText("开始").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("龘").assertExists()
        rule.onNodeWithText("第 1 题").assertExists()
        rule.onNodeWithText("自己选的").assertExists()
        assertEquals(ContributePromptDeck.Choice.Own, HandwritingContribution.choice(context))
        assertEquals("龘 你好", HandwritingContribution.ownText(context))
        // 换一批：回到填词
        rule.onNodeWithTag("contribute-own-clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("contribute-own-input").assertExists()
        // 只写词语
        rule.onNodeWithTag("contribute-choice").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("词语").performClick()
        rule.waitForIdle()
        assertEquals(ContributePromptDeck.Choice.Words, HandwritingContribution.choice(context))
        rule.onNodeWithText("像平时手写输入那样写").assertExists()
    }

    /** 用户 11-13：字母、假名、日语、一段话、公式也能选；一段话可以从剪贴板导入。 */
    @Test fun contributeOffersOtherScriptsAndParagraphFromClipboard() {
        HandwritingContribution.setConsented(context)
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, contribute = true, devTools = false) } }
        rule.waitForIdle()
        for (title in listOf("假名", "英文字母", "日文汉字", "数学符号", "公式")) {
            rule.onNodeWithTag("contribute-choice").performClick()
            rule.waitForIdle()
            rule.onNodeWithText(title).performClick()
            rule.waitForIdle()
            assertEquals(title, HandwritingContribution.choice(context).title)
            rule.onNodeWithText("第 1 题").assertExists()
            rule.onNodeWithTag("contribute-own-input").assertDoesNotExist()
        }
        rule.onNodeWithTag("contribute-choice").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("一段话").performClick()
        rule.waitForIdle()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "鑫淼 あい"))
        rule.onNodeWithTag("contribute-own-paste").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("开始").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("鑫").assertExists()
        assertEquals("鑫淼 あい", HandwritingContribution.paragraphText(context))
    }

    /**
     * 回归（11-09「没有分类选择了」）：自用包第一次进「手写训练」要落在训练题库，顶栏是题库分类按钮，
     * 不能一进来就是贡献题。照设置页路由的写法用 lastOpened 决定进哪边。
     */
    @Test fun firstOpenOfTrainingEntryShowsBankCategories() {
        assertFalse(HandwritingContribution.lastOpened(context))
        rule.setContent { MaterialTheme {
            HandwritingTrainingScreen(onBack = {}, contribute = HandwritingContribution.lastOpened(context), devTools = true)
        } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("切换题库").assertExists()
        rule.onAllNodesWithText("贡献手写样本").assertCountEquals(0)
        rule.onNodeWithTag("contribute-status").assertDoesNotExist()
        // 分类菜单里训练题库都在，贡献题排最后
        rule.onNodeWithContentDescription("切换题库").performClick()
        rule.waitForIdle()
        for (title in listOf("公式", "日语句子", "英语", "假名", "英文字母", "数学符号", "中文汉字", "自己的文字", "认错的字", "贡献手写样本")) {
            assertTrue("分类菜单缺 $title", rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        }
    }

    /** 自用包停在贡献题时，顶栏仍然是能切回分类的题库按钮。 */
    @Test fun selfBuildContributeKeepsBankCategories() {
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, contribute = true, devTools = true) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("切换题库").assertExists()
    }

    @Test fun bankMenuSwitchesBetweenTrainingAndContribute() {
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, devTools = true) } }
        rule.waitForIdle()

        // 训练 → 贡献：贡献题在菜单最后
        rule.onNodeWithContentDescription("切换题库").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("贡献手写样本").performClick()
        rule.waitForIdle()
        assertTrue(HandwritingContribution.lastOpened(context))
        rule.onNodeWithText("贡献手写样本").assertExists()
        // 贡献那边仍有题库菜单，能切回训练题库
        rule.onNodeWithContentDescription("切换题库").assertExists()

        // 贡献 → 训练的「假名」题库
        rule.onNodeWithContentDescription("切换题库").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("假名").performClick()
        rule.waitForIdle()
        assertFalse(HandwritingContribution.lastOpened(context))
        rule.onNodeWithText("假名").assertExists()
        rule.onAllNodesWithText("贡献手写样本").assertCountEquals(0)
    }

    @Test fun ownTextBankWritesCharByChar() {
        rule.setContent { MaterialTheme { HandwritingTrainingScreen(onBack = {}, devTools = true) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("切换题库").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("自己的文字").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("training-text-input").performTextInput("你好 你们")
        rule.onNodeWithText("开始").performClick()
        rule.waitForIdle()
        // 去掉空白、按出现顺序去重：你 好 们，从第一个字写起
        rule.onNodeWithText("1/3", substring = true).assertExists()
        rule.onNodeWithText("你").assertExists()
    }

    @Test fun textPromptsDedupeAndSkipSpaces() {
        val prompts = textPrompts("你好 a你\n好!")
        assertEquals(listOf("你", "好", "a", "!"), prompts.map { it.label })
        assertEquals(TrainingPromptType.Chinese, prompts[0].type)
        assertEquals(TrainingPromptType.Custom, prompts[2].type)
        assertTrue(prompts.all { it.id == "text:${it.label}" })
        assertTrue(textPrompts("  \n ").isEmpty())
    }

    @Test fun removeSamplesDropsOnlyThatPrompt() {
        val store = TrainingSampleStore(context, "handwriting_merge_test")
        val stroke = listOf(TrainingStroke(listOf(TrainingPoint(0f, 0f, 0L), TrainingPoint(1f, 1f, 10L))))
        fun sample(label: String) = TrainingSample(textPrompts(label).first(), 100f, 100f, 1f, stroke, 0L)
        store.append(sample("你"), "test", "test")
        store.append(sample("好"), "test", "test")
        store.append(sample("你"), "test", "test")
        assertEquals(2, store.removeSamples("text:你"))
        assertEquals(mapOf("text:好" to 1), store.countsByPrompt())
        assertEquals(0, store.removeSamples("text:你"))
    }
}
