package dev.woms.mumdroid.ui.screen

import androidx.compose.ui.text.TextRange
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User

/**
 * The text/target state of the chat input bar: the message body, the selected
 * `@user` / `#channel` prefix, and the rules for opening, replacing and
 * cancelling that selection.
 *
 * Extracted from the Compose input bar so the (fiddly) prefix handling is pure
 * and unit-testable: the highlight in the field is a real prefix in the text, so
 * every edit path has to keep [body], [prefix] and the field value consistent.
 */
internal class ChatComposer(
    /** The channel a message goes to when no `#` target is selected. */
    private val defaultChannelId: Int,
) {
    /** The message as the user sees it, including any selected prefix. */
    var text: String = ""
        private set

    /**
     * The caret/selection inside [text] as a character range. The field is a
     * controlled component: it renders exactly the value its owner passes, so
     * the selection has to travel with the text or the caret snaps back to the
     * start of the field on every recomposition.
     */
    var selection: TextRange = TextRange.Zero
        private set

    /** The user selected for a private message, if any. */
    var privateTarget: User? = null
        private set

    /** The channel explicitly selected, if any. */
    var channelTarget: Channel? = null
        private set

    /** Which inline picker is open, if any. */
    var picker: Picker = Picker.NONE
        private set

    enum class Picker { NONE, USER, CHANNEL }

    /** The highlighted prefix rendered for the current target (or empty). */
    val prefix: String
        get() = privateTarget?.let { "@${it.name} " }
            ?: channelTarget?.let { "#${it.name} " }
            ?: ""

    /** The message body, with the selected prefix stripped. */
    val body: String
        get() = if (prefix.isNotEmpty() && text.startsWith(prefix)) {
            text.removePrefix(prefix)
        } else {
            text
        }

    /** Whether the text start renders as a highlighted target prefix. */
    val hasHighlightedPrefix: Boolean
        get() = prefix.isNotEmpty() && text.startsWith(prefix)

    fun onTextChanged(newText: String, newSelection: TextRange = TextRange.Zero) {
        val last = newText.lastOrNull()
        when {
            last == '@' -> picker = Picker.USER
            last == '#' -> picker = Picker.CHANNEL
            // The user kept typing without picking: close the open picker.
            picker != Picker.NONE -> picker = Picker.NONE
        }
        // Deleting the highlighted selection cancels it and does not re-add it.
        if (prefix.isNotEmpty() && !newText.startsWith(prefix)) {
            clearTargets()
        }
        text = newText
        selection = newSelection.coerceIn(newText)
    }

    /** Applies a `@user` selection (mutually exclusive with the channel target). */
    fun selectUser(user: User) {
        channelTarget = null
        privateTarget = user
        picker = Picker.NONE
        replacePrefix("@${user.name} ")
    }

    /** Applies a `#channel` selection (mutually exclusive with the user target). */
    fun selectChannel(channel: Channel) {
        privateTarget = null
        channelTarget = channel
        picker = Picker.NONE
        replacePrefix("#${channel.name} ")
    }

    private fun replacePrefix(newPrefix: String) {
        var t = text
        // The @/# that opened the picker is kept in the text until a target is
        // chosen, so drop that trailing char before inserting the new prefix.
        if (t.endsWith("@") || t.endsWith("#")) {
            t = t.dropLast(1)
        }
        val rest = when {
            t.startsWith("@") || t.startsWith("#") -> {
                val end = t.indexOf(' ', 1)
                if (end >= 0) t.substring(end + 1) else ""
            }
            else -> t
        }
        text = newPrefix + rest
        // A target was picked from the picker: put the caret after the prefix
        // so the user can keep typing the message body.
        selection = TextRange(text.length)
    }

    /** The destination a [send] would use. */
    fun destination(): Destination = when {
        privateTarget != null -> Destination.Private(privateTarget!!.session)
        channelTarget != null -> Destination.Channel(channelTarget!!.id)
        else -> Destination.Channel(defaultChannelId)
    }

    /**
     * Returns the outgoing (destination, body) and resets the composer, or null
     * when the body is blank.
     */
    fun consume(): Pair<Destination, String>? {
        val message = body.trim()
        if (message.isBlank()) return null
        val target = destination()
        reset()
        return target to message
    }

    fun reset() {
        text = ""
        selection = TextRange.Zero
        clearTargets()
    }

    /**
     * Clamps [this] to the valid range of [text] (the field may have been
     * shortened by an edit, a target switch or a send).
     */
    private fun TextRange.coerceIn(text: String): TextRange {
        val start = start.coerceIn(0, text.length)
        val end = end.coerceIn(0, text.length)
        return if (start <= end) TextRange(start, end) else TextRange(end, start)
    }

    private fun clearTargets() {
        privateTarget = null
        channelTarget = null
    }

    sealed interface Destination {
        data class Channel(val channelId: Int) : Destination
        data class Private(val session: Int) : Destination
    }
}
