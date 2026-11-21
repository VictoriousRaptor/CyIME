package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「候选栏显示刚复制的内容」：关掉后候选栏不放刚复制的，验证码照旧。 */
class ClipboardBarSuggestionTest {
    private val sms = ClipboardItem(text = "123456", timestamp = 1_000)
    private val copied = listOf(
        ClipboardItem(text = "刚复制的", timestamp = 2_000),
        ClipboardItem(text = "早就复制的", timestamp = 10),
        ClipboardItem(text = "贴过了", timestamp = 2_000, consumed = true),
        ClipboardItem(text = "123456", timestamp = 2_000),
    )

    @Test fun onShowsTheCodeThenFreshUnusedCopies() {
        assertEquals(listOf("123456", "刚复制的"), recentBarItems(sms, copied, cutoff = 500, includeCopied = true).map { it.text })
    }

    @Test fun offKeepsOnlyTheCode() {
        assertEquals(listOf("123456"), recentBarItems(sms, copied, cutoff = 500, includeCopied = false).map { it.text })
        assertEquals(emptyList<String>(), recentBarItems(null, copied, cutoff = 500, includeCopied = false).map { it.text })
    }
}
