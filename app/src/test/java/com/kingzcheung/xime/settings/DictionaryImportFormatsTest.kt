package com.kingzcheung.xime.settings

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryImportFormatsTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    private fun <T> withFile(bytes: ByteArray, action: (File) -> T): T {
        val dir = Files.createTempDirectory("import").toFile()
        try { return action(File(dir, "in").apply { writeBytes(bytes) }) } finally { dir.deleteRecursively() }
    }

    @Test fun `Gboard zip is read through its dictionary txt`() {
        val bytes = zip("readme.txt" to "nothing", "dictionary.txt" to
            "# Gboard Dictionary version:1\nnihao\t你好\tzh-CN\nshijie\t世界\tzh-CN\nhello\thello\ten-US\n")
        val parsed = withFile(bytes) { file -> TxtCellDictionary.parse(DictionaryImportFormats.toText(file)!!.readBytes()) }
        assertEquals(listOf("你好|ni hao", "世界|shi jie"), parsed.entries.map { it.word + "|" + it.code })
        assertEquals(listOf("hello"), parsed.english)
    }

    @Test fun `zip without a word list is refused`() {
        val error = runCatching { withFile(zip("a.png" to "x")) { DictionaryImportFormats.toText(it) } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("dictionary.txt"))
    }

    @Test fun `Microsoft Pinyin dctx drops tone numbers`() {
        val xml = "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<ns1:Dictionary xmlns:ns1=\"http://www.microsoft.com/ime/dctx\">" +
            "<ns1:DictionaryHeader></ns1:DictionaryHeader>\r\n<ns1:DictionaryEntry>\r\n<ns1:InputString>ni3 hao3</ns1:InputString>\r\n" +
            "<ns1:OutputString>你好</ns1:OutputString>\r\n<ns1:Exist>1</ns1:Exist>\r\n</ns1:DictionaryEntry>\r\n" +
            "<ns1:DictionaryEntry><ns1:InputString>lv4 se4</ns1:InputString><ns1:OutputString>绿色</ns1:OutputString></ns1:DictionaryEntry></ns1:Dictionary>"
        val parsed = withFile(xml.toByteArray(Charsets.UTF_8)) { file ->
            TxtCellDictionary.parse(DictionaryImportFormats.toText(file)!!.readBytes())
        }
        assertEquals(listOf("你好|ni hao", "绿色|lv se"), parsed.entries.map { it.word + "|" + it.code })
    }

    @Test fun `other files are imported as they are`() {
        assertNull(withFile("你好 ni hao\n".toByteArray()) { DictionaryImportFormats.toText(it) })
        assertNull(withFile("<html>你好</html>".toByteArray()) { DictionaryImportFormats.toText(it) })
    }

    @Test fun `the import button takes a Gboard zip and leaves no temporary files`() {
        val dir = Files.createTempDirectory("cells").toFile()
        try {
            val store = CellDictionaryStore(dir)
            val bytes = zip("dictionary.txt" to "# Gboard Dictionary version:1\nnihao\t你好\tzh-CN\n")
            val entry = store.installFile(bytes.inputStream())
            assertEquals(1, entry.count)
            assertTrue(store.dictionaryFile(entry.id).readText().contains("你好\tni hao\t100"))
            assertEquals(1, store.installFile("世界 shi jie\n".toByteArray().inputStream()).count)  // plain text unchanged
            assertTrue(dir.listFiles()!!.none { it.name.startsWith(".import-") })
        } finally { dir.deleteRecursively() }
    }
}
