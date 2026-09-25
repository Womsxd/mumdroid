package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanAclSnapshot
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.PermissionDeny
import dev.woms.mumdroid.core.net.ChannelAclReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the caller must do to apply a channel password. */
internal sealed class PasswordApply {
    /** The ACL query already answered: send this reply with [password]. */
    data class Send(val reply: ChannelAclReply, val password: String) : PasswordApply()

    /** No reply yet: query [channelId] and retry when it arrives. */
    data class Query(val channelId: Int) : PasswordApply()
}

/**
 * Channel ACL editor and channel-password state.
 *
 * Owns the ACL query/apply handshake (`apply` waits for the query reply, then
 * carries the password over), the "create channel with a password" deferral
 * (the channel id only exists after the server echoes the new channel), the
 * denied-join prompt and the password retry latch, plus the registered-user
 * name cache the ACL editor resolves ids against.
 *
 * The TCP sends triggered by these decisions stay in `ServerAdminSession`.
 */
internal class ChannelAclState {

    private val _channelAclPassword = MutableStateFlow<ChannelAclPassword?>(null)
    val channelAclPassword: StateFlow<ChannelAclPassword?> = _channelAclPassword

    private val _channelAcl = MutableStateFlow<ChanAclSnapshot?>(null)
    val channelAcl: StateFlow<ChanAclSnapshot?> = _channelAcl

    private val _aclUserNames = MutableStateFlow(AclUserNames())
    val aclUserNames: StateFlow<AclUserNames> = _aclUserNames

    private val _channelPasswordPrompt = MutableStateFlow<ChannelPasswordPrompt?>(null)
    val channelPasswordPrompt: StateFlow<ChannelPasswordPrompt?> = _channelPasswordPrompt

    /** Last ACL reply, kept so [preparePasswordApply] can skip a second query. */
    private var lastAclReply: ChannelAclReply? = null

    /** Password waiting for the ACL reply of its channel. */
    private var pendingPasswordApply: Pair<Int, String>? = null

    /** Password to attach to a channel that is still being created. */
    private var pendingCreatePassword: Triple<Int, String, String>? = null

    /** Channel the local user is joining with a password; set before the move. */
    var passwordJoinChannelId: Int? = null
        private set

    fun queueCreatePassword(parentId: Int, name: String, password: String) {
        val token = password.trim()
        pendingCreatePassword = if (token.isEmpty()) null else Triple(parentId, name, token)
    }

    /** The password for a channel the server just echoed, or null. */
    fun maybeCreatePassword(isNew: Boolean, channel: Channel): Pair<Int, String>? {
        if (!isNew) return null
        val pending = pendingCreatePassword ?: return null
        if (channel.parentId != pending.first || channel.name != pending.second) return null
        pendingCreatePassword = null
        return channel.id to pending.third
    }

    /** Records an ACL reply; returns the reply + password to send now, if any. */
    fun onAcl(reply: ChannelAclReply): Pair<ChannelAclReply, String>? {
        lastAclReply = reply
        _channelAcl.value = reply.snapshot()
        _channelAclPassword.value = ChannelAclPassword(reply.channelId, reply.password())
        val pending = pendingPasswordApply
        if (pending != null && pending.first == reply.channelId) {
            pendingPasswordApply = null
            return reply to pending.second
        }
        return null
    }

    /**
     * @return ACL reply + password to send now, or the channel id to query,
     *   or null if nothing to do (empty password with no reply).
     */
    fun preparePasswordApply(channelId: Int, password: String): PasswordApply? {
        val token = password.trim()
        val reply = lastAclReply
        if (reply != null && reply.channelId == channelId) {
            return PasswordApply.Send(reply, token)
        }
        if (token.isEmpty()) return null
        pendingPasswordApply = channelId to token
        return PasswordApply.Query(channelId)
    }

    /** Builds the ACL write that applies [password]; null when unchanged. */
    fun passwordAclMessage(reply: ChannelAclReply, password: String): ChannelAclReply? {
        val msg = reply.withPassword(password) ?: return null
        _channelAclPassword.value = ChannelAclPassword(reply.channelId, password)
        return msg
    }

    /**
     * Turns a denied join on a password-protected channel into the password
     * prompt. [retry] tells the dialog the previous password was rejected.
     *
     * @return true when the prompt took over the denial.
     */
    fun promptForChannelPassword(
        deny: PermissionDeny,
        channel: Channel?,
        enterPermission: Long,
        onDenied: (String) -> Unit,
        passwordDeniedMessage: (String) -> String,
    ): Boolean {
        if (deny.type != PermissionDeny.DenyType.PERMISSION) return false
        if ((deny.permission and enterPermission) == 0L) return false
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

    /** Consumes the latch when the local user lands in the expected channel. */
    fun consumePasswordJoin(channelId: Int): Boolean {
        if (passwordJoinChannelId != channelId) return false
        passwordJoinChannelId = null
        return true
    }

    /**
     * Merges a `QueryUsers` answer into the name map. `update` is a CAS loop:
     * this runs on the TCP read thread while [clear] can arrive from the ping
     * timer's `onDisconnected` or from `MumbleService.disconnect()` on
     * `Dispatchers.Default`, and a plain read-modify-write could overwrite that
     * clear with the pre-clear value. [ChannelAclState] outlives a connection
     * (`ServerAdminSession` is created once per service), so a lost clear would
     * leave the previous server's names in the next session.
     */
    fun onQueryUsers(ids: List<Int>, names: List<String>) {
        _aclUserNames.update { it.merge(ids, names) }
    }

    fun clear() {
        lastAclReply = null
        pendingPasswordApply = null
        pendingCreatePassword = null
        _channelAcl.value = null
        // Same reason as [onQueryUsers]: sequenced against the merge instead of
        // being a plain store that a racing CAS could be based on.
        _aclUserNames.update { AclUserNames() }
        _channelAclPassword.value = null
        _channelPasswordPrompt.value = null
        passwordJoinChannelId = null
    }
}
