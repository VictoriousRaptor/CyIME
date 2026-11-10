package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CursorMagnifierAnchorTest {
    @Test fun trackingRequestsMonitoringAndInsertionGeometryWithoutDroppingLineMetrics() {
        val connection = mock<InputConnection> {
            on { requestCursorUpdates(any(), any()) } doReturn true
        }
        assertTrue(requestCursorMagnifierUpdates(connection))
        verify(connection).requestCursorUpdates(eq(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR),
            argThat { this and InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER != 0 &&
                this and InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS != 0 &&
                this and InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS != 0 })
    }

    @Test fun hostsRejectingFiltersReceiveUnfilteredMonitorRequest() {
        val connection = mock<InputConnection> {
            on { requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR) } doReturn true
        }
        assertTrue(requestCursorMagnifierUpdates(connection))
        verify(connection).requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
    }
}
