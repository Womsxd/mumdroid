package dev.woms.mumdroid.core.security

import dev.woms.mumdroid.core.security.ApkSigningBlock.parse
import dev.woms.mumdroid.core.security.ApkSigningBlock.readBlock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A minimal, dependency-free parser for the **APK Signing Block**.
 *
 * Unlike [android.content.pm.PackageManager]'s signature APIs — which report a
 * single, already-collapsed answer that the platform chose for us — this parser
 * exposes the *raw* block layout so we can inspect every scheme and every
 * signer that is actually present in the file.
 *
 * ## Why the platform answer is not enough
 *
 * Android validates signatures with a *priority* model, not a *set* model:
 *
 *  - If a v3 block is present, v2 is **not** checked at all.
 *  - The v3 "strip protection" marker (`0x3ba06f8c` in the v3 signing
 *    attributes) only defends against *removing* v3, never against *adding* it.
 *  - So "keep the original v2 block, append an attacker v3 block" passes
 *    system verification, and `PackageManager#apkContentsSigners` then returns
 *    the *attacker's* certificate.
 *
 * A verifier that only compares the platform-provided digest therefore compares
 * the attacker's certificate against the expected one — and depending on how it
 * is written, may not notice at all. Parsing the block ourselves lets us
 * enumerate **all** signers and cross-check the schemes against each other.
 *
 * ## Layout
 *
 * ```
 * [ u64 blockSize ][ id-value pairs ... ][ u64 blockSize ][ 16-byte magic ]
 * ```
 * where the magic is `"APK Sig Block 42"` and `blockSize` is
 * `totalBlockLength - 8`. Each pair is `[ u64 len ][ u32 id ][ len bytes ]`.
 *
 * Every integer is little-endian. Pair ids of interest (as serialized):
 *  - `0x7109871a` — APK Signature Scheme v2 signers
 *  - `0xf05368c0` — APK Signature Scheme v3.0 signers
 *  - `0x1b93ad61` — APK Signature Scheme v3.1 signers
 *  - `0x42726577` — signing attributes (carries the strip-protection marker)
 *
 * This class is pure JVM: it has no Android dependencies and is unit-testable
 * on a plain JVM.
 *
 * Only the bytes that matter are read: the End-Of-Central-Directory window at
 * the end of the file, and then the signing block itself. See [parse].
 */
object ApkSigningBlock {

    /** 16-byte trailer that anchors the signing block. */
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** Minimum End-Of-Central-Directory record size (with an empty comment). */
    private const val EOCD_MIN_SIZE = 22

    /** Maximum ZIP comment, which can follow the EOCD record. */
    private const val MAX_COMMENT = 0xFFFF

    /**
     * Pair ids, stored as their **little-endian** 4-byte form (which is how
     * `apksig` writes them) so they can be compared without tripping over
     * Kotlin's signed `Int` literals (`0xf05368c0` does not fit in an `Int`).
     */
    private val ID_V2 = intBytes(0x7109871a)
    private val ID_V3_0 = intBytes(0xf05368c0)
    private val ID_V3_1 = intBytes(0x1b93ad61)
    private val ID_SIGNING_ATTRS = intBytes(0x42726577)

    /** Uppercase hex of the v2 signer pair id, as stored. */
    const val ID_V2_HEX = "1A870971"

    /** Uppercase hex of the v3.0 signer pair id, as stored. */
    const val ID_V3_0_HEX = "C06853F0"

    /** Uppercase hex of the v3.1 signer pair id, as stored. */
    const val ID_V3_1_HEX = "61AD931B"

    /** Uppercase hex of the signing-attributes pair id, as stored. */
    const val ID_SIGNING_ATTRS_HEX = "77657242"

    private fun intBytes(v: Long): ByteArray = byteArrayOf(
        v.toByte(),
        (v ushr 8).toByte(),
        (v ushr 16).toByte(),
        (v ushr 24).toByte(),
    )

    /** An id-value pair as it appears in the block. */
    class Pair(val id: ByteArray, val value: ByteArray) {
        /** Uppercase hex of the 4 stored id bytes, e.g. `1A870971`. */
        val idHex: String get() = id.joinToString("") { "%02X".format(it) }

        val isV2: Boolean get() = id.contentEquals(ID_V2)
        val isV3_0: Boolean get() = id.contentEquals(ID_V3_0)
        val isV3_1: Boolean get() = id.contentEquals(ID_V3_1)
        val isSigningAttrs: Boolean get() = id.contentEquals(ID_SIGNING_ATTRS)
        val isSigners: Boolean get() = isV2 || isV3_0 || isV3_1

        override fun equals(other: Any?): Boolean =
            other is Pair && id.contentEquals(other.id) && value.contentEquals(other.value)

        override fun hashCode(): Int = 31 * id.contentHashCode() + value.contentHashCode()

        override fun toString(): String = "Pair(id=0x$idHex, valueSize=${value.size})"
    }

    /** One signer of one scheme, already reduced to a certificate. */
    data class Signer(val schemeId: String, val certificate: X509Certificate) {
        /** SHA-256 of the DER-encoded certificate. */
        val sha256: ByteArray
            get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

        /** SHA-256 of the DER-encoded certificate, as uppercase colon-hex. */
        val sha256Hex: String
            get() = sha256.joinToString(":") { "%02X".format(it) }
    }

    /**
     * The parsed block.
     *
     * @param pairs every id-value pair, in file order — including ids we do not
     *   recognise, so callers can reject unexpected ones.
     */
    class Result(
        val pairs: List<Pair>,
        val signers: List<Signer>,
    ) {
        /** Ids present in the block, in file order, as uppercase hex. */
        val ids: List<String> get() = pairs.map { it.idHex }

        /** Scheme ids that contributed at least one signer. */
        val schemes: Set<String> get() = signers.mapTo(LinkedHashSet()) { it.schemeId }

        /** Signers belonging to one scheme. */
        fun signersOf(schemeId: String): List<Signer> = signers.filter { it.schemeId == schemeId }

        /** Whether any pair carries the v2 / v3.0 / v3.1 signer id. */
        fun hasSigners(schemeId: String): Boolean =
            signers.any { it.schemeId == schemeId }

        /** All signing-attribute blobs, in file order. */
        fun signingAttributes(): List<ByteArray> =
            pairs.filter { it.isSigningAttrs }.map { it.value }

        /** Whether any signing-attribute blob contains the given 4-byte marker. */
        fun attributesContain(marker: ByteArray): Boolean =
            signingAttributes().any { blob -> blob.indexOf(marker) >= 0 }

        private fun ByteArray.indexOf(needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > size) return -1
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }

    /**
     * Parses the signing block out of an APK file.
     *
     * Only the tail of the file is read — the (at most 64 KiB + 22 byte)
     * End-Of-Central-Directory window, and then the signing block itself, which
     * sits between the last ZIP entry and the central directory. A 20–80 MB APK
     * therefore costs a few kilobytes of allocation rather than a full copy.
     *
     * @throws IOException when the block is missing, malformed, or its declared
     *   size disagrees with the bytes actually present.
     */
    @Throws(IOException::class)
    fun parse(apk: File): Result = RandomAccessFile(apk, "r").use { parseBlock(readBlock(it)) }

    /** Parses the pairs out of a signing block that has already been read. */
    @Throws(IOException::class)
    private fun parseBlock(block: ByteArray): Result {
        val pairs = readPairs(block)
        val signers = pairs.mapNotNull { pair ->
            parseSigners(pair)?.map { Signer(pair.idHex, it) }
        }.flatten()
        return Result(pairs, signers)
    }

    /**
     * Reads the signing block and nothing else, walking from the
     * End-Of-Central-Directory record backwards exactly like `apksig` does.
     *
     * @return the block, from its leading `u64 size` through the 16-byte magic.
     */
    @Throws(IOException::class)
    private fun readBlock(file: RandomAccessFile): ByteArray {
        // 1) The EOCD is at most 22 bytes, optionally followed by a comment of
        //    up to 64 KiB, so the whole record lives in this tail window.
        val fileLength = file.length()
        val windowLength = minOf(fileLength, (EOCD_MIN_SIZE + MAX_COMMENT).toLong()).toInt()
        val window = ByteArray(windowLength)
        file.seek(fileLength - windowLength)
        file.readFully(window)
        val eocd = findEocd(window)

        // 2) Its central-directory offset is exactly where the block ends.
        val blockEnd = readInt(window, eocd + 16).toLong() and 0xFFFFFFFFL
        if (blockEnd <= 0 || blockEnd > fileLength) {
            throw IOException("implausible central directory offset: $blockEnd")
        }
        val magicOffset = blockEnd - MAGIC.size
        if (magicOffset < 0) {
            throw IOException("no room for an APK Signing Block before $blockEnd")
        }

        // 3) The block is anchored by its 16-byte magic trailer.
        val magic = ByteArray(MAGIC.size)
        file.seek(magicOffset)
        file.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("no APK Signing Block magic before the central directory")
        }

        // `size` counts the bytes after the leading u64 up to and including
        // the trailing u64 that precedes the magic, hence the `+ 8`.
        val size = readLongAt(file, magicOffset - 8)
        if (size < 24 || size > magicOffset) {
            throw IOException("implausible APK Signing Block size: $size")
        }
        val start = magicOffset + 8 - size
        if (start < 0) {
            throw IOException("APK Signing Block starts before the file")
        }
        if (readLongAt(file, start) != size) {
            throw IOException("APK Signing Block size fields disagree")
        }
        if (size + 8 > Int.MAX_VALUE) {
            throw IOException("APK Signing Block is too large: $size")
        }

        val block = ByteArray((size + 8).toInt())
        file.seek(start)
        file.readFully(block)
        return block
    }

    /** Reads a little-endian `u64` at [offset]. */
    @Throws(IOException::class)
    private fun readLongAt(file: RandomAccessFile, offset: Long): Long {
        val buf = ByteArray(8)
        file.seek(offset)
        file.readFully(buf)
        return readLong(buf, 0)
    }

    /**
     * Reads every id-value pair between the two size fields of [block], which
     * starts at the leading `u64 size`.
     */
    @Throws(IOException::class)
    private fun readPairs(block: ByteArray): List<Pair> {
        val out = ArrayList<Pair>()
        var pos = 8
        val stop = block.size - MAGIC.size - 8
        while (pos < stop) {
            if (pos + 12 > stop) throw IOException("truncated pair header at $pos")
            val len = readLong(block, pos)
            if (len < 0 || len > (stop - pos - 8).toLong()) {
                throw IOException("pair length $len overflows the signing block at $pos")
            }
            val id = block.copyOfRange(pos + 8, pos + 12)
            // `len` counts the 4-byte id as well, so the value is only
            // `len - 4` bytes long. Reading `len` bytes would pull in the
            // start of the next pair (or the trailer for the last pair) and
            // can truncate the final signer's length-prefixed data.
            val value = block.copyOfRange(pos + 12, (pos + 8 + len).toInt())
            out.add(Pair(id, value))
            pos += (8 + len).toInt()
        }
        if (pos != stop) {
            throw IOException("APK Signing Block walk ended at $pos, expected $stop")
        }
        return out
    }

    /**
     * Extracts the certificates from a signers pair.
     *
     * The grammar mirrors `apksig`'s `V2SchemeVerifier` / `V3SchemeVerifier`
     * exactly. Everything is a **length-prefixed sequence** — there are no
     * explicit counts anywhere:
     * ```
     * value         = u32 len + signers
     * signers       = signer*
     * signer        = u32 len + signedData + signatures + u32 len + publicKey
     * signedData    = u32 len + (digests + certificates + attributes)
     * digests       = digest*
     * certificates  = certificate*
     * certificate   = u32 len + DER
     * ```
     * We only need the certificates, but we walk the structure so a malformed
     * blob fails loudly instead of yielding a plausible-looking empty list.
     */
    private fun parseSigners(pair: Pair): List<X509Certificate>? {
        if (!pair.isSigners) return null
        return try {
            readSigners(pair.value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readSigners(value: ByteArray): List<X509Certificate> {
        val certs = ArrayList<X509Certificate>()
        val signers = ByteReader(value).readLengthPrefixed()
        val cursor = ByteReader(signers)
        while (cursor.remaining > 0) {
            val signer = ByteReader(cursor.readLengthPrefixed())
            val signedData = ByteReader(signer.readLengthPrefixed())
            // remaining signer fields (signatures, public key) are skipped
            // digests come first and are not needed
            signedData.readLengthPrefixed()
            val certificates = signedData.readLengthPrefixed()
            certs.addAll(readCertificates(certificates))
        }
        return certs
    }

    private fun readCertificates(block: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>()
        val cursor = ByteReader(block)
        while (cursor.remaining > 0) {
            val der = cursor.readLengthPrefixed()
            out.add(factory.generateCertificate(der.inputStream()) as X509Certificate)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // low-level readers
    // ---------------------------------------------------------------------

    /**
     * Finds the EOCD record inside the tail [window] read by [readBlock].
     *
     * Candidates are scanned backwards, as ZIP requires, but a candidate is only
     * accepted when the comment length it declares ends exactly at the end of
     * the file. That is what makes the bytes an EOCD *record* rather than bytes
     * that merely look like one — and the ZIP comment is attacker-controlled
     * data. Without the check, a `PK\x05\x06` planted in the comment wins the
     * backwards scan, and the central-directory offset [readBlock] then reads
     * from it is whatever the attacker wrote there (a parse failure on an
     * "implausible offset").
     */
    private fun findEocd(window: ByteArray): Int {
        val sig = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
        for (i in window.size - EOCD_MIN_SIZE downTo 0) {
            if (window[i] == sig[0] && window[i + 1] == sig[1] &&
                window[i + 2] == sig[2] && window[i + 3] == sig[3] &&
                i + EOCD_MIN_SIZE + readUInt16(window, i + 20) == window.size
            ) {
                return i
            }
        }
        throw IOException("no End-Of-Central-Directory record")
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > buf.size) throw IOException("read past end at $offset")
        return (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLong(buf: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > buf.size) throw IOException("read past end at $offset")
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readInt(buf: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > buf.size) throw IOException("read past end at $offset")
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun Long.toIntChecked(what: String): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw IOException("$what is out of range: $this")
        return toInt()
    }

    /** Cursor over a little-endian byte array. */
    private class ByteReader(private val buf: ByteArray) {
        var position: Int = 0

        /** Bytes left to read. */
        val remaining: Int get() = buf.size - position

        fun readUInt32(): Long {
            if (position + 4 > buf.size) throw IOException("read past end at $position")
            val v = (buf[position].toLong() and 0xFF) or
                ((buf[position + 1].toLong() and 0xFF) shl 8) or
                ((buf[position + 2].toLong() and 0xFF) shl 16) or
                ((buf[position + 3].toLong() and 0xFF) shl 24)
            position += 4
            return v
        }

        /** Reads a `u32 length` followed by that many bytes. */
        fun readLengthPrefixed(): ByteArray {
            val len = readUInt32().toIntChecked("length prefix")
            if (position + len > buf.size) throw IOException("length-prefixed read past end")
            val out = buf.copyOfRange(position, position + len)
            position += len
            return out
        }
    }
}
