package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Dedicated test device: real keyboard -> candidate click -> InputConnection -> learning. */
class T9EnglishImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeCandidatesLearnAndIdleSentencesReachTheRealKeyboard(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Use a touch-only test device; a virtual PC keyboard enables compact mode",
            android.content.res.Configuration.KEYBOARD_NOKEYS, context.resources.configuration.keyboard)
        val engine = RimeEngine.getInstance()
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val previous = engine.getCurrentSchema()
        fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        val previousIme = shell("settings get secure default_input_method").trim()
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        shell("ime enable $ime"); shell("ime set $ime")
        lateinit var editor: EditText
        rule.setContent {
            AndroidView(factory = { EditText(it).also { editor = it; it.hint = "T9 English regression" } },
                modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        rule.runOnUiThread {
            editor.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        try {
            rule.chooseModeThroughLanguageAndPanel("t9_pinyin")
            val labels = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
            val sentenceKeys = "48452474687445394942"
            sentenceKeys.forEach { key -> rule.onNodeWithText(labels.getValue(key), ignoreCase = true).performTouchInput { down(center); up() } }
            // Observe the production controller's idle update, without calling the
            // test-only await/refine helper or replacing the native candidate list.
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("bar-candidate:0") and hasText("回来收拾了一下"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(sentenceKeys, engine.getInput())
            rule.runOnUiThread { assertEquals("", editor.text.toString()) }
            instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(context.getExternalFilesDir(null), "t9-idle-refinement-ime.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            engine.clearQueuedT9Composition()
            for ((word, keys) in listOf("steam" to "78326", "Dota2" to "36822", "CS2" to "272")) {
                var firstRank = -1
                repeat(2) { attempt ->
                    keys.forEach { key -> rule.onNodeWithText(labels.getValue(key), ignoreCase = true).performTouchInput { down(center); up() } }
                    rule.waitUntil(10_000) { engine.getInput() == keys }
                    rule.waitForIdle()
                    val rows = engine.inspectCandidates(200)
                    val rank = rows.indexOfFirst { it[0] == word }
                    assertTrue("$word is recalled", rank >= 0)
                    if (attempt == 0) firstRank = rank
                    else {
                        assertTrue("$word improves after a real host commit", rank <= firstRank)
                        assertEquals("user_table", rows[rank][2])
                    }
                    val bar = rule.onNodeWithTag("bar-candidate:$rank")
                    if (bar.isDisplayed()) bar.performClick()
                    else {
                        rule.onNodeWithTag("candidate-expansion").performClick()
                        rule.onNodeWithTag("expanded-candidates").performScrollToNode(hasTestTag("expanded-candidate:$rank"))
                        rule.onNodeWithTag("expanded-candidate:$rank").performTouchInput { down(center); up() }
                    }
                    rule.waitUntil(10_000) {
                        var accepted = false
                        rule.runOnUiThread { accepted = editor.text.toString() == word }
                        accepted && engine.getInput().isEmpty()
                    }
                    rule.runOnUiThread { assertEquals(word, editor.text.toString()); editor.setText("") }
                }
            }
            instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(context.getExternalFilesDir(null), "t9-english-ime.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            File(context.getExternalFilesDir(null), "t9-ime-state.txt").writeText(
                "input=${engine.getInput()}\nscoring=${engine.t9ScoringStatus()}\nrefinement=${engine.t9RefinementState()}\n" +
                    engine.inspectCandidates(20).joinToString("\n") { it.joinToString(" | ") })
            instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(context.getExternalFilesDir(null), "t9-ime-final.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            engine.clearQueuedT9Composition()
            engine.switchSchema(previous)
            if (previousIme.isNotEmpty() && previousIme != "null") shell("ime set $previousIme")
        }
    }
}
