package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.UdpVoiceCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVoiceCryptoTest {

    // Monotonic fake clock; starts away from 0 so the armed-time sentinel
    // (cryptoReadyMs == 0 means "not armed") does not swallow the baseline.
    private var nowMs = 100_000L

    private fun armedCrypto(): UdpVoiceCrypto {
        val crypto = UdpVoiceCrypto(clock = { nowMs })
        crypto.setup(
            key = ByteArray(16) { it.toByte() },
            clientNonce = ByteArray(16),
            serverNonce = ByteArray(16),
        )
        return crypto
    }

    private fun encryptedPacket(crypto: UdpVoiceCrypto, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        assertEquals(4 + payload.size, crypto.encrypt(payload, out))
        return out
    }

    @Test
    fun setup_armsCryptoAndSnapshotsEncryptIv() {
        val crypto = armedCrypto()
        assertTrue(crypto.isReady)
        val iv = crypto.encryptIV()
        assertNotNull(iv)
        assertEquals(16, iv!!.size)
    }

    @Test
    fun setup_rejectsInvalidKeyWithoutSideEffects() {
        val crypto = UdpVoiceCrypto(clock = { nowMs })
        crypto.setup(ByteArray(15), ByteArray(16), ByteArray(16))
        assertFalse(crypto.isReady)
        assertNull(crypto.encryptIV())
    }

    @Test
    fun decrypt_roundTripsAndRejectsTampering() {
        val crypto = armedCrypto()
        val payload = ByteArray(40) { it.toByte() }
        val packet = encryptedPacket(crypto, payload)
        val plain = crypto.decrypt(packet, 0, packet.size)
        assertNotNull(plain)
        assertArrayEquals(payload, plain)

        val bad = packet.copyOf()
        bad[1] = (bad[1].toInt() xor 1).toByte()
        assertNull(crypto.decrypt(bad, 0, bad.size))
    }

    @Test
    fun resyncDecryptIV_countsNonceResyncsOnly() {
        val crypto = armedCrypto()
        assertEquals(0, crypto.packetStats().resync)
        assertTrue(crypto.resyncDecryptIV(ByteArray(16) { 6 }))
        assertEquals(1, crypto.packetStats().resync)
        assertFalse(crypto.resyncDecryptIV(ByteArray(8)))
        assertEquals(1, crypto.packetStats().resync)
    }

    @Test
    fun decrypt_failuresForFiveSecondsRequestResyncOnce() {
        val crypto = armedCrypto()
        var resyncRequests = 0
        crypto.onRequestCryptResync = { resyncRequests++ }

        val packet = encryptedPacket(crypto, ByteArray(40))
        packet[1] = (packet[1].toInt() xor 1).toByte() // break the OCB2 tag

        // Within the official 5-second window: failures stay silent.
        nowMs += 4_999
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(0, resyncRequests)

        // Past the window (baseline = armed time, official tLastGood): one request.
        nowMs += 2
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(1, resyncRequests)

        // Repeat failures inside the suppression window: no further requests.
        nowMs += 1_000
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(1, resyncRequests)

        // A successful decrypt re-arms the rule.
        val goodPacket = encryptedPacket(crypto, ByteArray(40))
        nowMs += 1_000
        assertNotNull(crypto.decrypt(goodPacket, 0, goodPacket.size))
        nowMs += 5_001
        packet[2] = (packet[2].toInt() xor 1).toByte()
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(2, resyncRequests)
    }

    @Test
    fun reset_wipesCryptoState() {
        val crypto = armedCrypto()
        assertTrue(crypto.isReady)
        crypto.reset()
        assertFalse(crypto.isReady)
        assertEquals(0, crypto.packetStats().good)
    }
}
