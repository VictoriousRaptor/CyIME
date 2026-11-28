package com.kingzcheung.xime.ui.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.StrokeKeyboardLayout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 11-26 群友截图：笔画键帽 一丨丿丶乛 要粗而统一。画出浅色、深色两种键面，对照截图看笔画粗细。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xxhdpi")
class StrokeKeycapRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun renderStrokeKeycaps() {
        val out = System.getenv("CYIME_RENDER_OUT")
        org.junit.Assume.assumeTrue(!out.isNullOrEmpty())
        AppFonts.initialize(rule.activity.application)
        KeysConfigHelper.loadConfig(rule.activity.application)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences()) {
                MaterialTheme {
                    Column(Modifier.background(Color(0xFF808080)).padding(4.dp)) {
                        for ((key, text, bg) in listOf(Triple(Color.White, Color(0xFF202020), Color(0xFFE8E8EC)),
                            Triple(Color(0xFF3A3A3C), Color.White, Color(0xFF1C1C1E)))) {
                            Box(Modifier.requiredSize(380.dp, 260.dp).background(bg)) {
                                StrokeKeyboardLayout({}, key, text, Color(0xFFB0B0B8),
                                    modifier = Modifier.requiredSize(380.dp, 260.dp))
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out).mkdirs()
        File(out, "stroke-keycaps.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
