package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.VoiceBandwidth
import kotlin.math.roundToInt

// ---- volume / quality sliders ----

@Composable
internal fun InputVolumeSlider(volume: Int, onVolumeChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.microphone_volume, volume),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = volume.toFloat(),
            onValueChange = { onVolumeChange(it.roundToInt()) },
            valueRange = 0f..200f,
            steps = 39,
        )
    }
}

@Composable
internal fun OutputVolumeSlider(volume: Int, onVolumeChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.incoming_volume, volume),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = volume.toFloat(),
            onValueChange = { onVolumeChange(it.roundToInt()) },
            valueRange = 0f..200f,
            steps = 39,
        )
    }
}

@Composable
internal fun TransmitQualitySlider(
    quality: Int,
    framesPerPacket: Int,
    tcpMode: Boolean,
    onQualityChange: (Int) -> Unit,
) {
    val min = VoiceBandwidth.QUALITY_MIN_KBPS
    val max = VoiceBandwidth.QUALITY_MAX_KBPS
    val clamped = VoiceBandwidth.clampQualityKbps(quality)
    val usage = remember(clamped, framesPerPacket, tcpMode) {
        VoiceBandwidth.peakUsage(
            bitrate = clamped * 1000,
            frames = framesPerPacket.coerceIn(1, 6),
            tcpMode = tcpMode,
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.transmit_quality, clamped),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = clamped.toFloat(),
            onValueChange = { onQualityChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
        )
        Text(
            stringResource(
                R.string.transmit_bandwidth,
                usage.totalBps / 1000.0,
                usage.audioBps / 1000.0,
                usage.positionBps / 1000.0,
                usage.overheadBps / 1000.0,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AgcMaxGainSlider(maxGainDb: Int, onMaxGainChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.agc_max_gain, maxGainDb),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = maxGainDb.toFloat(),
            onValueChange = { onMaxGainChange(it.roundToInt()) },
            valueRange = 5f..60f,
            steps = 10,
        )
    }
}

@Composable
internal fun NoiseLevelSlider(level: Int, onLevelChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.suppression_level, level),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevelChange(it.roundToInt()) },
            valueRange = 0f..60f,
            steps = 11,
        )
    }
}

@Composable
internal fun VadSpeechThresholdSlider(threshold: Int, onThresholdChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.speech_threshold, threshold),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = threshold.toFloat(),
            onValueChange = { onThresholdChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

@Composable
internal fun VadSilenceThresholdSlider(threshold: Int, max: Int, onThresholdChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.silence_threshold, threshold),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = threshold.toFloat(),
            onValueChange = { onThresholdChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = 19,
        )
    }
}

@Composable
internal fun VadHoldSlider(holdMs: Int, onHoldChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.voice_hold, holdMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = holdMs.toFloat(),
            onValueChange = { onHoldChange(it.roundToInt()) },
            valueRange = 0f..500f,
            steps = 24,
        )
    }
}
