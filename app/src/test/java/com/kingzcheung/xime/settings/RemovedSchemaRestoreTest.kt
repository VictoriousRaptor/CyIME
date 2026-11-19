package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.service.selectableWithStored
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemovedSchemaRestoreTest {
    @Test fun uninstallSelectedPackageKeepsMenuSelectionAndNextStartupInSync() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val rime = SchemaManager.getRimeDir(app).apply { mkdirs() }
        val staged = File(app.cacheDir, "imported-scheme").apply { mkdirs() }
        val imported = "issue340_pinyin"
        fun schema(dir: File, id: String) = File(dir, "$id.schema.yaml").apply {
            writeText("schema:\n  schema_id: $id\n  name: $id\n")
        }
        schema(rime, "rime_ice")
        val source = schema(staged, imported)
        SchemaManifestManager.installStagedFiles(app, "feedback340", "test", "1", true, staged, listOf(source.name))
        val build = SchemaManager.getBuildDir(app).apply { mkdirs() }
        for (id in listOf("rime_ice", imported)) File(build, "$id.schema.yaml").writeText("# compiled")
        SchemaManager.setEnabledSchemas(app, listOf(imported, "rime_ice"))
        SettingsPreferences.setCurrentSchema(app, imported)
        InputModes.selectProfile(app, imported, InputLanguage.CHINESE)
        val result = SchemaManager.uninstallPackage(app, "feedback340")
        assertTrue(result.message, result.success)
        // 卸载方案包时一并删掉它的编译产物（9ba50323）；内置方案的不动。
        assertFalse(File(build, "$imported.schema.yaml").exists())
        assertTrue(File(build, "rime_ice.schema.yaml").isFile)
        assertEquals("rime_ice", SettingsPreferences.getCurrentSchema(app))
        assertEquals("rime_ice", InputModes.selectedProfiles(app)[InputLanguage.CHINESE])
        val menu = SchemaManager.keyboardMenuSchemas(app).map { it.schemaId }
        assertTrue("rime_ice" in menu)
        assertFalse(imported in menu)
        val restored = selectableWithStored(imported, listOf(imported, "rime_ice"),
            SchemaManager.getEnabledSchemas(app), { false }, { SchemaManager.isSchemaCompiled(app, it) })
        assertEquals(listOf("rime_ice"), restored)
    }
}
