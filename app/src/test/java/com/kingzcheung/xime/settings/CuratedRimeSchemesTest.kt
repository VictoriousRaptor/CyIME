package com.kingzcheung.xime.settings

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

class CuratedRimeSchemesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `reviewed Wubi packages are available without an index and do not compare unrelated app versions`() {
        val schemes = CuratedRimeSchemes.merge(emptyList())
        assertEquals(listOf("wubi86", "wubi98", "flypy"), schemes.map { it.id })
        schemes.forEach {
            assertTrue(XimeIndexParser.toItem(it, "1.5.0").compatible)
            assertEquals(1, it.versions.size)
            assertTrue(it.resolvedVersion()!!.downloadUrls.all { source -> source.sha256!!.matches(Regex("[a-f0-9]{64}")) })
        }
        assertEquals(listOf("pinyin_simp", "symbols"), schemes[0].dependencies)
        assertEquals(listOf("pinyin_simp", "symbols"), schemes[1].dependencies)
        // 小鹤音形自带反查和拼字词库（flypydz、flypyok），不拉别的方案。
        assertEquals(emptyList<String>(), schemes[2].dependencies)
    }

    @Test fun `reviewed packages come first while unrelated market compatibility is unchanged`() {
        val unrelated = MarketScheme(id = "future-scheme", appVersion = ">=9.0.0")
        val oldBundle = MarketScheme(id = "builtin", appVersion = ">=2.6.0")
        val upstream98 = MarketScheme(id = "wubi98", currentVersion = "unreviewed-new-version", appVersion = ">=2.3.0")
        val result = CuratedRimeSchemes.merge(listOf(oldBundle, upstream98, unrelated))
        assertEquals(listOf("wubi86", "wubi98", "flypy", "builtin", "future-scheme"), result.map { it.id })
        assertEquals("1.0.0", result[1].currentVersion)
        assertFalse(XimeIndexParser.toItem(result[3], "1.5.0").compatible)
        assertFalse(XimeIndexParser.toItem(result[4], "1.5.0").compatible)
    }

    @Test fun `install selects advertised Wubi scheme only when present and does not affect other packages`() {
        val variants = setOf("wubi_pinyin", "wubi_trad", "wubi86")
        assertEquals("wubi86", CuratedRimeSchemes.preferredSchema("wubi86", variants))
        assertEquals("wubi98", CuratedRimeSchemes.preferredSchema("wubi98", setOf("wubi98")))
        // 小鹤音形包里还有 flypydz、flypyok 两个辅助方案，装完选中的是 flypy 本身。
        assertEquals("flypy", CuratedRimeSchemes.preferredSchema("flypy", setOf("flypydz", "flypyok", "flypy")))
        assertNull(CuratedRimeSchemes.preferredSchema("wubi86", setOf("wubi_pinyin")))
        assertNull(CuratedRimeSchemes.preferredSchema("builtin", setOf("builtin", "handwriting")))
    }

    @Test fun `download source and reviewed archive identities stay pinned`() {
        val schemes = CuratedRimeSchemes.merge(emptyList())
        val sources86 = schemes[0].resolvedVersion()!!.downloadUrls
        assertEquals(2, sources86.size)
        assertEquals("https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.schema.yaml", sources86[0].url)
        assertEquals("cdb5aac1a9aa071552d5fdffdfe5a6618b429b19358b8b1e003130e835d5a166", sources86[0].sha256)
        assertEquals("https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.dict.yaml", sources86[1].url)
        assertEquals("f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a", sources86[1].sha256)
        val source98 = schemes[1].resolvedVersion()!!.downloadUrls.single()
        assertEquals("https://github.com/cz-archive/rime-wubi98/archive/refs/tags/1.0.0.tar.gz", source98.url)
        assertEquals("7d105524b81373c7fef0cbfd197fa3c236bcb4264b1935c5e384bacb10fece53", source98.sha256)
        val sourceFlypy = schemes[2].resolvedVersion()!!.downloadUrls.single()
        assertEquals("https://github.com/cubercsl/rime-flypy/archive/9ee464765e325dfd4b04926028d79d60882e653e.tar.gz", sourceFlypy.url)
    }

    @Test fun `existing market installer accepts both verified loose Wubi resources without importing other schemes`() = runBlocking {
        val files = temporary.newFolder("files")
        val context = mock<Context> { on { filesDir } doReturn files }
        val market = SchemaManager.getMarketDir(context, "wubi86").apply { mkdirs() }
        val source = linkedMapOf(
            "wubi86.schema.yaml" to "schema:\n  schema_id: wubi86\n  name: 五笔86\ntranslator:\n  dictionary: wubi86\n",
            "wubi86.dict.yaml" to "---\nname: wubi86\nversion: '1'\n...\n你\twqiy\n",
        )
        source.forEach { (name, content) -> File(market, name).writeText(content) }
        assertEquals(source.keys, SchemaManager.listInstallTargetFiles(context, "wubi86").toSet())
        val staged = temporary.newFolder("staged")
        assertTrue(SchemaManager.installFromMarketToRime(context, "wubi86", staged))
        assertEquals(source.keys, staged.listFiles()!!.map { it.name }.toSet())
        source.forEach { (name, content) -> assertEquals(content, File(staged, name).readText()) }
    }
}
