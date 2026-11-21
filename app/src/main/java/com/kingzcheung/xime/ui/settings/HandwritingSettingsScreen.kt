package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.twotone.ModelTraining
import androidx.compose.material.icons.twotone.Inventory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.VolunteerActivism
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 手写的设置都放在这一页（用户 10-21 要求和语音分开）：停顿识别、公式标记、手写模型。
 * 「改进手写」只留一个写字入口（用户 11-05 合并）：公开包是「贡献手写样本」；「装到我的设备」和调试包是
 * 「手写训练」，贡献题和原「手写数据采集」（自己的文字）都在它的题库菜单里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingSettingsContent(
    onBack: () -> Unit,
    onOpenModels: () -> Unit = {},
    onOpenTraining: () -> Unit = {},
    onOpenContribute: () -> Unit = {},
    devTools: Boolean = com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG,
) {
    Scaffold(modifier = Modifier.testTag("handwriting-settings"), containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = { Text("手写") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HandwritingSettings()
            SettingsSection(title = "手写模型", content = {
                SettingsItem(Icons.TwoTone.Inventory, "已安装的手写模型",
                    "切换或删除手写模型", onClick = onOpenModels, showArrow = true)
            })
            SettingsSection(title = "改进手写", content = {
                if (devTools) {
                    SettingsItem(Icons.TwoTone.ModelTraining, "手写训练",
                        "按题写字，导出训练或贡献给作者", onClick = onOpenTraining, showArrow = true)
                } else {
                    SettingsItem(Icons.TwoTone.VolunteerActivism, "贡献手写样本",
                        "按题写字，分享或上传给作者训练手写模型", onClick = onOpenContribute, showArrow = true)
                }
            })
        }
    }
}
