package com.kingzcheung.xime.speech

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 一次听写收到的麦克风电平（只算数字，不留声音、不留文字），写进日志，用来比较手机和平板为什么认得不一样
 * （用户 11-22：同样的话平板上几乎没法用）。
 *
 * - 每 [chunkMs] 一块算一个 dBFS；说话时的电平看 90 分位，底噪看 10 分位。
 * - 300 Hz 以下能量占比：桌面、风扇这类低频轰鸣多时高（10-19 平板实测 78–90%），人声正常在三成以下。
 * - 削顶样本数、直流偏移：录音太响或硬件偏置。
 */
internal class InputLevelStats(private val sampleRate: Int = 16000, private val chunkMs: Int = 100) {
    private val chunkSamples = sampleRate * chunkMs / 1000
    private val chunkDb = ArrayList<Float>()
    private var chunkSum = 0.0
    private var chunkCount = 0
    private var peak = 0
    private var clipped = 0L
    private var samples = 0L
    private var sum = 0.0
    private var total = 0.0
    private var low = 0.0
    private var lp = 0.0
    private val alpha = 1.0 - exp(-2.0 * Math.PI * LOW_HZ / sampleRate)

    fun add(buffer: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = buffer[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            if (a >= CLIP) clipped++
            val x = s / 32768.0
            sum += x
            total += x * x
            lp += alpha * (x - lp)
            low += lp * lp
            chunkSum += x * x
            if (++chunkCount == chunkSamples) {
                chunkDb += db(sqrt(chunkSum / chunkCount)).toFloat()
                chunkSum = 0.0; chunkCount = 0
            }
        }
        samples += count
    }

    /** 说话时的电平（dBFS，90 分位）；没收到声音时为 null。 */
    fun speechDb(): Double? = percentile(0.9)
    /** 底噪（dBFS，10 分位）。 */
    fun floorDb(): Double? = percentile(0.1)
    /** 300 Hz 以下能量占比（0~1）。 */
    fun lowShare(): Double = if (total <= 0.0) 0.0 else (low / total).coerceIn(0.0, 1.0)
    fun peakDb(): Double = db(peak / 32768.0)
    fun clippedSamples(): Long = clipped
    fun dcOffset(): Double = if (samples == 0L) 0.0 else sum / samples

    fun summary(): String = "secs=${"%.1f".format(samples.toDouble() / sampleRate)} " +
        "speech=${speechDb()?.let { "%.1f".format(it) } ?: "-"}dBFS floor=${floorDb()?.let { "%.1f".format(it) } ?: "-"}dBFS " +
        "peak=${"%.1f".format(peakDb())}dBFS low300=${"%.2f".format(lowShare())} clipped=$clipped dc=${"%.4f".format(dcOffset())}"

    private fun percentile(p: Double): Double? {
        if (chunkDb.isEmpty()) return null
        val sorted = chunkDb.sorted()
        return sorted[((sorted.size - 1) * p).toInt()].toDouble()
    }

    private companion object {
        const val LOW_HZ = 300.0
        const val CLIP = 32700
        fun db(v: Double): Double = if (v <= 1e-9) -180.0 else 20.0 * log10(v)
    }
}
