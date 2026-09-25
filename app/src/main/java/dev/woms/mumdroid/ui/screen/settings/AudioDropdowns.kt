package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.OpusImplementation
import dev.woms.mumdroid.core.model.VadMethod
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoicePlaybackMode

// ---- dropdowns ----
//
// Thin wrappers over EnumDropdown: each names its field, its entries and how a
// value reads, so the screen keeps calling them by setting name.

@Composable
internal fun NoiseModeDropdown(
    mode: NoiseSuppressionMode,
    enabled: Boolean,
    onModeChange: (NoiseSuppressionMode) -> Unit,
) {
    EnumDropdown(
        value = mode,
        entries = NoiseSuppressionMode.entries,
        label = stringResource(R.string.denoise_engine),
        enabled = enabled,
        onValueChange = onModeChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun NoiseSuppressionMode.displayName(): String = when (this) {
    NoiseSuppressionMode.SYSTEM -> stringResource(R.string.ns_system)
    NoiseSuppressionMode.SPEEX -> "Speex"
    NoiseSuppressionMode.RNNOISE -> "RNNoise"
    NoiseSuppressionMode.SPEEX_RNNOISE -> "Speex + RNNoise"
}

@Composable
internal fun VadMethodDropdown(
    method: VadMethod,
    onMethodChange: (VadMethod) -> Unit,
) {
    EnumDropdown(
        value = method,
        entries = VadMethod.entries,
        label = stringResource(R.string.detection_method),
        onValueChange = onMethodChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun VadMethod.displayName(): String = when (this) {
    VadMethod.AMPLITUDE -> stringResource(R.string.method_amplitude)
    VadMethod.SIGNAL_TO_NOISE -> stringResource(R.string.method_snr)
}

@Composable
internal fun VoiceModeDropdown(
    mode: VoiceMode,
    onModeChange: (VoiceMode) -> Unit,
) {
    EnumDropdown(
        value = mode,
        entries = VoiceMode.entries,
        label = stringResource(R.string.voice_mode),
        onValueChange = onModeChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun VoiceMode.displayName(): String = when (this) {
    VoiceMode.CONTINUOUS -> stringResource(R.string.voice_continuous)
    VoiceMode.VAD -> stringResource(R.string.voice_vad)
    VoiceMode.PTT -> stringResource(R.string.voice_ptt)
}

@Composable
internal fun VoicePlaybackModeDropdown(
    mode: VoicePlaybackMode,
    onModeChange: (VoicePlaybackMode) -> Unit,
) {
    EnumDropdown(
        value = mode,
        entries = VoicePlaybackMode.entries,
        label = stringResource(R.string.voice_playback_mode),
        onValueChange = onModeChange,
        labelOf = { it.displayName() },
        supporting = stringResource(R.string.voice_playback_mode_sub),
    )
}

@Composable
internal fun VoicePlaybackMode.displayName(): String = when (this) {
    VoicePlaybackMode.COMMUNICATION -> stringResource(R.string.voice_playback_communication)
    VoicePlaybackMode.MEDIA -> stringResource(R.string.voice_playback_media)
}

@Composable
internal fun AgcModeDropdown(mode: AgcMode, onModeChange: (AgcMode) -> Unit) {
    EnumDropdown(
        value = mode,
        entries = AgcMode.entries,
        label = stringResource(R.string.automatic_gain_control),
        onValueChange = onModeChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun AgcMode.displayName(): String = when (this) {
    AgcMode.SYSTEM -> stringResource(R.string.agc_system)
    AgcMode.SPEEX -> stringResource(R.string.agc_speex)
}

@Composable
internal fun AecModeDropdown(
    mode: AecMode,
    allowSystem: Boolean = true,
    onModeChange: (AecMode) -> Unit,
) {
    EnumDropdown(
        value = mode,
        entries = AecMode.entries.filter { allowSystem || it != AecMode.SYSTEM },
        label = stringResource(R.string.echo_cancellation),
        onValueChange = onModeChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun AecMode.displayName(): String = when (this) {
    AecMode.SYSTEM -> stringResource(R.string.aec_system)
    AecMode.SPEEX -> stringResource(R.string.aec_speex)
}

@Composable
internal fun MicSourceDropdown(source: MicSource, onSourceChange: (MicSource) -> Unit) {
    EnumDropdown(
        value = source,
        entries = MicSource.entries,
        label = stringResource(R.string.mic_source),
        onValueChange = onSourceChange,
        labelOf = { it.displayName() },
    )
}

@Composable
internal fun MicSource.displayName(): String = when (this) {
    MicSource.MIC -> stringResource(R.string.mic_source_mic)
    MicSource.VOICE_COMMUNICATION -> stringResource(R.string.mic_source_vc)
}

/** The frame sizes the codec accepts, shown as the packet duration they give. */
private val AUDIO_PER_PACKET_CHOICES = listOf(1, 2, 4, 6)

@Composable
internal fun AudioPerPacketDropdown(
    framesPerPacket: Int,
    onFramesPerPacketChange: (Int) -> Unit,
) {
    EnumDropdown(
        value = framesPerPacket,
        entries = AUDIO_PER_PACKET_CHOICES,
        label = stringResource(R.string.audio_per_packet),
        onValueChange = onFramesPerPacketChange,
        labelOf = { "${it * 10} ms" },
    )
}

@Composable
internal fun OpusImplementationDropdown(
    implementation: OpusImplementation,
    onImplementationChange: (OpusImplementation) -> Unit,
) {
    EnumDropdown(
        value = implementation,
        entries = OpusImplementation.entries,
        label = stringResource(R.string.opus_implementation),
        onValueChange = onImplementationChange,
        labelOf = { it.displayName() },
        supporting = stringResource(R.string.opus_implementation_sub),
    )
}

@Composable
internal fun OpusImplementation.displayName(): String = when (this) {
    OpusImplementation.CONCENTUS -> stringResource(R.string.opus_implementation_concentus)
    OpusImplementation.LIBOPUS -> stringResource(R.string.opus_implementation_libopus)
}
