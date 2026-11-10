package com.kingzcheung.xime.platform

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * 系统版本带来的每一处差异，从哪个 API 起有、以下怎么办，都登记在这张表里。
 *
 * 规则（`PlatformApiGuardTest` 会查）：
 * - 只有 [AndroidPlatform] 读 `Build.VERSION.SDK_INT`，进程启动时读一次；
 * - 别处按能力名判断（`AndroidPlatform.windowBlur`），不再写 `SDK_INT >= 31`；
 * - 新用到一个 Android 9（API 28，minSdk）以后才有的系统接口，先在这里加一项，再在调用处用它。
 *
 * [minSdk] 改了，[AndroidPlatform] 里同名属性的 `@ChecksSdkIntAtLeast(api = …)` 要一起改（测试会对）。
 */
enum class PlatformFeature(val minSdk: Int, val label: String) {
    // —— 键盘窗口与底边 ——
    GESTURE_INSETS(Build.VERSION_CODES.Q, "手势区/可点区 inset，固定键盘可贴底"),
    GESTURE_EXCLUSION(Build.VERSION_CODES.Q, "调整键盘时屏蔽系统边缘手势"),
    BAR_CONTRAST(Build.VERSION_CODES.Q, "可关系统栏对比度遮罩"),
    WINDOW_INSET_TYPES(Build.VERSION_CODES.R, "按类型读导航栏/状态栏/手势条，窗口底边适配"),
    WINDOW_METRICS(Build.VERSION_CODES.R, "WindowMetrics 与 Context.display"),
    NAV_BAR_COLOR_IGNORED(Build.VERSION_CODES.VANILLA_ICE_CREAM, "系统不画导航栏颜色，只把键盘底色报给系统"),

    // —— 编辑框 ——
    INITIAL_SURROUNDING_TEXT(Build.VERSION_CODES.R, "EditorInfo 自带光标前后文字"),
    INLINE_SUGGESTIONS(Build.VERSION_CODES.R, "自动填充建议显示在候选栏"),
    SURROUNDING_TEXT(Build.VERSION_CODES.S, "一次读光标前后文字和选区"),
    CURSOR_ANCHOR_FILTER(Build.VERSION_CODES.TIRAMISU, "按需订阅光标信息，读编辑框边界"),
    CURSOR_ANCHOR_LINES(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "读可见行边界和文字字号"),
    VIEW_GLOBAL_MATRIX(Build.VERSION_CODES.Q, "输入法内部编辑器的屏幕变换矩阵"),

    // —— 外观 ——
    WINDOW_BLUR(Build.VERSION_CODES.S, "跨窗口模糊（玻璃背景）"),
    RENDER_EFFECT(Build.VERSION_CODES.S, "RenderEffect（玻璃文字托底）"),
    STRETCH_OVERSCROLL(Build.VERSION_CODES.S, "列表拉伸回弹（键盘里统一关掉，见 NoKeyboardOverscroll）"),
    DYNAMIC_COLOR(Build.VERSION_CODES.S, "系统取色主题"),
    RUNTIME_SHADER(Build.VERSION_CODES.TIRAMISU, "AGSL 着色器（液态玻璃折射）"),

    // —— 触感 ——
    HAPTIC_QUERY(Build.VERSION_CODES.R, "能查马达支持的原语/预设效果"),
    VIBRATOR_MANAGER(Build.VERSION_CODES.S, "VibratorManager 取默认马达"),
    TOUCH_VIBRATION_USAGE(Build.VERSION_CODES.TIRAMISU, "按触摸用途振动，跟随系统触摸强度"),

    // —— 窗口状态 ——
    WINDOW_VISIBILITY_LISTENER(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "窗口可见性回调（以下轮询）"),

    // —— 文件与权限 ——
    MEDIASTORE_DOWNLOADS(Build.VERSION_CODES.Q, "MediaStore 写公共下载/图片目录"),
    READ_MEDIA_IMAGES(Build.VERSION_CODES.TIRAMISU, "图片权限 READ_MEDIA_IMAGES"),
    PARTIAL_PHOTO_ACCESS(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "只授权部分照片"),

    // —— 系统 ——
    THERMAL_STATUS(Build.VERSION_CODES.Q, "读发热等级"),
    AUDIO_RECORDING_CALLBACK(Build.VERSION_CODES.Q, "麦克风被别的应用占用时有回调"),
    CALL_AUDIO_ROUTE(Build.VERSION_CODES.S, "通话模式回调、通话音频设备"),
    SOC_INFO(Build.VERSION_CODES.S, "读芯片厂商和型号"),
    PER_APP_LANGUAGE(Build.VERSION_CODES.TIRAMISU, "应用内单独选界面语言"),
    BATCH_COMPONENT_STATE(Build.VERSION_CODES.TIRAMISU, "一次切换多个启动图标"),
    TILE_PENDING_INTENT(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "快捷开关用 PendingIntent 打开页面"),

    // —— 调试测量（只在调试工具里用） ——
    ASYNC_TRACE(Build.VERSION_CODES.Q, "异步 Trace 段"),
    GPU_FRAME_METRIC(Build.VERSION_CODES.S, "帧耗时里的 GPU 时间"),
    MOTION_EVENT_NANOS(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "触摸事件纳秒时间戳");

    companion object {
        /** 一行写清某个 API 级别走哪些路：有的列名字，没有的列名字和从哪个 API 起有。 */
        fun summary(sdk: Int): String {
            val (on, off) = entries.partition { sdk >= it.minSdk }
            return buildString {
                append("API ").append(sdk)
                append(" | 有：").append(on.joinToString("、") { it.name.lowercase() }.ifEmpty { "无" })
                append(" | 没有：").append(off.joinToString("、") { "${it.name.lowercase()}(${it.minSdk})" }.ifEmpty { "无" })
            }
        }
    }
}

/** 本机的 Android 版本和由它决定的能力；进程里只算一次。 */
object AndroidPlatform {
    /** API 级别（Android 9 = 28）。只有这里读 `Build.VERSION.SDK_INT`。 */
    @JvmField val sdk: Int = Build.VERSION.SDK_INT

    /** 系统给的版本名（如 "14"）；读不到时为空。 */
    val release: String = Build.VERSION.RELEASE.orEmpty()

    fun has(feature: PlatformFeature): Boolean = sdk >= feature.minSdk

    /** 「Android 12 (API 31)」，关于页、日志里统一这样写。 */
    val versionLabel: String get() = "Android $release (API $sdk)"

    /** 这台设备走哪些路（[PlatformFeature.summary]），键盘启动时写进日志。 */
    fun summary(): String = PlatformFeature.summary(sdk)

    /**
     * 厂商原名（如 "samsung"）。只有这里读 `Build.MANUFACTURER`。
     * 每次现读（只是读个静态字段）：测试里按用例换厂商（ShadowBuild.setManufacturer）要能生效。
     */
    val manufacturer: String get() = Build.MANUFACTURER.orEmpty()

    /** 厂商归类，各处的厂商适配按它判断。只有这里读 `Build.BRAND`。每次现算，理由同上。 */
    val vendor: DeviceVendor get() = DeviceVendor.of(manufacturer, Build.BRAND)

    /** 「samsung SM-X710」，关于页、日志里统一这样写。 */
    val deviceLabel: String get() = "$manufacturer ${Build.MODEL}"

    /**
     * 每个日志文件开头写的两行：版本、机型、厂商归类、系统版本和系统编译号，以及本机走哪些路。
     * 公开版也写（只有设备信息，没有用户输入的文字），用户分享日志时一眼看出是什么手机、什么系统。
     */
    fun diagnosticHeader(appVersion: String): String =
        "Device: CyIME $appVersion | $deviceLabel | 厂商：${vendor.name.lowercase()} | $versionLabel | " +
            "系统编译号：${Build.DISPLAY.orEmpty()}\nPlatform: ${summary()}"

    // —— 键盘窗口与底边 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val gestureInsets: Boolean = has(PlatformFeature.GESTURE_INSETS)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val gestureExclusion: Boolean = has(PlatformFeature.GESTURE_EXCLUSION)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val barContrast: Boolean = has(PlatformFeature.BAR_CONTRAST)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val windowInsetTypes: Boolean = has(PlatformFeature.WINDOW_INSET_TYPES)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val windowMetrics: Boolean = has(PlatformFeature.WINDOW_METRICS)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    val navBarColorIgnored: Boolean = has(PlatformFeature.NAV_BAR_COLOR_IGNORED)

    // —— 编辑框 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val initialSurroundingText: Boolean = has(PlatformFeature.INITIAL_SURROUNDING_TEXT)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val inlineSuggestions: Boolean = has(PlatformFeature.INLINE_SUGGESTIONS)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val surroundingText: Boolean = has(PlatformFeature.SURROUNDING_TEXT)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val cursorAnchorFilter: Boolean = has(PlatformFeature.CURSOR_ANCHOR_FILTER)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val cursorAnchorLines: Boolean = has(PlatformFeature.CURSOR_ANCHOR_LINES)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val viewGlobalMatrix: Boolean = has(PlatformFeature.VIEW_GLOBAL_MATRIX)

    // —— 外观 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val windowBlur: Boolean = has(PlatformFeature.WINDOW_BLUR)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val renderEffect: Boolean = has(PlatformFeature.RENDER_EFFECT)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val dynamicColor: Boolean = has(PlatformFeature.DYNAMIC_COLOR)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val runtimeShader: Boolean = has(PlatformFeature.RUNTIME_SHADER)

    // —— 触感 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val hapticQuery: Boolean = has(PlatformFeature.HAPTIC_QUERY)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val vibratorManager: Boolean = has(PlatformFeature.VIBRATOR_MANAGER)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val touchVibrationUsage: Boolean = has(PlatformFeature.TOUCH_VIBRATION_USAGE)

    // —— 窗口状态 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val windowVisibilityListener: Boolean = has(PlatformFeature.WINDOW_VISIBILITY_LISTENER)

    // —— 文件与权限 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val mediaStoreDownloads: Boolean = has(PlatformFeature.MEDIASTORE_DOWNLOADS)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val readMediaImages: Boolean = has(PlatformFeature.READ_MEDIA_IMAGES)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val partialPhotoAccess: Boolean = has(PlatformFeature.PARTIAL_PHOTO_ACCESS)

    // —— 系统 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val thermalStatus: Boolean = has(PlatformFeature.THERMAL_STATUS)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val audioRecordingCallback: Boolean = has(PlatformFeature.AUDIO_RECORDING_CALLBACK)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val callAudioRoute: Boolean = has(PlatformFeature.CALL_AUDIO_ROUTE)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val socInfo: Boolean = has(PlatformFeature.SOC_INFO)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val perAppLanguage: Boolean = has(PlatformFeature.PER_APP_LANGUAGE)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val batchComponentState: Boolean = has(PlatformFeature.BATCH_COMPONENT_STATE)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val tilePendingIntent: Boolean = has(PlatformFeature.TILE_PENDING_INTENT)

    // —— 调试测量 ——
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val asyncTrace: Boolean = has(PlatformFeature.ASYNC_TRACE)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val gpuFrameMetric: Boolean = has(PlatformFeature.GPU_FRAME_METRIC)
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val motionEventNanos: Boolean = has(PlatformFeature.MOTION_EVENT_NANOS)
}
