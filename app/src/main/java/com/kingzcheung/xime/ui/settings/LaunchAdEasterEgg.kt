package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.delay

/**
 * 启动广告彩蛋（用户 11-15）：「关于 CyIME」里的开关，默认关。开着时每次打开设置先放一页假广告（[LaunchAdPoster]），
 * [SECONDS] 秒后自己关，随时能点「跳过」或按返回。只在设置 App 里，键盘上没有；不联网，不读写开关以外的任何东西。
 */
object LaunchAdEgg {
    const val SECONDS = 5

    /** 在「关于」里刚把开关打开：马上放一遍，让人看到开的是什么。只在这个进程里数。 */
    var previewRequest by mutableIntStateOf(0)
        private set

    fun preview() { previewRequest++ }

    /**
     * 这次打开设置放不放：老人包不放；从别的应用分享文件进来、从键盘跳到某个具体页面或插件页时不放，不挡正事。
     * 桌面图标（没带页面）和键盘菜单的「设置」（带的是首页）才算「打开设置」。
     */
    fun allowedAtLaunch(elder: Boolean, action: String?, openFragment: String?, openPluginId: String?): Boolean =
        !elder && action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_SEND_MULTIPLE &&
            openPluginId == null && (openFragment == null || openFragment == SettingsRoutes.Main)
}

/**
 * 放在设置最上面一层。[launchAllowed] 见 [LaunchAdEgg.allowedAtLaunch]；[blocked] 时（首次设置、推荐配置还在走）不放。
 * 开关只在打开设置的那一刻读一次：放完这一遍，这次打开期间不再放（转屏也不重来）。
 */
@Composable
fun LaunchAdEasterEgg(launchAllowed: Boolean, blocked: Boolean) {
    val context = LocalContext.current
    val enabledAtLaunch = remember { launchAllowed && SettingsPreferences.isLaunchAdEggEnabled(context) }
    var launchDone by rememberSaveable { mutableStateOf(false) }
    var previewDone by remember { mutableIntStateOf(LaunchAdEgg.previewRequest) }
    val preview = LaunchAdEgg.previewRequest != previewDone
    if (blocked || !(preview || (enabledAtLaunch && !launchDone))) return
    val close = {
        launchDone = true
        previewDone = LaunchAdEgg.previewRequest
    }
    var secondsLeft by remember { mutableIntStateOf(LaunchAdEgg.SECONDS) }
    var poked by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        close()
    }
    // 每 600 ms 换一次颜色：不到每秒一下。
    val blink by produceState(false) {
        while (true) {
            delay(600L)
            value = !value
        }
    }
    BackHandler(onBack = close)
    // 设置是全屏铺到状态栏、导航栏底下的：底色铺满，版面和「跳过」让开状态栏、刘海和导航栏。
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF000))) {
        LaunchAdPoster(secondsLeft, blink, poked, onSkip = close, onPoke = { poked++ },
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
    }
}
