package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.audio.MicLevelMeter
import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoicePlaybackMode

// ---- Audio & Voice (merged) ----

@Composable
internal fun AudioSettingsScreen(
    settings: AppSettings,
    onChanged: (AppSettings) -> Unit,
    modifier: Modifier,
) {
    // In VAD mode the microphone permission is requested automatically so the
    // user can tune the VAD thresholds. In continuous/PTT modes there is no
    // need for a permanent level bar — it can be activated manually instead.
    val context = androidx.compose.ui.platform.LocalContext.current
    val micReady = remember { mutableStateOf(false) }
    val manualMeter = remember { mutableStateOf(false) }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Re-trigger the level meter once permission is granted so recording
        // starts right away.
        micReady.value = granted
        if (granted && settings.voiceMode != VoiceMode.VAD) {
            manualMeter.value = true
        }
    }
    LaunchedEffect(settings.voiceMode) {
        if (settings.voiceMode == VoiceMode.VAD) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            micReady.value = granted
            manualMeter.value = false
            if (!granted) {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        } else {
            micReady.value = false
            manualMeter.value = false
        }
    }

    // Independent microphone capture for a live level meter. It reads the mic
    // directly and reports the level of the *processed* audio (denoise when
    // enabled, AGC when enabled, and the manual microphone input gain) so it
    // matches the audio that would actually be transmitted, mirroring the
    // capture pipeline used when connected. This lets the user tune the VAD
    // thresholds / gain without needing a server connection.
    //
    // VAD mode: always on (the thresholds need a live level). Continuous/PTT:
    // opt-in via the toggle button below.
    fun hasMicPermission() = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val vadActive = settings.voiceMode == VoiceMode.VAD
    val capture = micReady.value &&
        (vadActive || manualMeter.value)
    var liveLevel by remember { mutableIntStateOf(0) }
    // Restart the meter whenever the audio processing settings it reflects
    // change, so the live level reacts to toggles/sliders in real time.
    DisposableEffect(
        capture,
        settings.noiseSuppressionEnabled,
        settings.noiseSuppressionMode,
        settings.noiseSuppressionDb,
        settings.agcEnabled,
        settings.agcMode,
        settings.agcMaxGainDb,
        settings.aecEnabled,
        settings.aecMode,
        settings.micSource,
        settings.inputVolume,
    ) {
        val meter = if (capture) {
            MicLevelMeter(
                onLevel = { level ->
                    if (capture) liveLevel = level
                },
                noiseSuppressionEnabled = settings.noiseSuppressionEnabled,
                noiseSuppressionMode = settings.noiseSuppressionMode,
                noiseSuppressionDb = settings.noiseSuppressionDb,
                agcMode = settings.agcMode,
                agcEnabled = settings.agcEnabled,
                agcMaxGainDb = settings.agcMaxGainDb,
                aecMode = settings.aecMode,
                aecEnabled = settings.aecEnabled,
                micSource = settings.micSource,
                inputVolume = settings.inputVolume,
            ).apply { start() }
        } else {
            null
        }
        if (meter == null) liveLevel = 0
        onDispose {
            meter?.stop()
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.sec_transmission))
        VoiceModeDropdown(
            mode = settings.voiceMode,
            onModeChange = { onChanged(settings.copy(voiceMode = it)) },
        )

        if (settings.voiceMode == VoiceMode.VAD) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.sec_voice_activity_detection))
            VadMethodDropdown(
                method = settings.vadMethod,
                onMethodChange = { onChanged(settings.copy(vadMethod = it)) },
            )
            // Live level meter to help tune the thresholds, mirroring the
            // desktop client's VAD level indicator.
            VadLevelMeter(
                level = liveLevel,
                speechThreshold = settings.vadSpeechThreshold,
                silenceThreshold = settings.vadSilenceThreshold,
                connected = capture,
            )
            VadSpeechThresholdSlider(
                threshold = settings.vadSpeechThreshold,
                onThresholdChange = {
                    onChanged(settings.copy(vadSpeechThreshold = it))
                },
            )
            VadSilenceThresholdSlider(
                threshold = settings.vadSilenceThreshold,
                max = settings.vadSpeechThreshold,
                onThresholdChange = {
                    onChanged(settings.copy(vadSilenceThreshold = it))
                },
            )
            VadHoldSlider(
                holdMs = settings.vadHoldFrames * 20,
                onHoldChange = { ms ->
                    onChanged(settings.copy(vadHoldFrames = (ms / 20).coerceAtLeast(0)))
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_mic_processing))

        MicSourceDropdown(
            source = settings.micSource,
            onSourceChange = { onChanged(settings.copy(micSource = it)) },
        )

        // In non-VAD modes the level meter is opt-in: a manual toggle that
        // opens the mic so the processing settings can be verified by ear/eye.
        if (!vadActive) {
            val meterActive = capture
            Button(
                onClick = {
                    if (meterActive) {
                        manualMeter.value = false
                    } else if (hasMicPermission()) {
                        micReady.value = true
                        manualMeter.value = true
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    stringResource(
                        if (meterActive) R.string.mic_meter_stop else R.string.mic_meter_start,
                    ),
                )
            }
            if (meterActive) {
                VadLevelMeter(
                    level = liveLevel,
                    speechThreshold = 0,
                    silenceThreshold = 0,
                    connected = true,
                )
            }
        }

        if (settings.micSource == MicSource.VOICE_COMMUNICATION) {
            Text(
                stringResource(R.string.vc_processing_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            SwitchRow(
                title = stringResource(R.string.echo_cancellation),
                subtitle = stringResource(R.string.echo_cancellation_sub),
                checked = settings.aecEnabled,
                onCheckedChange = { onChanged(settings.copy(aecEnabled = it)) },
            )

            if (settings.aecEnabled) {
                AecModeDropdown(
                    mode = settings.aecMode,
                    allowSystem = true,
                    onModeChange = { onChanged(settings.copy(aecMode = it)) },
                )
                if (settings.anyMediaPlayback()) {
                    Text(
                        stringResource(R.string.aec_media_software_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SwitchRow(
                title = stringResource(R.string.automatic_gain_control),
                subtitle = stringResource(R.string.agc_sub),
                checked = settings.agcEnabled,
                onCheckedChange = { onChanged(settings.copy(agcEnabled = it)) },
            )

            if (settings.agcEnabled) {
                AgcModeDropdown(
                    mode = settings.agcMode,
                    onModeChange = { onChanged(settings.copy(agcMode = it)) },
                )

                if (settings.agcMode == AgcMode.SPEEX) {
                    AgcMaxGainSlider(
                        maxGainDb = settings.agcMaxGainDb,
                        onMaxGainChange = { onChanged(settings.copy(agcMaxGainDb = it)) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SwitchRow(
                title = stringResource(R.string.noise_suppression),
                subtitle = stringResource(R.string.noise_suppression_sub),
                checked = settings.noiseSuppressionEnabled,
                onCheckedChange = { onChanged(settings.copy(noiseSuppressionEnabled = it)) },
            )

            if (settings.noiseSuppressionEnabled) {
                NoiseModeDropdown(
                    mode = settings.noiseSuppressionMode,
                    enabled = true,
                    onModeChange = { onChanged(settings.copy(noiseSuppressionMode = it)) },
                )

                // The suppression level only applies to the Speex software stage.
                if (settings.noiseSuppressionMode == NoiseSuppressionMode.SPEEX ||
                    settings.noiseSuppressionMode == NoiseSuppressionMode.SPEEX_RNNOISE
                ) {
                    NoiseLevelSlider(
                        level = settings.noiseSuppressionDb,
                        onLevelChange = { onChanged(settings.copy(noiseSuppressionDb = it)) },
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_input_codec))

        InputVolumeSlider(
            volume = settings.inputVolume,
            onVolumeChange = { onChanged(settings.copy(inputVolume = it)) },
        )

        TransmitQualitySlider(
            quality = settings.transmitQuality,
            framesPerPacket = settings.framesPerPacket,
            tcpMode = settings.forceTcp,
            onQualityChange = {
                onChanged(settings.copy(transmitQuality = VoiceBandwidth.clampQualityKbps(it)))
            },
        )

        AudioPerPacketDropdown(
            framesPerPacket = settings.framesPerPacket,
            onFramesPerPacketChange = { onChanged(settings.copy(framesPerPacket = it)) },
        )

        SwitchRow(
            title = stringResource(R.string.low_latency_mode),
            subtitle = stringResource(R.string.low_latency_sub),
            checked = settings.lowLatency,
            onCheckedChange = { onChanged(settings.copy(lowLatency = it)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_output))

        if (settings.micSource == MicSource.MIC) {
            VoicePlaybackModeDropdown(
                mode = settings.voicePlaybackMode,
                onModeChange = { onChanged(settings.copy(voicePlaybackMode = it)) },
            )
        }

        OutputDeviceOrderList(
            order = settings.outputDeviceOrder,
            onOrderChange = { onChanged(settings.copy(outputDeviceOrder = it)) },
        )

        OutputVolumeSlider(
            volume = settings.outputVolume,
            onVolumeChange = { onChanged(settings.copy(outputVolume = it)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_behaviour))
        SwitchRow(
            title = stringResource(R.string.half_duplex),
            subtitle = stringResource(R.string.half_duplex_sub),
            checked = settings.halfDuplex,
            onCheckedChange = { onChanged(settings.copy(halfDuplex = it)) },
        )
    }
}
