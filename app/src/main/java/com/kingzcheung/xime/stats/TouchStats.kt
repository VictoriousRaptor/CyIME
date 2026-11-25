package com.kingzcheung.xime.stats

/**
 * 按键落点统计的记录入口（数据见 [TouchStatsData]）：只在本机计数，不记录、不保存、不发送打过的任何文字。
 *
 * 只有「装到我的设备」的包会打开（[TouchStatsStore.init] 给 [gate]）；公开的包里 [gate] 一直是 null，
 * 这里每个入口只读一次这个变量就返回。打开时跟着「打字统计」的开关，无痕时不记。
 * 打字路径上只做内存里的加法（一把不争用的锁），不读写磁盘。纯 Kotlin，电脑上能直接测。
 */
object TouchStats {
    /** 现在记不记；null 是这个包里没有这项统计。 */
    @Volatile internal var gate: (() -> Boolean)? = null
    /** 和触摸事件同一个钟的现在（Android 上是 SystemClock.uptimeMillis）。 */
    @Volatile internal var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }
    /** 接在输入方式 ID 后面的区分（横屏）。 */
    @Volatile internal var boardSuffix: () -> String = { "" }

    private val lock = Any()
    private var recorder = TouchStatsRecorder()
    private var dirty = false

    private fun on(): Boolean = gate?.invoke() == true

    // ---------- 字母这类键的手势 ----------

    /**
     * 手指按在一个键上，在键的按下回调之前调：[x]、[y] 是键里的位置，[width]、[height] 是键的大小（含它管的键缝），都是像素；
     * [eventAt] 是这次按下的时刻。之后调 [landed]。空格、删除这类自己管手势的键给 [tracked] = false：只记落点，
     * 没有这一下的号，也不用调 [finish]。
     */
    fun landing(x: Float, y: Float, width: Int, height: Int, density: Float, eventAt: Long, tracked: Boolean = true) {
        if (!on() || density <= 0f) return
        val now = clock()
        synchronized(lock) { recorder.landing(x / density, y / density, width / density, height / density, eventAt, now, tracked) }
    }

    /** 键的按下回调跑完了：这一下的号（给 [early]、[finish]）；没在记、或者是不跟手势的落点时是 0。 */
    fun landed(): Long {
        if (gate == null) return 0L
        return synchronized(lock) { recorder.landed() }
    }

    /** 这一下还按着就按点按提前出字了（后按的键先抬手）。 */
    fun early(token: Long) {
        if (token == 0L) return
        val now = clock()
        synchronized(lock) { recorder.early(token, now) }
    }

    /**
     * 手势结束：[outcome] 是 [TouchStatsData.Outcome]，[dx]、[dy] 是手指最后的位置相对按下点（像素），
     * [eventAt] 是最后一个触摸事件的时刻。
     */
    fun finish(token: Long, outcome: Int, dx: Float, dy: Float, density: Float, eventAt: Long) {
        if (token == 0L || density <= 0f) return
        val now = clock()
        synchronized(lock) { recorder.finish(token, outcome, dx / density, dy / density, eventAt, now); dirty = true }
    }

    // ---------- 排了按下先后的键 ----------

    /** 字母、空格、删除这些键有一下开始了；[others] 是这时别的还没抬起的有几个。 */
    fun pressBegan(others: Int) {
        if (!on()) return
        synchronized(lock) { recorder.pressBegan(others); dirty = true }
    }

    /** 记一次 [TouchStatsData.Count] 里的事（空格、删除键提前出字）。 */
    fun count(index: Int) {
        if (!on()) return
        synchronized(lock) { recorder.count(index); dirty = true }
    }

    // ---------- 输入法服务 ----------

    /** 屏幕键盘按下一个键（[TypingStats.onKeyDown] 转过来，那边已经看过开关和无痕）。 */
    internal fun keyDown(key: String) {
        if (gate == null) return
        val now = clock()
        synchronized(lock) { recorder.keyDown(key, now); dirty = true }
    }

    /** 一个键出字了（空格、删除、回车、数字页的键靠它对上按下；字母键的出字手势自己报）。 */
    fun keyEmit(key: String) {
        if (!on()) return
        val now = clock()
        synchronized(lock) { recorder.keyEmit(key, now) }
    }

    /** 输入法没接的输入；[reason] 是代码里写死的原因。 */
    fun dropped(reason: String) {
        if (!on()) return
        synchronized(lock) { recorder.dropped(reason); dirty = true }
    }

    /** 选了一个候选（[TypingStats.onCandidatePicked] 转过来）。 */
    internal fun picked(correction: String) {
        if (gate == null) return
        synchronized(lock) { recorder.picked(correction); dirty = true }
    }

    /** 文字从屏幕键盘上屏了（[TypingStats.onCommit] 转过来）；[comboId] 是 [TypingStatsCombo.id]。 */
    internal fun committed(comboId: String?) {
        if (gate == null || comboId == null) return
        val id = comboId + boardSuffix()
        synchronized(lock) { recorder.committed(id) }
    }

    // ---------- 存取（[TouchStatsStore]、测试） ----------

    /** 键盘收起：没结束的按下不要了。返回有没有新计数要写盘。 */
    internal fun hidden(): Boolean = synchronized(lock) { recorder.reset(); dirty }

    /** 要写盘的内容（在后台线程取，读盘之后）；没有新计数时是 null。 */
    internal fun takeText(): String? = synchronized(lock) {
        if (!dirty) return null
        dirty = false
        recorder.data.encode()
    }

    /** 读盘得到的旧计数，加上读盘前已经记下的。 */
    internal fun merge(stored: TouchStatsData) = synchronized(lock) { recorder.data = stored.plus(recorder.data) }

    /** 现在的计数的一份拷贝（含还没写盘的）。 */
    internal fun snapshot(): TouchStatsData = synchronized(lock) { recorder.data.copy() }

    /** 清空，只给测试用。 */
    internal fun clear() = synchronized(lock) { recorder = TouchStatsRecorder(); dirty = false }
}
