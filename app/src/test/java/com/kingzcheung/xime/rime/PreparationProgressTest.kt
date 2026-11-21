package com.kingzcheung.xime.rime

import com.kingzcheung.xime.platform.DeviceVendor
import com.kingzcheung.xime.ui.settings.preparationLogTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 设置向导第 2 步显示准备到哪一步、超时提示（11-20 小米 HyperOS 4 用户卡在「正在准备词库」）。 */
class PreparationProgressTest {
    @Test fun dictionariesComeFromTheShippedSchemas() {
        val zhuyin = File("src/main/assets/rime_chinese/cyime_zhuyin.schema.yaml").readText()
        assertTrue(PreparationProgress.dictionariesIn(zhuyin).contains("rime_ice"))
        val cangjie = File("src/main/assets/rime_chinese/cyime_cangjie.schema.yaml").readText()
        assertTrue(PreparationProgress.dictionariesIn(cangjie).contains("cyime_cangjie"))
        // 顶格的 dictionary（不是 translator 下面的）和注释不算。
        assertEquals(setOf("a", "b"), PreparationProgress.dictionariesIn(
            "translator:\n  dictionary: a\nmelt_eng:\n  dictionary: \"b\"\n# dictionary: c\ndictionary: d\n"))
    }

    @Test fun progressMovesThroughTheSteps() {
        val s = PreparationProgress.State()
        assertEquals(0f, PreparationProgress.fraction(s), 0f)
        assertEquals(0f, PreparationProgress.fraction(s.copy(step = PreparationProgress.Step.COPY)), 0f)
        assertEquals(1f / 3, PreparationProgress.fraction(s.copy(step = PreparationProgress.Step.ENGINE)), 1e-4f)
        assertEquals(2f / 3, PreparationProgress.fraction(s.copy(step = PreparationProgress.Step.COMPILE)), 1e-4f)
        assertEquals(2f / 3 + 1f / 6, PreparationProgress.fraction(
            s.copy(step = PreparationProgress.Step.COMPILE, done = 1, total = 2)), 1e-4f)
        assertEquals(1f, PreparationProgress.fraction(s.copy(step = PreparationProgress.Step.DONE)), 0f)
    }

    @Test fun adviceNamesTheSystemSetting() {
        val xiaomi = PreparationProgress.stallAdvice(PreparationProgress.Step.COMPILE, DeviceVendor.XIAOMI, lowStorage = false)
        assertTrue(xiaomi.any { "HyperOS" in it && "无限制" in it })
        assertTrue(xiaomi.none { "剩余空间" in it })
        assertTrue(xiaomi.last().contains("导出日志"))
        val copy = PreparationProgress.stallAdvice(PreparationProgress.Step.COPY, DeviceVendor.SAMSUNG, lowStorage = false)
        assertTrue(copy.any { "剩余空间" in it })
        assertTrue(PreparationProgress.stallAdvice(null, DeviceVendor.OTHER, lowStorage = true).any { "剩余空间" in it })
    }

    @Test fun logTailKeepsPreparationLines() {
        val log = """
            2026-11-20 23:10:00.000 [W] RimeConfigHelper: Build directory missing, empty or damaged, running full deploy
            2026-11-20 23:10:01.000 [W] ImeKeyRouter: something else
            2026-11-20 23:13:01.000 [W] PreparationProgress: Stalled 180s at COMPILE "正在写 rime_ice.table.bin（38 MB）" 0/3
                at com.kingzcheung.xime.rime.RimeEngine.nativeDeploy(Native Method)
        """.trimIndent()
        val tail = preparationLogTail(log)
        assertEquals(2, tail.size)
        assertTrue(tail[0].startsWith("23:10:00.000 [W] RimeConfigHelper"))
        assertTrue(tail[1].contains("Stalled 180s"))
        assertEquals(1, preparationLogTail(log, lines = 1).size)
    }

    @Test fun sizes() {
        assertEquals("1 KB", PreparationProgress.sizeText(10))
        assertEquals("38 MB", PreparationProgress.sizeText(38L * 1024 * 1024 + 5))
    }
}
