package dev.woms.mumdroid.core.model

/**
 * Channel-to-channel voice links (`ChannelState.links` / `links_add` /
 * `links_remove`), matching desktop `Channel::link` / `allLinks()`.
 */
object ChannelLinks {
    data class Menu(
        val showLink: Boolean,
        val showUnlink: Boolean,
        val showUnlinkAll: Boolean,
    ) {
        val any: Boolean get() = showLink || showUnlink || showUnlinkAll
    }

    /**
     * Desktop `msgChannelState`: a non-empty `links` list replaces the set;
     * otherwise apply add/remove. An empty `links` field is indistinguishable
     * from "unset" in protobuf, so it does not clear partners.
     */
    fun nextDirectLinks(
        existing: Set<Int>,
        replace: Collection<Int>,
        add: Collection<Int>,
        remove: Collection<Int>,
    ): Set<Int> {
        if (replace.isNotEmpty()) return replace.toSet()
        return existing - remove.toSet() + add
    }

    fun syncPartners(
        map: MutableMap<Int, Channel>,
        channelId: Int,
        previous: Set<Int>,
        next: Set<Int>,
    ) {
        for (id in previous - next) {
            val other = map[id] ?: continue
            map[id] = other.copy(linkedIds = other.linkedIds - channelId)
        }
        for (id in next - previous) {
            val other = map[id] ?: continue
            map[id] = other.copy(linkedIds = other.linkedIds + channelId)
        }
    }

    fun collect(channels: List<Channel>): Map<Int, Set<Int>> {
        val out = mutableMapOf<Int, Set<Int>>()
        fun walk(ch: Channel) {
            out[ch.id] = ch.linkedIds
            ch.children.forEach(::walk)
        }
        channels.forEach(::walk)
        return out
    }

    /** Desktop `Channel::allLinks()`, including [start]. */
    fun allLinkedIds(linksById: Map<Int, Set<Int>>, start: Int): Set<Int> {
        val seen = mutableSetOf(start)
        val stack = ArrayDeque<Int>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            for (other in linksById[id] ?: emptySet()) {
                if (seen.add(other)) stack.add(other)
            }
        }
        return seen
    }

    /**
     * Which of Link / Unlink / Unlink All to show. Unlike desktop (which
     * always lists all three and greys them), we show only the actions that
     * apply: Link xor Unlink for the selected channel, plus Unlink All when
     * the current channel has partners.
     */
    fun menu(
        homeId: Int,
        targetId: Int,
        homeDirectLinks: Set<Int>,
        targetInHomeComponent: Boolean,
        homeCanLink: Boolean,
        targetCanLink: Boolean,
    ): Menu {
        val notHome = homeId != targetId
        val linked = notHome && targetInHomeComponent
        return Menu(
            showLink = notHome && !linked && homeCanLink && targetCanLink,
            showUnlink = linked && (homeCanLink || targetCanLink),
            showUnlinkAll = homeDirectLinks.isNotEmpty() && homeCanLink,
        )
    }
}
