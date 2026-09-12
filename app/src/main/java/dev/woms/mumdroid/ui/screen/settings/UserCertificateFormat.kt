package dev.woms.mumdroid.ui.screen.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers for the certificate screens. Kept out of the
 * composables so the rendering rules (what a blank subject looks like, how a
 * validity timestamp is printed) can be unit-tested without a Compose host.
 */
internal object CertificateFormatting {

    /** The compact label of a certificate subject: the X.500 CN only. */
    fun displaySubject(subject: String): String = subject.removePrefix("CN=")

    /**
     * Renders a certificate validity timestamp as `yyyy-MM-dd` in the device
     * time zone. Non-positive values (missing/unparsable metadata) render as
     * a dash instead of the epoch date.
     */
    fun formatEpoch(epochMillis: Long): String {
        if (epochMillis <= 0) return "-"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(epochMillis))
    }

    /**
     * Renders a raw lower-case hex digest the way the official certificate
     * viewer does (`ViewCert::prettifyDigest`): upper-case, one `:` between
     * bytes. Used for the server's `UserState.hash`, which arrives as bare
     * hex but is shown as the same colon-separated fingerprint as a locally
     * computed digest. Anything that is not an even-length hex string is
     * returned trimmed and unchanged.
     */
    fun prettifyDigest(digest: String): String {
        val trimmed = digest.trim()
        val isHex = trimmed.length % 2 == 0 &&
            trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (!isHex) return trimmed
        return trimmed.chunked(2).joinToString(":") { it.uppercase(Locale.US) }
    }
}

/**
 * Immutable state of the two-step delete confirmation used by the certificate
 * picker: the first confirmation request arms the pending item, the second one
 * yields it for deletion.
 *
 * Holding the rule in a plain value type keeps the composable a thin shell and
 * makes "the user really has to confirm twice" unit-testable.
 */
internal data class DeleteConfirmation<T>(
    val candidate: T? = null,
    val requiresSecondStep: Boolean = false,
) {

    /** Arms the confirmation for [item] (first step of a fresh request). */
    fun request(item: T) = DeleteConfirmation(candidate = item, requiresSecondStep = false)

    /**
     * Advances the confirmation by one step. Returns the next state and, once
     * the second step is confirmed, the item the caller should delete.
     */
    fun advance(): Pair<DeleteConfirmation<T>, T?> = when {
        candidate == null -> Pair(this, null)
        requiresSecondStep -> Pair(DeleteConfirmation<T>(), candidate)
        else -> Pair(DeleteConfirmation(candidate, requiresSecondStep = true), null)
    }

    /** Abandons the pending deletion. */
    fun dismiss() = DeleteConfirmation<T>()
}
