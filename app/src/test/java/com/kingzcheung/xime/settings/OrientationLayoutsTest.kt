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
}
