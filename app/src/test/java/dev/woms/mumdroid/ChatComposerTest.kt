package dev.woms.mumdroid

import androidx.compose.ui.text.TextRange
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.ui.screen.ChatComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's prefix is a real part of the field text, so every edit path
 * (typing `@`/`#`, picking a target, switching targets, deleting the prefix) has
 * to keep the body, prefix and destination consistent.
 */
class ChatComposerTest {

    private fun composer(channelId: Int = 5) = ChatComposer(channelId)

    private val alice = User(session = 7, name = "alice")
    private val lobby = Channel(id = 3, name = "Lobby")

    @Test
    fun plainText_sendsToTheDefaultChannel() {
        val c = composer()
        c.onTextChanged("hello")
        val (destination, message) = c.consume()!!
        assertEquals(ChatComposer.Destination.Channel(5), destination)
        assertEquals("hello", message)
    }

    @Test
    fun typingAt_opensTheUserPickerAndKeepsTheChar() {
        val c = composer()
        c.onTextChanged("hi @")
        assertEquals(ChatComposer.Picker.USER, c.picker)
        assertEquals("hi @", c.text)
    }

    @Test
    fun typingHash_opensTheChannelPickerAndKeepsTheChar() {
        val c = composer()
        c.onTextChanged("#")
        assertEquals(ChatComposer.Picker.CHANNEL, c.picker)
        assertEquals("#", c.text)
    }

    @Test
    fun typingMoreWithoutPicking_closesThePicker() {
        val c = composer()
        c.onTextChanged("@")
        assertEquals(ChatComposer.Picker.USER, c.picker)
        c.onTextChanged("@a")
        assertEquals(ChatComposer.Picker.NONE, c.picker)
    }

    @Test
    fun selectingUser_replacesTheAtCharWithThePrefix() {
        val c = composer()
        c.onTextChanged("@")
        c.selectUser(alice)
        assertEquals("@alice ", c.text)
        assertEquals(ChatComposer.Picker.NONE, c.picker)
        assertEquals(ChatComposer.Destination.Private(7), c.destination())
        assertEquals("", c.body.trim())
    }

    @Test
    fun selectingUser_keepsTextTypedBeforeTheAtChar() {
        // Preserved behaviour: the text before the @ is kept after the prefix,
        // so the prefix still starts the field (and stays highlightable).
        val c = composer()
        c.onTextChanged("hi @")
        c.selectUser(alice)
        assertEquals("@alice hi ", c.text)
        assertTrue(c.hasHighlightedPrefix)
    }

    @Test
    fun selectingChannel_usesTheChannelDestination() {
        val c = composer()
        c.onTextChanged("#")
        c.selectChannel(lobby)
        assertEquals("#Lobby ", c.text)
        assertEquals(ChatComposer.Destination.Channel(3), c.destination())
    }

    @Test
    fun switchingTargets_replacesThePreviousPrefix() {
        val c = composer()
        c.selectUser(alice)
        c.onTextChanged("@alice hello")
        c.selectChannel(lobby)
        assertEquals("#Lobby hello", c.text)
        assertNull(c.privateTarget)
        assertEquals(ChatComposer.Destination.Channel(3), c.destination())
    }

    @Test
    fun deletingThePrefix_cancelsTheSelection() {
        val c = composer()
        c.selectUser(alice)
        assertEquals(ChatComposer.Destination.Private(7), c.destination())
        // The user deleted the highlighted prefix: the target is cancelled and
        // the message would go to the channel instead.
        c.onTextChanged("hello")
        assertNull(c.privateTarget)
        assertEquals(ChatComposer.Destination.Channel(5), c.destination())
    }

    @Test
    fun consume_trimsTheBodyAndResets() {
        val c = composer()
        c.selectChannel(lobby)
        c.onTextChanged("#Lobby   spaced  ")
        val (destination, message) = c.consume()!!
        assertEquals(ChatComposer.Destination.Channel(3), destination)
        assertEquals("spaced", message)
        assertEquals("", c.text)
        assertNull(c.channelTarget)
        assertNull(c.destination().let { it as? ChatComposer.Destination.Private })
    }

    @Test
    fun consume_blankBodyReturnsNullAndKeepsState() {
        val c = composer()
        c.onTextChanged("   ")
        assertNull(c.consume())
        assertEquals("   ", c.text)
    }

    @Test
    fun body_stripsThePrefixOnlyWhenPresent() {
        val c = composer()
        c.onTextChanged("plain")
        assertEquals("plain", c.body)
        c.selectUser(alice)
        c.onTextChanged("@alice text")
        assertEquals("text", c.body)
        assertTrue(c.hasHighlightedPrefix)
    }

    @Test
    fun typing_keepsTheCaretWhereTheEditorPutIt() {
        // Regression: the field is a controlled component, so the selection has
        // to be written back with the text. Dropping it makes the caret jump to
        // the start of the box after every keystroke.
        val c = composer()
        c.onTextChanged("hi", TextRange(2))
        assertEquals(TextRange(2), c.selection)
        c.onTextChanged("hi!", TextRange(3))
        assertEquals(TextRange(3), c.selection)
    }

    @Test
    fun editingInTheMiddle_keepsTheCaretPosition() {
        val c = composer()
        c.onTextChanged("hlo", TextRange(3))
        // "l" inserted after the "h": the caret stays between "l" and "o".
        c.onTextChanged("hllo", TextRange(2))
        assertEquals(TextRange(2), c.selection)
        assertEquals("hllo", c.text)
    }

    @Test
    fun onTextChanged_withoutASelection_defaultsToTheStart() {
        val c = composer()
        c.onTextChanged("hello", TextRange(5))
        c.onTextChanged("hello")
        assertEquals(TextRange.Zero, c.selection)
        assertEquals("hello", c.text)
    }

    @Test
    fun selection_isClampedToTheTextLength() {
        // Switching targets shortens the text; the caret from the longer text
        // must not survive as an out-of-range selection.
        val c = composer()
        c.onTextChanged("@alice a very long body", TextRange(23))
        c.selectChannel(lobby)
        assertEquals(TextRange(c.text.length), c.selection)
    }

    @Test
    fun selectingTarget_putsTheCaretAfterThePrefix() {
        val c = composer()
        c.onTextChanged("@", TextRange(1))
        c.selectUser(alice)
        assertEquals("@alice ", c.text)
        assertEquals(TextRange("@alice ".length), c.selection)
    }

    @Test
    fun reset_clearsTextAndTargets() {
        val c = composer()
        c.selectUser(alice)
        c.onTextChanged("@alice x")
        c.reset()
        assertEquals("", c.text)
        assertEquals(TextRange.Zero, c.selection)
        assertNull(c.privateTarget)
        assertNull(c.channelTarget)
        assertEquals(ChatComposer.Picker.NONE, c.picker)
    }
}
