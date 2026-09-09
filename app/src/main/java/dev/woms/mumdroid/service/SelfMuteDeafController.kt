package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.SelfMuteDeaf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local self-mute / self-deafen state machine, including the desktop rule
 * that deafening also mutes and that undeafen may restore the microphone.
 * Side effects (ending transmission, stopping playback) are reported through
 * [onMuteChanged] / [onDeafenChanged], so this class stays pure state.
 */
internal class SelfMuteDeafController(
    private val onMuteChanged: (muted: Boolean) -> Unit,
    private val onDeafenChanged: (deafened: Boolean) -> Unit,
) {
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened

    private var unmuteOnUndeaf = false

    val mutedValue: Boolean get() = _muted.value
    val deafenedValue: Boolean get() = _deafened.value

    /** True while the local microphone must not transmit. */
    val isBlocked: Boolean get() = _muted.value || _deafened.value

    fun toggleMute(): Boolean {
        val wasMuted = _muted.value
        apply(current().toggleMute())
        if (_muted.value != wasMuted) onMuteChanged(_muted.value)
        return _muted.value
    }

    fun toggleDeafen(): Boolean {
        val wasMuted = _muted.value
        val wasDeafened = _deafened.value
        apply(current().toggleDeafen())
        if (_muted.value != wasMuted) onMuteChanged(_muted.value)
        if (_deafened.value != wasDeafened) onDeafenChanged(_deafened.value)
        return _deafened.value
    }

    fun clear() {
        _muted.value = false
        _deafened.value = false
        unmuteOnUndeaf = false
    }

    private fun current(): SelfMuteDeaf = SelfMuteDeaf(_muted.value, _deafened.value, unmuteOnUndeaf)

    private fun apply(next: SelfMuteDeaf) {
        _muted.value = next.muted
        _deafened.value = next.deafened
        unmuteOnUndeaf = next.unmuteOnUndeaf
    }
}
