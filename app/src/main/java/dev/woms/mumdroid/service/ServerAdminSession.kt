package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.AccessTokens
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordAcl
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.TimedUserBan
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.RegisteredUser
import dev.woms.mumdroid.data.ChannelAccessTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private data class PendingTimedBan(
    val session: Int,
    val name: String,
    val hash: String,
    val reason: String,
    val duration: Int,
    val banCertificate: Boolean,
    val banIp: Boolean,
    val banListSnapshot: List<BanEntry>?,
)

/**
 * Access tokens, ACL password apply, registered-user list, ban list, and
 * timed-ban orchestration, including the TCP sends those flows need.
 */
internal class ServerAdminSession(private val scope: CoroutineScope) {

    companion object {
        private const val TIMED_BAN_STATS_WAIT_MS = 3000L
    }

    private val _accessTokens = MutableStateFlow<List<String>>(emptyList())
    val accessTokens: StateFlow<List<String>> = _accessTokens

    private val _registeredUsers = MutableStateFlow<List<RegisteredUser>?>(null)
    val registeredUsers: StateFlow<List<RegisteredUser>?> = _registeredUsers

    private val _banList = MutableStateFlow<List<BanEntry>?>(null)
    val banList: StateFlow<List<BanEntry>?> = _banList

    private val _userListRefreshing = MutableStateFlow(false)
    val userListRefreshing: StateFlow<Boolean> = _userListRefreshing

    private val _banListRefreshing = MutableStateFlow(false)
    val banListRefreshing: StateFlow<Boolean> = _banListRefreshing

    private val _channelAclPassword = MutableStateFlow<ChannelAclPassword?>(null)
    val channelAclPassword: StateFlow<ChannelAclPassword?> = _channelAclPassword

    private val _channelPasswordPrompt = MutableStateFlow<ChannelPasswordPrompt?>(null)
    val channelPasswordPrompt: StateFlow<ChannelPasswordPrompt?> = _channelPasswordPrompt

    private val _userStats = MutableStateFlow<UserConnectionInfo?>(null)
    val userStats: StateFlow<UserConnectionInfo?> = _userStats

    private var lastAclQuery: dev.woms.mumdroid.core.proto.ACL? = null
    private var pendingPasswordApply: Pair<Int, String>? = null
    private var pendingCreatePassword: Triple<Int, String, String>? = null
    private var pendingTimedBan: PendingTimedBan? = null
    @Volatile
    private var pendingBanAddress: ByteArray? = null
    private val pendingKickSent = AtomicBoolean(false)
    var passwordJoinChannelId: Int? = null

    fun setTokens(tokens: List<String>) {
        _accessTokens.value = tokens
    }

    fun tokens(): List<String> = _accessTokens.value

    fun clearTokens() {
        _accessTokens.value = emptyList()
    }

    fun beginUserListRequest(clear: Boolean, connected: Boolean): Boolean {
        if (!connected) {
            _userListRefreshing.value = false
            return false
        }
        if (clear) {
            _registeredUsers.value = null
            _userListRefreshing.value = false
        } else {
            _userListRefreshing.value = true
        }
        return true
    }

    fun onUserList(users: List<RegisteredUser>) {
        _registeredUsers.value = users.sortedBy { it.name.lowercase() }
        _userListRefreshing.value = false
    }

    fun renameRegisteredUser(userId: Int, newName: String): RegisteredUser? {
        val name = newName.trim()
        if (name.isEmpty()) return null
        val current = _registeredUsers.value ?: return null
        if (current.any { it.userId != userId && it.name == name }) return null
        _registeredUsers.value = current.map { if (it.userId == userId) it.copy(name = name) else it }
        return RegisteredUser(userId, name)
    }

    fun unregisterUser(userId: Int): RegisteredUser? {
        if (userId == 0) return null
        val current = _registeredUsers.value ?: return null
        _registeredUsers.value = current.filter { it.userId != userId }
        return RegisteredUser(userId)
    }

    fun beginBanListRequest(clear: Boolean, connected: Boolean): Boolean {
        if (!connected) {
            _banListRefreshing.value = false
            return false
        }
        if (clear) {
            _banList.value = null
            _banListRefreshing.value = false
        } else {
            _banListRefreshing.value = true
        }
        return true
    }

    fun setBanList(bans: List<BanEntry>) {
        _banList.value = bans
    }

    fun queueCreatePassword(parentId: Int, name: String, password: String) {
        val token = password.trim()
        pendingCreatePassword = if (token.isEmpty()) null else Triple(parentId, name, token)
    }

    fun maybeCreatePassword(isNew: Boolean, channel: Channel): Pair<Int, String>? {
        if (!isNew) return null
        val pending = pendingCreatePassword ?: return null
        if (channel.parentId != pending.first || channel.name != pending.second) return null
        pendingCreatePassword = null
        return channel.id to pending.third
    }

    fun onAcl(acl: dev.woms.mumdroid.core.proto.ACL): Pair<dev.woms.mumdroid.core.proto.ACL, String>? {
        lastAclQuery = acl
        _channelAclPassword.value = ChannelAclPassword(
            acl.channelId,
            ChannelPasswordAcl.extractPassword(acl),
        )
        val pending = pendingPasswordApply
        if (pending != null && pending.first == acl.channelId) {
            pendingPasswordApply = null
            return acl to pending.second
        }
        return null
    }

    /**
     * @return ACL snapshot + password to send now, or the channel id to query,
     *   or null if nothing to do (empty password with no snapshot).
     */
    fun preparePasswordApply(channelId: Int, password: String): PasswordApply? {
        val token = password.trim()
        val snap = lastAclQuery
        if (snap != null && snap.channelId == channelId) {
            return PasswordApply.Send(snap, token)
        }
        if (token.isEmpty()) return null
        pendingPasswordApply = channelId to token
        return PasswordApply.Query(channelId)
    }

    fun passwordAclMessage(
        snap: dev.woms.mumdroid.core.proto.ACL,
        password: String,
    ): dev.woms.mumdroid.core.proto.ACL? {
        val msg = ChannelPasswordAcl.apply(snap, password) ?: return null
        _channelAclPassword.value = ChannelAclPassword(snap.channelId, password)
        return msg
    }

    fun promptForChannelPassword(
        denied: dev.woms.mumdroid.core.proto.PermissionDenied,
        channel: Channel?,
        enterPermission: Int,
        onDenied: (String) -> Unit,
        passwordDeniedMessage: (String) -> String,
    ): Boolean {
        if (denied.type != dev.woms.mumdroid.core.proto.PermissionDenied.DenyType.Permission) {
            return false
        }
        if ((denied.permission and enterPermission) == 0) return false
        if (channel == null || !channel.isEnterRestricted) return false
        val retry = passwordJoinChannelId == channel.id
        passwordJoinChannelId = null
        _channelPasswordPrompt.value = ChannelPasswordPrompt(channel.id, channel.name, retry)
        onDenied(passwordDeniedMessage(channel.name))
        return true
    }

    fun clearPasswordPrompt() {
        _channelPasswordPrompt.value = null
    }

    fun notePasswordJoin(channelId: Int?) {
        passwordJoinChannelId = channelId
    }

    fun consumePasswordJoin(channelId: Int): Boolean {
        if (passwordJoinChannelId != channelId) return false
        passwordJoinChannelId = null
        return true
    }

    fun beginTimedBan(session: Int, user: User?, reason: String, duration: Int, banCertificate: Boolean, banIp: Boolean): Boolean {
        pendingTimedBan = if (duration > 0 && user != null) {
            pendingBanAddress = null
            pendingKickSent.set(false)
            PendingTimedBan(
                session = session,
                name = user.name,
                hash = user.hash,
                reason = reason.trim(),
                duration = duration,
                banCertificate = banCertificate,
                banIp = banIp,
                banListSnapshot = _banList.value,
            )
        } else {
            pendingBanAddress = null
            null
        }
        return pendingTimedBan != null
    }

    fun sendPendingTimedBanKick(): PendingKick? {
        if (!pendingKickSent.compareAndSet(false, true)) return null
        val pending = pendingTimedBan ?: return null
        return PendingKick(pending.session, pending.reason, pending.banCertificate, pending.banIp)
    }

    fun onBanList(bans: List<BanEntry>): List<BanEntry>? {
        _banListRefreshing.value = false
        val pending = pendingTimedBan
        if (pending != null) {
            val patched = TimedUserBan.applyDuration(
                bans,
                pending.name,
                pending.hash,
                pending.duration,
                pending.banListSnapshot,
                pendingBanAddress,
            )
            pendingTimedBan = null
            if (patched != null) return patched
        }
        _banList.value = bans
        return null
    }

    fun onUserRemovedBan(session: Int, banned: Boolean): Boolean {
        val pending = pendingTimedBan
        return banned && pending != null && pending.session == session
    }

    fun onUserStats(stats: dev.woms.mumdroid.core.proto.UserStats, userName: String): PendingKick? {
        val pending = pendingTimedBan
        val kick = if (pending != null && pending.session == stats.session &&
            pendingKickSent.compareAndSet(false, true)
        ) {
            pendingBanAddress = if (stats.hasAddress()) {
                stats.address.toByteArray()
            } else {
                ByteArray(0)
            }
            PendingKick(pending.session, pending.reason, pending.banCertificate, pending.banIp)
        } else {
            null
        }
        _userStats.value = UserConnectionInfo.fromProto(stats, userName, _userStats.value)
        return kick
    }

    fun clearUserStats() {
        _userStats.value = null
    }

    fun clearUserStatsIfSession(session: Int) {
        if (_userStats.value?.session == session) {
            _userStats.value = null
        }
    }

    fun requestUserList(client: MumbleClient?, clear: Boolean = true) {
        if (!beginUserListRequest(clear, client != null)) return
        val c = client ?: return
        scope.launch { c.requestUserList() }
    }

    fun renameRegisteredUser(client: MumbleClient?, userId: Int, newName: String) {
        val entry = renameRegisteredUser(userId, newName) ?: return
        val c = client ?: return
        scope.launch { c.sendUserList(listOf(entry)) }
    }

    fun unregisterUser(client: MumbleClient?, userId: Int) {
        val entry = unregisterUser(userId) ?: return
        val c = client ?: return
        scope.launch { c.sendUserList(listOf(entry)) }
    }

    fun requestBanList(client: MumbleClient?, clear: Boolean = true) {
        if (!beginBanListRequest(clear, client != null)) return
        val c = client ?: return
        scope.launch { c.requestBanList() }
    }

    fun replaceBanList(client: MumbleClient?, bans: List<BanEntry>) {
        setBanList(bans)
        val c = client ?: return
        scope.launch { c.sendBanList(bans) }
    }

    fun handleBanList(client: MumbleClient?, bans: List<BanEntry>) {
        val patched = onBanList(bans)
        if (patched != null) replaceBanList(client, patched)
    }

    fun kickUser(client: MumbleClient?, session: Int, reason: String) {
        client?.kickUser(session, reason)
    }

    fun registerUser(client: MumbleClient?, session: Int) {
        client?.registerUser(session)
    }

    fun requestUserStats(client: MumbleClient?, session: Int, statsOnly: Boolean = false) {
        client?.requestUserStats(session, statsOnly)
    }

    fun requestAcl(client: MumbleClient?, channelId: Int) {
        val c = client ?: return
        scope.launch { c.requestAcl(channelId) }
    }

    fun banUser(
        client: MumbleClient?,
        session: Int,
        user: User?,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
        duration: Int,
    ) {
        val c = client ?: return
        if (beginTimedBan(session, user, reason, duration, banCertificate, banIp)) {
            c.requestUserStats(session, statsOnly = false)
            scope.launch {
                delay(TIMED_BAN_STATS_WAIT_MS)
                val kick = sendPendingTimedBanKick() ?: return@launch
                c.banUser(kick.session, kick.reason, kick.banCertificate, kick.banIp)
            }
            return
        }
        c.banUser(session, reason, banCertificate, banIp)
    }

    fun handleUserStats(client: MumbleClient?, stats: dev.woms.mumdroid.core.proto.UserStats, userName: String) {
        val kick = onUserStats(stats, userName)
        if (kick != null) {
            client?.banUser(kick.session, kick.reason, kick.banCertificate, kick.banIp)
        }
    }

    fun handleUserRemovedBan(client: MumbleClient?, session: Int, banned: Boolean) {
        if (onUserRemovedBan(session, banned)) {
            val c = client ?: return
            scope.launch { c.requestBanList() }
        }
    }

    fun applyChannelPassword(
        client: MumbleClient?,
        channelId: Int,
        password: String,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        when (val action = preparePasswordApply(channelId, password)) {
            is PasswordApply.Send -> sendPasswordAcl(client, action.snap, action.password, persistToken)
            is PasswordApply.Query -> {
                val c = client ?: return
                scope.launch { c.requestAcl(action.channelId) }
            }
            null -> Unit
        }
    }

    fun handleAcl(
        client: MumbleClient?,
        acl: dev.woms.mumdroid.core.proto.ACL,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        val pending = onAcl(acl) ?: return
        sendPasswordAcl(client, pending.first, pending.second, persistToken)
    }

    fun replaceAccessTokens(
        tokens: List<String>,
        store: ChannelAccessTokenStore,
        host: String,
        port: Int,
        client: MumbleClient?,
    ) {
        val sanitized = AccessTokens.sanitize(tokens)
        setTokens(sanitized)
        val c = client
        scope.launch {
            store.replaceTokens(host, port, sanitized)
            c?.setTokens(sanitized)
        }
    }

    suspend fun persistAccessToken(
        channelId: Int,
        token: String,
        store: ChannelAccessTokenStore,
        host: String,
        port: Int,
    ) {
        val next = AccessTokens.sanitize(AccessTokens.add(tokens(), token))
        setTokens(next)
        store.upsert(host, port, channelId, token)
        setTokens(store.replaceTokens(host, port, next))
    }

    private fun sendPasswordAcl(
        client: MumbleClient?,
        snap: dev.woms.mumdroid.core.proto.ACL,
        password: String,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        val msg = passwordAclMessage(snap, password) ?: return
        val c = client ?: return
        scope.launch {
            if (password.isNotEmpty()) {
                persistToken(snap.channelId, password)
                c.setTokens(tokens())
            }
            c.sendAcl(msg)
        }
    }

    fun clear() {
        lastAclQuery = null
        pendingPasswordApply = null
        pendingCreatePassword = null
        _channelAclPassword.value = null
        _registeredUsers.value = null
        _banList.value = null
        _userListRefreshing.value = false
        _banListRefreshing.value = false
        pendingTimedBan = null
        pendingBanAddress = null
        pendingKickSent.set(false)
        _channelPasswordPrompt.value = null
        passwordJoinChannelId = null
        _userStats.value = null
    }

    data class PendingKick(
        val session: Int,
        val reason: String,
        val banCertificate: Boolean,
        val banIp: Boolean,
    )

    sealed class PasswordApply {
        data class Send(val snap: dev.woms.mumdroid.core.proto.ACL, val password: String) : PasswordApply()
        data class Query(val channelId: Int) : PasswordApply()
    }
}
