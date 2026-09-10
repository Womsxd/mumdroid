package dev.woms.mumdroid.core.model

/**
 * Whisper / shout ("voice target") model, aligned with the official protocol:
 * a client registers a numbered receiver set with the server over TCP
 * (`MumbleProto.VoiceTarget`, message type [dev.woms.mumdroid.core.net.MessageType.VOICE_TARGET])
 * and then stamps that number into the `target` field of every outgoing audio
 * packet.
 *
 * Only the numbering, the receiver-set shape and the reserved ids are protocol
 * facts; everything else (when a target is chosen, how it is shown) is this
 * client's own design.
 */
data class VoiceTargetTarget(
    /** Sessions of every user that should receive the audio directly. */
    val sessions: List<Int> = emptyList(),
    /** Channel whose occupants should receive the audio, or null. */
    val channelId: Int? = null,
    /** Optional ACL group restriction for [channelId]. */
    val group: String = "",
    /** Follow the channel's links. */
    val links: Boolean = false,
    /** Include the channel's children. */
    val children: Boolean = false,
) {
    /** A receiver set with neither sessions nor a channel carries nobody. */
    val isEmpty: Boolean get() = sessions.isEmpty() && channelId == null
}

/** Reserved voice-target ids, mirroring official `MumbleProtocol::ReservedTargetIDs`. */
object VoiceTargetId {
    /** Normal speech: the packet carries this and the server broadcasts normally. */
    const val REGULAR_SPEECH = 0

    /**
     * Server loopback: audio tagged with this id is echoed back to the sender
     * by murmur (`forceAddReceiver` on the sending user only) and reaches nobody
     * else. It is the wire half of the server-side audio self-test, see
     * [LoopbackMode.SERVER]. Because it sits outside the registered range it
     * needs no `VoiceTarget` registration.
     */
    const val SERVER_LOOPBACK = 31

    /**
     * Registered (shout/whisper) ids accepted by murmur: `msgVoiceTarget`
     * drops anything outside `[1, 0x1f)`, and the id is written into the five
     * least significant header bits of a legacy audio packet, so 5 bits is the
     * hard ceiling.
     */
    const val MIN = 1
    const val MAX = 30

    val RANGE = MIN..MAX

    /**
     * Sentinel meaning "this target resolves to nobody, send nothing".
     *
     * The official client uses `iTarget = -1` for the same purpose and then
     * refuses to encode any audio. That is a safety rule, not cosmetics: an
     * unresolvable whisper must never degrade into a channel-wide broadcast.
     */
    const val NONE = -1
}

/**
 * The user's speaking intent, before any id is allocated. This is what the UI
 * sets and what the target controller resolves against the live roster.
 */
sealed interface VoiceTargetSpec {
    /** Whisper to one or more users (by session). */
    data class Users(val sessions: List<Int>) : VoiceTargetSpec

    /** Shout to a channel, optionally following links / including children. */
    data class Channel(
        val channelId: Int,
        val links: Boolean = false,
        val children: Boolean = false,
        val group: String = "",
    ) : VoiceTargetSpec
}

/**
 * Wire `context` of a server → client audio packet, mirroring
 * official `MumbleProtocol::AudioContext`.
 */
enum class AudioContext(val wire: Int) {
    /** Plain speech (also used for linked-channel traffic). */
    NORMAL(0),

    /** The sender shouted to the channel this audio was delivered through. */
    SHOUT(1),

    /** The sender whispered to this client directly. */
    WHISPER(2),

    /** Delivered because of a channel listener subscription. */
    LISTEN(3),

    /** Not a context we know (sender refused to resolve, future value). */
    UNKNOWN(-1),
    ;

    companion object {
        fun fromWire(value: Int): AudioContext =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * Talk state of a user, mirroring official `Settings::TalkState`. The desktop
 * client derives it from the playback path (receiving) or from the active
 * voice target (sending) and picks the talking icon from it.
 */
enum class TalkState {
    PASSIVE,
    TALKING,
    WHISPERING,
    SHOUTING,
    ;

    /** True for every state that draws a talking indicator. */
    val isAudible: Boolean get() = this != PASSIVE

    companion object {
        /**
         * Maps a received packet's [AudioContext] to the talk state, exactly as
         * official `AudioOutputSpeech::prepareSampleBuffer` does: listeners and
         * normal speech look like plain talking, only shout/whisper differ.
         */
        fun fromContext(context: AudioContext): TalkState = when (context) {
            AudioContext.SHOUT -> SHOUTING
            AudioContext.WHISPER -> WHISPERING
            else -> TALKING
        }
    }
}

/**
 * User-facing description of the active voice target, driving the voice-bar
 * chip and the roster talk-state icons.
 *
 * [spec] is null for regular speech. [available] is false when the target no
 * longer resolves to anybody (all target users left, the channel is gone):
 * audio is then withheld rather than broadcast, and the UI must say so.
 */
data class VoiceTargetStatus(
    val spec: VoiceTargetSpec? = null,
    val channelName: String = "",
    val userNames: List<String> = emptyList(),
    val available: Boolean = true,
) {
    /** No whisper/shout target: regular speech. */
    val isRegular: Boolean get() = spec == null

    /** A target is set but resolves to nobody. */
    val isUnavailable: Boolean get() = spec != null && !available

    /** Channel-shaped targets may follow links / include children. */
    val links: Boolean get() = (spec as? VoiceTargetSpec.Channel)?.links ?: false
    val children: Boolean get() = (spec as? VoiceTargetSpec.Channel)?.children ?: false

    /** Sessions this target points at (whisper targets only). */
    val sessions: List<Int> get() = (spec as? VoiceTargetSpec.Users)?.sessions ?: emptyList()
}
