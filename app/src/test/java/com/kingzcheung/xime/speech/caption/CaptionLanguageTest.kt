package com.kingzcheung.xime.speech.caption

import org.junit.Assert.assertEquals
import com.kingzcheung.xime.ui.caption.CaptionLanguage
import org.junit.Test

class CaptionLanguageTest {
    @Test fun `Zipformer words keep their characters and take SenseVoice punctuation`() {
        val words = "インターネットで敵対的環境構想について検索すると恐らく現地企業の住所が出てくるでしょう"
        val senseVoice = "インターネットで敵対的環境コースについて検索すると、おそらく現地企業の住所が出てくるでしょう。"
        assertEquals("インターネットで敵対的環境構想について検索すると、恐らく現地企業の住所が出てくるでしょう。",
            projectPunctuation(words, senseVoice))
    }

    @Test fun `spaces from token timing go and nothing is invented without marks`() {
        assertEquals("今日はいい天気", projectPunctuation("今日 は いい 天気", "今日はいい天気"))
        assertEquals("今日はいい天気", projectPunctuation("今日 は いい 天気", ""))
        assertEquals("", projectPunctuation("", "はい。"))
    }

    @Test fun `Japanese and English captions keep their own script, Chinese still drops foreign text`() {
        assertEquals("はい、そうです。", chooseFinal("はいそうです", "はい、そうです。", chineseOnly = false))
        assertEquals("Nice to meet you.", chooseFinal("nice to meet you", "Nice to meet you.", chineseOnly = false))
        assertEquals("你好", chooseFinal("你好", "こんにちは", chineseOnly = true))
    }

    @Test fun `elder package captions offer only Chinese, and a restored Japanese choice reads as Chinese`() {
        val elder = CaptionLanguage.forPackage(elder = true)
        assertEquals(listOf(CaptionLanguage.CHINESE), elder)
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.of("ja", elder))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.of("en", elder))
        val normal = CaptionLanguage.forPackage(elder = false)
        assertEquals(CaptionLanguage.JAPANESE, CaptionLanguage.of("ja", normal))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.of(null, normal))
    }

    /** 用户 11-14：字幕一进来跟界面语言，中文界面就是中文；界面是没有字幕的语言时也是中文。 */
    @Test fun `captions start in the interface language`() {
        val normal = CaptionLanguage.forPackage(elder = false)
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.forInterface("zh-CN", normal))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.forInterface("zh-Hant-TW", normal))
        assertEquals(CaptionLanguage.JAPANESE, CaptionLanguage.forInterface("ja-JP", normal))
        assertEquals(CaptionLanguage.ENGLISH, CaptionLanguage.forInterface("en-US", normal))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.forInterface("ko-KR", normal))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.forInterface(null, normal))
        assertEquals(CaptionLanguage.CHINESE, CaptionLanguage.forInterface("en-US", CaptionLanguage.forPackage(elder = true)))
    }
}
