package com.kingzcheung.xime.service

import com.kingzcheung.xime.association.AssociationCandidate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 连续联想的停止条件（用户 11-13：连续联想的结束逻辑可以优化）。 */
class AssociationChainStopTest {
    @Test fun aConfidentNextWordKeepsTheChainGoing() {
        // 谢谢 → 你：表里 P≈0.31，融合后约 0.22。
        assertTrue(continuesAssociationChain(listOf(AssociationCandidate("你", 0.22f), AssociationCandidate("的", 0.05f))))
        assertTrue(continuesAssociationChain(listOf(AssociationCandidate("了", ASSOCIATION_CHAIN_MIN_SCORE))))
    }

    @Test fun weakOrMissingSuggestionsEndTheChain() {
        // 吗 → 我：一句说完了，表里 P≈0.04，融合后约 0.028。
        assertFalse(continuesAssociationChain(listOf(AssociationCandidate("我", 0.028f), AssociationCandidate("你", 0.02f))))
        assertFalse(continuesAssociationChain(emptyList()))
    }

    @Test fun theBestScoreDecidesWhateverTheOrder() {
        assertTrue(continuesAssociationChain(listOf(AssociationCandidate("人", 0.01f), AssociationCandidate("们", 0.06f))))
    }

    /** 只有选了联想词的上屏才算接着联想；打字上屏照常出（不受分数限制）。 */
    @Test fun onlyAssociationPicksAreChained() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val router = File(root, "app/src/main/java/com/kingzcheung/xime/service/ImeKeyRouter.kt").readText()
        val select = router.substringAfter("internal fun selectAssociation(").substringBefore("\n    }\n")
        assertTrue(select.contains("chained = true"))
        // 空格上屏第一个联想词也是选联想词。
        assertTrue(router.contains("service.commitTextAndPredict(association, isPaste = false, chained = true)"))
        assertEquals(2, Regex("chained = true").findAll(router).count())
        val service = File(root, "app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
        assertTrue(service.contains("override fun commitText(text: String) {\n        commitTextAndPredict(text, isPaste = false)\n"))
    }
}
