package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.CoroutineTestRule
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChineseSchemeDefaultsMigrationTest {
    @get:Rule val dispatchers = CoroutineTestRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val rime get() = SchemaManager.getRimeDir(app)
    private val custom get() = rime.resolve("default.custom.yaml")
    private val flypy = "double_pinyin_flypy"
    private val merged = "pinyin_14jian"
    private val flypy14 = "double_pinyin_flypy_14jian"

    @Before fun installSchemas() {
        rime.mkdirs()
        (listOf("rime_ice", "t9_pinyin", merged, flypy14, "wubi86", "my_scheme") +
            CyimeInputDefaults.doublePinyin).forEach { rime.resolve("$it.schema.yaml").writeText("schema: {schema_id: $it}") }
    }

    @Test fun missingListOffersFlypyOnBothLayouts() = assertFreshChoices()

    @Test fun enablingAnAlreadyDeployedSchemaNotifiesTheOpenKeyboardAndKeepsCurrentScheme() = runBlocking {
        val initial = SchemaManager.getEnabledSchemas(app) - "my_scheme"
        SchemaManager.setEnabledSchemas(app, initial)
        SettingsPreferences.setCurrentSchema(app, "rime_ice")
        SchemaManager.getBuildDir(app).apply { mkdirs() }.resolve("my_scheme.schema.yaml").writeText("schema: {}")
        val change = async(start = CoroutineStart.UNDISPATCHED) { SchemaManager.enabledSchemasChanged.first() }
        SchemaManager.setEnabledSchemas(app, initial + "my_scheme")
        withTimeout(2_000) { change.await() }
        assertTrue("my_scheme" in SchemaManager.getEnabledSchemas(app))
        assertTrue(SchemaManager.isSchemaCompiled(app, "my_scheme"))
        assertEquals("rime_ice", SettingsPreferences.getCurrentSchema(app))
    }

    @Test fun bundledTemplateOffersFlypyOnBothLayouts() {
        custom.writeText(app.assets.open("default.custom.yaml").bufferedReader().use { it.readText() })
        assertFreshChoices()
    }

    @Test fun languageSettingsCanSelectACompanionWithoutGivingItAnIndependentSwitch() = runBlocking {
        val model = SchemaSettingsViewModel(app)
        val loaded = withTimeout(10_000) { model.uiState.first { it.allSchemas.isNotEmpty() } }
        val installed = loaded.allSchemas.map { it.schemaId }.toSet()
        val profiles = InputModes.ordered(app, loaded.allSchemas.filter { it.schemaId in loaded.enabledSchemas }.map { it.toSchemaInfo() })
        assertTrue(flypy14 in installed)
        assertFalse(CyimeInputDefaults.hasIndependentToggle(flypy14, installed))
        val result = InputProfileSelection.switchLayout(profiles, profiles.first { it.schemaId == flypy }, InputLayout.MERGED14.id)
        assertEquals(flypy14, result?.entry?.schemaId)
    }

    private fun assertFreshChoices() {
        val ids = SchemaManager.getEnabledSchemas(app)
        // 新装：小鹤和 14 键默认打开；其余 5 种双拼留给「按设备推荐配置」里问了再开（10-31 走查，不先全列进方案栏）。
        assertTrue(ids.toString(), ids.containsAll(listOf(flypy, merged, flypy14)))
        assertTrue(ids.toString(), ids.none { it in CyimeInputDefaults.doublePinyin && it != flypy })
        val choices = ids.map { SchemaInfo(it, it, "", "", "") }
        val base = choices.first { it.schemaId == flypy }
        assertEquals(flypy14, InputProfileSelection.switchLayout(choices, base, InputLayout.MERGED14.id)?.entry?.schemaId)
    }

    @Test fun upgradeKeepsStandaloneFlypy14WubiCustomOrderAndExplicitDisables() {
        val saved = listOf("my_scheme", "wubi86", flypy14)
        custom.writeText("patch:\n  menu/page_size: 11\n  schema_list:\n" +
            saved.joinToString("\n") { "    - schema: $it" } + "\n")
        SettingsPreferences.setCurrentSchema(app, flypy14)
        val upgraded = SchemaManager.getEnabledSchemas(app)
        assertEquals(saved.take(2), upgraded.take(2))
        assertTrue(upgraded.containsAll(listOf(flypy, merged, flypy14)))
        assertEquals(flypy14, SettingsPreferences.getCurrentSchema(app))
        assertEquals(upgraded, SchemaManager.getEnabledSchemas(app))
        assertTrue(custom.readText().contains("menu/page_size: 11"))

        // The settings screen still sends the previous implied variant when a parent is switched off.
        SchemaManager.setEnabledSchemas(app, upgraded - flypy)
        val after = SchemaManager.getEnabledSchemas(app)
        assertFalse(flypy in after)
        assertFalse(flypy14 in after)
        assertTrue(merged in after)
        assertEquals(after, SchemaManager.getEnabledSchemas(app))
    }
}
