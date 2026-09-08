package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import kotlin.math.roundToInt

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
