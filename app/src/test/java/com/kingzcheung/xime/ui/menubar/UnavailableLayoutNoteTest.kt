package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfileSelection
import com.kingzcheung.xime.settings.InputProfiles
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 用户 11-22：选了五笔，点别的布局像被弹回去，以为不能改。灰卡片上要写明不能用什么、点了换成什么。 */
class UnavailableLayoutNoteTest {
    private val entries = listOf("rime_ice", "t9_pinyin", "pinyin_14jian", "wubi86").map { SchemaInfo(it, it, "", "", "") }
    private val wubi = entries.last()

    @Before fun builtInLayouts() {
        KeysConfigHelper.clearSchemaBindingsForTest()
        InputProfiles.updateInstalled(emptyList())
    }

    @Test fun greyLayoutsSayWhyAndWhatATapDoes() {
        val layouts = InputProfileSelection.languageLayouts(entries, wubi)
        val switches = layouts.associate { it.id to InputProfileSelection.switchLayout(entries, wubi, it.id) }
        val notes = InputProfileSelection.unavailableNotes(wubi, switches)
        assertFalse("五笔能用 26 键，不该标灰", InputLayout.QWERTY.id in notes)
        assertEquals("不能用${wubi.schemeLabel}\n点击切换拼音", notes[InputLayout.T9.id])
        assertTrue(notes.toString(), InputLayout.MERGED14.id in notes)
    }
}
