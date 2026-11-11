package com.kingzcheung.xime.plugin.core.model

import com.kingzcheung.xime.plugin.core.api.ToolResult

/**
 * 插件能力声明（manifest.capabilities）：宿主消费能力的**唯一来源**。
 *
 * 静态能力（搜索支持、布局、结果模式、录音格式等）一律由元数据声明，
 * 插件 Lua 侧只提供运行时数据与事件处理，不再重复声明。
 */
data class PluginCapabilities(
    val emoji: EmojiCapabilities? = null,
    val speech: SpeechCapabilities? = null,
    val tool: ToolCapabilities? = null,
    @kotlinx.serialization.SerialName("clipboard_sync")
    val clipboardSync: ClipboardSyncCapabilities? = null,
    @kotlinx.serialization.SerialName("backup")
    val backup: BackupCapabilities? = null,
    /** 下行事件订阅声明（如 "input_changed"）：未声明的事件宿主不投递，通道也不建立。 */
    val events: List<String> = emptyList(),
    /** 候选词变换能力（manifest 声明 `candidate_transform: true`）：rime 返回候选后、
     *  候选栏渲染前，宿主同步调用插件 transformCandidates 修改候选；首个接入输入
     *  主流程（hotPath）的能力，硬超时 15ms + 敏感输入短路 + 连续超时熔断。 */
    @kotlinx.serialization.SerialName("candidate_transform")
    val candidateTransform: Boolean = false,
    /** 快捷发送只读能力（manifest 声明 `quick_send_read: true`）：声明后宿主注入
     *  `host.quickSend`（list()），并允许订阅 `quick_send_changed` 事件。 */
    @kotlinx.serialization.SerialName("quick_send_read")
    val quickSendRead: Boolean = false,
    /** 剪贴板只读能力（manifest 声明 `clipboard_read: true`）：声明后宿主注入
     *  `host.clipboard`（getText()）。 */
    @kotlinx.serialization.SerialName("clipboard_read")
    val clipboardRead: Boolean = false,
    /** 通讯录候选（manifest 声明 `contacts: true`）：插件启用且授权后，宿主自己读通讯录、
     *  在候选里给人名和号码。通讯录内容只在宿主内存里，不交给插件脚本，不学习、不备份、不同步。 */
    val contacts: Boolean = false,
    /** 字幕（manifest 声明 `caption: true`）：字幕页面、识别、下拉快捷开关和桌面图标都在宿主里，
     *  插件装上并启用才放出这些入口，停用或卸载就收起。脚本拿不到录音和字幕内容。 */
    val caption: Boolean = false,
    /** 离线翻译（manifest 声明 `offline_translate: true`）：声明后宿主注入 `host.translate`
     *  （查状态、下载模型、在手机上翻译）。模型在「扩展 › 模型」，装了才能用。 */
    @kotlinx.serialization.SerialName("offline_translate")
    val offlineTranslate: Boolean = false,
    /** 日语（manifest 声明 `japanese: true`）：日语方案、键盘和转换都在宿主里，插件装上并启用才打开日语、
     *  下载日语词库和转换引擎；停用就关掉日语，卸载时删掉下载的词库（学习记录留着）。 */
    val japanese: Boolean = false,
) {
    companion object {
        val EMPTY = PluginCapabilities()
    }

    /** emoji 表情能力声明。 */
    data class EmojiCapabilities(
        val supportsSearch: Boolean = false,
        /** 网格列数（缺省按宿主默认）。 */
        val columns: Int? = null,
        /** 单行高度 dp。 */
        val itemHeightDp: Int? = null,
    )

    /** speech 语音识别能力声明。 */
    data class SpeechCapabilities(
        val inputMode: String = "streaming",
        val supportsPartialResults: Boolean = true,
        val requiresNetwork: Boolean = true,
        /** 接口返回的文字自带标点（manifest `keepsPunctuation: true`）：跟随「自动加标点」和逐项开关，不再空格化。 */
        val keepsPunctuation: Boolean = false,
        /** manifest `languages: any`：接口自己认语言，宿主把录音语言（zh/en/ja）交给 start；未声明 = 只用于中文。 */
        val anyLanguage: Boolean = false,
    ) {
        /** inputMode: batch：录完整段上传。失败时宿主用本地模型重识别这段录音，收尾等待放宽到 30 秒。 */
        val isBatch: Boolean get() = inputMode.equals("batch", ignoreCase = true)
    }

    /** tool 工具面板能力声明。 */
    data class ToolCapabilities(
        /** 结果显示方式：DIRECT 直接上屏 / SELECT 全屏候选页面；null 宿主按结果数量兜底。 */
        val display: ToolResult? = null,
        /**
         * 面板上下文（manifest `context: field`）：没选字时取整个输入框并在点结果时替换整框，
         * 不退到剪贴板（AI 改写）。未声明 = 旧行为：选区 > 剪贴板（AI 回复）。
         */
        val wholeField: Boolean = false,
    )

    /** clipboard_sync 剪贴板同步能力声明。 */
    data class ClipboardSyncCapabilities(
        val protocols: List<String> = emptyList(),
        /** 0 = visibility-triggered only; LAN plugins may request foreground polling. */
        val foregroundPollIntervalMs: Long = 0,
        /** 0 leaves size policy to the legacy transport. */
        val maxTextBytes: Int = 0,
    )

    /** backup 备份能力声明：宿主负责备份包生成/恢复，插件只承载传输协议。 */
    data class BackupCapabilities(
        val protocols: List<String> = emptyList(),
    )
}