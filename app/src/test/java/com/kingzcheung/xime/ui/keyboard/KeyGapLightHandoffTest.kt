package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 切换扩散交给下一块键盘（KeyGapLightHandoff，用户 11-22：点符号、切语言要有全键盘扩散）。 */
class KeyGapLightHandoffTest {
    @After fun reset() = KeyGapLightHandoff.clear()

    @Test fun `a fresh press is handed over once`() {
        KeyGapLightHandoff.post(Offset(40f, 300f), nowMillis = 1_000L)
        assertEquals(Offset(40f, 300f), KeyGapLightHandoff.take(nowMillis = 1_150L))
        // 拿过就清掉：同一下按键不会让两块键盘各扩一次。
        assertNull(KeyGapLightHandoff.take(nowMillis = 1_160L))
    }

    @Test fun `a press long ago is not handed over`() {
        KeyGapLightHandoff.post(Offset(40f, 300f), nowMillis = 1_000L)
        assertNull(KeyGapLightHandoff.take(nowMillis = 1_000L + 901L))
    }

    @Test fun `the latest press wins`() {
        KeyGapLightHandoff.post(Offset(10f, 10f), nowMillis = 1_000L)
        KeyGapLightHandoff.post(Offset(500f, 320f), nowMillis = 1_100L)
        assertEquals(Offset(500f, 320f), KeyGapLightHandoff.take(nowMillis = 1_200L))
    }

    @Test fun `a cancelled touch hands nothing over`() {
        KeyGapLightHandoff.post(Offset(40f, 300f), nowMillis = 1_000L)
        KeyGapLightHandoff.clear()
        assertNull(KeyGapLightHandoff.take(nowMillis = 1_050L))
    }

    @Test fun `nothing posted gives nothing`() {
        assertNull(KeyGapLightHandoff.take(nowMillis = 5_000L))
    }
}
