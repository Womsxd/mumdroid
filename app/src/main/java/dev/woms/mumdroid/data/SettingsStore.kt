package dev.woms.mumdroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.woms.mumdroid.core.audio.VoiceBandwidth
import dev.woms.mumdroid.core.audio.noise.NoiseSuppressionMode
import dev.woms.mumdroid.core.model.AppLanguage
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AgcMode
import dev.woms.mumdroid.core.model.MicSource
import dev.woms.mumdroid.core.model.AppTheme
import dev.woms.mumdroid.core.model.VadMethod
import dev.woms.mumdroid.core.model.VoiceMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoicePlaybackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persists the user-configurable [AppSettings] in a dedicated DataStore.
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_NOISE_ENABLED = booleanPreferencesKey("noise_suppression_enabled")
        private val KEY_NOISE_MODE = stringPreferencesKey("noise_suppression_mode")
        private val KEY_NOISE_DB = intPreferencesKey("noise_suppression_db")
        private val KEY_AGC_MODE = stringPreferencesKey("agc_mode")
        private val KEY_AGC_ENABLED = booleanPreferencesKey("agc_enabled")
        private val KEY_AGC_MAX_GAIN = intPreferencesKey("agc_max_gain_db")
        private val KEY_FORCE_TCP = booleanPreferencesKey("force_tcp")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_DEFAULT_USERNAME = stringPreferencesKey("default_username")
        private val KEY_CERT_PINNING = booleanPreferencesKey("certificate_pinning")
        private val KEY_INPUT_VOLUME = intPreferencesKey("input_volume")
        private val KEY_INPUT_BITRATE = intPreferencesKey("input_bitrate")
        private val KEY_FRAMES_PER_PACKET = intPreferencesKey("frames_per_packet")
        private val KEY_LOW_LATENCY = booleanPreferencesKey("low_latency")
        private val KEY_VAD_METHOD = stringPreferencesKey("vad_method")
        private val KEY_VAD_SPEECH_THRESHOLD = intPreferencesKey("vad_speech_threshold")
        private val KEY_VAD_SILENCE_THRESHOLD = intPreferencesKey("vad_silence_threshold")
        private val KEY_VAD_HOLD_FRAMES = intPreferencesKey("vad_hold_frames")
        private val KEY_AEC_MODE = stringPreferencesKey("aec_mode")
        private val KEY_AEC_ENABLED = booleanPreferencesKey("aec_enabled")
        private val KEY_MIC_SOURCE = stringPreferencesKey("mic_source")
        private val KEY_VOICE_PLAYBACK_MODE = stringPreferencesKey("voice_playback_mode")
        private val KEY_OUTPUT_DEVICE_ORDER = stringPreferencesKey("output_device_order")
        private val KEY_SPEAKER_OUTPUT = booleanPreferencesKey("speaker_output")
        private val KEY_OUTPUT_VOLUME = intPreferencesKey("output_volume")
        private val KEY_HALF_DUPLEX = booleanPreferencesKey("half_duplex")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_SHOW_USER_COUNT = booleanPreferencesKey("show_user_count")
        private val KEY_STAY_AWAKE = booleanPreferencesKey("stay_awake")
        private val KEY_EARPIECE_PROXIMITY_FADE = booleanPreferencesKey("earpiece_proximity_fade")
        private val KEY_CHAT_NOTIFICATIONS = booleanPreferencesKey("chat_notifications")
        private val KEY_QOS = booleanPreferencesKey("qos")
        private val KEY_AUTO_SERVER_PING = booleanPreferencesKey("auto_server_ping")
        private val KEY_SERVER_PING_INTERVAL = intPreferencesKey("server_ping_interval_seconds")
        private val KEY_VOICE_MODE = stringPreferencesKey("voice_mode")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        // Dropped: UDP-unusable is process-only now. Wipe leftovers on save.
        private val KEY_UDP_DISABLED_LEGACY = stringSetPreferencesKey("udp_disabled_servers")
        // Dropped: last channel now lives on the Room server row.
        private val KEY_LAST_CHANNELS_LEGACY = stringSetPreferencesKey("last_channels")
        // Dropped: channel passwords now live in Room.
        private val KEY_ACCESS_TOKENS_LEGACY = stringSetPreferencesKey("access_tokens")
    }

    data class LegacyLastChannel(
        val host: String,
        val port: Int,
        val id: Int,
        val name: String,
    ) {
        companion object {
            /** Parses a leftover tab-separated `host:port`, channel id, name row. */
            fun parse(raw: String): LegacyLastChannel? {
                val parts = raw.split('\t', limit = 3)
                if (parts.size < 2) return null
                val hostPort = parts[0]
                val colon = hostPort.lastIndexOf(':')
                if (colon <= 0) return null
                val host = hostPort.substring(0, colon)
                val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
                val id = parts[1].toIntOrNull() ?: return null
                return LegacyLastChannel(host, port, id, parts.getOrElse(2) { "" })
            }
        }
    }

    private val store = context.settingsDataStore

    /** Emits the current settings with defaults applied. */
    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            noiseSuppressionEnabled = prefs[KEY_NOISE_ENABLED] ?: true,
            noiseSuppressionMode = parseMode(prefs[KEY_NOISE_MODE]),
            noiseSuppressionDb = prefs[KEY_NOISE_DB] ?: 15,
            agcEnabled = prefs[KEY_AGC_ENABLED] ?: true,
            agcMode = parseEnum(prefs[KEY_AGC_MODE], AgcMode.SPEEX),
            agcMaxGainDb = prefs[KEY_AGC_MAX_GAIN] ?: 30,
            forceTcp = prefs[KEY_FORCE_TCP] ?: false,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: false,
            defaultUsername = prefs[KEY_DEFAULT_USERNAME] ?: "",
            certificatePinning = prefs[KEY_CERT_PINNING] ?: true,
            inputVolume = prefs[KEY_INPUT_VOLUME] ?: 100,
            transmitQuality = VoiceBandwidth.clampQualityKbps(prefs[KEY_INPUT_BITRATE] ?: 40),
            framesPerPacket = prefs[KEY_FRAMES_PER_PACKET] ?: 2,
            lowLatency = prefs[KEY_LOW_LATENCY] ?: false,
            vadMethod = parseVadMethod(prefs[KEY_VAD_METHOD]),
            vadSpeechThreshold = prefs[KEY_VAD_SPEECH_THRESHOLD] ?: 98,
            vadSilenceThreshold = prefs[KEY_VAD_SILENCE_THRESHOLD] ?: 80,
            vadHoldFrames = prefs[KEY_VAD_HOLD_FRAMES] ?: 20,
            aecEnabled = prefs[KEY_AEC_ENABLED] ?: false,
            aecMode = parseEnum(prefs[KEY_AEC_MODE], AecMode.SYSTEM),
            micSource = parseEnum(prefs[KEY_MIC_SOURCE], MicSource.MIC),
            voicePlaybackMode = parseEnum(
                prefs[KEY_VOICE_PLAYBACK_MODE],
                VoicePlaybackMode.COMMUNICATION,
            ),
            outputDeviceOrder = parseOutputOrder(
                prefs[KEY_OUTPUT_DEVICE_ORDER],
                prefs[KEY_SPEAKER_OUTPUT] ?: false,
            ),
            outputVolume = prefs[KEY_OUTPUT_VOLUME] ?: 100,
            halfDuplex = prefs[KEY_HALF_DUPLEX] ?: false,
            theme = parseTheme(prefs[KEY_THEME]),
            showUserCount = prefs[KEY_SHOW_USER_COUNT] ?: false,
            stayAwake = prefs[KEY_STAY_AWAKE] ?: false,
            earpieceProximityFade = prefs[KEY_EARPIECE_PROXIMITY_FADE] ?: true,
            chatNotifications = prefs[KEY_CHAT_NOTIFICATIONS] ?: true,
            qualityOfService = prefs[KEY_QOS] ?: false,
            autoServerPing = prefs[KEY_AUTO_SERVER_PING] ?: false,
            serverPingIntervalSeconds = AppSettings.clampServerPingIntervalSeconds(
                prefs[KEY_SERVER_PING_INTERVAL] ?: AppSettings.SERVER_PING_INTERVAL_DEFAULT_SEC,
            ),
            voiceMode = parseVoiceMode(prefs[KEY_VOICE_MODE]),
            language = parseLanguage(prefs[KEY_LANGUAGE]),
        ).sanitized()
    }

    /** Drops leftover DataStore channel-password keys; they now live in Room. */
    suspend fun wipeLegacyAccessTokens() {
        store.edit { prefs -> prefs.remove(KEY_ACCESS_TOKENS_LEGACY) }
    }

    /**
     * Reads leftover `host:port → last channel` rows from DataStore and
     * deletes the key. Callers copy them onto the matching Room server row.
     */
    suspend fun consumeLegacyLastChannels(): List<LegacyLastChannel> {
        val entries = store.data.first()[KEY_LAST_CHANNELS_LEGACY] ?: emptySet()
        val parsed = entries.mapNotNull(LegacyLastChannel::parse)
        if (entries.isNotEmpty()) {
            store.edit { prefs -> prefs.remove(KEY_LAST_CHANNELS_LEGACY) }
        }
        return parsed
    }

    /** Persists a full settings snapshot. */
    suspend fun update(settings: AppSettings) {
        val saved = settings.sanitized()
        store.edit { prefs ->
            prefs[KEY_NOISE_ENABLED] = settings.noiseSuppressionEnabled
            prefs[KEY_NOISE_MODE] = settings.noiseSuppressionMode.name
            prefs[KEY_NOISE_DB] = settings.noiseSuppressionDb
            prefs[KEY_AGC_ENABLED] = settings.agcEnabled
            prefs[KEY_AGC_MODE] = settings.agcMode.name
            prefs[KEY_AGC_MAX_GAIN] = settings.agcMaxGainDb
            prefs[KEY_FORCE_TCP] = settings.forceTcp
            prefs[KEY_AUTO_RECONNECT] = settings.autoReconnect
            prefs[KEY_DEFAULT_USERNAME] = settings.defaultUsername
            prefs[KEY_CERT_PINNING] = settings.certificatePinning
            prefs[KEY_INPUT_VOLUME] = settings.inputVolume
            prefs[KEY_INPUT_BITRATE] = VoiceBandwidth.clampQualityKbps(settings.transmitQuality)
            prefs[KEY_FRAMES_PER_PACKET] = settings.framesPerPacket
            prefs[KEY_LOW_LATENCY] = settings.lowLatency
            prefs[KEY_VAD_METHOD] = settings.vadMethod.name
            prefs[KEY_VAD_SPEECH_THRESHOLD] = settings.vadSpeechThreshold
            prefs[KEY_VAD_SILENCE_THRESHOLD] = settings.vadSilenceThreshold
            prefs[KEY_VAD_HOLD_FRAMES] = settings.vadHoldFrames
            prefs[KEY_AEC_ENABLED] = saved.aecEnabled
            prefs[KEY_AEC_MODE] = saved.aecMode.name
            prefs[KEY_MIC_SOURCE] = saved.micSource.name
            prefs[KEY_VOICE_PLAYBACK_MODE] = saved.voicePlaybackMode.name
            prefs[KEY_OUTPUT_DEVICE_ORDER] = saved.outputDeviceOrder.joinToString(",") { it.name }
            prefs[KEY_OUTPUT_VOLUME] = saved.outputVolume
            prefs[KEY_HALF_DUPLEX] = saved.halfDuplex
            prefs[KEY_THEME] = saved.theme.name
            prefs[KEY_SHOW_USER_COUNT] = saved.showUserCount
            prefs[KEY_STAY_AWAKE] = saved.stayAwake
            prefs[KEY_EARPIECE_PROXIMITY_FADE] = saved.earpieceProximityFade
            prefs[KEY_CHAT_NOTIFICATIONS] = saved.chatNotifications
            prefs[KEY_QOS] = saved.qualityOfService
            prefs[KEY_AUTO_SERVER_PING] = saved.autoServerPing
            prefs[KEY_SERVER_PING_INTERVAL] =
                AppSettings.clampServerPingIntervalSeconds(saved.serverPingIntervalSeconds)
            prefs[KEY_VOICE_MODE] = saved.voiceMode.name
            prefs[KEY_LANGUAGE] = saved.language.name
            prefs.remove(KEY_UDP_DISABLED_LEGACY)
            prefs.remove(KEY_ACCESS_TOKENS_LEGACY)
            prefs.remove(KEY_LAST_CHANNELS_LEGACY)
        }
    }

    private fun parseMode(name: String?): NoiseSuppressionMode =
        runCatching { NoiseSuppressionMode.valueOf(name ?: "") }
            .getOrDefault(NoiseSuppressionMode.SPEEX)

    /** Generic enum parser with a fallback default. */
    private inline fun <reified T : Enum<T>> parseEnum(name: String?, default: T): T =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun parseTheme(name: String?): AppTheme =
        runCatching { AppTheme.valueOf(name ?: "") }
            .getOrDefault(AppTheme.SYSTEM)

    private fun parseVoiceMode(name: String?): VoiceMode =
        runCatching { VoiceMode.valueOf(name ?: "") }
            .getOrDefault(VoiceMode.CONTINUOUS)

    private fun parseVadMethod(name: String?): VadMethod =
        runCatching { VadMethod.valueOf(name ?: "") }
            .getOrDefault(VadMethod.AMPLITUDE)

    private fun parseLanguage(name: String?): AppLanguage =
        runCatching { AppLanguage.valueOf(name ?: "") }
            .getOrDefault(AppLanguage.SYSTEM)

    private fun parseOutputOrder(raw: String?, speakerLegacy: Boolean): List<VoiceOutputTarget> {
        if (raw != null) {
            val parsed = raw.split(',').mapNotNull { name ->
                runCatching { VoiceOutputTarget.valueOf(name.trim()) }.getOrNull()
            }
            return VoiceOutputTarget.normalize(parsed)
        }
        return if (speakerLegacy) {
            VoiceOutputTarget.SPEAKER_FIRST_ORDER
        } else {
            VoiceOutputTarget.DEFAULT_ORDER
        }
    }
}
