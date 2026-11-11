package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.R
import com.kingzcheung.xime.keyboard.*
import com.kingzcheung.xime.ui.common.menuSwipeBack
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.MenuBar
import com.kingzcheung.xime.ui.keyboard.MenuBarState
import com.kingzcheung.xime.ui.settings.SettingsRoutes
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuSwipeBackTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
    }

    private fun TouchInjectionScope.edgeSwipe() = swipe(
        Offset(width * .87f, centerY), Offset(width * .32f, centerY - 5f), 380)

    @Test fun deliberateLeftSwipeReturnsFromSettingsButVerticalPagingStaysOnThePage() {
        rule.setContent { XimeTheme { SettingsScreen(initialRoute = SettingsRoutes.KeyEffect) } }
        rule.onNodeWithText("启用按键音").assertIsDisplayed()
        rule.onRoot().performTouchInput {
            swipe(Offset(width * .86f, height * .8f), Offset(width * .79f, height * .3f), 400)
        }
        rule.onNodeWithText("按键效果").assertIsDisplayed()
        shot("settings-scrolled")
        rule.onRoot().performTouchInput { edgeSwipe() }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_title)).assertIsDisplayed()
        shot("settings-returned")
        rule.onRoot().performTouchInput { edgeSwipe() }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_title)).assertIsDisplayed()
    }

    @Test fun slidersOwnTheirHorizontalDragAndAnInitiallyVerticalGestureCannotBecomeBack() {
        var backs = 0
        var value by mutableFloatStateOf(0.9f)
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().menuSwipeBack(Unit, { backs++ }).verticalScroll(scroll)) {
                Slider(value, { value = it }, modifier = Modifier.fillMaxWidth().testTag("slider"))
                repeat(40) { Text("第 ${it + 1} 行", Modifier.height(48.dp)) }
            }
        }
        rule.onNodeWithTag("slider").performTouchInput { edgeSwipe() }
        rule.runOnIdle { assertTrue("slider value $value", value < .65f); assertEquals(0, backs) }
        rule.onRoot().performTouchInput {
            down(Offset(width * .9f, height * .8f))
            repeat(8) { moveBy(Offset(-1f, -16f)) }
            repeat(12) { moveBy(Offset(-16f, -1f)) }
            up()
        }
        rule.runOnIdle {
            assertEquals(0, backs)
            assertTrue("list offset ${scroll.value}, max ${scroll.maxValue}", scroll.value > 0)
        }
    }

    @Test fun swipeInAnEditedSettingsFormUsesItsExistingBackConfirmation() {
        rule.setContent { XimeTheme { SettingsScreen(initialRoute = SettingsRoutes.DoublePinyin) } }
        rule.onNodeWithTag("double-pinyin-new").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("保留这份方案")
        rule.onRoot().performTouchInput { edgeSwipe() }
        rule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("保留这份方案").assertExists()
    }

    @Test fun shortDiagonalRightwardReversedHeldAndMultitouchSwipesDoNotReturn() {
        var backs = 0
        rule.setContent { Box(Modifier.fillMaxSize().menuSwipeBack(Unit, { backs++ })) }
        val root = rule.onRoot()
        root.performTouchInput {
            val start = Offset(width * .9f, centerY)
            swipe(start, start + Offset(-48f, 0f), 200)
            swipe(start, start + Offset(-140f, -85f), 320)
            swipe(start, start + Offset(24f, 0f), 100)
            swipe(Offset(centerX, centerY), Offset(20f, centerY), 240)
            down(start); moveBy(Offset(-120f, 0f)); moveBy(Offset(100f, 0f)); up()
            down(start); advanceEventTime(700); moveBy(Offset(-150f, 0f)); up()
            down(0, start); down(1, start + Offset(0f, 40f))
            moveBy(0, Offset(-150f, 0f)); up(1); up(0)
        }
        rule.runOnIdle { assertEquals(0, backs) }
        root.performTouchInput { edgeSwipe() }
        rule.runOnIdle { assertEquals(1, backs) }
    }

    /** 11-10：翻到最后一页再往左划一下，什么都没点菜单就退出了。最后一页往左划什么也不做，菜单留着。 */
    @Test fun lastPageOverSwipeKeepsTheMenuOpen() {
        val model = KeyboardViewModel(app)
        model.showOverlay(OverlayRoute.Menu, listOf(OverlayRoute.SchemaList))
        val buttons = ToolbarButton.entries.take(13)
        var taps = 0
        var holds = 0
        val actions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it), onLongClick = { holds++ }) { taps++ } }
        rule.setContent {
            val page by model.page.collectAsState()
            if ((page as? KeyboardPage.Overlay)?.route == OverlayRoute.Menu) {
                MenuBar(MenuBarState(true, false, backgroundColor = Color.LightGray), actions,
                    Modifier.width(360.dp).height(230.dp).testTag("menu-body"))
            }
        }
        val menu = rule.onNodeWithTag("menu-body")
        rule.onNodeWithTag("menu-item:${buttons[0].id}").performTouchInput { click(); longClick() }
        rule.runOnIdle { assertEquals(1, taps); assertEquals(1, holds) }
        // 第一页往左划翻页
        menu.performTouchInput { edgeSwipe() }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        shot("keyboard-second-page")
        // 最后一页再划：边缘起手、中间起手、划得很远，都停在这一页
        menu.performTouchInput { edgeSwipe() }
        menu.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .2f, centerY), 330) }
        menu.performTouchInput { swipe(Offset(width * .96f, centerY), Offset(width * .02f, centerY), 200) }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(OverlayRoute.Menu, (model.page.value as KeyboardPage.Overlay).route)
            assertEquals(1, taps); assertEquals(1, holds)
        }
    }

    /** 11-03：在「声音振动」上往右划直接算点了。划不动的方向、没压到右边缘的格子，横划都不算点。 */
    @Test fun horizontalSwipesOnAMenuItemNeverCountAsATap() {
        val buttons = listOf(ToolbarButton.SOUND, ToolbarButton.EMOJI, ToolbarButton.CLIPBOARD, ToolbarButton.SCHEMA)
        var taps = 0
        val actions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) { taps++ } }
        rule.setContent {
            MenuBar(MenuBarState(true, false, backgroundColor = Color.LightGray), actions,
                Modifier.width(360.dp).height(230.dp).testTag("menu-body"))
        }
        val sound = rule.onNodeWithTag("menu-item:${ToolbarButton.SOUND.id}")
        // 只有一页：往右划翻不了页，什么也不做
        sound.performTouchInput { swipe(Offset(width * .2f, centerY), Offset(width * .9f, centerY), 300) }
        rule.runOnIdle { assertEquals(0, taps) }
        // 往左划也翻不了页：不算点，菜单也还在
        sound.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .8f - 150f, centerY), 300) }
        rule.runOnIdle { assertEquals(0, taps) }
        sound.assertIsDisplayed()
        sound.performClick()
        rule.runOnIdle { assertEquals(1, taps) }
    }

    /** 平板：网格在宽面板里居中，从网格外的空白起手往左划，也不算点、不退出。 */
    @Test fun lastPageSwipeFromOutsideTheCenteredGridDoesNothing() {
        // 两项时网格 296dp 宽、在 336dp 里居中，两边各空 20dp
        val buttons = listOf(ToolbarButton.SOUND, ToolbarButton.EMOJI)
        var taps = 0
        val actions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) { taps++ } }
        rule.setContent {
            MenuBar(MenuBarState(true, false, backgroundColor = Color.LightGray), actions,
                Modifier.width(360.dp).height(230.dp).testTag("menu-body"))
        }
        val body = rule.onNodeWithTag("menu-body")
        body.performTouchInput { swipe(Offset(width - 22f, centerY), Offset(width - 92f, centerY), 250) }
        body.performTouchInput { swipe(Offset(width * .5f, centerY), Offset(width * .5f - 40f, centerY), 250) }
        rule.runOnIdle { assertEquals(0, taps) }
        rule.onNodeWithTag("menu-item:${ToolbarButton.SOUND.id}").assertIsDisplayed()
    }

    /** 11-03 平板：翻到最后一页以后往右划回不到前一页，只能关了菜单重开。 */
    @Test fun lastPageSwipesRightBackToThePreviousPage() {
        val buttons = ToolbarButton.entries.take(13)
        var taps = 0
        val actions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) { taps++ } }
        rule.setContent {
            MenuBar(MenuBarState(true, false, backgroundColor = Color.LightGray), actions,
                Modifier.width(360.dp).height(230.dp).testTag("menu-body"))
        }
        val menu = rule.onNodeWithTag("menu-body")
        menu.performTouchInput { swipe(Offset(width * .68f, centerY), Offset(width * .04f, centerY), 330) }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        menu.performTouchInput { swipe(Offset(width * .3f, centerY), Offset(width * .96f, centerY), 330) }
        rule.onNodeWithTag("menu-item:${buttons[0].id}").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, taps) }
        // 回到第一页后照样能翻过去，最后一页再往左划停在原地
        menu.performTouchInput { swipe(Offset(width * .68f, centerY), Offset(width * .04f, centerY), 330) }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        menu.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .2f, centerY), 330) }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, taps) }
        // 往右照样回得去
        menu.performTouchInput { swipe(Offset(width * .3f, centerY), Offset(width * .96f, centerY), 330) }
        rule.onNodeWithTag("menu-item:${buttons[0].id}").assertIsDisplayed()
    }

    /** 三页的菜单：翻到第三页以后一页一页往右划回去，中间那页也照样能往回翻。 */
    @Test fun threePageMenuSwipesBackPageByPage() {
        val buttons = ToolbarButton.entries.take(20)
        var taps = 0
        val actions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) { taps++ } }
        rule.setContent {
            MenuBar(MenuBarState(true, false, backgroundColor = Color.LightGray), actions,
                Modifier.width(360.dp).height(230.dp).testTag("menu-body"))
        }
        val menu = rule.onNodeWithTag("menu-body")
        val forward: TouchInjectionScope.() -> Unit = { swipe(Offset(width * .68f, centerY), Offset(width * .04f, centerY), 330) }
        val backward: TouchInjectionScope.() -> Unit = { swipe(Offset(width * .3f, centerY), Offset(width * .96f, centerY), 330) }
        menu.performTouchInput(forward)
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        menu.performTouchInput(forward)
        rule.onNodeWithTag("menu-item:${buttons[16].id}").assertIsDisplayed()
        menu.performTouchInput(backward)
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed()
        menu.performTouchInput(backward)
        rule.onNodeWithTag("menu-item:${buttons[0].id}").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, taps) }
    }

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        File(out, "menu-back-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
