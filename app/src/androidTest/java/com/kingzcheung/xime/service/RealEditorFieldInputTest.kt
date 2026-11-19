package com.kingzcheung.xime.service

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.kingzcheung.xime.XimeApplication
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test

/** Fictional, disposable editors only. No account, clipboard read, password screenshot or text log. */
@SdkSuppress(minSdkVersion = 29)
class RealEditorFieldInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun fieldSessionsUseLiteralKeysAndRestoreSavedLanguage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.javaClass.name == "androidx.test.runner.AndroidJUnitRunner")
        val context = instrumentation.targetContext
        check(context.applicationContext is XimeApplication)
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeId = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == imeId }
        lateinit var host: FrameLayout
        lateinit var editor: FixtureEditor
        var editorReady = false
        var service: XimeInputMethodService? = null
        var fieldId = 100
        fun openField(type: Int, action: Int = EditorInfo.IME_ACTION_DONE, prompt: String? = null,
                      expectedKind: EditorFieldPolicy.Kind = EditorFieldPolicy.fromInputType(type).kind) {
            rule.runOnUiThread {
                editor = FixtureEditor(rule.activity).apply {
                    id = ++fieldId
                    inputType = type
                    imeOptions = action
                    hint = prompt
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) doneActions++
                        true
                    }
                }
                editorReady = true
                host.removeAllViews()
                host.addView(editor, FrameLayout.LayoutParams(-1, 180))
                editor.requestFocus()
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(30_000) {
                rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                service?.currentInputEditorInfo?.fieldId == fieldId && service?.inputReadiness?.ticket() != null &&
                    service?.keyboardCallbacks != null && service?.editorInputPolicy?.field?.kind == expectedKind
            }
        }
        fun typeKey(key: String, shifted: Boolean = false) {
            rule.runOnUiThread { checkNotNull(service?.keyboardCallbacks).onKeyPress(key, shifted) }
        }
        fun drainInputQueue() {
            val complete = java.util.concurrent.atomic.AtomicBoolean(false)
            rule.runOnUiThread {
                checkNotNull(service).keyRouter.postRimeJob {
                    withContext(Dispatchers.Main) { complete.set(true) }
                }
            }
            rule.waitUntil(10_000) { complete.get() }
        }
        fun assertText(expected: String) {
            var matches = false
            try {
                rule.waitUntil(10_000) {
                    rule.runOnUiThread { matches = editor.text.toString() == expected }
                    matches
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                var metadata = ""
                rule.runOnUiThread {
                    metadata = "fieldId=$fieldId inputType=${editor.inputType} expectedLength=${expected.length} " +
                        "actualLength=${editor.length()} policy=${service?.editorInputPolicy?.field?.kind} " +
                        "direct=${service?.editorInputPolicy?.directInput} ascii=${service?.uiState?.value?.isAsciiMode} " +
                        "pendingCommands=${service?.inputCommands?.hasPendingCommands}"
                }
                throw AssertionError("literal editor result mismatch; $metadata", timeout)
            }
            // Boolean assertion deliberately avoids printing even the fictional field contents.
            assertTrue("literal editor result", matches)
        }
        try {
            shell("ime enable $imeId"); shell("ime set $imeId")
            rule.setContent { AndroidView(factory = { FrameLayout(it).also { frame -> host = frame } }) }
            rule.waitForIdle()
            openField(InputType.TYPE_CLASS_TEXT)
            drainInputQueue()
            val actual = checkNotNull(service)
            val savedSchema = SettingsPreferences.getCurrentSchema(context)
            val savedAscii = actual.rimeEngine.getUserConfigBool("var/option/ascii_mode")
            val passwordTypes = listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            for (type in passwordTypes) {
                openField(type)
                rule.waitUntil(10_000) { actual.uiState.value.isAsciiMode }
                typeKey("a"); typeKey("b")
                assertText("ab")
                // 文字密码框和可见密码框一样：语言键能切到中文，只在这个框里有效（用户 11-16）；再按一次切回英文。
                typeKey("ime_switch")
                rule.waitUntil(10_000) { !actual.uiState.value.isAsciiMode }
                typeKey("ime_switch")
                rule.waitUntil(10_000) { actual.uiState.value.isAsciiMode }
                typeKey("c")
                assertText("abc")
                rule.runOnUiThread { actual.textCommit.commitLiteralText("！") }
                assertText("abc!")
                assertFalse(actual.candidateState.value.isComposing)
                assertTrue(actual.candidateState.value.pendingEnglishText.isEmpty())
                assertTrue(actual.candidateState.value.associationCandidates.isEmpty())
                assertTrue(actual.rimeEngine.getInput().isEmpty())
                typeKey("q", shifted = true)
                assertText("abc!Q")
                typeKey("delete")
                assertText("abc!")
                rule.runOnUiThread { editor.setSelection(1, 3) }
                typeKey("delete")
                assertText("a!")
                rule.runOnUiThread { editor.setSelection(editor.length()) }
                typeKey("enter")
                rule.waitUntil(10_000) { editor.doneActions == 1 }
                assertText("a!")
                rule.runOnUiThread {
                    val event = KeyEvent(1L, 1L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
                    actual.keyRouter.handleKeyPress("\t", true, hardwareEvent = event)
                }
                rule.waitUntil(10_000) { editor.tabDowns == 1 && editor.tabUps == 1 }
                assertTrue("Tab retains Shift for backwards form navigation", editor.tabShifted)
                assertText("a!")
                val previousSession = actual.uiState.value.inputSessionId
                rule.runOnUiThread { imm.restartInput(editor) }
                rule.waitUntil(10_000) { actual.uiState.value.inputSessionId != previousSession && actual.inputReadiness.ticket() != null }
                // 重新进框还是英文。
                rule.waitUntil(10_000) { actual.uiState.value.isAsciiMode }
                typeKey("r")
                assertText("a!r")
                assertTrue(actual.uiState.value.isAsciiMode)
                // Explicit paste is intentionally not subject to typed-key ASCII conversion.
                rule.runOnUiThread { actual.commitPastedText("文") }
                assertText("a!r文")
            }
            val numeric = listOf(
                Triple(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, "7", "7"),
                Triple(InputType.TYPE_CLASS_NUMBER, "-.7", "7"),
                Triple(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, "-7.5", "-75"),
                Triple(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, "-7.5", "-7.5"),
                Triple(InputType.TYPE_CLASS_PHONE, "+7*#", "+7*#"),
                Triple(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, "7/8", "7/8"),
                Triple(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, "7:08", "7:08"),
            )
            for ((type, keys, expected) in numeric) {
                openField(type)
                rule.waitUntil(10_000) { actual.keyboardViewModel.keyboardState.value is KeyboardLayoutState.Number }
                keys.forEach { typeKey(it.toString()) }
                assertText(expected)
                // 'a' is valid AM/PM input in a time field; use a letter no numeric kind accepts.
                typeKey("ime_switch"); typeKey("中"); typeKey("z")
                // Queue a valid key so the subsequent check also observes the rejected keys completing.
                typeKey("2")
                assertText(expected + "2")
                assertTrue(actual.rimeEngine.getInput().isEmpty())
            }
            for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)) {
                openField(InputType.TYPE_CLASS_TEXT or variation)
                rule.waitUntil(10_000) { actual.uiState.value.isAsciiMode }
                typeKey("a"); typeKey("＠"); typeKey("b"); typeKey("。"); typeKey("c")
                assertText("a@b.c")
                assertFalse(actual.editorInputPolicy.locksNativeInput)
                val nativeSchema = actual.rimeEngine.getCurrentSchema()
                val nativeLanguage = InputModes.languageOf(nativeSchema, actual.uiState.value.schemas)
                if (nativeLanguage in LanguagePreferences.enabled(context)) {
                    // Exercise the public native selection, without enabling a disabled user language.
                    rule.runOnUiThread { actual.schemaController.switchSchema(nativeSchema) }
                    drainInputQueue()
                    assertFalse("explicit internationalized address input remains available", actual.uiState.value.isAsciiMode)
                    assertFalse(actual.editorInputPolicy.directInput)
                    val previousSession = actual.uiState.value.inputSessionId
                    rule.runOnUiThread { imm.restartInput(editor) }
                    rule.waitUntil(10_000) { actual.uiState.value.inputSessionId != previousSession && actual.inputReadiness.ticket() != null }
                    drainInputQueue()
                    assertFalse("restart preserves this field's explicit language", actual.uiState.value.isAsciiMode)
                    assertFalse(actual.rimeEngine.isAsciiMode())
                    assertText("a@b.c")
                }
            }
            openField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_GO, "在 Google 中搜索或输入网址", EditorFieldPolicy.Kind.SEARCH_OR_ADDRESS)
            drainInputQueue()
            assertEquals("the navigation bar inherits the original language", savedAscii, actual.uiState.value.isAsciiMode)
            assertEquals(savedAscii, actual.rimeEngine.isAsciiMode())
            assertFalse(actual.editorInputPolicy.directInput)

            openField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            drainInputQueue()
            rule.waitUntil(10_000) { actual.uiState.value.isAsciiMode == savedAscii }
            assertFalse(actual.editorInputPolicy.scoped)
            assertEquals("temporary fields must not save a schema", savedSchema, SettingsPreferences.getCurrentSchema(context))
            assertEquals("temporary fields must not save ASCII mode", savedAscii,
                actual.rimeEngine.getUserConfigBool("var/option/ascii_mode"))
        } finally {
            rule.runOnUiThread {
                if (editorReady) {
                    editor.text.clear()
                    imm.hideSoftInputFromWindow(editor.windowToken, 0)
                }
            }
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!wasEnabled && previousIme != imeId) shell("ime disable $imeId")
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    /** Records control-event metadata only, never any text passing through InputConnection. */
    private class FixtureEditor(context: Context) : EditText(context) {
        var doneActions = 0
        var tabDowns = 0
        var tabUps = 0
        var tabShifted = false
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_TAB) {
                        if (event.action == KeyEvent.ACTION_DOWN) tabDowns++
                        if (event.action == KeyEvent.ACTION_UP) tabUps++
                        tabShifted = event.isShiftPressed
                        // The single disposable field has no previous/next focus target.
                        return true
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }

    private fun findService(view: View): XimeInputMethodService? {
        var context: Context? = view.context
        while (context != null) {
            if (context is XimeInputMethodService) return context
            context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) findService(view.getChildAt(index))?.let { return it }
        return null
    }
}
