package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.data.LastChannelStore
import dev.woms.mumdroid.data.ServerStore
import dev.woms.mumdroid.service.LastChannelSession
import dev.woms.mumdroid.service.SessionRoster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remembering / restoring the local user's channel across reconnects. */
class LastChannelSessionTest {

    private class FakeStore : LastChannelStore {
        var lastChannel: ServerStore.LastChannel? = null
        var resolvedId: Long = 0
        var resolveCalls = 0
        val saved = mutableListOf<Triple<Long, Int, String>>()

        override suspend fun resolveId(serverId: Long, host: String, port: Int): Long {
            resolveCalls++
            return resolvedId
        }

        override suspend fun getLastChannel(serverId: Long): ServerStore.LastChannel? = lastChannel

        override suspend fun setLastChannel(serverId: Long, channelId: Int, channelName: String) {
            saved += Triple(serverId, channelId, channelName)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val roster = SessionRoster(scope)

    private fun session(store: FakeStore): LastChannelSession =
        LastChannelSession(scope, roster).apply { attach(store) }

    private fun setUpRoster() {
        roster.localSession = 1
        roster.putChannel(Channel(id = 1, name = "Root"))
        roster.putChannel(Channel(id = 2, name = "Child"))
        roster.putChannel(Channel(id = 3, name = "New"))
        roster.userMap[1] = User(session = 1, name = "Me", channelId = 1, isLocalUser = true)
    }

    @Test
    fun prepareForConnect_marksARestoreWhenAChannelWasSavedForThisServer() {
        setUpRoster()
        val store = FakeStore().apply {
            resolvedId = 5
            lastChannel = ServerStore.LastChannel(id = 2, name = "Child")
        }

        val session = session(store)
        runBlocking { session.prepareForConnect("h", 64738, serverId = 5) }

        assertTrue(session.restorePending)
    }

    @Test
    fun restoreAfterSync_joinsTheSavedChannelAndAnnouncesOnce() {
        setUpRoster()
        val store = FakeStore().apply {
            resolvedId = 5
            lastChannel = ServerStore.LastChannel(id = 2, name = "Child")
        }
        val session = session(store)
        runBlocking { session.prepareForConnect("h", 64738, serverId = 5) }

        var joined = -1
        var announced = -1
        var stayed = false
        session.restoreAfterSync(
            "h",
            64738,
            joinWithoutAnnounce = { joined = it },
            onStay = { stayed = true },
            announceJoin = { announced = it },
        )

        assertEquals(2, joined)
        assertEquals(2, announced)
        assertFalse(stayed)
        assertFalse(session.restorePending)
    }

    @Test
    fun restoreAfterSync_withoutAPendingRestoreStaysPut() {
        setUpRoster()
        val session = session(FakeStore())

        var joined = false
        var stayed = false
        var announced = false
        session.restoreAfterSync(
            "h",
            64738,
            joinWithoutAnnounce = { joined = true },
            onStay = { stayed = true },
            announceJoin = { announced = true },
        )

        assertTrue(stayed)
        assertFalse(joined)
        assertFalse(announced)
    }

    @Test
    fun prepareForConnect_reusesTheRememberedChannelForTheSameServer() {
        setUpRoster()
        val store = FakeStore().apply {
            resolvedId = 5
            lastChannel = ServerStore.LastChannel(id = 2, name = "Child")
        }
        val session = session(store)

        runBlocking { session.prepareForConnect("h", 64738, serverId = 5) }
        runBlocking { session.prepareForConnect("h", 64738, serverId = 5) }

        assertEquals(1, store.resolveCalls)
    }

    @Test
    fun remember_persistsTheChannelNameFromTheRoster() {
        setUpRoster()
        val store = FakeStore().apply { resolvedId = 9 }
        val session = session(store)

        session.remember(channelId = 3, host = "h", port = 64738, serverIdHint = 9)

        assertEquals(listOf(Triple(9L, 3, "New")), store.saved)
    }

    @Test
    fun remember_ignoresAnEmptyHost() {
        setUpRoster()
        val store = FakeStore()
        val session = session(store)

        session.remember(channelId = 3, host = "", port = 64738, serverIdHint = 9)

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun persistFromLocal_remembersTheLocalUsersChannel() {
        setUpRoster()
        roster.userMap[1] = roster.userMap[1]!!.copy(channelId = 3)
        val store = FakeStore().apply { resolvedId = 9 }
        val session = session(store)

        session.persistFromLocal("h", 64738, serverIdHint = 9)

        assertEquals(listOf(Triple(9L, 3, "New")), store.saved)
    }

    @Test
    fun clearedPendingJoinIsNotAnnounced() {
        setUpRoster()
        val store = FakeStore().apply {
            resolvedId = 5
            lastChannel = ServerStore.LastChannel(id = 2, name = "Child")
        }
        val session = session(store)
        runBlocking { session.prepareForConnect("h", 64738, serverId = 5) }
        session.clearPending()

        var joined = -1
        var announced = false
        session.restoreAfterSync(
            "h",
            64738,
            joinWithoutAnnounce = { joined = it },
            onStay = {},
            announceJoin = { announced = true },
        )

        assertEquals(2, joined)
        assertFalse("a cleared restore must not announce the join", announced)
    }
}
