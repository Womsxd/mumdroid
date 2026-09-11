package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * One level of the context menus: a row that opens a second menu next to itself.
 *
 * Both the user and the channel long-press menus nest their long lists (move,
 * moderation, voice targets) behind an entry like this so the phone-sized menu
 * stays short. The submenu is a plain [DropdownMenu] anchored inside a [Box]:
 * Material has no first-class nested menu, and positioning it at a fixed
 * [DpOffset] keeps the submenu from covering its own parent entry.
 */
@Composable
internal fun NestedDropdownMenu(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(168.dp, 0.dp),
            content = content,
        )
    }
}
