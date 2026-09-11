package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.User

/**
 * The row content of a user: the talk/listen indicator, the name and the
 * status badges. Split out of [UserRow] so the argument list there stays
 * about behaviour, not about label styling.
 */
@Composable
internal fun RowScope.UserRowLabel(user: User) {
    val showTalking = UserRowPresentation.showsTalking(user)
    val talkingColor = UserRowPresentation.talkColor(user, MaterialTheme.colorScheme.primary)
    if (user.isChannelListener) {
        // A listener never speaks through the proxy, so the talk indicator is
        // replaced by a fixed "listening" glyph.
        Icon(
            Icons.Filled.Hearing,
            contentDescription = stringResource(R.string.channel_listener),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(18.dp),
        )
    } else {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(UserRowPresentation.talkDescription(user)),
            tint = if (showTalking) talkingColor
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .padding(end = 6.dp)
                .size(18.dp),
        )
    }
    Text(
        text = user.name,
        fontWeight = if (user.isLocalUser) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (user.isChannelListener) FontStyle.Italic else FontStyle.Normal,
        style = MaterialTheme.typography.bodyMedium,
        color = if (UserRowPresentation.isNameDimmed(user)) Color.Gray else Color.Unspecified,
        modifier = Modifier.weight(1f),
    )
    // Status icons after the name, left-to-right like desktop
    // UserModel::data (first declared = closest to the name).
    for (status in user.visibleStatusIcons()) {
        UserStatusBadge(status)
    }
}
