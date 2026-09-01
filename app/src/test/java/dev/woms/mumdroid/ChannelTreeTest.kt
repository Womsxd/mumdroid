package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTreeTest {

    @Test
    fun siblings_sortByPositionThenName() {
        val root = Channel(id = 0, parentId = 0, name = "Root", position = 0)
        val late = Channel(id = 3, parentId = 0, name = "Alpha", position = 10)
        val early = Channel(id = 1, parentId = 0, name = "Zulu", position = -20)
        val midB = Channel(id = 4, parentId = 0, name = "Beta", position = 0)
        val midA = Channel(id = 2, parentId = 0, name = "Alpha", position = 0)

        val tree = ChannelTree.build(listOf(late, midB, root, early, midA), emptyList())
        val kids = tree.single { it.id == 0 }.children.map { it.id }
        // Official Channel::lessThan: lower iPosition first; equal position by name.
        assertEquals(listOf(1, 2, 4, 3), kids)
    }

    @Test
    fun nested_childrenSortedIndependently() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val lobby = Channel(id = 1, parentId = 0, name = "Lobby", position = 5)
        val games = Channel(id = 2, parentId = 0, name = "Games", position = 1)
        val z = Channel(id = 10, parentId = 2, name = "Zed", position = 0)
        val a = Channel(id = 11, parentId = 2, name = "Ace", position = 0)

        val tree = ChannelTree.build(listOf(z, lobby, a, games, root), emptyList())
        val top = tree.single { it.id == 0 }.children.map { it.id }
        assertEquals(listOf(2, 1), top)
        val nested = tree.single { it.id == 0 }.children.single { it.id == 2 }.children.map { it.id }
        assertEquals(listOf(11, 10), nested)
    }

    @Test
    fun users_caseInsensitiveThenCaseSensitive() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val bob = User(session = 1, name = "bob", channelId = 0)
        val Alice = User(session = 2, name = "Alice", channelId = 0)
        val alice = User(session = 3, name = "alice", channelId = 0)

        val tree = ChannelTree.build(listOf(root), listOf(bob, Alice, alice))
        val names = tree.single().users.map { it.name }
        // Case-insensitive groups Alice/alice before bob; then case-sensitive
        // ('A' < 'a') so Alice before alice — User::lessThan.
        assertEquals(listOf("Alice", "alice", "bob"), names)
    }

    @Test
    fun ghostUsers_withoutSessionOrName_areDropped() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val real = User(session = 4, name = "bob", channelId = 0)
        val nameless = User(session = 5, name = "", channelId = 0, suppress = true)
        val noSession = User(session = 0, name = "ghost", channelId = 0, suppress = true)

        val tree = ChannelTree.build(listOf(root), listOf(real, nameless, noSession))
        assertEquals(listOf("bob"), tree.single().users.map { it.name })
    }

    @Test
    fun siblings_usePositionEvenIfParentIdsDiffer() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        // Same children list after a parent move; position must still win.
        val first = Channel(id = 1, parentId = 0, name = "Zed", position = -10)
        val second = Channel(id = 2, parentId = 99, name = "Ace", position = 5)
        val order = listOf(second, first).sortedWith { a, b ->
            ChannelTree.compareChannels(a, b, sameParent = true)
        }
        assertEquals(listOf(1, 2), order.map { it.id })
    }

    @Test
    fun mixedLatinAndHan_matchesDesktopLatinFirst() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val apex = Channel(id = 1, parentId = 0, name = "APEX", position = 0)
        val minecraft = Channel(id = 2, parentId = 0, name = "Minecraft", position = 0)
        val triangle = Channel(id = 3, parentId = 0, name = "三角", position = 0)
        val gi = Channel(id = 4, parentId = 0, name = "gi", position = 1)

        val tree = ChannelTree.build(listOf(gi, triangle, apex, minecraft, root), emptyList())
        val names = tree.single { it.id == 0 }.children.map { it.name }
        // PC: APEX, Minecraft, 三角 (pos 0, Latin before Han), then gi (pos 1).
        assertEquals(listOf("APEX", "Minecraft", "三角", "gi"), names)
    }

    @Test
    fun flattenForPicker_walksDepthFirst() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val games = Channel(id = 2, parentId = 0, name = "Games", position = 1)
        val lobby = Channel(id = 1, parentId = 0, name = "Lobby", position = 5)
        val ace = Channel(id = 11, parentId = 2, name = "Ace")
        val tree = ChannelTree.build(listOf(root, games, lobby, ace), emptyList())
        val picks = ChannelTree.flattenForPicker(tree)
        assertEquals(listOf(0, 2, 11, 1), picks.map { it.id })
        assertEquals(listOf(0, 1, 2, 1), picks.map { it.indent })
        assertEquals(games, ChannelTree.find(tree, 2))
        assertEquals(ace, ChannelTree.find(tree, 11))
    }

    @Test
    fun listeners_appearInListenedChannelAboveSeatedUsers() {
        val root = Channel(id = 0, parentId = 0, name = "Root")
        val games = Channel(id = 2, parentId = 0, name = "Games", position = 1)
        val alice = User(session = 1, name = "Alice", channelId = 0)
        val bob = User(session = 2, name = "bob", channelId = 2)
        val tree = ChannelTree.build(
            listOf(root, games),
            listOf(alice, bob),
            listeningBySession = mapOf(1 to setOf(2), 2 to setOf(2)),
        )
        val gamesUsers = tree.single { it.id == 0 }.children.single { it.id == 2 }.users
        assertEquals(listOf(true, true, false), gamesUsers.map { it.isChannelListener })
        assertEquals(listOf("Alice", "bob", "bob"), gamesUsers.map { it.name })
        assertEquals(listOf(2, 2, 2), gamesUsers.map { if (it.isChannelListener) it.listenerChannelId else it.channelId })
        val rootUsers = tree.single { it.id == 0 }.users
        assertEquals(listOf("Alice"), rootUsers.map { it.name })
        assertEquals(listOf(false), rootUsers.map { it.isChannelListener })
    }

    @Test
    fun canCollapse_whenChildrenOrUsersPresent() {
        val empty = Channel(id = 1, name = "Lobby")
        assertEquals(false, ChannelTree.canCollapse(empty))
        assertEquals(
            true,
            ChannelTree.canCollapse(empty.copy(children = mutableListOf(Channel(id = 2, name = "A")))),
        )
        assertEquals(
            true,
            ChannelTree.canCollapse(empty.copy(users = mutableListOf(User(session = 1, name = "alice")))),
        )
        assertEquals(
            false,
            ChannelTree.canCollapse(
                Channel(id = 0, name = "Root", children = mutableListOf(Channel(id = 1, name = "A"))),
            ),
        )
    }

    @Test
    fun toggleCollapsed_addsAndRemovesId() {
        assertEquals(setOf(2), ChannelTree.toggleCollapsed(emptySet(), 2))
        assertEquals(emptySet<Int>(), ChannelTree.toggleCollapsed(setOf(2), 2))
        assertEquals(setOf(1, 3), ChannelTree.toggleCollapsed(setOf(1), 3))
    }
}
