package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R

// ---- VAD level meter ----

/**
 * A live voice-activity level meter. The filled bar reflects the current input
 * level (0..100) while the markers show the silence (lower) and speech (upper)
 * thresholds, mirroring the desktop client's VAD tuning meter.
 */
@Composable
internal fun VadLevelMeter(
    level: Int,
    speechThreshold: Int,
    silenceThreshold: Int,
    connected: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.live_level, level),
            style = MaterialTheme.typography.bodyMedium,
        )
        val errorColor = MaterialTheme.colorScheme.error
        val tertiaryColor = MaterialTheme.colorScheme.tertiary
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
        ) {
            // Filled level up to the current value.
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Silence threshold marker.
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val w = size.width
                val h = size.height
                drawLine(
                    color = errorColor,
                    start = androidx.compose.ui.geometry.Offset(w * silenceThreshold / 100f, 0f),
                    end = androidx.compose.ui.geometry.Offset(w * silenceThreshold / 100f, h),
                    strokeWidth = 2.dp.toPx(),
                )
                drawLine(
                    color = tertiaryColor,
                    start = androidx.compose.ui.geometry.Offset(w * speechThreshold / 100f, 0f),
                    end = androidx.compose.ui.geometry.Offset(w * speechThreshold / 100f, h),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.silence) + "\n$silenceThreshold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.speech) + "\n$speechThreshold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!connected) {
            Text(
                stringResource(R.string.vad_live_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

