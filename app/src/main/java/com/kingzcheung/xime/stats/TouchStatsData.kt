package com.kingzcheung.xime.stats

/**
 * 按键落点统计的数据：只有计数，没有打过的任何文字，也没有按键的先后记录。纯 Kotlin，不碰 Android，电脑上能直接测。
 *
 * 为了回答「手指按下去，出来的是不是想按的那个键」：每个键上手指落在键里的哪一格、
 * 哪些按下没有出字和原因、几根手指交叠时出字顺序和按下顺序反了几次、删掉重打成了哪个键、
 * 选了纠错候选时按错的那一下落在哪。只在「装到我的设备」的包里记，跟着「打字统计」的开关，无痕时不记
 * （见 [TouchStats]、[TouchStatsStore]）。
 */
class TouchStatsData {
    /** 一次按下的结果。顺序就是存储顺序，只能往后加。 */
    object Outcome {
        /** 没有结果（不用）。 */
        const val UNKNOWN = 0
        /** 点按出字。 */
        const val TAP = 1
        /** 同一个键上又按下一根手指，前一下照点按出字（小鹤 uu）。 */
        const val TAP_HANDOVER = 2
        /** 后按的键先抬手时，这一下提前按点按出了字（按按下的先后；没有这条规则时出字顺序会反）。 */
        const val TAP_EARLY = 3
        /** 长按（长按菜单松手，或者长按动作到点执行了）。 */
        const val LONG = 4
        /** 上下左右滑出了动作。 */
        const val SWIPE = 5
        /** 滑到别的键上松手（滑到哪个键按哪个键）。 */
        const val SLIDE = 6
        /** 没出字：这根手指被上层拿走了（滑行、键盘上滑动移光标等）。 */
        const val NONE_TAKEN = 7
        /** 没出字：横着拖出了取消的距离。 */
        const val NONE_FAR = 8
        /** 没出字：滑过了阈值但没有动作（那个方向没有动作，或者滑出去又收回来）。 */
        const val NONE_BLOCKED = 9
        /** 没出字：留给滑行的键上拖远了，又没成滑行。 */
        const val NONE_DRIFT = 10
        /** 没出字：手指不见了，或者手势被取消（键盘换页、收起）。 */
        const val NONE_LOST = 11
        const val SIZE = 12

        fun isTap(outcome: Int) = outcome == TAP || outcome == TAP_HANDOVER || outcome == TAP_EARLY
        fun isNone(outcome: Int) = outcome in NONE_TAKEN..NONE_LOST
    }

    /** 键的种类，交叠统计用。 */
    object Kind {
        /** 字母、数字、标点这些有落点的键。 */
        const val CHAR = 0
        const val SPACE = 1
        const val DELETE = 2
        const val OTHER = 3
        const val SIZE = 4
    }

    /** 一组计数的下标。新增只能往后加，旧文件少的列按 0 读。 */
    object Count {
        /** 按下的键（所有键）。 */
        const val KEY_DOWNS = 0
        /** 其中有落点的键（字母等）。 */
        const val CHAR_PRESSES = 1
        /** 删除键按下。 */
        const val DELETES = 2
        /** 点按出的字紧接着就被删除键删掉。 */
        const val REGRETS = 3
        /** 删掉以后重打成了别的键（记进 [Board.retyped]）。 */
        const val RETYPED = 4
        /** 删掉以后重打的还是这个键。 */
        const val RETYPED_SAME = 5
        /** 选了纠错候选，并且对上了刚才那几下按键。 */
        const val CORRECTED_PICKS = 6
        /** 选了纠错候选，但对不上刚才的按键（改过拼音、不是 26 键等），没有记落点。 */
        const val CORRECTED_UNMATCHED = 7
        /** 纠错候选里的「颠倒」。 */
        const val SWAPS = 8
        /** 其中这两下出字顺序和按下顺序本来就是反的（两根手指交叠）。 */
        const val SWAPS_FROM_ORDER = 9
        /** 没出字的按下里像点按的（时间短、手指几乎没动）。 */
        const val LOST_TAPS = 10
        /** 空格还按着、后按的键先抬手时，空格提前出了字（字母键的这种情况记在 [Outcome.TAP_EARLY]）。 */
        const val EARLY_SPACE = 11
        /** 删除键这类会连发的键提前出了字。 */
        const val EARLY_REPEAT = 12
        /** 删除键这一下没有照常删一个字（拖动删除、长按连删、划掉整段等），之后不再对「删掉重打」。 */
        const val DELETE_OTHER = 13
        /** 空格出字以后紧接着就按了删除键。 */
        const val SPACE_REGRETS = 14
        const val SIZE = 15
    }

    /** 点按抬手时手指在哪：还在这个键里，或者从哪一边出了键。 */
    object UpSide {
        const val INSIDE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val ABOVE = 3
        const val BELOW = 4
        const val SIZE = 5
        /** 只在「按成>想按」的落点里用：记这一对的时候手指还没抬。 */
        const val UNKNOWN = 5
    }

    /** [Board.fate] 里分的三种事：字母键点按出字、其中紧接着被删掉的、其中事后改成了别的键的（删掉重打或选了纠错候选）。 */
    object Fate {
        const val TAP = 0
        const val REGRET = 1
        const val CHANGED = 2
        const val SIZE = 3
    }

    /** 稀疏计数：下标 → 次数。 */
    class Sparse {
        val counts = HashMap<Int, Long>()
        fun add(index: Int, amount: Long = 1) { counts[index] = (counts[index] ?: 0L) + amount }
        operator fun get(index: Int): Long = counts[index] ?: 0L
        val total: Long get() = counts.values.sum()
        fun addAll(other: Sparse) { other.counts.forEach { (k, v) -> add(k, v) } }
        fun copy() = Sparse().also { it.counts.putAll(counts) }
        fun encode(): String = counts.keys.sorted().joinToString(",") { "$it:${counts.getValue(it)}" }
        companion object {
            fun decode(text: String) = Sparse().also { out ->
                text.split(',').forEach { item ->
                    val colon = item.indexOf(':')
                    if (colon > 0) {
                        val index = item.substring(0, colon).toIntOrNull()
                        val count = item.substring(colon + 1).toLongOrNull()
                        if (index != null && index >= 0 && count != null && count > 0) out.add(index, count)
                    }
                }
            }
        }
    }

    /** 一个键的计数。落点格是 [GRID]×[GRID]，下标 = 行 × [GRID] + 列，从键（含它管的键缝）的左上角起。 */
    class Key {
        /** 点按出字的那些按下落在哪。 */
        val land = Sparse()
        /** 其中紧接着被删掉的。 */
        val regret = Sparse()
        /** 其中不是紧接着删、但后来删掉重打成了别的键的（和 [regret] 不重复）。 */
        val changed = Sparse()
        /** 没出字的按下落在哪。 */
        val lost = Sparse()
        /** 键的大小（dp，含键缝）：次数、宽之和、高之和、最窄、最宽。横竖屏、悬浮大小混在一起时最窄最宽差得多。 */
        var sized = 0L
        var widthSum = 0.0
        var heightSum = 0.0
        var minWidth = Float.MAX_VALUE
        var maxWidth = 0f

        fun size(widthDp: Float, heightDp: Float) {
            sized++; widthSum += widthDp; heightSum += heightDp
            if (widthDp < minWidth) minWidth = widthDp
            if (widthDp > maxWidth) maxWidth = widthDp
        }

        fun addAll(other: Key) {
            land.addAll(other.land); regret.addAll(other.regret); changed.addAll(other.changed); lost.addAll(other.lost)
            sized += other.sized; widthSum += other.widthSum; heightSum += other.heightSum
            minWidth = minOf(minWidth, other.minWidth); maxWidth = maxOf(maxWidth, other.maxWidth)
        }

        fun copy() = Key().also { it.addAll(this) }
    }

    /** 一种「语言 · 方案 · 布局」（[TypingStatsCombo.id]；还没上过屏时是 [UNKNOWN_BOARD]）上的计数。 */
    class Board(val id: String) {
        val counts = LongArray(Count.SIZE)
        /** 有落点的键每次按下的结果，下标是 [Outcome]。 */
        val outcomes = LongArray(Outcome.SIZE)
        /** 出字顺序和按下顺序反了：[后出字的那个键的种类 × Kind.SIZE + 抢先出字的键的种类]。 */
        val inversions = LongArray(Kind.SIZE * Kind.SIZE)
        /** 一个键按下时，别的键还有几个没抬：0、1、2、3 个以上（字母、空格、删除这些排了先后的键）。 */
        val overlap = LongArray(4)
        /** 点按抬手时手指的位置，下标是 [UpSide]：如果按抬手点算，出了键的那些会变成别的键。 */
        val upSide = LongArray(UpSide.SIZE)
        /** 输入法没接的输入（键盘还没准备好、上一次上屏还没被接受等）：原因 → 次数。原因是代码里写死的几个词。 */
        val dropped = LinkedHashMap<String, Long>()
        /** 相邻两次按下隔多久，[GAP_STEP_MS] 毫秒一格，最后一格是更久。 */
        val gap = LongArray(GAP_BINS)
        /** 同一个键抬手到再按下隔多久，[SAME_STEP_MS] 毫秒一格；[sameGapNear] 是其中两下落点很近的（疑似一下按成两下）。 */
        val sameGap = LongArray(SAME_BINS)
        val sameGapNear = LongArray(SAME_BINS)
        /** 手指按下、抬起到输入法处理这一下隔多久，[LAG_STEP_MS] 毫秒一格。 */
        val lagDown = LongArray(LAG_BINS)
        val lagUp = LongArray(LAG_BINS)
        /** 每种结果的「手指挪了多远 × 按了多久」：下标 = 距离格 × [TIME_BINS] + 时间格。 */
        val travelTime = Array(Outcome.SIZE) { Sparse() }
        /** 点按、没出字的按下，抬手点相对按下点往哪偏：下标 = 行 × [DRIFT_SIDE] + 列，[DRIFT_STEP_DP] dp 一格，正中是没动。 */
        val driftTap = Sparse()
        val driftNone = Sparse()
        /** 键名（小写字母、数字、标点；太长的记成 other）→ 这个键的计数。 */
        val keys = LinkedHashMap<String, Key>()
        /**
         * 字母键的点按按「按下时别的键有没有还按着 × 离上一次按下隔多久」分开数：点按、其中紧接着被删掉的、
         * 其中事后改成了别的键的。下标见 [fateIndex]。用来看按错是不是集中在打得快、两根手指交叠的时候。
         */
        val fate = Sparse()
        /**
         * 删掉重打成了别的键：「按成的>想按的」→ 按成的那一下的落点（[pairIndex]：落点格，加上抬手时手指在键的哪一边）。
         * 删掉一个字母以后按的是空格，记成「按成的>space」。
         */
        val retyped = LinkedHashMap<String, Sparse>()
        /** 选了纠错候选：「按成的>想按的」（多打的键是「按成的>」）→ 按成的那一下的落点（[pairIndex]）。 */
        val corrected = LinkedHashMap<String, Sparse>()

        fun key(name: String): Key = keys[name] ?: (if (keys.size >= MAX_KEYS) keys.getOrPut(OTHER_KEY) { Key() }
            else Key().also { keys[name] = it })

        fun pair(map: LinkedHashMap<String, Sparse>, name: String): Sparse? =
            map[name] ?: if (map.size >= MAX_PAIRS) null else Sparse().also { map[name] = it }

        fun addAll(other: Board) {
            for (i in counts.indices) counts[i] += other.counts[i]
            for (i in outcomes.indices) outcomes[i] += other.outcomes[i]
            for (i in inversions.indices) inversions[i] += other.inversions[i]
            for (i in overlap.indices) overlap[i] += other.overlap[i]
            for (i in upSide.indices) upSide[i] += other.upSide[i]
            other.dropped.forEach { (reason, n) -> drop(reason, n) }
            for (i in gap.indices) gap[i] += other.gap[i]
            for (i in sameGap.indices) { sameGap[i] += other.sameGap[i]; sameGapNear[i] += other.sameGapNear[i] }
            for (i in lagDown.indices) { lagDown[i] += other.lagDown[i]; lagUp[i] += other.lagUp[i] }
            for (i in travelTime.indices) travelTime[i].addAll(other.travelTime[i])
            driftTap.addAll(other.driftTap); driftNone.addAll(other.driftNone)
            fate.addAll(other.fate)
            other.keys.forEach { (name, k) -> key(name).addAll(k) }
            other.retyped.forEach { (name, s) -> pair(retyped, name)?.addAll(s) }
            other.corrected.forEach { (name, s) -> pair(corrected, name)?.addAll(s) }
        }

        fun drop(reason: String, amount: Long = 1) {
            if (reason in dropped || dropped.size < MAX_REASONS) dropped[reason] = (dropped[reason] ?: 0L) + amount
        }

        fun copy() = Board(id).also { it.addAll(this) }

        val isEmpty: Boolean get() = counts.all { it == 0L } && overlap.all { it == 0L } && dropped.isEmpty()
    }

    val boards = LinkedHashMap<String, Board>()
    /** 最近一次上屏的输入方式：下一批按键先记在它上面（进程重启后接着用）。 */
    var lastBoard = UNKNOWN_BOARD

    fun board(id: String): Board = boards[id] ?: (if (boards.size >= MAX_BOARDS) boards.getOrPut(UNKNOWN_BOARD) { Board(UNKNOWN_BOARD) }
        else Board(id).also { boards[id] = it })

    fun isEmpty(): Boolean = boards.values.all { it.isEmpty }

    /** 把 [other] 的计数加进来（读盘得到的旧数据 + 读盘前已经记下的新计数）；[lastBoard] 取 [other] 的。 */
    fun plus(other: TouchStatsData): TouchStatsData {
        val out = copy()
        other.boards.forEach { (id, b) -> out.board(id).addAll(b) }
        if (other.lastBoard != UNKNOWN_BOARD) out.lastBoard = other.lastBoard
        return out
    }

    fun copy(): TouchStatsData = TouchStatsData().also { out ->
        boards.forEach { (id, b) -> out.boards[id] = b.copy() }
        out.lastBoard = lastBoard
    }

    // ---------- 存取 ----------

    /**
     * 一行一项的纯文本。`b=<输入方式>` 开一段，之后的行都属于它：`counts=…`、`k/<键名>/land=格:次数,…`、
     * `retyped/<按成的>><想按的>=落点:次数,…` 等。只有数字和内置的键名、输入方式 ID。
     */
    fun encode(): String = buildString {
        append("v=1\n")
        append("grid=").append(GRID).append('\n')
        append("last=").append(lastBoard).append('\n')
        boards.values.filter { !it.isEmpty }.forEach { b ->
            append("b=").append(b.id).append('\n')
            append("counts=").append(b.counts.joinToString(",")).append('\n')
            append("outcomes=").append(b.outcomes.joinToString(",")).append('\n')
            append("inversions=").append(b.inversions.joinToString(",")).append('\n')
            append("overlap=").append(b.overlap.joinToString(",")).append('\n')
            append("upside=").append(b.upSide.joinToString(",")).append('\n')
            b.dropped.forEach { (reason, n) -> append("dropped/").append(escape(reason)).append('=').append(n).append('\n') }
            append("gap=").append(b.gap.joinToString(",")).append('\n')
            append("samegap=").append(b.sameGap.joinToString(",")).append('\n')
            append("samegapnear=").append(b.sameGapNear.joinToString(",")).append('\n')
            append("lagdown=").append(b.lagDown.joinToString(",")).append('\n')
            append("lagup=").append(b.lagUp.joinToString(",")).append('\n')
            b.travelTime.forEachIndexed { i, s -> if (s.counts.isNotEmpty()) append("tt/").append(i).append('=').append(s.encode()).append('\n') }
            if (b.driftTap.counts.isNotEmpty()) append("drifttap=").append(b.driftTap.encode()).append('\n')
            if (b.driftNone.counts.isNotEmpty()) append("driftnone=").append(b.driftNone.encode()).append('\n')
            if (b.fate.counts.isNotEmpty()) append("fate=").append(b.fate.encode()).append('\n')
            b.keys.forEach { (name, k) ->
                val prefix = "k/" + escape(name) + "/"
                append(prefix).append("size=").append(k.sized).append(',').append(k.widthSum).append(',').append(k.heightSum)
                    .append(',').append(if (k.sized > 0) k.minWidth else 0f).append(',').append(k.maxWidth).append('\n')
                if (k.land.counts.isNotEmpty()) append(prefix).append("land=").append(k.land.encode()).append('\n')
                if (k.regret.counts.isNotEmpty()) append(prefix).append("regret=").append(k.regret.encode()).append('\n')
                if (k.changed.counts.isNotEmpty()) append(prefix).append("changed=").append(k.changed.encode()).append('\n')
                if (k.lost.counts.isNotEmpty()) append(prefix).append("lost=").append(k.lost.encode()).append('\n')
            }
            b.retyped.forEach { (name, s) -> append("retyped/").append(escape(name)).append('=').append(s.encode()).append('\n') }
            b.corrected.forEach { (name, s) -> append("corrected/").append(escape(name)).append('=').append(s.encode()).append('\n') }
        }
    }

    companion object {
        /** 每个键横竖各分几格。 */
        const val GRID = 10
        const val UNKNOWN_BOARD = "?"
        const val OTHER_KEY = "other"
        const val MAX_BOARDS = 32
        const val MAX_KEYS = 80
        const val MAX_PAIRS = 400
        const val MAX_REASONS = 16

        const val GAP_STEP_MS = 25L
        const val GAP_BINS = 25
        const val SAME_STEP_MS = 10L
        const val SAME_BINS = 21
        /** 同一个键两下落点不到这么远算「很近」（dp）。 */
        const val SAME_NEAR_DP = 12f
        const val LAG_STEP_MS = 4L
        const val LAG_BINS = 26
        const val TRAVEL_STEP_DP = 4f
        const val TRAVEL_BINS = 21
        const val TIME_STEP_MS = 25L
        const val TIME_BINS = 21
        const val DRIFT_STEP_DP = 4f
        /** 偏移图每边几格（单数，正中一格是没动）。 */
        const val DRIFT_SIDE = 31
        /** 没出字的按下：不超过这么久、手指挪动不超过这么远，算「像点按」。 */
        const val LOST_TAP_MS = 300L
        const val LOST_TAP_DP = 20f

        fun bin(value: Long, step: Long, bins: Int): Int = (value.coerceAtLeast(0) / step).coerceAtMost(bins - 1L).toInt()
        fun bin(value: Float, step: Float, bins: Int): Int = (value.coerceAtLeast(0f) / step).toInt().coerceIn(0, bins - 1)

        /** 落点格：[x]、[y] 是手指在键里的位置占键宽、键高的比例（0 到 1）。 */
        fun cell(x: Float, y: Float): Int =
            (y * GRID).toInt().coerceIn(0, GRID - 1) * GRID + (x * GRID).toInt().coerceIn(0, GRID - 1)

        /** 「按成>想按」里一下的落点：[cell] 是落点格，[up] 是抬手时手指在键的哪一边（[UpSide]，还没抬手是 [UpSide.UNKNOWN]）。 */
        fun pairIndex(cell: Int, up: Int): Int = up.coerceIn(0, UpSide.UNKNOWN) * GRID * GRID + cell

        /** [Board.fate] 的下标：[what] 是 [Fate]，[overlapped] 是这一下按着的时候别的键也按着，[gapBin] 是 [gap] 的那一格。 */
        fun fateIndex(what: Int, overlapped: Boolean, gapBin: Int): Int =
            (what * 2 + (if (overlapped) 1 else 0)) * GAP_BINS + gapBin.coerceIn(0, GAP_BINS - 1)

        /** 偏移格：往右、往下为正，单位 dp。 */
        fun driftCell(dxDp: Float, dyDp: Float): Int {
            val half = DRIFT_SIDE / 2
            val col = (Math.round(dxDp / DRIFT_STEP_DP) + half).coerceIn(0, DRIFT_SIDE - 1)
            val row = (Math.round(dyDp / DRIFT_STEP_DP) + half).coerceIn(0, DRIFT_SIDE - 1)
            return row * DRIFT_SIDE + col
        }

        /** 键名里的等号、斜杠、换行换掉，不让它们破坏文件的一行一项。 */
        private fun escape(name: String): String = buildString {
            name.forEach { c -> if (c == '=' || c == '/' || c == '%' || c < ' ') append('%').append(c.code.toString(16).padStart(4, '0')) else append(c) }
        }

        private fun unescape(name: String): String {
            if ('%' !in name) return name
            val out = StringBuilder()
            var i = 0
            while (i < name.length) {
                val c = name[i]
                if (c == '%' && i + 4 < name.length) {
                    val code = name.substring(i + 1, i + 5).toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 5; continue }
                }
                out.append(c); i++
            }
            return out.toString()
        }

        /** 读不懂的行跳过；整份坏了就当没有统计，不影响打字。 */
        fun decode(text: String): TouchStatsData {
            val out = TouchStatsData()
            var board: Board? = null
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                try {
                    val b = board
                    when {
                        key == "last" -> if (value.isNotEmpty()) out.lastBoard = value
                        key == "b" -> board = if (value.isEmpty()) null else out.board(value)
                        b == null -> Unit
                        key == "counts" -> fill(b.counts, value)
                        key == "outcomes" -> fill(b.outcomes, value)
                        key == "inversions" -> fill(b.inversions, value)
                        key == "overlap" -> fill(b.overlap, value)
                        key == "upside" -> fill(b.upSide, value)
                        key.startsWith("dropped/") -> value.toLongOrNull()?.let { if (it > 0) b.drop(unescape(key.substring(8)), it) }
                        key == "gap" -> fill(b.gap, value)
                        key == "samegap" -> fill(b.sameGap, value)
                        key == "samegapnear" -> fill(b.sameGapNear, value)
                        key == "lagdown" -> fill(b.lagDown, value)
                        key == "lagup" -> fill(b.lagUp, value)
                        key == "drifttap" -> b.driftTap.addAll(Sparse.decode(value))
                        key == "driftnone" -> b.driftNone.addAll(Sparse.decode(value))
                        key == "fate" -> b.fate.addAll(Sparse.decode(value))
                        key.startsWith("tt/") -> key.substring(3).toIntOrNull()?.let { i ->
                            if (i in b.travelTime.indices) b.travelTime[i].addAll(Sparse.decode(value))
                        }
                        key.startsWith("retyped/") -> b.pair(b.retyped, unescape(key.substring(8)))?.addAll(Sparse.decode(value))
                        key.startsWith("corrected/") -> b.pair(b.corrected, unescape(key.substring(10)))?.addAll(Sparse.decode(value))
                        key.startsWith("k/") -> {
                            val slash = key.lastIndexOf('/')
                            if (slash > 2) {
                                val k = b.key(unescape(key.substring(2, slash)))
                                when (key.substring(slash + 1)) {
                                    "land" -> k.land.addAll(Sparse.decode(value))
                                    "regret" -> k.regret.addAll(Sparse.decode(value))
                                    "changed" -> k.changed.addAll(Sparse.decode(value))
                                    "lost" -> k.lost.addAll(Sparse.decode(value))
                                    "size" -> value.split(',').let { p ->
                                        val n = p.getOrNull(0)?.toLongOrNull() ?: 0L
                                        if (n > 0) {
                                            k.sized += n
                                            k.widthSum += p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                            k.heightSum += p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                                            k.minWidth = minOf(k.minWidth, p.getOrNull(3)?.toFloatOrNull() ?: Float.MAX_VALUE)
                                            k.maxWidth = maxOf(k.maxWidth, p.getOrNull(4)?.toFloatOrNull() ?: 0f)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                }
            }
            return out
        }

        private fun fill(target: LongArray, value: String) {
            value.split(',').forEachIndexed { i, s -> if (i < target.size) target[i] = s.toLongOrNull() ?: 0 }
        }
    }
}
