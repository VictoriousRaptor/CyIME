package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Actual laid-out letter cells, including custom and split QWERTY rows. */
internal class ShiftSlideTargets(private val candidates: CandidateDropTargets = CandidateDropTargets.shared) {
    data class Target(val key: String, val bounds: Rect, val commit: () -> Unit)
    data class Drag(val origin: Offset, val pointer: Offset)
    private val targets = mutableMapOf<Any, List<Target>>()
    /** 只在按住符号键当 Ctrl 时才算目标的键（九键数字键）；按住 Shift 拖过去不算。 */
    private val ctrlOnly = mutableSetOf<Any>()
    var keyboardBounds: Rect = Rect.Zero
    var hovered by mutableStateOf<Target?>(null)
        private set
    var drag by mutableStateOf<Drag?>(null)
        private set
    /** 这次按住 Shift 已经拖出过 Shift 键：14 键据此把双字母键拆成单字母键，直到松开 Shift（用户 10-21）。 */
    var dragging by mutableStateOf(false)
        private set
    /** 按住符号键当 Ctrl：字母键改显示快捷键，拖到字母上松手发 Ctrl+字母，不选候选（用户 10-24）。 */
    var ctrl by mutableStateOf(false)
        private set
    /**
     * 字母键现在是否换成快捷键键面。按下符号键先不换：点一下就开符号时键盘不闪「复制」「粘贴」；
     * 按满符号键时限（同长按）或拖出符号键才换（用户 10-31）。拖动、另一根手指点字母照样立刻发 Ctrl+字母。
     */
    var ctrlFaces by mutableStateOf(false)
        private set
    private var ctrlShortcut: ((Char) -> Unit)? = null
    /** 这次按住符号键已经用另一根手指点过字母：松手不再当点按打开符号。 */
    var ctrlUsed = false
        private set
    fun beginCtrl(shortcut: (Char) -> Unit) {
        ctrl = true
        ctrlFaces = false
        ctrlShortcut = shortcut
        ctrlUsed = false
        SpaceCursorShift.ctrlHolder = this
    }
    /** 按满时限：字母键渐变成快捷键键面；不在按住符号键时什么都不做。 */
    fun showCtrlFaces() {
        if (ctrl) ctrlFaces = true
    }
    /** 按住符号键时另一根手指点字母：和按住 Shift 再点一样，发 Ctrl+这个字母（用户 10-24）。 */
    fun ctrlTap(letters: String): Boolean {
        val letter = letters.singleOrNull()?.takeIf { ctrl && it.lowercaseChar() in 'a'..'z' } ?: return false
        val send = ctrlShortcut ?: return false
        ctrlUsed = true
        send(letter)
        return true
    }

    /** 按住符号键时另一根手指点回车：Ctrl+回车，发送（用户 11-03）。不在按住符号键时返回 false，回车照常。 */
    fun ctrlEnter(): Boolean {
        if (!ctrl) return false
        val send = ctrlShortcut ?: return false
        ctrlUsed = true
        send(com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER)
        return true
    }

    /** 按住符号键时另一根手指点删除键：Ctrl+删除（用户 11-13）。不在按住符号键时返回 false，删除照常。 */
    fun ctrlBackspace(): Boolean {
        if (!ctrl) return false
        val send = ctrlShortcut ?: return false
        ctrlUsed = true
        send(com.kingzcheung.xime.keyboard.CtrlShortcuts.BACKSPACE)
        return true
    }

    /** 长按空格移光标时按住符号键：和按住 Shift 一样扩选；算用过 Ctrl，松手不开符号（用户 11-03）。 */
    fun ctrlSelect(): Boolean {
        if (!ctrl) return false
        ctrlUsed = true
        return true
    }

    /**
     * 回车键的位置：按住符号键拖到回车上松手发 Ctrl+回车（按键名走 Ctrl 那条路）；
     * 从 Shift 拖到回车上松手是 Shift+回车，换行（[shiftEnter]，用户 11-04）。
     */
    fun putEnter(id: Any, bounds: Rect, shiftEnter: () -> Unit = {}) {
        targets[id] = listOf(Target(ENTER_KEY, bounds, shiftEnter))
    }

    /**
     * 九键数字键当 Ctrl 目标（用户 11-03）：按住 !@# 拖到这个键上松手、或另一根手指点它，发 Ctrl+[letter]。
     * 键名记成这个字母，松手时和 26 键的字母走同一条路。
     */
    fun putCtrl(id: Any, letter: Char, bounds: Rect) {
        if (letter.lowercaseChar() !in 'a'..'z') {
            remove(id)
            return
        }
        targets[id] = listOf(Target(letter.uppercaseChar().toString(), bounds) {})
        ctrlOnly += id
    }

    /** 合并字母键按左右半区选单个字母，绝不调用它的长按或符号手势。 */
    fun put(id: Any, letters: String, bounds: Rect, commit: (String) -> Unit) {
        if (letters.length !in 1..2 || letters.any { it.lowercaseChar() !in 'a'..'z' }) {
            remove(id)
            return
        }
        ctrlOnly -= id
        targets[id] = letters.mapIndexed { index, char ->
            val letter = char.uppercaseChar().toString()
            val cell = Rect(bounds.left + bounds.width * index / letters.length, bounds.top,
                bounds.left + bounds.width * (index + 1) / letters.length, bounds.bottom)
            Target(letter, cell) { commit(letter) }
        }
    }
    fun remove(id: Any) {
        targets.remove(id)
        ctrlOnly -= id
    }

    /**
     * 滑到哪个键按哪个键（用户 11-14，像三星键盘）：滑行输入和「上下滑输入符号」都关着时，
     * 手指按下后在键盘上滑，滑到的键显示按下，松手按这个键。这里记所有能这样按的键的位置和点按。
     */
    private val slideTaps = mutableMapOf<Any, Target>()
    /** 手指现在滑到的那个键（[putSlideTap] 的 id）；没在滑时 null。键据此画按下的样子。 */
    var slideHovered by mutableStateOf<Any?>(null)
        private set
    /** 有手指正滑离按下的键：按下的那个键不再画按下的样子，除非手指又回到它上面。 */
    var sliding by mutableStateOf(false)
        private set
    fun putSlideTap(id: Any, bounds: Rect, tap: () -> Unit) {
        slideTaps[id] = Target(SLIDE_KEY, bounds, tap)
    }
    fun removeSlideTap(id: Any) {
        slideTaps.remove(id)
        if (slideHovered === id) slideHovered = null
    }
    /** 手指滑到 [point]（和 [keyboardBounds] 同一坐标）：记下手指下的键，滑出所有键时为 null。 */
    fun slideOver(point: Offset) {
        val id = slideTaps.entries.firstOrNull { it.value.bounds.contains(point) }?.key
        if (slideHovered !== id) slideHovered = id
        if (!sliding) sliding = true
    }
    /** 松手：按手指下的那个键；手指不在任何键上时什么都不按，返回 false。 */
    fun slideRelease(): Boolean {
        val target = slideHovered?.let(slideTaps::get)
        slideHovered = null
        sliding = false
        target?.commit?.invoke() ?: return false
        return true
    }
    fun slideCancel() {
        slideHovered = null
        sliding = false
    }
    fun at(point: Offset): Target? = if (!keyboardBounds.contains(point)) candidateAt(point)?.takeUnless { ctrl }
        else targets.entries.asSequence().filter { (id, _) -> ctrl || id !in ctrlOnly }.flatMap { (_, cells) -> cells.asSequence() }
            .firstOrNull { it.bounds.contains(point) }

    /** 拖出键盘、落在候选上：松手等于按住 Shift 点这个候选（释义模式上屏英文）。 */
    private fun candidateAt(point: Offset): Target? =
        candidates.at(point)?.let { (bounds, select) -> Target(CANDIDATE_KEY, bounds, select) }

    companion object {
        /** 候选目标的 key；不是字母，键帽高亮不会匹配到它。 */
        const val CANDIDATE_KEY = "candidate"
        /** 滑到哪个键按哪个键的目标名；不是字母，Shift 拖动的高亮不会匹配到它。 */
        const val SLIDE_KEY = "slide"
        /** 回车目标的 key：一个字符，符号键松手时按 Ctrl+这个字符发出（[com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER]）。 */
        val ENTER_KEY = com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER.toString()
    }

    fun move(shiftBounds: Rect, pointer: Offset): Target? {
        if (shiftBounds.contains(pointer)) {
            clearDrag()
            return null
        }
        drag = Drag(shiftBounds.center, pointer)
        dragging = true
        val target = at(pointer)
        // Pointer motion redraws the line; keys recompose only when the target changes.
        if (hovered?.key != target?.key || hovered?.bounds != target?.bounds) hovered = target
        if (ctrl) ctrlFaces = true
        else {
            candidates.dragLine = drag?.let { it.origin to it.pointer }
            candidates.hoveredBounds = target?.takeIf { it.key == CANDIDATE_KEY }?.bounds
        }
        return target
    }
    /** 松开 Shift：清掉拖线，键盘回到原来的布局。 */
    fun finish() {
        clearDrag()
        dragging = false
        ctrl = false
        ctrlFaces = false
        ctrlShortcut = null
        if (SpaceCursorShift.ctrlHolder === this) SpaceCursorShift.ctrlHolder = null
    }
    fun clearDrag() {
        drag = null
        hovered = null
        candidates.dragLine = null
        candidates.hoveredBounds = null
    }
}
internal val LocalShiftSlideTargets = staticCompositionLocalOf<ShiftSlideTargets?> { null }
