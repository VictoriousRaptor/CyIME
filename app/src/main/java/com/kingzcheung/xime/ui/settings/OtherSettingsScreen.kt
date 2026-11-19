package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Backup
import androidx.compose.material.icons.twotone.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.R

/**
 * 首页「系统 › 其他设置」：用户 11-02 说系统一组「塞得东西太多了」，把不常点的备份与恢复、性能与省电、
 * 界面语言收到这里；剪贴板与同步、扩展、关于仍在首页。各页本身和搜索跳转不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsContent(onBack: () -> Unit, onNavigateToRoute: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.item_other_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = stringResource(R.string.section_system), searchKey = "其他设置", content = {
                SettingsItem(
                    icon = Icons.TwoTone.Backup,
                    title = stringResource(R.string.item_backup_title), searchKey = "备份与恢复",
                    subtitle = stringResource(R.string.item_backup_summary),
                    onClick = { onNavigateToRoute(SettingsRoutes.Backup) },
                    showArrow = true
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SettingsItem(
                    icon = Icons.TwoTone.Speed,
                    title = stringResource(R.string.item_performance_title), searchKey = "性能与省电",
                    subtitle = stringResource(R.string.item_performance_summary),
                    onClick = { onNavigateToRoute(SettingsRoutes.Performance) },
                    showArrow = true
                )
                // 界面语言：默认跟随系统；选英文时设置各页按 assets/i18n/en.tsv 显示英文。
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                AppLanguageItem()
            })
        }
    }
}
