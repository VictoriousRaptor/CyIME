package com.kingzcheung.xime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCatalogTest {

    private fun asset(name: String, size: Long = 100, digest: String? = "sha256:ABCDEF") =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/Cyletix/CyIME/releases/download/x/$name"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/Cyletix/CyIME/releases/tag/$tag","assets":[${assets.joinToString(",")}]}"""

    // Same mix as the real list: a draft, a Windows release, the models release and older Android releases.
    private val releases = "[" + listOf(
        release("1.15.6", asset("CyIME-1.15.6-arm64-v8a.apk"), asset("CyIME-1.15.6-full-arm64-v8a.apk"), draft = true),
        release("windows-2026.10.30", asset("CyIME-Windows-Setup-2026.10.30-258D9681.exe")),
        release("1.14.156", asset("CyIME-1.14.156-arm64-v8a.apk", 121031364), asset("CyIME-1.14.156-full-arm64-v8a.apk", 708584112)),
        release("models", asset("cyime_gloss-v12.tsv")),
        release("1.14.72", asset("CyIME-1.14.72-arm64-v8a.apk"), asset("CyIME-1.14.73-full-arm64-v8a.apk")),
        release("1.20.0", asset("CyIME-1.20.0-arm64-v8a.apk"), prerelease = true),
    ).joinToString(",") + "]"

    @Test
    fun picksNewestPublishedAndroidRelease() {
        val latest = AppUpdateCatalog.latest(releases, full = false, abis = listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("1.14.156", latest.version)
        assertEquals("CyIME-1.14.156-arm64-v8a.apk", latest.asset!!.name)
        assertEquals(121031364L, latest.asset!!.sizeBytes)
        assertEquals("abcdef", latest.asset!!.sha256)
        assertEquals("https://github.com/Cyletix/CyIME/releases/tag/1.14.156", latest.pageUrl)
    }

    @Test
    fun fullEditionGetsFullApk() {
        val latest = AppUpdateCatalog.latest(releases, full = true, abis = listOf("arm64-v8a"))!!
        assertEquals("CyIME-1.14.156-full-arm64-v8a.apk", latest.asset!!.name)
    }

    @Test
    fun noMatchingAbiLeavesOnlyThePage() {
        val latest = AppUpdateCatalog.latest(releases, full = false, abis = listOf("x86_64"))!!
        assertEquals("1.14.156", latest.version)
        assertNull(latest.asset)
    }

    @Test
    fun universalIsTheFallback() {
        val list = "[" + release("1.3.12", asset("CyIME-1.3.12-arm64-v8a.apk"), asset("CyIME-1.3.12-universal.apk")) + "]"
        assertEquals("CyIME-1.3.12-universal.apk", AppUpdateCatalog.latest(list, false, listOf("x86_64"))!!.asset!!.name)
    }

    @Test
    fun debugApksAreNotOffered() {
        val list = "[" + release("1.16.1", asset("CyIME-1.16.1-debug-arm64-v8a.apk")) + "]"
        assertNull(AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!.asset)
    }

    @Test
    fun missingDigestStillOffersTheApk() {
        val list = "[" + release("1.16.1", asset("CyIME-1.16.1-arm64-v8a.apk", digest = null)) + "]"
        assertNull(AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!.asset!!.sha256)
    }

    @Test
    fun noReleasesAtAll() {
        assertNull(AppUpdateCatalog.latest("[]", false, listOf("arm64-v8a")))
        assertNull(AppUpdateCatalog.latest("""{"message":"Not Found"}""", false, listOf("arm64-v8a")))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdateCatalog.isNewer("1.14.156", "1.14.95"))
        assertTrue(AppUpdateCatalog.isNewer("1.15.0", "1.14.156"))
        assertFalse(AppUpdateCatalog.isNewer("1.14.156", "1.15.10"))
        assertFalse(AppUpdateCatalog.isNewer("1.15.10", "1.15.10"))
        assertTrue(AppUpdateCatalog.isNewer("1.15.11", "1.15.10-nightly"))
        assertFalse(AppUpdateCatalog.isNewer("models", "1.0.0"))
    }

    @Test
    fun apkFileVersion() {
        assertEquals("1.14.156", AppUpdateCatalog.versionOfApk("CyIME-1.14.156-full-arm64-v8a.apk"))
        assertEquals("1.14.156", AppUpdateCatalog.versionOfApk("CyIME-1.14.156-arm64-v8a.apk.part"))
        assertNull(AppUpdateCatalog.versionOfApk("other.apk"))
    }

    // Same shape scripts/store/sync_modelscope.py app writes to ModelScope app/releases.json.
    private val modelscope = """[{"tag_name":"1.15.47","name":"CyIME 1.15.47","draft":false,"prerelease":false,""" +
        """"html_url":"${AppUpdateCatalog.MODELSCOPE_PAGE}","assets":[""" +
        """{"name":"CyIME-1.15.47-arm64-v8a.apk","size":103000000,"digest":"sha256:AA11",""" +
        """"browser_download_url":"${AppUpdateCatalog.modelscopeAssetUrl("1.15.47", "CyIME-1.15.47-arm64-v8a.apk")}"},""" +
        """{"name":"CyIME-1.15.47-full-arm64-v8a.apk","size":447000000,"digest":"sha256:BB22",""" +
        """"browser_download_url":"${AppUpdateCatalog.modelscopeAssetUrl("1.15.47", "CyIME-1.15.47-full-arm64-v8a.apk")}"}]}]"""

    @Test
    fun modelscopeManifestParsesLikeGitHub() {
        val latest = AppUpdateCatalog.latest(modelscope, full = true, abis = listOf("arm64-v8a"))!!
        assertEquals("1.15.47", latest.version)
        assertEquals("CyIME-1.15.47-full-arm64-v8a.apk", latest.asset!!.name)
        assertEquals("bb22", latest.asset!!.sha256)
        assertEquals(AppUpdateCatalog.MODELSCOPE_PAGE, latest.pageUrl)
        assertEquals(
            "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/app/1.15.47/CyIME-1.15.47-full-arm64-v8a.apk",
            latest.asset!!.url,
        )
    }

    @Test
    fun eachSourceFallsBackToTheOther() {
        val fromModelScope = AppUpdateCatalog.withMirror(AppUpdateCatalog.latest(modelscope, false, listOf("arm64-v8a"))!!, true)
        assertEquals(listOf("https://github.com/Cyletix/CyIME/releases/download/1.15.47/CyIME-1.15.47-arm64-v8a.apk"),
            fromModelScope.asset!!.mirrors)
        val fromGitHub = AppUpdateCatalog.withMirror(AppUpdateCatalog.latest(releases, false, listOf("arm64-v8a"))!!, false)
        assertEquals(listOf(AppUpdateCatalog.modelscopeAssetUrl("1.14.156", "CyIME-1.14.156-arm64-v8a.apk")),
            fromGitHub.asset!!.mirrors)
        val pageOnly = AppUpdateCatalog.latest(releases, false, listOf("x86_64"))!!
        assertEquals(pageOnly, AppUpdateCatalog.withMirror(pageOnly, false))
    }

    @Test
    fun domesticUsersOpenModelScope() {
        assertEquals(AppUpdateCatalog.MODELSCOPE_PAGE, AppUpdateCatalog.releasesPage(domestic = true))
        assertEquals(AppUpdateCatalog.RELEASES_PAGE, AppUpdateCatalog.releasesPage(domestic = false))
    }

    @Test
    fun fdroidInstallsAreStoreManaged() {
        assertTrue(AppUpdateCatalog.isStoreInstaller("org.fdroid.fdroid"))
        assertTrue(AppUpdateCatalog.isStoreInstaller("org.fdroid.basic"))
        assertTrue(AppUpdateCatalog.isStoreInstaller("com.looker.droidify"))
        // GitHub 包手动装、adb 装（没有安装来源）、谷歌商店：照旧在应用里检查更新。
        assertFalse(AppUpdateCatalog.isStoreInstaller(null))
        assertFalse(AppUpdateCatalog.isStoreInstaller("com.google.android.packageinstaller"))
        assertFalse(AppUpdateCatalog.isStoreInstaller("com.android.shell"))
    }
}
