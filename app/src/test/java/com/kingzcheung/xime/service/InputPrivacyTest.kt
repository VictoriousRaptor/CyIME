package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.settings.IncognitoState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPrivacyTest {
    private val saved = Triple(IncognitoState.manual, IncognitoState.sensitiveEditor, IncognitoState.clipboardSearch)
    private val savedPasswordField = IncognitoState.passwordField
    private val savedPasswordEditor = IncognitoState.passwordEditor
    private val engine = mutableListOf<Boolean>()
    private val privacy = InputPrivacy { engine += it }.also {
        it.loadManual(false)
        IncognitoState.sensitiveEditor = false
        IncognitoState.clipboardSearch = false
        IncognitoState.passwordEditor = false
    }

    @After fun restore() {
        IncognitoState.manual = saved.first
        IncognitoState.sensitiveEditor = saved.second
        IncognitoState.clipboardSearch = saved.third
        IncognitoState.passwordField = savedPasswordField
        IncognitoState.passwordEditor = savedPasswordEditor
    }

    private fun field(inputType: Int, imeOptions: Int = 0) =
        EditorInfo().apply { this.inputType = inputType; this.imeOptions = imeOptions }
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    @Test fun theEngineFollowsEveryFieldOnceWithoutRepeats() {
        privacy.beginEditor(field(text))
        privacy.beginEditor(field(text))
        privacy.beginEditor(field(password))
        privacy.beginEditor(field(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        privacy.beginEditor(field(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        privacy.beginEditor(field(text))
        assertEquals(listOf(false, true, false), engine)
    }

    @Test fun searchOpenedInAPasswordFieldKeepsItPrivateAfterClosing() {
        privacy.beginEditor(field(password))
        privacy.setClipboardSearch(true)
        privacy.setClipboardSearch(false)
        assertTrue(IncognitoState.active)
        assertEquals(listOf(true), engine)
    }

    @Test fun aFieldChangeDuringSearchIsNotUndoneWhenSearchCloses() {
        privacy.beginEditor(field(text))
        privacy.setClipboardSearch(true)
        privacy.beginEditor(field(password))
        privacy.setClipboardSearch(false)
        assertTrue(IncognitoState.active)
        // And back: a normal field learns again once search closes.
        privacy.setClipboardSearch(true)
        privacy.beginEditor(field(text))
        privacy.setClipboardSearch(false)
        assertFalse(IncognitoState.active)
        assertEquals(listOf(false, true, false), engine)
    }

    @Test fun theManualSwitchDuringSearchOutlivesIt() {
        privacy.beginEditor(field(text))
        privacy.setClipboardSearch(true)
        privacy.setManual(true)
        privacy.setClipboardSearch(false)
        assertTrue(IncognitoState.active)
        privacy.setManual(false)
        assertEquals(listOf(false, true, false), engine)
    }

    @Test fun theSavedSwitchWaitsForTheFirstField() {
        privacy.loadManual(true)
        assertTrue(engine.isEmpty())
        privacy.beginEditor(field(text))
        assertEquals(listOf(true), engine)
    }

    /** 用户 11-09：网页上复制的普通文字被当成密码。剪贴板只认开着的藏字密码框。 */
    @Test fun clipboardCountsOnlyAnOpenHiddenPasswordField() {
        privacy.beginEditor(field(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(IncognitoState.active)
        assertFalse(IncognitoState.passwordField)
        privacy.beginEditor(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(IncognitoState.passwordField)
        privacy.beginEditor(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(IncognitoState.passwordField)
        privacy.beginEditor(field(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(IncognitoState.passwordField)
        privacy.beginEditor(field(password))
        assertTrue(IncognitoState.passwordField)
        // Leaving the field: a copy made on a web page afterwards is not from a password field.
        privacy.endEditor()
        assertFalse(IncognitoState.passwordField)
        assertTrue(IncognitoState.active)
    }

    @Test fun lockedWordOrderStopsEngineLearningWithoutIncognito() {
        privacy.beginEditor(field(text))
        privacy.setLockWordOrder(true)
        assertFalse("剪贴板和联想照常", IncognitoState.active)
        privacy.beginEditor(field(text))
        privacy.setLockWordOrder(false)
        assertEquals(listOf(false, true, false), engine)
    }

    @Test fun passwordDisableLearningToggleControlsLearningWhileKeepingSecurityBoundary() {
        privacy.loadPasswordDisableLearning(true)
        privacy.beginEditor(field(text))
        assertFalse(IncognitoState.active)
        assertFalse(IncognitoState.passwordEditor)

        // 默认开启：进入密码框临时关闭学习，离开后恢复。
        privacy.beginEditor(field(password))
        assertTrue(IncognitoState.active)
        assertTrue(IncognitoState.passwordEditor)
        assertTrue(IncognitoState.editorSecuritySensitive)
        assertTrue(IncognitoState.securitySensitive)
        privacy.beginEditor(field(text))
        assertFalse(IncognitoState.active)
        assertFalse(IncognitoState.passwordEditor)
        assertFalse(IncognitoState.editorSecuritySensitive)

        // 关闭开关：密码框不关闭学习，但安全边界（passwordEditor / securitySensitive）仍有效；
        // IME_FLAG_NO_PERSONALIZED_LEARNING 与手动无痕照常生效。
        privacy.setPasswordDisableLearning(false)
        privacy.beginEditor(field(password))
        assertFalse(IncognitoState.active)
        assertTrue(IncognitoState.passwordEditor)
        assertTrue(IncognitoState.editorSecuritySensitive)
        assertTrue(IncognitoState.securitySensitive)

        // 在密码框内实时切换开关：立即关闭/恢复引擎学习。
        privacy.setPasswordDisableLearning(true)
        assertTrue(IncognitoState.active)
        privacy.setPasswordDisableLearning(false)
        assertFalse(IncognitoState.active)

        // 离开密码框结束编辑后，passwordEditor 与 securitySensitive 立即清掉，不残留到后续输入框外操作。
        privacy.endEditor()
        assertFalse(IncognitoState.passwordEditor)
        assertFalse(IncognitoState.editorSecuritySensitive)
        assertFalse(IncognitoState.securitySensitive)

        privacy.beginEditor(field(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(IncognitoState.active)
        assertEquals(listOf(false, true, false, true, false, true), engine)
    }
}
