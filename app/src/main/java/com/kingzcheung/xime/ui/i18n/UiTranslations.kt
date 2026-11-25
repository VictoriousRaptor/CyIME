package com.kingzcheung.xime.ui.i18n

/**
 * 界面文字的英文表（gettext 式：中文原文就是键）。
 *
 * 设置页源码照旧写中文；英文界面时 [Text] 用这张表把要显示的整句换成英文。没有对应英文的照原样显示中文，
 * 所以新写的中文设置文字不会出错，只是先显示中文，补进 assets/i18n/en.tsv 就变英文
 * （`python tools/i18n/extract_ui_strings.py --missing` 列出还没翻的）。
 *
 * 表的格式：每行「中文<TAB>英文」，`\n` `\t` `\\` 转义，# 开头是注释；英文写成 = 的有意不翻。
 * 字符串模板在键里写成 {0} {1}…（按源码里出现的顺序），英文里同样的 {n} 换回实际的值；
 * 值本身是中文且表里有时也换成英文（如「已切换到 {0}」里的方案名）。
 *
 * 纯 Kotlin，不碰 Android，单元测试直接用。
 */
class UiTranslations private constructor(
    private val exact: Map<String, String>,
    private val templates: List<Template>,
) {
    val size: Int get() = exact.size + templates.size

    private class Template(val pattern: Regex, val slots: List<Int>, val english: String, val literalLength: Int)

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /** [text] 的英文；表里没有就原样返回。只含中文以外字符的直接返回。 */
    fun translate(text: String): String {
        if (!hasHan(text)) return text
        // 界面句子不会这么长；用户粘进来的长文（设置搜索框里「没有找到“…”」）逐段套模板会把主线程卡死。
        if (text.length > MAX_TRANSLATABLE) return text
        synchronized(cache) { cache[text]?.let { return it } }
        val result = translate(text, 0) ?: text
        synchronized(cache) { cache[text] = result }
        return result
    }

    /** 表里有这一句（整句或模板）时的英文，没有返回 null。 */
    fun lookup(text: String): String? = if (hasHan(text)) translate(text, 0) else null

    private fun translate(text: String, depth: Int): String? {
        exact[text]?.let { return it }
        matchTemplate(text, depth)?.let { return it }
        if (depth > 1) return null
        // 两侧空白、句末标点不同（「名称：」「正在下载…」）时按去掉后的找。
        val trimmed = text.trim()
        if (trimmed != text && trimmed.isNotEmpty()) {
            translate(trimmed, depth + 1)?.let { return text.replace(trimmed, it) }
        }
        for ((zh, en) in TRAILING) {
            if (text.length > zh.length && text.endsWith(zh)) {
                translate(text.dropLast(zh.length), depth + 1)?.let { return it + en }
            }
        }
        // 多段拼起来的（换行、「 · 」、「、」连接的几项），能翻的段各自翻。
        for ((zh, en) in SEPARATORS) {
            if (zh !in text) continue
            val parts = text.split(zh)
            var changed = false
            val out = parts.map { part ->
                if (!hasHan(part)) part else (translate(part, depth + 1)?.also { changed = true } ?: part)
            }
            if (changed) return out.joinToString(en)
        }
        return null
    }

    private fun matchTemplate(text: String, depth: Int): String? {
        for (template in templates) {
            val match = template.pattern.matchEntire(text) ?: continue
            var result = template.english
            template.slots.forEachIndexed { group, slot ->
                val value = match.groupValues[group + 1]
                val shown = if (depth < 2 && hasHan(value)) translate(value, depth + 1) ?: value else value
                result = result.replace("{$slot}", shown)
            }
            return result
        }
        return null
    }

    companion object {
        private const val CACHE_SIZE = 1024
        /** 表里最长的键约 2200 字（许可说明）；再长的只会是用户的文字，不翻。 */
        private const val MAX_TRANSLATABLE = 4096
        /** 表里英文写成「=」的是有意留中文的（示例候选词、成语、给对面人看的快捷回复等）。 */
        private const val KEEP = "="
        private val SLOT = Regex("\\{(\\d+)\\}")
        private val TRAILING = listOf("：" to ": ", "。" to ".", "…" to "…", "..." to "...", "？" to "?", "！" to "!")
        private val SEPARATORS = listOf("\n" to "\n", " › " to " › ", " · " to " · ", "；" to "; ", "、" to ", ", "，" to ", ")

        val EMPTY = UiTranslations(emptyMap(), emptyList())

        fun hasHan(text: String): Boolean = text.any { it in '㐀'..'鿿' }

        fun parse(tsv: String): UiTranslations {
            val exact = HashMap<String, String>()
            val templates = ArrayList<Template>()
            for (line in tsv.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val zh = unescape(line.substring(0, tab))
                val en = unescape(line.substring(tab + 1))
                if (en.isEmpty() || en == KEEP || !hasHan(zh)) continue
                if (SLOT.containsMatchIn(zh)) template(zh, en)?.let(templates::add) else exact[zh] = en
            }
            // 字面部分长的先试：「已下载 {0} 个」优先于「{0} 个」。
            templates.sortByDescending { it.literalLength }
            return UiTranslations(exact, templates)
        }

        private fun template(zh: String, en: String): Template? {
            val slots = ArrayList<Int>()
            val pattern = StringBuilder()
            var at = 0
            for (m in SLOT.findAll(zh)) {
                pattern.append(Regex.escape(zh.substring(at, m.range.first)))
                pattern.append("(.+?)")
                slots += m.groupValues[1].toInt()
                at = m.range.last + 1
            }
            pattern.append(Regex.escape(zh.substring(at)))
            val literal = SLOT.replace(zh, "")
            // 字面部分没有汉字的模板（「{0}：{1}」）会套住任何文字，不收。
            if (!hasHan(literal)) return null
            return Template(Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL), slots, en, literal.length)
        }

        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(when (val n = s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> n })
                    i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
