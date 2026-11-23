package com.kingzcheung.xime.ui.menubar

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SchemaPicking
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户 11-22：设置里有仓颉、注音，键盘的方案面板里没有。键盘面板方案一行必须正是设置页那一行
 * （[SchemaPicking.schemeRow]），不能再按布局另筛一遍。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
class SchemePanelMatchesSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val schemas = listOf("rime_ice", "t9_pinyin", "double_pinyin_flypy", "wubi86", "cyime_cangjie", "cyime_zhuyin", "stroke")
        .map { SchemaInfo(it, it, "", "", "") }

    @Test fun panelListsEverySchemeTheSettingsRowLists() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        val current = schemas.first { it.schemaId == "rime_ice" }
        val expected = SchemaPicking.schemeRow(InputModes.ordered(app, schemas), current).map { it.schemaId }
        assertTrue(expected.toString(), expected.containsAll(listOf("wubi86", "cyime_cangjie", "cyime_zhuyin", "stroke")))
        rule.setContent {
            MaterialTheme {
                SchemaListView(schemas, "rime_ice", Color.White, Color.Blue, Color.Black, Color(0xFFEEEEEE),
                    onSelectSchema = {}, modifier = Modifier.requiredSize(400.dp, 280.dp))
            }
        }
        for (id in expected) {
            rule.onNodeWithTag("profile-scheme-list:list", useUnmergedTree = true).performScrollToKey(id)
            rule.onNodeWithTag("panel-scheme:$id", useUnmergedTree = true).assertExists()
        }
    }
}
