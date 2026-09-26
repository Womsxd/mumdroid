package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.model.ServerPort.MAX
import dev.woms.mumdroid.core.model.ServerPort.MAX_DIGITS
import dev.woms.mumdroid.core.model.ServerPort.MIN
import dev.woms.mumdroid.core.model.ServerPort.parse


/**
 * The TCP port a [MumbleServer] listens on, plus the rules the server edit
 * dialog needs to turn typed text into one.
 *
 * Kept out of the dialog so the accepted range is testable on its own: a port
 * of 0 or above 65535 is not something a socket can listen on or dial, yet the
 * dialog's old `toIntOrNull()` check accepted both and stored them.
 */
object ServerPort {

    /** The port the official client pre-fills. */
    const val DEFAULT = 64738

    /** Ports are 16-bit; 0 is reserved and is never a listen port. */
    const val MIN = 1
    const val MAX = 65535

    /** Enough for [MAX]; anything longer cannot be a port. */
    private const val MAX_DIGITS = 5

    /**
     * [raw] reduced to what a port may consist of: ASCII digits, at most
     * [MAX_DIGITS] of them. Full-width digits are dropped rather than kept,
     * because [parse] could not read them back.
     */
    fun filter(raw: String): String = raw.filter { it in '0'..'9' }.take(MAX_DIGITS)

    /**
     * [text] as a port, or null when it is not a number in [MIN]..[MAX].
     * Surrounding whitespace is ignored; nothing else is.
     */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }
}
