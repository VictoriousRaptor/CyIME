package com.kingzcheung.xime.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 当前是否无痕：用户手动开启，或当前输入框为密码 / 宿主声明 IME_FLAG_NO_PERSONALIZED_LEARNING。
 * 无痕时不学新词、不调词频、不记联想与剪贴板历史、不派发打字统计；已有数据照常使用。
 * 主线程写、引擎线程与剪贴板监听读，volatile 保证可见性。
 */
object IncognitoState {
    @Volatile var manual: Boolean = false
    @Volatile var sensitiveEditor: Boolean = false
    /** 当前输入框是否为密码框（与「密码框临时关闭学习」开关无关，用于保持插件与剪贴板同步安全边界）。 */
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
    val securitySensitive: Boolean get() = active || passwordEditor

    fun isPasswordInput(attribute: EditorInfo?): Boolean {
        if (attribute == null) return false
        val cls = attribute.inputType and InputType.TYPE_MASK_CLASS
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation in intArrayOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
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
