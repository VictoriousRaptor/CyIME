package com.kingzcheung.xime.service

/**
 * 注音（大千）键：键帽是注音符号，cyime_zhuyin 方案的拼写是大千式键盘的 ASCII 键码（ㄅ=1 ㄆ=q ㄇ=a …）。
 * 声调 ˊˇˋ˙ 是 6 3 4 7，第一声是空格。
 */
internal object ZhuyinKeys {
    private const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙"
    private const val CODES = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347"
    private const val TONES = " 6347"

    /** 注音符号的大千键码；不是注音符号返回 null。 */
    fun code(key: String): Char? = key.singleOrNull()?.let { SYMBOLS.indexOf(it) }?.takeIf { it >= 0 }?.let(CODES::get)

    fun isTone(code: Char): Boolean = code in TONES

    /**
     * 实体键盘上不是字母的大千键：数字行和 , . / ; -（ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ、ㄝㄡㄥㄤㄦ）。字母键本来就交给 Rime。
     * 实体键盘照大千键位打（jp4wu = ㄨㄣˋㄊㄧ 問題），这些键不能当选候选、翻页或标点。
     */
    fun isHardwareSymbolCode(key: String): Boolean = key.length == 1 && key[0] in "1234567890,./;-"

    /** 声调只跟在音节后面：没打字、刚打过声调或分隔符时不收。 */
    fun takesTone(input: String): Boolean = input.isNotEmpty() && input.last() !in TONES && input.last() != '\''
}
