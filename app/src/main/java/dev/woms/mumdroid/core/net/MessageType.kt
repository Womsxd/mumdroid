package dev.woms.mumdroid.core.net

/**
 * Mumble TCP control-channel message type identifiers.
 *
 * Numbers match official `MUMBLE_ALL_TCP_MESSAGES` in `src/MumbleProtocol.h`
 * (mumble 1ce8d4262). They are **not** declared in Mumble.proto — the TCP
 * framing is a 16-bit type prefix outside protobuf. Only append; never
 * reorder. Type 26 (`PLUGIN_DATA_TRANSMISSION`) is defined in the proto
 * but this client ignores it.
 */
object MessageType {
    const val VERSION = 0
    const val UDP_TUNNEL = 1
    const val AUTHENTICATE = 2
    const val PING = 3
    const val REJECT = 4
    const val SERVER_SYNC = 5
    const val CHANNEL_REMOVE = 6
    const val CHANNEL_STATE = 7
    const val USER_REMOVE = 8
    const val USER_STATE = 9
    const val BAN_LIST = 10
    const val TEXT_MESSAGE = 11
    const val PERMISSION_DENIED = 12
    const val ACL = 13
    const val QUERY_USERS = 14
    const val CRYPT_SETUP = 15
    const val CONTEXT_ACTION_MODIFY = 16
    const val CONTEXT_ACTION = 17
    const val USER_LIST = 18
    const val VOICE_TARGET = 19
    const val PERMISSION_QUERY = 20
    const val CODEC_VERSION = 21
    const val USER_STATS = 22
    const val REQUEST_BLOB = 23
    const val SERVER_CONFIG = 24
    const val SUGGEST_CONFIG = 25
    const val PLUGIN_DATA_TRANSMISSION = 26
}

/** Legacy UDP audio message types (top 3 bits of the header byte). */
object UdpType {
    const val VOICE_CELT_ALPHA = 0
    const val PING = 1
    const val VOICE_SPEEX = 2
    const val VOICE_CELT_BETA = 3
    const val VOICE_OPUS = 4
}
