package com.kingzcheung.xime.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

data class CustomKeyboardLayout(
    val id: String,
    val name: String,
    val rows: List<List<String>>,
    val redVowels: Boolean = false,
    val redMoved: Boolean = true,
    /**
     * 单独调过的按键宽度，单位是 26 键的一格；没有条目时按字母数（单字母 1 格、合并键 2 格）。
     * 只存差异，旧布局和内置排列读出来仍是空的，行为和以前完全一样。
     */
    val widths: Map<String, Float> = emptyMap(),
) {
    /** Precomputed for the per-key preedit path; copies rebuild it with the new rows. */
    val mergedGroups: List<String> = rows.flatten().filter { it.length > 1 }
    /** Four staggered rows of single letters (6/7/6/7, Typewise): a honeycomb, not the ten-unit frame. */
    val honeycomb: Boolean get() = rows.map { it.size } == HONEYCOMB_ROWS

    /** 这个键占几格：用户调过就按调过的，否则按字母数。 */
    fun widthOf(key: String): Float = widths[key] ?: key.length.toFloat()
    /**
     * 一排按键按原本的格数（10/9/7）等比缩放后的宽度：调宽一个键，同排其它键按比例让出，
     * 整排仍占原来那么多格。键盘、编辑器和缩略预览共用这一份算法，三者看到的宽窄一致。
     */
    fun rowWidths(row: List<String>): List<Float> {
        val nominal = row.sumOf { it.length }.toFloat().coerceAtLeast(0.001f)
        val total = row.sumOf { widthOf(it).toDouble() }.toFloat().coerceAtLeast(0.001f)
        return row.map { widthOf(it) * nominal / total }
    }
    /** 调宽一个键，按 0.1 格取整并限制在可用的范围；调回默认宽度就删掉条目。 */
    fun withWidth(key: String, width: Float): CustomKeyboardLayout {
        if (rows.none { key in it }) return this
        val rounded = (width.coerceIn(MIN_WIDTH, MAX_WIDTH) * 10f).roundToInt() / 10f
        return copy(widths = if (rounded == key.length.toFloat()) widths - key else widths + (key to rounded))
    }

    fun valid(): Boolean {
        if (name.isBlank() || name.length > 32) return false
        if (honeycomb) return (id in BuiltinLetterLayouts.ids || id.matches(Regex("custom_pinyin_[a-f0-9]{32}"))) &&
            rows.flatten().all { it.length == 1 && it[0] in 'a'..'z' } &&
            rows.flatten().joinToString("").toSet() == ('a'..'z').toSet()
        // 内置排列（QWJRTK、Cyletix10、Colemak）是起点，按键可以像自定义布局一样调整（方案由当前排列生成）。
        return (id in BuiltinLetterLayouts.ids || id.matches(Regex("custom_pinyin_[a-f0-9]{32}"))) &&
            rows.map { row -> row.sumOf { it.length } } == (if (hasExtraSlot()) listOf(10, 10, 7) else listOf(10, 9, 7)) &&
            rows.flatten().count { it == EMPTY_SLOT || it == ";" } <= 1 &&
            rows.flatten().all { it == EMPTY_SLOT || it == ";" || it.length in 1..2 && it.all { c -> c in 'a'..'z' } } &&
            rows.flatten().joinToString("").toSet() == (('a'..'z').toSet() + (if (hasSemicolon()) setOf(';') else emptySet()) + (if (EMPTY_SLOT in rows.flatten()) setOf('_') else emptySet()))
    }
    fun representative(c: Char): Char = rows.flatten().firstOrNull { c in it }?.first() ?: c
    fun encode(text: String) = text.map(::representative).joinToString("")
    /** Empty cells are editor-only; typing compacts them without creating a punctuation key. */
    fun typingRows(): List<List<String>> = rows.map { row -> row.filterNot { it == EMPTY_SLOT } }
    fun hasSemicolon() = rows.any { ";" in it }
    private fun hasExtraSlot() = hasSemicolon() || rows.any { EMPTY_SLOT in it }
    fun withSpareSlot(): CustomKeyboardLayout = if (hasExtraSlot() || honeycomb) this else
        copy(rows = rows.mapIndexed { index, row -> if (index == 1) row + EMPTY_SLOT else row })
    fun setSemicolonEnabled(enabled: Boolean): CustomKeyboardLayout {
        val layout = withSpareSlot()
        val newRows = layout.rows.map { row -> row.map {
            if (enabled && it == EMPTY_SLOT) ";" else if (!enabled && it == ";") EMPTY_SLOT else it
        } }
        // 空位和分号是同一格换了个内容，调过的宽度跟着走。
        val newWidths = when {
            enabled -> layout.widths[EMPTY_SLOT]?.let { layout.widths - EMPTY_SLOT + (";" to it) } ?: layout.widths
            else -> layout.widths[";"]?.let { layout.widths - ";" + (EMPTY_SLOT to it) } ?: layout.widths
        }
        return layout.copy(rows = newRows, widths = newWidths)
    }
    private fun referenceRows() = when {
        id == Cyletix10Layout.ID -> Cyletix10Layout.referenceRows
        hasExtraSlot() -> listOf(BASE[0], BASE[1] + ";", BASE[2])
        else -> BASE
    }
    /** A honeycomb has no QWERTY position to compare with, so no letter counts as moved. */
    fun movedLetters(): Set<Char> = if (honeycomb) emptySet() else rows.zip(referenceRows()).flatMap { (row, original) ->
        row.joinToString("").zip(original).filter { (letter, base) -> letter != base && letter in 'a'..'z' }.map { it.first }
    }.toSet()
    fun isRed(letter: Char): Boolean = letter.lowercaseChar().let {
        it in 'a'..'z' && ((redVowels && it in "aeiou") || (redMoved && it in movedLetters()))
    }
    fun swap(first: String, second: String): CustomKeyboardLayout {
        // Different-size keys cannot cross rows without changing physical row widths.
        val a = rows.indexOfFirst { first in it }; val b = rows.indexOfFirst { second in it }
        if (a < 0 || b < 0 || (a != b && first.length != second.length)) return this
        return copy(rows = rows.map { row -> row.map { when(it) { first -> second; second -> first; else -> it } } })
    }
    fun merge(left: String): CustomKeyboardLayout {
        val rowIndex = rows.indexOfFirst { left in it }
        val row = rows.getOrNull(rowIndex) ?: return this
        val index = row.indexOf(left)
        val right = row.getOrNull(index + 1)
        if (index < 0 || right == null || left.length != 1 || right.length != 1 || left[0] !in 'a'..'z' || right[0] !in 'a'..'z') return this
        val merged = left + right
        // 合并键接过左右两格的宽度，合并后这一片还是原来那么大。
        val width = widthOf(left) + widthOf(right)
        val kept = widths - left - right
        return copy(
            rows = rows.mapIndexed { i, r -> if (i == rowIndex) r.take(index) + merged + r.drop(index + 2) else r },
            widths = if (width == merged.length.toFloat()) kept else kept + (merged to width),
        )
    }
    fun split(key: String): CustomKeyboardLayout {
        if (rows.none { key in it }) return this
        // 拆开的两格平分原来那一格的宽度；默认 2 格的合并键拆完各 1 格。
        val each = widthOf(key) / key.length
        val kept = widths - key
        return copy(
            rows = rows.map { row -> row.flatMap { if (it == key) it.map(Char::toString) else listOf(it) } },
            widths = if (each == 1f) kept else kept + key.map { it.toString() to each },
        )
    }
    /**
     * 这个单字母键在 26 键上占的那一格的字母（和 [gestures] 同一套位置），角标按这一格出；
     * 蜂窝没有对应的 26 键位置，按字母自己。分号键挪进字母区时（Cyletix10），被挤到多出来那一格的字母
     * 拿分号键占掉的那一格，26 个字母各占一格不落空，分号键自己不带角标。不在这个布局里的键、分号键返回 null。
     */
    fun positionLetter(key: String): String? {
        if (honeycomb) return key.takeIf { it.length == 1 && rows.any { row -> key in row } }
        val reference = referenceRows()
        val slots = buildMap<String, Char> {
            rows.forEachIndexed { rowIndex, row ->
                var column = 0
                row.forEach { cell ->
                    reference.getOrNull(rowIndex)?.getOrNull(column)?.let { put(cell, it) }
                    column += if (cell == EMPTY_SLOT) 1 else cell.length
                }
            }
        }
        fun letter(c: String) = c.length == 1 && c[0] in 'a'..'z'
        val slot = slots[key]?.takeIf { letter(key) } ?: return null
        if (slot in 'a'..'z') return slot.toString()
        val stranded = slots.filter { (cell, at) -> letter(cell) && at !in 'a'..'z' }.keys.toList()
        val freed = slots.filter { (cell, at) -> !letter(cell) && at in 'a'..'z' }.values.toList()
        return freed.getOrNull(stranded.indexOf(key))?.toString()
    }
    fun gestures(base: Map<String, KeyGestureConfig>): Map<String, KeyGestureConfig> {
        val result = base.toMutableMap()
        rows.forEachIndexed { rowIndex, row ->
            var column = 0
            row.forEach { key ->
                if (key == EMPTY_SLOT) { column++; return@forEach }
                val source = base[key.first().toString()] ?: KeyGestureConfig()
                // Swipes keep their physical position; a honeycomb has none to keep, so each letter keeps its own.
                val hint = if (honeycomb) source.swipeUp else base[referenceRows()[rowIndex][column].toString()]?.swipeUp
                result[key] = source.copy(
                    tap = GestureDef(label = key, value = key.first().toString()), swipeUp = hint,
                    longPress = if (key.length == 1) source.longPress else LongPressConfig("bubble", key.map { GestureDef(label = it.toString(), value = it.toString()) })
                )
                column += key.length
            }
        }
        return result
    }
    /** Generate an independent prism; the base dictionary, translator and all other options are reused. */
    fun schema(base: String): String {
        require(valid())
        var text = base.replace("schema_id: rime_ice", "schema_id: $id")
            .replace("name: 雾凇拼音", "name: ${JSONObject.quote(name)}")
        if (rows.flatten().any { it.length == 2 }) {
            val speller = Regex("(?ms)^speller:.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\\z)").find(text)
                ?: error("中文26键缺少拼写器配置")
            check(speller.value.contains("  algebra:")) { "中文26键缺少编码规则" }
            text = text.replaceRange(speller.range, speller.value.trimEnd() + "\n" + mergedRules() + "\n")
            text = text.replace(Regex("(?m)^  prism:.*$"), "  prism: $id")
            if (!text.contains("  prism: $id")) text = text.replace("translator:\n", "translator:\n  prism: $id\n")
            val letters = "abcdefghijklmnopqrstuvwxyz"
            text = text.replace("cyime_particle_shortcuts:/common\n  alphabet: $letters",
                "cyime_particle_shortcuts:/common\n  alphabet: ${encode(letters)}")
        }
        return text
    }
    /**
     * 合并键的拼写规则：每组派生一条，把组里其他字母换成代表字母（键上第一个字母，点按送的就是它），原拼写保留。
     * 点按打出的全是代表字母，和原来整表 xlit 一样；长按气泡选的另一个字母（如 we 键的 e）也照原拼音认，
     * 不会被整表替换掉而解成别的字（xlit 时 wen 出“额”）。
     */
    fun mergedRules(): String = mergedGroups.joinToString("") { "    - derive/[${it.drop(1)}]/${it[0]}/\n" }

    companion object {
        const val EMPTY_SLOT = "_"
        /** 单键可调的最窄/最宽格数；再宽会挤压同排其它键的可点区域。 */
        const val MIN_WIDTH = 0.5f
        const val MAX_WIDTH = 3f
        /** 1.15.19 及以前生成的整表替换规则（字母表 → 代表字母），初始化时换成 [mergedRules]。 */
        fun legacyRule(layout: CustomKeyboardLayout) = "    - xlit/abcdefghijklmnopqrstuvwxyz/${layout.encode("abcdefghijklmnopqrstuvwxyz")}/\n"
        val HONEYCOMB_ROWS = listOf(6, 7, 6, 7)
        val BASE = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        fun fresh() = CustomKeyboardLayout("custom_pinyin_" + UUID.randomUUID().toString().replace("-", ""), "我的布局", BASE.map { it.map(Char::toString) })
    }
}

/** User-owned layouts. A deleted sample is never seeded again; no personal dictionaries are deleted. */
object CustomKeyboardLayouts {
    const val REVISION = "custom_keyboard_layout_revision"
    const val STATUS = "custom_keyboard_layout_status"
    private var cached: List<CustomKeyboardLayout> by mutableStateOf(emptyList())
    fun find(id: String) = cached.firstOrNull { it.id == id } ?: BuiltinLetterLayouts.find(id)
    /** 方案用的自建布局：布局自己的拼音方案，或双拼在这个布局上的版本（见 DoublePinyinSchemes.isOnLayout）。 */
    fun forSchema(id: String) = find(id) ?: DoublePinyinSchemes.layoutOf(id)?.let { cached.firstOrNull { layout -> layout.id == it } }
    fun isCustom(id: String) = id in BuiltinLetterLayouts.ids || id.startsWith("custom_pinyin_")
    @Synchronized fun load(context: Context): List<CustomKeyboardLayout> {
        val file = File(context.filesDir, "custom-keyboard-layouts.json")
        if (!file.exists()) {
            cached = emptyList()
            persist(context, cached)
        } else {
            val array = JSONArray(file.readText())
            cached = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val version = o.optInt("formatVersion", 1)
                val savedRows = o.getString("rows").split('/').map { it.split(',') }
                val migratedRows = if (version < 2 && savedRows.getOrNull(1)?.lastOrNull() == ";")
                    savedRows.mapIndexed { index, row -> if (index == 1) row.dropLast(1) + CustomKeyboardLayout.EMPTY_SLOT else row }
                else savedRows
                // 版本 3 才有键宽；旧布局按字母数占位，读出来和以前一样。
                val widths = if (version >= 3) {
                    val cells = migratedRows.flatten()
                    val saved = o.optJSONObject("widths")
                    buildMap {
                        saved?.keys()?.forEach { key ->
                            val width = saved.optDouble(key).toFloat()
                            if (key in cells && width.isFinite() && width > 0f)
                                put(key, width.coerceIn(CustomKeyboardLayout.MIN_WIDTH, CustomKeyboardLayout.MAX_WIDTH))
                        }
                    }
                } else emptyMap()
                CustomKeyboardLayout(o.getString("id"), o.getString("name"),
                    migratedRows, o.optBoolean("redVowels"), o.optBoolean("redMoved", true), widths)
            }.also { list -> require(list.all { it.valid() } && list.map { it.id }.distinct().size == list.size) }
        }
        return cached
    }
    private fun persist(context: Context, layouts: List<CustomKeyboardLayout>) {
        val array = JSONArray()
        layouts.forEach { layout -> array.put(JSONObject().put("formatVersion", 3).put("id", layout.id).put("name", layout.name)
            .put("rows", layout.rows.joinToString("/") { it.joinToString(",") }).put("redVowels", layout.redVowels)
            .put("redMoved", layout.redMoved)
            .put("widths", JSONObject().apply { layout.widths.forEach { (key, width) -> put(key, width.toDouble()) } })) }
        writeAtomically(File(context.filesDir, "custom-keyboard-layouts.json"), array.toString())
    }
    private fun writeAtomically(file: File, content: String) {
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { stream -> stream.write(content.toByteArray()); stream.fd.sync() }
            java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    fun save(context: Context, layout: CustomKeyboardLayout) {
        require(layout.valid()) { "布局必须完整包含26个字母，名称为1至32字" }
        // Never call schema migration while holding the layout lock: migration loads layouts too.
        synchronized(this) {
            val old = load(context)
            require(old.none { it.id != layout.id && it.name == layout.name }) { "已有同名方案" }
            val updated = if (old.any { it.id == layout.id }) old.map { if (it.id == layout.id) layout else it } else old + layout
            val dir = SchemaManager.getRimeDir(context)
            val base = File(dir, "rime_ice.schema.yaml")
            check(base.isFile) { "请先完成中文26键部署" }
            val schema = File(dir, "${layout.id}.schema.yaml")
            val previous = schema.takeIf { it.isFile }?.readText()
            try {
                writeAtomically(schema, layout.schema(base.readText()))
                persist(context, updated)
            } catch (e: Exception) {
                if (previous == null) schema.delete() else writeAtomically(schema, previous)
                throw e
            }
            cached = updated
        }
        // 双拼在合并键布局上的版本跟着这个布局的合并键重写，启用方案时一起提供。
        DoublePinyinSchemes.syncLayouts(context)
        SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + layout.id).distinct())
        notifyChanged(context)
    }

    /**
     * The schema a layout should have now. Layout schemas are copies of 中文26键 (rime_ice) with their own ID,
     * name and key order, so they are generated again from the current rime_ice instead of staying a snapshot of
     * the version they were made from. A saved layout uses its rows; a built-in arrangement nobody adjusted keeps
     * the ID and name its file has. Null when rime_ice is not CyIME's own (a market version stays its own).
     */
    internal fun refreshedSchema(base: String, current: String, saved: CustomKeyboardLayout?): String? {
        if (!base.contains("schema_id: rime_ice") || !base.contains("cyime_neighbor_correction: true")) return null
        val text = saved?.takeIf { it.valid() }?.schema(base) ?: run {
            if (saved != null || !base.contains("name: 雾凇拼音")) return null
            val id = Regex("(?m)^  schema_id: (\\S+)").find(current)?.groupValues?.get(1) ?: return null
            val name = Regex("(?m)^  name: (.+)$").find(current)?.groupValues?.get(1) ?: return null
            base.replace("schema_id: rime_ice", "schema_id: $id").replace("name: 雾凇拼音", "name: $name")
        }
        return EnglishCandidatePolicy.install(text)
    }

    /** Bring every layout schema up to the current rime_ice; the previous file is backed up once. */
    fun followBaseSchema(context: Context) {
        synchronized(this) {
            val dir = SchemaManager.getRimeDir(context)
            val base = File(dir, "rime_ice.schema.yaml").takeIf { it.isFile }?.readText() ?: return
            val saved = load(context).associateBy { it.id }
            for (id in BuiltinLetterLayouts.ids + saved.keys) {
                val file = File(dir, "$id.schema.yaml")
                if (!file.isFile) continue
                val old = file.readText()
                val updated = refreshedSchema(base, old, saved[id]) ?: continue
                if (updated == old) continue
                val backup = File(context.filesDir, "rime-upgrade-backups/layout-base/${file.name}")
                if (!backup.exists()) { backup.parentFile!!.mkdirs(); writeAtomically(backup, old) }
                writeAtomically(file, updated)
            }
        }
    }

    /** Upgrade translator flags and the shared candidate policy; keep user key order and dictionaries. */
    fun enableMeasuredCorrection(context: Context) = synchronized(this) {
        val dir = SchemaManager.getRimeDir(context)
        for (layout in load(context)) {
            val file = File(dir, "${layout.id}.schema.yaml")
            if (!file.isFile) continue
            val old = file.readText()
            // 旧的整表 xlit 换成逐组派生：长按选的第二个字母按原拼音认（见 mergedRules），点按的结果不变。
            var updated = EnglishCandidatePolicy.install(old.replace(CustomKeyboardLayout.legacyRule(layout), layout.mergedRules()))
            if (!updated.contains("cyime_neighbor_correction: true")) {
                val section = Regex("(?ms)^translator:\\r?\\n.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\\z)").find(updated) ?: continue
                val translator = section.value.replace(Regex("(?m)^  enable_correction:.*\\r?\\n"), "")
                    .replaceFirst(Regex("^translator:\\r?\\n"), "translator:\n  enable_correction: true\n  cyime_neighbor_correction: true\n")
                updated = updated.replaceRange(section.range, translator)
            }
            if (updated == old) continue
            val backup = File(context.filesDir, "rime-upgrade-backups/neighbor-correction/${file.name}")
            if (!backup.exists()) { backup.parentFile!!.mkdirs(); writeAtomically(backup, old) }
            writeAtomically(file, updated)
        }
    }
    fun delete(context: Context, id: String) {
        val remaining = SchemaManager.getEnabledSchemas(context).filterNot { it == id }
        synchronized(this) {
            val old = load(context)
            require(old.any { it.id == id })
            val schema = File(SchemaManager.getRimeDir(context), "$id.schema.yaml")
            val previous = schema.takeIf { it.isFile }?.readText()
            // Remove only the schema we own; user dictionaries and learned data are retained.
            check(!schema.exists() || schema.delete()) { "无法删除布局配置" }
            try { persist(context, old.filterNot { it.id == id }) }
            catch (e: Exception) { if (previous != null) writeAtomically(schema, previous); throw e }
            cached = old.filterNot { it.id == id }
        }
        CustomLayoutSymbols.clear(context, id)
        // 删掉双拼在这个布局上的版本；正在用时换回那个双拼方案的 26 键。
        DoublePinyinSchemes.syncLayouts(context)
        if (SettingsPreferences.getCurrentSchema(context) == id) SettingsPreferences.setCurrentSchema(context, "rime_ice")
        SchemaManager.setEnabledSchemas(context, (remaining + "rime_ice").distinct())
        notifyChanged(context)
    }
    private fun notifyChanged(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(STATUS, "等待应用布局")
            .putLong(REVISION, System.currentTimeMillis()).apply()
    }
}
