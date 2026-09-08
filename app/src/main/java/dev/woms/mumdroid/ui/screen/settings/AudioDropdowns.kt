package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VadMethod
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoicePlaybackMode

// ---- dropdowns ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoiseModeDropdown(
    mode: NoiseSuppressionMode,
    enabled: Boolean,
    onModeChange: (NoiseSuppressionMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = mode.displayName(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.denoise_engine)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NoiseSuppressionMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onModeChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun NoiseSuppressionMode.displayName(): String = when (this) {
    NoiseSuppressionMode.SYSTEM -> stringResource(R.string.ns_system)
    NoiseSuppressionMode.SPEEX -> "Speex"
    NoiseSuppressionMode.RNNOISE -> "RNNoise"
    NoiseSuppressionMode.SPEEX_RNNOISE -> "Speex + RNNoise"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VadMethodDropdown(
    method: VadMethod,
    onMethodChange: (VadMethod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = method.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.detection_method)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VadMethod.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onMethodChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun VadMethod.displayName(): String = when (this) {
    VadMethod.AMPLITUDE -> stringResource(R.string.method_amplitude)
    VadMethod.SIGNAL_TO_NOISE -> stringResource(R.string.method_snr)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceModeDropdown(
    mode: VoiceMode,
    onModeChange: (VoiceMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = mode.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.voice_mode)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VoiceMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onModeChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun VoiceMode.displayName(): String = when (this) {
    VoiceMode.CONTINUOUS -> stringResource(R.string.voice_continuous)
    VoiceMode.VAD -> stringResource(R.string.voice_vad)
    VoiceMode.PTT -> stringResource(R.string.voice_ptt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePlaybackModeDropdown(
    mode: VoicePlaybackMode,
    onModeChange: (VoicePlaybackMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = mode.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.voice_playback_mode)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VoicePlaybackMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onModeChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
    Text(
        stringResource(R.string.voice_playback_mode_sub),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun VoicePlaybackMode.displayName(): String = when (this) {
    VoicePlaybackMode.COMMUNICATION -> stringResource(R.string.voice_playback_communication)
    VoicePlaybackMode.MEDIA -> stringResource(R.string.voice_playback_media)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgcModeDropdown(mode: AgcMode, onModeChange: (AgcMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = mode.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.automatic_gain_control)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AgcMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onModeChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AgcMode.displayName(): String = when (this) {
    AgcMode.SYSTEM -> stringResource(R.string.agc_system)
    AgcMode.SPEEX -> stringResource(R.string.agc_speex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AecModeDropdown(
    mode: AecMode,
    allowSystem: Boolean = true,
    onModeChange: (AecMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = mode.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.echo_cancellation)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AecMode.entries.filter { allowSystem || it != AecMode.SYSTEM }.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName()) },
                    onClick = {
                        onModeChange(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AecMode.displayName(): String = when (this) {
    AecMode.SYSTEM -> stringResource(R.string.aec_system)
    AecMode.SPEEX -> stringResource(R.string.aec_speex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicSourceDropdown(source: MicSource, onSourceChange: (MicSource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = source.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.mic_source)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MicSource.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.displayName()) },
                    onClick = {
                        onSourceChange(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun MicSource.displayName(): String = when (this) {
    MicSource.MIC -> stringResource(R.string.mic_source_mic)
    MicSource.VOICE_COMMUNICATION -> stringResource(R.string.mic_source_vc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPerPacketDropdown(
    framesPerPacket: Int,
    onFramesPerPacketChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val entries = listOf(
        1 to "10 ms",
        2 to "20 ms",
        4 to "40 ms",
        6 to "60 ms",
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = entries.firstOrNull { it.first == framesPerPacket }?.second ?: "${framesPerPacket * 10} ms",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.audio_per_packet)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onFramesPerPacketChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

