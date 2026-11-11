package com.kingzcheung.xime.service

import com.kingzcheung.xime.keyboard.HANDWRITING_SCHEMA_ID
import com.kingzcheung.xime.keyboard.underlyingPage
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.isPunctuationWidthSwitch
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CyIME 引擎补丁里的选项：全角默认只换全角标点表；开着时上屏的字母、数字、空格也改成全角（日文全角英数）。 */
internal const val FULL_SHAPE_ASCII_OPTION = "cyime_full_shape_ascii"

/** 方案里也有、但由设置页开关决定的 Rime 开关：不从 user.yaml 恢复，见 [ImeSessionController.applyCandidateOptions]。 */
private val SETTING_SWITCHES = setOf("traditionalization", "emoji")

/**
 * 输入会话组合/UI 更新与方案开关。
 *
 * 承载 Rime composition 到候选栏状态的映射（applyComposition/updateUIWithResult）、
 * 方案名称/开关刷新与切换、T9 切离提交等逻辑。共享状态通过 service 引用访问。
 */
internal class ImeSessionController(private val service: XimeInputMethodService) {
    private var omissionJob: kotlinx.coroutines.Job? = null
    private var omissionRevision = 0L
    private val omissionPause = OmissionPause()
    /** 上一帧九键编码属于哪个输入框和方案；只有同一个才能借上一帧的字母编码，见 [com.kingzcheung.xime.rime.t9RawPreedit]。 */
    private var t9PreeditOwner: Pair<Long, String>? = null
    private val t9DigitPreeditLog = T9DigitPreeditLog()

    private fun scheduleOmission(ascii: Boolean) {
        val state = service.candidateState.value
        if (state.engineRevision == omissionRevision) return
        omissionRevision = state.engineRevision
        omissionJob?.cancel()
        omissionPause.onComposition(state.inputText.length, android.os.SystemClock.uptimeMillis())
        val ui = service.uiState.value
        val profile = ui.inputProfile
        if (ascii || !service.isChineseMode || !state.isComposing || state.hasPrevPage ||
            profile.language != InputLanguage.CHINESE ||
            profile.scheme != com.kingzcheung.xime.settings.InputScheme.PINYIN ||
            profile.layout.kind != com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC ||
            profile.mode != com.kingzcheung.xime.settings.InputMode.KEYBOARD ||
            state.inputText.length !in 4..80 || state.inputText.any { it !in 'a'..'z' }) return
        // 停顿后只补漏键和打反两种读法：两个都关了就不再算一遍（方案补丁已让引擎不查，这里省掉这次重算）。
        if (!com.kingzcheung.xime.settings.SpellingPatches.typo(service, com.kingzcheung.xime.settings.SpellingPatches.Typo.OMISSION) &&
            !com.kingzcheung.xime.settings.SpellingPatches.typo(service, com.kingzcheung.xime.settings.SpellingPatches.Typo.TRANSPOSITION)) return
        val length = state.inputText.length
        omissionJob = service.serviceScope.launch(Dispatchers.Main.immediate) {
            // 停顿按自己的打字节奏算，打字途中不补算；补算占着引擎时退格和下一键都得等（见 OmissionPause）。
            kotlinx.coroutines.delay(omissionPause.delayMs())
            if (service.uiState.value.inputSessionId != ui.inputSessionId ||
                !state.hasSameSelectionSource(service.candidateState.value) || service.inputCommands.hasPendingCommands ||
                !omissionPause.withinBudget(length, android.os.SystemClock.uptimeMillis()))
                return@launch
            var elapsedMs = -1L
            val changed = withContext(service.keyProcessingDispatcher) {
                val started = android.os.SystemClock.uptimeMillis()
                service.rimeEngine.refineOmission(state.engineRevision).also {
                    elapsedMs = android.os.SystemClock.uptimeMillis() - started
                }
            }
            if (elapsedMs >= 0) {
                omissionPause.onRefined(length, elapsedMs, android.os.SystemClock.uptimeMillis())
                if (KeyLatencyProbe.enabled) android.util.Log.d(KeyLatencyProbe.TAG, "refine len=$length ms=$elapsedMs changed=$changed")
            }
            if (changed && service.uiState.value.inputSessionId == ui.inputSessionId &&
                service.candidateState.value.engineRevision == state.engineRevision) {
                omissionRevision = state.engineRevision + 1
                service.updateUI()
            }
        }
    }

    internal fun applyComposition(
        composition: com.kingzcheung.xime.rime.RimeComposition,
        pluginActions: List<CandidateAction> = emptyList(),
        t9PluginInjections: List<T9CandidateInjection> = emptyList(),
    ) {
        if (suppressFieldComposition()) return
        val inputText = composition.input
        val codeInInputBox = TerminalEditor.inputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX
        // 日语罗马音显示归一化：末尾未拼完的字母按按下的键显示（与 updateUIWithResult 同一出口规则）
        val preeditText = service.japaneseInputController.normalizePreedit(inputText, composition.preedit)
        val candidatesWithComments = composition.candidates.toList()
        val isAsciiMode = composition.isAsciiMode
        val hasNextPage = composition.hasNextPage
        val hasPrevPage = composition.hasPrevPage

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            val filtered = candidatesWithComments.filterNot { candidate ->
                candidate.text.any { it.code in 0x4E00..0x9FFF }
            }
            filtered.map { it.text } to filtered.map { it.comment }
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        var displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        var t9CandidateActions: List<CandidateAction> = emptyList()
        if (isT9Schema) {
            // T9 编码显示永不回退原始数字 input：preedit 缺失，或含未转换数字
            //（合成切不出词时引擎把原始输入当编码回退，如全拼+简拼混合态）时，
            // 保留上一帧字母编码，用户看到的编码必须是字母
            val ui = service.uiState.value
            val owner = ui.inputSessionId to ui.currentSchemaId
            val previous = service.candidateState.value
            val rawPreedit = com.kingzcheung.xime.rime.t9RawPreedit(preeditText, inputText,
                previous.preeditText, previous.isComposing, t9PreeditOwner == owner)
            t9PreeditOwner = if (inputText.isEmpty()) null else owner
            if (inputText.isNotEmpty() && preeditText.any { it.isDigit() }) t9DigitPreeditLog.note(
                inputText.length, preeditText.count { it.isDigit() }, candidatesWithComments.size,
                candidatesWithComments.firstOrNull()?.comment?.count { it != ' ' && it != '\'' } ?: 0,
                rawPreedit.isNotEmpty())
            FileLogger.i(XimeInputMethodService.TAG) {
                "T9 display: enginePreedit='$preeditText' rawPreedit='$rawPreedit' input='$inputText' partials=${service.t9PartialSegments.size}"
            }
            // preedit 转换由 C++ t9_filter 完成，Kotlin 侧直接使用引擎输出的 preedit
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            // T9 插件候选按引擎锚点插入（transformForT9 已把插件输出顺序折叠为
            // anchorEngineIndex；编码命中→锚 0→第二候选位，内容命中→跟随对应候选；
            // 无锚点追加末尾）。引擎段 actions 用平行 index 引用（T9 点击走引擎分支，
            // 不实际消费 engineIndex，仅用于插件候选分流）
            val baseCands = display.displayCandidates
            val baseComments = display.displayComments
            val byAnchor = t9PluginInjections.filter { it.contactName == null }.groupBy { it.anchorEngineIndex }
            val mergedCands = ArrayList<String>(baseCands.size + t9PluginInjections.size)
            val mergedComments = ArrayList<String>(mergedCands.size)
            val mergedActions = ArrayList<CandidateAction>(mergedCands.size)
            for ((idx, cand) in baseCands.withIndex()) {
                byAnchor[idx]?.forEach { inj ->
                    mergedCands.add(inj.snapshot.text)
                    mergedComments.add(inj.snapshot.comment)
                    mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
                }
                mergedCands.add(cand)
                mergedComments.add(baseComments.getOrElse(idx) { "" })
                mergedActions.add(CandidateAction.engine(idx))
            }
            byAnchor[null]?.forEach { inj ->
                mergedCands.add(inj.snapshot.text)
                mergedComments.add(inj.snapshot.comment)
                mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
            }
            // 通讯录的人名、号码（不学习）：已经分段上屏了一部分时不给，免得只上屏名字丢了前面的字。
            val contacts = if (service.t9PartialSegments.isNotEmpty()) emptyList() else t9PluginInjections.mapNotNull { inj ->
                inj.contactName?.let { com.kingzcheung.xime.contacts.ContactItem(inj.snapshot.text, inj.snapshot.comment, it) }
            }
            val rows = com.kingzcheung.xime.contacts.ContactCandidates.merge(
                com.kingzcheung.xime.contacts.CandidateRows(mergedCands, mergedComments, mergedActions), contacts,
            )
            displayCandidates = rows.texts
            displayComments = rows.comments
            isComposing = display.isComposing
            t9CandidateActions = rows.actions
        } else {
            // 非 T9 方案：preeditText 用引擎回显（带音节分隔符，如全拼 ni'hao）供候选栏展示；
            // inputText 保留原始键入串（无分隔）——空格/切模式等提交路径会把它直接上屏，
            // 不能混入回显字符。历史上曾整体用 input 规避自装双拼插件的展开编码显示，
            // 代价是全拼丢失分隔符；现仅显示层恢复 librime 标准回显。
            displayText = if (preeditText.isNotEmpty()) preeditText else inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = inputText.isNotEmpty()
        }

        displayText = com.kingzcheung.xime.rime.editablePreeditText(displayText, service.uiState.value.currentSchemaId, isAsciiMode)
        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            traceEventId = composition.traceEventId,
            engineRevision = composition.engineRevision,
            // T9 保持合成显示态同源；非 T9 仅显示层用 preedit 回显，inputText 保留原始键入串
            inputText = if (isT9Schema) displayText else inputText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            correctedCandidates = candidatesWithComments.filter { it.correction.isNotEmpty() }.associate { it.text to it.correction },
            isComposing = isComposing,
            // 刚打 @ 给的邮箱后缀（默认关）不是英文补全，跟着这次刷新留着。
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty() &&
                !EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)) emptyList()
                else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage,
            // 候选词变换映射（全键盘 pluginActions / T9 平行 actions；防御空）
            candidateActions = if (isT9Schema) t9CandidateActions else pluginActions
        )
        if (isAsciiMode != service.uiState.value.isAsciiMode) {
            FileLogger.i(XimeInputMethodService.TAG, "applyComposition: ascii ${service.uiState.value.isAsciiMode}->$isAsciiMode")
        }
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        scheduleOmission(isAsciiMode)
        service.refreshExpandedCandidates()
        // composing 快照 → 插件（input_changed 事件；T9 与候选栏同源显示态）
        service.pluginEvents.dispatchInputChanged(if (isT9Schema) displayText else inputText)

        if (pendingEnglish.isNotEmpty() && !service.isSecretEditor() && service.supportsEnglishCandidateReplace() &&
            !EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    val current = service.candidateState.value
                    // 在途联想过期校验：pendingEnglish 已变/已清（如点击候选栏"清空"）→
                    // 丢弃迟到回填，防止"清了又冒出来"与旧联想覆盖新联想的竞态
                    if (current.pendingEnglishText == pendingEnglish) {
                        service.candidateState.value = current.copy(associationCandidates = candidates)
                    }
                }
            }
        }

        if (codeInInputBox && !service.uiState.value.toolPanelInputFocused) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    /** 九键「下一项」换了高亮：输入框模式里的编码跟着换成高亮候选的拼音。 */
    internal fun refreshInputBoxComposition() {
        if (TerminalEditor.inputTextLocation(service) != SettingsPreferences.INPUT_TEXT_INPUT_BOX ||
            service.uiState.value.toolPanelInputFocused) return
        val state = service.candidateState.value
        val text = state.shownPreedit(service.uiState.value.inputProfile)
        if (state.isComposing && text.isNotEmpty()) showInputBoxComposition(service.currentInputConnection, text)
    }

    /** 在输入框模式向编辑器写入编码文本。 */
    private fun showInputBoxComposition(ic: android.view.inputmethod.InputConnection?, displayText: String) {
        // Android may detach the editor while a queued candidate snapshot is being delivered.
        if (ic == null) return
        // Typing into the keyboard's own field (clipboard search, tool panel, quick send): the pinyin
        // belongs there, never in the app behind it. 26-key search wrote it into the app's field and it
        // stayed after search closed (10-30 device check); all three callers pass through here.
        if (service.uiState.value.inputTarget.isInternal) return
        // 日语光标在读音中间：竖线不写进输入框，输入框的光标放在那里（11-05 用户反馈）。
        val caret = displayText.indexOf(PREEDIT_CARET)
        if (caret >= 0 && service.uiState.value.inputProfile.capabilities.japaneseConversion) {
            showInputBoxCompositionWithCaret(ic, displayText.removeRange(caret, caret + 1), caret)
            return
        }
        if (service.keepInlineEditCaret(displayText)) return
        // 第二参数为 1：光标相对编码起始偏移 1 个字符，使光标落在编码末尾，
        // 避免传 displayText.length 时被 AOSP 钳制到整段文本末尾（光标跑到最右边）。
        // 标记输入框存在 composing 区域：endComposingInputBox 仅在此标记下执行 setComposingText("") 清空，
        // 否则该调用会在光标处插入空串，光标处有选中文字时等于删除选区。
        ic.beginBatchEdit()
        try {
            if (ic.setComposingText(displayText, 1)) service.markInputBoxComposing(displayText)
        } finally {
            ic.endBatchEdit()
        }
    }

    /** 写读音后把输入框光标挪到读音里第 [caret] 个字后面（setComposingText 只能把光标放在两头）。 */
    private fun showInputBoxCompositionWithCaret(ic: android.view.inputmethod.InputConnection, text: String, caret: Int) {
        ic.beginBatchEdit()
        val written = try { ic.setComposingText(text, 1) } finally { ic.endBatchEdit() }
        if (!written) return
        service.markInputBoxComposing(text)
        val value = runCatching { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull() ?: return
        if (value.startOffset < 0 || value.selectionStart != value.selectionEnd) return
        val position = value.startOffset + value.selectionStart - (text.length - caret)
        if (position >= 0 && ic.setSelection(position, position)) service.moveInlineCompositionCaret(caret)
    }

    internal fun updateUIWithResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        pluginActions: List<CandidateAction> = emptyList(),
        updateInputBoxComposition: Boolean = true,
    ) {
        if (suppressFieldComposition()) return
        val isAsciiMode = result.isAsciiMode
        val candidatesWithComments = result.candidates
        // 日语罗马音显示归一化：末尾未拼完的字母按按下的键显示（引擎回显会给 っ / ん），
        // 与退格单位同源。放在出口而不是按键路径，刷新（updateUI/applyComposition）才不打回原回显。
        val preeditText = service.japaneseInputController.normalizePreedit(result.inputText, result.preeditText)

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            val filtered = candidatesWithComments.filterNot { candidate ->
                candidate.text.any { it.code in 0x4E00..0x9FFF }
            }
            filtered.map { it.text } to filtered.map { it.comment }
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        // 非 T9 方案（如双拼）使用原始输入文本显示，
        // 避免显示 rime speller 展开后的编码（如双拼 i → ch）
        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史（全拼/简拼）过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        var displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        if (isT9Schema) {
            // 同 applyComposition：T9 编码显示永不回退原始数字 input，
            // preedit 缺失或含未转换数字时保留上一帧字母编码
            val rawPreedit = when {
                preeditText.isNotEmpty() && preeditText.none { it.isDigit() } -> preeditText
                result.inputText.isEmpty() -> ""
                else -> service.candidateState.value.preeditText
            }
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, result.inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            displayCandidates = display.displayCandidates
            displayComments = display.displayComments
            isComposing = display.isComposing
        } else {
            // 非 T9 方案：preeditText 用引擎回显（带音节分隔符，如全拼 ni'hao），空则回退 input；
            // inputText 保留原始键入串供提交路径直接上屏（见 applyComposition 注释）。
            displayText = if (preeditText.isNotEmpty()) preeditText else result.inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = result.inputText.isNotEmpty()
        }

        displayText = com.kingzcheung.xime.rime.editablePreeditText(displayText, service.uiState.value.currentSchemaId, isAsciiMode)
        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            engineRevision = result.engineRevision,
            inputText = if (isT9Schema) displayText else result.inputText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            correctedCandidates = candidatesWithComments.filter { it.correction.isNotEmpty() }.associate { it.text to it.correction },
            isComposing = isComposing,
            // 刚打 @ 给的邮箱后缀（默认关）不是英文补全，跟着这次刷新留着。
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty() &&
                !EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)) emptyList()
                else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = result.hasNextPage,
            hasPrevPage = result.hasPrevPage,
            // 候选词变换映射（T9 不接入变换，防御清空；ascii 时调用方不产生 actions）
            candidateActions = if (isT9Schema) emptyList() else pluginActions
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = isAsciiMode)
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        scheduleOmission(isAsciiMode)
        service.refreshExpandedCandidates()
        // 候选展开页：编码删空（候选与联想均空）时自动收起，不留空页
        service.maybeCollapseCandidatePage()
        // composing 快照 → 插件（input_changed 事件；空编码表示本轮输入结束）
        service.pluginEvents.dispatchInputChanged(if (isT9Schema) displayText else result.inputText)

        if (pendingEnglish.isNotEmpty() && !service.isSecretEditor() && service.supportsEnglishCandidateReplace() &&
            !EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    val current = service.candidateState.value
                    // 在途联想过期校验：pendingEnglish 已变/已清 → 丢弃迟到回填
                    if (current.pendingEnglishText == pendingEnglish) {
                        service.candidateState.value = current.copy(associationCandidates = candidates)
                    }
                }
            }
        }

        // 在键盘自己的输入栏里打字（翻译栏等）：编码在栏里，不去动应用输入框（翻译栏写在那里的译文是组合区，
        // 这里收尾会把它定下来，下一次译文就接在后面了）。
        if (updateInputBoxComposition && !service.uiState.value.toolPanelInputFocused &&
            TerminalEditor.inputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    private val schemaRefreshRevision = java.util.concurrent.atomic.AtomicLong()

    internal fun updateSchemaName() {
        val revision = schemaRefreshRevision.incrementAndGet()
        val context = service
        service.serviceScope.launch(Dispatchers.IO) {
            val page = service.keyboardViewModel.page.value
            val isHandwritingMode = (page.underlyingPage() as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
            val engineSchemaId = service.rimeEngine.getCurrentSchema()
            // session 未就绪时 getCurrentSchema() 返回空串：用持久化方案兜底，
            // 避免空值覆盖已正确的 currentSchemaId/schemaName 导致键盘退化为全键盘
            val currentSchemaId = when {
                // 引擎已切到非手写方案时以引擎为准：键盘若仍停留手写页（部署期间
                // fallback 的残留），钉死 handwriting 会让 UI 与引擎永久脱节，
                // 表现为选完方案后键盘卡在手写页
                engineSchemaId.isNotEmpty() && !isHandwritingSchema(engineSchemaId) -> engineSchemaId
                // 手写页在态时报告当前持久化的手写方案 id（内置为 handwriting，
                // 第三方手写方案报告其自身 id，方案名/图标显示才正确）
                isHandwritingMode -> SettingsPreferences.getCurrentSchema(context)
                    .takeIf { isHandwritingSchema(it) } ?: HANDWRITING_SCHEMA_ID
                engineSchemaId.isNotEmpty() -> engineSchemaId
                else -> SettingsPreferences.getCurrentSchema(context)
            }
            val name = SchemaManager.getSchemaDisplayName(context, currentSchemaId)

            val schemas = SchemaManager.keyboardMenuSchemas(context)

            withContext(Dispatchers.Main) {
                if (revision != schemaRefreshRevision.get()) return@withContext
                service.uiState.value = service.uiState.value.copy(
                    schemaName = name ?: currentSchemaId,
                    currentSchemaId = currentSchemaId,
                    schemas = schemas
                )
                refreshSchemaSwitches()
            }
        }
    }

    /** 读取当前引擎方案 `switches` 的实际取值，组装成菜单栏可展示的状态。 */
    private fun loadSchemaSwitches(schemaId: String): List<com.kingzcheung.xime.viewmodel.SchemaSwitchUiState> {
        if (schemaId.isEmpty()) return emptyList()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        // 不能取英文态下恒为半角的值：切回中文不会重新读取，角标就会显示半角而实际上屏全角。
        val fullPunctuation = service.textCommit.punctuationWidthChoice()
        // 全角/半角是标点宽度的唯一入口：方案里的 full_shape / half_shape / ascii_punct
        //（含 options 选项组）不再作为独立菜单项重复列出；引擎选项改由 applyPunctuationWidth 同步。
        val switches = defs.filterNot { isPunctuationWidthSwitch(it) }.map { def ->
            val index = when {
                def.name.isNotEmpty() && def.states.isNotEmpty() ->
                    if (service.rimeEngine.getOption(def.name)) minOf(1, def.states.size - 1) else 0
                def.options.isNotEmpty() -> {
                    val active = def.options.indexOfFirst { service.rimeEngine.getOption(it) }
                    if (active < 0) 0 else active
                }
                else -> 0
            }
            com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
                name = def.name,
                options = def.options,
                states = def.states,
                abbrev = def.abbrev,
                currentIndex = index
            )
        }
        // All layouts, including schemas without a native full_shape switch, share this control.
        return switches + com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
            name = "full_shape", states = listOf("半角", "全角"), currentIndex = if (fullPunctuation) 1 else 0)
    }

    /** 在引擎线程上刷新菜单栏方案开关状态。 */
    internal fun refreshSchemaSwitches() {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            val schemaId = service.rimeEngine.getCurrentSchema()
            val switches = loadSchemaSwitches(schemaId)
            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(schemaSwitches = switches)
            }
        }
    }

    /** Share the punctuation preference and serialize the shortcut with typed keys. */
    internal fun togglePunctuationWidth(notifyAscii: Boolean = false) {
        service.keyRouter.postRimeJob {
            if (service.uiState.value.isAsciiMode) {
                if (notifyAscii) withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(service, "英文模式使用半角标点", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@postRimeJob
            }
            val full = !service.textCommit.isFullWidthPunctuation()
            SettingsPreferences.setPunctuationFullWidth(service, full)
            applyPunctuationWidth(asciiMode = false)
            service.updateUI()
            refreshSchemaSwitches()
        }
    }

    /** 菜单栏方案开关点击：切换引擎选项并刷新状态。 */
    internal fun toggleSchemaSwitch(sw: com.kingzcheung.xime.viewmodel.SchemaSwitchUiState) {
        if (sw.name == "full_shape" || sw.name == "ascii_punct") { togglePunctuationWidth(); return }
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            if (sw.name == "ascii_mode") {
                service.schemaController.switchInputMethod()
            } else if (sw.name.isNotEmpty()) {
                val newValue = !service.rimeEngine.getOption(sw.name)
                service.rimeEngine.setOption(sw.name, newValue)
                persistSchemaOption(sw.name, newValue)
                service.updateUI()
            } else if (sw.options.isNotEmpty()) {
                val nextIndex = (sw.currentIndex + 1) % sw.options.size
                sw.options.forEachIndexed { i, opt ->
                    service.rimeEngine.setOption(opt, i == nextIndex)
                    persistSchemaOption(opt, i == nextIndex)
                }
                service.updateUI()
            }
            refreshSchemaSwitches()
        }
    }

    /**
     * 把用户持久化的标点宽度选择写回引擎选项，让菜单/键面显示与实际输出同源。
     *
     * 全角/半角只作用于中文与日文：英文（ASCII）模式固定半角，只清掉全角字符输出
     * （ascii_punct 在英文态由 ascii_composer 接管，不动它，退出英文后中文标点不会被改写）。
     * 用户从未选择过宽度时同样不动引擎——此时界面显示值取自方案自身的 ascii_punct，两者本来一致。
     *
     * @param asciiMode 权威的中英状态，由调用方给出；不要读 uiState，切换方案/退出英文时
     *   uiState 尚未同步，会误判成英文而把中文键盘按半角处理。
     */
    internal fun applyPunctuationWidth(asciiMode: Boolean) {
        // 中文的全角只管标点：候选里的英文、数字和空格照候选栏显示的半角上屏（GitHub 不会变成 ＧｉｔＨｕｂ）。
        // 日文的全角英数是常用写法，仍由引擎整段转成全角。
        val japanese = InputLanguage.forSchema(service.rimeEngine.getCurrentSchema()) == InputLanguage.JAPANESE
        service.rimeEngine.setOption(FULL_SHAPE_ASCII_OPTION, japanese)
        if (asciiMode) {
            service.rimeEngine.setOption("full_shape", false)
            return
        }
        if (!SettingsPreferences.hasPunctuationFullWidth(service)) return
        val full = SettingsPreferences.punctuationFullWidth(service, true)
        service.rimeEngine.setOption("ascii_punct", !full)
        service.rimeEngine.setOption("full_shape", full)
    }

    /**
     * 设置页里直接改 Rime 候选的开关写进引擎，按键路径、打开输入框和设置监听共用一处，设置是唯一来源：
     * 中英混输、繁体输出（雾凇系方案是 traditionalization；仓颉码表是繁体，反过来用 simplification）、
     * 时间日期候选（选项是反的）、候选里显示表情、打符号名出符号、希腊字母、生僻字。只在值不同时写，返回是否有改动。
     * [persist] 时把方案开关 traditionalization、emoji 同步进 user.yaml：librime 自己重建会话时按
     * switcher/save_options 从那里恢复，旧值不能再盖过设置。按键路径不写文件。
     */
    internal fun applyCandidateOptions(
        scheme: InputScheme = service.uiState.value.inputProfile.scheme,
        persist: Boolean = false,
    ): Boolean {
        if (!RimeEngine.isInitialized()) return false
        val prefs = SettingsPreferences.getPrefsPublic(service)
        val traditional = prefs.getBoolean(KeyboardInputPreferences.TRADITIONAL_OUTPUT_KEY, false)
        // 日语长音键：默认 L 键就是 ー；选了单独的 ー 键时 L 是字母 l。翻译器读选项，显示与退格读同一个值。
        val lLetter = !com.kingzcheung.xime.ui.keyboard.JapaneseLongVowelKey.fromName(
            prefs.getString(KeyboardInputPreferences.JAPANESE_LONG_VOWEL_KEY, null)).lIsLongVowel
        com.kingzcheung.xime.rime.japaneseLIsLongVowel = !lLetter
        val options = buildList {
            add("cyime_mixed_input" to prefs.getBoolean(KeyboardInputPreferences.MIXED_INPUT_KEY, false))
            add("traditionalization" to traditional)
            if (scheme == InputScheme.CANGJIE) add("simplification" to !traditional)
            if (scheme == InputScheme.ZHUYIN) {
                add("zh_tw" to true) // 声调表使用台湾字形，简体输出在声调筛选后转换。
                add("zhuyin_simplified" to !traditional) // 注音也跟「繁体输出」：关时转简体。
            }
            add("_cyime_datetime_off" to !prefs.getBoolean(KeyboardInputPreferences.DATETIME_CANDIDATES_KEY, false))
            add("emoji" to prefs.getBoolean(KeyboardInputPreferences.CANDIDATE_EMOJI_KEY, false))
            add("cyime_symbol_names" to prefs.getBoolean(KeyboardInputPreferences.SYMBOL_NAMES_KEY, false))
            add("cyime_greek_letters" to prefs.getBoolean(KeyboardInputPreferences.GREEK_LETTERS_KEY, false))
            val cylmAlternative = prefs.getBoolean(KeyboardInputPreferences.CYLM_LONG_ALTERNATIVE_KEY, false)
            if (persist || cylmAlternative) add("cyime_cylm_alternative" to cylmAlternative)
            add("cyime_rare_chars" to prefs.getBoolean(KeyboardInputPreferences.RARE_CHARACTERS_KEY, false))
            add("_cyime_l_letter" to lLetter)
        }
        var changed = false
        for ((name, value) in options) {
            if (service.rimeEngine.getOption(name) != value) {
                service.rimeEngine.setOption(name, value)
                changed = true
            }
            if (persist && name in SETTING_SWITCHES && service.rimeEngine.getUserConfigBool("var/option/$name") != value) {
                service.rimeEngine.setUserConfigBool("var/option/$name", value)
            }
        }
        return changed
    }

    /** 将方案选项状态写入 librime user.yaml（var/option/<name>）。 */
    internal fun persistSchemaOption(name: String, value: Boolean) {
        if (service.editorInputPolicy.scoped && name == "ascii_mode") return
        if (RimeEngine.isInitialized()) {
            service.rimeEngine.setUserConfigBool("var/option/$name", value)
        }
    }

    /** 从 librime user.yaml 恢复方案选项（中/西、简/繁等），在切换方案后调用。 */
    internal fun restorePersistedSchemaOptions() {
        if (!RimeEngine.isInitialized()) return
        val schemaId = service.rimeEngine.getCurrentSchema()
        if (schemaId.isEmpty()) return
        val rimeAsciiBefore = service.rimeEngine.isAsciiMode()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        val scheme = com.kingzcheung.xime.settings.InputProfiles.describe(schemaId).scheme
        // 方案写的默认值（reset）只给导入的方案用；APK 自带的方案保持原来的行为（没切过就是关），不改内置默认。
        val useDeclaredDefault = !SchemaManager.isBundledSchema(service, schemaId)
        for (def in defs) {
            // 标点宽度不从 user.yaml 恢复：它的权威来源是用户的「全角／半角」选择，
            // 由 applyPunctuationWidth 统一回写（否则这里会把用户选择覆盖成旧值）。
            if (isPunctuationWidthSwitch(def)) continue
            // 简繁、表情也不从 user.yaml 恢复：权威来源是设置页的开关，由下面的 applyCandidateOptions 写回。
            if (def.name in SETTING_SWITCHES || def.name == "simplification" && scheme == InputScheme.CANGJIE) continue
            if (def.name == "zh_tw" && scheme == InputScheme.ZHUYIN) continue
            if (def.name.isNotEmpty()) {
                // 导入的方案用户没切过（user.yaml 里没有这一项）就按方案写的默认值（reset），不能一律当成关：
                // 导入的声笔 sbxm 写 pro_char reset: 1（单字），原来被改成 0（词组）（GitHub #105）。
                val key = "var/option/${def.name}"
                val value = if (service.rimeEngine.getUserConfigString(key) != null) service.rimeEngine.getUserConfigBool(key)
                    else useDeclaredDefault && def.reset == 1
                service.rimeEngine.setOption(def.name, value)
            } else if (def.options.isNotEmpty()) {
                val activeIndex = def.options.indexOfFirst { service.rimeEngine.getUserConfigBool("var/option/$it") }
                if (activeIndex >= 0) {
                    def.options.forEachIndexed { i, opt -> service.rimeEngine.setOption(opt, i == activeIndex) }
                }
            }
        }
        // A disabled language may still be the real Rime fallback for the virtual English mode.
        if (com.kingzcheung.xime.settings.InputLanguage.forSchema(schemaId) !in
            com.kingzcheung.xime.settings.LanguagePreferences.enabled(service)) {
            service.rimeEngine.setOption("ascii_mode", true)
            persistSchemaOption("ascii_mode", true)
        }
        service.editorInputPolicy.engineAsciiOverride()?.let { service.rimeEngine.setOption("ascii_mode", it) }
        applyPunctuationWidth(service.rimeEngine.isAsciiMode())
        // 切方案后 uiState 可能还没跟上，使用引擎刚刚切到的方案。
        applyCandidateOptions(scheme = scheme, persist = true)
        val rimeAsciiAfter = service.rimeEngine.isAsciiMode()
        if (rimeAsciiBefore != rimeAsciiAfter) {
            FileLogger.i(XimeInputMethodService.TAG, "restorePersistedSchemaOptions: ascii $rimeAsciiBefore -> $rimeAsciiAfter (ui=${service.uiState.value.isAsciiMode})")
        }
    }

    /**
     * T9 键盘切换离开时：提交右侧候选词列表首位候选词并清理 T9 和 Rime 状态。
     * 运行在 keyProcessingDispatcher 线程。
     */
    internal suspend fun commitFirstCandidateAndClearT9() {
        // 「下一项」换了高亮：先走空格那条路上屏高亮的候选，剩下的编码再按首选上屏（用户 10-27：处处跟高亮一致）。
        val snapshot = service.candidateState.value
        snapshot.modeChangeCandidateIndex(service.uiState.value.inputProfile)?.let {
            service.keyRouter.selectCandidateAsync(it, snapshot = snapshot)
        }
        service.textCommit.commitCompositionAndLiteral()
    }

    /** Late engine snapshots cannot reopen composition in a field that requires literal input. */
    private fun suppressFieldComposition(): Boolean {
        val state = service.uiState.value
        if (!service.editorInputPolicy.directInput || state.inputTarget.isInternal) return false
        service.uiState.value = state.copy(isAsciiMode = true)
        val candidates = service.candidateState.value
        if (!candidates.isShowingRecentClipboard) service.candidateState.value = CandidateState()
        return true
    }

}

/**
 * 九键编码里还有没转成字母的数字（整串没有出整句，首选只盖住前面一段）时记一行 W 日志，
 * 只记长度和个数，不记按键和文字；同一串只记一次，最多每 2 秒一行。11-10 录屏「突然什么都输入不了」用。
 */
internal class T9DigitPreeditLog(private val now: () -> Long = { android.os.SystemClock.uptimeMillis() }) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastInput = -1

    fun note(inputLength: Int, digits: Int, candidates: Int, topSpellingLetters: Int, borrowed: Boolean) {
        val t = now()
        if (inputLength == lastInput || t - lastAt < 2_000L) return
        lastAt = t
        lastInput = inputLength
        com.kingzcheung.xime.util.FileLogger.w("T9Display", "digit preedit: input_len=$inputLength " +
            "digits_left=$digits candidates=$candidates top_spelling_letters=$topSpellingLetters borrowed=$borrowed")
    }
}
