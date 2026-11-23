package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch

/**
 * 设置页预览键盘盖住的高度。预览显示时设置列表铺到键盘后面，固定键盘两侧镂空处透出列表，和在别的应用里一样；
 * 列表末尾留出这么高，最后几项仍能滚到键盘上方。键盘收起、显示悬浮「显示键盘」按钮时为
 * [SHOW_KEYBOARD_BUTTON_SPACE]；没有预览的页为 0。
 */
internal val LocalKeyboardPreviewInset = compositionLocalOf { 0.dp }

/**
 * 试打框是否开着。列表页据此在打开时记住滚动位置，关掉后放回去（11-08 反馈：主题与外观页试打后返回，
 * 位置跳到下面约一屏。关试打框时焦点落到下面的设置项上，列表跟着滚过去了）。
 */
internal val LocalTypingTrialOpen = compositionLocalOf { false }

/** 悬浮「显示键盘」按钮离页面底边的距离。 */
internal val SHOW_KEYBOARD_BUTTON_MARGIN = 12.dp

/** 有悬浮「显示键盘」按钮时列表末尾空出的高度：按钮 48dp、上下各 12dp，最后一项能滚到按钮上方。 */
internal val SHOW_KEYBOARD_BUTTON_SPACE = 48.dp + SHOW_KEYBOARD_BUTTON_MARGIN * 2

/** 设置列表的内边距：原有的上下边距，再加上预览键盘盖住的那段（或悬浮「显示键盘」按钮占的那段）。 */
@Composable
internal fun keyboardPreviewContentPadding(top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    PaddingValues(top = top, bottom = bottom + LocalKeyboardPreviewInset.current)

/** 给真实输入法提供窗口连接，没有文本缓冲区，也不会把 Enter、Tab 等键转发给设置页。 */
internal class StylePreviewEditor(context: Context) : View(context) {
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var pendingShow = false
    private var panel: KeyboardPanelLaunch.Panel? = null
    private var editorOption = KeyboardPanelLaunch.PREVIEW_EDITOR
    private var connectedOption: String? = null
    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(info: EditorInfo): BaseInputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        info.privateImeOptions = editorOption
        connectedOption = editorOption
        info.initialSelStart = 0
        info.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            override fun getEditable(): Editable? = null
            override fun sendKeyEvent(event: KeyEvent) = true
            override fun performEditorAction(actionCode: Int) = true
            override fun performContextMenuAction(id: Int) = true
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (!event.isSystem && event.keyCode != KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)

    private val showIme = Runnable {
        if (!pendingShow || !isAttachedToWindow || !hasWindowFocus()) return@Runnable
        pendingShow = false
        val tool = panel
        if (tool != null) KeyboardPanelLaunch.request(tool, this) else KeyboardPanelLaunch.cancel(this)
        isFocusableInTouchMode = true
        val reconnect = isFocused && connectedOption != editorOption
        if (requestFocus()) {
            if (reconnect) imm?.restartInput(this)
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    fun showPreview(tool: KeyboardPanelLaunch.Panel? = null, example: KeyboardPanelLaunch.Example = KeyboardPanelLaunch.Example.Keyboard) {
        panel = tool
        editorOption = if (tool == null) example.option else KeyboardPanelLaunch.editorOption(tool)
        pendingShow = true
        removeCallbacks(showIme)
        post(showIme)
    }
    /** 试打框接手键盘：焦点已在真实输入框上，这里只撤掉待弹出和面板请求，不收起键盘。 */
    fun handOff() {
        pendingShow = false
        removeCallbacks(showIme)
        KeyboardPanelLaunch.cancel(this)
        KeyboardPanelLaunch.typingRequested = false
        isFocusable = false
    }
    fun hidePreview() {
        pendingShow = false
        removeCallbacks(showIme)
        KeyboardPanelLaunch.cancel(this)
        KeyboardPanelLaunch.typingRequested = false
        if (isFocused) {
            imm?.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
        }
        isFocusable = false
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pendingShow) { removeCallbacks(showIme); post(showIme) }
    }
    override fun onDetachedFromWindow() {
        hidePreview()
        super.onDetachedFromWindow()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardSettingsScaffold(
    title: String, onBack: () -> Unit, preview: Boolean,
    editPanel: KeyboardPanelLaunch.Panel? = null, example: KeyboardPanelLaunch.Example = KeyboardPanelLaunch.Example.Keyboard,
    modifier: Modifier = Modifier,
    /** 标题栏右侧的页面操作（如「按键符号」的更多菜单），排在「收起键盘」前面。 */
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val imm = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    var selected by remember(context) { mutableStateOf(isCyimeSelected(context)) }
    var enabled by remember(context) { mutableStateOf(false) }
    var editor by remember { mutableStateOf<StylePreviewEditor?>(null) }
    // 进页面不自动弹键盘（11-01「进个页面就弹出键盘 什么设置都看不到」）：点「显示键盘」才弹，
    // 或在尺寸／排列页点「调整尺寸」「编辑排列」时打开对应面板。
    var autoShow by rememberSaveable { mutableStateOf(false) }
    // 试打框：半透明盖在设置页上，真实输入框接收键盘输入（11-01：预览键盘点不了，改成能直接打字）。
    var typing by remember { mutableStateOf(false) }
    if (preview) {
        DisposableEffect(context) {
            fun refresh() {
                selected = isCyimeSelected(context)
                enabled = isCyimeEnabled(context)
            }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            }
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
            refresh()
            onDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
        LaunchedEffect(editor, selected, example, typing) {
            if (typing) return@LaunchedEffect
            if (selected && autoShow) editor?.showPreview(example = example) else editor?.hidePreview()
        }
        // 点了只读预览键盘：直接进试打框。
        val typingRequested = KeyboardPanelLaunch.typingRequested
        LaunchedEffect(typingRequested) {
            if (!typingRequested) return@LaunchedEffect
            KeyboardPanelLaunch.typingRequested = false
            if (selected) typing = true
        }
    }
    val visible = preview && selected && KeyboardPanelLaunch.previewVisible
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var trialOwnsIme by remember { mutableStateOf(false) }
    var previewOwnsIme by remember { mutableStateOf(false) }
    LaunchedEffect(typing, imeBottom) {
        if (typing) trialOwnsIme = true
        else if (imeBottom == 0) trialOwnsIme = false
    }
    LaunchedEffect(visible, imeBottom) {
        if (visible) previewOwnsIme = true
        else if (imeBottom == 0) previewOwnsIme = false
    }
    Box(modifier) {
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            actions()
            if (visible) TextButton(onClick = { autoShow = false; editor?.hidePreview() }) { Text("收起键盘") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { padding ->
        // 预览键盘显示时页面不再整体顶到键盘上方（原来两侧镂空处只剩一块纯色底，11-01 平板截图）：
        // 列表铺到键盘后面，末尾留出键盘那段。真实输入框弹出的键盘照旧把页面顶上去。
        val density = LocalDensity.current
        val keyboardInset = if (visible || previewOwnsIme) with(density) {
            (WindowInsets.ime.getBottom(this).toDp() - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
        } else 0.dp
        // 「显示键盘」做成悬浮按钮（11-03「两边不能站满 应该做成浮动按钮」）：列表铺到页面底边，两侧露出设置；
        // 列表末尾空出按钮的高度，滚到底最后一项在按钮上方。
        val floatingButton = preview && !visible && !previewOwnsIme
        val listEndInset = if (preview) maxOf(keyboardInset, SHOW_KEYBOARD_BUTTON_SPACE) else keyboardInset
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .then(if (preview && !visible && !previewOwnsIme && !typing && !trialOwnsIme) Modifier.imePadding() else Modifier)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
              CompositionLocalProvider(LocalKeyboardPreviewInset provides listEndInset, LocalTypingTrialOpen provides typing) {
                if (editPanel == null) content(Modifier.fillMaxSize())
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = listEndInset)) {
                    content(Modifier.fillMaxWidth())
                    if (selected) OutlinedButton(
                        onClick = { autoShow = true; editor?.showPreview(editPanel) },
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                    ) { Text(if (editPanel == KeyboardPanelLaunch.Panel.RESIZE) "调整尺寸" else "编辑排列") }
                }
              }
              if (floatingButton) {
                FilledTonalButton(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SHOW_KEYBOARD_BUTTON_MARGIN)
                        .fillMaxWidth(0.54f).widthIn(max = 280.dp).heightIn(min = 48.dp).testTag("show-keyboard-button"),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface),
                    // 浮在列表上面：带一点阴影，和后面滚过的设置分得开。
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp, pressedElevation = 3.dp),
                    onClick = {
                        // 已选中 CyIME：直接打开试打框，键盘弹出就能打字。
                        when {
                            selected -> typing = true
                            enabled -> imm?.showInputMethodPicker()
                            else -> context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }
                        autoShow = true
                    },
                ) { Text(if (selected) "显示键盘" else if (enabled) "选择 CyIME" else "启用 CyIME") }
              }
            }
        }
        if (preview) AndroidView(
            factory = { StylePreviewEditor(it).also { view -> editor = view } },
            modifier = Modifier.padding(padding).size(1.dp),
            onRelease = { it.hidePreview() },
        )
    }
    if (typing) TypingTrialOverlay(onReady = { editor?.handOff() }, onClose = { typing = false; autoShow = false })
    }
}

/**
 * 设置页试打：纯效果展示（11-01「不要剪贴板这种，直接在屏幕上显示文字，下面半透明后面设置背景，随时返回关闭清空」）。
 * 整屏一层半透明底，后面的设置隐约可见；打的字直接以大字号显示在键盘上方，没有输入框、标题和按钮。
 * 返回键、返回手势或收起键盘都会关闭，关闭即清空，下次打开是空的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingTrialOverlay(onReady: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val imm = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    var field by remember { mutableStateOf<EditText?>(null) }
    val close: () -> Unit = {
        field?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        onClose()
    }
    val currentOnClose by rememberUpdatedState(onClose)
    DisposableEffect(Unit) {
        val dismiss = { field?.clearFocus(); currentOnClose() }
        KeyboardPanelLaunch.closeTypingTrial = dismiss
        onDispose { if (KeyboardPanelLaunch.closeTypingTrial === dismiss) KeyboardPanelLaunch.closeTypingTrial = null }
    }
    // 键盘没弹出时的返回。
    BackHandler(onBack = close)
    // 系统返回手势先收起输入法；动画目标变为零时就关闭，别等动画结束后的可见性通知。
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val imeSource = WindowInsets.imeAnimationSource.getBottom(density)
    val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, imeSource, imeTarget) {
        // A newly opened trial can overlap the previous IME hide animation.
        // Do not treat that old animation as this trial being dismissed.
        if (imeVisible && imeTarget > 0) imeShown = true
        if (imeShown && ((!imeVisible) || (imeSource > 0 && imeTarget == 0))) {
            field?.clearFocus()
            onClose()
        }
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Box(Modifier.fillMaxSize().testTag("typing-trial")
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
        AndroidView(
            factory = { ctx ->
                object : EditText(ctx) {
                    // 返回键在输入法之前先到这里：键盘开着也是按一次就关闭并清空。
                    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event)
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
                        return true
                    }
                }.apply {
                    tag = "typing-trial-field"
                    background = null
                    setPadding(0, 0, 0, 0)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    gravity = Gravity.CENTER
                    isVerticalScrollBarEnabled = false
                    // 只是展示：不弹复制、粘贴菜单。
                    isLongClickable = false
                    val noMenu = object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                        override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                    }
                    customSelectionActionModeCallback = noMenu
                    customInsertionActionModeCallback = noMenu
                    // 挂到窗口后再拿焦点：键盘从只读预览换成这里，不先收起再弹出。
                    post {
                        requestFocus()
                        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        onReady()
                    }
                }.also { field = it }
            },
            update = { it.setTextColor(textColor) },
            // 文字区就是键盘上方的整块空间，点哪里都是继续打字。
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.systemBars))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}
