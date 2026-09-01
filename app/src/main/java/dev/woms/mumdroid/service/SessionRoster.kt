package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelLinks
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStateMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Live channel tree, user table, local mute/ignore, listen-in, and ACL bits.
 */
internal class SessionRoster(private val scope: CoroutineScope) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users

    private val _permissionEpoch = MutableStateFlow(0)
    val permissionEpoch: StateFlow<Int> = _permissionEpoch

    private val _listeningChannels = MutableStateFlow<Set<Int>>(emptySet())
    val listeningChannels: StateFlow<Set<Int>> = _listeningChannels

    val channelMap = ConcurrentHashMap<Int, Channel>()
    val userMap = ConcurrentHashMap<Int, User>()
    val localBlockSet = ConcurrentHashMap.newKeySet<Int>()
    val localIgnoreSet = ConcurrentHashMap.newKeySet<Int>()
    val listeningBySession = ConcurrentHashMap<Int, MutableSet<Int>>()
    val channelPermissions = ConcurrentHashMap<Int, Int>()

    var localSession: Int = 0

    fun snapshotUsers(): List<User> =
        userMap.values
            .filter { it.session != 0 && it.name.isNotEmpty() }
            .sortedWith(ChannelTree::compareUsers)

    fun buildChannelTree(): List<Channel> =
        ChannelTree.build(
            channelMap.values,
            userMap.values,
            listeningBySession.mapValues { it.value.toSet() },
        )

    fun publish() {
        scope.launch {
            _users.value = snapshotUsers()
            _channels.value = buildChannelTree()
        }
    }

    fun publishNow() {
        _users.value = snapshotUsers()
        _channels.value = buildChannelTree()
    }

    fun publishChannelsNow() {
        _channels.value = buildChannelTree()
    }

    fun channelName(channelId: Int): String =
        channelMap[channelId]?.name ?: channelId.toString()

    fun localChannelName(): String {
        val id = userMap[localSession]?.channelId ?: return ""
        return channelMap[id]?.name?.trim().orEmpty()
    }

    fun localUser(): User? = userMap[localSession]

    fun isLocallyBlocked(session: Int): Boolean = session in localBlockSet

    fun isIgnored(session: Int?): Boolean = session != null && session in localIgnoreSet

    fun setUserTalking(session: Int, talking: Boolean) {
        val user = userMap[session] ?: return
        val next = talking && !user.isSpeakBlocked
        if (user.talking == next) return
        userMap[session] = user.copy(talking = next)
        publish()
    }

    fun updateLocalMuteDeafen(selfMuted: Boolean, selfDeafened: Boolean) {
        val local = userMap[localSession] ?: return
        userMap[localSession] = local.copy(
            selfMute = selfMuted,
            selfDeaf = selfDeafened,
        )
        publish()
    }

    fun setLocalBlock(session: Int, blocked: Boolean) {
        if (blocked) localBlockSet.add(session) else localBlockSet.remove(session)
        applyLocalFlags(session)
    }

    fun setLocalIgnore(session: Int, ignored: Boolean) {
        if (ignored) localIgnoreSet.add(session) else localIgnoreSet.remove(session)
        applyLocalFlags(session)
    }

    private fun applyLocalFlags(session: Int) {
        val user = userMap[session] ?: return
        userMap[session] = user.copy(
            localBlock = session in localBlockSet,
            localIgnore = session in localIgnoreSet,
        )
        publish()
    }

    fun putChannel(channel: Channel) {
        channelMap[channel.id] = channel
    }

    fun mergeChannelState(state: dev.woms.mumdroid.core.proto.ChannelState): Pair<Channel?, Channel> {
        val existing = channelMap[state.channelId]
        val previousLinks = existing?.linkedIds ?: emptySet()
        val nextLinks = ChannelLinks.nextDirectLinks(
            existing = previousLinks,
            replace = if (state.linksCount > 0) state.linksList else emptyList(),
            add = state.linksAddList,
            remove = state.linksRemoveList,
        )
        val merged = Channel(
            id = state.channelId,
            parentId = if (state.hasParent()) state.parent else existing?.parentId ?: 0,
            name = if (state.hasName()) state.name else existing?.name ?: "",
            description = if (state.hasDescription()) state.description else existing?.description ?: "",
            position = if (state.hasPosition()) state.position else existing?.position ?: 0,
            temporary = if (state.hasTemporary()) state.temporary else existing?.temporary ?: false,
            maxUsers = if (state.hasMaxUsers()) state.maxUsers else existing?.maxUsers ?: 0,
            isEnterRestricted = if (state.hasIsEnterRestricted()) {
                state.isEnterRestricted
            } else {
                existing?.isEnterRestricted ?: false
            },
            canEnter = if (state.hasCanEnter()) state.canEnter else existing?.canEnter ?: true,
            linkedIds = nextLinks,
        )
        channelMap[merged.id] = merged
        ChannelLinks.syncPartners(channelMap, merged.id, previousLinks, nextLinks)
        return existing to merged
    }

    fun removeChannel(channelId: Int) {
        val gone = channelMap.remove(channelId)
        if (gone != null) {
            ChannelLinks.syncPartners(channelMap, channelId, gone.linkedIds, emptySet())
        }
        listeningBySession.values.forEach { it.remove(channelId) }
        if (localSession != 0) publishLocalListening()
        scope.launch { _channels.value = buildChannelTree() }
    }

    fun mergeUserState(user: dev.woms.mumdroid.core.proto.UserState): Pair<User?, User>? {
        val existing = if (UserStateMerge.hasValidSession(user)) userMap[user.session] else null
        if (!UserStateMerge.shouldApply(existing, user)) return null
        val selfMute = if (user.hasSelfMute()) user.selfMute else existing?.selfMute ?: false
        val selfDeaf = if (user.hasSelfDeaf()) user.selfDeaf else existing?.selfDeaf ?: false
        val mute = if (user.hasMute()) user.mute else existing?.mute ?: false
        val deaf = if (user.hasDeaf()) user.deaf else existing?.deaf ?: false
        val suppress = if (user.hasSuppress()) user.suppress else existing?.suppress ?: false
        val prioritySpeaker =
            if (user.hasPrioritySpeaker()) user.prioritySpeaker else existing?.prioritySpeaker ?: false
        val speakBlocked = mute || deaf || suppress || selfMute || selfDeaf
        val updated = User(
            session = user.session,
            name = if (user.hasName()) user.name else existing?.name ?: "",
            userId = if (user.hasUserId()) user.userId else existing?.userId ?: -1,
            channelId = if (user.hasChannelId()) user.channelId else existing?.channelId ?: 0,
            selfMute = selfMute,
            selfDeaf = selfDeaf,
            mute = mute,
            deaf = deaf,
            suppress = suppress,
            prioritySpeaker = prioritySpeaker,
            talking = if (speakBlocked) false else existing?.talking ?: false,
            isLocalUser = user.session == localSession,
            localBlock = existing?.localBlock ?: (user.session in localBlockSet),
            localIgnore = existing?.localIgnore ?: (user.session in localIgnoreSet),
            hash = if (user.hasHash()) user.hash else existing?.hash.orEmpty(),
        )
        userMap[updated.session] = updated
        return existing to updated
    }

    fun removeUser(session: Int): User? {
        val removed = userMap.remove(session)
        listeningBySession.remove(session)
        if (session == localSession) _listeningChannels.value = emptySet()
        return removed
    }

    fun applyPermissionQuery(channelId: Int, permissions: Int, flush: Boolean) {
        if (flush) channelPermissions.clear()
        channelPermissions[channelId] = permissions
        _permissionEpoch.value++
    }

    fun applyListeningChannels(
        session: Int,
        msg: dev.woms.mumdroid.core.proto.UserState,
        onStarted: (channelId: Int) -> Unit,
        onStopped: (channelId: Int) -> Unit,
        onUserStarted: (actorName: String) -> Unit,
        onUserStopped: (actorName: String) -> Unit,
    ) {
        if (msg.listeningChannelAddCount == 0 && msg.listeningChannelRemoveCount == 0) return
        val set = listeningBySession.getOrPut(session) { ConcurrentHashMap.newKeySet() }
        val myChannel = userMap[localSession]?.channelId
        val actorName = userMap[session]?.name.orEmpty()
        for (channelId in msg.listeningChannelAddList) {
            if (!set.add(channelId)) continue
            when {
                session == localSession && localSession != 0 -> onStarted(channelId)
                myChannel != null && channelId == myChannel && session != localSession ->
                    onUserStarted(actorName)
            }
        }
        for (channelId in msg.listeningChannelRemoveList) {
            if (!set.remove(channelId)) continue
            when {
                session == localSession && localSession != 0 -> onStopped(channelId)
                myChannel != null && channelId == myChannel && session != localSession ->
                    onUserStopped(actorName)
            }
        }
        if (session == localSession) publishLocalListening()
    }

    fun publishLocalListening() {
        _listeningChannels.value =
            listeningBySession[localSession]?.toSet() ?: emptySet()
    }

    fun markLocalUser(session: Int) {
        localSession = session
        var changed = false
        for ((id, user) in userMap) {
            val shouldBeLocal = id == session
            if (user.isLocalUser != shouldBeLocal) {
                userMap[id] = user.copy(isLocalUser = shouldBeLocal)
                changed = true
            }
        }
        if (changed) publishNow()
        publishLocalListening()
    }

    fun permissions(channelId: Int): Int = channelPermissions[channelId] ?: 0

    fun hasPermissions(channelId: Int): Boolean = channelPermissions.containsKey(channelId)

    fun canAdministerChannel(channelId: Int): Boolean =
        ChanACL.canMuteDeafenOrWrite(permissions(channelId))

    fun canMuteUser(user: User): Boolean =
        ChanACL.canOfferMute(
            permissions(user.channelId),
            isSelf = user.isLocalUser,
            muted = user.mute,
            suppressed = user.suppress,
        )

    fun canPrioritySpeaker(user: User): Boolean =
        ChanACL.canPrioritySpeaker(permissions(user.channelId))

    fun canMoveInChannel(channelId: Int): Boolean =
        ChanACL.canMove(permissions(channelId))

    fun canKickUser(): Boolean = ChanACL.canKick(permissions(0))

    fun canBanUser(): Boolean = ChanACL.canBan(permissions(0))

    fun canEditRegisteredUsers(): Boolean = ChanACL.canRegisterOthers(permissions(0))

    fun canRegisterUser(user: User): Boolean =
        ChanACL.canOfferRegister(
            permissions(0),
            isSelf = user.isLocalUser,
            isRegistered = user.isRegistered,
            hasCertificate = user.hash.isNotEmpty(),
        )

    fun canTextMessage(channelId: Int): Boolean =
        ChanACL.canTextMessage(permissions(channelId))

    fun canListen(channelId: Int): Boolean =
        ChanACL.canListen(permissions(channelId))

    fun canWriteChannel(channelId: Int): Boolean =
        ChanACL.canWrite(permissions(channelId))

    fun canAddChannel(channelId: Int): Boolean =
        ChanACL.canAddChannel(permissions(channelId))

    fun canMakePermanentChannel(channelId: Int): Boolean =
        ChanACL.canMakePermanentChannel(permissions(channelId))

    fun canLinkChannel(channelId: Int): Boolean =
        ChanACL.canLinkChannel(permissions(channelId))

    fun clear() {
        channelMap.clear()
        userMap.clear()
        localBlockSet.clear()
        localIgnoreSet.clear()
        listeningBySession.clear()
        _listeningChannels.value = emptySet()
        channelPermissions.clear()
        localSession = 0
        _channels.value = emptyList()
        _users.value = emptyList()
    }
}
