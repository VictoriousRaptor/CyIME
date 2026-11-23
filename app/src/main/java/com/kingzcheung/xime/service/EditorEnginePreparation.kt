package com.kingzcheung.xime.service

/** Runs in the input FIFO before this editor's first key, never on the UI thread. */
internal fun prepareEditorEngine(
    currentSchema: String,
    targetSchema: String,
    switchSchema: (String) -> Boolean,
    clearComposition: () -> Unit,
    restoreOptions: () -> Unit,
): Boolean {
    // Re-selecting an unchanged schema rebuilds translators and their dictionaries.
    if (currentSchema != targetSchema && !switchSchema(targetSchema)) return false
    clearComposition()
    restoreOptions()
    return true
}

/** A clipboard notification cannot replace an unfinished word or a key awaiting delivery. */
internal fun canShowClipboardSuggestions(composing: Boolean, pendingEnglish: String, inputPending: Boolean) =
    !composing && pendingEnglish.isEmpty() && !inputPending

/** 实体键盘 Ctrl+V（带不带 Shift 都算）交给应用粘贴；按住不放的重复不是又一次粘贴，Alt、Win 组合另有用处。 */
internal fun isHardwarePasteShortcut(keyCode: Int, ctrl: Boolean, alt: Boolean, meta: Boolean, repeatCount: Int) =
    keyCode == android.view.KeyEvent.KEYCODE_V && ctrl && !alt && !meta && repeatCount == 0

/**
 * 没接实体键盘时收到带 Ctrl / Alt / Meta 的按键：是手势工具、自动化、无障碍服务注入的 Ctrl+C / V / A / X / Z 这类编辑快捷键
 * （用户 11-21：CyIME 弹出时 XposedEdge 手势里的复制、粘贴等编辑功能没反应，其他输入法正常）。
 * 原来只在接了实体键盘时才把这种键交给应用，没接时 Ctrl+C 的 C 被当成字母打进编码、按键被吞。现在一律交给应用。
 */
internal fun passesInjectedShortcutToApp(hardwareKeyboard: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean) =
    !hardwareKeyboard && (ctrl || alt || meta)
