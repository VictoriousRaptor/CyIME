package com.kingzcheung.xime.service

import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config

/** Exercises production tracking without starting the service's native input engine. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CursorMagnifierServiceTest {
    private val service = mock<XimeInputMethodService>()
    private val state = mutableStateOf(InputUIState(inputSessionId = 7L))
    private val connection = mock<InputConnection> {
        on { requestCursorUpdates(any(), any()) } doReturn true
    }
    private val monitoring = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR

    init {
        setField("uiState", state)
        setField("cursorMagnifierVisible${'$'}delegate", mutableStateOf(false))
        setField("cursorMagnifierVisibilityEpoch${'$'}delegate", mutableLongStateOf(0L))
        whenever(service.uiState).thenReturn(state)
        whenever(service.currentInputConnection).thenReturn(connection)
        whenever(service.hasHardwareKeyboard).thenReturn(false)
        whenever(service.cursorMagnifierVisible).thenCallRealMethod()
        whenever(service.cursorMagnifierVisibilityEpoch).thenCallRealMethod()
        doCallRealMethod().whenever(service).updateCursorMagnifierVisibility(any())
        doCallRealMethod().whenever(service).trackCursorMagnifier(any(), any())
    }

    private fun setField(name: String, value: Any?) {
        XimeInputMethodService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun field(name: String): Any? = XimeInputMethodService::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(service)
    }

    @Test fun hidingStopsProductionMonitoringAndDropsGeometryUntilANewGesture() {
        service.updateCursorMagnifierVisibility(true)
        verifyNoInteractions(connection)
        service.trackCursorMagnifier(7L, true)
        verify(connection).requestCursorUpdates(eq(monitoring), any())
        // Seed objects that otherwise keep the editor's last reported geometry alive.
        setField("cursorMagnifierAnchor", HardwareCursorAnchor(20f, 30f, 20f, 50f))
        setField("cursorMagnifierAnchorInfo", mock<CursorAnchorInfo>())
        setField("cursorMagnifierLine", 12..20)
        setField("cursorMagnifierLineCaret", 16)
        service.updateCursorMagnifierVisibility(false)
        assertFalse(service.cursorMagnifierVisible)
        assertEquals(1L, service.cursorMagnifierVisibilityEpoch)
        assertEquals(false, field("cursorMagnifierActive"))
        assertEquals(false, field("cursorMagnifierMonitoring"))
        assertNull(field("cursorMagnifierAnchor"))
        assertNull(field("cursorMagnifierAnchorInfo"))
        assertNull(field("cursorMagnifierLine"))
        assertEquals(-1, field("cursorMagnifierLineCaret"))
        inOrder(connection) {
            verify(connection).requestCursorUpdates(eq(monitoring), any())
            verify(connection).requestCursorUpdates(0)
        }
        clearInvocations(connection)

        // A delayed composition callback must not turn tracking back on while hidden.
        service.trackCursorMagnifier(7L, true)
        service.updateCursorMagnifierVisibility(false)
        service.updateCursorMagnifierVisibility(true)
        verifyNoInteractions(connection)
        assertEquals(1L, service.cursorMagnifierVisibilityEpoch)
        assertEquals(false, field("cursorMagnifierActive"))
        service.trackCursorMagnifier(7L, true)
        service.trackCursorMagnifier(7L, true)
        verify(connection, times(1)).requestCursorUpdates(eq(monitoring), any())
        service.trackCursorMagnifier(7L, false)
        verify(connection).requestCursorUpdates(0)
    }

    @Test fun staleSessionCallbacksCannotStartOrStopTheCurrentGesture() {
        service.updateCursorMagnifierVisibility(true)
        service.trackCursorMagnifier(6L, true)
        verifyNoInteractions(connection)
        service.trackCursorMagnifier(7L, true)
        clearInvocations(connection)
        service.trackCursorMagnifier(6L, false)
        service.trackCursorMagnifier(6L, true)
        assertEquals(true, field("cursorMagnifierActive"))
        verifyNoInteractions(connection)
        service.trackCursorMagnifier(7L, false)
        verify(connection).requestCursorUpdates(0)
    }
}
