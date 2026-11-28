package com.kingzcheung.xime.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBackgroundGuideTest {
    private val guided = listOf(DeviceVendor.XIAOMI, DeviceVendor.VIVO, DeviceVendor.OPPO, DeviceVendor.HUAWEI, DeviceVendor.HONOR)

    @Test fun onlyTheAggressiveVendorsGetTheRow() {
        for (vendor in guided) {
            assertTrue(vendor.name, OemBackgroundGuide.steps(vendor, null)!!.isNotBlank())
            assertTrue(vendor.name, OemBackgroundGuide.candidates(vendor).isNotEmpty())
        }
        for (vendor in listOf(DeviceVendor.SAMSUNG, DeviceVendor.OTHER)) {
            assertNull(OemBackgroundGuide.steps(vendor, null))
            assertTrue(OemBackgroundGuide.candidates(vendor).isEmpty())
        }
    }

    @Test fun stepsFollowTheSystemVersion() {
        val old = OemBackgroundGuide.steps(DeviceVendor.OPPO, OemRom("ColorOS", "11.3"))
        val new = OemBackgroundGuide.steps(DeviceVendor.OPPO, OemRom("ColorOS", "12.1"))
        assertNotEquals(old, new)
        assertEquals(new, OemBackgroundGuide.steps(DeviceVendor.OPPO, null))
        assertNotEquals(OemBackgroundGuide.steps(DeviceVendor.VIVO, OemRom("Funtouch OS", "13.0")),
            OemBackgroundGuide.steps(DeviceVendor.VIVO, OemRom("OriginOS", "5.0")))
    }

    @Test fun candidatesAreFullyQualified() {
        for (vendor in guided) for ((pkg, cls) in OemBackgroundGuide.candidates(vendor)) {
            assertTrue("$pkg/$cls", cls.contains('.') && pkg.contains('.'))
        }
    }
}
