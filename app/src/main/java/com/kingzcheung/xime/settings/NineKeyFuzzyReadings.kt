package com.kingzcheung.xime.settings

/** 九键左栏的读音索引。按键本身仍查原来的数字词图，点选读音后才用字母查词。 */
internal class NineKeyFuzzyReadings(val scheme: DoublePinyinScheme?, groups: Set<SpellingPatches.Fuzzy>) {
    data class Reading(val pinyin: String, val spelling: String, val digits: Int, val near: Boolean)

    private val index = buildMap<String, MutableList<Reading>> {
        for (reading in DoublePinyinScheme.SYLLABLES) {
            val spelling = code(scheme, reading) ?: continue
            fun add(typed: String, near: Boolean) {
                val keys = code(scheme, typed) ?: return
                val digits = keys.map { digitOf(it) ?: return }.joinToString("")
                getOrPut(digits) { ArrayList() }.add(Reading(reading, spelling, digits.length, near))
            }
            // 全拼的精确读音已有原生左栏；双拼的读音必须按当前方案的两个键计算。
            if (scheme != null) add(reading, false)
            for (typed in alternatives(reading, groups)) add(typed, true)
        }
    }.mapValues { (_, readings) -> readings.sortedBy { it.near }.distinctBy { it.pinyin } }

    fun options(digits: String): List<Reading> = buildList {
        for (length in minOf(6, digits.length) downTo 1) {
            index[digits.substring(0, length)]?.let { addAll(it) }
        }
    }

    companion object {
        private fun code(scheme: DoublePinyinScheme?, reading: String) = if (scheme == null) reading else scheme.code(reading)

        /** 字母所在的数字键；微软双拼 ing 的分号和 L 同在 5 键（[DoublePinyinScheme.t9Digits]）。 */
        private fun digitOf(key: Char): Char? = when (key) {
            in 'a'..'z' -> "22233344455566677778889999"[key - 'a']
            ';' -> '5'
            else -> null
        }
        private val initials = setOf(SpellingPatches.Fuzzy.Z, SpellingPatches.Fuzzy.C, SpellingPatches.Fuzzy.S,
            SpellingPatches.Fuzzy.NL, SpellingPatches.Fuzzy.FH, SpellingPatches.Fuzzy.RL)

        private fun alternatives(reading: String, groups: Set<SpellingPatches.Fuzzy>): Set<String> {
            val starts = listOf(reading) + groups.filter { it in initials }.flatMap { it.spellings(reading) }
            return buildSet {
                addAll(starts)
                for (start in starts) for (group in groups) if (group !in initials) addAll(group.spellings(start))
                remove(reading)
            }
        }

        /** 字母补丁排在数字映射之后。用临时字符避免一组规则的输出继续触发另一组。 */
        fun doubleRules(scheme: DoublePinyinScheme, groups: Set<SpellingPatches.Fuzzy>): List<String> {
            val circled = "ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ"
            // 分号（微软双拼的 ing）也先换成临时字符，最后换回来。
            val semicolon = '⊙'
            fun mark(key: Char) = if (key == ';') semicolon else circled[key - 'a']
            val sources = sortedMapOf<String, MutableSet<String>>()
            for (reading in DoublePinyinScheme.SYLLABLES) {
                val from = scheme.code(reading) ?: continue
                for (typed in alternatives(reading, groups)) {
                    val to = scheme.code(typed) ?: continue
                    if (from != to && to.all { it in 'a'..'z' || it == ';' }) sources.getOrPut(to) { sortedSetOf() }.add(from)
                }
            }
            return sources.map { (to, from) ->
                "fuzz/^(${from.joinToString("|")})$/${to.map(::mark).joinToString("")}/"
            } + "xlit/$circled$semicolon/abcdefghijklmnopqrstuvwxyz;/"
        }
    }
}
