package dev.woms.mumdroid

import dev.woms.mumdroid.core.crypto.CryptOCB2
import dev.woms.mumdroid.core.crypto.CryptState
import dev.woms.mumdroid.core.net.UdpVoiceManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptStateTest {

    @Test
    fun ocb2RoundTrip() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())

        val payload = ByteArray(100) { it.toByte() }
        val encrypted = ByteArray(payload.size)
        val tag = ByteArray(16)
        val written = crypt.encrypt(encrypted, payload, tag)
        assertTrue(written > 0)

        val decrypted = ByteArray(encrypted.size)
        val decWritten = crypt.decrypt(decrypted, encrypted, tag)
        assertTrue(decWritten > 0)
        assertArrayEquals(payload, decrypted)

        // Tampering with the tag must fail authentication.
        tag[0] = (tag[0].toInt() xor 1).toByte()
        val bad = crypt.decrypt(ByteArray(encrypted.size), encrypted, tag)
        assertEquals(-1, bad)
    }

    @Test
    fun ocb2_officialEmptyAndLongTestVectors() {
        // draft-krovetz-ocb-00 / official `TestCrypt::testvectors`.
        val rawkey = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        )
        val crypt = CryptOCB2()
        assertTrue(crypt.setKey(rawkey))
        assertTrue(crypt.setNonce(rawkey))

        val blankTag = ByteArray(16)
        assertEquals(0, crypt.encrypt(ByteArray(0), ByteArray(0), blankTag))
        assertArrayEquals(
            byteArrayOf(
                0xBF.toByte(), 0x31, 0x08, 0x13, 0x07, 0x73, 0xAD.toByte(), 0x5E,
                0xC7.toByte(), 0x0E, 0xC6.toByte(), 0x9E.toByte(), 0x78, 0x75, 0xA7.toByte(), 0xB0.toByte(),
            ),
            blankTag,
        )
        assertEquals(0, crypt.decrypt(ByteArray(0), ByteArray(0), blankTag))

        val source = ByteArray(40) { it.toByte() }
        val encrypted = ByteArray(40)
        val longTag = ByteArray(16)
        assertEquals(40, crypt.encrypt(encrypted, source, longTag))
        assertArrayEquals(
            byteArrayOf(
                0x9D.toByte(), 0xB0.toByte(), 0xCD.toByte(), 0xF8.toByte(),
                0x80.toByte(), 0xF7.toByte(), 0x3E, 0x3E,
                0x10, 0xD4.toByte(), 0xEB.toByte(), 0x32, 0x17, 0x76, 0x66, 0x88.toByte(),
            ),
            longTag,
        )
        assertArrayEquals(
            byteArrayOf(
                0xF7.toByte(), 0x5D, 0x6B, 0xC8.toByte(), 0xB4.toByte(), 0xDC.toByte(), 0x8D.toByte(), 0x66,
                0xB8.toByte(), 0x36, 0xA2.toByte(), 0xB0.toByte(), 0x8B.toByte(), 0x32, 0xA6.toByte(), 0x36,
                0x9F.toByte(), 0x1C, 0xD3.toByte(), 0xC5.toByte(), 0x22, 0x8D.toByte(), 0x79, 0xFD.toByte(),
                0x6C, 0x26, 0x7F, 0x5F, 0x6A, 0xA7.toByte(), 0xB2.toByte(), 0x31,
                0xC7.toByte(), 0xDF.toByte(), 0xB9.toByte(), 0xD5.toByte(), 0x99.toByte(), 0x51, 0xAE.toByte(), 0x9C.toByte(),
            ),
            encrypted,
        )
    }

    @Test
    fun ocb2AcceptsThreeByteWireTag() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())
        val payload = ByteArray(40) { it.toByte() }
        val encrypted = ByteArray(payload.size)
        val fullTag = ByteArray(16)
        assertTrue(crypt.encrypt(encrypted, payload, fullTag) > 0)
        val wireTag = byteArrayOf(fullTag[0], fullTag[1], fullTag[2])
        val decrypted = ByteArray(payload.size)
        assertTrue(crypt.decrypt(decrypted, encrypted, wireTag) > 0)
        assertArrayEquals(payload, decrypted)
        wireTag[0] = (wireTag[0].toInt() xor 1).toByte()
        assertEquals(-1, crypt.decrypt(ByteArray(payload.size), encrypted, wireTag))
    }

    @Test
    fun cryptStateLegacyRoundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16)
        val serverNonce = ByteArray(16)
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))

        // Encrypt a payload, then decrypt it with a mirroring state.
        val payload = ByteArray(50) { it.toByte() }
        val packet = crypt.encrypt(payload)
        assertNotNull(packet)
        assertEquals(4 + payload.size, packet!!.size)

        val mirror = CryptState()
        mirror.setKey(key, clientNonce, serverNonce)
        val decrypted = mirror.decrypt(packet)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)

        // Corrupting the tag must cause authentication failure.
        val badPacket = packet.copyOf()
        badPacket[1] = (badPacket[1].toInt() xor 1).toByte()
        assertNull(mirror.decrypt(badPacket))
    }

    @Test
    fun cryptStateEncrypt_writesIntoCallerBuffer() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16)
        val serverNonce = ByteArray(16)
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))

        val payload = ByteArray(50) { it.toByte() }
        val dest = ByteArray(8 + 4 + payload.size + 3)
        dest.fill(0x7f)
        val n = crypt.encrypt(payload, dest, destOffset = 8)
        assertEquals(4 + payload.size, n)
        assertEquals(0x7f.toByte(), dest[7])
        assertEquals(0x7f.toByte(), dest[8 + n])

        val mirror = CryptState()
        mirror.setKey(key, clientNonce, serverNonce)
        val decrypted = mirror.decrypt(dest, 8, n)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)

        assertEquals(-1, crypt.encrypt(payload, ByteArray(3)))
        val emptyDest = ByteArray(4)
        assertEquals(4, crypt.encrypt(ByteArray(0), emptyDest))
        assertNotNull(mirror.decrypt(emptyDest))
    }

    @Test
    fun cryptStateDecrypt_readsFromOffsetInLargerBuffer() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16)
        val serverNonce = ByteArray(16)
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))

        val payload = ByteArray(50) { it.toByte() }
        val inner = ByteArray(7 + payload.size + 3)
        System.arraycopy(payload, 0, inner, 7, payload.size)
        val packet = crypt.encrypt(inner, 7, payload.size)
        assertNotNull(packet)
        assertEquals(4 + payload.size, packet!!.size)

        val padded = ByteArray(12 + packet.size + 9)
        System.arraycopy(packet, 0, padded, 12, packet.size)
        val mirror = CryptState()
        mirror.setKey(key, clientNonce, serverNonce)
        val decrypted = mirror.decrypt(padded, 12, packet.size)
        assertNotNull(decrypted)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun ocb2RoundTrip_usesBufferOffsets() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())

        val payload = ByteArray(100) { it.toByte() }
        val inputPadded = ByteArray(8 + payload.size + 3)
        System.arraycopy(payload, 0, inputPadded, 8, payload.size)
        val outputPadded = ByteArray(4 + payload.size + 5)
        val tag = ByteArray(16)
        val written = crypt.encrypt(
            outputPadded, inputPadded, tag,
            inputOffset = 8,
            inputLength = payload.size,
            outputOffset = 4,
        )
        assertEquals(payload.size, written)

        val decrypted = ByteArray(payload.size)
        val decWritten = crypt.decrypt(
            decrypted, outputPadded, tag,
            inputOffset = 4,
            inputLength = payload.size,
        )
        assertEquals(payload.size, decWritten)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun ocb2_clearKeysReturnsFailureInsteadOfThrowing() {
        val crypt = CryptOCB2()
        crypt.setKey(crypt.generateKey())
        crypt.setNonce(crypt.generateNonce())
        crypt.clearKeys()
        val payload = ByteArray(16) { it.toByte() }
        assertEquals(0, crypt.encrypt(ByteArray(16), payload, ByteArray(16)))
        assertEquals(-1, crypt.decrypt(ByteArray(16), payload, ByteArray(3)))
        val state = CryptState()
        state.setKey(ByteArray(16) { it.toByte() }, ByteArray(16), ByteArray(16))
        state.reset()
        assertNull(state.encrypt(payload))
        assertNull(state.decrypt(ByteArray(20)))
    }

    @Test
    fun cryptState_resyncCountsNonceNotFullKey() {
        val key = ByteArray(16) { it.toByte() }
        val clientNonce = ByteArray(16) { 1 }
        val serverNonce = ByteArray(16) { 2 }
        val crypt = CryptState()
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))
        assertEquals(0, crypt.resyncPackets)
        // Full key re-delivery is not a nonce resync (official setKey).
        assertTrue(crypt.setKey(key, clientNonce, serverNonce))
        assertEquals(0, crypt.resyncPackets)
        // Official CryptStateOCB2::setDecryptIV only replaces the IV.
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 3 }))
        assertEquals(0, crypt.resyncPackets)
        // Official msgCryptSetup: m_statsLocal.resync++ then setDecryptIV.
        crypt.incrementResync()
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 4 }))
        assertEquals(1, crypt.resyncPackets)
        crypt.incrementResync()
        assertTrue(crypt.setDecryptIV(ByteArray(16) { 5 }))
        assertEquals(2, crypt.resyncPackets)
        assertFalse(crypt.setDecryptIV(ByteArray(8)))
        assertEquals(2, crypt.resyncPackets)
        val udp = UdpVoiceManager("127.0.0.1", 64738)
        udp.setupCryptography(key, clientNonce, serverNonce)
        assertEquals(0, udp.packetStats().resync)
        assertTrue(udp.resyncDecryptIV(ByteArray(16) { 6 }))
        assertEquals(1, udp.packetStats().resync)
        assertFalse(udp.resyncDecryptIV(ByteArray(8)))
        assertEquals(1, udp.packetStats().resync)
        udp.close()
    }

    @Test
    fun cryptState_concurrentEncryptDecrypt() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val clientIv = ByteArray(16) { 1 }
        val serverIv = ByteArray(16) { 2 }
        val local = CryptState()
        val remote = CryptState()
        assertTrue(local.setKey(key, clientIv, serverIv))
        assertTrue(remote.setKey(key, serverIv, clientIv))

        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val rounds = 200
        val send = Thread {
            repeat(rounds) { i ->
                val payload = ByteArray(40) { (i + it).toByte() }
                val packet = local.encrypt(payload)
                if (packet == null) {
                    errors.add("local encrypt failed at $i")
                    return@repeat
                }
                val plain = remote.decrypt(packet)
                if (plain == null || !plain.contentEquals(payload)) {
                    errors.add("remote decrypt failed at $i")
                }
            }
        }
        val recv = Thread {
            repeat(rounds) { i ->
                val payload = ByteArray(36) { (i * 2 + it).toByte() }
                val packet = remote.encrypt(payload)
                if (packet == null) {
                    errors.add("remote encrypt failed at $i")
                    return@repeat
                }
                val plain = local.decrypt(packet)
                if (plain == null || !plain.contentEquals(payload)) {
                    errors.add("local decrypt failed at $i")
                }
            }
        }
        send.start()
        recv.start()
        send.join()
        recv.join()
        assertTrue(errors.joinToString(), errors.isEmpty())
    }
}
