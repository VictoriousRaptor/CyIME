package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-19（存储空间）：0 B 的行不该有「清理」；不能清的行从来没有；正在清的那行留着转圈。 */
class StorageClearButtonTest {
    @Test fun clearShowsOnlyWhenThereIsSomethingToClear() {
        assertTrue(storageClearShown(clearable = true, sizeBytes = 565_000, cleaning = false))
        assertFalse(storageClearShown(clearable = true, sizeBytes = 0, cleaning = false))
        assertTrue(storageClearShown(clearable = true, sizeBytes = 0, cleaning = true))
        assertFalse(storageClearShown(clearable = false, sizeBytes = 112_000, cleaning = false))
    }
}
