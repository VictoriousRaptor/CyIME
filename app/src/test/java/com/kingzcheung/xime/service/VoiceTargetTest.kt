package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * 翻译栏里语音输入（11-11）：开始说话时焦点在键盘自己的输入框，这次说的话写进那个框，不写应用的框、不发到电脑；
 * 说到一半焦点挪走也不换框。
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceTargetTest {
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPrefs: SharedPreferences
    @Mock private lateinit var hostConnection: InputConnection
    @Mock private lateinit var barConnection: InputConnection
    @Mock private lateinit var mockManager: SpeechRecognitionManager
    @Mock private lateinit var mockMainHandler: Handler

    /** 当前焦点所在的键盘内部输入框；null 表示焦点在应用的输入框。 */
    private var focused: String? = null
    private var barConnected = true
    private val sent = mutableListOf<String>()
    private var hostComposingMarks = 0
    private var currentState = InputUIState(currentSchemaId = "rime_ice")
    private lateinit var target: VoiceTarget<String>
    private lateinit var handler: VoiceRecognitionHandler
    private lateinit var onResult: (String) -> Unit
    private lateinit var onPartial: (String) -> Unit
    private lateinit var onState: (RecognitionState) -> Unit

    @Before
    fun setup() {
        whenever(mockContext.getSharedPreferences(any(), anyInt())).thenReturn(mockPrefs)
        whenever(mockPrefs.getBoolean(any(), any<Boolean>())).thenAnswer { it.getArgument(1) }
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_USE_LOCAL), any<Boolean>())).thenReturn(true)
        whenever(mockManager.finishTimeoutMs).thenReturn(8000L)
        whenever(mockMainHandler.postDelayed(any<Runnable>(), anyLong())).thenReturn(true)
        target = VoiceTarget(
            focusedInternal = { focused },
            connectionFor = { box -> if (box == "bar" && barConnected) barConnection else null },
            host = { hostConnection },
        )
        handler = VoiceRecognitionHandler(
            context = mockContext,
            onStateChanged = { currentState = it },
            getState = { currentState },
            getInputConnection = { target.connection() },
            managerFactory = { mockManager },
            mainHandler = mockMainHandler,
            // 和输入法服务里一样：写进键盘自己的框时不标应用的组合区，不发到电脑。
            onComposingWritten = { if (!target.internal) hostComposingMarks++ },
            remoteTyping = { if (target.internal) null else { text: String -> sent += text } },
            onSessionStart = { target.begin() },
        )
        handler.initialize()
        val results = argumentCaptor<(String) -> Unit>()
        val partials = argumentCaptor<(String) -> Unit>()
        val states = argumentCaptor<(RecognitionState) -> Unit>()
        verify(mockManager).setCallbacks(results.capture(), partials.capture(), states.capture(), any(), any(), any())
        onResult = results.firstValue
        onPartial = partials.firstValue
        onState = states.firstValue
    }

    private fun start(toolbar: Boolean) {
        currentState = currentState.copy(voiceSticky = toolbar, isVoiceMode = true)
        handler.startRecognition()
        onState(RecognitionState.LISTENING)
    }

    @Test fun `按住说话写进翻译栏，应用输入框不动`() {
        focused = "bar"
        start(toolbar = false)
        onPartial("你好")
        handler.finishRecognition()
        onResult("你好世界")
        verify(barConnection).setComposingText(eq("你好"), anyInt())
        verify(barConnection).commitText(eq("世界"), anyInt())
        verify(hostConnection, never()).setComposingText(any(), anyInt())
        verify(hostConnection, never()).commitText(any(), anyInt())
        assertEquals(0, hostComposingMarks)
        assertTrue(sent.isEmpty())
    }

    @Test fun `工具栏听写中焦点挪走也接着写翻译栏`() {
        focused = "bar"
        start(toolbar = true)
        focused = null
        onResult("今天天气不错")
        verify(barConnection).commitText(eq("今天天气不错"), anyInt())
        verify(hostConnection, never()).commitText(any(), anyInt())
    }

    @Test fun `焦点在应用输入框时照旧写应用的框、隔空输入照旧`() {
        focused = null
        start(toolbar = false)
        assertFalse(target.internal)
        onPartial("你好")
        handler.commitPendingOnRelease()
        assertEquals(listOf("你好"), sent)
        assertEquals(1, hostComposingMarks)
        verify(barConnection, never()).setComposingText(any(), anyInt())
    }

    @Test fun `翻译栏拿不到连接时不落到应用输入框`() {
        focused = "bar"
        barConnected = false
        start(toolbar = false)
        assertSame("bar", target.editor)
        assertNull(target.connection())
        onPartial("你好")
        handler.finishRecognition()
        onResult("你好")
        verify(hostConnection, never()).setComposingText(any(), anyInt())
        verify(hostConnection, never()).commitText(any(), anyInt())
    }

    @Test fun `下一次说话按当时的焦点重新选框`() {
        focused = "bar"
        start(toolbar = false)
        assertTrue(target.internal)
        handler.finishRecognition(); onResult("")
        focused = null
        start(toolbar = false)
        assertFalse(target.internal)
    }
}
