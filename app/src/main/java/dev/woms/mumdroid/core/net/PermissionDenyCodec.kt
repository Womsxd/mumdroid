package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.PermissionDeny
import dev.woms.mumdroid.core.proto.PermissionDenied

/**
 * Translates the `PermissionDenied` reply into [PermissionDeny], so the
 * session layer only ever names the domain type.
 */
object PermissionDenyCodec {

    fun fromProto(msg: PermissionDenied): PermissionDeny =
        PermissionDeny(
            type = when (msg.type) {
                PermissionDenied.DenyType.Permission -> PermissionDeny.DenyType.PERMISSION
                PermissionDenied.DenyType.SuperUser -> PermissionDeny.DenyType.SUPER_USER
                PermissionDenied.DenyType.ChannelName -> PermissionDeny.DenyType.CHANNEL_NAME
                PermissionDenied.DenyType.TextTooLong -> PermissionDeny.DenyType.TEXT_TOO_LONG
                PermissionDenied.DenyType.TemporaryChannel -> PermissionDeny.DenyType.TEMPORARY_CHANNEL
                PermissionDenied.DenyType.MissingCertificate -> PermissionDeny.DenyType.MISSING_CERTIFICATE
                PermissionDenied.DenyType.UserName -> PermissionDeny.DenyType.USER_NAME
                PermissionDenied.DenyType.ChannelFull -> PermissionDeny.DenyType.CHANNEL_FULL
                PermissionDenied.DenyType.NestingLimit -> PermissionDeny.DenyType.NESTING_LIMIT
                PermissionDenied.DenyType.ChannelCountLimit -> PermissionDeny.DenyType.CHANNEL_COUNT_LIMIT
                PermissionDenied.DenyType.ChannelListenerLimit -> PermissionDeny.DenyType.CHANNEL_LISTENER_LIMIT
                PermissionDenied.DenyType.UserListenerLimit -> PermissionDeny.DenyType.USER_LISTENER_LIMIT
                // `Text` is the protobuf default (also what an unset field
                // reads as) and `H9K` is a joke value; neither has wording of
                // its own. The javalite enum has no UNRECOGNIZED member, but
                // `forNumber` already falls back to `Text` for an unknown
                // number, so an unknown deny type lands here as well.
                PermissionDenied.DenyType.Text,
                PermissionDenied.DenyType.H9K,
                -> PermissionDeny.DenyType.OTHER
            },
            reason = msg.reason,
            channelId = msg.channelId,
            permission = ChanAclCodec.fromProtoUInt32(msg.permission),
        )
}
