package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 推荐配置页去别的页面再回来：可删的还是那几项就留着原来的勾选，换了一批就重新全选（返回 null）。 */
class SetupGuideDraftTest {
    @Test fun keepsUncheckedItemsWhenComingBack() {
        val draft = SetupGuideDraft()
        assertNull(draft.removeIdsFor(setOf("a", "b")))
        draft.keepRemoveIds(setOf("a", "b"), setOf("a"))
        assertEquals(setOf("a"), draft.removeIdsFor(setOf("a", "b")))
    }

    @Test fun startsOverWhenRemovableItemsChange() {
        val draft = SetupGuideDraft()
        draft.keepRemoveIds(setOf("a", "b"), setOf("a"))
        assertNull(draft.removeIdsFor(setOf("a", "c")))
    }
}
