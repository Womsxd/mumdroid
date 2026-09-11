package dev.woms.mumdroid

import dev.woms.mumdroid.service.AccessTokenPersistence
import dev.woms.mumdroid.service.AccessTokenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI list and the list sent to the server must never disagree: whatever the
 * store keeps after a write is what the client is told to authenticate with.
 */
class AccessTokenStateTest {

    /** Records the writes and emulates the store's own sanitizing. */
    private class FakeStore : AccessTokenPersistence {
        val serverWrites = mutableListOf<List<String>>()
        val channelWrites = mutableListOf<Pair<Int, String>>()
        var keep: (List<String>) -> List<String> = { it }

        override suspend fun replaceServerTokens(tokens: List<String>): List<String> {
            serverWrites.add(tokens)
            return keep(tokens)
        }

        override suspend fun upsertChannelToken(channelId: Int, token: String) {
            channelWrites.add(channelId to token)
        }
    }

    // Unconfined keeps the (already non-suspending) fake store synchronous, so
    // the assertions do not need to race the launched write.
    private fun state() = AccessTokenState(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun replace_persistsTheSanitizedListAndPushesWhatTheStoreKept() {
        val store = FakeStore()
        // Say "none" when asked to store, to prove the store's answer wins.
        store.keep = { emptyList() }
        val state = state()
        var pushed: List<String>? = null

        state.replace(listOf(" b ", "", "a", "B"), store) { pushed = it }

        assertEquals(listOf(listOf("a", "b")), store.serverWrites)
        assertEquals(listOf<String>(), pushed)
        assertEquals(emptyList<String>(), state.tokens())
    }

    @Test
    fun replace_withoutAStoreEchoStillShowsTheSanitizedTokens() {
        val store = FakeStore()
        val state = state()
        var pushed: List<String>? = null

        state.replace(listOf("B", " a ", "b"), store) { pushed = it }

        assertEquals(listOf("a", "B"), store.serverWrites.single())
        assertEquals(listOf("a", "B"), pushed)
        assertEquals(listOf("a", "B"), state.tokens())
    }

    @Test
    fun persistChannelToken_recordsBothShapes() = runBlocking {
        val store = FakeStore()
        val state = state()
        state.replace(listOf("existing"), store) {}

        state.persistChannelToken(channelId = 4, token = " gate ", persistence = store)

        assertEquals(listOf(4 to "gate"), store.channelWrites)
        assertEquals(listOf(listOf("existing"), listOf("existing", "gate")), store.serverWrites)
        assertEquals(listOf("existing", "gate"), state.tokens())
    }

    @Test
    fun persistChannelToken_doesNotDuplicateCaseInsensitiveTokens() = runBlocking {
        val store = FakeStore()
        val state = state()
        state.setTokens(listOf("gate"))

        state.persistChannelToken(channelId = 4, token = "GATE", persistence = store)

        assertEquals(listOf("gate"), state.tokens())
    }

    @Test
    fun clearTokens_emptiesTheBag() {
        val state = state()
        state.setTokens(listOf("a"))
        state.clearTokens()
        assertTrue(state.tokens().isEmpty())
        assertEquals(emptyList<String>(), state.accessTokens.value)
    }
}
