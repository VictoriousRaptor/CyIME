package com.kingzcheung.xime.service

import android.util.Log
import com.kingzcheung.xime.contacts.CandidateRows
import com.kingzcheung.xime.contacts.ContactCandidates
import com.kingzcheung.xime.contacts.ContactIndex
import com.kingzcheung.xime.contacts.ContactItem
import com.kingzcheung.xime.contacts.ContactSuggestions
import com.kingzcheung.xime.plugin.core.lua.CandidateTransformCandidate
import com.kingzcheung.xime.plugin.core.lua.CandidateTransformItem
import com.kingzcheung.xime.plugin.core.lua.CandidateTransformOutcome
import com.kingzcheung.xime.plugin.core.lua.CandidateTransformRequest
import com.kingzcheung.xime.plugin.core.lua.LuaScriptRuntime
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger

/** 变换结果：显示候选（引擎引用 + 插件候选混合）+ 平行的上屏动作映射。 */
internal data class CandidateTransformResult(
    val candidates: List<RimeCandidate>,
    val actions: List<CandidateAction>,
)

/** T9 场景插件候选快照（text + comment）：引擎候选原样保留，插件候选追加右栏。 */
data class PluginCandidateSnapshot(
    val text: String,
    val comment: String,
)

/**
 * T9 插件候选注入：锚定在某个引擎候选之后。
 * [anchorEngineIndex] = 该条目在插件响应 items 中前一个引擎引用项（engine_index）；
 * null = 无引擎锚点（追加末尾）。两条命中规则在插件 Lua 侧已表达为输出顺序
 * （编码命中紧跟引擎第一候选 → anchor=0 即第二候选位；内容命中紧跟对应候选 → anchor=k），
 * 本模型把该顺序语义无损带过 T9 索引不可信的问题。
 */
data class T9CandidateInjection(
    val anchorEngineIndex: Int?,
    val snapshot: PluginCandidateSnapshot,
    /** 通讯录插件的人名/号码：不按锚点插，交给 [com.kingzcheung.xime.contacts.ContactCandidates.merge]。 */
    val contactName: Boolean? = null,
)

/**
 * 候选词变换协调器（插件 candidate_transform 能力，首个 hotPath 能力）。
 *
 * 在 rime 返回候选之后、候选栏渲染之前同步调用插件 transformCandidates 修改候选；
 * 插件候选（text）点击后直接上屏插件文本，引擎引用（engine_index）走原引擎选词。
 *
 * 设计契约（docs/plugin-capability-registry.md §4.1/§5）：
 * - 线程：仅在 key-processing 线程调用（阻塞至多 15ms），主线程永不等待插件
 * - 短路：敏感输入框 / T9 / ascii 模式 / 空编码 / 无声明插件 → 不产生请求
 * - 熔断：连续 3 次失败（超时/报错/格式错）本会话禁用，插件重载或进程重启恢复
 * - 降级：任何失败回退原始候选，actions 为空 = 纯引擎语义
 */
internal class CandidateTransformCoordinator(private val service: XimeInputMethodService) {

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * 插件响应 → 显示候选 + 上屏动作（纯函数，便于单测）。
         * 语义校验：engine_index 越界/重复丢弃；text 空丢弃；总数上限截断。
         * 引用项 comment 覆盖显示注释；无可显示内容返回 null（不干预）。
         */
        internal fun buildDisplay(
            items: List<CandidateTransformItem>,
            engineCandidates: List<RimeCandidate>,
            maxCandidates: Int = LuaScriptRuntime.TRANSFORM_MAX_CANDIDATES,
        ): CandidateTransformResult? {
            val display = ArrayList<RimeCandidate>(items.size)
            val actions = ArrayList<CandidateAction>(items.size)
            val seenEngineIdx = HashSet<Int>()
            for (item in items) {
                if (display.size >= maxCandidates) break
                val engineIdx = item.engineIndex
                if (engineIdx != null) {
                    if (engineIdx < 0 || engineIdx >= engineCandidates.size || !seenEngineIdx.add(engineIdx)) continue
                    val engine = engineCandidates[engineIdx]
                    display.add(RimeCandidate(engine.text, item.comment ?: engine.comment))
                    actions.add(CandidateAction.engine(engineIdx))
                } else {
                    val text = item.text?.takeIf { it.isNotEmpty() } ?: continue
                    display.add(RimeCandidate(text, item.comment ?: ""))
                    actions.add(CandidateAction.plugin(text))
                }
            }
            if (display.isEmpty()) return null
            // 纯引擎引用（重排/去注释覆盖）也保留 actions：显示 index 与引擎 index 可能不同
            return CandidateTransformResult(display, actions)
        }
    }

    private val failureCircuit = RuntimeFailureCircuit(MAX_CONSECUTIVE_FAILURES)

    /** 对一次引擎按键结果做变换。返回 null = 不干预，调用方原样渲染。 */
    fun transformFor(result: RimeProcessResult): CandidateTransformResult? {
        if (result.isAsciiMode) return null
        if (result.inputText.isEmpty() && result.candidates.isEmpty()) return null
        return transform(
            inputText = result.inputText,
            preedit = result.preeditText,
            engineCandidates = result.candidates.toList(),
            asciiMode = result.isAsciiMode,
            firstPage = !result.hasPrevPage,
        )
    }

    /**
     * 变换入口（key-processing 线程，阻塞至多 15ms）。
     * T9 路径不接入变换（v1 边界），此处防御再判。
     */
    fun transform(
        inputText: String,
        preedit: String,
        engineCandidates: List<RimeCandidate>,
        asciiMode: Boolean,
        firstPage: Boolean = true,
    ): CandidateTransformResult? {
        if (asciiMode) return null
        if (isT9Schema(service.uiState.value.currentSchemaId)) return null
        val transformed = transformByPlugin(inputText, preedit, engineCandidates, asciiMode)
        val state = service.uiState.value
        return glideAlternatives.inject(withContacts(transformed, inputText, preedit, engineCandidates, firstPage),
            inputText, engineCandidates, firstPage, state.currentSchemaId, state.inputSessionId)
    }

    /** 中文滑行这一笔的备选，见 [GlideAlternatives]。 */
    val glideAlternatives = GlideAlternatives()

    /**
     * 通讯录插件（宿主内置查找，不经插件脚本）：编码正好是某个联系人名字的拼音时，
     * 把名字和号码放进候选（见 [ContactCandidates.merge]），点了走不学习的上屏。
     * 只在首页、中文键盘、非密码框里给；没启用时只读一个 volatile 就返回。
     */
    private fun withContacts(
        transformed: CandidateTransformResult?,
        inputText: String,
        preedit: String,
        engineCandidates: List<RimeCandidate>,
        firstPage: Boolean,
    ): CandidateTransformResult? {
        val items = contactItems(inputText, preedit, firstPage, t9 = false)
        if (items.isEmpty()) return transformed
        val base = transformed?.candidates ?: engineCandidates
        val merged = ContactCandidates.merge(
            CandidateRows(base.map { it.text }, base.map { it.comment }, transformed?.actions.orEmpty()), items,
        )
        val candidates = merged.texts.indices.map { i ->
            val from = merged.origin[i]
            val original = base.getOrNull(from)
            when {
                original == null -> RimeCandidate(merged.texts[i], merged.comments[i])
                original.comment == merged.comments[i] -> original
                else -> original.copy(comment = merged.comments[i])
            }
        }
        return CandidateTransformResult(candidates, merged.actions)
    }

    private fun contactItems(inputText: String, preedit: String, firstPage: Boolean, t9: Boolean): List<ContactItem> {
        if (!firstPage || !ContactSuggestions.isActive || inputText.isEmpty()) return emptyList()
        // 密码框（含终端）不给；手动无痕照样给：通讯录候选本来就不学习。
        if (service.isSecretEditor()) return emptyList()
        val profile = service.uiState.value.inputProfile
        if (profile.language != com.kingzcheung.xime.settings.InputLanguage.CHINESE ||
            profile.mode != com.kingzcheung.xime.settings.InputMode.KEYBOARD) return emptyList()
        val letters = ContactIndex.lettersOf(preedit) ?: ContactIndex.lettersOf(inputText)
        val digits = if (t9) ContactIndex.digitsOf(inputText) else null
        return ContactSuggestions.itemsFor(letters, digits)
    }

    /** 插件脚本（candidate_transform）那一份变换；没有声明的插件返回 null。 */
    private fun transformByPlugin(
        inputText: String,
        preedit: String,
        engineCandidates: List<RimeCandidate>,
        asciiMode: Boolean,
    ): CandidateTransformResult? {
        if (service.pluginEvents.isCurrentEditorSensitive) return null
        val (pluginId, runtime) = findRuntime() ?: return null
        if (!failureCircuit.permits(runtime)) return null
        val outcome = runtime.transformCandidates(
            CandidateTransformRequest(
                inputText = inputText,
                preedit = preedit,
                candidates = engineCandidates.map { CandidateTransformCandidate(it.text, it.comment) },
                asciiMode = asciiMode,
            )
        )
        return when (outcome) {
            is CandidateTransformOutcome.NoResponse -> {
                failureCircuit.record(runtime, false)
                logTransform("QWERTY", pluginId, inputText, "no-intervention", 0)
                null
            }
            is CandidateTransformOutcome.Failed -> {
                logTransform("QWERTY", pluginId, inputText, "failed", 0)
                if (failureCircuit.record(runtime, true)) {
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "candidate_transform 连续失败 $MAX_CONSECUTIVE_FAILURES 次，禁用当前实例的变换；重载可恢复"
                    )
                }
                null
            }
            is CandidateTransformOutcome.Success -> {
                failureCircuit.record(runtime, false)
                val transformed = buildDisplay(outcome.items, engineCandidates)
                logTransform("QWERTY", pluginId, inputText, "inject", transformed?.candidates?.size ?: 0)
                transformed
            }
        }
    }

    /**
     * T9 场景变换入口（t9Dispatcher/key-processing 线程，阻塞至多 15ms）。
     * 与 [transform] 的区别：T9 候选索引可能被引擎 partial 展示态替换，engine_index
     * 引用不可信 → 响应只取 text 追加项（引擎引用丢弃），但记录每条 text 项的
     * 引擎锚点（其前最近一次引擎引用的 index），供 applyComposition T9 分支按锚点
     * 插入（插件 Lua 的输出顺序已表达"编码命中→第二候选位、内容命中→跟随候选"）。
     * 短路条件同 [transform]；T9 本身不作短路（本方法即 T9 路径）。
     */
    fun transformForT9(result: RimeProcessResult): List<T9CandidateInjection>? {
        if (result.isAsciiMode) return null
        if (result.inputText.isEmpty() && result.candidates.isEmpty()) return null
        val contacts = contactItems(result.inputText, result.preeditText, !result.hasPrevPage, t9 = true).map {
            T9CandidateInjection(null, PluginCandidateSnapshot(it.text, it.comment), contactName = it.isName)
        }
        val plugin = transformForT9ByPlugin(result)
        if (contacts.isEmpty()) return plugin
        return plugin.orEmpty() + contacts
    }

    private fun transformForT9ByPlugin(result: RimeProcessResult): List<T9CandidateInjection>? {
        if (service.pluginEvents.isCurrentEditorSensitive) return null
        val (pluginId, runtime) = findRuntime() ?: return null
        if (!failureCircuit.permits(runtime)) return null
        val outcome = runtime.transformCandidates(
            CandidateTransformRequest(
                inputText = result.inputText,
                preedit = result.preeditText,
                candidates = result.candidates.map { CandidateTransformCandidate(it.text, it.comment) },
                asciiMode = result.isAsciiMode,
            )
        )
        return when (outcome) {
            is CandidateTransformOutcome.NoResponse -> {
                failureCircuit.record(runtime, false)
                logTransform("T9", pluginId, result.inputText, "no-intervention", 0)
                null
            }
            is CandidateTransformOutcome.Failed -> {
                logTransform("T9", pluginId, result.inputText, "failed", 0)
                if (failureCircuit.record(runtime, true)) {
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "candidate_transform(T9) 连续失败 $MAX_CONSECUTIVE_FAILURES 次，禁用当前实例的变换；重载可恢复"
                    )
                }
                null
            }
            is CandidateTransformOutcome.Success -> {
                failureCircuit.record(runtime, false)
                val injections = mutableListOf<T9CandidateInjection>()
                var lastEngineAnchor: Int? = null
                for (item in outcome.items) {
                    if (item.engineIndex != null) {
                        lastEngineAnchor = item.engineIndex
                        continue
                    }
                    val text = item.text?.takeIf { it.isNotEmpty() } ?: continue
                    injections.add(
                        T9CandidateInjection(lastEngineAnchor, PluginCandidateSnapshot(text, item.comment ?: ""))
                    )
                }
                logTransform("T9", pluginId, result.inputText, "inject", injections.size)
                injections.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** 第一个声明 candidate_transform 且已加载的插件运行时（v1 单插件；链式变换为后续增强）。
     *  返回 (插件 id, 运行时)；无可用插件返回 null。 */
    private fun findRuntime(): Pair<String, LuaScriptRuntime>? {
        for ((pluginId, loaded) in PluginManager.loadedPluginsFlow.value) {
            if (loaded.pluginInfo.capabilities?.candidateTransform != true) continue
            if (!com.kingzcheung.xime.settings.SettingsPreferences.isPluginEnabled(service, pluginId)) continue
            val script = loaded.script ?: continue
            return pluginId to script
        }
        return null
    }

    /** 诊断日志：hotPath 每键调用。调用点只传原始值（零字符串操作），
     *  isLoggable 未开启时立即返回（唯一开销是一次 int 读+比较，~1ns）。 */
    private fun logTransform(mode: String, pluginId: String?, input: String, state: String, count: Int) {
        if (!Log.isLoggable(XimeInputMethodService.TAG, Log.DEBUG)) return
        Log.d(XimeInputMethodService.TAG, "candidate_transform[$mode] plugin=$pluginId input='$input' $state=$count")
    }
}
