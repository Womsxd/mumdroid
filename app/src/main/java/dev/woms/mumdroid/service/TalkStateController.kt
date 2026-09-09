package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.VoiceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transmission talk-state machine: PTT / VAD / continuous gating, the local
 * talking + VAD-level flows, and the half-duplex incoming-suppression rule.
 */
internal class TalkStateController(
    private val transmitter: VoiceTransmitter,
    private val isTransmitBlocked: () -> Boolean,
    private val localSession: () -> Int,
    private val setUserTalking: (session: Int, talking: Boolean) -> Unit,
) {
    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking

    private val _vadLevel = MutableStateFlow(0)
    val vadLevel: StateFlow<Int> = _vadLevel

    var voiceMode: VoiceMode = VoiceMode.CONTINUOUS
        private set

    var halfDuplex: Boolean = false
        private set

    var pttHeld: Boolean = false
        private set

    /** Applies the configured mode without running the transition logic. */
    fun setVoiceMode(mode: VoiceMode) {
        voiceMode = mode
    }

    fun setHalfDuplex(value: Boolean) {
        halfDuplex = value
    }

    fun startTalking() {
        if (isTransmitBlocked()) return
        pttHeld = true
        if (voiceMode == VoiceMode.PTT) setTalking(true)
    }

    fun stopTalking() {
        if (voiceMode != VoiceMode.PTT) return
        pttHeld = false
        endTransmission()
    }

    /** PTT/continuous transition after [setVoiceMode] changed the mode. */
    fun applyVoiceModeChange() {
        if (voiceMode == VoiceMode.PTT) {
            if (!pttHeld) endTransmission()
        } else {
            pttHeld = false
            startContinuousTalking()
        }
    }

    fun startContinuousTalking() {
        if (voiceMode == VoiceMode.CONTINUOUS && !isTransmitBlocked()) setTalking(true)
    }

    fun endTransmission() {
        if (_talking.value) transmitter.terminate()
        if (voiceMode == VoiceMode.PTT) pttHeld = false
        _talking.value = false
        _vadLevel.value = 0
        setUserTalking(localSession(), false)
    }

    fun onSpeechDetected(active: Boolean) {
        if (isTransmitBlocked()) return
        val wasTalking = _talking.value
        if (voiceMode == VoiceMode.VAD && wasTalking != active) setTalking(active)
        if (voiceMode == VoiceMode.VAD && wasTalking && !active) transmitter.terminate()
    }

    fun onVadLevel(level: Int) {
        if (voiceMode == VoiceMode.VAD) _vadLevel.value = level
    }

    fun shouldTransmit(): Boolean {
        if (isTransmitBlocked()) return false
        if (voiceMode == VoiceMode.PTT) return pttHeld
        return true
    }

    fun shouldSuppressIncoming(): Boolean =
        halfDuplex && voiceMode != VoiceMode.CONTINUOUS && _talking.value

    /** Clears talk state on stop without notifying the roster (as before). */
    fun resetForStop() {
        pttHeld = false
        _talking.value = false
        _vadLevel.value = 0
    }

    private fun setTalking(talking: Boolean) {
        _talking.value = talking
        setUserTalking(localSession(), talking)
    }
}
