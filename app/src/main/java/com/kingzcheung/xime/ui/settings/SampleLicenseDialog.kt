package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.handwriting.training.HandwritingContribution

@Composable
internal fun SampleLicenseDialog(scope: String, onDismiss: () -> Unit, onAgree: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("贡献样本协议") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(scope)
            Spacer(Modifier.height(12.dp))
            Text(HandwritingContribution.LICENSE_TEXT)
            TextButton(onClick = { uriHandler.openUri(HandwritingContribution.LICENSE_URL) }) {
                Text("阅读 CC0 1.0 条款")
            }
        }
    }, confirmButton = { TextButton(onClick = onAgree) { Text("同意协议") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不贡献") } })
}
