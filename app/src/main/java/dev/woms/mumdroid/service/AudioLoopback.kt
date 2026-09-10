package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.LoopbackMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session state of the audio self-test ("loopback"): which [LoopbackMode] is
 * active, what target outgoing audio must carry because of it, and where local
 * capture goes while the local mode is on.
 *
 * The mode is deliberately **not** persisted: it mirrors the official client's
 * temporary switch (leaving the session, or the app, drops it), because a user
 * who forgot to turn a self-test off would silently stop being heard.
 *
 * Threading: [set] runs on the command thread, [current] and [loopLocal] on the
 * capture thread. Only the [mode] StateFlow read crosses that boundary, exactly
 * like [VoiceTargetController]'s target id.
 */
internal class AudioLoopback(
    private val localSession: () -> Int,
    /**
     * Hands a captured frame of local audio to playback, keyed by the session
     * it must be mixed as. Called from the capture thread.
     */
    private val play: (session: Int, pcm: ShortArray) -> Unit,
) {
    private val _mode = MutableStateFlow(LoopbackMode.OFF)
    val mode: StateFlow<LoopbackMode> = _mode

    /** Snapshot for the capture thread. */
    val current: LoopbackMode get() = _mode.value

    /**
     * Applies a new mode.
     *
     * @return true when the mode actually changed, so the caller can flush the
     *         utterance that was in flight (its remaining frames must not be
     *         sent under the previous mode).
     */
    fun set(mode: LoopbackMode): Boolean {
        if (_mode.value == mode) return false
        _mode.value = mode
        return true
    }

    /**
     * Voice-target id for a new outgoing frame, given the id the whisper/shout
     * intent resolved to. The server mode overrides it with
     * [dev.woms.mumdroid.core.model.VoiceTargetId.SERVER_LOOPBACK]; the other
     * modes pass it through (the local mode never reaches the wire at all).
     */
    fun outgoingTargetId(voiceTargetId: Int): Int = current.outgoingTargetId(voiceTargetId)

    /**
     * Routes a captured frame to local playback when the local mode is active.
     *
     * @return true when the frame was consumed locally and must therefore not
     *         be transmitted — playing the mic back locally while also sending
     *         it would defeat the point of the self-test (and the official
     *         client does not send in this mode either).
     */
    fun loopLocal(pcm: ShortArray): Boolean {
        if (!current.isLocal) return false
        play(localSession(), pcm)
        return true
    }

    /** Forgets the mode without touching the audio endpoints (session teardown). */
    fun reset() {
        _mode.value = LoopbackMode.OFF
    }
}
