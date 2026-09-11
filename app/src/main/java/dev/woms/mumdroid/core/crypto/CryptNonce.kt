package dev.woms.mumdroid.core.crypto

/**
 * The OCB2 nonce and replay arithmetic of the legacy Mumble UDP crypto layer,
 * extracted from [CryptState] so the wire rules can be read and tested on
 * their own.
 *
 * The nonce is a 16-byte little-endian counter; the first byte travels in the
 * packet header (`iv[0]`) so the receiver can tell in-order, late and lost
 * datagrams apart and re-derive the sender's nonce without a handshake per
 * packet.
 */
internal object CryptNonce {

    /** Increments a little-endian nonce in place; stops at the first byte that does not carry. */
    fun increment(nonce: ByteArray) {
        for (i in nonce.indices) {
            val v = (nonce[i].toInt() and 0xff) + 1
            nonce[i] = (v and 0xff).toByte()
            if (v != 0x100) break
        }
    }

    /** Decrements a little-endian nonce in place; stops at the first byte that does not borrow. */
    fun decrement(nonce: ByteArray) {
        for (i in nonce.indices) {
            val v = (nonce[i].toInt() and 0xff) - 1
            nonce[i] = (v and 0xff).toByte()
            if (v != -1) break
        }
    }

    /** How the receiver must treat the incoming `iv[0]` byte. */
    enum class Advance {
        /** Adopt the header as the new nonce; nothing was lost. */
        IN_ORDER,

        /**
         * A datagram from the recent past (or the previous round): decrypt it
         * but restore the nonce afterwards, and count it as late.
         */
        LATE_RESTORE,

        /** The header is a duplicate of the current nonce: reject without decrypting. */
        DUPLICATE,

        /** Packets were skipped in the current round. */
        GAP_FORWARD,

        /** Packets were skipped across a round wraparound of `iv[0]`. */
        GAP_WRAP,

        /** More than half a round away: indistinguishable from a replay. */
        UNKNOWN,
    }

    /** The outcome of applying an `iv[0]` byte to the current decryption nonce. */
    data class Plan(
        val advance: Advance,
        /** Nonce bytes differing from the current one, applied in place. */
        val apply: (ByteArray) -> Unit,
        /** Packets skipped (0 when nothing was lost). */
        val lost: Int = 0,
        /** 1 for a late packet, 0 otherwise. */
        val late: Int = 0,
        /** Whether the previous nonce must be restored after decryption. */
        val restore: Boolean = false,
    )

    private const val HALF_TURN = 128

    /**
     * Decides what the header byte [ivByte] means for [current].
     *
     * Mirrors the official `CryptStateOCB2::decrypt`: the "next" byte and the
     * current byte are the only in-order cases; a small negative distance is a
     * late packet; anything else is a gap (possibly with a round carry).
     */
    fun plan(current: ByteArray, ivByte: Int): Plan {
        val cur = current[0].toInt() and 0xff
        // Same header again: a replay. (Official code reaches the same verdict
        // through its final `else`, so the reject behaviour is unchanged.)
        if (ivByte == cur) return Plan(Advance.DUPLICATE, {})
        val next = (cur + 1) and 0xff
        if (next == ivByte) {
            return if (ivByte > cur) {
                Plan(Advance.IN_ORDER, { n -> n[0] = ivByte.toByte() })
            } else {
                Plan(
                    Advance.IN_ORDER,
                    { n ->
                        n[0] = ivByte.toByte()
                        incrementHighBytes(n)
                    },
                )
            }
        }

        var diff = ivByte - cur
        if (diff > HALF_TURN) diff -= 256
        else if (diff < -HALF_TURN) diff += 256

        return when {
            ivByte < cur && diff > LATE_WINDOW && diff < 0 -> Plan(
                Advance.LATE_RESTORE,
                { n -> n[0] = ivByte.toByte() },
                lost = -1,
                late = 1,
                restore = true,
            )
            ivByte > cur && diff > LATE_WINDOW && diff < 0 -> Plan(
                Advance.LATE_RESTORE,
                { n ->
                    n[0] = ivByte.toByte()
                    decrementHighBytes(n)
                },
                lost = -1,
                late = 1,
                restore = true,
            )
            ivByte > cur && diff > 0 -> Plan(
                Advance.GAP_FORWARD,
                { n -> n[0] = ivByte.toByte() },
                lost = ivByte - cur - 1,
            )
            ivByte < cur && diff > 0 -> Plan(
                Advance.GAP_WRAP,
                { n ->
                    n[0] = ivByte.toByte()
                    incrementHighBytes(n)
                },
                lost = 256 - cur + ivByte - 1,
            )
            else -> Plan(Advance.UNKNOWN, {})
        }
    }

    /** Distance above which a negative diff is a gap rather than a late packet. */
    private const val LATE_WINDOW = -30

    private fun incrementHighBytes(nonce: ByteArray) {
        for (i in 1 until nonce.size) {
            val v = (nonce[i].toInt() and 0xff) + 1
            nonce[i] = (v and 0xff).toByte()
            if (v != 0x100) break
        }
    }

    private fun decrementHighBytes(nonce: ByteArray) {
        for (i in 1 until nonce.size) {
            val v = (nonce[i].toInt() and 0xff) - 1
            nonce[i] = (v and 0xff).toByte()
            if (v != -1) break
        }
    }
}

/**
 * The 256-entry `(iv[0] -> iv[1])` replay memory of the official client: a
 * packet whose first two nonce bytes match a previously accepted one is
 * rejected instead of being decrypted twice.
 */
internal class CryptReplayHistory {
    private val entries = ByteArray(256)

    fun clear() {
        entries.fill(0)
    }

    /** @return true when this `(iv[0], iv[1])` pair was already accepted. */
    fun isReplay(iv0: Int, iv1: Byte): Boolean = entries[iv0 and 0xff] == iv1

    fun remember(iv0: Int, iv1: Byte) {
        entries[iv0 and 0xff] = iv1
    }
}

/**
 * The good/late/lost packet counters, mirroring the official
 * `CryptStateOCB2::decrypt` bookkeeping.
 */
internal class CryptPacketStats {
    var good = 0
        private set
    var late = 0
        private set
    var lost = 0
        private set
    var resync = 0
        private set

    /**
     * A late packet reports `lost = -1`, which must *subtract* from the loss
     * count (the packet was not actually lost), but never below zero.
     */
    fun onDecrypted(lost: Int, late: Int) {
        good++
        if (late > 0) {
            this.late += late
        } else if (this.late > kotlin.math.abs(late)) {
            this.late += late
        }
        if (lost > 0) {
            this.lost += lost
        } else if (this.lost > kotlin.math.abs(lost)) {
            this.lost += lost
        }
    }

    /** Official `msgCryptSetup`: counted on the control thread, not in decrypt. */
    fun onResync() {
        resync++
    }

    fun reset() {
        good = 0
        late = 0
        lost = 0
        resync = 0
    }
}
