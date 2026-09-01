package dev.woms.mumdroid.service

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.ServerRemovalKind
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserRemoveNotice

/**
 * Join / leave / kick system lines and permission-denied copy.
 */
internal class SessionNotices(
    private val chat: SessionChat,
    private val roster: SessionRoster,
    private val serverName: () -> String,
    private val strings: Strings,
) {
    interface Strings {
        fun getString(id: Int): String
        fun getString(id: Int, vararg formatArgs: Any): String
    }

    var joinHintsEnabled = false

    fun system(message: String) {
        chat.appendSystem(serverName(), message)
    }

    fun applyListening(session: Int, msg: dev.woms.mumdroid.core.proto.UserState) {
        roster.applyListeningChannels(
            session,
            msg,
            onStarted = { id -> system(strings.getString(R.string.listening_started, roster.channelName(id))) },
            onStopped = { id -> system(strings.getString(R.string.listening_stopped, roster.channelName(id))) },
            onUserStarted = { actor ->
                system(
                    strings.getString(
                        R.string.listening_user_started,
                        actor.ifEmpty { strings.getString(R.string.unknown_user) },
                    ),
                )
            },
            onUserStopped = { actor ->
                system(
                    strings.getString(
                        R.string.listening_user_stopped,
                        actor.ifEmpty { strings.getString(R.string.unknown_user) },
                    ),
                )
            },
        )
    }

    fun announceChannelChange(
        protoSession: Int,
        newChannel: Int,
        existing: User?,
        updatedName: String,
        restorePending: Boolean,
        consumePasswordJoin: (Int) -> Boolean,
    ) {
        if (newChannel == 0) return
        val oldChannel = existing?.channelId
        val myChannel = roster.localUser()?.channelId
        if (protoSession == roster.localSession) {
            if (existing == null && !restorePending) {
                val name = updatedName.ifEmpty { strings.getString(R.string.you) }
                system(strings.getString(R.string.chat_user_joined, name, roster.channelName(newChannel)))
            } else if (oldChannel != null && oldChannel != newChannel && consumePasswordJoin(newChannel)) {
                val name = updatedName.ifEmpty { strings.getString(R.string.you) }
                system(
                    strings.getString(
                        R.string.chat_user_moved,
                        name,
                        roster.channelName(oldChannel),
                        roster.channelName(newChannel),
                    ),
                )
            }
        } else if (joinHintsEnabled) {
            if (existing == null) {
                if (newChannel == myChannel) {
                    system(strings.getString(R.string.chat_user_joined, updatedName, roster.channelName(newChannel)))
                }
            } else if (oldChannel != null && oldChannel != newChannel) {
                if (newChannel == myChannel || oldChannel == myChannel) {
                    system(
                        strings.getString(
                            R.string.chat_user_moved,
                            updatedName,
                            roster.channelName(oldChannel),
                            roster.channelName(newChannel),
                        ),
                    )
                }
            }
        }
    }

    fun announceLocalJoin(channelId: Int) {
        val name = roster.localUser()?.name.orEmpty()
            .ifEmpty { strings.getString(R.string.you) }
        system(strings.getString(R.string.chat_user_joined, name, roster.channelName(channelId)))
    }

    fun userRemoved(
        session: Int,
        actor: Int,
        hasActor: Boolean,
        reason: String,
        ban: Boolean,
    ): RemovedEvent {
        val removed = roster.userMap[session]
        val actorName = if (hasActor) roster.userMap[actor]?.name.orEmpty() else ""
        val targetName = removed?.name.orEmpty()
        val isLocal = session == roster.localSession && roster.localSession != 0
        val removal = UserRemoveNotice.of(
            isLocal = isLocal,
            hasActor = hasActor,
            actorName = actorName,
            targetName = targetName,
            reason = reason,
            banned = ban,
        )
        if (removal != null) {
            val message = formatServerRemoval(removal)
            system(message)
            return RemovedEvent(removal, message, removed, isLocal)
        }
        val myChannel = roster.localUser()?.channelId
        if (joinHintsEnabled && !isLocal && removed != null &&
            removed.channelId != 0 && removed.channelId == myChannel
        ) {
            system(
                strings.getString(R.string.chat_user_left, removed.name, roster.channelName(removed.channelId)),
            )
        }
        return RemovedEvent(null, null, removed, isLocal)
    }

    fun permissionDeniedText(denied: dev.woms.mumdroid.core.proto.PermissionDenied): String {
        if (denied.reason.isNotEmpty()) return denied.reason
        return when (denied.type) {
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.Permission ->
                strings.getString(R.string.permission_denied_permission)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.SuperUser ->
                strings.getString(R.string.permission_denied_superuser)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.ChannelName ->
                strings.getString(R.string.permission_denied_channel_name)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.TextTooLong ->
                strings.getString(R.string.permission_denied_text_too_long)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.TemporaryChannel ->
                strings.getString(R.string.permission_denied_temporary)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.MissingCertificate ->
                strings.getString(R.string.permission_denied_certificate)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.UserName ->
                strings.getString(R.string.permission_denied_username)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.ChannelFull ->
                strings.getString(R.string.permission_denied_channel_full)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.NestingLimit ->
                strings.getString(R.string.permission_denied_nesting)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.ChannelCountLimit ->
                strings.getString(R.string.permission_denied_channel_count)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.ChannelListenerLimit ->
                strings.getString(R.string.permission_denied_channel_listener)
            dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.UserListenerLimit ->
                strings.getString(R.string.permission_denied_user_listener)
            else -> strings.getString(R.string.permission_denied)
        }
    }

    fun formatServerRemoval(removal: ServerRemoval): String {
        val actor = removal.actorName.ifEmpty { strings.getString(R.string.unknown_user) }
        val target = removal.targetName.ifEmpty { strings.getString(R.string.unknown_user) }
        val base = when {
            removal.isLocal && removal.kind == ServerRemovalKind.BANNED ->
                strings.getString(R.string.you_were_kicked_banned, actor)
            removal.isLocal && removal.kind == ServerRemovalKind.KICKED ->
                strings.getString(R.string.you_were_kicked, actor)
            removal.isLocal ->
                strings.getString(R.string.you_were_removed)
            removal.kind == ServerRemovalKind.BANNED ->
                strings.getString(R.string.user_was_kicked_banned, target, actor)
            else ->
                strings.getString(R.string.user_was_kicked, target, actor)
        }
        return if (removal.reason.isBlank()) base
        else base + strings.getString(R.string.notice_reason_suffix, removal.reason)
    }

    data class RemovedEvent(
        val removal: ServerRemoval?,
        val message: String?,
        val removed: User?,
        val isLocal: Boolean,
    )
}
