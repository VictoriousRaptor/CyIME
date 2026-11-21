package com.kingzcheung.xime.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SpeechToTextSettingsContent
import org.junit.Rule
import org.junit.Test

class SpeechSettingsLayoutGateTest {
    @get:Rule val rule = createComposeRule()

    private data class Viewport(val width: Int, val height: Int, val font: Float)
    private val viewports = listOf(
        Viewport(280, 360, 2f),
        Viewport(360, 640, 1.3f),
        Viewport(800, 500, 1f),
    )

    @Test fun onlineSpeechPageFitsShortAndLargeTextViewports() = withUseLocal(false) {
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("speech-settings-host")) {
                        key(v) { SpeechToTextSettingsContent(onBack = {}) }
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("speech-settings-host", "在线语音设置 $v")
            val title = rule.onNodeWithText("语音转文本")
            if (fitsDevice(v)) title.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", title, "标题 $v")
            val engine = rule.onNodeWithText("使用本地模型").performScrollTo()
            if (fitsDevice(v)) engine.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", engine, "本地模型开关 $v")
            val footerNode = rule.onNodeWithText("在线服务需要网络连接", substring = true)
                .performScrollTo()
            if (fitsDevice(v)) footerNode.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", footerNode, "在线说明 $v")
            val footer = footerNode.fetchSemanticsNode().boundsInRoot
            val host = rule.onNodeWithTag("speech-settings-host").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("在线语音页末项超出 $v: $footer / $host", footer.bottom <= host.bottom + 1f)
            rule.assertGeometry("speech-settings-host", "在线语音说明末项 $v")
        }
    }

    @Test fun localSpeechPageCanReachLastModelOptionAtEverySize() = withUseLocal(true) {
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("speech-settings-host")) {
                        key(v) { SpeechToTextSettingsContent(onBack = {}) }
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("speech-settings-host", "本地语音设置 $v")
            rule.onNodeWithText("中文语音").performScrollTo().assertIsDisplayed()
            val lastModel = rule.onNodeWithTag("speech-correction").performScrollTo()
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val host = rule.onNodeWithTag("speech-settings-host").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("本地语音页末项超出 $v: $lastModel / $host", lastModel.bottom <= host.bottom + 1f)
            rule.assertGeometry("speech-settings-host", "本地语音模型末项 $v")
        }
    }

    private fun fitsDevice(viewport: Viewport): Boolean {
        val size = ApplicationProvider.getApplicationContext<Context>().resources.configuration
        return viewport.width <= size.screenWidthDp && viewport.height <= size.screenHeightDp
    }

    private fun assertInsideHost(hostTag: String, node: SemanticsNodeInteraction, scenario: String) {
        val host = rule.onNodeWithTag(hostTag).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("$scenario 不在合成视口内: $bounds / $host",
            bounds.left >= host.left - 1f && bounds.right <= host.right + 1f &&
                bounds.top >= host.top - 1f && bounds.bottom <= host.bottom + 1f)
    }

    private fun withUseLocal(value: Boolean, block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val key = SettingsPreferences.KEY_STT_USE_LOCAL
        val existed = prefs.contains(key)
        val previous = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, value).commit()
        try {
            block()
        } finally {
            prefs.edit().apply {
                if (existed) putBoolean(key, previous) else remove(key)
            }.commit()
        }
    }
}
