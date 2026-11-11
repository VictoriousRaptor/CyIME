package com.kingzcheung.xime.service

import android.graphics.Matrix
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class CursorMagnifierLegacyLineTest {
    @Test fun olderAndroidUsesActualCharacterBoundsWithoutTheNewApi() = runTest {
        val connection = mock<InputConnection>()
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(4, 4).apply {
            for (index in 0 until 8) {
                val x = (index % 4) * 10f
                val y = (index / 4) * 20f
                addCharacterBounds(index, x, y, x + 10f, y + 20f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            }
        }.build()
        assertEquals(0 until 4, readCursorMagnifierLine(connection, 4, HardwareCursorAnchor(40f, 0f, 40f, 20f), info))
        assertEquals(4 until 8, readCursorMagnifierLine(connection, 4, HardwareCursorAnchor(0f, 20f, 0f, 40f), info))
        verifyNoInteractions(connection)
    }

    @Test fun unavailableOrHiddenCharactersNeverExpandTheVerifiedInterval() = runTest {
        val connection = mock<InputConnection>()
        val info = CursorAnchorInfo.Builder().setMatrix(Matrix()).setSelectionRange(3, 3)
            .addCharacterBounds(1, 10f, 0f, 20f, 20f, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)
            .addCharacterBounds(2, 20f, 0f, 30f, 20f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
            .addCharacterBounds(3, 30f, 0f, 40f, 20f, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION).build()
        assertEquals(2 until 4, readCursorMagnifierLine(connection, 3, HardwareCursorAnchor(30f, 0f, 30f, 20f), info))
        verifyNoInteractions(connection)
    }
}
