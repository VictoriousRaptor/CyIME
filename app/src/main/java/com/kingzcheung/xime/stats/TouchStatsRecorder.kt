package com.kingzcheung.xime.stats

import com.kingzcheung.xime.rime.CorrectionMark
import com.kingzcheung.xime.rime.CorrectionMarks
import com.kingzcheung.xime.stats.TouchStatsData.Count
import com.kingzcheung.xime.stats.TouchStatsData.Fate
import com.kingzcheung.xime.stats.TouchStatsData.Kind
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import com.kingzcheung.xime.stats.TouchStatsData.UpSide

/**
 * 把按键事件记成 [TouchStatsData] 里的计数。纯 Kotlin，电脑上能直接测；开关、加锁在 [TouchStats]，读写文件在 [TouchStatsStore]。
 *
 * 事件的来源：
 * 1. 字母这类键的手势（detectExclusiveKeyGestures）：按下时 [landing] → 键的按下回调里 [keyDown]（给出键名）→ [landed] 拿到这一下的号；
 *    提前出字时 [early]；手势结束时 [finish]（结果、手指挪了多远）。
 * 2. 自己管手势的键（空格、删除、回车、数字页的键）：按下只有 [keyDown]（空格、删除这类先给一个不跟手势的 [landing]，
 *    只为记落点），出字时 [keyEmit]。
 * 3. 排了按下先后的键（[com.kingzcheung.xime.ui.keyboard.KeyPressOrder]）每次按下 [pressBegan]。
 * 4. 选候选 [picked]、上屏 [committed]、输入法没接的输入 [dropped]。
 *
 * 为了把「删掉重打」「选了纠错候选」对回到是哪一下按键，内存里留着正在打的这一串字母各自的落点格（不存盘，
 * 选了候选、打了别的键就清掉）；存下来的只有「按成的键>想按的键」各落在哪一格的次数。
 */
class TouchStatsRecorder(var data: TouchStatsData = TouchStatsData()) {
    private class Press(val name: String, val kind: Int, val seq: Long, val downAt: Long, val gapBin: Int) {
        var cell = -1
        var widthDp = 0f
        var heightDp = 0f
        /** 落点在键里的位置（dp）。 */
        var xDp = 0f
        var yDp = 0f
        var emitted = false
        /** 这一下按着的时候，别的键也按着。 */
        var overlapped = false
        /** 这一下点按出的字母（出了字才有）。 */
        var tap: Typed? = null
        /** 删除键按下时，上一个键是不是刚点按出的字母。 */
        var regret: Typed? = null
        /** 删除键按下时，上一个键是不是刚出字的空格。 */
        var regretSpace: Press? = null
        /** 空格按下时，上一个键是不是删除键、删的是刚点按出的那个字母。 */
        var afterDelete: Typed? = null
    }

    /** 正在打的一串字母里的一下。 */
    private class Typed(val letter: Char, val key: String, val cell: Int, val at: Long, val gapBin: Int, val overlapped: Boolean) {
        /** 这一下出字时，比它后按下的键已经先出了字。 */
        var late = false
        /** 抬手时手指在键的哪一边（[UpSide]）。 */
        var up = UpSide.UNKNOWN
        /** 紧接着被删除键删掉了。 */
        var regretted = false
    }

    /** 有落点的按下，等手势结束。 */
    private val open = ArrayList<Press>(4)
    /** 没有落点的按下（空格、删除、回车等），等它出字。 */
    private val plain = ArrayList<Press>(4)
    private val typed = ArrayList<Typed>(32)
    /** 被删除键删掉的那些，最后删的在最后；重打时从最后一个对起。 */
    private val deleted = ArrayList<Typed>(8)
    private var seq = 0L
    private var lastDownAt = 0L
    private var lastEmitSeq = -1L
    private var lastEmitKind = Kind.OTHER
    /** 上一个键是不是刚点按出的字母（紧接着的删除算「后悔」）。 */
    private var lastTap: Typed? = null
    /** 上一个键是不是刚出字的空格。 */
    private var lastSpace: Press? = null
    /** 上一个键是不是删除键、删的是刚点按出的字母（紧接着的空格算「删掉以后按了空格」）。 */
    private var lastRegret: Typed? = null
    /** 按过空格、回车这类键：它可能把正在打的这串选上屏（[picked] 还要用这串），下一个字母或删除来之前再清。 */
    private var clearOnNext = false

    // 同一个键上一下抬手的时刻和位置。
    private var lastUpName = ""
    private var lastUpAt = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f

    // 手势交过来、等按下回调取走的落点。
    private var hasLanding = false
    private var landTracked = true
    private var landX = 0f; private var landY = 0f; private var landW = 0f; private var landH = 0f
    private var landLag = 0L; private var landAt = 0L
    private var landedSeq = 0L

    private val board: TouchStatsData.Board get() = data.board(data.lastBoard)

    /**
     * 手指按在一个键上：[x]、[y] 是键里的位置，[width]、[height] 是键的大小（含键缝），都是 dp；[eventAt] 是按下的时刻，[now] 是现在。
     * [tracked] 是字母这类键的手势（之后有 [landed] 的号和 [finish]）；空格、删除这类自己管手势的键给 false，只记落点。
     */
    fun landing(x: Float, y: Float, width: Float, height: Float, eventAt: Long, now: Long, tracked: Boolean = true) {
        hasLanding = true
        landTracked = tracked
        landedSeq = 0L
        landX = x; landY = y; landW = width; landH = height
        landAt = eventAt; landLag = now - eventAt
    }

    /** [landing] 之后、键的按下回调跑完了：这一下的号；按下回调没有报键名（没记上）、或者是不跟手势的落点时是 0。 */
    fun landed(): Long {
        hasLanding = false
        return landedSeq.also { landedSeq = 0L }
    }

    /** 一个键按下（所有键）。[now] 只在没有落点（自己管手势的键）时当按下的时刻。 */
    fun keyDown(name: String, now: Long) {
        val b = board
        val landed = hasLanding
        hasLanding = false
        val at = if (landed) landAt else now
        val key = keyName(name)
        b.counts[Count.KEY_DOWNS]++
        // 离上一次按下隔多久；键盘刚出来的第一下算「更久」。
        var gapBin = TouchStatsData.GAP_BINS - 1
        if (lastDownAt > 0 && at >= lastDownAt) {
            gapBin = TouchStatsData.bin(at - lastDownAt, TouchStatsData.GAP_STEP_MS, TouchStatsData.GAP_BINS)
            b.gap[gapBin]++
        }
        lastDownAt = at
        // 上一下删除键没有照常删一个字（拖动删除、划掉整段，或者还按着、连删起来了）：正在打的这串对不上了。
        var spaceBefore = lastSpace
        lastSpace = null
        val regretBefore = lastRegret
        lastRegret = null
        plain.lastOrNull()?.let {
            if (it.kind == Kind.DELETE && !it.emitted) { plain.remove(it); spaceBefore = spaceBefore ?: it.regretSpace; unsync(b) }
        }
        if (landed && landTracked) {
            val press = Press(key, Kind.CHAR, ++seq, at, gapBin)
            b.counts[Count.CHAR_PRESSES]++
            b.lagDown[TouchStatsData.bin(landLag, TouchStatsData.LAG_STEP_MS, TouchStatsData.LAG_BINS)]++
            press.widthDp = landW; press.heightDp = landH; press.xDp = landX; press.yDp = landY
            if (landW > 0f && landH > 0f) {
                press.cell = TouchStatsData.cell(landX / landW, landY / landH)
                b.key(key).size(landW, landH)
            }
            if (key == lastUpName && at >= lastUpAt) {
                val bin = TouchStatsData.bin(at - lastUpAt, TouchStatsData.SAME_STEP_MS, TouchStatsData.SAME_BINS)
                b.sameGap[bin]++
                if (kotlin.math.hypot(landX - lastUpX, landY - lastUpY) < TouchStatsData.SAME_NEAR_DP) b.sameGapNear[bin]++
            }
            if (open.size >= MAX_OPEN) open.removeAt(0)
            open.add(press)
            landedSeq = press.seq
            lastTap = null
            return
        }
        val kind = when (key) { "space" -> Kind.SPACE; "delete" -> Kind.DELETE; else -> Kind.OTHER }
        val press = Press(key, kind, ++seq, at, gapBin)
        if (landed && landW > 0f && landH > 0f) {
            press.cell = TouchStatsData.cell(landX / landW, landY / landH)
            b.key(key).size(landW, landH)
        }
        // 同一个键又按下，上一下就算过去了；太久没出字的也不等了。
        plain.removeAll { it.name == key || at - it.downAt > EMIT_WAIT_MS }
        if (plain.size >= MAX_OPEN) plain.removeAt(0)
        plain.add(press)
        if (kind == Kind.DELETE) {
            b.counts[Count.DELETES]++
            press.regret = lastTap
            press.regretSpace = spaceBefore
        } else {
            if (kind == Kind.SPACE) press.afterDelete = regretBefore
            clearOnNext = true
            deleted.clear()
        }
        lastTap = null
    }

    /** 自己管手势的键（空格、删除、回车等）出字了。字母这类键的出字看 [early]、[finish]。 */
    fun keyEmit(name: String, now: Long) {
        val key = keyName(name)
        val press = plain.lastOrNull { it.name == key && !it.emitted && now - it.downAt <= EMIT_WAIT_MS }
        if (press == null) {
            // 删除键连发的第二下起，或者别处来的删除：删了几个字不知道了。
            if (key == "delete") unsync(board)
            return
        }
        emitted(press)
        val b = board
        if (press.cell >= 0) b.key(key).land.add(press.cell)
        if (press.kind == Kind.DELETE) {
            if (clearOnNext) { typed.clear(); deleted.clear(); clearOnNext = false }
            val regretted = press.regret
            if (regretted != null && typed.lastOrNull() === regretted) {
                b.counts[Count.REGRETS]++
                regretted.regretted = true
                lastRegret = regretted
                if (regretted.cell >= 0) b.key(regretted.key).regret.add(regretted.cell)
                b.fate.add(TouchStatsData.fateIndex(Fate.REGRET, regretted.overlapped, regretted.gapBin))
            }
            press.regretSpace?.let { space ->
                b.counts[Count.SPACE_REGRETS]++
                if (space.cell >= 0) b.key(space.name).regret.add(space.cell)
            }
            // 删掉的是正在打的这串的最后一下；留着，看接下来重打成什么。
            typed.removeLastOrNull()?.let { if (deleted.size >= MAX_DELETED) deleted.removeAt(0); deleted.add(it) }
        } else if (press.kind == Kind.SPACE) {
            lastSpace = press
            // 点按出一个字母、紧接着删掉、再按空格：那一下多半是想按空格（落在第三排字母的下沿），或者多打了一个字母。
            press.afterDelete?.let { before ->
                if (now - before.at <= RETYPE_MS) { b.counts[Count.RETYPED]++; changed(b, b.retyped, before, "space") }
            }
        }
    }

    /** [tap] 这一下事后改成了 [intended]（删掉重打成别的键、删掉后按了空格，或者选了纠错候选；多打的是空串）。 */
    private fun changed(b: TouchStatsData.Board, pairs: LinkedHashMap<String, TouchStatsData.Sparse>, tap: Typed, intended: String) {
        if (tap.cell >= 0) {
            b.pair(pairs, "${tap.letter}>$intended")?.add(TouchStatsData.pairIndex(tap.cell, tap.up))
            // 落点图上「后来改掉的」：紧接着删的已经记在 regret 里，选纠错候选改的看 corrected，这里只补不是紧接着删的。
            if (pairs === b.retyped && !tap.regretted) b.key(tap.key).changed.add(tap.cell)
        }
        b.fate.add(TouchStatsData.fateIndex(Fate.CHANGED, tap.overlapped, tap.gapBin))
    }

    private fun unsync(b: TouchStatsData.Board) {
        if (typed.isNotEmpty() || deleted.isNotEmpty()) b.counts[Count.DELETE_OTHER]++
        typed.clear(); deleted.clear(); lastTap = null
    }

    /** 这一下出字了：比它后按下的键已经先出过字，就是顺序反了。 */
    private fun emitted(press: Press): Boolean {
        press.emitted = true
        val late = lastEmitSeq > press.seq
        if (late) board.inversions[press.kind * Kind.SIZE + lastEmitKind]++
        else { lastEmitSeq = press.seq; lastEmitKind = press.kind }
        return late
    }

    /** 排了按下先后的键（字母、空格、删除等）有一下开始了；[others] 是这时别的还没抬起的有几个。 */
    fun pressBegan(others: Int) {
        val b = board
        b.overlap[others.coerceIn(0, b.overlap.size - 1)]++
        // 这时还没抬起的字母键（刚按下的这一下也在里面）都算「交叠着按的」。
        if (others > 0) open.forEach { it.overlapped = true }
    }

    /** 记一次 [Count] 里的事（空格、删除键提前出字）。 */
    fun count(index: Int) {
        if (index in 0 until Count.SIZE) board.counts[index]++
    }

    /** 输入法没接的输入；[reason] 是代码里写死的原因。 */
    fun dropped(reason: String) = board.drop(reason)

    /** [token] 这一下还按着，后按的键先抬手，所以它现在按点按提前出字。 */
    fun early(token: Long, now: Long) {
        val press = open.firstOrNull { it.seq == token } ?: return
        if (press.emitted) return
        tapped(press, emitted(press), now)
    }

    /**
     * [token] 这一下的手势结束：[code] 是 [Outcome]，[dx]、[dy] 是最后的位置相对按下点（dp），
     * [eventAt] 是最后一个触摸事件的时刻，[now] 是现在。
     */
    fun finish(token: Long, code: Int, dx: Float, dy: Float, eventAt: Long, now: Long) {
        val index = open.indexOfFirst { it.seq == token }
        if (index < 0) return
        val press = open.removeAt(index)
        if (code !in 0 until Outcome.SIZE) return
        val b = board
        b.outcomes[code]++
        b.lagUp[TouchStatsData.bin(now - eventAt, TouchStatsData.LAG_STEP_MS, TouchStatsData.LAG_BINS)]++
        val travel = kotlin.math.hypot(dx, dy)
        val held = (eventAt - press.downAt).coerceAtLeast(0)
        b.travelTime[code].add(TouchStatsData.bin(travel, TouchStatsData.TRAVEL_STEP_DP, TouchStatsData.TRAVEL_BINS) * TouchStatsData.TIME_BINS +
            TouchStatsData.bin(held, TouchStatsData.TIME_STEP_MS, TouchStatsData.TIME_BINS))
        lastUpName = press.name; lastUpAt = eventAt; lastUpX = press.xDp + dx; lastUpY = press.yDp + dy
        when {
            Outcome.isTap(code) -> {
                b.driftTap.add(TouchStatsData.driftCell(dx, dy))
                var up = UpSide.UNKNOWN
                if (press.cell >= 0) {
                    b.key(press.name).land.add(press.cell)
                    up = upSide(press.xDp + dx, press.yDp + dy, press.widthDp, press.heightDp)
                    b.upSide[up]++
                }
                // 提前出字的那一下在 [early] 里已经记过。
                if (!press.emitted) tapped(press, emitted(press), eventAt)
                press.tap?.up = up
            }
            Outcome.isNone(code) -> {
                b.driftNone.add(TouchStatsData.driftCell(dx, dy))
                if (press.cell >= 0) b.key(press.name).lost.add(press.cell)
                if (held <= TouchStatsData.LOST_TAP_MS && travel <= TouchStatsData.LOST_TAP_DP) b.counts[Count.LOST_TAPS]++
            }
            // 长按、滑动出的不是这个键的字母：正在打的这一串对不上了。
            else -> {
                if (!press.emitted) emitted(press)
                typed.clear(); deleted.clear(); lastTap = null; clearOnNext = false
            }
        }
    }

    private fun upSide(x: Float, y: Float, width: Float, height: Float): Int {
        val left = -x; val right = x - width; val above = -y; val below = y - height
        val out = maxOf(left, right, above, below)
        return when {
            out <= 0f -> UpSide.INSIDE
            out == left -> UpSide.LEFT
            out == right -> UpSide.RIGHT
            out == above -> UpSide.ABOVE
            else -> UpSide.BELOW
        }
    }

    private fun tapped(press: Press, late: Boolean, at: Long) {
        val letter = press.name.singleOrNull()?.takeIf { it in 'a'..'z' }
        if (letter == null || clearOnNext) { typed.clear(); deleted.clear(); clearOnNext = false }
        if (letter == null) { lastTap = null; return }
        val b = board
        val tap = Typed(letter, press.name, press.cell, at, press.gapBin, press.overlapped)
        tap.late = late
        press.tap = tap
        b.fate.add(TouchStatsData.fateIndex(Fate.TAP, tap.overlapped, tap.gapBin))
        // 删掉重打：和最后删掉的那一下比。
        val before = deleted.removeLastOrNull()
        if (before != null) {
            if (at - before.at > RETYPE_MS) deleted.clear()
            else if (before.letter == letter) b.counts[Count.RETYPED_SAME]++
            else { b.counts[Count.RETYPED]++; changed(b, b.retyped, before, letter.toString()) }
        }
        if (typed.size >= MAX_TYPED) typed.removeAt(0)
        typed.add(tap)
        lastTap = tap
    }

    /** 选了一个候选；[correction] 是它的纠错标记（没纠错为空）。 */
    fun picked(correction: String) {
        lastTap = null
        deleted.clear()
        clearOnNext = false
        val marks = if (correction.isEmpty()) emptyList() else runCatching { CorrectionMarks.parse(correction) }.getOrDefault(emptyList())
        if (marks.none { it !is CorrectionMark.Keep && it !is CorrectionMark.Gap }) {
            // 没纠错的候选不知道用掉了几个字母：这一串不再对。
            typed.clear()
            return
        }
        val b = board
        // 标记里「按成的」那一串字母，要和正在打的这串的开头一样才对得上。只有全拼的标记两边都是键上的字母
        // （双拼的「想按的」那一边是整个拼音）。
        val letters = StringBuilder()
        for (mark in marks) when (mark) {
            is CorrectionMark.Keep -> letters.append(mark.letter)
            is CorrectionMark.Replace -> letters.append(mark.typed)
            is CorrectionMark.Extra -> letters.append(mark.letter)
            is CorrectionMark.Swap -> letters.append(mark.correct.reversed())
            else -> Unit
        }
        val matches = fullPinyin(b.id) && letters.isNotEmpty() && letters.length <= typed.size &&
            letters.indices.all { typed[it].letter == letters[it].lowercaseChar() }
        if (!matches) {
            b.counts[Count.CORRECTED_UNMATCHED]++
            typed.clear()
            return
        }
        b.counts[Count.CORRECTED_PICKS]++
        var at = 0
        for (mark in marks) when (mark) {
            is CorrectionMark.Keep -> at++
            is CorrectionMark.Replace -> changed(b, b.corrected, typed[at++], mark.correct.lowercaseChar().toString())
            is CorrectionMark.Extra -> changed(b, b.corrected, typed[at++], "")
            is CorrectionMark.Swap -> {
                b.counts[Count.SWAPS]++
                if (typed[at].late || typed[at + 1].late) b.counts[Count.SWAPS_FROM_ORDER]++
                at += 2
            }
            else -> Unit
        }
        repeat(letters.length) { typed.removeAt(0) }
    }

    /** 文字从屏幕键盘上屏了：之后的按键记在这种输入方式上（[TypingStatsCombo.id]，只认屏幕键盘的）。 */
    fun committed(boardId: String?) {
        if (boardId != null && boardId.startsWith("kb/") && boardId != data.lastBoard) data.lastBoard = boardId
    }

    /** 键盘收起：没结束的都不要了。 */
    fun reset() {
        open.clear(); plain.clear(); typed.clear(); deleted.clear()
        lastTap = null; lastSpace = null; lastRegret = null; lastDownAt = 0L; lastUpName = ""; clearOnNext = false
        hasLanding = false; landedSeq = 0L
    }

    companion object {
        const val MAX_OPEN = 8
        const val MAX_TYPED = 64
        const val MAX_DELETED = 16
        /** 没有落点的键按下后，等它出字最多等这么久。 */
        const val EMIT_WAIT_MS = 3_000L
        /** 从按下被删掉的那一下算起，隔这么久才打的不算「重打」。 */
        const val RETYPE_MS = 10_000L

        /** 键名：短的原样（小写），长的（整句动作名等）合成 other。 */
        fun keyName(name: String): String = when {
            name == "space" || name == "delete" || name == "enter" -> name
            name.isEmpty() || name.length > 3 -> TouchStatsData.OTHER_KEY
            else -> name.lowercase()
        }

        /** 这种输入方式是不是全拼（`kb/<语言>/pinyin/<字母布局>`）。 */
        fun fullPinyin(boardId: String): Boolean = boardId.startsWith("kb/") && boardId.split('/').getOrNull(2) == "pinyin"
    }
}
