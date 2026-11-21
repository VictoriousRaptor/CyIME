package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.then
import org.mockito.kotlin.whenever

/**
 * 语音松手收尾状态机测试：finishRecognition 停止送音后等待引擎最终结果，
 * 超时才回退提交部分结果（修复"说完立刻松手，尾部语音被截断"）。
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceRecognitionHandlerFinishTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockInputConnection: InputConnection

    @Mock
    private lateinit var mockManager: SpeechRecognitionManager

    @Mock
    private lateinit var mockMainHandler: Handler

    private lateinit var handler: VoiceRecognitionHandler

    private val stateChanges = mutableListOf<InputUIState>()
    private var recordingStoppedCount = 0
    private var voiceCompleteCount = 0
    private var currentState = InputUIState(currentSchemaId = "rime_ice")
    private var wholeSentence = false
    private var autoPunctuation = false
    /** 繁体输出时注入的简转繁；测试里用逐字对照代替 OpenCC。 */
    private var traditional: ((String) -> String)? = null
    private val fakeS2t = mapOf('说' to '說', '话' to '話', '语' to '語', '纠' to '糾', '错' to '錯')
    private fun fakeTraditional(text: String) = text.map { fakeS2t[it] ?: it }.joinToString("")

    /** mock mainHandler 的 postDelayed 队列，可手动推进超时。 */
    private val posted = mutableListOf<Runnable>()

    private lateinit var onResult: (String) -> Unit
    private lateinit var onPartial: (String) -> Unit
    private lateinit var onState: (RecognitionState) -> Unit
    private lateinit var onError: (String, Boolean) -> Unit

    @Before
    fun setup() {
        whenever(mockContext.getSharedPreferences(any(), anyInt())).thenReturn(mockPrefs)
        // 所有布尔设置返回默认值；isSttUseLocal 置 true 使 resolveProviderName 走本地引擎
        // 早退（不触碰未初始化的 PluginManager/ExtensionManager），warmup 分支为异步线程无副作用
        whenever(mockPrefs.getBoolean(any(), any<Boolean>())).thenAnswer { it.getArgument(1) }
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_USE_LOCAL), any<Boolean>()))
            .thenReturn(true)
        // 整句定稿默认开；这些用例断言的是关闭时（每 6 秒校正、标点变空格）的行为，开启时的用例单独设 wholeSentence = true。
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_WHOLE_SENTENCE), any<Boolean>()))
            .thenAnswer { wholeSentence }
        // 自动加标点默认关（1.15.0 以前的习惯）；保留模型标点的用例单独设 autoPunctuation = true。
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_AUTO_PUNCTUATION), any<Boolean>()))
            .thenAnswer { autoPunctuation }
        whenever(mockManager.finishTimeoutMs).thenReturn(8000L)
        whenever(mockMainHandler.postDelayed(any<Runnable>(), anyLong())).thenAnswer {
            posted.add(it.getArgument(0))
            true
        }
        whenever(mockMainHandler.removeCallbacks(any<Runnable>())).thenAnswer {
            posted.remove(it.getArgument<Runnable>(0))
        }

        handler = VoiceRecognitionHandler(
            context = mockContext,
            onRecordingStopped = { recordingStoppedCount++ },
            onStateChanged = { stateChanges.add(it); currentState = it },
            getState = { currentState },
            getInputConnection = { mockInputConnection },
            onVoiceComplete = { voiceCompleteCount++ },
            managerFactory = { mockManager },
            mainHandler = mockMainHandler,
            traditionalConverter = { traditional },
        )
        handler.initialize()

        val onResultCaptor = argumentCaptor<(String) -> Unit>()
        val onPartialCaptor = argumentCaptor<(String) -> Unit>()
        val onStateCaptor = argumentCaptor<(RecognitionState) -> Unit>()
        val onErrorCaptor = argumentCaptor<(String, Boolean) -> Unit>()
        verify(mockManager).setCallbacks(
            onResultCaptor.capture(), onPartialCaptor.capture(), onStateCaptor.capture(),
            onErrorCaptor.capture(), any(), any()
        )
        onResult = onResultCaptor.firstValue
        onPartial = onPartialCaptor.firstValue
        onState = onStateCaptor.firstValue
        onError = onErrorCaptor.firstValue
    }

    private fun runTimeouts() {
        val copy = posted.toList()
        posted.clear()
        copy.forEach { it.run() }
    }

    @Test fun japaneseFinalizationIsNotCutOffByTheOldUiDeadline() {
        whenever(mockManager.finishTimeoutMs).thenReturn(18000L)
        onPartial("今日は東京")
        handler.finishRecognition()
        verify(mockMainHandler).postDelayed(any<Runnable>(), eq(18500L))
        onResult("今日は東京 明日は大阪")
        assertTrue(posted.isEmpty())
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `收尾时收到最终结果则提交完整结果并取消超时兜底`() {
        onPartial.invoke("你好")
        handler.finishRecognition()

        // 停止送音、进入"正在识别"、安排了超时兜底
        verify(mockManager).stopRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)
        assertEquals(1, posted.size)

        // 引擎吐出最终结果：提交增量部分（partial 已通过 composing 上屏，不追加句读）
        onResult.invoke("你好世界再见")

        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("世界再见"), eq(1))
        assertEquals(1, voiceCompleteCount)
        // 超时兜底已被取消，手动推进不再重复提交
        assertTrue(posted.isEmpty())
        runTimeouts()
        verify(mockInputConnection, never()).commitText(eq("你好"), eq(1))
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `超时未收到最终结果则回退提交部分结果且忽略迟到的最终结果`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        runTimeouts()

        // 部分结果已实时上屏，兜底只结束 composing
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        assertEquals(1, voiceCompleteCount)

        // 迟到的最终结果被抑制：不再写入输入框（避免重复/错乱）
        onResult.invoke("你好世界再见")
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        assertEquals(2, voiceCompleteCount)
    }

    @Test
    fun `无已识别文本时收尾不等待直接结束`() {
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)
        assertTrue(posted.isEmpty())
        assertFalse(stateChanges.any { it.voiceRecognitionState == RecognitionState.PROCESSING })
    }

    @Test
    fun `收尾中重复调用幂等`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, posted.size)
        // 收尾尚未完成（在等最终结果），不触发 onVoiceComplete
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `会话被丢弃后收尾不提交文本且超时兜底失效`() {
        onPartial.invoke("你好")
        handler.abandonSession()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        runTimeouts()
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).finishComposingText()
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `收尾期间引擎的IDLE状态不覆盖正在识别显示`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 引擎 stop 过程中回调 IDLE：保持"正在识别..."显示
        onState.invoke(RecognitionState.IDLE)
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 其他状态（如 ERROR）不被过滤
        onState.invoke(RecognitionState.ERROR)
        assertEquals(RecognitionState.ERROR, stateChanges.last().voiceRecognitionState)
    }

    @Test
    fun `部分结果写入composing区域并同步到UI状态`() {
        onPartial.invoke("你好")

        verify(mockInputConnection).setComposingText(eq("你好"), eq(1))
        assertEquals("你好", stateChanges.last().voiceRecognizedText)
    }

    @Test
    fun `录音中收到流式最终结果则上屏该句但会话继续`() {
        onPartial.invoke("你好")
        // 用户尚未松手，流式插件按句回调 final：该句上屏，会话必须继续
        onResult.invoke("你好")

        // 结束 composing，但不结束会话、不停止录音——
        // 若此时触发 onVoiceComplete，松手停止链即失效，录音会一直在后台运行
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockManager, never()).stopRecognition()
        assertEquals(0, voiceCompleteCount)

        // 随后松手：正常收尾结束会话
        onPartial.invoke("世界")
        handler.finishRecognition()
        verify(mockManager).stopRecognition()
        onResult.invoke("世界")
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `录音中引擎报错则停止录音并结束会话且丢弃迟到结果`() {
        onError.invoke("网络断开", false)

        // 录音中报错：必须显式停止录音并结束会话，否则 UI 恢复后松手停止链失效
        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)

        // 错误后迟到的部分/最终结果不再写入输入框（迟到 final 仍走一次幂等完成）
        onPartial.invoke("你好")
        onResult.invoke("你好世界")
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
    }

    @Test fun `pressing voice again while the last one is finishing commits its visible text first`() {
        // 2026-11-16：上一次还在等最终结果（整段重识别）时又按了开始，上一次的字留在组字区，被这一次的临时结果盖掉。
        val committed = StringBuilder()
        var composing = ""
        whenever(mockInputConnection.setComposingText(any(), anyInt())).thenAnswer {
            composing = it.getArgument<CharSequence>(0).toString(); true
        }
        whenever(mockInputConnection.finishComposingText()).thenAnswer { committed.append(composing); composing = ""; true }
        whenever(mockInputConnection.commitText(any(), anyInt())).thenAnswer {
            composing = ""; committed.append(it.getArgument<CharSequence>(0)); true
        }
        handler.startRecognition()
        onPartial("今天下午开会")
        handler.finishRecognition()
        handler.startRecognition() // pressed again before the final arrived
        onPartial("别迟到")
        assertEquals("今天下午开会", committed.toString())
        assertEquals("别迟到", composing)
        verify(mockManager, times(2)).startRecognition(any())
    }

    private val editor = StringBuilder()

    private fun segmentedResult(vararg parts: SpeechSegment) {
        val callback = argumentCaptor<(List<SpeechSegment>, Boolean) -> Unit>()
        verify(mockManager).segmentedTextCallback = callback.capture()
        callback.firstValue(parts.toList(), false)
    }

    @Test fun mixedOnlineAndFallbackSegmentsRespectTheirOwnPunctuation() {
        autoPunctuation = true
        startToolbar()
        segmentedResult(
            SpeechSegment("你好，世界。", InputLanguage.CHINESE, true),
            SpeechSegment("本地，备用。", InputLanguage.CHINESE, false),
        )
        assertEquals("你好，世界。 本地 备用", editor.toString())
    }

    @Test fun disabledPunctuationAlsoAppliesToOnlineResults() {
        startToolbar()
        segmentedResult(SpeechSegment("你好，世界。", InputLanguage.CHINESE, true))
        assertEquals("你好 世界", editor.toString())
    }

    private fun startToolbar() {
        whenever(mockInputConnection.getTextBeforeCursor(anyInt(), anyInt())).thenAnswer {
            editor.takeLast(it.getArgument<Int>(0)).toString()
        }
        whenever(mockInputConnection.commitText(any(), anyInt())).thenAnswer {
            editor.append(it.getArgument<CharSequence>(0)); true
        }
        whenever(mockInputConnection.deleteSurroundingText(anyInt(), anyInt())).thenAnswer {
            editor.delete((editor.length - it.getArgument<Int>(0)).coerceAtLeast(0), editor.length); true
        }
        currentState = currentState.copy(voiceSticky = true, isVoiceMode = true)
        handler.startRecognition()
        onState(RecognitionState.LISTENING)
    }

    @Test fun `toolbar stop immediately releases button while late final remains accepted`() {
        startToolbar(); onPartial("你好")
        handler.finishRecognition()
        assertEquals(1, recordingStoppedCount)
        assertEquals(0, voiceCompleteCount)
        onResult("你好世界")
        assertEquals("你好世界", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `local empty final after a completed sentence does not wait for timeout`() {
        startToolbar(); onResult("完整一句")
        handler.finishRecognition(); onResult(""); onState(RecognitionState.IDLE)
        assertEquals(1, voiceCompleteCount)
        assertEquals("完整一句", editor.toString())
        assertTrue(posted.isEmpty())
    }

    @Test fun `typing after stop rejects stale correction`() {
        startToolbar(); onPartial("你好")
        handler.finishRecognition(); handler.abandonPendingOnManualInput()
        mockInputConnection.commitText("手动", 1)
        onResult("你好世界")
        assertEquals("你好手动", editor.toString())
    }

    @Test fun `switching to English while finishing retains the Chinese final exactly once`() {
        startToolbar()
        onPartial("你好")
        handler.finishRecognition()
        assertEquals(1, posted.size)
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        assertEquals(1, posted.size)
        assertEquals(RecognitionState.PROCESSING, currentState.voiceRecognitionState)
        assertEquals(0, voiceCompleteCount)
        verify(mockManager, never()).cancelRecognition()

        onResult("你好世界句号")
        assertEquals("你好世界。", editor.toString())
        assertTrue(posted.isEmpty())
        repeat(2) { onResult("你好世界句号") }
        runTimeouts()
        assertEquals("你好世界。", editor.toString())
        verify(mockInputConnection).commitText("世界。", 1)
        verify(mockInputConnection, times(2)).commitText(any(), anyInt())
        verify(mockManager).startRecognition(InputLanguage.CHINESE)
        verify(mockManager, times(1)).startRecognition(any())
        verify(mockManager).stopRecognition()
        assertEquals(1, recordingStoppedCount)
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `manual correction after a finishing language toggle still rejects the pending final`() {
        startToolbar()
        onPartial("泥好")
        handler.finishRecognition()
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        assertEquals(1, posted.size)

        handler.abandonPendingOnManualInput()
        editor.replace(0, editor.length, "你好（手动修改）")
        org.mockito.Mockito.clearInvocations(mockInputConnection)
        assertTrue(posted.isEmpty())
        onPartial("泥好世界")
        repeat(2) { onResult("泥好世界句号") }
        runTimeouts()
        assertEquals("你好（手动修改）", editor.toString())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
        verify(mockManager).stopRecognition()
        assertEquals(1, recordingStoppedCount)
    }

    @Test fun `toolbar streams partial and sentence results before stop without duplicates`() {
        startToolbar()
        onPartial("你好")
        assertEquals("你好", editor.toString())
        onResult("你好世界")
        assertEquals("你好世界", editor.toString())
        assertEquals(0, voiceCompleteCount)
        onPartial("继续")
        assertEquals("你好世界 继续", editor.toString())
        handler.finishRecognition()
        onResult("继续说话")
        assertEquals("你好世界 继续说话", editor.toString())
        assertEquals(1, voiceCompleteCount)
        repeat(2) { onResult("继续说话") }
        assertEquals("你好世界 继续说话", editor.toString())
    }

    @Test fun `toolbar partial revisions replace only its own visible tail`() {
        startToolbar()
        onPartial("泥好世界")
        assertEquals("泥好世界", editor.toString())
        onPartial("你好世界")
        assertEquals("你好世界", editor.toString())
        handler.finishRecognition(); onResult("你好世界")
        assertEquals("你好世界", editor.toString())
    }

    @Test fun `toolbar speech preserves intervening manual text`() {
        startToolbar()
        onPartial("你好")
        mockInputConnection.commitText("手动文字", 1)
        onPartial("你好世界")
        assertEquals("你好手动文字世界", editor.toString())
        handler.finishRecognition(); onResult("你好世界")
        assertEquals("你好手动文字世界", editor.toString())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
    }

    @Test fun `toolbar speech does not overwrite a manual selection`() {
        startToolbar()
        onPartial("你好")
        whenever(mockInputConnection.getSelectedText(0)).thenReturn("用户选中的文字")
        onPartial("你好世界")
        handler.finishRecognition(); onResult("你好世界")
        assertEquals("你好", editor.toString())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
    }

    @Test fun `toolbar accepts final only engines after stop`() {
        startToolbar()
        handler.finishRecognition()
        assertEquals(0, voiceCompleteCount)
        onResult("最终识别结果")
        verify(mockInputConnection).commitText(eq("最终识别结果"), eq(1))
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `toolbar timeout finishes the visible partial once and rejects late results`() {
        startToolbar()
        onResult("第一句话")
        onPartial("第二句")
        assertEquals("第一句话 第二句", editor.toString())
        handler.finishRecognition(); runTimeouts()
        assertEquals("第一句话 第二句", editor.toString())
        repeat(2) { onResult("第二句话") }
        assertEquals("第一句话 第二句", editor.toString())
    }

    @Test fun `toolbar abandoning session rejects all late results`() {
        startToolbar()
        onPartial("不要提交")
        handler.abandonSession()
        repeat(2) { onResult("不要提交") }
        onPartial("不要提交")
        assertEquals("不要提交", editor.toString())
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
    }

    @Test fun `toolbar stop replay does not duplicate the previous final`() {
        startToolbar()
        onResult("完整的一句")
        handler.finishRecognition()
        onResult("完整的一句")
        verify(mockInputConnection).commitText(eq("完整的一句"), eq(1))
    }
    @Test fun `输入会话变化后迟到语音不能写入新输入框`() {
        currentState = currentState.copy(inputSessionId = 1)
        startToolbar()
        onPartial("第一句")
        org.mockito.Mockito.clearInvocations(mockInputConnection)
        currentState = currentState.copy(inputSessionId = 2)
        onPartial("第一句残留")
        onResult("第一句残留最终结果")
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
    }

    @Test fun `partial final and sequential sentences use spaces without terminal punctuation`() {
        startToolbar()
        onPartial("你好，world！")
        assertEquals("你好 world", editor.toString())
        onResult("你好，world。")
        assertEquals("你好 world", editor.toString())
        onPartial("How are")
        assertEquals("你好 world How are", editor.toString())
        handler.finishRecognition()
        onResult("How are you？")
        assertEquals("你好 world How are you", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `whole sentence with auto punctuation keeps the local model's Chinese punctuation`() {
        wholeSentence = true
        autoPunctuation = true
        startToolbar()
        onPartial("今天天气不错，我们去公园吧。")
        assertEquals("今天天气不错，我们去公园吧。", editor.toString())
        handler.finishRecognition()
        onResult("今天天气不错，我们去公园吧。明天见。")
        assertEquals("今天天气不错，我们去公园吧。明天见。", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `whole sentence without auto punctuation turns model marks into spaces like before 1_15`() {
        wholeSentence = true
        startToolbar()
        onPartial("今天天气不错，我们去公园吧。")
        assertEquals("今天天气不错 我们去公园吧", editor.toString())
        handler.finishRecognition()
        onResult("今天天气不错，我们去公园吧。明天见。")
        assertEquals("今天天气不错 我们去公园吧 明天见", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `whole sentence without auto punctuation still writes dictated marks`() {
        wholeSentence = true
        startToolbar()
        onPartial("我到了，逗号，马上下来句号。")
        assertEquals("我到了，马上下来。", editor.toString())
    }

    @Test fun `whole sentence leaves the English side of a Chinese pair with spaces`() {
        wholeSentence = true
        autoPunctuation = true
        currentState = currentState.copy(isAsciiMode = true)
        startToolbar()
        onPartial("Hello, world!")
        assertEquals("Hello world", editor.toString())
        handler.finishRecognition()
        onResult("Hello world.")
        assertEquals("Hello world", editor.toString())
    }

    @Test fun `whole sentence leaves a standalone English profile with the old spacing`() {
        wholeSentence = true
        autoPunctuation = true
        currentState = currentState.copy(currentSchemaId = "english", isAsciiMode = true)
        startToolbar()
        onPartial("How are you? Fine.")
        assertEquals("How are you Fine", editor.toString())
    }

    @Test fun `whole sentence keeps punctuation when the segment language is unspecified`() {
        // #415：段语言不是恰好「中文」（未标注语言也按中文识别）时，开着自动加标点也只剩空格。
        wholeSentence = true
        autoPunctuation = true
        startToolbar()
        segmentedResult(SpeechSegment("今天天气不错，我们去公园吧。", InputLanguage.UNSPECIFIED, false))
        assertEquals("今天天气不错，我们去公园吧。", editor.toString())
    }

    @Test fun `whole sentence leaves Japanese with the old spacing`() {
        wholeSentence = true
        autoPunctuation = true
        currentState = currentState.copy(currentSchemaId = "japanese")
        startToolbar()
        onPartial("今日は、東京です。")
        assertEquals("今日は 東京です", editor.toString())
    }

    @Test fun `final revisions remove punctuation already present in partial without deleting words`() {
        startToolbar()
        onPartial("Hello, wor")
        onPartial("Hello, world!")
        handler.finishRecognition()
        onResult("Hello world.")
        assertEquals("Hello world", editor.toString())
    }


    @Test fun `local idle cannot commit a preview before the final callback`() {
        startToolbar(); onPartial("未校正预览")
        handler.finishRecognition(); onState(RecognitionState.IDLE)
        assertEquals(0, voiceCompleteCount)
        onResult("已校正文本")
        assertEquals("已校正文本", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `empty multilingual final removes its unsupported preview`() {
        startToolbar(); onPartial("不应上屏的预览")
        handler.finishRecognition(); onResult("")
        assertEquals("", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `ITN final replaces Chinese decimal preview without losing point or dictated punctuation`() {
        startToolbar(); onPartial("零点零五")
        onPartial("0.05句号")
        assertEquals("0.05。", editor.toString())
        handler.finishRecognition(); onResult("0.05句号。")
        assertEquals("0.05。", editor.toString())
    }

    @Test fun `arabic numbers switch converts preview and final the same way and is off by default`() {
        startToolbar(); onPartial("三百二十五块")
        assertEquals("三百二十五块", editor.toString())
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_ARABIC_NUMBERS), any<Boolean>())).thenReturn(true)
        onPartial("三百二十五块零点五")
        assertEquals("325块0.5", editor.toString())
        handler.finishRecognition(); onResult("三百二十五块零点五句号")
        assertEquals("325块0.5。", editor.toString())
    }

    @Test fun `release and timeout never normalize already converted punctuation twice`() {
        startToolbar(); onPartial("结果逗号0.05句号")
        handler.commitPendingOnRelease()
        assertEquals("结果，0.05。", editor.toString())
    }

    @Test fun `composing release retains explicit terminal punctuation`() {
        onPartial("0.05句号")
        handler.commitPendingOnRelease()
        verify(mockInputConnection).setComposingText(eq("0.05。"), eq(1))
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
    }
    @Test fun `input ends before voice UI appears cancels prestart and pending recording`() {
        currentState = currentState.copy(isVoiceMode = false)
        handler.startDelayedPreStart()
        handler.endInputSession()
        assertTrue(posted.isEmpty())
        verify(mockManager).cancelPreStart()
        verify(mockManager).cancelRecognition()
        onPartial("late")
        verify(mockInputConnection, never()).setComposingText(any(), anyInt())
    }

    @Test fun `Japanese English Japanese switches request matching recognizers without ending recording`() {
        currentState = currentState.copy(currentSchemaId = "japanese", isAsciiMode = false)
        startToolbar()
        onPartial("句号")
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        onPartial("句号，問題")
        assertEquals("句号 問題", editor.toString())
        currentState = currentState.copy(isAsciiMode = false)
        handler.onInputLanguageChanged()
        onPartial("句号，問題，続く")
        assertEquals("句号 問題 続く", editor.toString())
        assertRecordingContinuesIn(InputLanguage.JAPANESE)
        verify(mockManager).switchRecognitionLanguage(InputLanguage.ENGLISH)
        verify(mockManager).switchRecognitionLanguage(InputLanguage.JAPANESE)
        handler.finishRecognition()
        onResult("句号，問題，続く。")
        assertEquals("句号 問題 続く", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `language change in same editor rejects pending voice text and final timeout`() {
        startToolbar(); onPartial("已有文字")
        handler.finishRecognition()
        val before = editor.toString()
        currentState = currentState.copy(currentSchemaId = "japanese")
        handler.onInputLanguageChanged()
        onPartial("旧语言结果"); onResult("迟到校正"); runTimeouts()
        assertEquals(before, editor.toString())
        verify(mockManager).cancelRecognition()
    }

    @Test fun `same language pair accepts callbacks before its keyboard observer runs`() {
        startToolbar()
        currentState = currentState.copy(isAsciiMode = true)
        onState(RecognitionState.LISTENING)
        onPartial("继续句号")
        assertEquals("继续。", editor.toString())
        onResult("继续句号")
        assertEquals("继续。", editor.toString())
        handler.onInputLanguageChanged()
        assertRecordingContinuesIn(InputLanguage.CHINESE)
    }

    private fun assertRecordingContinuesIn(language: InputLanguage) {
        verify(mockManager).startRecognition(language)
        verify(mockManager, times(1)).startRecognition(any())
        verify(mockManager, never()).cancelRecognition()
        verify(mockManager, never()).stopRecognition()
        assertEquals(RecognitionState.LISTENING, currentState.voiceRecognitionState)
        assertEquals(0, voiceCompleteCount)
    }

    @Test fun `Chinese English Chinese keyboard switches keep Chinese normalization and one recording`() {
        startToolbar()
        onPartial("结果")
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        onPartial("结果逗号")
        assertEquals("结果，", editor.toString())
        currentState = currentState.copy(isAsciiMode = false)
        handler.onInputLanguageChanged()
        onPartial("结果逗号完成")
        assertEquals("结果，完成", editor.toString())
        assertRecordingContinuesIn(InputLanguage.CHINESE)
        handler.finishRecognition()
        onResult("结果逗号完成句号")
        assertEquals("结果，完成。", editor.toString())
        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `English side of Chinese pair starts English recognition and keeps one microphone`() {
        currentState = currentState.copy(isAsciiMode = true)
        startToolbar()
        onPartial("hello")
        currentState = currentState.copy(isAsciiMode = false)
        handler.onInputLanguageChanged()
        onPartial("hello句号")
        assertEquals("hello。", editor.toString())
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        onPartial("hello句号 world")
        assertEquals("hello。world", editor.toString())
        assertRecordingContinuesIn(InputLanguage.ENGLISH)
        verify(mockManager).switchRecognitionLanguage(InputLanguage.CHINESE)
        verify(mockManager).switchRecognitionLanguage(InputLanguage.ENGLISH)
        handler.finishRecognition()
        onResult("hello句号 world!")
        assertEquals("hello。world", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `English side of Japanese pair starts English model and switches both ways`() {
        currentState = currentState.copy(currentSchemaId = "japanese", isAsciiMode = true)
        startToolbar()
        onPartial("hello")
        repeat(4) {
            currentState = currentState.copy(isAsciiMode = !currentState.isAsciiMode)
            handler.onInputLanguageChanged()
        }
        onPartial("hello world")
        assertEquals("hello world", editor.toString())
        assertRecordingContinuesIn(InputLanguage.ENGLISH)
        verify(mockManager, times(2)).switchRecognitionLanguage(InputLanguage.JAPANESE)
        verify(mockManager, times(2)).switchRecognitionLanguage(InputLanguage.ENGLISH)
    }

    @Test fun `a standalone English profile still starts its English recognizer`() {
        currentState = currentState.copy(currentSchemaId = "english", isAsciiMode = true)
        startToolbar()
        assertRecordingContinuesIn(InputLanguage.ENGLISH)
    }

    @Test fun `a different native language rejects callbacks before its observer runs`() {
        startToolbar()
        onPartial("已有文字")
        org.mockito.Mockito.clearInvocations(mockInputConnection)
        currentState = currentState.copy(currentSchemaId = "japanese", isAsciiMode = false)
        onPartial("旧中文增量")
        onResult("旧中文最终结果")
        handler.onInputLanguageChanged()
        assertEquals("已有文字", editor.toString())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(mockManager).cancelRecognition()
        verify(mockManager, times(1)).startRecognition(any())
    }

    @Test fun `changing native language while ASCII remains English cancels the old recording`() {
        currentState = currentState.copy(isAsciiMode = true)
        startToolbar()
        onPartial("existing")
        org.mockito.Mockito.clearInvocations(mockInputConnection)
        currentState = currentState.copy(currentSchemaId = "japanese", isAsciiMode = true)
        assertEquals(InputLanguage.ENGLISH, currentState.inputProfile.language)
        handler.onInputLanguageChanged()
        onPartial("existing stale")
        onResult("existing stale final")
        assertEquals("existing", editor.toString())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(mockManager).cancelRecognition()
        verify(mockManager).startRecognition(InputLanguage.ENGLISH)
        verify(mockManager, times(1)).startRecognition(any())
    }

    @Test fun `ASCII native language changes are detected by callbacks before the observer`() {
        currentState = currentState.copy(isAsciiMode = true)
        startToolbar()
        onPartial("existing")
        org.mockito.Mockito.clearInvocations(mockInputConnection)
        currentState = currentState.copy(currentSchemaId = "japanese", isAsciiMode = true)
        assertEquals(InputLanguage.ENGLISH, currentState.inputProfile.language)
        onPartial("existing stale")
        onResult("existing stale final")
        handler.onInputLanguageChanged()
        assertEquals("existing", editor.toString())
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(mockManager).cancelRecognition()
        verify(mockManager, times(1)).startRecognition(any())
    }

    @Test fun `manual correction during English keyboard input survives later Chinese voice increments`() {
        startToolbar()
        onPartial("泥好")
        currentState = currentState.copy(isAsciiMode = true)
        handler.onInputLanguageChanged()
        handler.abandonPendingOnManualInput()
        editor.replace(0, editor.length, "你好")
        onPartial("泥好世界")
        assertEquals("你好世界", editor.toString())
        currentState = currentState.copy(isAsciiMode = false)
        handler.onInputLanguageChanged()
        onPartial("泥好世界继续")
        assertEquals("你好世界继续", editor.toString())
        assertRecordingContinuesIn(InputLanguage.CHINESE)
        handler.finishRecognition()
        onResult("泥好世界继续")
        assertEquals("你好世界继续", editor.toString())
        verify(mockInputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `changing layout within the same language keeps the voice session`() {
        startToolbar()
        currentState = currentState.copy(currentSchemaId = "t9_pinyin")
        handler.onInputLanguageChanged()
        onPartial("继续")
        assertEquals("继续", editor.toString())
        verify(mockManager, never()).cancelRecognition()
    }

    /** 宿主重设文字后的输入框：光标被放回 [caret]，之后光标跟着上屏走。 */
    private var hostCaret = 0
    private fun hostRestartsField(caret: Int) {
        hostCaret = caret
        whenever(mockInputConnection.getExtractedText(any(), anyInt())).thenAnswer {
            android.view.inputmethod.ExtractedText().also { e ->
                e.text = editor.toString(); e.startOffset = 0; e.selectionStart = hostCaret; e.selectionEnd = hostCaret
            }
        }
        whenever(mockInputConnection.setSelection(anyInt(), anyInt())).thenAnswer { hostCaret = it.getArgument(0); true }
        whenever(mockInputConnection.commitText(any(), anyInt())).thenAnswer {
            editor.append(it.getArgument<CharSequence>(0)); hostCaret = editor.length; true
        }
        currentState = currentState.copy(inputSessionId = currentState.inputSessionId + 1)
    }

    @Test fun `toolbar dictation survives the host restarting the same field and its caret goes back`() {
        startToolbar()
        onPartial("你好世界")
        hostRestartsField(caret = 0)
        assertTrue(handler.continueAfterHostRestart(listOf(0, 4)))
        verify(mockInputConnection).setSelection(4, 4)
        onPartial("你好世界今天")
        assertEquals("你好世界今天", editor.toString())
        verify(mockManager, never()).cancelRecognition()
        handler.finishRecognition(); onResult("你好世界今天")
        assertEquals("你好世界今天", editor.toString())
        assertEquals(1, voiceCompleteCount)
    }

    @Test fun `host restart that replaced the dictated text still ends the dictation`() {
        startToolbar()
        onPartial("你好世界")
        editor.replace(0, editor.length, "别的内容")
        hostRestartsField(caret = 0)
        assertFalse(handler.continueAfterHostRestart(listOf(0, 4)))
        verify(mockInputConnection, never()).setSelection(anyInt(), anyInt())
    }

    @Test fun `hold to talk partial is marked as composing again after the host restart`() {
        handler.startRecognition()
        onPartial("你好")
        editor.append("你好") // 重启时框架把组合区定成了正文
        hostRestartsField(caret = 0)
        whenever(mockInputConnection.setComposingRegion(0, 2)).thenReturn(true)
        assertTrue(handler.continueAfterHostRestart(listOf(0, 2)))
        verify(mockInputConnection).setSelection(2, 2)
        verify(mockInputConnection).setComposingRegion(0, 2)
        onPartial("你好世界")
        verify(mockInputConnection).setComposingText(eq("你好世界"), eq(1))
    }

    @Test
    fun `繁体输出时预览和上屏都是正体`() {
        traditional = this::fakeTraditional
        onPartial.invoke("说话")
        verify(mockInputConnection).setComposingText(eq("說話"), eq(1))
        assertEquals("說話", currentState.voiceRecognizedText)
        handler.finishRecognition()
        onResult.invoke("说话语音")
        // 预览和定稿按同一张表转换，前缀对得上，只补后面的字
        verify(mockInputConnection).commitText(eq("語音"), eq(1))
    }

    @Test
    fun `繁体输出关着时语音照旧出简体`() {
        onPartial.invoke("说话")
        verify(mockInputConnection).setComposingText(eq("说话"), eq(1))
        handler.finishRecognition()
        onResult.invoke("说话语音")
        verify(mockInputConnection).commitText(eq("语音"), eq(1))
    }

    @Test
    fun `繁体输出时简体和正体写的纠错条目都配得上`() {
        traditional = this::fakeTraditional
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_CORRECTIONS), any<Boolean>())).thenReturn(true)
        whenever(mockPrefs.getString(eq(SettingsPreferences.KEY_STT_CORRECTIONS_TEXT), any())).thenReturn("说话 讲话\n語音 纠错")
        onResult.invoke("说话语音")
        // 词表同样转过：简体写的「说话」配上「說話」，正体写的「語音」照样配上；换上的字也是转过的（假表里没有「讲」，「话」转成「話」）
        verify(mockInputConnection).commitText(eq("讲話糾錯"), eq(1))
    }
}
