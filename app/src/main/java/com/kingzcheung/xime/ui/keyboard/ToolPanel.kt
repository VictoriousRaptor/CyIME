package com.kingzcheung.xime.ui.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.service.ToolPanelEditTextHolder

internal val TOOL_PANEL_HEIGHT = 170

/** The same tool panel used as the clipboard search field: one line, no status row. */
internal const val CLIPBOARD_SEARCH_PANEL_HEIGHT = 96

/** Clipboard search on a short screen (landscape): close button and field share one row. */
internal const val CLIPBOARD_SEARCH_INLINE_PANEL_HEIGHT = 48

/** 翻译栏（display: live）：一行，和横屏剪贴板搜索栏一样高。 */
internal const val TOOL_PANEL_BAR_HEIGHT = 48

/** 屏幕矮（手机横屏）时的 AI 工具面板：关闭钮和输入框一行，不显示状态行，和横屏剪贴板搜索栏一样高。 */
internal const val TOOL_PANEL_COMPACT_HEIGHT = CLIPBOARD_SEARCH_INLINE_PANEL_HEIGHT

/**
 * 工具面板在键盘上方撑出的高度；窗口高度和面板绘制用同一个数。PASSIVE 走全屏覆盖，不撑高。
 * [compact]：完整面板放不下（[toolPanelCompact]）时用一行的面板。
 */
internal fun toolPanelExtraHeight(visible: Boolean, display: String?, compact: Boolean = false): Int = when {
    !visible || display == "PASSIVE" -> 0
    display == "LIVE" -> TOOL_PANEL_BAR_HEIGHT
    compact -> TOOL_PANEL_COMPACT_HEIGHT
    else -> TOOL_PANEL_HEIGHT
}

/**
 * 键盘加上完整的工具面板超出能用的高度 [available]（屏幕减去底部留白、悬浮时减去卡片下面那截）：
 * 手机横屏就是这样。以前横屏、悬浮一律不开面板（提示「不支持」），下载的翻译、AI 插件在横屏用不了（用户 11-12）；
 * 现在改用一行的面板，窗口不超出屏幕。
 */
internal fun toolPanelCompact(keyboardHeight: Int, available: Int): Boolean =
    keyboardHeight + TOOL_PANEL_HEIGHT > available

/**
 * AI 工具插件输入面板（候选栏上方）。
 *
 * 仅承载输入与生成触发：输入框（预填上下文，键盘按键路由可注入）+ 生成状态提示。
 * 生成结果不再显示在此面板，多条结果由 passive 面板（InfoPanel 内 items 点选上屏）承载。
 */
@Composable
fun ToolPanel(
    title: String,
    isFocused: Boolean,
    isLoading: Boolean = false,
    initialText: String = "",
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    cardBgColor: Color,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    heightDp: Int = TOOL_PANEL_HEIGHT,
    hint: String = "输入上下文或指令",
    /** Null keeps the generate hint; an empty string hides the status row. */
    statusText: String? = null,
    singleLine: Boolean = false,
    onTextChange: ((String) -> Unit)? = null,
    /** Close button and field on one row, no title (see [CandidateBarOverlayPanel]). */
    inline: Boolean = false,
) {
    val closeButtonBg = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )

    CandidateBarOverlayPanel(
        heightDp = heightDp,
        backgroundColor = backgroundColor,
        cardBgColor = cardBgColor,
        closeButtonBg = closeButtonBg,
        closeButtonColor = accentColor,
        title = title,
        titleColor = textColor,
        modifier = modifier,
        onCloseClick = onClose,
        inline = inline,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    toolPanelEditText(context, textColor, hint, singleLine, isFocused, onFocusChange, onTextChange)
                },
                update = { editText -> syncToolPanelEditText(editText, isFocused, initialText) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (singleLine) 36.dp else 52.dp),
            )

            if (statusText != "") Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusText ?: if (isLoading) "生成中..." else "输入上下文后按回车生成",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 面板输入框：键盘按键经 [ToolPanelEditTextHolder] 打进来。 */
private fun toolPanelEditText(
    context: android.content.Context,
    textColor: Color,
    hint: String,
    singleLine: Boolean,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onTextChange: ((String) -> Unit)?,
    keepFocusOnBlur: Boolean = false,
): android.widget.EditText = android.widget.EditText(context).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    setTextColor(textColor.hashCode())
    setHintTextColor((textColor.copy(alpha = 0.4f)).hashCode())
    this.hint = hint
    textSize = 16f
    isSingleLine = singleLine
    gravity = Gravity.TOP or Gravity.START
    setPadding(4, 2, 4, 2)

    setImeActionLabel("生成", EditorInfo.IME_ACTION_DONE)
    imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
        EditorInfo.IME_ACTION_DONE

    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
        // 翻译栏：框在键盘窗口里丢了焦点（键盘重排之类）不算离开，离开只看用户点了应用输入框。
        if (hasFocus || !keepFocusOnBlur) onFocusChange(hasFocus)
    }
    setOnClickListener { onFocusChange(true) }
    onTextChange?.let { change ->
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { change(s?.toString().orEmpty()) }
        })
    }
    ToolPanelEditTextHolder.editText = this
    if (isFocused) {
        post { requestFocus() }
    }
}

private fun syncToolPanelEditText(editText: android.widget.EditText, isFocused: Boolean, initialText: String) {
    // Something else took view focus (the toolbar's layout switch rebuilds the keyboard
    // under the search field): give it back so the caret stays where keys go.
    if (isFocused && !editText.hasFocus()) editText.post { editText.requestFocus() }
    if (initialText.isNotEmpty() && !editText.text.toString().equals(initialText)) {
        editText.setText(initialText)
        editText.setSelection(initialText.length)
    }
}

/**
 * 翻译栏（插件 `display: live`，样子照三星键盘的翻译）：左边圆钮（插件给的第一个按钮，
 * 如目标语言「EN」），中间一行输入框，右边关闭。打的字进这一行，结果由宿主写进应用输入框。
 */
@Composable
fun ToolPanelBar(
    chipLabel: String?,
    hint: String,
    isFocused: Boolean,
    isLoading: Boolean,
    textColor: Color,
    accentColor: Color,
    cardBgColor: Color,
    onChipClick: () -> Unit,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 插件给的语言选择（源语言、目标语言）：画成「源 ⇄ 目标」，点哪边就弹哪边的列表。 */
    selects: List<com.kingzcheung.xime.plugin.core.config.UiNode> = emptyList(),
    /** 两个选择中间画对调箭头。 */
    showSwap: Boolean = false,
    onSelect: (key: String, option: String) -> Unit = { _, _ -> },
    onSwap: () -> Unit = {},
) {
    // 圆钮用按键底色和按键字色，跟着键盘主题走（原来取应用主题的浅紫，深色下白字看不清）
    val circleBg = cardBgColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOOL_PANEL_BAR_HEIGHT.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chipLabel != null) {
            // 触摸 36dp，和关闭按钮一样大
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .clickable(onClick = onChipClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = chipLabel,
                    color = textColor,
                    fontSize = if (chipLabel.length > 2) 11.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (selects.isNotEmpty()) {
            LiveBarLanguages(selects, showSwap, textColor, circleBg, onSelect, onSwap)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(cardBgColor.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                factory = { context ->
                    toolPanelEditText(context, textColor, hint, true, isFocused, onFocusChange, onTextChange,
                        keepFocusOnBlur = true)
                        .apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
                },
                update = { editText ->
                    syncToolPanelEditText(editText, isFocused, "")
                    // 打字去了应用输入框：栏里不显示光标，看得出字不会进栏；点一下栏又回来。
                    if (editText.isCursorVisible != isFocused) editText.isCursorVisible = isFocused
                    if (editText.hint?.toString() != hint) editText.hint = hint
                },
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterEnd).size(14.dp),
                    color = textColor.copy(alpha = 0.5f),
                    strokeWidth = 1.5.dp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 翻译栏左边的「自动检测 ⇄ 英文」：点左边选原文语言，点右边选目标语言，中间箭头两边对调。
 * 每边一个 36dp 高的胶囊，和圆钮、关闭按钮一样高。
 */
@Composable
private fun LiveBarLanguages(
    selects: List<com.kingzcheung.xime.plugin.core.config.UiNode>,
    showSwap: Boolean,
    textColor: Color,
    pillColor: Color,
    onSelect: (key: String, option: String) -> Unit,
    onSwap: () -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        selects.forEachIndexed { index, node ->
            val key = node.key ?: return@forEachIndexed
            if (index == 1 && showSwap) {
                Box(
                    Modifier.size(28.dp, 36.dp).testTag("live-bar-swap")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSwap),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⇄", color = textColor.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1)
                }
            } else if (index == 1) {
                Spacer(Modifier.width(4.dp))
            }
            Box(
                Modifier.height(36.dp).widthIn(max = 84.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(pillColor)
                    .testTag("live-bar-lang:$key")
                    .clickable { open = key }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(node.value ?: node.defaultValue.orEmpty(), color = textColor, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (open == key) {
                    OptionMenuPopup(node.label.orEmpty(), node.options, node.value,
                        onChoose = { option -> open = null; if (option != node.value) onSelect(key, option) },
                        onDismiss = { open = null })
                }
            }
        }
    }
}
