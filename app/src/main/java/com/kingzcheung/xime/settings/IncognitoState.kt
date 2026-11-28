package com.kingzcheung.xime.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 当前隐私与无痕状态：
 * - [active]：是否停用词库学习、调频、记忆与剪贴板历史（手动无痕、剪贴板搜索，或受「密码框临时关闭学习」控制的密码框/NO_PERSONALIZED_LEARNING）。
 * - [securitySensitive] / [editorSecuritySensitive]：插件、剪贴板同步、语音悬浮条续听、打字统计等安全边界；
 *   即使关闭「密码框临时关闭学习」，密码输入框仍保持隔离（[passwordEditor]）。
 * - [passwordField]：仅指藏字密码框，专用于剪贴板历史掩码。
 * 主线程写、引擎线程与剪贴板监听读，volatile 保证可见性。
 */
object IncognitoState {
    @Volatile var manual: Boolean = false
    @Volatile var sensitiveEditor: Boolean = false
    /** 当前开着的输入框是否为密码框（含可见密码，离开输入框清掉；与「密码框临时关闭学习」开关无关，用于保持安全边界）。 */
    @Volatile var passwordEditor: Boolean = false
    /**
     * 剪贴板只看这个：当前开着的是藏字的密码框，离开输入框就清掉。以前剪贴板看 [sensitiveEditor]，
     * 浏览器无痕页（NO_PERSONALIZED_LEARNING）、借「可见密码」关联想的地址栏和搜索框、离开密码框后
     * 没清的旧状态，都把网页上复制的普通文字当成密码藏起来（用户 11-09）。
     */
    @Volatile var passwordField: Boolean = false
    /**
     * Clipboard search is open: what is typed into it is never learned or sent to plugins. Kept
     * apart from [sensitiveEditor], which every new input field rewrites.
     */
    @Volatile var clipboardSearch: Boolean = false
    val active: Boolean get() = manual || sensitiveEditor || clipboardSearch
    val editorSecuritySensitive: Boolean get() = sensitiveEditor || passwordEditor
    val securitySensitive: Boolean get() = manual || editorSecuritySensitive || clipboardSearch

    fun isPasswordInput(attribute: EditorInfo?): Boolean {
        if (isPasswordField(attribute)) return true
        val type = attribute?.inputType ?: return false
        return (type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    fun hasNoPersonalizedLearning(attribute: EditorInfo?): Boolean =
        attribute != null && (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)

    fun isSensitiveEditor(attribute: EditorInfo?): Boolean =
        isPasswordInput(attribute) || hasNoPersonalizedLearning(attribute)

    /** 藏字的密码框：文字密码、网页密码、数字密码。「可见密码」常被用来关联想，不算。 */
    fun isPasswordField(attribute: EditorInfo?): Boolean {
        if (attribute == null) return false
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        return when (attribute.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
