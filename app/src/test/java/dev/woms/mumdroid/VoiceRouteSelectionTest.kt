package dev.woms.mumdroid

import android.media.AudioDeviceInfo
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRouteSelectionTest {

    @Test
    fun connectedTargets_onlyReportsPresentDevices() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        assertEquals(
            listOf(
                VoiceOutputTarget.HEADSET,
                VoiceOutputTarget.SPEAKER,
                VoiceOutputTarget.EARPIECE,
            ),
            VoiceRouteSelection.connectedTargets(types),
        )
    }

    @Test
    fun connectedTargets_bluetoothViaA2dp() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        assertTrue(VoiceOutputTarget.BLUETOOTH in VoiceRouteSelection.connectedTargets(types))
        assertFalse(VoiceOutputTarget.HEADSET in VoiceRouteSelection.connectedTargets(types))
    }

    @Test
    fun pick_usesUserOrderAmongConnected() {
        val connected = listOf(VoiceOutputTarget.SPEAKER, VoiceOutputTarget.EARPIECE)
        assertEquals(
            VoiceOutputTarget.EARPIECE,
            VoiceRouteSelection.pick(
                listOf(
                    VoiceOutputTarget.HEADSET,
                    VoiceOutputTarget.BLUETOOTH,
                    VoiceOutputTarget.EARPIECE,
                    VoiceOutputTarget.SPEAKER,
                ),
                connected,
            ),
        )
        assertEquals(
            VoiceOutputTarget.SPEAKER,
            VoiceRouteSelection.pick(VoiceOutputTarget.SPEAKER_FIRST_ORDER, connected),
        )
    }

    @Test
    fun pick_skipsMissingAndFallsThrough() {
        val connected = listOf(VoiceOutputTarget.SPEAKER)
        assertEquals(
            VoiceOutputTarget.SPEAKER,
            VoiceRouteSelection.pick(VoiceOutputTarget.DEFAULT_ORDER, connected),
        )
    }

    @Test
    fun pick_emptyWhenNothingConnected() {
        assertNull(VoiceRouteSelection.pick(VoiceOutputTarget.DEFAULT_ORDER, emptyList()))
    }

    @Test
    fun preferredOutputType_headsetBeatsHeadphones() {
        val types = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            VoiceRouteSelection.preferredOutputType(
                VoiceOutputTarget.HEADSET,
                media = false,
                types,
            ),
        )
    }

    @Test
    fun preferredOutputType_bluetoothPrefersScoForCall() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            VoiceRouteSelection.preferredOutputType(
                VoiceOutputTarget.BLUETOOTH,
                media = false,
                types,
            ),
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            VoiceRouteSelection.preferredOutputType(
                VoiceOutputTarget.BLUETOOTH,
                media = true,
                types,
            ),
        )
    }

    @Test
    fun preferredOutputType_speakerAndEarpiece() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            VoiceRouteSelection.preferredOutputType(VoiceOutputTarget.SPEAKER, false, types),
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            VoiceRouteSelection.preferredOutputType(VoiceOutputTarget.EARPIECE, false, types),
        )
    }

    @Test
    fun needsBluetoothSco_onlyCallBluetooth() {
        assertTrue(
            VoiceRouteSelection.needsBluetoothSco(VoiceOutputTarget.BLUETOOTH, media = false),
        )
        assertFalse(
            VoiceRouteSelection.needsBluetoothSco(VoiceOutputTarget.BLUETOOTH, media = true),
        )
        assertFalse(
            VoiceRouteSelection.needsBluetoothSco(VoiceOutputTarget.HEADSET, media = false),
        )
    }

    @Test
    fun input_headsetUsesHeadsetMic() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            VoiceRouteSelection.preferredInputType(VoiceOutputTarget.HEADSET, types),
        )
    }

    @Test
    fun input_headphonesWithoutMic_leavesPhoneMic() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
        assertNull(VoiceRouteSelection.preferredInputType(VoiceOutputTarget.HEADSET, types))
    }

    @Test
    fun input_speakerAndEarpieceUseBuiltinMic() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            VoiceRouteSelection.preferredInputType(VoiceOutputTarget.SPEAKER, types),
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            VoiceRouteSelection.preferredInputType(VoiceOutputTarget.EARPIECE, types),
        )
    }

    @Test
    fun input_bluetoothUsesScoMic() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            VoiceRouteSelection.preferredInputType(VoiceOutputTarget.BLUETOOTH, types),
        )
    }

    @Test
    fun routesToEarpiece_onlyWhenEarpieceSelected() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        assertTrue(
            VoiceRouteSelection.routesToEarpiece(
                connected = true,
                target = VoiceOutputTarget.EARPIECE,
                availableOutputTypes = types,
            ),
        )
        assertFalse(
            VoiceRouteSelection.routesToEarpiece(
                connected = true,
                target = VoiceOutputTarget.SPEAKER,
                availableOutputTypes = types,
            ),
        )
        assertFalse(
            VoiceRouteSelection.routesToEarpiece(
                connected = false,
                target = VoiceOutputTarget.EARPIECE,
                availableOutputTypes = types,
            ),
        )
    }

    @Test
    fun normalize_fillsMissingAndDropsDupes() {
        assertEquals(
            VoiceOutputTarget.DEFAULT_ORDER,
            VoiceOutputTarget.normalize(emptyList()),
        )
        assertEquals(
            listOf(
                VoiceOutputTarget.SPEAKER,
                VoiceOutputTarget.HEADSET,
                VoiceOutputTarget.BLUETOOTH,
                VoiceOutputTarget.EARPIECE,
            ),
            VoiceOutputTarget.normalize(listOf(VoiceOutputTarget.SPEAKER, VoiceOutputTarget.SPEAKER)),
        )
    }

    @Test
    fun move_reordersWithinBounds() {
        val order = VoiceOutputTarget.DEFAULT_ORDER
        assertEquals(
            listOf(
                VoiceOutputTarget.BLUETOOTH,
                VoiceOutputTarget.HEADSET,
                VoiceOutputTarget.EARPIECE,
                VoiceOutputTarget.SPEAKER,
            ),
            VoiceOutputTarget.move(order, 0, 1),
        )
        assertEquals(order, VoiceOutputTarget.move(order, 0, -1))
    }
}
