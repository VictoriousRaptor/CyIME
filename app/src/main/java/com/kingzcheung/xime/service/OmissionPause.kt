package com.kingzcheung.xime.service

/**
 * 26 键全拼「停顿补算」（漏键/打反纠错）什么时候算、算不算。补算占着引擎线程，算的时候退格和下一个键都要排队等它。
 *
 * - 停顿按这个人自己的打字节奏算：最近几次字母间隔中位数的 1.5 倍，至少 180 ms（原来的固定值），至多 450 ms。
 *   打得慢的人（每键 200～400 ms）原来几乎每个键后都要补算一次，下一个键得等它算完；现在只在真停下来时算。
 * - 补算耗时随拼音长度上涨（长句一次能到几十上百毫秒），同样长度还跟这一串打错了多少有关。本机实测某个长度的补算
 *   连续 [slowRuns] 次超过 [budgetMs]，之后 [retryMs] 内这么长及更长的拼音不再补算，停手后按退格不用等它。
 *   只超一次不停：偶尔一次刚过预算就停，会把之后 [retryMs] 里本来不慢的补算也一起停掉。过了 [retryMs] 再试，
 *   还超就接着停，不超才恢复。快的机器测不到超时，行为不变。
 *
 * 只在主线程调用。时间都是 uptime 毫秒。
 */
internal class OmissionPause(
    private val minDelayMs: Long = 180L,
    private val maxDelayMs: Long = 450L,
    private val budgetMs: Long = 100L,
    private val retryMs: Long = 5 * 60_000L,
    private val slowRuns: Int = 2,
) {
    private val gaps = LongArray(8)
    private var gapCount = 0
    private var nextGap = 0
    private var lastLength = 0
    private var lastAt = 0L

    // 每 8 个字母一档：连续超时了几次（记到 slowRuns 为止），和最近一次超时的时间。
    private val slowCount = IntArray(BUCKETS)
    private val slowAt = LongArray(BUCKETS)

    /** 拼音变了：长度正好加 1 记一次字母间隔；隔了 [MAX_GAP_MS] 以上算停顿，不进节奏。 */
    fun onComposition(length: Int, now: Long) {
        val gap = now - lastAt
        if (lastLength > 0 && length == lastLength + 1 && gap in 1..MAX_GAP_MS) {
            gaps[nextGap] = gap
            nextGap = (nextGap + 1) % gaps.size
            if (gapCount < gaps.size) gapCount++
        }
        lastLength = length
        lastAt = now
    }

    fun delayMs(): Long {
        if (gapCount < 3) return minDelayMs
        val sorted = gaps.copyOf(gapCount).also { it.sort() }
        return (sorted[gapCount / 2] * 3 / 2).coerceIn(minDelayMs, maxDelayMs)
    }

    /** 这么长的拼音现在补不补算：同档或更短的档连续超过预算、还没到重试时间就不补。 */
    fun withinBudget(length: Int, now: Long): Boolean {
        for (bucket in 0..bucket(length)) {
            if (slowCount[bucket] >= slowRuns && now - slowAt[bucket] < retryMs) return false
        }
        return true
    }

    /** 补算实际耗时（只算引擎里的时间，不含排队）。没超预算就把这一档连续超时的次数清掉。 */
    fun onRefined(length: Int, elapsedMs: Long, now: Long) {
        val bucket = bucket(length)
        if (elapsedMs > budgetMs) {
            if (slowCount[bucket] < slowRuns) slowCount[bucket]++
            slowAt[bucket] = now
        } else {
            slowCount[bucket] = 0
        }
    }

    private fun bucket(length: Int) = (length / 8).coerceIn(0, BUCKETS - 1)

    private companion object {
        const val BUCKETS = 11
        const val MAX_GAP_MS = 1500L
    }
}
