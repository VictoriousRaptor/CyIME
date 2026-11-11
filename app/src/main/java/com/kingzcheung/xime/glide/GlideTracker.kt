package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import kotlin.math.abs

/**
 * 判断一次触摸是不是滑行。只有确认是滑行后才接管手势，静止时仍可点按、长按。
 *
 * - 必须从字母键起手；按住超过 [HOLD_MS] 才移动的留给长按，不再接管。
 * - 距离按实际键宽高计算，单手/悬浮缩小时也不必横跨整个键。
 * - 跨入另一字母并达到解码最短距离就接管，各方向相同。
 */
class GlideTracker(
    private val keys: Map<Char, LetterKeyRect>,
    private val touchSlop: Float,
    x: Float, y: Float, private val downTime: Long,
    /** 按住这么久才移动的留给长按；「长按输入」角标设得更短时跟着缩短，见 KeyboardInputPreferences.glideHoldMs。 */
    private val holdMs: Long = HOLD_MS,
) {
    private val keyWidth = keys.values.map { it.width }.sorted().let { it.getOrNull(it.size / 2) } ?: 1f
    private val keyHeight = keys.values.map { it.height }.sorted().let { it.getOrNull(it.size / 2) } ?: 1f
    private val startX = x
    private val startY = y
    private var minX = x; private var maxX = x; private var minY = y; private var maxY = y
    private var moved = false
    private val visited = LinkedHashSet<Char>()
    private val pointsX = ArrayList<Float>()
    private val pointsY = ArrayList<Float>()
    private val pointsT = ArrayList<Long>()

    val startsOnLetter: Boolean = keyAt(x, y) != null
    var claimed = false
        private set
    /** 已判定不是滑行（按住后才移动、离开键盘区等），本次触摸不再接管。 */
    var abandoned = !startsOnLetter
        private set

    init { add(x, y, downTime) }

    fun xs(): FloatArray = pointsX.toFloatArray()
    fun ys(): FloatArray = pointsY.toFloatArray()
    /** 每个轨迹点的时刻（「贡献滑行轨迹」用，识别不看时间）。 */
    fun ts(): LongArray = pointsT.toLongArray()

    /** 加入一个轨迹点；返回本次是否刚刚确认为滑行。 */
    fun move(x: Float, y: Float, time: Long, eventTime: Long = time): Boolean {
        if (abandoned) return false
        if (!moved && (abs(x - startX) > touchSlop || abs(y - startY) > touchSlop)) {
            moved = true
            // 批量历史采样到达时，长按可能已经触发；不能用较早的采样时间反过来抢走它。
            if (eventTime - downTime > holdMs && !claimed) { abandoned = true; return false }
        }
        add(x, y, time)
        if (claimed) return false
        val spanX = (maxX - minX) / keyWidth
        val spanY = (maxY - minY) / keyHeight
        claimed = moved && visited.size >= 2 && kotlin.math.hypot(spanX, spanY) >= MIN_SPAN_KEYS
        return claimed
    }

    fun abandon() { if (!claimed) abandoned = true }

    private fun add(x: Float, y: Float, time: Long) {
        pointsX += x; pointsY += y; pointsT += time
        minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        keyAt(x, y)?.let { visited += it }
    }

    private fun keyAt(x: Float, y: Float): Char? = keys.entries.firstOrNull { (_, rect) ->
        x >= rect.x && x < rect.x + rect.width && y >= rect.y && y < rect.y + rect.height
    }?.key

    companion object {
        const val HOLD_MS = 300L
        // 不低于解码器的最短轨迹，否则接管后会吞掉没有候选的短划。
        const val MIN_SPAN_KEYS = GlideDecoder.MIN_PATH_KEYS
    }
}

/**
 * 滑行整词上屏的文字：前面紧挨字母、数字或句末标点时补一个空格，
 * 开头、空白后、左括号或左引号后不补。
 */
fun glideCommitText(word: String, before: Char?): String =
    if (before == null || before.isWhitespace() || before in "([{<\"'“‘（【《「『") word else " $word"

/** 按 Shift 状态决定大小写：单次大写只改首字母，锁定时全部大写。 */
fun glideCased(word: String, shifted: Boolean, capsLocked: Boolean): String = when {
    capsLocked -> word.uppercase()
    shifted -> word.replaceFirstChar { it.uppercaseChar() }
    else -> word
}

/**
 * 中文滑行适用的键盘：全拼、双拼的字母键盘（26 键、分体、自定义字母排列）。
 * 14 键、九键、注音、日文、五笔不滑行；双拼只认得出键位表的方案。
 */
fun chineseGlideSupported(profile: com.kingzcheung.xime.settings.InputProfile, schemaId: String): Boolean =
    profile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
        profile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC &&
        (profile.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN ||
            profile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN &&
            com.kingzcheung.xime.settings.DoublePinyinSchemes.find(schemaId) != null)
