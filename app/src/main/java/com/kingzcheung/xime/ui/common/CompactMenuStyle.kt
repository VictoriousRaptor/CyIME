package com.kingzcheung.xime.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared appearance; each host retains its own focus, anchoring and gesture rules. */
internal object CompactMenuStyle {
    val CornerRadius = 12.dp
    val Shape = RoundedCornerShape(CornerRadius)
    val MinRowHeight = 36.dp
    val FontSize = 14.sp
    val LineHeight = 20.sp
    val IconSize = 18.dp
    val HorizontalPadding = 12.dp
    val VerticalPadding = 4.dp
    val ItemGap = 8.dp
    // Material DropdownMenu supplies this same vertical content inset.
    val SurfacePadding = 8.dp
    val Elevation = 6.dp
}

@Composable
internal fun CompactMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    supportingText: String? = null,
    tint: Color = Color.Unspecified,
) {
    val base = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val foreground = base.copy(alpha = base.alpha * if (enabled) 1f else .38f)
    val interaction = if (selected == null) Modifier.clickable(enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, enabled, role = Role.RadioButton, onClick = onClick)
    Row(modifier.fillMaxWidth().heightIn(min = CompactMenuStyle.MinRowHeight).then(interaction)
        .padding(horizontal = CompactMenuStyle.HorizontalPadding, vertical = CompactMenuStyle.VerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompactMenuStyle.ItemGap)) {
        if (icon != null) Icon(icon, null, Modifier.size(CompactMenuStyle.IconSize), tint = foreground)
        Column(Modifier.weight(1f)) {
            Text(label, color = foreground, fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight)
            supportingText?.let {
                Text(it, color = foreground.copy(alpha = foreground.alpha * .72f), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (selected != null) {
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(CompactMenuStyle.IconSize), tint = foreground)
            else Spacer(Modifier.size(CompactMenuStyle.IconSize))
        }
    }
}

/** Settings menus keep Android's dropdown focus and positioning, sharing the IME menu rows. */
@Composable
internal fun CompactDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
        offset = offset, shape = CompactMenuStyle.Shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp, shadowElevation = CompactMenuStyle.Elevation, content = content)
}

@Composable
internal fun CompactMenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = CompactMenuStyle.HorizontalPadding), thickness = .5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
}
