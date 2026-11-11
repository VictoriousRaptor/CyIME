package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.handwriting.FormulaRecognizer
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 手写板的固定行为（11-09 他：「让你做手写改了多少次了」）。每条都是以前被改坏、又修回来的地方，
 * 谁再动手写面板，这里先拦下：
 * - 半屏：右侧删除键 + 底排 !@#、123、全屏手写、空格、语言、回车，和 26 键底排同一排位置（f864ed2b2、8caab1742）；
 * - 全屏：底排换成「收起全屏手写」，上面多一排符号键（476392f23、8caab1742）；
 * - 当前手写语言写在空格键上，画板左上角平时不浮语言胶囊（#107，e5b518136）；
 * - 公式按钮只在英文手写里有，中文、日语没有（ec23da432）；
 * - 英文手写没装公式模型时点「公式」直接打开公式模型的下载页，不跳只列已装模型的页（8f8f1acf1）；
 * - 全屏时底下那块按键的底色铺满平时键盘的整宽、一直铺到面板底边，两侧和底下不漏透明的一条（11-09 截图）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h860dp-xhdpi")
class HandwritingPanelContractTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        KeysConfigHelper.loadConfig(app); KeyboardThemes.reload(app)
        AppFonts.initialize(app)
    }

    private fun show(language: InputLanguage, expanded: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides
                KeyboardInputPreferences(frostedGlass = FrostedGlassConfig.defaults(false, enabled = false))) {
                MaterialTheme {
                    HandwritingKeyboardLayout(language = language, bottomPaddingDp = 0, expanded = expanded,
                        modifier = Modifier.requiredSize(400.dp, 260.dp))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun halfScreenKeepsDeleteKeyAndFullBottomRow() {
        show(InputLanguage.CHINESE)
        rule.onNodeWithTag("handwriting-canvas").assertExists()
        rule.onNodeWithTag("handwriting-side-keys").assertExists()
        rule.onNodeWithTag("handwriting-key:delete").assertExists()
        for (key in listOf("symbol", "number", "expand", "space", "ime_switch", "enter")) {
            assertTrue("半屏手写底排缺 $key", exists("handwriting-key:$key"))
        }
        assertFalse("半屏不该有全屏的符号行", exists("handwriting-symbol-row"))
    }

    @Test fun fullscreenSwapsToCollapseAndAddsSymbolRow() {
        show(InputLanguage.CHINESE, expanded = true)
        for (key in listOf("symbol", "number", "collapse", "space", "ime_switch", "enter", "delete")) {
            assertTrue("全屏手写缺 $key", exists("handwriting-key:$key"))
        }
        assertFalse(exists("handwriting-key:expand"))
        assertTrue(exists("handwriting-symbol-row"))
    }

    @Test fun fullscreenFooterMaterialCoversSidesAndBottom() {
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides
                KeyboardInputPreferences(frostedGlass = FrostedGlassConfig.defaults(false, enabled = false))) {
                MaterialTheme {
                    HandwritingKeyboardLayout(language = InputLanguage.CHINESE, bottomPaddingDp = 0, expanded = true,
                        expandedControlsWidthDp = 400, modifier = Modifier.requiredSize(400.dp, 260.dp).testTag("host"))
                }
            }
        }
        rule.waitForIdle()
        fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val host = bounds("host")
        val material = bounds("handwriting-controls-material")
        val controls = bounds("handwriting-controls")
        assertEquals(host.left.value, material.left.value, 0.5f)
        assertEquals(host.right.value, material.right.value, 0.5f)
        // 底色接到面板底边，下面接导航栏那条，中间不留透明的缝
        assertEquals(host.bottom.value, material.bottom.value, 0.5f)
        // 按键还在平时的位置：两侧留边、底部留白都在底色里面
        assertTrue(controls.left > material.left && controls.right < material.right)
        assertTrue(controls.bottom < material.bottom)
    }

    @Test fun languageIsOnTheSpaceKeyNotFloatingOnTheBoard() {
        show(InputLanguage.CHINESE)
        assertTrue("空格键要写「中文手写」",
            rule.onAllNodesWithText("中文手写", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse("画板上平时不浮语言胶囊（#107）", exists("handwriting-language"))
    }

    @Test fun englishSpaceKeySaysEnglish() {
        show(InputLanguage.ENGLISH)
        assertTrue(rule.onAllNodesWithText("英文手写", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun formulaButtonOnlyInEnglish() {
        show(InputLanguage.CHINESE)
        assertFalse("中文手写不该有公式按钮", exists("handwriting-formula-toggle"))
    }

    @Test fun formulaButtonShownInEnglish() {
        show(InputLanguage.ENGLISH)
        assertTrue("英文手写要有公式按钮", exists("handwriting-formula-toggle"))
    }

    @Test fun japaneseHasNoFormulaButton() {
        show(InputLanguage.JAPANESE)
        assertFalse("日语手写不该有公式按钮", exists("handwriting-formula-toggle"))
        // 缺公式模型时（假名比对要用它）说清楚去哪下载，不说「暂不支持」
        if (exists("handwriting-unavailable")) assertTrue(
            rule.onAllNodesWithText("公式手写模型", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun formulaWithoutModelOpensItsDownloadPage() {
        assertFalse("测试环境里不该有公式模型", FormulaRecognizer.isAvailable(app))
        show(InputLanguage.ENGLISH)
        rule.onNodeWithTag("handwriting-formula-toggle").performClick()
        rule.waitForIdle()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("点公式应该打开设置", started)
        assertEquals("model_market_detail/${FormulaRecognizer.MODEL_ID}", started.getStringExtra("open_fragment"))
    }
}
