package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveBarLanguageTapTest {
    @Test fun firstTapOpensTheList() {
        assertEquals("source", liveBarLangTap(null, "source", null, Long.MIN_VALUE, 1000L))
    }

    @Test fun secondTapOnTheSamePillClosesIt() {
        // 弹窗没先关：胶囊自己关
        assertNull(liveBarLangTap("source", "source", null, Long.MIN_VALUE, 2000L))
        // 弹窗先当「点了外面」关掉，胶囊随后收到同一下点击：不再打开
        assertNull(liveBarLangTap(null, "source", "source", 2010L, 2000L))
        assertNull(liveBarLangTap(null, "source", "source", 1990L, 2000L))
    }

    @Test fun tappingThePillLaterOpensItAgain() {
        // 点外面关掉以后过了一会儿再点胶囊：照常打开
        assertEquals("source", liveBarLangTap(null, "source", "source", 2000L, 5000L))
    }

    @Test fun tappingTheOtherPillSwitchesLists() {
        assertEquals("target", liveBarLangTap("source", "target", null, Long.MIN_VALUE, 3000L))
        assertEquals("target", liveBarLangTap(null, "target", "source", 3005L, 3000L))
    }
}
