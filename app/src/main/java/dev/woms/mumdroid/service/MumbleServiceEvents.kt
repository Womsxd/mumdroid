package dev.woms.mumdroid.service

import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.CertificateDecision
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.MumbleListener
import dev.woms.mumdroid.core.net.RegisteredUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receives every [MumbleListener] event on behalf of [MumbleService] and keeps
 * the roster/chat/admin/voice/notice collaborators in sync. All state lives in
 * the owning service; this class only orchestrates it.
 */
internal class MumbleServiceEvents(private val svc: MumbleService) : MumbleListener {

    private val PROTOBUF_INTRODUCTION_VERSION_V2 = (1L shl 48) or (5L shl 32)

    // ---- status helpers ----

    fun updateStatus(text: String) {
        svc._status.value = text
        svc.notifications.update(text, svc._serverName.value, svc.reconnect.countdown.value)
    }

    private fun connectedStatusText(channelName: String = svc.roster.localChannelName()): String {
        val name = channelName.trim()
        return if (name.isEmpty()) svc.getString(R.string.status_connected)
        else svc.getString(R.string.status_connected_in_channel, name)
    }

    fun updateConnectedStatus(channelName: String = svc.roster.localChannelName()) {
        if (!svc._connected.value) return
        updateStatus(connectedStatusText(channelName))
    }

    fun clearSessionState() {
        svc.roster.clear()
        svc.cert.clear()
        svc.admin.clear()
    }

    fun applyChannelPassword(channelId: Int, password: String) {
        svc.admin.applyChannelPassword(svc.client, channelId, password) { id, token ->
            svc.admin.persistAccessToken(id, token, svc.channelAccessTokenStore, svc.host, svc.port)
        }
    }

    fun handlePrivateReply(intent: android.content.Intent) {
        val parsed = svc.notifications.parsePrivateReply(intent) ?: return
        val session = parsed.first
        var actorName = parsed.second.first
        val text = parsed.second.second
        if (actorName.isEmpty()) {
            actorName = svc.roster.userMap[session]?.name ?: session.toString()
        }
        if (!svc._connected.value) return
        svc.sendPrivateChat(session, text)
        svc.notifications.notifyPrivateChat(
            session,
            actorName,
            svc.getString(R.string.you) + ": " + text,
            onlyAlertOnce = true,
        )
    }

    fun buildConnectionStats(): MumbleClient.ConnectionStats {
        val voiceStats = svc.voice.connectionStats()
        return voiceStats.copy(
            tcpPingAvg = svc.tcpPing.averageMsLong.toFloat(),
            tcpPingVar = svc.tcpPing.variance,
            tcpPingPackets = svc.tcpPing.sampleCount,
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
        svc.admin.handleAcl(svc.client, acl) { id, token ->
            svc.admin.persistAccessToken(id, token, svc.channelAccessTokenStore, svc.host, svc.port)
        }
    }

    override fun onUserList(users: List<RegisteredUser>) = svc.admin.onUserList(users)

    override fun onBanList(bans: List<BanEntry>, query: Boolean) = svc.admin.handleBanList(svc.client, bans)

    override fun onPermissionQuery(channelId: Int, permissions: Int, flush: Boolean) {
        svc.roster.applyPermissionQuery(channelId, permissions, flush)
    }

    override fun onConnected(session: Int, welcomeText: String, maxBandwidth: Int) {
        svc._connected.value = true
        svc._connecting.value = false
        svc.reconnect.noteConnected()
        svc.roster.markLocalUser(session)
        updateConnectedStatus()
        svc.voice.applyMaxBandwidth(maxBandwidth)
        if (welcomeText.isNotEmpty()) {
            svc.notices.system(welcomeText)
        }
        svc.lastChannel.restoreAfterSync(
            host = svc.host,
            port = svc.port,
            joinWithoutAnnounce = { svc.sessionChannels.join(it, announceMove = false) },
            onStay = { updateConnectedStatus() },
            announceJoin = { svc.notices.announceLocalJoin(it) },
        )
        svc.scope.launch {
            delay(1500)
            svc.notices.joinHintsEnabled = true
        }
        svc.scope.launch {
            val fp = svc.client?.serverFingerprint
            if (!fp.isNullOrBlank()) {
                svc.certificateStore.record(svc.host, svc.port, fp)
            }
        }
        svc.voice.start(session)
    }

    override fun onRejected(reason: String, type: Int) {
        svc.notices.joinHintsEnabled = false
        svc._connecting.value = false
        svc.reconnect.markNotReconnecting()
        updateStatus(svc.getString(R.string.status_rejected, reason))
    }

    override fun onDisconnected(reason: String) {
        svc.notices.joinHintsEnabled = false
        svc.voice.stop()
        svc.notifications.cancelChat()
        if (!svc.lastChannel.restorePending) {
            svc.lastChannel.persistFromLocal(svc.host, svc.port, svc.connectedServerId)
        }
        clearSessionState()
        val params = svc.lastConnectParams
        val serverForced = svc._serverRemoval.value != null
        val canRetry = svc.reconnect.canRetry(
            autoReconnect = svc.currentSettings.autoReconnect,
            hasParams = params != null,
            manualDisconnect = svc._manualDisconnect.value,
            serverForced = serverForced,
        )
        if (canRetry && params != null) {
            svc._connected.value = false
            svc._connecting.value = false
            svc.reconnect.startCountdown(
                onTick = { remaining ->
                    updateStatus(svc.getString(R.string.reconnect_in_seconds, remaining))
                },
                onRetry = {
                    if (!svc._connected.value && !svc._connecting.value && !svc._manualDisconnect.value) {
                        svc.scope.launch {
                            svc.connect(
                                params.host,
                                params.port,
                                params.username,
                                params.password,
                                params.displayName,
                                params.serverId,
                            )
                        }
                    }
                },
            )
            return
        }
        svc.reconnect.cancelAndResetAttempts()
        svc._connected.value = false
        svc._connecting.value = false
        val displayReason = if (reason == MumbleClient.CERTIFICATE_REJECTED) {
            svc.getString(R.string.status_certificate_rejected)
        } else {
            reason
        }
        updateStatus(
            when {
                serverForced -> svc._status.value.ifEmpty {
                    svc.getString(R.string.status_server_removed_title)
                }
                svc._manualDisconnect.value -> svc.getString(R.string.status_disconnected_reason, displayReason)
                else -> svc.getString(R.string.status_connection_failed, displayReason)
            },
        )
        svc.stopSelf()
    }

    override fun onChannelState(channel: Channel) {
        svc.roster.putChannel(channel)
        if (svc._connected.value && svc.roster.localUser()?.channelId == channel.id) {
            updateConnectedStatus()
        }
        svc.scope.launch { svc.roster.publishChannelsNow() }
    }

    override fun onChannelStateProto(state: dev.woms.mumdroid.core.proto.ChannelState) {
        val (existing, merged) = svc.roster.mergeChannelState(state)
        onChannelState(merged)
        svc.admin.maybeCreatePassword(existing == null, merged)?.let { (id, password) ->
            applyChannelPassword(id, password)
        }
    }

    override fun onPermissionDenied(denied: dev.woms.mumdroid.core.proto.PermissionDenied) {
        val handled = svc.admin.promptForChannelPassword(
            denied,
            svc.roster.channelMap[denied.channelId],
            ChanACL.ENTER,
            onDenied = { svc.notices.system(it) },
            passwordDeniedMessage = { name ->
                svc.getString(R.string.permission_denied_channel_password, name)
            },
        )
        if (handled) return
        onInfo(svc.notices.permissionDeniedText(denied))
    }

    override fun onCodecVersion(opus: Boolean) {
        if (!opus) {
            svc.notices.system(svc.getString(R.string.codec_opus_required))
        }
    }

    override fun onChannelRemoved(channelId: Int) {
        svc.roster.removeChannel(channelId)
    }

    override fun onUserState(user: dev.woms.mumdroid.core.proto.UserState) {
        val merged = svc.roster.mergeUserState(user) ?: return
        val (existing, updated) = merged
        svc.notices.applyListening(updated.session, user)
        val speakBlocked = updated.mute || updated.deaf || updated.suppress ||
            updated.selfMute || updated.selfDeaf
        if (updated.session == svc.roster.localSession) {
            svc.voice.applyLocalSpeakBlock(
                wasBlocked = existing?.isSpeakBlocked == true,
                nowBlocked = speakBlocked,
            )
            if (user.hasChannelId() && !svc.lastChannel.restorePending) {
                svc.lastChannel.persistFromLocal(svc.host, svc.port, svc.connectedServerId)
                updateConnectedStatus()
            }
        }

        if (user.hasChannelId()) {
            svc.notices.announceChannelChange(
                protoSession = user.session,
                newChannel = user.channelId,
                existing = existing,
                updatedName = updated.name,
                restorePending = svc.lastChannel.restorePending,
                consumePasswordJoin = svc.admin::consumePasswordJoin,
            )
        }
        svc.roster.publish()
    }

    override fun onUserRemoved(
        session: Int,
        actor: Int,
        hasActor: Boolean,
        reason: String,
        ban: Boolean,
    ) {
        val event = svc.notices.userRemoved(session, actor, hasActor, reason, ban)
        if (event.removal != null && event.removal.isLocal) {
            if (event.removed != null && !svc.lastChannel.restorePending) {
                svc.lastChannel.remember(event.removed.channelId, svc.host, svc.port, svc.connectedServerId)
            }
            svc.reconnect.cancel()
            svc._serverRemoval.value = event.removal
            updateStatus(event.message.orEmpty())
        }
        svc.roster.removeUser(session)
        svc.admin.clearUserStatsIfSession(session)
        svc.roster.publish()
        svc.admin.handleUserRemovedBan(svc.client, session, ban)
    }

    override fun onTextMessage(actor: String, text: String, channelId: Int, isPrivate: Boolean) {
        val session = actor.toIntOrNull()
        val isSystem = session == null || session == 0 || svc.roster.userMap[session] == null
        if (!isSystem && svc.roster.isIgnored(session)) return
        val actorName = if (isSystem) svc.serverName.value.ifEmpty { svc.getString(R.string.system_message) }
        else svc.roster.userMap[session]?.name ?: actor
        svc.chat.appendAsync(
            ChatMessage(
                actorSession = session ?: 0,
                actorName = actorName,
                channelId = channelId,
                channelName = if (isSystem) "" else svc.roster.channelName(channelId),
                text = text,
                isSystem = isSystem,
                isPrivate = isPrivate && !isSystem,
            ),
        )
        if (svc.currentSettings.chatNotifications) {
            if (isPrivate && !isSystem && session != svc.roster.localSession) {
                svc.notifications.notifyPrivateChat(session, actorName, text)
            } else if (!isPrivate && !isSystem && session != svc.roster.localSession) {
                val myChannel = svc.roster.localUser()?.channelId
                if (myChannel == null || channelId == myChannel) {
                    svc.notifications.notifyChannelChat(actorName, svc.roster.channelName(channelId), text)
                }
            }
        }
    }

    override fun onServerConfig(welcomeText: String, maxBandwidth: Int, maxUsers: Int) {
        svc.voice.applyMaxBandwidth(maxBandwidth)
        if (maxUsers > 0) {
            svc.serverMaxUsers = maxUsers
        }
        if (welcomeText.isNotEmpty()) {
            svc.notices.system(welcomeText)
        }
    }

    override fun onUserStats(stats: dev.woms.mumdroid.core.proto.UserStats) {
        val name = svc.roster.userMap[stats.session]?.name.orEmpty()
        svc.admin.handleUserStats(svc.client, stats, name)
    }

    override fun onInfo(message: String) {
        if (message.isEmpty()) return
        svc.notices.system(message)
    }

    override fun onServerVersion(versionV2: Long, legacyVersion: Int) {
        val v2 = if (versionV2 != 0L) versionV2 else legacyVersionToV2(legacyVersion)
        svc.voice.onServerVersion(v2 >= PROTOBUF_INTRODUCTION_VERSION_V2 && v2 != 0L)
    }

    override fun onCryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        svc.voice.onCryptSetup(svc.host, svc.port, key, clientNonce, serverNonce)
    }

    override fun onTunneledPacket(body: ByteArray) {
        svc.voice.playTunneled(body)
    }

    override fun onCertificateError(
        fingerprint: String,
        pinnedFingerprint: String,
        respond: (CertificateDecision) -> Unit,
    ) {
        svc.cert.present(fingerprint, pinnedFingerprint, svc.host, svc.port, respond)
    }
}
