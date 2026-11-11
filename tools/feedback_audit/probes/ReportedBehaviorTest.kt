package com.kingzcheung.xime.audit

import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.settings.searchSettings
import com.kingzcheung.xime.ui.theme.BackgroundMotionPath
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedBehaviorTest {
    @Test fun independentEnglishOffersNineKeys() {
        InputProfiles.updateInstalled(emptyList())
        KeysConfigHelper.clearSchemaBindingsForTest()
        val installed = listOf("rime_ice", "pinyin_14jian", "t9_pinyin").map { SchemaInfo(it, it, "", "", "") }
        val all = InputModes.available(installed, InputModes.defaultModeOrder)
        fun layouts(language: InputLanguage) =
            InputProfileSelection.layoutRow(all.filter { it.language == language }, all).map { it.id }
        assertTrue("Fixture must include Chinese nine-key", InputLayout.T9.id in layouts(InputLanguage.CHINESE))
        val english = layouts(InputLanguage.ENGLISH)
        assertTrue("English layout choices = $english", InputLayout.T9.id in english)
    }

    @Test fun sentenceCapitalizationIsFindable() {
        assertTrue("Control: settings search should find the existing flick option",
            searchSettings("长按").any { it.title == "拂动选字母" })
        for (query in listOf("首字母大写", "句首大写", "首句大写", "自动大写", "英文句首自动大写", "俄语")) {
            assertTrue("未找到开关：$query", searchSettings(query).any { it.title == "首字母大写" })
        }
    }

    @Test fun zoomInKeepsItsDirectionUntilLoopBoundary() = checkZoom(BackgroundMotionEffect.ZOOM_IN, 1)
    @Test fun zoomOutKeepsItsDirectionUntilLoopBoundary() = checkZoom(BackgroundMotionEffect.ZOOM_OUT, -1)

    private fun checkZoom(effect: BackgroundMotionEffect, direction: Int) {
        val shot = 1000L
        val duration = BackgroundMotionPath.cycleMs(effect, shot)
        val frames = (0L until duration step 50L).map { BackgroundMotionPath.at(effect, it, shot).scale }
        assertTrue("Must actually move in selected direction",
            (frames[frames.size / 3] - frames.first()) * direction > .001f)
        val reversal = frames.zipWithNext().indexOfFirst { (a, b) -> (b - a) * direction < -.0001f }
        assertTrue("Reverse motion at ${(reversal + 1) * 50} ms: ${frames.drop(reversal.coerceAtLeast(0)).take(2)}",
            reversal < 0)
    }
}
