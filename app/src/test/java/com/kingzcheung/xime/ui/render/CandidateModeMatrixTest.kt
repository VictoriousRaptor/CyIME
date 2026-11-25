package com.kingzcheung.xime.ui.render

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.CandidateRightKey
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 候选栏三套用法逐格核对（11-25 他：「三种选项下每一种操作每一种状态的操作都是不受影响的，能保证吗」）。
 * 用法：默认（左右滑关 / 开）、收键盘、经典；状态：空闲、打字、展开着、联想；
 * 「显示 ‹ 取消键」「关联想用 ⊗」各开关；操作：点最左、点最右、往左滑、往右滑。
 * 每格的预期按设置页三张卡写的说明：默认右边 ▾ 展开 / ▴ 收回；收键盘右边一直收起键盘；经典右边 × 先关、空闲时同一处收键盘。
 * 结果表写到 build/reports/candidate-mode-matrix.tsv，真机录屏按这张表对。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class CandidateModeMatrixTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray)
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs get() = SettingsPreferences.getPrefsPublic(context)

    @After fun reset() {
        prefs.edit().remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_EXPAND)
            .remove(SettingsPreferences.KEY_CANDIDATE_CLOSE_ON_RIGHT)
            .remove(SettingsPreferences.KEY_CANDIDATE_RIGHT_HIDES)
            .remove(SettingsPreferences.KEY_CANDIDATE_SWIPE_BEFORE_CLOSE)
            .remove(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON)
            .remove(SettingsPreferences.KEY_ASSOCIATION_CANCEL_CROSS).commit()
    }

    private enum class Mode(val label: String) { DEFAULT("默认"), DEFAULT_SWIPE("默认+左右滑"), HIDE("右侧收起"), CLOSE("经典") }
    private enum class Bar(val label: String) { IDLE("空闲"), TYPING("打字"), EXPANDED("展开着"), ASSOCIATION("联想") }
    private enum class Action(val label: String) { SWIPE_LEFT("往左滑"), SWIPE_RIGHT("往右滑"), LEFT("点最左"), RIGHT("点最右") }

    private val typing = CandidateBarState.ChineseCandidates((1..12).map { "候选$it" }, inputText = "houxuan", hasMore = true)
    // 电脑上量的字宽是假的：短词 20 个会被算成一屏全放得下（那时本来就不让拉），用长词保证一屏放不下。
    private val association = CandidateBarState.AssociationOnly((1..20).map { "联想词语很长的一个联想$it" }, hasMore = true)

    private fun apply(mode: Mode, cancel: Boolean, cross: Boolean) {
        reset()
        when (mode) {
            Mode.DEFAULT -> SettingsPreferences.setCandidateRightKey(context, CandidateRightKey.EXPAND)
            Mode.DEFAULT_SWIPE -> {
                SettingsPreferences.setCandidateRightKey(context, CandidateRightKey.EXPAND)
                SettingsPreferences.setCandidateSwipeExpand(context, true)
            }
            Mode.HIDE -> SettingsPreferences.setCandidateRightKey(context, CandidateRightKey.HIDE)
            Mode.CLOSE -> SettingsPreferences.setCandidateRightKey(context, CandidateRightKey.CLOSE)
        }
        SettingsPreferences.setShowCandidateCancelButton(context, cancel)
        SettingsPreferences.setAssociationCancelCross(context, cross)
    }

    /** 设计上这一格该发生什么。select / association 是手指落在候选、联想词上（那一端没有键）。 */
    private fun expected(mode: Mode, cancel: Boolean, cross: Boolean, bar: Bar, action: Action): String {
        if (bar == Bar.IDLE) return when (action) { Action.LEFT -> "logo"; Action.RIGHT -> "hide"; else -> "-" }
        val word = if (bar == Bar.ASSOCIATION) "association" else "select"
        val leftKey = when {
            mode == Mode.CLOSE -> false
            bar == Bar.ASSOCIATION -> cancel || cross
            else -> cancel
        }
        val swipes = mode != Mode.DEFAULT
        return when (action) {
            Action.LEFT -> if (leftKey) "cancel" else word
            Action.RIGHT -> when (mode) {
                Mode.HIDE -> "hide"
                Mode.CLOSE -> "cancel"
                else -> if (bar == Bar.EXPANDED) "back" else "expand"
            }
            Action.SWIPE_LEFT, Action.SWIPE_RIGHT -> when {
                !swipes -> "-"
                bar == Bar.EXPANDED -> "collapse"
                else -> "expand"
            }
        }
    }

    @Test fun everyModeStateAndAction() {
        val state = mutableStateOf<CandidateBarState>(CandidateBarState.Idle)
        val expanded = mutableStateOf(false)
        val events = mutableListOf<String>()
        rule.setContent {
            CandidateBar(state = state.value, candidatePageExpanded = expanded.value, visuals = visuals,
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = { events += "select" },
                    onAssociationSelect = { events += "association" },
                    onLogoClick = { events += "logo" },
                    onCancelInput = { events += "cancel" },
                    onHideKeyboard = { events += "hide" },
                    onShowMoreCandidates = { events += "expand" },
                    onBack = { events += "back" },
                    onCollapseCandidates = { events += "collapse" },
                    onCandidateStripScrolled = {}, onCandidateStripNearEnd = {}),
                showPreeditPreview = false, modifier = Modifier.width(400.dp))
        }
        val rows = mutableListOf("用法\t取消键\t⊗\t状态\t操作\t预期\t实际\t结果")
        val wrong = mutableListOf<String>()
        for (mode in Mode.values()) for (cancel in listOf(true, false)) for (cross in listOf(false, true)) {
            rule.runOnIdle { apply(mode, cancel, cross) }
            for (bar in Bar.values()) for (action in Action.values()) {
                rule.runOnIdle {
                    state.value = when (bar) {
                        Bar.IDLE -> CandidateBarState.Idle
                        Bar.ASSOCIATION -> association
                        else -> typing
                    }
                    expanded.value = bar == Bar.EXPANDED
                }
                rule.waitForIdle()
                rule.runOnIdle { events.clear() }
                val dp = rule.density.density
                when (action) {
                    Action.LEFT -> rule.onRoot().performTouchInput { click(Offset(14f * dp, centerY)) }
                    Action.RIGHT -> rule.onRoot().performTouchInput { click(Offset(width - 14f * dp, centerY)) }
                    Action.SWIPE_LEFT, Action.SWIPE_RIGHT -> {
                        val pull = rule.onNodeWithTag("candidate-pull-expand", useUnmergedTree = true)
                        if (runCatching { pull.fetchSemanticsNode() }.isSuccess) {
                            // 滑到头再拉才展开（候选多时第一下是滚动），最多拉三下。
                            repeat(3) { if (events.none { it == "expand" || it == "collapse" }) pull.pullAcross(leftward = action == Action.SWIPE_LEFT) }
                        }
                    }
                }
                rule.waitForIdle()
                val actual = rule.runOnIdle { events.distinct().joinToString("+").ifEmpty { "-" } }
                val want = expected(mode, cancel, cross, bar, action)
                val ok = actual == want
                rows += listOf(mode.label, if (cancel) "开" else "关", if (cross) "开" else "关", bar.label, action.label,
                    want, actual, if (ok) "对" else "错").joinToString("\t")
                if (!ok) wrong += "${mode.label} 取消键${if (cancel) "开" else "关"} ⊗${if (cross) "开" else "关"} ${bar.label} ${action.label}: 预期 $want 实际 $actual"
            }
        }
        File("build/reports").mkdirs()
        File("build/reports/candidate-mode-matrix.tsv").writeText(rows.joinToString("\n") + "\n")
        assertTrue("${wrong.size} 格不对：\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
