package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CommentsDisabled
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.core.model.UserStatusIcon

/**
 * One mute/deafen/priority badge of a user row. The icon, string and palette
 * slot come from [UserRowPresentation]; this composable only maps them onto
 * Compose, so the ordering and tint matrix stay unit-testable.
 */
@Composable
internal fun UserStatusBadge(status: UserStatusIcon) {
    val style = UserRowPresentation.badge(status)
    val image: ImageVector = when (status) {
        UserStatusIcon.PRIORITY_SPEAKER -> Icons.Filled.Campaign
        UserStatusIcon.SERVER_MUTE,
        UserStatusIcon.SUPPRESS,
        UserStatusIcon.SELF_MUTE,
        UserStatusIcon.LOCAL_MUTE,
        // Material Icons "Comments Disabled" is the chat-bubble-off glyph.
        -> Icons.Filled.MicOff
        UserStatusIcon.LOCAL_IGNORE -> Icons.Filled.CommentsDisabled
        UserStatusIcon.SERVER_DEAF,
        UserStatusIcon.SELF_DEAF,
        -> Icons.AutoMirrored.Filled.VolumeOff
    }
    Icon(
        imageVector = image,
        contentDescription = stringResource(style.descRes),
        tint = UserRowColors.of(style.tint, MaterialTheme.colorScheme.primary),
        modifier = Modifier.padding(start = 6.dp),
    )
}
