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
 */
internal class MumbleServiceEvents(
    private val state: SessionState,
    private val scope: CoroutineScope,
    private val roster: SessionRoster,
    private val admin: ServerAdminSession,
    private val voice: VoiceSession,
    private val chat: SessionChat,
    private val notices: SessionNotices,
    private val reconnect: ReconnectController,
    private val cert: CertificatePromptController,
    private val lastChannel: LastChannelSession,
    private val sessionChannels: SessionChannels,
    private val tcpPing: TcpPingStats,
    private val notifications: ConnectionNotifications,
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
        state.status.value = text
        notifications.update(text, state.serverName.value, reconnect.countdown.value)
    }

    private fun connectedStatusText(channelName: String = roster.localChannelName()): String {
        val name = channelName.trim()
        return if (name.isEmpty()) host.getString(R.string.status_connected)
        else host.getString(R.string.status_connected_in_channel, name)
    }

    fun updateConnectedStatus(channelName: String = roster.localChannelName()) {
        if (!state.connected.value) return
        updateStatus(connectedStatusText(channelName))
    }

    fun clearSessionState() {
        roster.clear()
        cert.clear()
        admin.clear()
    }

    fun applyChannelPassword(channelId: Int, password: String) {
        admin.applyChannelPassword(state.client, channelId, password) { id, token ->
            host.persistAccessToken(id, token)
        }
    }

    fun handlePrivateReply(intent: android.content.Intent) {
        val parsed = notifications.parsePrivateReply(intent) ?: return
        val session = parsed.first
        var actorName = parsed.second.first
        val text = parsed.second.second
        if (actorName.isEmpty()) {
            actorName = roster.userMap[session]?.name ?: session.toString()
        }
        if (!state.connected.value) return
        chat.sendToUser(state.client, session, text, state.serverName.value, actorName)
        notifications.notifyPrivateChat(
            session,
            actorName,
            host.getString(R.string.you) + ": " + text,
            onlyAlertOnce = true,
        )
    }

    fun buildConnectionStats(): MumbleClient.ConnectionStats {
        val voiceStats = voice.connectionStats()
        return voiceStats.copy(
            tcpPingAvg = tcpPing.averageMsLong.toFloat(),
            tcpPingVar = tcpPing.variance,
            tcpPingPackets = tcpPing.sampleCount,
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
        admin.handleAcl(state.client, acl) { id, token ->
            host.persistAccessToken(id, token)
        }
    }

    override fun onQueryUsers(ids: List<Int>, names: List<String>) {
        admin.onQueryUsers(ids, names)
    }

    override fun onUserList(users: List<RegisteredUser>) = admin.onUserList(users)

    override fun onBanList(bans: List<BanEntry>, query: Boolean) = admin.handleBanList(state.client, bans)

    override fun onPermissionQuery(channelId: Int, permissions: Long, flush: Boolean) {
        roster.applyPermissionQuery(channelId, permissions, flush)
    }

    override fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int) {
        state.connected.value = true
        state.connecting.value = false
        reconnect.noteConnected()
        roster.markLocalUser(session)
        updateConnectedStatus()
        voice.applyMaxBandwidth(maxBandwidth)
        if (welcomeText.isNotEmpty()) {
            notices.system(welcomeText)
        }
        lastChannel.restoreAfterSync(
            host = state.host,
            port = state.port,
            joinWithoutAnnounce = { sessionChannels.join(it, announceMove = false) },
            onStay = { updateConnectedStatus() },
            announceJoin = { notices.announceLocalJoin(it) },
        )
        scope.launch {
            delay(1500)
            notices.joinHintsEnabled = true
        }
        scope.launch {
            val fp = state.client?.serverFingerprint
            if (!fp.isNullOrBlank()) {
                host.recordCertificate(state.host, state.port, fp)
            }
        }
        voice.start(session)
    }

    override fun onRejected(reason: String, type: Int) {
        notices.joinHintsEnabled = false
        state.connecting.value = false
        reconnect.markNotReconnecting()
        updateStatus(host.getString(R.string.status_rejected, reason))
    }

    override fun onDisconnected(reason: String) {
        notices.joinHintsEnabled = false
        voice.stop()
        notifications.cancelChat()
        if (!lastChannel.restorePending) {
            lastChannel.persistFromLocal(state.host, state.port, state.connectedServerId)
        }
        clearSessionState()
        val params = state.lastConnectParams
        val serverForced = state.serverRemoval.value != null
        val canRetry = reconnect.canRetry(
            autoReconnect = state.currentSettings.autoReconnect,
            hasParams = params != null,
            manualDisconnect = state.manualDisconnect.value,
            serverForced = serverForced,
        )
        if (canRetry && params != null) {
            state.connected.value = false
            state.connecting.value = false
            reconnect.startCountdown(
                onTick = { remaining ->
                    updateStatus(host.getString(R.string.reconnect_in_seconds, remaining))
                },
                onRetry = {
                    if (!state.connected.value && !state.connecting.value && !state.manualDisconnect.value) {
                        scope.launch { host.connect(params) }
                    }
                },
            )
            return
        }
        reconnect.cancelAndResetAttempts()
        state.connected.value = false
        state.connecting.value = false
        val displayReason = if (reason == ClientTlsPolicy.CERTIFICATE_REJECTED) {
            host.getString(R.string.status_certificate_rejected)
        } else {
            reason
        }
        updateStatus(
            when {
                serverForced -> state.status.value.ifEmpty {
                    host.getString(R.string.status_server_removed_title)
                }
                state.manualDisconnect.value -> host.getString(R.string.status_disconnected_reason, displayReason)
                else -> host.getString(R.string.status_connection_failed, displayReason)
            },
        )
        host.stopSelf()
    }

    override fun onChannelState(channel: Channel) {
        roster.putChannel(channel)
        if (state.connected.value && roster.localUser()?.channelId == channel.id) {
            updateConnectedStatus()
        }
        scope.launch { roster.publishChannelsNow() }
    }

    override fun onChannelStateProto(state: dev.woms.mumdroid.core.proto.ChannelState) {
        val (existing, merged) = roster.mergeChannelState(state)
        onChannelState(merged)
        admin.maybeCreatePassword(existing == null, merged)?.let { (id, password) ->
            applyChannelPassword(id, password)
        }
    }

    override fun onPermissionDenied(denied: dev.woms.mumdroid.core.proto.PermissionDenied) {
        val handled = admin.promptForChannelPassword(
            denied,
            roster.channelMap[denied.channelId],
            ChanACL.ENTER.toLong(),
            onDenied = { notices.system(it) },
            passwordDeniedMessage = { name ->
                host.getString(R.string.permission_denied_channel_password, name)
            },
        )
        if (handled) return
        onInfo(notices.permissionDeniedText(denied))
    }

    override fun onCodecVersion(opus: Boolean) {
        if (!opus) {
            notices.system(host.getString(R.string.codec_opus_required))
        }
    }

    override fun onChannelRemoved(channelId: Int) {
        roster.removeChannel(channelId)
    }

    override fun onUserState(user: dev.woms.mumdroid.core.proto.UserState) {
        val merged = roster.mergeUserState(user) ?: return
        val (existing, updated) = merged
        notices.applyListening(updated.session, user)
        val speakBlocked = updated.mute || updated.deaf || updated.suppress ||
            updated.selfMute || updated.selfDeaf
        if (updated.session == roster.localSession) {
            voice.applyLocalSpeakBlock(
                wasBlocked = existing?.isSpeakBlocked == true,
                nowBlocked = speakBlocked,
            )
            if (user.hasChannelId() && !lastChannel.restorePending) {
                lastChannel.persistFromLocal(state.host, state.port, state.connectedServerId)
                updateConnectedStatus()
            }
        }

        if (user.hasChannelId()) {
            notices.announceChannelChange(
                protoSession = user.session,
                newChannel = user.channelId,
                existing = existing,
                updatedName = updated.name,
                restorePending = lastChannel.restorePending,
                consumePasswordJoin = admin::consumePasswordJoin,
            )
        }
        roster.publish()
    }

    override fun onUserRemoved(
        session: Int,
        actor: Int,
        hasActor: Boolean,
        reason: String,
        ban: Boolean,
    ) {
        val event = notices.userRemoved(session, actor, hasActor, reason, ban)
        if (event.removal != null && event.removal.isLocal) {
            if (event.removed != null && !lastChannel.restorePending) {
                lastChannel.remember(event.removed.channelId, state.host, state.port, state.connectedServerId)
            }
            reconnect.cancel()
            state.serverRemoval.value = event.removal
            updateStatus(event.message.orEmpty())
        }
        roster.removeUser(session)
        admin.clearUserStatsIfSession(session)
        roster.publish()
        admin.handleUserRemovedBan(state.client, session, ban)
    }

    override fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean) {
        val session = actor.toIntOrNull()
        val isSystem = session == null || session == 0 || roster.userMap[session] == null
        if (!isSystem && roster.isIgnored(session)) return
        val actorName = if (isSystem) state.serverName.value.ifEmpty { host.getString(R.string.system_message) }
        else roster.userMap[session]?.name ?: actor
        chat.appendAsync(
            ChatMessage(
                actorSession = session ?: 0,
                actorName = actorName,
                channelId = channelId,
                channelName = if (isSystem) "" else roster.channelName(channelId),
                text = text,
                isSystem = isSystem,
                isPrivate = isPrivate && !isSystem,
            ),
        )
        if (state.currentSettings.chatNotifications) {
            if (isPrivate && !isSystem && session != roster.localSession) {
                notifications.notifyPrivateChat(session, actorName, text)
            } else if (!isPrivate && !isSystem && session != roster.localSession) {
                val myChannel = roster.localUser()?.channelId
                if (myChannel == null || channelId == myChannel) {
                    notifications.notifyChannelChat(actorName, roster.channelName(channelId), text)
                }
            }
        }
    }

    override fun onServerConfig(welcomeText: String, maxBandwidth: Int, maxUsers: Int) {
        voice.applyMaxBandwidth(maxBandwidth)
        if (maxUsers > 0) {
            state.serverMaxUsers = maxUsers
        }
        if (welcomeText.isNotEmpty()) {
            notices.system(welcomeText)
        }
    }

    override fun onUserStats(stats: dev.woms.mumdroid.core.proto.UserStats) {
        val name = roster.userMap[stats.session]?.name.orEmpty()
        admin.handleUserStats(state.client, stats, name)
    }

    override fun onInfo(message: String) {
        if (message.isEmpty()) return
        notices.system(message)
    }

    override fun onServerVersion(versionV2: Long, legacyVersion: Int) {
        val v2 = if (versionV2 != 0L) versionV2 else legacyVersionToV2(legacyVersion)
        voice.onServerVersion(v2 >= PROTOBUF_INTRODUCTION_VERSION_V2 && v2 != 0L)
    }

    override fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        voice.onCryptSetup(state.host, state.port, key, clientNonce, serverNonce)
    }

    override fun onTunneledPacket(body: ByteArray) {
        voice.playTunneled(body)
    }

    override fun onCertificateError(
        fingerprint: String,
        pinnedFingerprint: String,
        respond: (CertificateDecision) -> Unit,
    ) {
        cert.present(fingerprint, pinnedFingerprint, state.host, state.port, respond)
    }
}
