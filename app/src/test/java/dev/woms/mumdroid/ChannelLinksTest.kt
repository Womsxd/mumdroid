package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLinksTest {

    @Test
    fun nextDirectLinks_replaceWinsOverAddRemove() {
        val next = ChannelLinks.nextDirectLinks(
            existing = setOf(1, 2),
            replace = listOf(8, 9),
            add = listOf(3),
            remove = listOf(1),
        )
        assertEquals(setOf(8, 9), next)
    }

    @Test
    fun nextDirectLinks_emptyReplaceAppliesAddRemove() {
        val next = ChannelLinks.nextDirectLinks(
            existing = setOf(1, 2),
            replace = emptyList(),
            add = listOf(3),
            remove = listOf(1),
        )
        assertEquals(setOf(2, 3), next)
    }

    @Test
    fun syncPartners_isSymmetric() {
        val map = mutableMapOf(
            1 to Channel(id = 1, name = "Home", linkedIds = setOf(2)),
            2 to Channel(id = 2, name = "Lobby", linkedIds = setOf(1)),
            3 to Channel(id = 3, name = "Games"),
        )
        ChannelLinks.syncPartners(map, 1, previous = setOf(2), next = setOf(3))
        assertEquals(setOf(3), map[1]!!.linkedIds)
        assertEquals(emptySet<Int>(), map[2]!!.linkedIds)
        assertEquals(setOf(1), map[3]!!.linkedIds)
    }

    @Test
    fun allLinkedIds_walksTransitiveComponent() {
        val links = mapOf(
            1 to setOf(2),
            2 to setOf(1, 3),
            3 to setOf(2),
            4 to emptySet(),
        )
        assertEquals(setOf(1, 2, 3), ChannelLinks.allLinkedIds(links, 1))
        assertEquals(setOf(4), ChannelLinks.allLinkedIds(links, 4))
    }

    @Test
    fun menu_showsLinkOrUnlinkNotBoth() {
        val can = true
        val link = ChannelLinks.menu(
            homeId = 1,
            targetId = 2,
            homeDirectLinks = emptySet(),
            targetInHomeComponent = false,
            homeCanLink = can,
            targetCanLink = can,
        )
        assertTrue(link.showLink)
        assertFalse(link.showUnlink)
        assertFalse(link.showUnlinkAll)

        val unlink = ChannelLinks.menu(
            homeId = 1,
            targetId = 2,
            homeDirectLinks = setOf(2),
            targetInHomeComponent = true,
            homeCanLink = can,
            targetCanLink = can,
        )
        assertFalse(unlink.showLink)
        assertTrue(unlink.showUnlink)
        assertTrue(unlink.showUnlinkAll)
    }

    @Test
    fun menu_hidesLinkOnCurrentChannel() {
        val menu = ChannelLinks.menu(
            homeId = 1,
            targetId = 1,
            homeDirectLinks = setOf(2),
            targetInHomeComponent = true,
            homeCanLink = true,
            targetCanLink = true,
        )
        assertFalse(menu.showLink)
        assertFalse(menu.showUnlink)
        assertTrue(menu.showUnlinkAll)
    }
}
