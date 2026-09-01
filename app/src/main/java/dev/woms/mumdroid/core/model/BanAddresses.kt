package dev.woms.mumdroid.core.model

import java.net.InetAddress
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class BanIpKind { V4, V6 }

/**
 * Ban-list address encoding matching desktop `HostAddress` / `BanEditor`:
 * IPv4 is stored as IPv4-mapped IPv6 (`::ffff:a.b.c.d`) with mask += 96.
 */
object BanAddresses {
    const val IPV4_MAPPED_PREFIX = 96
    val IPV4_MASK_RANGE = 8..32
    val IPV6_MASK_RANGE = 8..128

    fun parseKind(address: String): BanIpKind? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        parseIpv4(trimmed)?.let { return BanIpKind.V4 }
        val v6 = parseIpv6(trimmed) ?: return null
        return if (isIpv4Mapped(v6)) BanIpKind.V4 else BanIpKind.V6
    }

    fun maskRange(kind: BanIpKind): IntRange =
        if (kind == BanIpKind.V4) IPV4_MASK_RANGE else IPV6_MASK_RANGE

    fun displayAddress(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            when {
                bytes.size == 4 -> ipv4String(bytes)
                isIpv4Mapped(bytes) -> ipv4String(bytes.copyOfRange(12, 16))
                bytes.size == 16 -> InetAddress.getByAddress(bytes).hostAddress.orEmpty()
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun displayMask(bytes: ByteArray, storedMask: Int): Int {
        if (bytes.size == 4 || isIpv4Mapped(bytes)) {
            return (storedMask - IPV4_MAPPED_PREFIX).coerceIn(IPV4_MASK_RANGE)
        }
        return storedMask.coerceIn(IPV6_MASK_RANGE)
    }

    /**
     * @return wire address and stored mask, or null if [address] is invalid.
     * Empty address encodes as empty bytes / mask 0 (hash-only bans).
     */
    fun encode(address: String, displayMask: Int): Pair<ByteArray, Int>? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return byteArrayOf() to 0
        parseIpv4(trimmed)?.let { raw ->
            val mask = displayMask.coerceIn(IPV4_MASK_RANGE)
            return mapV4(raw) to mask + IPV4_MAPPED_PREFIX
        }
        val v6 = parseIpv6(trimmed) ?: return null
        if (isIpv4Mapped(v6)) {
            val mask = displayMask.coerceIn(IPV4_MASK_RANGE)
            return v6 to mask + IPV4_MAPPED_PREFIX
        }
        val mask = displayMask.coerceIn(IPV6_MASK_RANGE)
        return v6 to mask
    }

    fun defaultDisplayMask(address: String): Int {
        val kind = parseKind(address) ?: return IPV4_MASK_RANGE.last
        return maskRange(kind).last
    }

    /**
     * List title: username when present, otherwise the IP (with mask),
     * otherwise the certificate hash.
     */
    fun label(name: String, address: ByteArray, hash: String, mask: Int = 0): String {
        val nick = name.trim()
        if (nick.isNotEmpty()) return nick
        val ip = displayAddress(address)
        if (ip.isNotEmpty()) {
            return if (mask > 0) "$ip/${displayMask(address, mask)}" else ip
        }
        return hash.trim().ifEmpty { "—" }
    }

    /**
     * Manual add requires a valid IP; name, reason and hash are optional.
     * Editing a hash-only ban still allows saving without an address.
     */
    fun canSave(
        address: String,
        mask: Int,
        hash: String,
        permanent: Boolean,
        durationSeconds: Int,
        requireIp: Boolean,
    ): Boolean {
        if (!permanent && durationSeconds <= 0) return false
        val trimmed = address.trim()
        if (trimmed.isNotEmpty() && parseKind(trimmed) == null) return false
        val hasIp = encode(address, mask)?.first?.isNotEmpty() == true
        return if (requireIp) hasIp else hasIp || hash.isNotBlank()
    }

    /** Literal IPv4 `a.b.c.d` only; does not resolve hostnames. */
    fun parseIpv4(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            bytes[i] = n.toByte()
        }
        return bytes
    }

    /** Literal IPv6 only (must contain `:`); does not resolve hostnames. */
    fun parseIpv6(address: String): ByteArray? {
        if (!address.contains(':')) return null
        if (address.any { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return try {
            val parsed = InetAddress.getByName(address)
            val bytes = parsed.address
            if (bytes.size == 16) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    private fun mapV4(raw: ByteArray): ByteArray {
        val mapped = ByteArray(16)
        mapped[10] = 0xFF.toByte()
        mapped[11] = 0xFF.toByte()
        raw.copyInto(mapped, 12)
        return mapped
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (i in 0..9) if (bytes[i] != 0.toByte()) return false
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    private fun ipv4String(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

object BanTimes {
    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    private val compactFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun parse(iso: String): Instant? {
        val trimmed = iso.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Instant.parse(trimmed)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(trimmed).toInstant()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC)
                } catch (_: Exception) {
                    try {
                        LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun format(iso: String): String? {
        val instant = parse(iso) ?: return null
        return displayFormat.format(instant)
    }

    fun format(instant: Instant): String = displayFormat.format(instant)

    fun formatCompact(instant: Instant): String = compactFormat.format(instant)

    fun durationSeconds(days: Int, hours: Int, minutes: Int): Int {
        val total = days.toLong() * 86_400L + hours.toLong() * 3_600L + minutes.toLong() * 60L
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun partsFromSeconds(seconds: Int): Triple<Int, Int, Int> {
        val s = seconds.coerceAtLeast(0)
        return Triple(s / 86_400, (s % 86_400) / 3_600, (s % 3_600) / 60)
    }

    fun secondsBetween(start: Instant, end: Instant): Int {
        val secs = ChronoUnit.SECONDS.between(start, end)
        return secs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun ofLocal(date: LocalDate, hour: Int, minute: Int): Instant =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()

    /**
     * Timed bans whose end is not strictly after [now] + [graceSeconds]
     * would take effect as an immediate unban.
     */
    fun isEffectivelyExpired(
        start: Instant,
        durationSeconds: Int,
        now: Instant = Instant.now(),
        graceSeconds: Long = 5,
    ): Boolean {
        if (durationSeconds <= 0) return false
        val end = start.plusSeconds(durationSeconds.toLong())
        return !end.isAfter(now.plusSeconds(graceSeconds))
    }
}
