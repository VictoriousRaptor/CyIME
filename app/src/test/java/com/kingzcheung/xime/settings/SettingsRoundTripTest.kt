package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.ui.keyboard.CursorGestureMode
import com.kingzcheung.xime.ui.keyboard.FormulaDelimiter
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import com.kingzcheung.xime.ui.settings.BackupChoices
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 设置页上的开关、单选、滑条：写进去再读出来必须是同一个值，并且两个不同的值都能来回切换，
 * 否则就是“改了不保存、退出再进来就变回去”。新增设置项时在 [cases] 里补一行。
 */
class SettingsRoundTripTest {
    private class Case<T>(val name: String, val a: T, val b: T,
        val write: (Context, T) -> Unit, val read: (Context) -> T)

    private val S = SettingsPreferences
    private val cases: List<Case<*>> = listOf(
        Case("底部圆角", true, false, S::setRoundedKeyboardBottom, S::roundedKeyboardBottom),
        Case("顶部圆角", false, true, S::setRoundedKeyboardTop, S::roundedKeyboardTop),
        Case("玻璃柔化顶部边缘", true, false, S::setGlassSoftTopEdge, S::glassSoftTopEdge),
        Case("液态玻璃", true, false, S::setGlassLiquid, S::glassLiquid),
        Case("数字框自动数字键盘", false, true, S::setAutoNumberKeyboardEnabled, S::isAutoNumberKeyboardEnabled),
        Case("分体键盘", true, false, S::setSplitKeyboardEnabled, S::isSplitKeyboardEnabled),
        Case("右半边也放 G 键", true, false, S::setSplitRightGEnabled, S::isSplitRightGEnabled),
        Case("数字行", true, false, S::setNumberRowEnabled, S::isNumberRowEnabled),
        Case("无痕", true, false, S::setIncognito, S::isIncognito),
        Case("候选注释", CandidateCommentMode.HIDE, CandidateCommentMode.SHOW, S::setCandidateCommentMode, S::candidateCommentMode),
        Case("候选注释内容", CandidateCommentContent.GLOSS, CandidateCommentContent.PINYIN,
            S::setCandidateCommentContent, S::candidateCommentContent),
        Case("候选栏取消按钮", true, false, S::setShowCandidateCancelButton, S::shouldShowCandidateCancelButton),
        Case("联想关闭键用叉号", true, false, S::setAssociationCancelCross, S::isAssociationCancelCross),
        Case("编码显示位置", SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR, SettingsPreferences.INPUT_TEXT_INPUT_BOX,
            S::setInputTextLocation, S::getInputTextLocation),
        Case("深浅色", 0, 1, S::setDarkMode, S::getDarkMode),
        Case("按键声音", true, false, S::setSoundEnabled, S::isSoundEnabled),
        Case("音量", 70, 35, S::setSoundVolume, S::getSoundVolume),
        Case("触觉反馈模式", "enabled", "disabled", S::setHapticMode, S::getHapticMode),
        Case("松开时振动", true, false, S::setHapticOnKeyUp, S::isHapticOnKeyUp),
        Case("按下振动时长", 40, 15, S::setVibrationPressDuration, S::getVibrationPressDuration),
        Case("长按振动时长", 60, 25, S::setVibrationLongPressDuration, S::getVibrationLongPressDuration),
        Case("按下振动强度", 200, 80, S::setVibrationPressAmplitude, S::getVibrationPressAmplitude),
        Case("长按振动强度", 180, 90, S::setVibrationLongPressAmplitude, S::getVibrationLongPressAmplitude),
        Case("智能联想", false, true, S::setSmartPredictionEnabled, S::isSmartPredictionEnabled),
        Case("空格上屏后联想", false, true, S::setSpaceCommitAssociationEnabled, S::isSpaceCommitAssociationEnabled),
        Case("单字联想", true, false, S::setAssociationSingleMode, S::isSingleAssociationMode),
        Case("退格直接删字", true, false, S::setDeleteSkipsAssociation, S::isDeleteSkipsAssociation),
        Case("删字后给同音字", true, false, S::setDeleteRecovery, S::isDeleteRecovery),
        Case("上方角标提示", false, true, S::setSwipeUpHintsEnabled, S::isSwipeUpHintsEnabled),
        Case("下方角标提示", false, true, S::setSwipeDownHintsEnabled, S::isSwipeDownHintsEnabled),
        Case("点按弹出气泡", true, false, S::setShowPressBubble, S::shouldShowPressBubble),
        Case("每页候选词数", 35, 25, S::setPageSize, S::getPageSize),
        Case("候选字大小", 15, 21, S::setCandidateTextSize, S::getCandidateTextSize),
        Case("候选词间距加宽", true, false, S::setWideCandidateSpacing, S::isWideCandidateSpacing),
        Case("短信验证码", true, false, S::setSmsCodeEnabled, S::isSmsCodeEnabled),
        Case("备份包含个人数据", true, false, BackupChoices::setIncludePersonal, BackupChoices::includePersonal),
    )

    @Test fun everySettingReadsBackWhatWasWritten() {
        val broken = cases.flatMap { case ->
            @Suppress("UNCHECKED_CAST") val c = case as Case<Any?>
            listOf(c.a, c.b).mapNotNull { value ->
                c.write(context, value)
                val read = c.read(context)
                if (read == value) null else "${c.name}：写入 $value，读出 $read"
            }
        }
        assertEquals("改了不保存的设置：\n" + broken.joinToString("\n"), emptyList<String>(), broken)
    }

    /** 各布局、角标、光标、手写等设置都存在同一个对象里：每一项改成非默认值保存后都要原样读回。 */
    @Test fun keyboardInputSettingsReadBackEveryField() {
        val changed = KeyboardInputPreferences().copy(
            spaceHold = SpaceHoldAction.VOICE_TOGGLE, cursorGesture = CursorGestureMode.KEYBOARD,
            cursorHoldSeconds = 0.5f, languageMenuHoldSeconds = 0.3f, symbolKeyHoldSeconds = 1.5f, candidateDragPercent = 30f, neighborCorrection = false,
            fourteenLetterSwipe = false, fourteenLetterSwipeDp = 30f, symbolInputMode = SymbolInputMode.LONG_PRESS, symbolLongPressSeconds = 0.45f,
            reverseSymbolSwipe = true, clearAllWhileComposing = true, clearBeforeCursorOnly = true, kanaDirectionHints = true, flickDirectionDisc = false, glideTyping = true,
            t9FlickLetters = true, letterLongPressMenu = true, cursorStepDp = 14f, cursorSwipeLines = 1.75f, keyTextScale = 1.3f, handwritingPauseSeconds = 0.8f,
            formulaDelimiter = FormulaDelimiter.entries.last { it != FormulaDelimiter.DOLLAR }, fixedSymbols = "，\n。",
            qwertyStagger = QwertyStaggerMode.PHYSICAL, qwertyStaggerCustom = 0.25f,
            qwertyCommaKey = false, qwertyPeriodKey = false, symbolKeyCycle = true,
        )
        changed.save(context)
        val read = KeyboardInputPreferences.read(context)
        // 玻璃、炫光、分体等由各自的设置函数保存，不经 save()；只比 save() 负责的字段。
        assertEquals(changed, read.copy(frostedGlass = changed.frostedGlass, keyGlowEnabled = changed.keyGlowEnabled,
            keyAnimationEnabled = changed.keyAnimationEnabled, showPressBubble = changed.showPressBubble,
            splitKeyboardEnabled = changed.splitKeyboardEnabled, splitGapPortrait = changed.splitGapPortrait,
            splitGapLandscape = changed.splitGapLandscape, sideSymbols = changed.sideSymbols))
    }

    /**
     * 新装横竖屏都默认固定（用户 10-31），但用户自己切成悬浮后，下次弹出键盘、转回这个方向都还是悬浮，
     * 位置、宽度、旋转角也原样；横竖屏各存一份，改一个方向不动另一个。
     */
    @Test fun floatingChoiceAndPositionStayPerOrientation() {
        for (landscape in listOf(false, true)) assertEquals(false, S.isFloatingMode(context, landscape))
        S.setFloatingMode(context, true, true)
        S.setFloatingOffsetX(context, -37, true); S.setFloatingOffsetY(context, 120, true)
        S.setFloatingWidthDp(context, 300, true); S.setFloatingRotation(context, 12f, true)
        S.setFloatingOffsetX(context, 5, false); S.setFloatingOffsetY(context, 40, false)
        assertEquals(listOf<Any>(true, -37, 120, 300, 12f), listOf<Any>(S.isFloatingMode(context, true),
            S.getFloatingOffsetX(context, true), S.getFloatingOffsetY(context, true),
            S.getFloatingWidthDp(context, true), S.getFloatingRotation(context, true)))
        assertEquals(listOf<Any>(false, 5, 40, 0), listOf<Any>(S.isFloatingMode(context, false),
            S.getFloatingOffsetX(context, false), S.getFloatingOffsetY(context, false), S.getFloatingWidthDp(context, false)))
    }

    /** 剪贴板同步页显示的插件 = 后台实际在用的插件：偏好不可用或没选时都是第一个已启用的。 */
    @Test fun clipboardSyncShowsThePluginTheServiceUses() {
        val available = listOf("a" to 1, "b" to 2)
        assertEquals("b", com.kingzcheung.xime.ui.settings.effectiveClipboardSyncPlugin(available, "b")?.first)
        assertEquals("a", com.kingzcheung.xime.ui.settings.effectiveClipboardSyncPlugin(available, "")?.first)
        assertEquals("a", com.kingzcheung.xime.ui.settings.effectiveClipboardSyncPlugin(available, "removed")?.first)
        assertEquals(null, com.kingzcheung.xime.ui.settings.effectiveClipboardSyncPlugin(emptyList<Pair<String, Int>>(), "a"))
    }

    private val context = mock<Context>()
    private val prefs = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(context.applicationContext).thenReturn(context)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(prefs.getInt(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1) }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        whenever(prefs.getBoolean(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        whenever(prefs.getFloat(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as? Float ?: it.getArgument<Float>(1) }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer {
            val value = it.getArgument<String?>(1)
            if (value != null) values[it.getArgument(0)] = value else values.remove(it.getArgument<String>(0))
            editor
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
        whenever(editor.putInt(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }
        whenever(editor.putFloat(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Float>(1); editor }
        whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
    }
}
