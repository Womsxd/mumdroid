package dev.woms.mumdroid.service

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.CertificateDecision
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.model.RegisteredUser
import dev.woms.mumdroid.core.net.ClientTlsPolicy
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.MumbleListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receives every [MumbleListener] event and keeps the roster/chat/admin/voice/
 * notice collaborators in sync. Session state lives in [SessionState] and the
 * few service-side operations it needs arrive through [SessionHost], so this
 * handler never reaches into the owning service.
 *
 * The bundle it reads lives at the bottom of this file: [SessionContext] exists
 * only as this handler's constructor input, so keeping it next to the class it
 * feeds is one hop shorter than a file of its own.
 */
internal class MumbleServiceEvents(
    private val context: SessionContext,
    private val host: SessionHost,
) : MumbleListener {

    /** Service-side operations the event handler needs. */
    internal interface SessionHost {
        fun getString(id: Int): String
        fun getString(id: Int, vararg formatArgs: Any): String
        suspend fun recordCertificate(host: String, port: Int, fingerprint: String)
        suspend fun persistAccessToken(channelId: Int, token: String)
        suspend fun connect(params: ConnectParams)
        fun stopSelf()
    }

    private val PROTOBUF_INTRODUCTION_VERSION_V2 = (1L shl 48) or (5L shl 32)

    // ---- status helpers ----

    fun updateStatus(text: String) {
        context.state.status.value = text
        context.notifications.update(text, context.state.serverName.value, context.reconnect.countdown.value)
    }

    private fun connectedStatusText(channelName: String = context.roster.localChannelName()): String {
        val name = channelName.trim()
        return if (name.isEmpty()) host.getString(R.string.status_connected)
        else host.getString(R.string.status_connected_in_channel, name)
    }

    fun updateConnectedStatus(channelName: String = context.roster.localChannelName()) {
        if (!context.state.connected.value) return
        updateStatus(connectedStatusText(channelName))
    }

    fun clearSessionState() {
        context.roster.clear()
        context.cert.clear()
        context.admin.clear()
    }

    fun applyChannelPassword(channelId: Int, password: String) {
        context.admin.applyChannelPassword(context.state.client, channelId, password) { id, token ->
            host.persistAccessToken(id, token)
        }
    }

    fun handlePrivateReply(intent: android.content.Intent) {
        val parsed = context.notifications.parsePrivateReply(intent) ?: return
        val session = parsed.first
        var actorName = parsed.second.first
        val text = parsed.second.second
        if (actorName.isEmpty()) {
            actorName = context.roster.userMap[session]?.name ?: session.toString()
        }
        if (!context.state.connected.value) return
        context.chat.sendToUser(context.state.client, session, text, context.state.serverName.value, actorName)
        context.notifications.notifyPrivateChat(
            session,
            actorName,
            host.getString(R.string.you) + ": " + text,
            onlyAlertOnce = true,
        )
    }

    fun buildConnectionStats(): MumbleClient.ConnectionStats {
        val voiceStats = context.voice.connectionStats()
        return voiceStats.copy(
            tcpPingAvg = context.tcpPing.averageMsLong.toFloat(),
            tcpPingVar = context.tcpPing.variance,
            tcpPingPackets = context.tcpPing.sampleCount,
        )
    }

    private fun legacyVersionToV2(legacy: Int): Long {
        val major = (legacy shr 16) and 0xffff
        val minor = (legacy shr 8) and 0xff
        val patch = legacy and 0xff
        return (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    }

    // ---- MumbleListener ----

    override fun onAcl(acl: dev.woms.mumdroid.core.proto.ACL) {
        context.admin.handleAcl(context.state.client, acl) { id, token ->
            host.persistAccessToken(id, token)
        }
    }

    override fun onQueryUsers(ids: List<Int>, names: List<String>) {
        context.admin.onQueryUsers(ids, names)
    }

    override fun onUserList(users: List<RegisteredUser>) = context.admin.onUserList(users)

    override fun onBanList(bans: List<BanEntry>, query: Boolean) = context.admin.handleBanList(context.state.client, bans)

    override fun onPermissionQuery(channelId: Int, permissions: Long, flush: Boolean) {
        context.roster.applyPermissionQuery(channelId, permissions, flush)
    }

    override fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int) {
        context.state.connected.value = true
        context.state.connecting.value = false
        context.reconnect.noteConnected()
        context.roster.markLocalUser(session)
        updateConnectedStatus()
        context.voice.applyMaxBandwidth(maxBandwidth)
        if (welcomeText.isNotEmpty()) {
            context.notices.system(welcomeText)
        }
        context.lastChannel.restoreAfterSync(
            host = context.state.host,
            port = context.state.port,
            joinWithoutAnnounce = { context.sessionChannels.join(it, announceMove = false) },
            onStay = { updateConnectedStatus() },
            announceJoin = { context.notices.announceLocalJoin(it) },
        )
        context.scope.launch {
            delay(1500)
            context.notices.joinHintsEnabled = true
        }
        context.scope.launch {
            val fp = context.state.client?.serverFingerprint
            if (!fp.isNullOrBlank()) {
                host.recordCertificate(context.state.host, context.state.port, fp)
            }
        }
        context.voice.start(session)
    }

    override fun onRejected(reason: String, type: Int) {
        context.notices.joinHintsEnabled = false
        context.state.connecting.value = false
        context.reconnect.markNotReconnecting()
        updateStatus(host.getString(R.string.status_rejected, reason))
    }

    override fun onDisconnected(reason: String) {
        context.notices.joinHintsEnabled = false
        context.voice.stop()
        context.notifications.cancelChat()
        if (!context.lastChannel.restorePending) {
            context.lastChannel.persistFromLocal(context.state.host, context.state.port, context.state.connectedServerId)
        }
        clearSessionState()
        val params = context.state.lastConnectParams
        val serverForced = context.state.serverRemoval.value != null
        val canRetry = context.reconnect.canRetry(
            autoReconnect = context.state.currentSettings.autoReconnect,
            hasParams = params != null,
            manualDisconnect = context.state.manualDisconnect.value,
            serverForced = serverForced,
        )
        if (canRetry && params != null) {
            context.state.connected.value = false
            context.state.connecting.value = false
            context.reconnect.startCountdown(
                onTick = { remaining ->
                    updateStatus(host.getString(R.string.reconnect_in_seconds, remaining))
                },
                onRetry = {
                    if (!context.state.connected.value && !context.state.connecting.value && !context.state.manualDisconnect.value) {
                        context.scope.launch { host.connect(params) }
                    }
                },
            )
            return
        }
        context.reconnect.cancelAndResetAttempts()
        context.state.connected.value = false
        context.state.connecting.value = false
        val displayReason = if (reason == ClientTlsPolicy.CERTIFICATE_REJECTED) {
            host.getString(R.string.status_certificate_rejected)
        } else {
            reason
        }
        updateStatus(
            when {
                serverForced -> context.state.status.value.ifEmpty {
                    host.getString(R.string.status_server_removed_title)
                }
                context.state.manualDisconnect.value -> host.getString(R.string.status_disconnected_reason, displayReason)
                else -> host.getString(R.string.status_connection_failed, displayReason)
            },
        )
        host.stopSelf()
    }

    override fun onChannelState(channel: Channel) {
        context.roster.putChannel(channel)
        if (context.state.connected.value && context.roster.localUser()?.channelId == channel.id) {
            updateConnectedStatus()
        }
        context.scope.launch { context.roster.publishChannelsNow() }
    }

    override fun onChannelStateProto(state: dev.woms.mumdroid.core.proto.ChannelState) {
        val (existing, merged) = context.roster.mergeChannelState(state)
        onChannelState(merged)
        context.admin.maybeCreatePassword(existing == null, merged)?.let { (id, password) ->
            applyChannelPassword(id, password)
        }
    }

    override fun onPermissionDenied(denied: dev.woms.mumdroid.core.proto.PermissionDenied) {
        val handled = context.admin.promptForChannelPassword(
            denied,
            context.roster.channelMap[denied.channelId],
            ChanACL.ENTER.toLong(),
            onDenied = { context.notices.system(it) },
            passwordDeniedMessage = { name ->
                host.getString(R.string.permission_denied_channel_password, name)
            },
        )
        if (handled) return
        onInfo(context.notices.permissionDeniedText(denied))
    }

    override fun onCodecVersion(opus: Boolean) {
        if (!opus) {
            context.notices.system(host.getString(R.string.codec_opus_required))
        }
    }

    override fun onChannelRemoved(channelId: Int) {
        context.roster.removeChannel(channelId)
    }

    override fun onUserState(user: dev.woms.mumdroid.core.proto.UserState) {
        val merged = context.roster.mergeUserState(user) ?: return
        val (existing, updated) = merged
        context.notices.applyListening(updated.session, user)
        val speakBlocked = updated.mute || updated.deaf || updated.suppress ||
            updated.selfMute || updated.selfDeaf
        if (updated.session == context.roster.localSession) {
            context.voice.applyLocalSpeakBlock(
                wasBlocked = existing?.isSpeakBlocked == true,
                nowBlocked = speakBlocked,
            )
            if (user.hasChannelId() && !context.lastChannel.restorePending) {
                context.lastChannel.persistFromLocal(context.state.host, context.state.port, context.state.connectedServerId)
                updateConnectedStatus()
            }
        }

        if (user.hasChannelId()) {
            context.notices.announceChannelChange(
                protoSession = user.session,
                newChannel = user.channelId,
                existing = existing,
                updatedName = updated.name,
                restorePending = context.lastChannel.restorePending,
                consumePasswordJoin = context.admin::consumePasswordJoin,
            )
        }
        context.roster.publish()
    }

    override fun onUserRemoved(
        session: Int,
        actor: Int,
        hasActor: Boolean,
        reason: String,
        ban: Boolean,
    ) {
        val event = context.notices.userRemoved(session, actor, hasActor, reason, ban)
        if (event.removal != null && event.removal.isLocal) {
            if (event.removed != null && !context.lastChannel.restorePending) {
                context.lastChannel.remember(event.removed.channelId, context.state.host, context.state.port, context.state.connectedServerId)
            }
            context.reconnect.cancel()
            context.state.serverRemoval.value = event.removal
            updateStatus(event.message.orEmpty())
        }
        context.roster.removeUser(session)
        context.admin.clearUserStatsIfSession(session)
        context.roster.publish()
        context.admin.handleUserRemovedBan(context.state.client, session, ban)
    }

    override fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean) {
        val session = actor.toIntOrNull()
        val isSystem = session == null || session == 0 || context.roster.userMap[session] == null
        if (!isSystem && context.roster.isIgnored(session)) return
        val actorName = if (isSystem) context.state.serverName.value.ifEmpty { host.getString(R.string.system_message) }
        else context.roster.userMap[session]?.name ?: actor
        context.chat.appendAsync(
            ChatMessage(
                actorSession = session ?: 0,
                actorName = actorName,
                channelId = channelId,
                channelName = if (isSystem) "" else context.roster.channelName(channelId),
                text = text,
                isSystem = isSystem,
                isPrivate = isPrivate && !isSystem,
            ),
        )
        if (context.state.currentSettings.chatNotifications) {
            if (isPrivate && !isSystem && session != context.roster.localSession) {
                context.notifications.notifyPrivateChat(session, actorName, text)
            } else if (!isPrivate && !isSystem && session != context.roster.localSession) {
                val myChannel = context.roster.localUser()?.channelId
                if (myChannel == null || channelId == myChannel) {
                    context.notifications.notifyChannelChat(actorName, context.roster.channelName(channelId), text)
                }
            }
        }
    }

    override fun onServerConfig(welcomeText: String, maxBandwidth: Int, maxUsers: Int) {
        context.voice.applyMaxBandwidth(maxBandwidth)
        if (maxUsers > 0) {
            context.state.serverMaxUsers = maxUsers
        }
        if (welcomeText.isNotEmpty()) {
            context.notices.system(welcomeText)
        }
    }

    override fun onUserStats(stats: dev.woms.mumdroid.core.proto.UserStats) {
        val name = context.roster.userMap[stats.session]?.name.orEmpty()
        context.admin.handleUserStats(context.state.client, stats, name)
    }

    override fun onInfo(message: String) {
        if (message.isEmpty()) return
        context.notices.system(message)
    }

    override fun onServerVersion(versionV2: Long, legacyVersion: Int) {
        val v2 = if (versionV2 != 0L) versionV2 else legacyVersionToV2(legacyVersion)
        context.voice.onServerVersion(v2 >= PROTOBUF_INTRODUCTION_VERSION_V2 && v2 != 0L)
    }

    override fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        context.voice.onCryptSetup(context.state.host, context.state.port, key, clientNonce, serverNonce)
    }

    override fun onTunneledPacket(body: ByteArray) {
        context.voice.playTunneled(body)
    }

    override fun onCertificateError(
        fingerprint: String,
        pinnedFingerprint: String,
        respond: (CertificateDecision) -> Unit,
    ) {
        context.cert.present(fingerprint, pinnedFingerprint, context.state.host, context.state.port, respond)
    }
}

/**
 * The collaborators a session's protocol event handler needs, bundled so
 * [MumbleServiceEvents] does not grow a constructor parameter per collaborator
 * (it reached fourteen before this).
 *
 * Assembled by [MumbleService] in `onCreate`, once every member exists; the
 * host callbacks (strings, persistence, connect, stopSelf) stay separate
 * because they go back into the owning Service.
 */
internal class SessionContext(
    val state: SessionState,
    val scope: CoroutineScope,
    val roster: SessionRoster,
    val admin: ServerAdminSession,
    val voice: VoiceSession,
    val chat: SessionChat,
    val notices: SessionNotices,
    val reconnect: ReconnectController,
    val cert: CertificatePromptController,
    val lastChannel: LastChannelSession,
    val sessionChannels: SessionChannels,
    val tcpPing: TcpPingStats,
    val notifications: ConnectionNotifications,
)
