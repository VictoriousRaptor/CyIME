package com.kingzcheung.xime.association

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-16 要的马来语：叠词 kanak-kanak 打一半就补；马来语词不够时补英文。 */
class MalayCompletionTest {
    private val malay = listOf("kanak", "kata", "kami", "makan", "mayur", "meeting")
    private val english = listOf("meet", "meeting", "meetings", "kangaroo")
    private val askedEnglish = mutableListOf<String>()

    private fun complete(typed: String, limit: Int = 5) = runBlocking {
        MalayCompletion.complete(typed, limit, { prefix -> malay.filter { it.startsWith(prefix) && it != prefix } },
            { prefix, count -> askedEnglish += prefix; english.filter { it.startsWith(prefix) }.take(count) })
    }

    @Test fun reduplicationRightAfterTheHyphen() {
        assertEquals(listOf("kanak-kanak"), complete("kanak-"))
        assertEquals(listOf("kanak-kanak", "kanak-kata", "kanak-kami"), complete("kanak-k"))
        // 后半个词不是重复：照常按连字符后面那段补。
        assertEquals(listOf("sayur-makan", "sayur-mayur", "sayur-meeting"), complete("sayur-m"))
        // 大小写跟着打的走。
        assertEquals(listOf("Kanak-kanak"), complete("Kanak-"))
        assertEquals(listOf("KANAK-KANAK"), complete("KANAK-"))
        // 打全了不重复给自己；叠词不补英文。
        assertEquals(emptyList<String>(), complete("kanak-kanak"))
        assertEquals(emptyList<String>(), askedEnglish)
    }

    @Test fun englishFillsWhatMalayLacks() {
        // 马来语词排前面，补不满时补英文，同一个词不出两次。
        assertEquals(listOf("meeting", "meet", "meetings"), complete("mee"))
        assertEquals(listOf("kanak", "kata", "kami", "kangaroo"), complete("ka"))
        // 马来语已经够了就不问英文。
        askedEnglish.clear()
        assertEquals(listOf("kanak", "kata"), complete("ka", limit = 2))
        assertEquals(emptyList<String>(), askedEnglish)
    }
}
