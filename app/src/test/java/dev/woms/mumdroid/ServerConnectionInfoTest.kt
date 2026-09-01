package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ServerConnectionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionInfoTest {

    @Test
    fun formatProtocol_prefersV2ThenLegacy() {
        val v2 = (1L shl 48) or (5L shl 32)
        assertEquals("1.5.0", ServerConnectionInfo.formatProtocol(v2, 0x010203))
        assertEquals("1.2.3", ServerConnectionInfo.formatProtocol(0L, 0x010203))
        assertEquals("", ServerConnectionInfo.formatProtocol(0L, 0))
    }

    @Test
    fun formatTlsProtocol_matchesDesktopLabels() {
        assertEquals("TLS 1.3", ServerConnectionInfo.formatTlsProtocol("TLSv1.3"))
        assertEquals("TLS 1.2", ServerConnectionInfo.formatTlsProtocol("TLSv1.2"))
        assertEquals("TLS 1.0", ServerConnectionInfo.formatTlsProtocol("TLSv1"))
        assertEquals("other", ServerConnectionInfo.formatTlsProtocol("other"))
    }

    @Test
    fun usesPerfectForwardSecrecy_tls13OrDhe() {
        assertTrue(ServerConnectionInfo.usesPerfectForwardSecrecy("TLSv1.3", "TLS_AES_128_GCM_SHA256"))
        assertTrue(ServerConnectionInfo.usesPerfectForwardSecrecy("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
        assertFalse(ServerConnectionInfo.usesPerfectForwardSecrecy("TLSv1.2", "TLS_RSA_WITH_AES_128_CBC_SHA"))
    }

    @Test
    fun formatLatency_includesStdDev() {
        assertEquals("12.0 ms (σ = 2.0 ms)", ServerConnectionInfo.formatLatency(12f, 4f))
    }

    @Test
    fun formatBandwidth_oneDecimalKbit() {
        assertEquals("54.8 kBit/s", ServerConnectionInfo.formatBandwidth(54_800))
    }

    @Test
    fun osDisplay_appendsVersion() {
        assertEquals(
            "Linux (6.1)",
            ServerConnectionInfo(os = "Linux", osVersion = "6.1").osDisplay,
        )
        assertEquals("Linux", ServerConnectionInfo(os = "Linux").osDisplay)
        assertEquals("", ServerConnectionInfo().osDisplay)
    }
}
