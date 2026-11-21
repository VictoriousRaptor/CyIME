package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 上屏「呢」后展开联想（用户 11-03 截图）：上面候选栏只放了一个「除」，其余全挤到下面。
 * 展开时栏里和打字候选一样能放几个放几个，展开页从栏里放不下的那个接着排，同一个词不出现两次；
 * 点栏里的联想和点展开页里的一样，上屏后收起。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w411dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedAssociationRowTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val words = listOf("除", "这", "有", "换", "整", "你", "啊", "还是", "我", "吧", "么", "哈哈", "嘛", "吗", "哈哈哈")

    @Test fun expandedAssociationsFillTheBarThenContinueBelow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val viewModel = spy(KeyboardViewModel(app))
        val snapshot = mutableStateOf(CandidateState(associationCandidates = words))
        val committed = mutableListOf<Int>()
        val callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
            onAssociationSelect = { committed += it })
        doReturn(mock<T9InputController>()).whenever(viewModel).bindT9Controller(callbacks)
        rule.setContent {
            KeyboardView(viewModel, KeyboardUiState(currentSchemaId = "rime_ice", isDarkTheme = true), callbacks,
                modifier = Modifier.width(411.dp).height(280.dp).testTag("keyboard"),
                candidateState = snapshot, inputSessionManagedByHost = true)
        }
        rule.pullOpenCandidates()
        rule.waitForIdle()
        rule.onNodeWithTag("expanded-candidates").assertExists()
        val inBar = rule.onAllNodesWithTag("candidate-fixed-strip", true).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
            words.indices.count { i -> rule.onAllNodesWithTag("bar-association:$i", true).fetchSemanticsNodes().isNotEmpty() }
        }
        shot("expanded-association-row")
        assertTrue("栏里只放了 $inBar 个", inBar >= 4)
        // 每个词只出现一次：栏里放下的不再出现在展开页，放不下的都在展开页里。
        for (word in words) assertEquals(word, 1, rule.onAllNodesWithText(word, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        rule.onNodeWithTag("bar-association:1", true).performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), committed)
        rule.onNodeWithTag("expanded-candidates").assertDoesNotExist()
    }

    private fun shot(name: String) {
        val out = System.getenv("CYIME_RENDER_OUT") ?: return
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        val b = rule.onNodeWithTag("keyboard").fetchSemanticsNode().boundsInWindow
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use {
            Bitmap.createBitmap(bitmap, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
