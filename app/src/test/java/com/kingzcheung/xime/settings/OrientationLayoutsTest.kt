package com.kingzcheung.xime.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrientationLayoutsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val zh = InputLanguage.CHINESE
    private val entries = listOf("rime_ice", "t9_pinyin", "japanese", "japanese_kana")
        .map { SchemaInfo(it, it, "", "", "") }

    private fun portrait() = RuntimeEnvironment.setQualifiers("w411dp-h891dp-port")
    private fun landscape() = RuntimeEnvironment.setQualifiers("w891dp-h411dp-land")

    @Test fun offByDefaultChangesNothing() {
        portrait()
        assertFalse(OrientationLayouts.isEnabled(ctx))
        InputModes.selectProfile(ctx, "t9_pinyin", zh)
        landscape()
        InputModes.selectProfile(ctx, "rime_ice", zh)
        assertNull(OrientationLayouts.slot(ctx, zh, true))
        assertNull(OrientationLayouts.slot(ctx, zh, false))
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries))
        portrait()
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries))
    }

    @Test fun landscapeQwertyPortraitNineKeys() {
        portrait()
        InputModes.selectProfile(ctx, "t9_pinyin", zh)
        OrientationLayouts.setEnabled(ctx, true)
        // 竖屏九键时转横屏点 26 键：横屏记 26 键，竖屏补记成之前的九键。
        landscape()
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
        InputModes.selectProfile(ctx, "rime_ice", zh)
        assertEquals("rime_ice", OrientationLayouts.slot(ctx, zh, true))
        assertEquals("t9_pinyin", OrientationLayouts.slot(ctx, zh, false))
        portrait()
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "rime_ice", entries))
        landscape()
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
        // 已在这个方向的条目上：不动。
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries))
    }

    @Test fun otherLanguagesRemovedEntriesAndSwitchingOffKeepTheCurrentEntry() {
        portrait()
        OrientationLayouts.setEnabled(ctx, true)
        InputModes.selectProfile(ctx, "t9_pinyin", zh)
        landscape()
        InputModes.selectProfile(ctx, "rime_ice", zh)
        // 日语没记过，中文的记录不影响日语。
        assertEquals("japanese_kana", OrientationLayouts.prepared(ctx, "japanese_kana", entries))
        // 记的条目被删掉了：留在当前条目。
        portrait()
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries.filter { it.schemaId != "t9_pinyin" }))
        // 关掉后清掉记录，再打开也不会带回旧布局。
        OrientationLayouts.setEnabled(ctx, false)
        OrientationLayouts.setEnabled(ctx, true)
        assertNull(OrientationLayouts.slot(ctx, zh, false))
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries))
    }

    private fun outerScreen() = RuntimeEnvironment.setQualifiers("sw380dp-w380dp-h820dp-port")
    private fun innerScreen() = RuntimeEnvironment.setQualifiers("sw650dp-w650dp-h820dp-port")

    /** GitHub #396：折叠屏外屏九键、内屏 26 键，两块屏都是竖屏，开合后各是各的。 */
    @Test fun foldableScreensKeepTheirOwnLayout() {
        outerScreen()
        OrientationLayouts.setEnabled(ctx, true)
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "rime_ice", entries))
        InputModes.selectProfile(ctx, "t9_pinyin", zh)
        // 展开到内屏：内屏还没记过，先不换；点 26 键记到内屏。
        innerScreen()
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
        InputModes.selectProfile(ctx, "rime_ice", zh)
        assertEquals("rime_ice", OrientationLayouts.slot(ctx, zh, false))
        // 合上回外屏是九键，再展开是 26 键。
        outerScreen()
        assertEquals("t9_pinyin", OrientationLayouts.slot(ctx, zh, false))
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "rime_ice", entries))
        innerScreen()
        assertEquals("rime_ice", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
    }

    /** 打开记忆后在外屏没选过布局：在内屏第一次换布局时，外屏补记成换之前用的。 */
    @Test fun foldableOuterScreenIsBackfilledWithThePreviousLayout() {
        outerScreen()
        InputModes.selectProfile(ctx, "t9_pinyin", zh)
        OrientationLayouts.setEnabled(ctx, true)
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
        innerScreen()
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "t9_pinyin", entries))
        InputModes.selectProfile(ctx, "rime_ice", zh)
        outerScreen()
        assertEquals("t9_pinyin", OrientationLayouts.prepared(ctx, "rime_ice", entries))
    }

    /**
     * 用户 11-20：横竖屏选了自定义布局后转屏不换。双拼、码表、英文的字母排列不在方案 id 里，
     * 现在也按方向记：竖屏 Colemak、横屏 26 键，转回去各是各的。
     */
    @Test fun letterLayoutsFollowTheOrientation() {
        val prefs = SettingsPreferences.getPrefsPublic(ctx)
        val usable: (InputLanguage, String) -> Boolean = { _, id -> id != "deleted" }
        // 关着：什么都不记、不换。
        assertEquals(emptyMap<InputLanguage, String?>(), OrientationLayouts.lettersToRestore(prefs, true, emptyMap(), usable))
        OrientationLayouts.setEnabled(ctx, true)
        // 竖屏用着 Colemak，转横屏点 26 键（null = 语言自己的 26 键）：横屏记 26 键，竖屏补记成 Colemak。
        OrientationLayouts.recordLetters(prefs, zh, null, "colemak", landscape = true)
        assertEquals(mapOf(zh to "colemak"), OrientationLayouts.lettersToRestore(prefs, false, emptyMap(), usable))
        assertEquals(mapOf(zh to null), OrientationLayouts.lettersToRestore(prefs, true, mapOf(zh to "colemak"), usable))
        // 已经是这个方向的排列：不动。
        assertEquals(emptyMap<InputLanguage, String?>(), OrientationLayouts.lettersToRestore(prefs, false, mapOf(zh to "colemak"), usable))
        // 英文另记，不影响中文。
        OrientationLayouts.recordLetters(prefs, InputLanguage.ENGLISH, "qwjrtk", null, landscape = false)
        assertEquals(mapOf(zh to "colemak", InputLanguage.ENGLISH to "qwjrtk"),
            OrientationLayouts.lettersToRestore(prefs, false, emptyMap(), usable))
        assertEquals(mapOf(zh to null), OrientationLayouts.lettersToRestore(prefs, true, mapOf(zh to "colemak"), usable))
        // 记的布局被删了：留在现在的。
        OrientationLayouts.recordLetters(prefs, zh, "deleted", null, landscape = false)
        assertEquals(emptyMap<InputLanguage, String?>(),
            OrientationLayouts.lettersToRestore(prefs, false, mapOf(InputLanguage.ENGLISH to "qwjrtk"), usable))
        // 关掉清掉记录。
        OrientationLayouts.setEnabled(ctx, false)
        assertNull(prefs.getString(OrientationLayouts.letterKey(zh, true), null))
    }
}
