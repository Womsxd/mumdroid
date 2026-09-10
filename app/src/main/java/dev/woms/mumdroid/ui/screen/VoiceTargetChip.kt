package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.model.VoiceTargetStatus

/**
 * Status chip for an active shout/whisper target, shown above the voice bar.
 *
 * It is deliberately always visible while a target is set (rather than a
 * transient banner): a phone user must be able to tell at a glance that their
 * next sentence will not go to the whole channel. The trailing button always
 * restores regular speech, so no target can get stuck.
 *
 * When the target lost its receivers the chip turns into an error style: the
 * client is withholding audio, which the user has to know about.
 */
@Composable
internal fun VoiceTargetChip(
    status: VoiceTargetStatus,
    onClear: () -> Unit,
) {
    if (status.isRegular) return
    val unavailable = status.isUnavailable
    val container = if (unavailable) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (unavailable) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (unavailable) {
                    Icons.Filled.ReportProblem
                } else if (status.spec is VoiceTargetSpec.Users) {
                    Icons.Filled.VisibilityOff
                } else {
                    Icons.Filled.Campaign
                },
                contentDescription = stringResource(R.string.voice_target_label),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (unavailable) stringResource(R.string.voice_target_unavailable)
                else targetLabel(status),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.voice_target_clear),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Chip text for the current target, including its links / children modifiers. */
@Composable
internal fun targetLabel(status: VoiceTargetStatus): String = when (val spec = status.spec) {
    null -> ""
    is VoiceTargetSpec.Users -> {
        val names = status.userNames.joinToString(", ")
        stringResource(R.string.voice_target_chip_whisper_many, names)
    }
    is VoiceTargetSpec.Channel -> when {
        spec.links && spec.children ->
            stringResource(R.string.voice_target_chip_shout_links_children, status.channelName)
        spec.links -> stringResource(R.string.voice_target_chip_shout_links, status.channelName)
        spec.children -> stringResource(R.string.voice_target_chip_shout_children, status.channelName)
        else -> stringResource(R.string.voice_target_chip_shout, status.channelName)
    }
}
