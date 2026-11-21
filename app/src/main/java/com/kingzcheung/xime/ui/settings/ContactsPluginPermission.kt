package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.contacts.ContactSuggestions

/**
 * 通讯录插件的开关：打开时才要通讯录权限（不打开插件就不要）；不给权限就不打开。
 * 返回的函数：`gate(打开?, 真正去拨开关)`。别的插件、关开关、已有权限时直接拨。
 */
@Composable
internal fun rememberContactsPermissionGate(pluginId: String): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember(pluginId) { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val proceed = pending
        pending = null
        if (granted) proceed?.invoke()
        else com.kingzcheung.xime.ui.i18n.UiToast.makeText(context, CONTACTS_DENIED, Toast.LENGTH_LONG).show()
    }
    return { turnOn, proceed ->
        if (!turnOn || ContactSuggestions.hasPermission(context) || !ContactSuggestions.isContactsPlugin(pluginId)) proceed()
        else {
            pending = proceed
            launcher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

internal const val CONTACTS_DENIED = "需要通讯录权限，请在系统设置里允许"
