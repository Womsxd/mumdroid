package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.model.VoiceTargetTarget
import dev.woms.mumdroid.core.net.MessageType
import dev.woms.mumdroid.core.net.VoiceTargetRegistry
import dev.woms.mumdroid.core.proto.VoiceTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the active voice target for the session: the user's speaking intent
 * ([VoiceTargetSpec]), the id registered for it on the server, and the target
 * id the send path must stamp on outgoing audio.
 *
 * Responsibilities:
 *  - validate the intent against the live roster, so a target whose receivers
 *    are gone becomes explicitly *unsendable* ([VoiceTargetId.NONE]) instead of
 *    degrading into a channel-wide broadcast,
 *  - turn [VoiceTargetRegistry] operations into `VoiceTarget` control messages,
 *  - re-validate on roster changes and revoke the registrations on teardown
 *    (murmur keeps a client's targets until they are cleared).
 *
 * Threading: [setSpec] / [clear] / [refresh] run on the command and roster
 * threads; [sendTargetId] is read from the audio capture thread. Only the
 * StateFlow read crosses that boundary, mirroring the official client's
 * unlocked `Global::iTarget` access.
 */
internal class VoiceTargetController(private val roster: SessionRoster) {
    private val registry = VoiceTargetRegistry()

    private val _status = MutableStateFlow(VoiceTargetStatus())
    val status: StateFlow<VoiceTargetStatus> = _status

    private val _sendTargetId = MutableStateFlow(VoiceTargetId.REGULAR_SPEECH)

    /** Id stamped on the next outgoing frame; 0 = regular speech, -1 = send nothing. */
    val sendTargetId: Int get() = _sendTargetId.value

    /**
     * Control-channel sink for the registration messages. Set while a client is
     * attached and cleared with it, so a teardown cannot write to a dead socket.
     */
    @Volatile
    var send: ((type: Int, message: com.google.protobuf.MessageLite) -> Unit)? = null

    /**
     * Talk state to show for the local user while transmitting: whispering for a
     * user target, shouting for a channel target, plain talking otherwise.
     * Null when a target is set but cannot send anything — audio is withheld
     * then, so no talking indicator may appear either.
     */
    fun activeTalkState(): TalkState? = when (val spec = _status.value.spec) {
        null -> TalkState.TALKING
        else -> if (!_status.value.available) {
            null
        } else {
            when (spec) {
                is VoiceTargetSpec.Users -> TalkState.WHISPERING
                is VoiceTargetSpec.Channel -> TalkState.SHOUTING
            }
        }
    }

    /** Applies a new speaking intent. Null restores regular speech. */
    fun setSpec(spec: VoiceTargetSpec?) {
        if (spec == null) {
            clear()
            return
        }
        val previous = _status.value.spec
        val usable = resolveAgainstRoster(spec)
        if (usable == null) {
            // Nothing to send to: revoke whatever was registered before and
            // report the unusable intent. The last known names are kept so the
            // chip can still name the receivers the user picked.
            val lastStatus = _status.value
            revoke(previous ?: spec)
            _sendTargetId.value = VoiceTargetId.NONE
            _status.value = VoiceTargetStatus(
                spec = spec,
                channelName = channelNameFor(spec).ifEmpty {
                    if (lastStatus.spec == spec) lastStatus.channelName else ""
                },
                userNames = userNamesFor(spec).mapIndexed { index, name ->
                    if (name.toIntOrNull() == null) name
                    else lastStatus.userNames.getOrNull(index) ?: name
                },
                available = false,
            )
            return
        }

        // A changed receiver set is a different target for the server, so the
        // previous registration is revoked in the same ordered control channel
        // that replaces it — never before it is no longer reachable by old audio.
        revoke(previous?.takeIf { it != usable })
        if (usable != spec) revoke(spec)

        val resolution = registry.resolve(usable)
        for (op in resolution.ops) {
            when (op) {
                is VoiceTargetRegistry.Op.Register -> emit(build(op.id, listOf(op.target)))
                is VoiceTargetRegistry.Op.Clear -> emit(build(op.id, emptyList()))
            }
        }
        _sendTargetId.value = resolution.targetId
        _status.value = VoiceTargetStatus(
            spec = usable,
            channelName = channelNameFor(usable),
            userNames = userNamesFor(usable),
        )
    }

    /** Clears the target and revokes every server-side registration. */
    fun clear() {
        if (_status.value.isRegular && registry.activeIds.isEmpty()) return
        for (op in registry.releaseAll()) {
            if (op is VoiceTargetRegistry.Op.Clear) emit(build(op.id, emptyList()))
        }
        _status.value = VoiceTargetStatus()
        _sendTargetId.value = VoiceTargetId.REGULAR_SPEECH
    }

    /** Forgets all local state without sending anything (disconnect). */
    fun reset() {
        registry.reset()
        _status.value = VoiceTargetStatus()
        _sendTargetId.value = VoiceTargetId.REGULAR_SPEECH
    }

    /**
     * Re-checks the active target against the live roster: receivers that
     * disappeared are dropped, the registration is refreshed, and a target left
     * without receivers is reported as unavailable so nothing is sent at all.
     */
    fun refresh() {
        // A target that already lost every receiver stays inert: session ids are
        // recycled by the server, so re-binding it when somebody else happens to
        // take the number over would silently send the whisper to a stranger.
        // Only an explicit new choice (setSpec / clear) revives speaking.
        if (!_status.value.available) return
        val spec = _status.value.spec ?: return
        setSpec(spec)
    }

    fun onRosterChanged() {
        if (_status.value.spec == null) return
        refresh()
    }

    /**
     * Restricts [spec] to receivers that still exist.
     *
     * @return the restricted spec, or null when none of the receivers is left.
     */
    private fun resolveAgainstRoster(spec: VoiceTargetSpec): VoiceTargetSpec? = when (spec) {
        is VoiceTargetSpec.Users -> {
            val alive = spec.sessions.filter { roster.userMap.containsKey(it) }
            if (alive.isEmpty()) null else VoiceTargetSpec.Users(alive)
        }
        is VoiceTargetSpec.Channel -> {
            if (roster.channelMap.containsKey(spec.channelId)) spec else null
        }
    }

    private fun channelNameFor(spec: VoiceTargetSpec): String = when (spec) {
        is VoiceTargetSpec.Channel -> roster.channelMap[spec.channelId]?.name.orEmpty()
        is VoiceTargetSpec.Users -> ""
    }

    /** Display names of the whisper receivers, keeping ids when a name is gone. */
    private fun userNamesFor(spec: VoiceTargetSpec): List<String> = when (spec) {
        is VoiceTargetSpec.Users -> spec.sessions.map { roster.userMap[it]?.name ?: it.toString() }
        is VoiceTargetSpec.Channel -> emptyList()
    }

    private fun revoke(spec: VoiceTargetSpec?) {
        val id = spec?.let { registry.forget(it) } ?: return
        emit(build(id, emptyList()))
    }

    private fun emit(message: VoiceTarget) {
        send?.invoke(MessageType.VOICE_TARGET, message)
    }

    private fun build(id: Int, targets: List<VoiceTargetTarget>): VoiceTarget {
        val builder = VoiceTarget.newBuilder().setId(id)
        for (target in targets) {
            val tb = VoiceTarget.Target.newBuilder()
            target.sessions.forEach { tb.addSession(it) }
            target.channelId?.let { tb.setChannelId(it) }
            if (target.group.isNotEmpty()) tb.setGroup(target.group)
            if (target.links) tb.setLinks(true)
            if (target.children) tb.setChildren(true)
            builder.addTargets(tb)
        }
        return builder.build()
    }
}
