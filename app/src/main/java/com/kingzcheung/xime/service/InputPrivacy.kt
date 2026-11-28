package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.settings.IncognitoState

/**
 * The service's one writer of [IncognitoState]. Each source (the manual switch, the current field,
 * clipboard search) changes only here, and every change re-applies the engine's learning switch,
 * so no source can change without the engine following it. Readers keep reading [IncognitoState].
 */
internal class InputPrivacy(private val applyEngine: (active: Boolean) -> Unit) {
    private var applied: Boolean? = null
    private var lockWordOrder = false
    private var passwordDisableLearning = true
    private var currentNoPersonalized = false

    /** The saved switch at start-up; the first field applies it to the engine. */
    fun loadManual(on: Boolean) { IncognitoState.manual = on }

    fun setManual(on: Boolean) { IncognitoState.manual = on; apply() }

    /** 「锁定词序」：只关引擎的学习（同无痕的那个开关），不算无痕，剪贴板和联想照常。启动时同样等第一个输入框。 */
    fun loadLockWordOrder(on: Boolean) { lockWordOrder = on }

    fun setLockWordOrder(on: Boolean) { lockWordOrder = on; apply() }

    /** 「密码框临时关闭学习」（默认开）：进入密码框临时停用学习与记忆，离开后恢复。启动时等第一个输入框生效。 */
    fun loadPasswordDisableLearning(on: Boolean) { passwordDisableLearning = on }

    fun setPasswordDisableLearning(on: Boolean) {
        passwordDisableLearning = on
        syncSensitiveEditor()
        apply()
    }

    /** A new field: password fields and fields asking for no personalized learning stay private. */
    fun beginEditor(attribute: EditorInfo?) {
        currentNoPersonalized = IncognitoState.hasNoPersonalizedLearning(attribute)
        IncognitoState.passwordEditor = IncognitoState.isPasswordInput(attribute)
        IncognitoState.passwordField = IncognitoState.isPasswordField(attribute)
        syncSensitiveEditor()
        apply()
    }

    private fun syncSensitiveEditor() {
        IncognitoState.sensitiveEditor = currentNoPersonalized || (IncognitoState.passwordEditor && passwordDisableLearning)
    }

    /** The field closed: copies after this did not come from a password field, and password-editor isolation clears. Learning waits for the next field. */
    fun endEditor() {
        IncognitoState.passwordField = false
        IncognitoState.passwordEditor = false
    }

    /** Its own source: a field change while search is open must not overwrite it, nor be restored by it. */
    fun setClipboardSearch(open: Boolean) { IncognitoState.clipboardSearch = open; apply() }

    private fun apply() {
        val active = IncognitoState.active || lockWordOrder
        if (applied == active) return
        applied = active
        applyEngine(active)
    }
}
