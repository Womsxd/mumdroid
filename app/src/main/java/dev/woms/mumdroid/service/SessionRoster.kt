package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelLinks
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.ChannelUpdate
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserUpdate
import dev.woms.mumdroid.core.net.UserStateMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Live channel tree, user table, local mute/ignore and listen-in.
 *
 * The ACL bits the server reported and the questions asked of them live in
 * [RosterPermissions], held here as [acl]: they are a cache of one map rather
 * than roster state, and nothing in them reads the tables above.
 */
internal class SessionRoster(private val scope: CoroutineScope) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users

    private val _listeningChannels = MutableStateFlow<Set<Int>>(emptySet())
    val listeningChannels: StateFlow<Set<Int>> = _listeningChannels

    val channelMap = ConcurrentHashMap<Int, Channel>()
    val userMap = ConcurrentHashMap<Int, User>()
    val localBlockSet = ConcurrentHashMap.newKeySet<Int>()
    val localIgnoreSet = ConcurrentHashMap.newKeySet<Int>()
    val listeningBySession = ConcurrentHashMap<Int, MutableSet<Int>>()

    /** The ACL bits the server reported, and the queries built on them. */
    val acl = RosterPermissions()

    var localSession: Int = 0

    /**
     * Fired when a user or channel disappears from the roster, so the active
     * voice target can be re-resolved: a whisper target whose users all left
     * must stop transmitting rather than fall back to a channel broadcast.
     */
    var onRosterPruned: (() -> Unit)? = null

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

    /**
     * Applies a playback-liveness talk state for [session], mirroring official
     * `ClientUser::setTalking`. A speak-blocked user (mute / deaf / ACL
     * suppress) never shows a talking indicator.
     */
    fun setUserTalkState(session: Int, state: TalkState) {
        val user = userMap[session] ?: return
        val next = if (user.isSpeakBlocked) TalkState.PASSIVE else state
        if (user.talkState == next) return
        userMap[session] = user.copy(talkState = next)
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

    fun mergeChannelState(update: ChannelUpdate): Pair<Channel?, Channel> {
        val existing = channelMap[update.channelId]
        val previousLinks = existing?.linkedIds ?: emptySet()
        val nextLinks = ChannelLinks.nextDirectLinks(
            existing = previousLinks,
            replace = update.links ?: emptyList(),
            add = update.linksAdd,
            remove = update.linksRemove,
        )
        val merged = Channel(
            id = update.channelId,
            parentId = update.parentId ?: existing?.parentId ?: 0,
            name = update.name ?: existing?.name ?: "",
            description = update.description ?: existing?.description ?: "",
            position = update.position ?: existing?.position ?: 0,
            temporary = update.temporary ?: existing?.temporary ?: false,
            maxUsers = update.maxUsers ?: existing?.maxUsers ?: 0,
            isEnterRestricted = update.isEnterRestricted ?: existing?.isEnterRestricted ?: false,
            canEnter = update.canEnter ?: existing?.canEnter ?: true,
            linkedIds = nextLinks,
        )
        channelMap[merged.id] = merged
        ChannelLinks.syncPartners(channelMap, merged.id, previousLinks, nextLinks)
        return existing to merged
    }

    fun removeChannel(channelId: Int) {
        val gone = channelMap.remove(channelId)
        if (gone != null) onRosterPruned?.invoke()
        if (gone != null) {
            ChannelLinks.syncPartners(channelMap, channelId, gone.linkedIds, emptySet())
        }
        listeningBySession.values.forEach { it.remove(channelId) }
        if (localSession != 0) publishLocalListening()
        scope.launch { _channels.value = buildChannelTree() }
    }

    fun mergeUserState(update: UserUpdate): Pair<User?, User>? {
        val existing = userMap[update.session]
        if (!UserStateMerge.shouldApply(existing, update)) return null
        val selfMute = update.selfMute ?: existing?.selfMute ?: false
        val selfDeaf = update.selfDeaf ?: existing?.selfDeaf ?: false
        val mute = update.mute ?: existing?.mute ?: false
        val deaf = update.deaf ?: existing?.deaf ?: false
        val suppress = update.suppress ?: existing?.suppress ?: false
        val prioritySpeaker = update.prioritySpeaker ?: existing?.prioritySpeaker ?: false
        val speakBlocked = mute || deaf || suppress || selfMute || selfDeaf
        val updated = User(
            session = update.session,
            name = update.name ?: existing?.name ?: "",
            userId = update.userId ?: existing?.userId ?: -1,
            channelId = update.channelId ?: existing?.channelId ?: 0,
            selfMute = selfMute,
            selfDeaf = selfDeaf,
            mute = mute,
            deaf = deaf,
            suppress = suppress,
            prioritySpeaker = prioritySpeaker,
            talkState = if (speakBlocked) TalkState.PASSIVE else existing?.talkState ?: TalkState.PASSIVE,
            isLocalUser = update.session == localSession,
            localBlock = existing?.localBlock ?: (update.session in localBlockSet),
            localIgnore = existing?.localIgnore ?: (update.session in localIgnoreSet),
            hash = update.hash ?: existing?.hash.orEmpty(),
        )
        userMap[updated.session] = updated
        return existing to updated
    }

    fun removeUser(session: Int): User? {
        val removed = userMap.remove(session)
        if (removed != null) onRosterPruned?.invoke()
        listeningBySession.remove(session)
        if (session == localSession) _listeningChannels.value = emptySet()
        return removed
    }

    fun applyListeningChannels(
        session: Int,
        update: UserUpdate,
        onStarted: (channelId: Int) -> Unit,
        onStopped: (channelId: Int) -> Unit,
        onUserStarted: (actorName: String) -> Unit,
        onUserStopped: (actorName: String) -> Unit,
    ) {
        if (update.listeningAdded.isEmpty() && update.listeningRemoved.isEmpty()) return
        val set = listeningBySession.getOrPut(session) { ConcurrentHashMap.newKeySet() }
        val myChannel = userMap[localSession]?.channelId
        val actorName = userMap[session]?.name.orEmpty()
        for (channelId in update.listeningAdded) {
            if (!set.add(channelId)) continue
            when {
                session == localSession && localSession != 0 -> onStarted(channelId)
                myChannel != null && channelId == myChannel && session != localSession ->
                    onUserStarted(actorName)
            }
        }
        for (channelId in update.listeningRemoved) {
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

    fun clear() {
        channelMap.clear()
        userMap.clear()
        localBlockSet.clear()
        localIgnoreSet.clear()
        listeningBySession.clear()
        _listeningChannels.value = emptySet()
        acl.clear()
        localSession = 0
        _channels.value = emptyList()
        _users.value = emptyList()
    }
}
