package com.kingzcheung.xime.service

/**
 * 长按字母气泡里点的小写字母：这一下不按「首字母大写」改成大写（#465：句首长按选小写还是出大写）。
 * 键盘点选时登记，按键线程处理这个字母时取走；只认同一个字母，过 1 秒作废，不会带到后面的点按上。
 */
internal object ExplicitLowercase {
    private const val TTL_MS = 1000L
    @Volatile private var pending: Pair<String, Long>? = null

    fun mark(letter: String, now: Long = android.os.SystemClock.uptimeMillis()) {
        pending = if (letter.length == 1 && letter[0].isLowerCase()) letter to now else null
    }

    fun consume(key: String, now: Long = android.os.SystemClock.uptimeMillis()): Boolean {
        val (letter, at) = pending ?: return false
        pending = null
        return letter == key && now - at in 0..TTL_MS
    }
}
