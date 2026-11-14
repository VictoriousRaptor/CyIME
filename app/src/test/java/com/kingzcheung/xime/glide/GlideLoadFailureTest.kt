package com.kingzcheung.xime.glide

import android.content.Context
import android.content.res.AssetManager
import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import java.io.FileNotFoundException

/** 滑行词表读不出来时（1.15.82 有人遇到 glide_pinyin.txt 找不到）抬手识别不闪退，这一笔当没认出（用户 11-12）。 */
class GlideLoadFailureTest {
    @Test fun missingLexiconGivesNoCandidatesInsteadOfCrashing() {
        val result = GlideLexicon.decodeOrEmpty { throw FileNotFoundException("glide_pinyin.txt") }
        assertTrue(result.isEmpty())
    }

    @Test fun normalDecodeIsPassedThrough() {
        val candidates = listOf(GlideCandidate("ni", 1f))
        assertEquals(candidates, GlideLexicon.decodeOrEmpty { candidates })
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellationStillPropagates() {
        GlideLexicon.decodeOrEmpty { throw kotlinx.coroutines.CancellationException("lifted") }
    }

    /** 整条路径：读 assets 就抛 FileNotFoundException 的 Context，抬手识别也不抛（词表若已被别的测试读进缓存则照常识别）。 */
    @Test fun strokeWithUnreadableAssetsDoesNotThrow() {
        val assets = mock<AssetManager> { on { open(any<String>()) } doThrow FileNotFoundException("glide_pinyin.txt") }
        val context = mock<Context>()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.assets).thenReturn(assets)
        val keys: Map<Char, LetterKeyRect> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, inset) ->
                letters.forEachIndexed { index, char -> put(char, LetterKeyRect((inset + index) * 60f, row * 80f, 60f, 80f)) }
            }
        }
        val xs = floatArrayOf(390f, 360f, 330f, 300f)
        val ys = floatArrayOf(200f, 170f, 140f, 120f)
        for (chinese in listOf(true, false)) {
            GlideLexicon.decodeStroke(context, GlideLexicon.keyboard(keys), chinese, null, xs, ys)
        }
    }
}
