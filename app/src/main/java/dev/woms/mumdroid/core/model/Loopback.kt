package dev.woms.mumdroid.core.model

/**
 * Audio self-test ("loopback") mode, mirroring official
 * `Settings::LoopMode`: play your own microphone back to yourself so you can
 * tell whether the mic, the headset and the codec chain actually work.
 *
 * While any loopback mode is active the microphone audio is **not** heard by
 * anybody else, which is why the UI must always say so.
 *
 * The two wire-visible behaviours differ in one place only: the local mode
 * never touches the network (the official `LoopUser` feeds the local playback
 * buffer directly, so it also works offline), while the server mode tags the
 * outgoing audio with [VoiceTargetId.SERVER_LOOPBACK] so murmur echoes it back
 * to this client and to nobody else.
 *
 * Session-scoped like the official client's temporary switch: it is not
 * persisted and is reset when the session ends.
 */
enum class LoopbackMode {
    /** No loopback: the microphone is transmitted normally. */
    OFF,

    /** Local self-test: capture is played back locally, nothing is sent. */
    LOCAL,

    /** Server self-test: capture is echoed back by the server (target 31). */
    SERVER,
    ;

    val isActive: Boolean get() = this != OFF

    /** True for [LOCAL]: no packet may leave the device while this is set. */
    val isLocal: Boolean get() = this == LOCAL

    /** True for [SERVER]: outgoing audio carries the server-loopback target. */
    val isServer: Boolean get() = this == SERVER

    companion object {
        /**
         * Maps the two switches of the self-test menu (enabled / server mode)
         * onto a mode, so the UI cannot express a state the protocol does not
         * have: disabled is always [OFF], and enabling starts in [LOCAL] — the
         * mode that works offline and does not bother the server.
         */
        fun of(enabled: Boolean, server: Boolean): LoopbackMode = when {
            !enabled -> OFF
            server -> SERVER
            else -> LOCAL
        }
    }

    /**
     * Voice-target id to stamp on outgoing audio, given the id the active
     * whisper/shout intent resolved to.
     *
     * Official `AudioInput::encodeAudioFrame` overwrites the packet target
     * with `SERVER_LOOPBACK` after the whisper bookkeeping, and never encodes
     * at all in local mode — so the two modes cannot be mixed with a
     * whisper/shout, and the local mode must not fall back to regular speech
     * either.
     */
    fun outgoingTargetId(voiceTargetId: Int): Int =
        if (isServer) VoiceTargetId.SERVER_LOOPBACK else voiceTargetId
}
