package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import com.kingzcheung.xime.handwriting.SPenSounds
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.handwriting.EnglishWordRecognizer
import com.kingzcheung.xime.handwriting.EnglishWordSpacing
import com.kingzcheung.xime.handwriting.FormulaRecognizer
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.HandwritingTemplates
import com.kingzcheung.xime.handwriting.HandwritingLanguages
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.handwriting.HandwritingStrokeFx
import com.kingzcheung.xime.handwriting.OverlappedHandwritingRecognizer
import com.kingzcheung.xime.handwriting.renderStrokes
import com.kingzcheung.xime.handwriting.rememberHandwritingInk
import com.kingzcheung.xime.handwriting.rememberSPenInk
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 公式预览的上边：让开画板顶部的语言胶囊和公式按钮（按钮 8dp 外边距 + 约 32dp 高）。 */
private val FORMULA_PREVIEW_TOP = 44.dp

/** 并排书写（日语、英文）一次最多切出的字数：一个单词、一小句。 */
private const val SIDE_BY_SIDE_MAX_CHARS = 12

/**
 * 手写板左上角的当前语言提示：平时只写语言名（「日语」「中文」），胶囊小、不占画板（用户 11-02：
 * 「不需要写那么多，日语两个字就行」）。只有正在准备、假名模板没读到这种要人知道的状态才多写一句。
 * [useTemplates] 不再改变文字（原来英文写「边写边学」），留着参数不改调用处。
 */
@Suppress("UNUSED_PARAMETER")
internal fun handwritingLanguageHint(language: InputLanguage, useTemplates: Boolean, preparing: Boolean, missingKana: Boolean): String = when {
    preparing -> "${language.displayName} · 正在准备（只在第一次较慢）…"
    // 手写训练页只在自用测试包里有，公开包不指向它。
    language == InputLanguage.JAPANESE && missingKana ->
        if (com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG)
            "日语 · 假名模板没读到，去 设置 → 手写训练 导入样本备份" else "日语 · 假名模板没读到"
    else -> language.displayName
}

/**
 * 当前语言做成与公式按钮（关闭时）同样的实心小胶囊：主题功能键底色（不透明）＋功能键文字色，
 * 任何主题、玻璃和壁纸上都看得清。左上角，右侧让出公式按钮。
 */
@Composable
internal fun HandwritingLanguageHint(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    // 不用 Surface：它会挡住落笔；这里只是显示，笔从胶囊上也能写下去
    Text(text, color = foreground, fontSize = 13.sp,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, end = 96.dp)
            .background(background.copy(alpha = 1f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("handwriting-language"))
}

@Composable
internal fun HandwritingUnavailable(language: InputLanguage, modifier: Modifier, onExit: () -> Unit,
    exitLabel: String = "返回键盘", message: String = HandwritingLanguages.unavailableMessage(language)) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("handwriting-unavailable"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = onExit) { Text(exitLabel) }
    }
}

@Composable
fun HandwritingKeyboardLayout(
    onKeyPress: (String) -> Unit = {},
    onNewCharacter: (() -> Unit)? = null,
    onRecognition: ((List<OverlappedHandwritingRecognizer.Segment>) -> Unit)? = null,
    /** 边写边识别：写的过程中当前笔迹的识别结果；空列表表示撤掉（见 HandwritingInputSession）。 */
    onPreview: ((List<OverlappedHandwritingRecognizer.Segment>) -> Unit)? = null,
    onButtonFeedback: ((String) -> Unit)? = null,
    keyTextColor: Color = Color(0xFF333333),
    keyBackgroundColor: Color = Color(0xFFE0E0E0),
    specialKeyBackgroundColor: Color = Color(0xFFD0D0D0),
    bottomPaddingDp: Int = 18,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
    sessionKey: Long = 0,
    specialKeyTextColor: Color = Color.White,
    expanded: Boolean = false,
    expandedControlsWidthDp: Int = 0,
    expandedControlsOffsetX: Int = 0,
    panelBackgroundColor: Color = Color.Transparent,
    expandedCandidateBar: @Composable () -> Unit = {},
    language: InputLanguage = InputLanguage.CHINESE,
    onUnsupportedExit: () -> Unit = {},
    unsupportedExitLabel: String = "返回键盘",
    floating: Boolean = false,
) {
    val sideSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.HANDWRITING)
    val rowSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.HANDWRITING_ROW)
    // 假名、英文字母：有公式模型包时走“边用边学”的模板比对（HandwritingTemplates），日语手写也因此可用
    val templateScript = HandwritingTemplates.Script.forLanguage(language)
        ?.takeIf { it.available(LocalContext.current) }
    val recognitionLanguage = HandwritingLanguages.recognitionLanguageForKeyboard(language)
        ?: if (templateScript != null) language else null
    if (recognitionLanguage == null) {
        // 日语手写靠公式模型算特征来比对假名：缺的是这个模型时，直接说去哪下载。
        val missingModel = HandwritingTemplates.Script.forLanguage(language) != null
        HandwritingUnavailable(language, modifier, onUnsupportedExit, unsupportedExitLabel,
            message = if (missingModel) "${language.displayName}手写需要先在 设置 → 扩展 → 模型 下载「公式手写模型」"
                else HandwritingLanguages.unavailableMessage(language))
        return
    }
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val keyConfig = remember(configVersion, KeyShapes.current) { KeysConfigHelper.getKeyboardKeyConfig() }
    val spacing = keyConfig.spacingFor("qwerty")
    // Measure gaps against the same four-row QWERTY reference. The handwriting
    // controls below still divide the actual body into five equal rows.
    KeyboardKeySpacingScope(modifier, verticalInset = (8 + bottomPaddingDp).dp,
        allowShrink = floating, applyGutter = !expanded, growthSpacing = spacing) { bodyModifier ->
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyConfig.cornerRadius.dp,
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = spacing.first?.dp ?: 2.dp, vertical = spacing.second?.dp ?: 2.dp),
    ) {
    val context = LocalContext.current
    var normalControlHeight by remember { mutableFloatStateOf(52f) }
    var normalControlScale by remember { mutableStateOf<Float?>(null) }
    var normalControlMetrics by remember { mutableStateOf<KeyVisualMetrics?>(null) }
    var normalControlGutter by remember { mutableFloatStateOf(8f) }
    val currentContentScale = LocalKeyboardKeyContentScale.current
    val currentVisualMetrics = LocalKeyboardKeyVisualMetrics.current
    val inkColor = rememberHandwritingInk(panelBackgroundColor)
    val penInkColor = rememberSPenInk(panelBackgroundColor)
    val scope = rememberCoroutineScope()
    val keyAction by rememberUpdatedState(onKeyPress)
    val newCharacter by rememberUpdatedState(onNewCharacter)
    val result by rememberUpdatedState(onRecognition)
    val preview by rememberUpdatedState(onPreview)
    val feedback by rememberUpdatedState(onButtonFeedback)
    // The rows sit on the keyboard's own background (gradient, image or glass); a solid panel colour
    // here showed as a dark band around the bottom row on gradient themes.
    val functionRowBackground = Color.Transparent
    val pauseMs by rememberUpdatedState((LocalKeyboardInputPreferences.current.handwritingPauseSeconds * 1000).toLong())
    val recognizeWhileWriting by rememberUpdatedState(LocalKeyboardInputPreferences.current.handwritingRecognizeWhileWriting)
    val recognitionMutex = remember { Mutex() }
    val stylusPresence = remember { StylusPresence() }
    val eraserRadiusPx = with(LocalDensity.current) { com.kingzcheung.xime.handwriting.PenEraser.Radius.toPx() }
    // 三星设备上用系统自带的 S Pen 书写/橡皮声，满音量对应约 1 dp/ms 的笔速。
    val penSoundFullSpeed = with(LocalDensity.current) { 1.dp.toPx() }
    val penSounds = remember(context) { SPenSounds(context.applicationContext) }
    LaunchedEffect(penSounds) { withContext(Dispatchers.IO) { penSounds.load() } }
    DisposableEffect(penSounds) { onDispose { penSounds.release() } }
    // 公式模式：只有电脑上训练的公式模型包放到了设备上才显示开关（见 FormulaRecognizer）
    val formulaPrefs = remember(context) { context.getSharedPreferences("handwriting", android.content.Context.MODE_PRIVATE) }
    // 公式只在英文手写里提供：中文、日语手写不显示公式按钮，也不沿用上次的公式模式。
    val formulaAvailable = remember(context, language) { language == InputLanguage.ENGLISH && FormulaRecognizer.isAvailable(context) }
    // 英文手写里总显示公式按钮；还没有公式模型时点它直接打开公式模型的下载页
    // （以前跳「模型管理」，那页只列已安装的模型，没装过的公式模型在那里找不到）
    val formulaShown = language == InputLanguage.ENGLISH
    var formulaMode by remember(formulaAvailable) { mutableStateOf(formulaAvailable && formulaPrefs.getBoolean("formula_mode", false)) }
    val formulaActive by rememberUpdatedState(formulaMode && formulaAvailable)
    val delimiter by rememberUpdatedState(LocalKeyboardInputPreferences.current.formulaDelimiter)
    // 模板库：第一次打开时把训练页写过的假名 / 字母算成模板（后台），之后每次选候选都会追加
    var templateCount by remember(templateScript) {
        mutableIntStateOf(templateScript?.let { HandwritingTemplates.count(context, it) } ?: 0)
    }
    // 只在后台读样本那一会儿显示“正在读取”；读完仍没有模板就直接说缺什么，不一直转圈。
    var importingTemplates by remember(templateScript) { mutableStateOf(templateScript != null) }
    LaunchedEffect(templateScript) {
        val script = templateScript ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                runCatching { HandwritingTemplates.importTrainingSamples(context, script) }
                runCatching { HandwritingTemplates.ensureDefaults(context, script) }
            }
            templateCount = HandwritingTemplates.count(context, script)
        } finally { importingTemplates = false }
    }
    val useTemplates by rememberUpdatedState(templateScript != null && templateCount > 0)
    // 公式模式下首选候选的渲染预览（LaTeX → KaTeX），板上没笔迹时不显示
    var formulaPreview by remember { mutableStateOf("") }
    // 英文：停顿后接着写的新词前补空格、一段里写了几个词时分开认（整词模型不出空格）
    val wordSpacing = remember(sessionKey, language) { EnglishWordSpacing() }
    fun spaced(segments: List<OverlappedHandwritingRecognizer.Segment>, startsWord: Boolean) =
        if (!startsWord || segments.isEmpty()) segments
        else listOf(segments[0].copy(candidates = segments[0].candidates.map { it.copy(char = " " + it.char) })) + segments.drop(1)
    val session = remember(scope, sessionKey, language) {
        HandwritingInputSession(scope, { pauseMs }, recognize = { strokes ->
            if (formulaActive) recognitionMutex.withLock {
                // 公式：整段笔迹一次识别成 LaTeX，候选按设置的标记上屏；首选另外渲染成公式预览（候选栏仍是纯文本）
                val results = withContext(Dispatchers.Default) {
                    FormulaRecognizer.recognize(context, strokes.map { stroke -> stroke.map { it.x to it.y } })
                }
                formulaPreview = results.firstOrNull()?.first.orEmpty()
                if (results.isEmpty()) emptyList()
                else listOf(OverlappedHandwritingRecognizer.Segment(0, strokes.size,
                    results.map { (latex, score) -> HandwritingCandidate(delimiter.wrap(latex), score) }, replacesPending = true))
            } else if (useTemplates) recognitionMutex.withLock {
                // 假名 / 漢字 / 字母：一次可以写多个字，和中文一样按停顿与笔画切分，每段和模板比，最像的几个当候选
                withContext(Dispatchers.Default) {
                    val script = templateScript ?: return@withContext emptyList()
                    val points = strokes.map { stroke -> stroke.map { it.x to it.y } }
                    val chunk = if (script == HandwritingTemplates.Script.LATIN) wordSpacing.next(points) else null
                    val gaps = HandwritingStrokeFx.windowGaps(strokes).map { it * OverlappedHandwritingRecognizer.GAP_SPLIT_MS / pauseMs }
                    // 字母最多 4 笔；日语有漢字，沿用中文的上限
                    // 日语、英文习惯从左往右并排写：位置空隙也当换字信号
                    val recognizer = if (script == HandwritingTemplates.Script.LATIN)
                        OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, maxStrokesPerSegment = 4, sideBySide = true, lettersApart = true)
                        else OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, sideBySide = true)
                    val segments = recognizer.recognize(points, gaps, predictFn = { seg, k ->
                        // 接着落笔、删除或切走时这次识别已作废：下一段推理前就停，不再占着锁让下一个字排队
                        ensureActive()
                        // 模板分数 (cos+1)/2 换回余弦，乱切的段明显更低，切分才分得开
                        HandwritingTemplates.recognize(context, script, seg, k, remember = false)
                            .map { (text, score) -> HandwritingCandidate(text, (2f * score - 1f).coerceAtLeast(0.0001f)) }
                    }).segments
                    // 英文整词模型在时：整段笔迹一次认成单词放在前面，逐个字母切出来的结果拼成一个词排在后面（数字只有它能出）
                    val words = if (chunk != null && EnglishWordRecognizer.isAvailable(context))
                        EnglishWordRecognizer.recognizeWords(context, points, chunk.words) else emptyList()
                    if (words.isNotEmpty()) {
                        val spelled = segments.joinToString("") { it.candidates.firstOrNull()?.char.orEmpty() }
                        val candidates = (words.map { (word, score) -> HandwritingCandidate(word, score) } +
                            listOfNotNull(spelled.takeIf { it.isNotEmpty() }?.let { HandwritingCandidate(it, 0f) }))
                            .distinctBy { it.char }
                        HandwritingTemplates.rememberForLearning(context, script, null)
                        return@withContext spaced(listOf(OverlappedHandwritingRecognizer.Segment(0, strokes.size, candidates)), chunk?.startsWord == true)
                    }
                    HandwritingTemplates.rememberForLearning(context, script, points.takeIf { segments.size == 1 })
                    spaced(segments, chunk?.startsWord == true)
                }
            } else {
                // 只传不可变快照；每次创建新窗口，取消的本地推理不会污染下一字缓存。
                val gaps = HandwritingStrokeFx.windowGaps(strokes).map { it * OverlappedHandwritingRecognizer.GAP_SPLIT_MS / pauseMs }
                recognitionMutex.withLock {
                    withContext(Dispatchers.Default) {
                        // 英文（内置模型里的字母）也按并排书写切分；中文保持叠写习惯
                        val points = strokes.map { stroke -> stroke.map { it.x to it.y } }
                        val startsWord = recognitionLanguage == InputLanguage.ENGLISH && wordSpacing.next(points).startsWord
                        spaced((if (recognitionLanguage == InputLanguage.CHINESE) OverlappedHandwritingRecognizer()
                            else OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, sideBySide = true,
                                maxStrokesPerSegment = if (recognitionLanguage == InputLanguage.ENGLISH) 4
                                    else OverlappedHandwritingRecognizer.DEFAULT_MAX_STROKES_PER_SEGMENT,
                                lettersApart = recognitionLanguage == InputLanguage.ENGLISH,
                                letterCost = OverlappedHandwritingRecognizer.MODEL_LETTER_COST)).recognize(
                            points, gaps,
                            // 作废的识别在下一段推理前就停（见上），不会跑完整个切分再放锁
                            predictFn = { seg, topK -> ensureActive(); HandwritingEngine.predict(seg, topK, recognitionLanguage) },
                        ).segments, startsWord)
                    }
                }
            }
        }, onNewCharacter = { newCharacter?.invoke() },
            // 按键（空格、回车、标点…）在本段笔迹认完之后才执行：从这里起下一段不再补空格
            onRecognition = { result?.invoke(it) }, onKey = { wordSpacing.reset(); keyAction(it) }, keepInk = { formulaActive },
            // 边写边识别只给中文、日语：英文要按停顿分词（有状态），公式整段重认，都不能提前认
            recognizeEarly = { recognizeWhileWriting && !formulaActive &&
                (recognitionLanguage == InputLanguage.CHINESE || recognitionLanguage == InputLanguage.JAPANESE) },
            onPreview = { preview?.invoke(it) })
    }
    val modelDownloaded by remember {
        ModelManager.downloadStates.map { it["ochwpro"] is ModelDownloadState.Complete }.distinctUntilChanged()
    }.collectAsState(ModelManager.downloadStates.value["ochwpro"] is ModelDownloadState.Complete)
    LaunchedEffect(modelDownloaded, language) { withContext(Dispatchers.IO) { HandwritingEngine.initialize(context, recognitionLanguage) } }
    LaunchedEffect(clearSignal, session) { session.clear() }
    DisposableEffect(session) { onDispose { session.clear() } }

    fun press(action: String) {
        feedback?.invoke(action)
        session.press(action)
    }

    // Keep QWERTY's content edges and bottom inset, with five handwriting rows.
    // 全屏时这段底部留白放进底下那块按键的底色里（见下），不在画板下面另留一条透明的。
    BoxWithConstraints(bodyModifier.fillMaxSize().testTag("handwriting-panel")
        .padding(bottom = if (expanded) 0.dp else KeyboardGridBottomMargin + bottomPaddingDp.dp)) {
        // Fullscreen applies to ink only. Keep the footer in the user's normal keyboard bounds.
        val controlsWidth = if (expanded && expandedControlsWidthDp > 0)
            (expandedControlsWidthDp.dp - normalControlGutter.dp * 2).coerceIn(1.dp, maxWidth.coerceAtLeast(1.dp)) else maxWidth
        val travel = ((maxWidth - controlsWidth) / 2).value.coerceAtLeast(0f)
        val controlsOffset = if (expanded) expandedControlsOffsetX.toFloat().coerceIn(-travel, travel).dp else 0.dp
        // The footer material below sits inside nested layout scopes, where maxWidth needs an explicit receiver.
        val panelWidth = maxWidth
        val bottom = QwertyBottomRowWeights.Standard
        val functionKeyWidth = controlsWidth * (bottom.enter / bottom.total)
        val footerHeight = if (expanded) normalControlHeight.dp.coerceAtMost(maxHeight / 3) else maxHeight / 5
        if (!expanded) SideEffect {
            normalControlHeight = footerHeight.value
            normalControlScale = currentContentScale
            normalControlMetrics = currentVisualMetrics
            normalControlGutter = currentVisualMetrics.gutterX
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
            // 公式预览只是叠在画板顶部的显示内容，放在画布下层：整块区域都能落笔，笔迹画在预览之上。
            // 放在左上语言胶囊和右上公式按钮那一行下面：「英文手写 · 边写边学」这类长提示比原来让出的 96dp 宽，
            // 会盖住预览（GitHub #66 #67）。
            if (formulaActive) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(top = FORMULA_PREVIEW_TOP, start = 16.dp, end = 16.dp).height(52.dp)
                .testTag("handwriting-formula-preview"), contentAlignment = Alignment.Center) {
                if (session.hasInk && formulaPreview.isNotEmpty())
                    com.kingzcheung.xime.ui.settings.KatexView(formulaPreview, Modifier.fillMaxSize(), color = keyTextColor.copy(alpha = 0.7f))
                else Text("写完停笔，这里显示公式预览", color = keyTextColor.copy(alpha = 0.4f), fontSize = 12.sp)
            }
            Canvas(Modifier.fillMaxSize().clipToBounds().testTag("handwriting-canvas")
                .semantics { contentDescription = "手写区域"; stateDescription = session.phase.description }
                .handwritingInk(session, stylusPresence, eraserRadiusPx,
                    onErased = { if (!penSounds.active) feedback?.invoke("handwriting_erase") },
                    onPenMotion = { erasing, speed -> penSounds.onMotion(erasing, speed, penSoundFullSpeed) },
                    onPenLifted = { penSounds.stop() })) {
                renderStrokes(session.strokes + listOfNotNull(session.currentStroke.takeIf { it.isNotEmpty() }), emptyList(), inkColor, transparentPaper = expanded, penColor = penInkColor)
            }
            // 正在写的语言平时写在空格键上，和其他键盘一样（用户 11-03，#107：胶囊挡住画板和公式预览）。
            // 画板上只在正在准备、假名模板没读到这种要人知道的时候才浮出提示。
            val hintPreparing = templateScript != null && importingTemplates
            val hintMissingKana = templateScript == HandwritingTemplates.Script.KANA && !useTemplates
            if (hintPreparing || hintMissingKana) HandwritingLanguageHint(handwritingLanguageHint(language, useTemplates,
                    preparing = hintPreparing, missingKana = hintMissingKana),
                specialKeyBackgroundColor, specialKeyTextColor, Modifier.align(Alignment.TopStart))
            // 公式开关是浮在画板右上角的实心小按钮，不用对钩：开启时强调色填满、文字在黑白里取反差大的；
            // 关闭时用主题功能键的底色（强制不透明）和文字色，不加描边。
            if (formulaShown) Surface(
                onClick = {
                    if (!formulaAvailable) {
                        context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context,
                            "model_market_detail/${FormulaRecognizer.MODEL_ID}"))
                        return@Surface
                    }
                    session.clear()
                    keyAction("clear_composition")
                    formulaMode = !formulaMode
                    formulaPreview = ""
                    formulaPrefs.edit().putBoolean("formula_mode", formulaMode).apply()
                    if (!formulaMode) FormulaRecognizer.release()
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("handwriting-formula-toggle")
                    .semantics { stateDescription = if (formulaMode) "公式模式已开" else "公式模式已关" },
                shape = RoundedCornerShape(50),
                color = if (formulaMode) MaterialTheme.colorScheme.primary.copy(alpha = 1f) else specialKeyBackgroundColor.copy(alpha = 1f),
                contentColor = if (formulaMode) {
                    if (MaterialTheme.colorScheme.primary.luminance() > 0.179f) Color.Black else Color.White
                } else specialKeyTextColor,
                shadowElevation = if (formulaMode) 2.dp else 0.dp,
            ) {
                Text("公式", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
                }
                if (!expanded) Column(Modifier.width(functionKeyWidth).fillMaxHeight().testTag("handwriting-side-keys")) {
                    // 「按键符号」的手写侧栏，三格。
                    (listOf("delete") + SideSymbolTarget.HANDWRITING.resolve(sideSymbols)).forEach { action ->
                        HandwritingFunctionKey(action, { press(action) },
                            if (action == "delete") specialKeyBackgroundColor else keyBackgroundColor,
                            if (action == "delete") specialKeyTextColor else keyTextColor,
                            Modifier.weight(1f).fillMaxWidth(), onClear = { press("clear_all") }, onUndo = { press("undo_clear") })
                    }
                }
            }
            val reportGlassRegion = HandwritingGlassRegion.reporter
            androidx.compose.runtime.DisposableEffect(expanded, reportGlassRegion) {
                if (!expanded) reportGlassRegion?.invoke(null)
                onDispose { reportGlassRegion?.invoke(null) }
            }
            // 全屏时底下这块（候选栏 + 两排键）的底色和平时键盘一样：铺满平时键盘的整宽（含两侧留边），
            // 往下铺过底部留白接到导航栏那条；按键位置不变（11-09 用户截图：两侧和底下各漏一条，透出后面的应用）。
            val materialWidth = if (expanded) (controlsWidth + normalControlGutter.dp * 2).coerceAtMost(panelWidth) else controlsWidth
            val materialTravel = ((panelWidth - materialWidth) / 2).value.coerceAtLeast(0f)
            val materialOffset = if (expanded) expandedControlsOffsetX.toFloat().coerceIn(-materialTravel, materialTravel).dp else 0.dp
            Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopCenter).absoluteOffset(x = materialOffset).width(materialWidth)
                // Fullscreen ink stays transparent; only the saved footer bounds receive the keyboard material.
                .then(if (expanded) Modifier.keyboardPanelBackground(panelBackgroundColor)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        reportGlassRegion?.invoke(android.graphics.Rect(kotlin.math.round(pos.x).toInt(), kotlin.math.round(pos.y).toInt(),
                            kotlin.math.round(pos.x + coords.size.width).toInt(), kotlin.math.round(pos.y + coords.size.height).toInt()))
                    }
                    // Tag before the padding: the tag measures the whole material, bottom inset included.
                    .testTag("handwriting-controls-material")
                    .padding(bottom = KeyboardGridBottomMargin + bottomPaddingDp.dp) else Modifier)) {
            Column(Modifier.align(Alignment.TopCenter).absoluteOffset(x = controlsOffset - materialOffset).width(controlsWidth)
                .testTag("handwriting-controls")) {
            CompositionLocalProvider(LocalKeyboardKeyVisualMetrics provides
                if (expanded) normalControlMetrics ?: currentVisualMetrics else currentVisualMetrics,
                LocalKeyboardKeyContentScale provides if (expanded) normalControlScale ?: currentContentScale else currentContentScale) {
            if (expanded) Box(Modifier.fillMaxWidth()) { expandedCandidateBar() }
            if (expanded) {
                Row(Modifier.fillMaxWidth().height(footerHeight).background(functionRowBackground)
                    .testTag("handwriting-symbol-row")) {
                    SideSymbolTarget.HANDWRITING_ROW.resolve(rowSymbols).forEach { action ->
                        HandwritingFunctionKey(action, { press(action) }, keyBackgroundColor, keyTextColor,
                            Modifier.weight(1f).fillMaxHeight())
                    }
                    HandwritingFunctionKey("delete", { press("delete") }, specialKeyBackgroundColor,
                        specialKeyTextColor, Modifier.width(functionKeyWidth).fillMaxHeight(), onClear = { press("clear_all") },
                        onUndo = { press("undo_clear") })
                }
            }
            Row(Modifier.fillMaxWidth().height(footerHeight).background(functionRowBackground)
                .testTag("handwriting-bottom-row")) {
                // The expand control occupies QWERTY's punctuation slot, so every
                // bottom-row boundary stays in place when switching input modes.
                val cycle = LocalKeyboardInputPreferences.current.symbolKeyCycle
                val languageOnLeft = LocalKeyboardInputPreferences.current.languageKeyOnLeft
                val keys = listOfNotNull("symbol" to bottom.mode,
                    ("number" to bottom.mode).takeUnless { cycle },
                    ("ime_switch" to bottom.language).takeIf { languageOnLeft },
                    (if (expanded) "collapse" else "expand") to bottom.punctuation,
                    "space" to (bottom.space + if (cycle) bottom.mode else 0f),
                    ("ime_switch" to bottom.language).takeUnless { languageOnLeft }, "enter" to bottom.enter)
                keys.forEach { (action, weight) ->
                    val special = action != "space"
                    if (cycle && action == "symbol") KeyboardModeKey(slot = 1, onKeyPress = { press(it) },
                        backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                        modifier = Modifier.weight(weight).fillMaxHeight())
                    else HandwritingFunctionKey(action, { press(action) },
                        if (special) specialKeyBackgroundColor else keyBackgroundColor,
                        if (special) specialKeyTextColor else keyTextColor,
                        Modifier.weight(weight).fillMaxHeight(), spaceLabel = "${recognitionLanguage.displayName}手写")
                }
            }
            }
            } // constrained controls
            } // footer material
            }
        }
    }
    }
    }
}

@Composable
private fun HandwritingFunctionKey(action: String, onClick: () -> Unit, background: Color, foreground: Color, modifier: Modifier,
    onClear: (() -> Unit)? = null, onUndo: (() -> Unit)? = null, spaceLabel: String = "") {
    val label = when (action) {
        "delete" -> "删除"; "enter" -> "回车"; "space" -> "空格"
        "symbol" -> "!@#"; "number" -> "123"; "ime_switch" -> "语言切换"
        "expand" -> "全屏手写"; "collapse" -> "收起全屏手写"; else -> punctuationKeyLabel(action)
    }
    val enter = LocalEnterKeyColors.current.takeIf { action == "enter" }
    val keyBackground = enter?.background ?: background
    val keyForeground = enter?.foreground ?: foreground
    if (action == "delete") {
        SwipeableIconKeyButton(
            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
            onClick = onClick, onLongClick = onClick, onSwipeUp = onClear,
            // 和其他键盘的删除键一样有撤回（11-05 别人的评论「手写时无法撤销」）：有笔迹撤最后一笔，刚清掉的放回。
            onSwipeDown = onUndo,
            followSymbolSwipeDirection = true, deleteKey = true,
            upFeedback = KeyFlickFeedback.DELETE,
            downFeedback = KeyFlickFeedback.UNDO,
            swipeText = "清空", swipeUpLabel = "清空", swipeDownLabel = "撤回",
            backgroundColor = keyBackground, iconColor = keyForeground,
            modifier = modifier.semantics { contentDescription = label }.testTag("handwriting-key:delete"),
        )
        return
    }
    val keyModifier = modifier.semantics {
        contentDescription = label
        onClick { onClick(); true }
    }.testTag("handwriting-key:$action")
    if (action == "space") {
        // 与其他键盘同一个空格键：键面上写当前手写的语言（「中文手写」），长按手势也一样。
        Box(keyModifier) {
            SpaceKeyButton(onClick = onClick, backgroundColor = keyBackground, textColor = keyForeground,
                schemaName = spaceLabel, modifier = Modifier.fillMaxSize())
        }
        return
    }
    if (action == "ime_switch") {
        // 与其他键盘同一个语言键：点按切换，长按弹出语言菜单。
        LanguageKeyButton(icon = rememberVectorPainter(Icons.Default.Language), onClick = onClick,
            backgroundColor = keyBackground, iconColor = keyForeground, modifier = keyModifier)
        return
    }
    val icon = when (action) {
        "enter" -> Icons.AutoMirrored.Filled.KeyboardReturn
        "space" -> Icons.Default.SpaceBar
        "ime_switch" -> Icons.Default.Language
        "expand" -> Icons.Default.OpenInFull
        "collapse" -> Icons.Default.CloseFullscreen
        else -> null
    }
    if (icon != null) IconKeyButton(rememberVectorPainter(icon), onClick,
        keyBackground, keyForeground, keyModifier)
    else CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
        // Labels are resolved above; mode labels must stay !@#/123 like KeyboardModeKey.
        KeyButton(label, onClick, keyBackground, keyForeground, keyModifier,
            fontSize = (if (action == "symbol" || action == "number") 16 else 18).sp)
    }
}
