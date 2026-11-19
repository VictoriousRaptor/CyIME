package com.kingzcheung.xime.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kingzcheung.xime.clipboard.sync.DeviceHost
import com.kingzcheung.xime.clipboard.sync.HostedDevice
import kotlinx.coroutines.delay

/** Lets another CyIME phone or tablet scan this device and sync clipboards without a computer. */
@Composable
fun DeviceHostPanel() {
    val enabled by DeviceHost.enabled.collectAsState()
    val devices by DeviceHost.devices.collectAsState()
    val offer by DeviceHost.offer.collectAsState()
    val status by DeviceHost.status.collectAsState()
    DisposableEffect(Unit) { onDispose { DeviceHost.closeOffer() } }
    DeviceHostPanelContent(enabled, devices, offer, status, DeviceHost.deviceName(),
        onEnabled = DeviceHost::setEnabled, onShowCode = { DeviceHost.openOffer() },
        onCloseCode = DeviceHost::closeOffer, onRemove = DeviceHost::remove)
}

@Composable
internal fun DeviceHostPanelContent(
    enabled: Boolean, devices: List<HostedDevice>, offer: String?, status: String, name: String,
    onEnabled: (Boolean) -> Unit, onShowCode: () -> Unit, onCloseCode: () -> Unit, onRemove: (HostedDevice) -> Unit,
) {
    SettingsSection(title = "让其他手机或平板连接本机", content = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("允许其他设备连接")
                    Text("不用电脑，手机和平板直接互相同步复制的文字。只在同一 Wi-Fi 下、扫码配对过的设备才能读写；密码等敏感内容不会发出。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("device-host-switch"))
            }
            if (enabled) {
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onShowCode, modifier = Modifier.fillMaxWidth().testTag("device-host-show-code")) {
                    Text("显示连接二维码")
                }
                Text("在另一部手机或平板的剪贴板同步里点“扫码添加另一台设备”，扫描这里的二维码。",
                    style = MaterialTheme.typography.bodyMedium)
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onRemove(device) }) { Text("移除") }
                    }
                }
            }
        }
    })
    if (offer != null) {
        var remaining by remember(offer) { mutableIntStateOf(120) }
        LaunchedEffect(offer) {
            while (remaining > 0) { delay(1000); remaining-- }
            onCloseCode()
        }
        val bitmap = remember(offer) { qrBitmap(offer) }
        AlertDialog(onDismissRequest = onCloseCode, confirmButton = { TextButton(onClick = onCloseCode) { Text("关闭") } },
            title = { Text("扫码连接 $name") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(bitmap, "连接二维码", Modifier.size(240.dp).background(Color.White).testTag("device-host-qr"),
                        filterQuality = FilterQuality.None)
                    Text("$remaining 秒内有效，只能使用一次", style = MaterialTheme.typography.bodyMedium)
                }
            })
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height)
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bitmap.asImageBitmap()
}
