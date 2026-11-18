package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCandidateHighlightTest {
    @Test fun highlightsOnlyTheEditedPartOfTheFirstSentence() {
        val active = SentenceCandidateHighlight("今天我们去", 2, 4)
        val first = sentenceCandidateText("今天我们去", 0, null, active, Color.Blue)
        assertEquals(listOf(2 to 4), first.spanStyles.map { it.start to it.end })
        assertTrue(sentenceCandidateText("今天我们去", 1, null, active, Color.Blue).spanStyles.isEmpty())
        assertTrue(sentenceCandidateText("今天他们去", 0, null, active, Color.Blue).spanStyles.isEmpty())
    }

    @Test fun accountsForNumberLabelsAndChangesInReplacementLength() {
        val active = SentenceCandidateHighlight("今天大家一起去", 2, 6)
        val first = sentenceCandidateText("1 今天大家一起去", 0, null, active, Color.Blue)
        assertEquals(listOf(4 to 8), first.spanStyles.map { it.start to it.end })
        val deleted = SentenceCandidateHighlight("今天去", 2, 2)
        assertTrue(sentenceCandidateText("今天去", 0, null, deleted, Color.Blue).spanStyles.isEmpty())
    }

    @Test fun draggingUsesTheLiveSelection() {
        val active = SentenceCandidateHighlight("今天我们去", 2, 4)
        val first = sentenceCandidateText("今天我们去", 0, 1..1, active, Color.Blue)
        assertEquals(listOf(1 to 2), first.spanStyles.map { it.start to it.end })
    }
    @Test fun keepsLaterSyllableSelectionsAfterSelectingAnEarlierCandidate() {
        val first = com.kingzcheung.xime.rime.PinyinSyllableOption(1, "ni", 0, 2, true)
        val second = com.kingzcheung.xime.rime.PinyinSyllableOption(2, "hao", 3, 5, true)
        assertEquals(listOf(second.copy(start = 0, end = 2)), remainingSentenceSyllables(listOf(first, second), 3))
        assertTrue(remainingSentenceSyllables(listOf(first, second), 5).isEmpty())
    }
}
