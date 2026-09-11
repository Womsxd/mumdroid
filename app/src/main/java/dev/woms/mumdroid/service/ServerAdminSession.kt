package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.RegisteredUser
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.core.net.MumbleClient
import dev.woms.mumdroid.core.net.UserConnectionInfo
import dev.woms.mumdroid.data.ChannelAccessTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Server administration: the sends behind the registered-user list, ban list,
 * channel ACL / password flows and user moderation, plus the state machines
 * those flows are built on.
 *
 * The state lives in focused collaborators so it can be unit tested without a
 * socket ([AccessTokenState], [AdminListState], [ChannelAclState],
 * [TimedBanState], [AdminUserStats]); this class only wires them to the client
 * and exposes their flows. Every send is a no-op when [client] is null, so a
 * dropped session degrades to "nothing happens" instead of a crash.
 */
internal class ServerAdminSession(private val scope: CoroutineScope) {

    companion object {
        private const val TIMED_BAN_STATS_WAIT_MS = 3000L
    }

    private val tokensState = AccessTokenState(scope)
    private val lists = AdminListState()
    private val acl = ChannelAclState()
    private val timedBans = TimedBanState()
    private val stats = AdminUserStats()

    // ---- flows ----

    val accessTokens: StateFlow<List<String>> = tokensState.accessTokens
    val registeredUsers: StateFlow<List<RegisteredUser>?> = lists.registeredUsers
    val banList: StateFlow<List<BanEntry>?> = lists.banList
    val userListRefreshing: StateFlow<Boolean> = lists.userListRefreshing
    val banListRefreshing: StateFlow<Boolean> = lists.banListRefreshing
    val channelAclPassword = acl.channelAclPassword
    val channelAcl = acl.channelAcl
    val aclUserNames = acl.aclUserNames
    val channelPasswordPrompt = acl.channelPasswordPrompt
    val userStats: StateFlow<UserConnectionInfo?> = stats.userStats

    // ---- access tokens ----

    fun setTokens(tokens: List<String>) = tokensState.setTokens(tokens)

    fun tokens(): List<String> = tokensState.tokens()

    fun clearTokens() = tokensState.clearTokens()

    fun replaceAccessTokens(
        tokens: List<String>,
        store: ChannelAccessTokenStore,
        host: String,
        port: Int,
        client: MumbleClient?,
    ) {
        val c = client
        tokensState.replace(tokens, persistence(store, host, port)) { stored -> c?.setTokens(stored) }
    }

    suspend fun persistAccessToken(
        channelId: Int,
        token: String,
        store: ChannelAccessTokenStore,
        host: String,
        port: Int,
    ) {
        tokensState.persistChannelToken(channelId, token, persistence(store, host, port))
    }

    /** Binds the token bag to one server address. */
    private fun persistence(
        store: ChannelAccessTokenStore,
        host: String,
        port: Int,
    ): AccessTokenPersistence = object : AccessTokenPersistence {
        override suspend fun replaceServerTokens(tokens: List<String>): List<String> =
            store.replaceTokens(host, port, tokens)

        override suspend fun upsertChannelToken(channelId: Int, token: String) =
            store.upsert(host, port, channelId, token)
    }

    // ---- registered users ----

    fun onUserList(users: List<RegisteredUser>) = lists.onUserList(users)

    fun requestUserList(client: MumbleClient?, clear: Boolean = true) {
        if (!lists.beginUserListRequest(clear, client != null)) return
        val c = client ?: return
        scope.launch { c.requestUserList() }
    }

    /** Renames locally and echoes the change back to the server. */
    fun renameRegisteredUser(client: MumbleClient?, userId: Int, newName: String) {
        val entry = lists.renameRegisteredUser(userId, newName) ?: return
        val c = client ?: return
        scope.launch { c.sendUserList(listOf(entry)) }
    }

    /** Unregisters locally and echoes the removal back to the server. */
    fun unregisterUser(client: MumbleClient?, userId: Int) {
        val entry = lists.unregisterUser(userId) ?: return
        val c = client ?: return
        scope.launch { c.sendUserList(listOf(entry)) }
    }

    // ---- ban list ----

    fun setBanList(bans: List<BanEntry>) = lists.setBanList(bans)

    fun requestBanList(client: MumbleClient?, clear: Boolean = true) {
        if (!lists.beginBanListRequest(clear, client != null)) return
        val c = client ?: return
        scope.launch { c.requestBanList() }
    }

    fun replaceBanList(client: MumbleClient?, bans: List<BanEntry>) {
        lists.setBanList(bans)
        val c = client ?: return
        scope.launch { c.sendBanList(bans) }
    }

    /**
     * Handles a ban-list reply: a pending timed ban patches its duration into
     * the entry the server appended, and the result is written back.
     */
    fun handleBanList(client: MumbleClient?, bans: List<BanEntry>) {
        lists.endBanListRequest()
        val patched = timedBans.patchBanList(bans)
        if (patched != null) {
            replaceBanList(client, patched)
        } else {
            lists.setBanList(bans)
        }
    }

    // ---- channel ACL / password ----

    fun queueCreatePassword(parentId: Int, name: String, password: String) =
        acl.queueCreatePassword(parentId, name, password)

    fun maybeCreatePassword(isNew: Boolean, channel: Channel): Pair<Int, String>? =
        acl.maybeCreatePassword(isNew, channel)

    fun promptForChannelPassword(
        denied: dev.woms.mumdroid.core.proto.PermissionDenied,
        channel: Channel?,
        enterPermission: Long,
        onDenied: (String) -> Unit,
        passwordDeniedMessage: (String) -> String,
    ): Boolean = acl.promptForChannelPassword(denied, channel, enterPermission, onDenied, passwordDeniedMessage)

    fun clearPasswordPrompt() = acl.clearPasswordPrompt()

    fun notePasswordJoin(channelId: Int?) = acl.notePasswordJoin(channelId)

    fun consumePasswordJoin(channelId: Int): Boolean = acl.consumePasswordJoin(channelId)

    fun requestAcl(client: MumbleClient?, channelId: Int) {
        val c = client ?: return
        scope.launch { c.requestAcl(channelId) }
    }

    fun sendAcl(client: MumbleClient?, snapshot: ChanAclSnapshot) {
        val c = client ?: return
        scope.launch { c.sendAcl(snapshot) }
    }

    fun onQueryUsers(ids: List<Int>, names: List<String>) = acl.onQueryUsers(ids, names)

    fun queryUsersByName(client: MumbleClient?, names: List<String>) {
        if (names.isEmpty()) return
        val c = client ?: return
        scope.launch { c.queryUsers(names = names) }
    }

    fun queryUsersById(client: MumbleClient?, ids: List<Int>) {
        if (ids.isEmpty()) return
        val c = client ?: return
        scope.launch { c.queryUsers(ids = ids) }
    }

    /** Applies a channel password: reuse the ACL reply, else query and retry. */
    fun applyChannelPassword(
        client: MumbleClient?,
        channelId: Int,
        password: String,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        when (val action = acl.preparePasswordApply(channelId, password)) {
            is PasswordApply.Send -> sendPasswordAcl(client, action.snap, action.password, persistToken)
            is PasswordApply.Query -> {
                val c = client ?: return
                scope.launch { c.requestAcl(action.channelId) }
            }
            null -> Unit
        }
    }

    /** Handles an ACL reply that may carry the password the editor is waiting for. */
    fun handleAcl(
        client: MumbleClient?,
        acl: dev.woms.mumdroid.core.proto.ACL,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        val pending = this.acl.onAcl(acl) ?: return
        sendPasswordAcl(client, pending.first, pending.second, persistToken)
    }

    private fun sendPasswordAcl(
        client: MumbleClient?,
        snap: dev.woms.mumdroid.core.proto.ACL,
        password: String,
        persistToken: suspend (channelId: Int, token: String) -> Unit,
    ) {
        val msg = acl.passwordAclMessage(snap, password) ?: return
        val c = client ?: return
        scope.launch {
            if (password.isNotEmpty()) {
                persistToken(snap.channelId, password)
                c.setTokens(tokensState.tokens())
            }
            c.sendAcl(msg)
        }
    }

    // ---- moderation ----

    fun kickUser(client: MumbleClient?, session: Int, reason: String) {
        client?.kickUser(session, reason)
    }

    fun registerUser(client: MumbleClient?, session: Int) {
        client?.registerUser(session)
    }

    fun requestUserStats(client: MumbleClient?, session: Int, statsOnly: Boolean = false) {
        client?.requestUserStats(session, statsOnly)
    }

    /**
     * Bans a user. With a duration the ban is assembled in two replies: the
     * kick goes out after the `UserStats` request (which supplies the address
     * murmur will store), then the ban list reply is patched.
     */
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
        val armed = timedBans.begin(
            session = session,
            user = user,
            reason = reason,
            duration = duration,
            banCertificate = banCertificate,
            banIp = banIp,
            banListSnapshot = lists.banSnapshot(),
        )
        if (armed) {
            c.requestUserStats(session, statsOnly = false)
            scope.launch {
                delay(TIMED_BAN_STATS_WAIT_MS)
                val kick = timedBans.sendKick() ?: return@launch
                c.banUser(kick.session, kick.reason, kick.banCertificate, kick.banIp)
            }
            return
        }
        c.banUser(session, reason, banCertificate, banIp)
    }

    fun handleUserStats(
        client: MumbleClient?,
        stats: dev.woms.mumdroid.core.proto.UserStats,
        userName: String,
    ) {
        this.stats.onStats(stats, userName)
        // The raw proto address is what murmur will store in the ban entry;
        // the formatted snapshot string cannot be matched against it.
        val address = if (stats.hasAddress()) stats.address.toByteArray() else ByteArray(0)
        val kick = timedBans.sendKickWithAddress(stats.session, address)
        if (kick != null) {
            client?.banUser(kick.session, kick.reason, kick.banCertificate, kick.banIp)
        }
    }

    fun handleUserRemovedBan(client: MumbleClient?, session: Int, banned: Boolean) {
        if (timedBans.matchesRemoval(session, banned)) {
            val c = client ?: return
            scope.launch { c.requestBanList() }
        }
    }

    // ---- user comments / textures ----

    fun setUserComment(client: MumbleClient?, session: Int, comment: String) {
        client?.setUserComment(session, comment)
    }

    fun resetUserComment(client: MumbleClient?, session: Int) {
        client?.resetUserComment(session)
    }

    fun setUserTexture(client: MumbleClient?, session: Int, texture: ByteArray) {
        client?.setUserTexture(session, texture)
    }

    fun resetUserTexture(client: MumbleClient?, session: Int) {
        client?.resetUserTexture(session)
    }

    // ---- user stats snapshot ----

    fun clearUserStats() = stats.clear()

    fun clearUserStatsIfSession(session: Int) = stats.clearIfSession(session)

    /** Resets every flow and pending request for a new session. */
    fun clear() {
        // Access tokens are per server address, not per session: they survive
        // a disconnect and are restored by the connect flow.
        lists.clear()
        acl.clear()
        timedBans.reset()
        stats.clear()
    }
}
