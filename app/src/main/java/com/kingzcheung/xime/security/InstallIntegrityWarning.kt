package com.kingzcheung.xime.security

import androidx.compose.material3.AlertDialog
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设置每次打开时，装的不是作者签名的包就弹这一框；点哪个按钮都只关这一次。 */
@Composable
fun InstallIntegrityWarning() {
    val context = LocalContext.current.applicationContext
    val result by produceState<InstallIntegrity.Result?>(null) {
        value = withContext(Dispatchers.IO) { InstallIntegrity.check(context) }
    }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (result != InstallIntegrity.Result.FOREIGN || dismissed) return
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { dismissed = true },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("非官方安装包") },
        text = { Text("这个 CyIME 不是作者签名发布的，可能被人改过。输入法能看到你打的每个字，请卸载后从官方下载页重新安装。") },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                runCatching { uriHandler.openUri(InstallIntegrity.DOWNLOAD_URL) }
            }) { Text("去下载") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("知道了") }
        },
    )
}
