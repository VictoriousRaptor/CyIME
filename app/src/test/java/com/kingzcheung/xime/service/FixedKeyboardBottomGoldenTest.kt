package com.kingzcheung.xime.service

import android.app.Activity
import android.graphics.Insets
import android.provider.Settings
import android.view.WindowInsets
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SettingsPreferences.KeyboardBottomPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File

/**
 * 固定键盘底部的回归基线。
 * `bottom-unified-golden-*.tsv`：「底部位置 = 自动」按决策表（[classifyBottomNav]，11-19 统一规则）的输出，
 * 覆盖手势导航、三键导航、把整条导航框报成可点区域的机型、只有手势条的机型、横屏、悬浮键盘下限、留白 0／抬高 16／调低 16／调到底。
 * 和 1.15.66 基线比，固定键盘只有「三星 + navigation_mode=0」的行变了（三键导航整条留着，11-19 三星三键被挡）；
 * 悬浮键盘下限在按 inset 认成手势的行跟着变成避开手势区。
 * `bottom-position-choices-golden-*.tsv`：另外两档（贴到最底、留出导航按钮）的固定规则，没变。
 * 以后改底部的任何代码，输出都必须和这几份文件一致；真要改结果，先拿到用户同意，再用环境变量重写文件并在提交里说明：
 * 自动档 `CYIME_BOTTOM_GOLDEN_WRITE_AUTO=1`，另两档 `CYIME_BOTTOM_GOLDEN_WRITE=1`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "sw360dp-w360dp-h800dp-mdpi")
class FixedKeyboardBottomGoldenTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val tab = "\t"
    private val newline = "\n"

    private data class Bars(val name: String, val nav: Int, val tappable: Int, val mandatory: Int)

    private val bars = listOf(
        Bars("gesture-bar-only", nav = 16, tappable = 0, mandatory = 16),
        Bars("nav-frame-no-tappable", nav = 48, tappable = 0, mandatory = 0),
        Bars("nav-frame-reported-tappable", nav = 48, tappable = 48, mandatory = 18),
        Bars("three-button", nav = 48, tappable = 48, mandatory = 48),
        Bars("gesture-small-tappable", nav = 48, tappable = 20, mandatory = 18),
    )

    private fun insetsOf(b: Bars): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, b.nav))
        .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, b.tappable))
        .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, b.mandatory))
        .build()

    private fun navigationMode(mode: String) {
        val resolver = activity.contentResolver
        if (mode == "unset") Settings.Secure.putString(resolver, "navigation_mode", null)
        else Settings.Secure.putInt(resolver, "navigation_mode", mode.toInt())
    }

    private fun autoRows(orientation: String): List<String> = buildList {
        for (maker in listOf("OPPO", "samsung", "unknown")) for (mode in listOf("unset", "0", "2")) for (b in bars)
            for (adjust in listOf(0, 16, -16, -80)) {
                ShadowBuild.setManufacturer(maker)
                navigationMode(mode)
                SettingsPreferences.setKeyboardBottomPosition(activity, KeyboardBottomPosition.AUTO)
                SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, adjust)
                val insets = insetsOf(b)
                val normal = extractBottomInset(insets, activity.window)
                val minimum = fixedKeyboardMinimumInsetPx(insets, activity.window, normal)
                val fixed = fixedKeyboardBottomInsetPx(insets, activity.window, normal, minimum)
                val floating = floatingMinimumInsetPx(activity.window, insets, normal)
                add(listOf(orientation, maker, mode, b.name, adjust, normal, minimum, fixed, floating).joinToString(tab))
            }
    }

    private fun choiceRows(orientation: String): List<String> = buildList {
        for (choice in listOf(KeyboardBottomPosition.FLUSH, KeyboardBottomPosition.KEEP_BUTTONS))
            for (maker in listOf("OPPO", "samsung")) for (mode in listOf("0", "2")) for (b in bars) for (adjust in listOf(0, 16, -16, -80)) {
                ShadowBuild.setManufacturer(maker)
                navigationMode(mode)
                SettingsPreferences.setKeyboardBottomPosition(activity, choice)
                SettingsPreferences.setKeyboardBottomAdjustmentDp(activity, adjust)
                val insets = insetsOf(b)
                val normal = extractBottomInset(insets, activity.window)
                val minimum = fixedKeyboardMinimumInsetPx(insets, activity.window, normal)
                add(listOf(orientation, choice.name, maker, mode, b.name, adjust, normal, minimum,
                    fixedKeyboardBottomInsetPx(insets, activity.window, normal, minimum)).joinToString(tab))
            }
    }

    private fun compare(file: File, header: String, actual: List<String>, writeEnv: String, message: String) {
        if (System.getenv(writeEnv) != null) {
            file.parentFile.mkdirs()
            file.writeText(header + newline + actual.joinToString(newline, postfix = newline))
            return
        }
        val expected = file.readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(message, expected.joinToString(newline), actual.joinToString(newline))
    }

    private fun checkAuto(orientation: String) = compare(File("src/test/resources/bottom-unified-golden-$orientation.tsv"),
        listOf("orientation", "maker", "navigation_mode", "bars", "adjust_dp", "normal", "minimum", "fixed", "floating_minimum")
            .joinToString(tab), autoRows(orientation), "CYIME_BOTTOM_GOLDEN_WRITE_AUTO", "自动档和决策表基线不一致（$orientation）")

    private fun checkChoices(orientation: String) = compare(File("src/test/resources/bottom-position-choices-golden-$orientation.tsv"),
        listOf("orientation", "position", "maker", "navigation_mode", "bars", "adjust_dp", "normal", "minimum", "fixed").joinToString(tab),
        choiceRows(orientation), "CYIME_BOTTOM_GOLDEN_WRITE", "两档固定位置的结果变了（$orientation）")

    @Test fun portraitAutoMatchesTheDecisionTable() = checkAuto("portrait")

    @Test @Config(qualifiers = "sw360dp-w800dp-h360dp-land-mdpi")
    fun landscapeAutoMatchesTheDecisionTable() = checkAuto("landscape")

    @Test fun portraitChoicesStayFixed() = checkChoices("portrait")

    @Test @Config(qualifiers = "sw360dp-w800dp-h360dp-land-mdpi")
    fun landscapeChoicesStayFixed() = checkChoices("landscape")
}
