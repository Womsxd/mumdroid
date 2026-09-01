package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VadMethod
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoicePlaybackMode
import kotlin.math.roundToInt

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

// ---- output device order ----

@Composable
internal fun OutputDeviceOrderList(
    order: List<VoiceOutputTarget>,
    onOrderChange: (List<VoiceOutputTarget>) -> Unit,
) {
    val items = VoiceOutputTarget.normalize(order)
    var showDialog by remember { mutableStateOf(false) }
    val labels = mapOf(
        VoiceOutputTarget.HEADSET to stringResource(R.string.output_headset),
        VoiceOutputTarget.BLUETOOTH to stringResource(R.string.output_bluetooth),
        VoiceOutputTarget.SPEAKER to stringResource(R.string.output_speaker),
        VoiceOutputTarget.EARPIECE to stringResource(R.string.output_earpiece),
    )
    val summary = items.joinToString(" → ") { labels.getValue(it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.output_device_order),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.output_device_order_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        OutputDeviceOrderDialog(
            order = items,
            onConfirm = { next ->
                onOrderChange(next)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun OutputDeviceOrderDialog(
    order: List<VoiceOutputTarget>,
    onConfirm: (List<VoiceOutputTarget>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(order) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val itemHeight = 56.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.output_device_order)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.output_device_order_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    stringResource(R.string.output_device_order_drag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                draft.forEachIndexed { index, target ->
                    key(target) {
                        val indexHolder = remember { mutableIntStateOf(index) }
                        indexHolder.intValue = index
                        val isDragging = draggingIndex == index
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset {
                                    IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0)
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingIndex = indexHolder.intValue
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val from = draggingIndex ?: return@detectDragGestures
                                            dragOffsetY += amount.y
                                            val to = (from + (dragOffsetY / itemHeightPx).roundToInt())
                                                .coerceIn(draft.indices)
                                            if (to != from) {
                                                draft = VoiceOutputTarget.move(draft, from, to - from)
                                                draggingIndex = to
                                                dragOffsetY -= (to - from) * itemHeightPx
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove,
                                                )
                                            }
                                        },
                                    )
                                },
                            color = if (isDragging) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                            contentColor = if (isDragging) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            shape = MaterialTheme.shapes.small,
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(itemHeight)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = stringResource(R.string.output_reorder_handle),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                                Text(
                                    "${index + 1}. ${stringResource(target.labelRes())}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(draft) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun VoiceOutputTarget.labelRes(): Int = when (this) {
    VoiceOutputTarget.HEADSET -> R.string.output_headset
    VoiceOutputTarget.BLUETOOTH -> R.string.output_bluetooth
    VoiceOutputTarget.SPEAKER -> R.string.output_speaker
    VoiceOutputTarget.EARPIECE -> R.string.output_earpiece
}
