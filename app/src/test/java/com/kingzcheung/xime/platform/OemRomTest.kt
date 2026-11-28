package com.kingzcheung.xime.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OemRomTest {
    private fun rom(vendor: DeviceVendor, vararg props: Pair<String, String>) =
        OemRom.detect(vendor) { key -> props.toMap()[key] }

    @Test fun oppoFamilyReadsTheNewKeyFirst() {
        assertEquals(OemRom("ColorOS", "14.0.0"), rom(DeviceVendor.OPPO, "ro.build.version.oplusrom" to "V14.0.0"))
        assertEquals(OemRom("ColorOS", "12.1"),
            rom(DeviceVendor.OPPO, "ro.build.version.opporom" to "V12.1", "ro.build.version.oplusrom" to ""))
        assertEquals("OxygenOS", rom(DeviceVendor.OPPO, "ro.oxygen.version" to "11.0", "ro.build.version.opporom" to "V11")?.name)
        assertEquals("realme UI", rom(DeviceVendor.OPPO, "ro.build.version.realmeui" to "V3.0", "ro.build.version.oplusrom" to "V12.1")?.name)
        assertNull(rom(DeviceVendor.OPPO))
    }

    @Test fun vivoTellsOriginFromFuntouch() {
        assertEquals(OemRom("OriginOS", "5.0"), rom(DeviceVendor.VIVO, "ro.vivo.os.name" to "vivo", "ro.vivo.os.version" to "5.0"))
        assertEquals(OemRom("Funtouch OS", "13.0"), rom(DeviceVendor.VIVO, "ro.vivo.os.name" to "Funtouch", "ro.vivo.os.version" to "13.0"))
        assertNull(rom(DeviceVendor.VIVO))
    }

    @Test fun hyperOsWinsOverItsLeftoverMiuiKey() {
        // HyperOS 还留着 ro.miui.ui.version.name=V816（#332 的日志就这样被写成 MIUI V816）。
        assertEquals(OemRom("HyperOS", "2.0.1.0"),
            rom(DeviceVendor.XIAOMI, "ro.mi.os.version.name" to "OS2.0.1.0.VNCCNXM", "ro.miui.ui.version.name" to "V816"))
        assertEquals(OemRom("MIUI", "12.5"), rom(DeviceVendor.XIAOMI, "ro.miui.ui.version.name" to "V125"))
        assertEquals(OemRom("MIUI", "14.0"), rom(DeviceVendor.XIAOMI, "ro.miui.ui.version.name" to "V140"))
        assertEquals(OemRom("MIUI", "12"), rom(DeviceVendor.XIAOMI, "ro.miui.ui.version.name" to "V12"))
    }

    @Test fun huaweiAndHonor() {
        assertEquals(OemRom("HarmonyOS", "4.2.0"), rom(DeviceVendor.HUAWEI, "hw_sc.build.platform.version" to "4.2.0"))
        assertEquals(OemRom("EMUI", "14.0.0"), rom(DeviceVendor.HUAWEI, "ro.build.version.emui" to "EmotionUI_14.0.0"))
        assertEquals(OemRom("MagicOS", "9.0"), rom(DeviceVendor.HONOR, "ro.build.version.magic" to "MagicOS_9.0"))
    }

    @Test fun othersAreStockAndMajorFallsBackToZero() {
        assertNull(rom(DeviceVendor.SAMSUNG, "ro.build.version.oplusrom" to "V14"))
        assertNull(rom(DeviceVendor.OTHER))
        assertEquals(12, OemRom.major(OemRom("ColorOS", "12.1")))
        assertEquals(0, OemRom.major(null))
        assertEquals(0, OemRom.major(OemRom("OriginOS", "")))
        assertEquals("ColorOS 12.1", OemRom("ColorOS", "12.1").label)
        assertEquals("OriginOS", OemRom("OriginOS", "").label)
    }
}
