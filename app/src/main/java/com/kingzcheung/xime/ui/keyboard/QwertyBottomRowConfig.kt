package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.LayoutKind
import kotlin.math.abs

enum class QwertyBottomKey(val id: String, val label: String, val optional: Boolean = false) {
    SYMBOLS("symbols", "!@#"), NUMBERS("numbers", "123"), COMMA("comma", "，", true),
    SPACE("space", "空格"), PERIOD("period", "。", true), LANGUAGE("language", "语言"), ENTER("enter", "回车");

    val movable: Boolean get() = this != SPACE && this != ENTER
    fun label(cycle: Boolean): String = if (this == SYMBOLS && cycle) "?123" else label
}

/** One canonical order, including the temporarily hidden number key. Enter is always appended. */
@ConsistentCopyVisibility
data class QwertyBottomRowConfig private constructor(val keys: List<QwertyBottomKey>) {
    fun encode(): String = keys.joinToString(",") { it.id }
    fun visible(cycle: Boolean): List<QwertyBottomKey> = keys.filter { !cycle || it != QwertyBottomKey.NUMBERS } + QwertyBottomKey.ENTER

    fun move(key: QwertyBottomKey, direction: Int, cycle: Boolean = false): QwertyBottomRowConfig {
        if (!key.movable || direction !in listOf(-1, 1)) return this
        val visible = visible(cycle).filter { it != QwertyBottomKey.ENTER }
        val index = visible.indexOf(key)
        if (index < 0) return this
        val neighbor = visible.getOrNull(index + direction) ?: return this
        val next = keys.toMutableList()
        val from = next.indexOf(key)
        val to = next.indexOf(neighbor)
        next[from] = neighbor
        next[to] = key
        return QwertyBottomRowConfig(next)
    }

    fun remove(key: QwertyBottomKey): QwertyBottomRowConfig =
        if (key.optional && key in keys) QwertyBottomRowConfig(keys - key) else this

    fun add(key: QwertyBottomKey): QwertyBottomRowConfig =
        if (key.optional && key !in keys) QwertyBottomRowConfig(keys + key) else this

    /** Logical edge colors are resolved before splitting space, so both pieces inherit its role. */
    internal fun slots(cycle: Boolean, split: Boolean): List<QwertyBottomSlot> {
        val visible = visible(cycle)
        val weights = QwertyBottomRowWeights.Standard
        fun fixed(key: QwertyBottomKey): Float = when (key) {
            QwertyBottomKey.SYMBOLS, QwertyBottomKey.NUMBERS -> weights.mode
            QwertyBottomKey.COMMA, QwertyBottomKey.PERIOD -> weights.punctuation
            QwertyBottomKey.LANGUAGE -> weights.language
            QwertyBottomKey.ENTER -> weights.enter
            QwertyBottomKey.SPACE -> 0f
        }
        val space = weights.total - visible.sumOf { fixed(it).toDouble() }.toFloat()
        val slots = visible.mapIndexed { index, key ->
            QwertyBottomSlot(key, if (key == QwertyBottomKey.SPACE) space else fixed(key),
                index < 2 || index >= visible.size - 2)
        }
        if (!split) return slots
        val middle = weights.total / 2f
        val spaceAt = visible.indexOf(QwertyBottomKey.SPACE)
        val beforeSpace = slots.take(spaceAt).sumOf { it.weight.toDouble() }.toFloat()
        val gap = QwertyBottomSlot(null, 0f, false)
        if (beforeSpace < middle - .001f && beforeSpace + space > middle + .001f) {
            val original = slots[spaceAt]
            return slots.take(spaceAt) + original.copy(weight = middle - beforeSpace) + gap +
                original.copy(weight = beforeSpace + space - middle) + slots.drop(spaceAt + 1)
        }
        // A function key straddles the midline: put the gap at the nearest key boundary,
        // keeping each function whole and fitting both groups into equal-width halves.
        val ends = slots.runningFold(0f) { sum, slot -> sum + slot.weight }
        val at = (1 until slots.size).minBy { abs(ends[it] - middle) }
        val left = slots.take(at).map { it.copy(weight = it.weight * middle / ends[at]) }
        val right = slots.drop(at).map { it.copy(weight = it.weight * middle / (weights.total - ends[at])) }
        return left + gap + right
    }

    companion object {
        const val PREF_KEY = "qwerty_bottom_row_order"
        val Default = QwertyBottomRowConfig(listOf(QwertyBottomKey.SYMBOLS, QwertyBottomKey.NUMBERS,
            QwertyBottomKey.COMMA, QwertyBottomKey.SPACE, QwertyBottomKey.PERIOD, QwertyBottomKey.LANGUAGE))
        fun parse(raw: String?): QwertyBottomRowConfig {
            val tokens = raw?.split(',') ?: return Default
            val keys = tokens.map { id -> QwertyBottomKey.entries.firstOrNull { it.id == id.trim() } ?: return Default }
            val required = listOf(QwertyBottomKey.SYMBOLS, QwertyBottomKey.NUMBERS, QwertyBottomKey.SPACE, QwertyBottomKey.LANGUAGE)
            return if (keys.distinct().size != keys.size || QwertyBottomKey.ENTER in keys || !keys.containsAll(required)) Default
                else QwertyBottomRowConfig(keys)
        }
    }
}

internal data class QwertyBottomSlot(val key: QwertyBottomKey?, val weight: Float, val special: Boolean)

/** Match layout facts, including rearranged 26-key alphabets and extra semicolon/choonpu keys. */
internal fun usesCustom26BottomRow(profile: InputProfile, rows: List<List<String>>): Boolean =
    profile.layout.kind == LayoutKind.ALPHABETIC && rows.size == 3 &&
        rows.flatten().all { it.length == 1 } &&
        rows.flatten().filter { it.single() in 'a'..'z' }.sorted() == ('a'..'z').map(Char::toString)
