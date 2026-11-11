package com.kingzcheung.xime.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAbiTest {
    @Test fun processArchitectureMapsToTheAbiOfItsLibraries() {
        assertEquals("arm64-v8a", NativeAbi.of("aarch64"))
        // 64 位手机上的 32 位进程
        assertEquals("armeabi-v7a", NativeAbi.of("armv8l"))
        assertEquals("armeabi-v7a", NativeAbi.of("armv7l"))
        assertEquals("x86_64", NativeAbi.of("x86_64"))
        assertEquals("x86", NativeAbi.of("i686"))
    }

    @Test fun ocrEngineIsOneVerifiedLibraryForThisAbi() {
        val version = BuiltinModelCatalog.models.first { it.id == BuiltinModelCatalog.OCR_ENGINE }.resolvedVersion()!!
        assert(version.archiveUrl!!.endsWith("-${NativeAbi.current}.tar.bz2"))
        assertEquals(listOf("libopencv_java4.so"), version.files.map { it.name })
        assert(version.files.single().sha256.length == 64 && version.sha256.length == 64)
    }
}
