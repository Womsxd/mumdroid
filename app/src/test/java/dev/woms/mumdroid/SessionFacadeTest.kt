package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetStatus
import dev.woms.mumdroid.core.net.UdpVoiceManager
import dev.woms.mumdroid.service.CertificatePromptController
import dev.woms.mumdroid.service.ConnectParams
import dev.woms.mumdroid.service.ReconnectController
import dev.woms.mumdroid.service.ServerAdminSession
import dev.woms.mumdroid.service.SessionChat
import dev.woms.mumdroid.service.SessionFacade
import dev.woms.mumdroid.service.SessionRoster
import dev.woms.mumdroid.service.SessionState
import dev.woms.mumdroid.service.SessionVoiceStatus
import dev.woms.mumdroid.service.TcpPingStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facade is a pure delegation table: it must expose each collaborator's own
 * flow instance (so UI collectors cannot end up on a copy) and derive the
 * connection snapshot from [SessionState] only.
 */
class SessionFacadeTest {

    private val state = SessionState()

    /** A stand-in for the voice session: the facade only reads these. */
    private class FakeVoice : SessionVoiceStatus {
        override val loopbackMode = kotlinx.coroutines.flow.MutableStateFlow(LoopbackMode.OFF)
        override val voiceTarget = kotlinx.coroutines.flow.MutableStateFlow(VoiceTargetStatus())
        override val outputTarget = kotlinx.coroutines.flow.MutableStateFlow<VoiceOutputTarget?>(null)
        override val selfMuted = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val selfDeafened = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val serverMaxBandwidthBps = 0
        override val udp: UdpVoiceManager? = null
        override fun currentBandwidthBps() = 40_000
        override fun udpFallback(forceTcp: Boolean, live: Boolean) = forceTcp || !live
    }

    /** Builds the facade with fakes for every Android-dependent collaborator. */
    private fun facade(): SessionFacade {
        val scope = CoroutineScope(Dispatchers.Default)
        return SessionFacade(
            state = state,
            roster = SessionRoster(scope),
            admin = ServerAdminSession(scope),
            voice = FakeVoice(),
            chat = SessionChat(scope),
            reconnect = ReconnectController(scope),
            cert = CertificatePromptController(),
            tcpPing = TcpPingStats(),
        )
    }

    @Test
    fun connectionFlows_areTheSameInstancesAsSessionState() {
        val facade = facade()
        assertSame(state.connected, facade.connected)
        assertSame(state.connecting, facade.connecting)
        assertSame(state.status, facade.status)
        assertSame(state.serverName, facade.serverName)
        assertSame(state.serverRemoval, facade.serverRemoval)
    }

    @Test
    fun favoriteId_prefersLastConnectParamsOverConnectedServerId() {
        val facade = facade()
        state.connectedServerId = 7L
        assertEquals(7L, facade.favoriteId())

        state.lastConnectParams = ConnectParams(
            host = "h",
            port = 1,
            username = "u",
            password = "p",
            displayName = "d",
            serverId = 42L,
        )
        assertEquals(42L, facade.favoriteId())
    }

    @Test
    fun connectionInfo_isEmptyWhileDisconnected() {
        val facade = facade()
        val info = facade.connectionInfo()
        assertEquals("", info.host)
        assertEquals(0, info.userCount)
    }

    @Test
    fun connectionInfo_reflectsLiveStateAndClearsWhenNotLive() {
        val facade = facade()
        state.host = "example.org"
        state.port = 64738
        state.connected.value = true
        val live = facade.connectionInfo()
        assertEquals("example.org", live.host)
        assertEquals(64738, live.port)

        // A dropped session must not leak the stale endpoint into the dialog.
        state.connected.value = false
        val dropped = facade.connectionInfo()
        assertEquals("example.org", dropped.host)
        assertEquals(0, dropped.userCount)
    }

    @Test
    fun voiceFlows_areExposedThroughTheVoiceSession() {
        val facade = facade()
        assertTrue(facade.selfMuted.value.not())
        assertFalse(facade.selfDeafened.value)
        assertEquals(VoiceTargetStatus(), facade.voiceTarget.value)
        assertEquals(LoopbackMode.OFF, facade.loopbackMode.value)
        assertNull(facade.certificatePrompt.value)
    }
}
