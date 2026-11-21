package com.kingzcheung.xime.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 导入的词库给笔画方案用（11-19）：应用词库时换成每字首笔的 stroke_cells 词表，笔画方案依赖它。 */
class StrokeCellDictionaryTest {
    @get:Rule val temp = TemporaryFolder()

    private val table = "---\nname: stroke\n...\n\n你\tpsnhshn\t0\n好\tzphzsh\t0\n律\tppszhhhhs\t0\n叶\tszhhs\t0\n叶\thszhh\t0\n"

    private fun rime(): File = temp.newFolder().also {
        File(it, "stroke.schema.yaml").writeText("schema:\n  schema_id: stroke\n  dependencies:\n    - stroke_phrase\n")
        File(it, "stroke.dict.yaml").writeText(table)
    }

    @Test fun `each word becomes the first stroke of every character`() {
        val firsts = StrokeCellDictionary.firstStrokes(table.lineSequence())
        assertEquals(listOf("pz"), StrokeCellDictionary.codes("你好", firsts))
        // 叶 两种笔顺：两个编码都记。
        assertEquals(listOf("ps", "ph"), StrokeCellDictionary.codes("律叶", firsts))
        assertTrue(StrokeCellDictionary.codes("你們", firsts).isEmpty())
    }

    @Test fun `applying an imported dictionary gives the stroke schema its table and removing it cleans up`() {
        val store = CellDictionaryStore(temp.newFolder())
        val entry = store.install(scelFixture())
        val rime = rime()
        val schemas = listOf(SchemaMeta("stroke", "笔画"))
        val bindings = CellDictionaryRime.prepare(rime, store, schemas, setOf(entry.id))
        assertTrue(bindings.isEmpty())
        val custom = File(rime, "stroke.custom.yaml").readText()
        assertTrue(custom.contains("\"schema/dependencies/+\": [stroke_cells]"))
        val dict = File(rime, "stroke_cells.dict.yaml")
        assertTrue(dict.readText().contains("你好\tpz\t1\n"))
        assertTrue(File(rime, "stroke_cells.schema.yaml").readText().contains("dictionary: stroke_cells"))
        // 每次启动都会再走一遍：没变不重写。
        dict.setLastModified(1_000_000L)
        CellDictionaryRime.prepare(rime, store, schemas, setOf(entry.id))
        assertEquals(1_000_000L, dict.lastModified())

        CellDictionaryRime.prepare(rime, store, schemas, emptySet())
        assertFalse(File(rime, "stroke.custom.yaml").readText().contains("stroke_cells"))
        assertFalse(dict.exists())
        assertFalse(File(rime, "stroke_cells.schema.yaml").exists())
    }

    @Test fun `an imported stroke dictionary reaches the stroke table and is removed with its dictionary`() {
        val store = CellDictionaryStore(temp.newFolder())
        val entry = store.installFile("你好 pz\n律叶 ppszhhhhs'szhhs\n".byteInputStream())
        assertEquals(0, entry.count)
        assertEquals(2, entry.stroke)
        assertEquals("你好\n律叶\n", store.strokeWordsFile(entry.id).readText())
        val rime = rime()
        CellDictionaryRime.prepare(rime, store, listOf(SchemaMeta("stroke", "笔画")), setOf(entry.id))
        val dict = File(rime, "stroke_cells.dict.yaml").readText()
        assertTrue(dict.contains("你好\tpz\t1\n"))
        assertTrue(dict.contains("律叶\tps\t1\n"))
        store.remove(entry.id)
        assertFalse(store.strokeWordsFile(entry.id).exists())
    }

    @Test fun `a stroke schema with its own dependency patch is left alone and pinyin still applies`() {
        val store = CellDictionaryStore(temp.newFolder())
        val entry = store.install(scelFixture())
        val rime = rime()
        val own = "patch:\n  \"schema/dependencies/+\": [my_words]\n"
        File(rime, "stroke.custom.yaml").writeText(own)
        File(rime, "pinyin_simp.schema.yaml").writeText("translator:\n  dictionary: rime_ice\n")
        val bindings = CellDictionaryRime.prepare(rime, store,
            listOf(SchemaMeta("stroke", "笔画"), SchemaMeta("pinyin_simp", "拼音")), setOf(entry.id))
        assertEquals(1, bindings.size)
        assertEquals(own, File(rime, "stroke.custom.yaml").readText())
        assertFalse(File(rime, "stroke_cells.dict.yaml").exists())
    }
}
