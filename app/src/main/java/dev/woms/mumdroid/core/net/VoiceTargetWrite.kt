package dev.woms.mumdroid.core.net

import dev.woms.mumdroid.core.model.VoiceTargetTarget
import dev.woms.mumdroid.core.proto.VoiceTarget

/**
 * Builds the `VoiceTarget` message (sent as [MessageType.VOICE_TARGET]) that
 * registers or clears a shout / whisper target. Owned here, next to
 * [VoiceTargetRegistry], so the session layer only names domain types.
 */
object VoiceTargetWrite {

    /** `VoiceTarget` with [id] and one entry per [targets] element. */
    fun message(id: Int, targets: List<VoiceTargetTarget>): VoiceTarget {
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
