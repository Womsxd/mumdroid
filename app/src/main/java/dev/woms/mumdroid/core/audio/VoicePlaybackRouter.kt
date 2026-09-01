package dev.woms.mumdroid.core.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.woms.mumdroid.core.model.VoiceOutputTarget

/**
 * Routes voice playback to a specific [VoiceOutputTarget].
 *
 * Communication playback uses [AudioManager.MODE_IN_COMMUNICATION] plus
 * [AudioManager.setCommunicationDevice] (or speakerphone / SCO on older
 * APIs). Media playback stays in [AudioManager.MODE_NORMAL] and exposes the
 * matching [AudioDeviceInfo] so the [AudioTrack] can pin it with
 * [android.media.AudioTrack.setPreferredDevice].
 */
class VoicePlaybackRouter(
    private val audioManager: AudioManager,
    private val onDevicesChanged: () -> Unit = {},
    private val onInputDeviceChanged: (AudioDeviceInfo?) -> Unit = {},
    private val onOutputDeviceChanged: (AudioDeviceInfo?) -> Unit = {},
) {

    @Volatile
    private var inCall = false

    @Volatile
    private var requestedTarget = VoiceOutputTarget.EARPIECE

    @Volatile
    private var mediaRequested = false

    private var scoStarted = false

    /** Last preferred capture device (headset mic or builtin). */
    @Volatile
    var inputDevice: AudioDeviceInfo? = null
        private set

    /** Last resolved playback device, for [android.media.AudioTrack.setPreferredDevice]. */
    @Volatile
    var outputDevice: AudioDeviceInfo? = null
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (inCall) onDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (inCall) onDevicesChanged()
        }
    }

    fun availableOutputTypes(): List<Int> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

    fun enterCall(target: VoiceOutputTarget, media: Boolean = false) {
        requestedTarget = target
        mediaRequested = media
        if (!inCall) {
            audioManager.registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper()),
            )
            inCall = true
        }
        applyRoute()
    }

    fun route(target: VoiceOutputTarget, media: Boolean) {
        requestedTarget = target
        mediaRequested = media
        if (inCall) applyRoute()
    }

    fun leaveCall() {
        if (inCall) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
        inCall = false
        stopSco()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        inputDevice = null
        outputDevice = null
        onInputDeviceChanged(null)
        onOutputDeviceChanged(null)
    }

    private fun applyRoute() {
        val target = requestedTarget
        val media = mediaRequested
        if (media) {
            applyMediaRoute(target)
        } else {
            applyCommunicationRoute(target)
        }
        applyInputPreference(target)
        onOutputDeviceChanged(outputDevice)
    }

    private fun applyCommunicationRoute(target: VoiceOutputTarget) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val wanted = VoiceRouteSelection.preferredOutputType(
                target,
                media = false,
                devices.map { it.type },
            )
            val device = wanted?.let { type -> devices.firstOrNull { it.type == type } }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
                outputDevice = device
            } else {
                audioManager.clearCommunicationDevice()
                outputDevice = findOutputDevice(target, media = false)
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = target == VoiceOutputTarget.SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = target == VoiceOutputTarget.SPEAKER
            outputDevice = findOutputDevice(target, media = false)
        }
        if (VoiceRouteSelection.needsBluetoothSco(target, media = false)) {
            startSco()
        } else {
            stopSco()
        }
    }

    /**
     * Music-path routing: no in-call mode and no SCO. The [AudioTrack] is
     * [android.media.AudioAttributes.USAGE_MEDIA], so pinning [outputDevice]
     * selects headset / A2DP / loudspeaker.
     */
    private fun applyMediaRoute(target: VoiceOutputTarget) {
        audioManager.mode = AudioManager.MODE_NORMAL
        stopSco()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = target == VoiceOutputTarget.SPEAKER
        outputDevice = findOutputDevice(target, media = true)
    }

    private fun findOutputDevice(target: VoiceOutputTarget, media: Boolean): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wanted = VoiceRouteSelection.preferredOutputType(
            target,
            media,
            devices.map { it.type },
        )
        return wanted?.let { type -> devices.firstOrNull { it.type == type } }
    }

    private fun applyInputPreference(target: VoiceOutputTarget) {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val wanted = VoiceRouteSelection.preferredInputType(target, inputs.map { it.type })
        val device = wanted?.let { type -> inputs.firstOrNull { it.type == type } }
        inputDevice = device
        onInputDeviceChanged(device)
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        if (scoStarted) return
        if (!audioManager.isBluetoothScoAvailableOffCall) return
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        scoStarted = true
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted && !audioManager.isBluetoothScoOn) return
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        scoStarted = false
    }
}
