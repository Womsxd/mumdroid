package dev.woms.mumdroid.ui.screen

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget

/** Bottom bar with mute, deafen and an optional full-width PTT hold bar. */
@Composable
internal fun VoiceControlBar(
    selfMuted: Boolean,
    selfDeafened: Boolean,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
    voiceMode: VoiceMode,
    outputTarget: VoiceOutputTarget?,
    onSelectOutputTarget: (VoiceOutputTarget) -> Unit,
) {
    val connectedTargets = rememberConnectedOutputTargets()
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.clip(CircleShape),
                ) {
                    Icon(
                        if (selfMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = stringResource(if (selfMuted) R.string.unmute else R.string.mute),
                    )
                }
                FilledIconButton(
                    onClick = onToggleDeafen,
                    modifier = Modifier.clip(CircleShape),
                ) {
                    Icon(
                        if (selfDeafened) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.toggle_deafen),
                    )
                }
                OutputDeviceButton(
                    current = outputTarget,
                    connected = connectedTargets,
                    onSelect = onSelectOutputTarget,
                )
            }
            if (voiceMode == VoiceMode.PTT) {
                PushToTalkBar(onTalkStart = onTalkStart, onTalkStop = onTalkStop)
            }
        }
    }
}

/**
 * Output-device control. Two connected devices toggle; more than two opens
 * a menu of currently connected sources.
 */
@Composable
private fun OutputDeviceButton(
    current: VoiceOutputTarget?,
    connected: List<VoiceOutputTarget>,
    onSelect: (VoiceOutputTarget) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shown = current?.takeIf { it in connected } ?: connected.firstOrNull()
    Box {
        FilledIconButton(
            onClick = {
                when {
                    connected.size > 2 -> menuOpen = true
                    connected.size == 2 -> {
                        val other = connected.firstOrNull { it != shown } ?: return@FilledIconButton
                        onSelect(other)
                    }
                }
            },
            modifier = Modifier.clip(CircleShape),
        ) {
            Icon(
                (shown ?: VoiceOutputTarget.EARPIECE).icon(),
                contentDescription = stringResource(
                    (shown ?: VoiceOutputTarget.EARPIECE).labelRes(),
                ),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            connected.forEach { target ->
                DropdownMenuItem(
                    text = { Text(stringResource(target.labelRes())) },
                    onClick = {
                        onSelect(target)
                        menuOpen = false
                    },
                    leadingIcon = {
                        Icon(target.icon(), contentDescription = null)
                    },
                    trailingIcon = if (target == shown) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberConnectedOutputTargets(): List<VoiceOutputTarget> {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    var types by remember {
        mutableStateOf(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type },
        )
    }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            }
        }
        types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return remember(types) { VoiceRouteSelection.connectedTargets(types) }
}

private fun VoiceOutputTarget.icon() = when (this) {
    VoiceOutputTarget.HEADSET -> Icons.Filled.Headphones
    VoiceOutputTarget.BLUETOOTH -> Icons.Filled.Bluetooth
    VoiceOutputTarget.SPEAKER -> Icons.Filled.Speaker
    VoiceOutputTarget.EARPIECE -> Icons.Filled.PhoneInTalk
}

private fun VoiceOutputTarget.labelRes(): Int = when (this) {
    VoiceOutputTarget.HEADSET -> R.string.output_headset
    VoiceOutputTarget.BLUETOOTH -> R.string.output_bluetooth
    VoiceOutputTarget.SPEAKER -> R.string.output_speaker
    VoiceOutputTarget.EARPIECE -> R.string.output_earpiece
}

/**
 * Full-width hold-to-talk strip. Press/release come from the button's own
 * interactionSource: a second pointerInput on top of clickable steals events
 * and the hold never registers reliably.
 */
@Composable
private fun PushToTalkBar(
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
) {
    val pttInteraction = remember { MutableInteractionSource() }
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(pttInteraction) {
        pttInteraction.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (!pressing) {
                        pressing = true
                        onTalkStart()
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (pressing) {
                        pressing = false
                        onTalkStop()
                    }
                }
            }
        }
    }
    // Mode switch / leaving the screen while held must not leave the mic open.
    DisposableEffect(Unit) {
        onDispose {
            if (pressing) {
                pressing = false
                onTalkStop()
            }
        }
    }
    Button(
        onClick = { },
        interactionSource = pttInteraction,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (pressing) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Text(
            text = stringResource(R.string.push_to_talk),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
