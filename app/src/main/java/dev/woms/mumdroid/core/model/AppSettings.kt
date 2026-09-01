package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode

/**
 * The user-configurable application settings.
 *
 * These mirror the options exposed by the desktop Mumble client that are
 * meaningful for this mobile client: audio / noise-suppression behaviour, the
 * voice transport mode (UDP vs. force-TCP) and a few connection conveniences.
 *
 * @property noiseSuppressionEnabled whether the noise-suppression stage is
 *   active on captured audio.
 * @property noiseSuppressionMode the denoising engine (System / Speex /
 *   RNNoise / Speex+RNNoise).
 * @property noiseSuppressionDb the suppression level in dB (0..60, negative
 *   stored value maps to -0..-60).
 * @property agcEnabled whether automatic gain control is active.
 * @property agcMode the automatic gain control backend: [AgcMode.SYSTEM]
 *   (Android's AutomaticGainControl effect on the mic session) or
 *   [AgcMode.SPEEX] (the speexdsp built-in AGC, as used by the desktop client).
 * @property agcMaxGainDb the maximal gain in dB the speexdsp AGC may apply
 *   (only relevant when [agcMode] is [AgcMode.SPEEX]; desktop derives ~30 dB
 *   from its loudness setting).
 * @property forceTcp when true, voice is sent over the TCP control channel
 *   instead of the UDP voice channel.
 * @property autoReconnect whether to attempt an automatic reconnect after an
 *   unexpected drop.
 * @property defaultUsername username pre-filled for the connect dialog.
 * @property certificatePinning when true, the server certificate fingerprint
 *   captured on the first connection is pinned for subsequent connections.
 * @property inputVolume manual input gain applied to captured audio (0..200 %,
 *   100 = unity), mirroring mumla's `inputVolume`.
 * @property transmitQuality the Opus encode bitrate, expressed in kilobits per
 *   second (8..192 kbps) and shown to the user as such, mirroring the desktop
 *   Mumble quality slider (`qsQuality` 8000..192000, default 40000 bps = 40
 *   kbps). The stored value is converted to bits-per-second with a *1000
 *   factor.
 * @property framesPerPacket the number of 10 ms Opus frames bundled into one
 *   packet, mirroring the desktop Mumble "Audio per packet" selector
 *   (1=10 ms, 2=20 ms, 4=40 ms, 6=60 ms).
 * @property lowLatency when true and the transmit quality is high enough, the
 *   Opus encoder is switched to the restricted-low-delay application to shave
 *   end-to-end latency, mirroring the desktop "Low latency mode" checkbox.
 * @property aecEnabled whether echo cancellation is active ([MicSource.MIC]
 *   only).
 * @property aecMode the echo cancellation implementation: [AecMode.SYSTEM]
 *   (platform AcousticEchoCanceler effect) or [AecMode.SPEEX] (software
 *   AEC against the speaker reference via speexdsp).
 * @property voicePlaybackMode communication vs media path shared by headset,
 *   Bluetooth and loudspeaker. Ignored when [micSource] is not [MicSource.MIC].
 *   The earpiece is always communication.
 * @property outputDeviceOrder join-time priority among
 *   [VoiceOutputTarget] values. The first currently connected target is used.
 * @property outputVolume the volume of incoming speech (0..200 %, 100 = unity),
 *   mirroring the desktop "Volume of incoming speech".
 * @property halfDuplex when true, incoming audio is muted while the local user
 *   is transmitting, mirroring mumla's `half_duplex`.
 * @property theme the UI colour theme (system / light / dark).
 * @property showUserCount whether to display the number of users per channel in
 *   the channel list, mirroring mumla's `show_user_count`.
 * @property stayAwake keeps the screen on while connected, mirroring mumla's
 *   `stay_awake`.
 * @property earpieceProximityFade when true (the default), the earpiece
 *   proximity blank fades in and out; when false it snaps off and on like
 *   a phone call. Proximity blanking itself is always on in earpiece mode.
 * @property chatNotifications whether to emit a notification for incoming text
 *   messages, mirroring mumla's `chatNotify`.
 * @property qualityOfService when true, the voice socket is marked for
 *   low-latency prioritisation, mirroring the desktop "Quality of Service".
 * @property autoServerPing when true, the home server list re-sends the
 *   unconnected UDP ping on a timer (off by default; official ConnectDialog
 *   always ticks, but a phone should not do that unless the user asks).
 * @property serverPingIntervalSeconds how often [autoServerPing] repeats
 *   (5..60, step 5).
 * @property language the UI language preference (system / English /
 *   Simplified Chinese). When set to [AppLanguage.SYSTEM] the app follows the
 *   device language.
 * @property voiceMode how transmission is triggered: continuous (always on),
 *   voice-activated (VAD) or push-to-talk, mirroring the desktop Mumble
 *   "Transmission" selector.
 * @property micSource the microphone audio source: [MicSource.MIC] applies the
 *   individual AEC / AGC / noise-suppression settings, while
 *   [MicSource.VOICE_COMMUNICATION] lets the platform handle all three at once
 *   (individual options then have no effect).
 * @property vadMethod how voice activity is measured when [voiceMode] is
 *   [VoiceMode.VAD], mirroring the desktop Mumble "Voice detection method":
 *   [VadMethod.AMPLITUDE] (mic level) or [VadMethod.SIGNAL_TO_NOISE] (SNR).
 * @property vadSpeechThreshold the level above which a frame is treated as
 *   speech (0..100), mirroring the desktop `fVADmax`.
 * @property vadSilenceThreshold the level below which a frame is treated as
 *   silence while not currently speaking (0..100, hysteresis), mirroring the
 *   desktop `fVADmin`. Must be <= [vadSpeechThreshold].
 * @property vadHoldFrames how many frames (20 ms each) the mic stays open after
 *   speech stops, mirroring the desktop "Voice hold".
 */
data class AppSettings(
    val noiseSuppressionEnabled: Boolean = true,
    val noiseSuppressionMode: NoiseSuppressionMode = NoiseSuppressionMode.SPEEX,
    val noiseSuppressionDb: Int = 15,
    val agcMode: AgcMode = AgcMode.SPEEX,
    val agcMaxGainDb: Int = 30,
    val agcEnabled: Boolean = true,
    val forceTcp: Boolean = false,
    val autoReconnect: Boolean = false,
    val defaultUsername: String = "",
    val certificatePinning: Boolean = true,
    val inputVolume: Int = 100,
    val transmitQuality: Int = 40,
    val framesPerPacket: Int = 2,
    val lowLatency: Boolean = false,
    val vadMethod: VadMethod = VadMethod.AMPLITUDE,
    val vadSpeechThreshold: Int = 98,
    val vadSilenceThreshold: Int = 80,
    val vadHoldFrames: Int = 20,
    val aecMode: AecMode = AecMode.SYSTEM,
    val aecEnabled: Boolean = false,
    val micSource: MicSource = MicSource.MIC,
    val voicePlaybackMode: VoicePlaybackMode = VoicePlaybackMode.COMMUNICATION,
    val outputDeviceOrder: List<VoiceOutputTarget> = VoiceOutputTarget.DEFAULT_ORDER,
    val outputVolume: Int = 100,
    val halfDuplex: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    val showUserCount: Boolean = false,
    val stayAwake: Boolean = false,
    val earpieceProximityFade: Boolean = true,
    val chatNotifications: Boolean = true,
    val qualityOfService: Boolean = false,
    val autoServerPing: Boolean = false,
    val serverPingIntervalSeconds: Int = SERVER_PING_INTERVAL_DEFAULT_SEC,
    val voiceMode: VoiceMode = VoiceMode.CONTINUOUS,
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    companion object {
        const val SERVER_PING_INTERVAL_MIN_SEC = 5
        const val SERVER_PING_INTERVAL_MAX_SEC = 60
        const val SERVER_PING_INTERVAL_STEP_SEC = 5
        const val SERVER_PING_INTERVAL_DEFAULT_SEC = 15

        fun clampServerPingIntervalSeconds(seconds: Int): Int {
            val step = SERVER_PING_INTERVAL_STEP_SEC
            val rounded = ((seconds + step / 2) / step) * step
            return rounded.coerceIn(SERVER_PING_INTERVAL_MIN_SEC, SERVER_PING_INTERVAL_MAX_SEC)
        }
    }

    /**
     * Playback path for [target]. Headset, Bluetooth and speaker share
     * [voicePlaybackMode]. Voice-communication mic source always stays on
     * the telephony route. The earpiece is communication-only.
     */
    fun playbackModeFor(target: VoiceOutputTarget): VoicePlaybackMode {
        if (micSource != MicSource.MIC) return VoicePlaybackMode.COMMUNICATION
        if (target == VoiceOutputTarget.EARPIECE) return VoicePlaybackMode.COMMUNICATION
        return voicePlaybackMode
    }

    fun usesMediaPlayback(target: VoiceOutputTarget): Boolean =
        playbackModeFor(target) == VoicePlaybackMode.MEDIA

    /** True when the shared path is media (earpiece still stays in-call). */
    fun anyMediaPlayback(): Boolean =
        micSource == MicSource.MIC && voicePlaybackMode == VoicePlaybackMode.MEDIA

    /**
     * AEC backend actually used for [target]. Media playback cannot use
     * the platform canceller — it only sees the voice mix.
     */
    fun effectiveAecMode(target: VoiceOutputTarget? = null): AecMode {
        val media = target != null && usesMediaPlayback(target)
        return if (media && aecMode == AecMode.SYSTEM) AecMode.SPEEX else aecMode
    }

    /** Normalizes the output-device order. */
    fun sanitized(): AppSettings {
        val order = VoiceOutputTarget.normalize(outputDeviceOrder)
        return if (order == outputDeviceOrder) this else copy(outputDeviceOrder = order)
    }
}

/**
 * Automatic gain control implementations ([AppSettings.agcEnabled] only):
 *
 *  - [SYSTEM]: Android's platform AutomaticGainControl effect attached to the
 *    microphone session (hardware/driver level where available).
 *  - [SPEEX]: the speexdsp pre-processor's built-in AGC with the desktop
 *    client's parameters (target 30000, max gain ~30 dB).
 */
enum class AgcMode {
    SYSTEM,
    SPEEX,
}

/**
 * Echo cancellation implementations ([AppSettings.aecEnabled] only):
 *
 *  - [SYSTEM]: platform AcousticEchoCanceler effect on the mic session.
 *  - [SPEEX]: software AEC against the speaker reference signal
 *    ([MediaRecorder.AudioSource.MIC]) using the speexdsp echo canceller.
 */
enum class AecMode {
    SYSTEM,
    SPEEX,
}

/**
 * The microphone audio source:
 *
 *  - [MIC]: plain microphone; the echo cancellation / gain control / noise
 *    suppression settings below apply individually.
 *  - [VOICE_COMMUNICATION]: the platform's voice-communication source, which
 *    handles AEC / AGC / noise suppression automatically. The individual
 *    processing options are not adjustable in this mode.
 */
enum class MicSource {
    MIC,
    VOICE_COMMUNICATION,
}

/**
 * Where incoming voice is mixed:
 *
 *  - [COMMUNICATION]: `USAGE_VOICE_COMMUNICATION` + in-call routing
 *    (earpiece / speakerphone / SCO). Required for platform AEC.
 *  - [MEDIA]: `USAGE_MEDIA` + normal routing (loudspeaker / A2DP). Shared by
 *    headset, Bluetooth and speaker when [MicSource.MIC] is selected; echo
 *    cancellation must be software. The earpiece cannot use media.
 */
enum class VoicePlaybackMode {
    COMMUNICATION,
    MEDIA,
}

/**
 * The UI language preference: [SYSTEM] follows the device language, while
 * [ENGLISH] and [CHINESE] force a specific language regardless of the device.
 */
enum class AppLanguage {
    /** Follow the device / system language. */
    SYSTEM,

    /** Force English. */
    ENGLISH,

    /** Force Simplified Chinese. */
    CHINESE,
}

/** The UI colour theme preference, mirroring mumla's `theme` option. */
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * How the voice-activity detector measures speech, mirroring the desktop
 * Mumble client's `VADSource` (see `src/mumble/Settings.h`):
 *
 *  - [AMPLITUDE]: base the decision on the clean microphone level.
 *  - [SIGNAL_TO_NOISE]: base the decision on the signal-to-noise ratio / the
 *    pre-processor's speech probability.
 */
enum class VadMethod {
    /** Base the voice decision on the (clean) microphone amplitude. */
    AMPLITUDE,

    /** Base the voice decision on the signal-to-noise ratio / speech probability. */
    SIGNAL_TO_NOISE,
}

/**
 * How microphone transmission is triggered, mirroring the desktop Mumble
 * client's "Transmission" selection.
 *
 *  - [CONTINUOUS]: always transmit while connected (unless muted/deafened).
 *  - [VAD]: transmit only while voice activity is detected on the input.
 *  - [PTT]: transmit only while the push-to-talk button is held down.
 */
enum class VoiceMode {
    /** Always transmit while connected (the default). */
    CONTINUOUS,

    /** Transmit while the voice-activity detector hears speech. */
    VAD,

    /** Transmit only while the talk button is held. */
    PTT,
}
