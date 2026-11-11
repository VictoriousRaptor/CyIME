package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem

/**
 * 键盘菜单长按打开的设置项，写设置搜索索引里的标题：跳转、定位和高亮与搜索点开同一条路。
 * 纯动作（编辑）、临时状态（全角/半角）、设置入口本身，
 * 以及表情、符号面板没有可设的东西，不在这里，长按不响应。
 */
internal val MenuSettingsTitles: Map<ToolbarButton, String> = mapOf(
    ToolbarButton.SCHEMA to "语言与方案",
    ToolbarButton.CLIPBOARD to "剪贴板与同步",
    ToolbarButton.OCR to "已安装的模型",
    ToolbarButton.FLOAT to "键盘尺寸与位置",
    ToolbarButton.FLOATING to "键盘尺寸与位置",
    ToolbarButton.ARRANGE to "工具栏与菜单排列",
    ToolbarButton.HANDWRITING_LOOKUP to "手写",
    ToolbarButton.VOICE to "语音转文本",
    ToolbarButton.CAPTION to "字幕",
    ToolbarButton.SOUND to "按键效果",
    ToolbarButton.GLASS to "玻璃效果",
    ToolbarButton.INCOGNITO to "无痕模式",
    ToolbarButton.TRADITIONAL to "繁体输出",
    ToolbarButton.GLIDE to "滑行输入",
    ToolbarButton.T9_FLICK to "拂动选字母",
    ToolbarButton.FOURTEEN_SWIPE to "左右滑动选字母",
    ToolbarButton.KANA_HINTS to "假名方向提示",
    ToolbarButton.FLICK_DISC to "拂动时显示方向圆盘",
)

/** [item] 长按要去的设置；插件按钮去这个插件的那一页（[pluginPageRoute]）。没有对应设置时为 null。 */
internal fun menuSettingsEntry(item: ToolbarButtonItem): SettingsSearchEntry? = when (item) {
    is ToolbarButtonItem.Builtin ->
        MenuSettingsTitles[item.button]?.let { label -> SettingsSearchIndex.firstOrNull { it.title == label } }
    is ToolbarButtonItem.Plugin -> SettingsSearchEntry(item.label, "", pluginPageRoute(item.pluginId), anchor = null)
}
