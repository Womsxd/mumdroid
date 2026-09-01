package dev.woms.mumdroid.core.model

import java.text.Collator
import java.util.Locale

/**
 * Channel/user tree order matching the official desktop client.
 *
 * Channels: [Channel.cpp] `Channel::lessThan` — siblings by `iPosition`, then
 * name. Desktop `QString::localeAwareCompare` on Windows/English (and pinyin)
 * keeps Latin names before Han; Android's zh Collator does the opposite, so
 * names are grouped by script first, then collated inside the group.
 *
 * Users: [User.cpp] `User::lessThan` — case-insensitive name, then
 * case-sensitive. Not locale-aware, so every client sees the same user order.
 */
object ChannelTree {
    private val latinCollator = Collator.getInstance(Locale.ENGLISH).apply {
        strength = Collator.TERTIARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    private val hanCollator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.TERTIARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    /**
     * @param sameParent official `first->cParent == second->cParent`. Sibling
     * lists always pass true so a stale [Channel.parentId] cannot skip position.
     */
    fun compareChannels(
        first: Channel,
        second: Channel,
        sameParent: Boolean = first.parentId == second.parentId,
    ): Int {
        if (sameParent && first.position != second.position) {
            return first.position.compareTo(second.position)
        }
        val byName = compareChannelNames(first.name, second.name)
        if (byName != 0) return byName
        return first.id.compareTo(second.id)
    }

    /**
     * Desktop mixed-script order: ASCII/Latin first, then Han, then other.
     * Without this, zh Collator puts 「三角」above APEX.
     */
    internal fun compareChannelNames(first: String, second: String): Int {
        val g1 = scriptGroup(first)
        val g2 = scriptGroup(second)
        if (g1 != g2) return g1.compareTo(g2)
        val collator = if (g1 == SCRIPT_HAN) hanCollator else latinCollator
        synchronized(collator) {
            return collator.compare(first, second)
        }
    }

    fun compareUsers(first: User, second: User): Int {
        val insensitive = first.name.compareTo(second.name, ignoreCase = true)
        if (insensitive != 0) return insensitive
        return first.name.compareTo(second.name)
    }

    /**
     * Builds a parent/child tree and sorts every sibling list like the PC client.
     *
     * [listeningBySession] injects desktop `ModelItem(isListener)` proxies into
     * each listened channel, grouped above seated users.
     */
    fun build(
        channels: Collection<Channel>,
        users: Collection<User>,
        listeningBySession: Map<Int, Set<Int>> = emptyMap(),
    ): List<Channel> {
        val all = channels.map { it.copy(children = mutableListOf(), users = mutableListOf()) }
        val byId = all.associateBy { it.id }
        val roots = mutableListOf<Channel>()
        all.forEach { ch ->
            val parent = byId[ch.parentId]
            if (parent != null && ch.id != ch.parentId) {
                parent.children.add(ch)
            } else {
                roots.add(ch)
            }
        }
        users.forEach { user ->
            // Session 0 / empty name are leftover partial UserState packets
            // (e.g. suppress after UserRemove), not real connected users.
            if (user.session == 0 || user.name.isEmpty()) return@forEach
            byId[user.channelId]?.users?.add(user)
            val listened = listeningBySession[user.session] ?: return@forEach
            for (channelId in listened) {
                byId[channelId]?.users?.add(
                    user.copy(
                        talking = false,
                        isChannelListener = true,
                        listenerChannelId = channelId,
                    ),
                )
            }
        }
        fun sortNode(ch: Channel) {
            ch.children.sortWith { a, b -> compareChannels(a, b, sameParent = true) }
            ch.users.sortWith(::compareChannelUsers)
            ch.children.forEach(::sortNode)
        }
        roots.sortWith { a, b -> compareChannels(a, b, sameParent = true) }
        roots.forEach(::sortNode)
        return roots
    }

    /**
     * Desktop `ModelItem::insertIndex`: listeners grouped together, directly
     * above seated users; each group uses [compareUsers].
     */
    internal fun compareChannelUsers(first: User, second: User): Int {
        if (first.isChannelListener != second.isChannelListener) {
            return if (first.isChannelListener) -1 else 1
        }
        return compareUsers(first, second)
    }

    /** Depth-first listing for a channel picker, in the same sibling order as the tree. */
    fun flattenForPicker(channels: List<Channel>, indent: Int = 0): List<ChannelPick> {
        val out = mutableListOf<ChannelPick>()
        fun walk(ch: Channel, depth: Int) {
            out += ChannelPick(id = ch.id, name = ch.name, indent = depth)
            ch.children.forEach { walk(it, depth + 1) }
        }
        channels.forEach { walk(it, indent) }
        return out
    }

    fun find(channels: List<Channel>, id: Int): Channel? {
        for (ch in channels) {
            if (ch.id == id) return ch
            find(ch.children, id)?.let { return it }
        }
        return null
    }

    /** True when the row can show a disclosure control. Root is always expanded. */
    fun canCollapse(channel: Channel): Boolean =
        channel.id != 0 && (channel.children.isNotEmpty() || channel.users.isNotEmpty())

    fun toggleCollapsed(collapsed: Set<Int>, channelId: Int): Set<Int> =
        if (channelId in collapsed) collapsed - channelId else collapsed + channelId

    private const val SCRIPT_LATIN = 0
    private const val SCRIPT_HAN = 1
    private const val SCRIPT_OTHER = 2

    private fun scriptGroup(name: String): Int {
        val c = name.firstOrNull() ?: return SCRIPT_LATIN
        val script = Character.UnicodeScript.of(c.code)
        return when (script) {
            Character.UnicodeScript.HAN -> SCRIPT_HAN
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED,
            -> SCRIPT_LATIN
            else -> if (c.code < 0x80) SCRIPT_LATIN else SCRIPT_OTHER
        }
    }
}

/** One row in a channel-picker dialog. */
data class ChannelPick(
    val id: Int,
    val name: String,
    val indent: Int,
)
