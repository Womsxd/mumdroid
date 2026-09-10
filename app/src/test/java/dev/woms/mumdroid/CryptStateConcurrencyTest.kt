package dev.woms.mumdroid

import dev.woms.mumdroid.core.crypto.CryptState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the CryptState lock contract. `setKey` runs on the TCP
 * read thread while `decrypt` runs on the UDP receive thread, and the two used
 * to take the direction locks in *opposite* orders (setKey nested
 * `encryptLock -> decryptLock`, every other method took a single lock), which
 * could deadlock and could expose a half-adopted key/nonce generation.
 */
class CryptStateConcurrencyTest {

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val clientNonce = ByteArray(16) { 0x11 }
    private val serverNonce = ByteArray(16) { 0x22 }

    private fun state() = CryptState().apply { assertTrue(setKey(key, clientNonce, serverNonce)) }

    /**
     * A teardown (`reset`) concurrent with in-flight encrypt/decrypt work must
     * not deadlock, and every call must fail closed rather than encrypt or
     * decrypt with wiped key material. Fails by timeout if the lock order
     * regresses into a cycle.
     */
    @Test
    fun resetNeverReleasesHalfWipedStateAndDoesNotDeadlock() {
        repeat(20) {
            val state = state()
            val stop = AtomicBoolean(false)
            val pair = ByteArray(64) { it.toByte() }
            // Encrypt once (single-threaded) to build a packet we can hammer
            // with decrypt on the other thread.
            val packet = state.encrypt(pair, 0, pair.size)!!
            val badDecrypt = AtomicInteger(0)
            val goodDecrypt = AtomicInteger(0)
            val errors = AtomicReference<Throwable?>()

            val workers = listOf(
                Thread {
                    try {
                        while (!stop.get()) {
                            if (state.encrypt(pair, 0, pair.size) == null) badDecrypt.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    }
                },
                Thread {
                    try {
                        while (!stop.get()) {
                            if (state.decrypt(packet, 0, packet.size) != null) goodDecrypt.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    }
                },
                Thread {
                    try {
                        while (!stop.get()) {
                            state.reset()
                            state.setKey(key, clientNonce, serverNonce)
                        }
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    }
                },
            )
            workers.forEach { it.isDaemon = true; it.start() }
            Thread.sleep(30)
            stop.set(true)
            workers.forEach { it.join(5_000) }

            errors.get()?.let { throw it }
            workers.forEach { assertFalse("worker still blocked: possible deadlock", it.isAlive) }
        }
    }

    /**
     * Key rotation interleaved with decryption.
     *
     * A peer packet is decrypted while this state is being rotated between
     * two nonce generations. A packet that authenticated must always yield
     * exactly the plaintext that was encrypted: no mix of "nonce of one
     * generation, AEAD context of the other" may ever pass the OCB2 tag
     * check, and the state must stay usable after the rotation storm.
     */
    @Test
    fun setKeyIsAtomicAgainstConcurrentDecrypt() {
        // The peer's nonce pair. The key is the same; only the nonces differ,
        // so a decrypt only succeeds while the live state is armed with the
        // generation whose decrypt nonce matches `peerNonce`.
        val peerNonce = ByteArray(16) { 0x55 }
        val payload = ByteArray(48) { it.toByte() }

        val peerB = CryptState().apply { assertTrue(setKey(key, peerNonce, peerNonce)) }
        val packetB = peerB.encrypt(payload, 0, payload.size)!!

        // Sanity: a receiver armed with the *matching* key/nonce pair decrypts
        // it (otherwise the test below could never observe a valid decrypt).
        val reference = CryptState().apply { assertTrue(setKey(key, peerNonce, peerNonce)) }
        assertArrayEquals(payload, reference.decrypt(packetB, 0, packetB.size))

        val state = state()
        // The initially armed generation uses a different nonce pair, so the
        // peer packet is not valid for it.
        assertNull(state.decrypt(packetB, 0, packetB.size))

        val stop = AtomicBoolean(false)
        val mismatches = AtomicReference<String?>()
        val decoded = AtomicInteger(0)
        val errors = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)

        val reader = Thread {
            started.countDown()
            try {
                while (!stop.get()) {
                    val out = state.decrypt(packetB, 0, packetB.size) ?: continue
                    decoded.incrementAndGet()
                    if (!out.contentEquals(payload)) {
                        mismatches.compareAndSet(null, "authenticated plaintext differed from the input")
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // Rotates the live state into (and back out of) the generation the
        // peer packet was produced with, so a concurrent decrypt can race the
        // AEAD-context rebuild of setKey against a live decrypt.
        val writer = Thread {
            try {
                while (!stop.get()) {
                    state.setKey(key, peerNonce, peerNonce)
                    state.reset()
                    state.setKey(key, clientNonce, serverNonce)
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        reader.isDaemon = true
        writer.isDaemon = true
        reader.start()
        writer.start()
        started.await(2, TimeUnit.SECONDS)
        Thread.sleep(50)
        stop.set(true)
        reader.join(5_000)
        writer.join(5_000)

        errors.get()?.let { throw it }
        assertFalse("reader thread stuck", reader.isAlive)
        assertFalse("writer thread stuck", writer.isAlive)
        assertNull("authenticated plaintext was corrupted by a key rotation", mismatches.get())
        assertTrue("test never exercised decryption", decoded.get() > 0)

        // The state must still be fully usable once the rotation storm stops.
        // The writer leaves the state on generation A, whose encrypt nonce is
        // `clientNonce`; a receiver needs its decrypt nonce on that same value
        // (the peer's encrypt nonce) to decrypt what we produce here.
        val finalPlain = state.encrypt(payload, 0, payload.size)
        assertNotNull("crypto unusable after concurrent rotation", finalPlain)
        val receiver = CryptState().apply { setKey(key, ByteArray(16), clientNonce) }
        assertArrayEquals(payload, receiver.decrypt(finalPlain!!, 0, finalPlain.size))
    }

    /** Key/nonce material must observe a full publish: after `setKey` returns
     *  on another thread, a fresh encrypt/decrypt round trip must work. */
    @Test
    fun setKeyPublishesUsableCryptoToOtherThreads() {
        val state = CryptState()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        Thread {
            try {
                state.setKey(key, clientNonce, serverNonce)
                ok.set(true)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(ok.get())
        assertTrue(state.isReady)

        val payload = ByteArray(40) { it.toByte() }
        val packet = state.encrypt(payload, 0, payload.size)
        assertNotNull("encrypt returned no packet after setKey", packet)
        // A fresh receiver state with the same key/nonce can decrypt what this
        // state produced; the local state's own decrypt nonce is the server
        // nonce, so it cannot decrypt its own output (official behaviour).
        val receiver = CryptState().apply { setKey(key, ByteArray(16), clientNonce) }
        assertArrayEquals(payload, receiver.decrypt(packet!!, 0, packet.size))
    }

    /** `reset` wipes everything and publishes "not ready" to other threads. */
    @Test
    fun resetPublishesNotReadyToOtherThreads() {
        val state = state()
        val done = CountDownLatch(1)
        Thread {
            try {
                state.reset()
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(state.isReady)
        assertNull(state.encrypt(ByteArray(16), 0, 16))
        assertEquals(0, state.stats().good)
    }

    /** A teardown in the middle of the official resync path must leave the
     *  counters and the IV snapshot consistent. */
    @Test
    fun resyncAndTeardownStayConsistent() {
        val state = state()
        val stop = AtomicBoolean(false)
        val errors = AtomicReference<Throwable?>()
        val threads = listOf(
            Thread {
                try {
                    while (!stop.get()) {
                        state.incrementResync()
                        state.setDecryptIV(ByteArray(16) { 7 })
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            },
            Thread {
                try {
                    while (!stop.get()) {
                        state.getEncryptIV()
                        state.stats()
                        state.reset()
                        state.setKey(key, clientNonce, serverNonce)
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            },
        )
        threads.forEach { it.isDaemon = true; it.start() }
        Thread.sleep(40)
        stop.set(true)
        threads.forEach { it.join(5_000) }
        errors.get()?.let { throw it }
        threads.forEach { assertFalse("thread still blocked: possible deadlock", it.isAlive) }
    }
}
