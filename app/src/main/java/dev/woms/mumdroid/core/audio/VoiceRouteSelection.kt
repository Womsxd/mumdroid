package dev.woms.mumdroid.core.audio

import android.media.AudioDeviceInfo
import dev.woms.mumdroid.core.model.VoiceOutputTarget

/**
 * Maps the four logical output targets onto Android device types and picks
 * the first connected target from the user's priority list.
 *
 * Headset / Bluetooth / speaker may use communication or media routing.
 * The earpiece is communication-only.
 */
object VoiceRouteSelection {

    /** Wired / USB / hearing-aid output, most specific first. */
    val HEADSET_OUTPUT_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** Bluetooth types used to decide whether a BT device is connected. */
    val BLUETOOTH_OUTPUT_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    /** In-call Bluetooth (SCO first). */
    val BLUETOOTH_COMM_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    /** Media Bluetooth (A2DP first). */
    val BLUETOOTH_MEDIA_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    fun isConnected(target: VoiceOutputTarget, availableTypes: Collection<Int>): Boolean =
        when (target) {
            VoiceOutputTarget.HEADSET -> HEADSET_OUTPUT_TYPES.any { it in availableTypes }
            VoiceOutputTarget.BLUETOOTH -> BLUETOOTH_OUTPUT_TYPES.any { it in availableTypes }
            VoiceOutputTarget.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in availableTypes
            VoiceOutputTarget.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in availableTypes
        }

    /** Connected logical targets, in enum order. */
    fun connectedTargets(availableTypes: Collection<Int>): List<VoiceOutputTarget> =
        VoiceOutputTarget.entries.filter { isConnected(it, availableTypes) }

    /** First connected target in the user's [order], or null if none match. */
    fun pick(
        order: List<VoiceOutputTarget>,
        connected: Collection<VoiceOutputTarget>,
    ): VoiceOutputTarget? =
        VoiceOutputTarget.normalize(order).firstOrNull { it in connected }

    /**
     * Android device type to request for [target]. Null when that target has
     * no matching device among [availableTypes].
     */
    fun preferredOutputType(
        target: VoiceOutputTarget,
        media: Boolean,
        availableTypes: Collection<Int>,
    ): Int? {
        val candidates = when (target) {
            VoiceOutputTarget.HEADSET -> HEADSET_OUTPUT_TYPES
            VoiceOutputTarget.BLUETOOTH ->
                if (media) BLUETOOTH_MEDIA_TYPES else BLUETOOTH_COMM_TYPES
            VoiceOutputTarget.SPEAKER -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            VoiceOutputTarget.EARPIECE -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        }
        return candidates.firstOrNull { it in availableTypes }
    }

    fun needsBluetoothSco(target: VoiceOutputTarget, media: Boolean): Boolean =
        target == VoiceOutputTarget.BLUETOOTH && !media

    /**
     * True when incoming voice will play from the builtin earpiece, so the
     * screen should blank when the phone is held to the ear.
     */
    fun routesToEarpiece(
        connected: Boolean,
        target: VoiceOutputTarget?,
        availableOutputTypes: Collection<Int>,
    ): Boolean {
        if (!connected || target != VoiceOutputTarget.EARPIECE) return false
        return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in availableOutputTypes
    }

    /**
     * Input devices that actually have a microphone. [TYPE_WIRED_HEADPHONES]
     * is output-only, so the phone mic stays in use for those.
     */
    val INPUT_HEADSET_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    val INPUT_BLUETOOTH_TYPES: IntArray = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun preferredInputType(
        target: VoiceOutputTarget,
        availableTypes: Collection<Int>,
    ): Int? = when (target) {
        VoiceOutputTarget.SPEAKER,
        VoiceOutputTarget.EARPIECE,
        -> AudioDeviceInfo.TYPE_BUILTIN_MIC.takeIf { it in availableTypes }
        VoiceOutputTarget.HEADSET -> INPUT_HEADSET_TYPES.firstOrNull { it in availableTypes }
        VoiceOutputTarget.BLUETOOTH -> INPUT_BLUETOOTH_TYPES.firstOrNull { it in availableTypes }
    }
}
