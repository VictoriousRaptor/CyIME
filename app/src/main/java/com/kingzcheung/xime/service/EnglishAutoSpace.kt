package com.kingzcheung.xime.service

/**
 * 英文选词后自动加空格（默认关，别人请求，10-31）：在候选栏点一个英文补全词，词后自动补一个空格，
 * 接着打下一个词不用再按空格。补的这个空格只管紧接着的一个键：
 * - 空格：不再多打一个（已经有了）；
 * - 句末标点 , . ? ! : ;：标点放到空格前面（word, ）；右括号 ) ] }：去掉空格（word)）；
 * - 退格：照常删掉这个空格；其他键照常，空格保留。
 * Shift、切换数字/符号页这些不打字的键不算，选完词切到符号页打 ? ! 照样换位置。
 * 光标前已经不是刚上屏的“词+空格”（挪了光标、宿主改了文字）就当没补过，这个键照常处理。
 *
 * 英文标点后自动加空格（另一个开关，默认关，别人请求，10-31）：英文键盘在字母后面打 , . ? ! : ; 时上屏“标点+空格”，
 * 补的空格同样只管紧接着的一个键（[arm] 传标点本身），所以按空格不多打、连打 ... ?! 空格留在最后。
 */
internal class EnglishAutoSpace {
    /** 刚上屏的“词+空格”；null 表示没有待处理的自动空格。按键线程读、主线程写。 */
    @Volatile private var armed: String? = null

    val isArmed: Boolean get() = armed != null

    fun arm(word: String) { armed = "$word " }

    fun disarm() { armed = null }

    /** 只管紧接着的一个键：取出后即失效。 */
    fun take(): String? = armed.also { armed = null }

    companion object {
        /** 设置键，默认关。 */
        const val PREF_KEY = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.ENGLISH_AUTO_SPACE_KEY

        /** 只换键盘页、不打字的键（Shift、切到数字或符号页再切回来）：自动空格留到下一个真正打字的键。 */
        private val NEUTRAL_KEYS = setOf("shift", "mode_change", "number", "common_symbol", "abc")

        fun isNeutral(key: String): Boolean = key in NEUTRAL_KEYS

        private const val MOVE_BEFORE = ".,?!:;"
        private const val DROP_BEFORE = ")]}"

        /**
         * 紧跟自动空格的键要怎么改：返回替换光标前那个空格的文字（空串表示空格键本身，什么都不用再打）；
         * null 表示这个键照常处理、空格保留。
         */
        /** 英文标点后自动加空格的设置键，默认关。 */
        const val PUNCTUATION_PREF_KEY = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.ENGLISH_PUNCTUATION_SPACE_KEY

        /** 数字里也会用的 , . :（3.14、1,000、10:30）只跟在字母或右括号、引号后面才补空格。 */
        private const val SPACE_AFTER_WORD = ".,:"
        /** ? ! ; 不会出现在数字中间，跟在数字后面也补。 */
        private const val SPACE_AFTER_ANY = "?!;"
        private const val CLOSERS = ")]}\"'\u2019\u201d"

        /**
         * 英文标点后要不要自动补空格：[before] 是光标前一个字，[after] 是光标后一个字。
         * 光标前是空白、标点、汉字或输入框开头不补；光标后已经是空白（在句中补标点）不补。
         */
        fun spacesAfterPunctuation(key: String, before: CharSequence?, after: CharSequence?): Boolean {
            if (key.length != 1) return false
            val p = key[0]
            if (p !in SPACE_AFTER_WORD && p !in SPACE_AFTER_ANY) return false
            val prev = before?.lastOrNull() ?: return false
            val latinLetter = prev.isLetter() && Character.UnicodeScript.of(prev.code) == Character.UnicodeScript.LATIN
            val ok = latinLetter || prev in CLOSERS || (p in SPACE_AFTER_ANY && prev in '0'..'9')
            if (!ok) return false
            return after?.firstOrNull()?.isWhitespace() != true
        }

        fun replacementFor(key: String): String? = when {
            key == "space" || key == " " -> ""
            key.length == 1 && key[0] in MOVE_BEFORE -> "$key "
            key.length == 1 && key[0] in DROP_BEFORE -> key
            else -> null
        }
    }
}

/** 选中的联想/补全候选对应的词（英文有原词时第 0 个是原词）；越界返回 null。 */
internal fun associationWordAt(state: CandidateState, index: Int): String? {
    val pending = state.pendingEnglishText
    val hasOriginal = state.showsTypedEnglishWord
    val words = if (hasOriginal) listOf(pending) + state.associationCandidates else state.associationCandidates
    return words.getOrNull(index)
}
