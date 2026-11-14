package com.kingzcheung.xime.settings

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 英文单词方案（cyime_english）不开 ascii 也是英文键盘：角标和英文直接输入一样是英文半角符号，
 * 不套中文符号（11-12 反馈：英文键盘没改设置就自动套用中文符号布局、全角）。
 */
class EnglishWordsSymbolsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repoFile(rel: String) = listOf(File(rel), File("app/$rel"), File("../$rel")).first { it.isFile }
    private val yaml by lazy { repoFile("src/main/assets/xime.yaml").readText() }

    @After fun reset() {
        KeysConfigHelper.setActiveKeyboardSchema("")
        KeysConfigHelper.clearSchemaBindingsForTest()
    }

    private fun loadKeyboards() {
        val assets = mock<android.content.res.AssetManager>()
        whenever(assets.open("xime.yaml")).thenAnswer { yaml.byteInputStream() }
        whenever(assets.open("xime.custom.yaml")).thenThrow(java.io.IOException("No custom override"))
        val context = mock<android.content.Context>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.filesDir).thenReturn(temp.root)
        whenever(context.getSharedPreferences("kime_settings", android.content.Context.MODE_PRIVATE))
            .thenReturn(mock<android.content.SharedPreferences>())
        KeysConfigHelper.loadConfig(context)
    }

    @Test fun `english words keyboard carries the english corner symbols`() {
        loadKeyboards()
        assertTrue(InputLanguage.ENGLISH.typesLettersDirectly)
        KeysConfigHelper.setActiveKeyboardSchema(InputModes.ENGLISH_WORDS)
        for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
            val gesture = KeysConfigHelper.getKeyGesture(key, false)
            assertEquals(key, QwertySwipeSymbols.up(true).getValue(key), gesture?.swipeUp?.value)
            assertEquals(key, QwertySwipeSymbols.down(true).getValue(key), gesture?.swipeDown?.value)
        }
    }
}
