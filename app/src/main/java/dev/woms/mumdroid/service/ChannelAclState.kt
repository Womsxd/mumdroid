package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.net.AclUserNames
import dev.woms.mumdroid.core.net.ChanAclSnapshot
import dev.woms.mumdroid.core.net.ChanAclWrite
import dev.woms.mumdroid.core.net.ChannelPasswordAcl
import dev.woms.mumdroid.core.proto.ACL
import dev.woms.mumdroid.core.proto.PermissionDenied
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the caller must do to apply a channel password. */
internal sealed class PasswordApply {
    /** The ACL query already answered: send this snapshot with [password]. */
    data class Send(val snap: ACL, val password: String) : PasswordApply()

    /** No snapshot yet: query [channelId] and retry when the reply arrives. */
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
    private var lastAclQuery: ACL? = null

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

    /** Records an ACL reply; returns the channel + password to send now, if any. */
    fun onAcl(acl: ACL): Pair<ACL, String>? {
        lastAclQuery = acl
        _channelAcl.value = ChanAclWrite.fromProto(acl)
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

    /** Builds the ACL write that applies [password]; null when unchanged. */
    fun passwordAclMessage(snap: ACL, password: String): ACL? {
        val msg = ChannelPasswordAcl.apply(snap, password) ?: return null
        _channelAclPassword.value = ChannelAclPassword(snap.channelId, password)
        return msg
    }

    /**
     * Turns a denied join on a password-protected channel into the password
     * prompt. [retry] tells the dialog the previous password was rejected.
     *
     * @return true when the prompt took over the denial.
     */
    fun promptForChannelPassword(
        denied: PermissionDenied,
        channel: Channel?,
        enterPermission: Long,
        onDenied: (String) -> Unit,
        passwordDeniedMessage: (String) -> String,
    ): Boolean {
        if (denied.type != PermissionDenied.DenyType.Permission) return false
        if ((ChanACL.fromProtoUInt32(denied.permission) and enterPermission) == 0L) return false
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

    fun onQueryUsers(ids: List<Int>, names: List<String>) {
        _aclUserNames.value = _aclUserNames.value.merge(ids, names)
    }

    fun clear() {
        lastAclQuery = null
        pendingPasswordApply = null
        pendingCreatePassword = null
        _channelAcl.value = null
        _aclUserNames.value = AclUserNames()
        _channelAclPassword.value = null
        _channelPasswordPrompt.value = null
        passwordJoinChannelId = null
    }
}
