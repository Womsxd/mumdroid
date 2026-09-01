package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSpeakBlockedTest {

    @Test
    fun idleUser_canSpeak() {
        assertFalse(User(session = 1, name = "a").isSpeakBlocked)
    }

    @Test
    fun selfMute_blocksSpeak() {
        assertTrue(User(session = 1, name = "a", selfMute = true).isSpeakBlocked)
    }

    @Test
    fun serverMute_blocksSpeak() {
        assertTrue(User(session = 1, name = "a", mute = true).isSpeakBlocked)
    }

    @Test
    fun aclSuppress_blocksSpeak() {
        assertTrue(User(session = 1, name = "a", suppress = true).isSpeakBlocked)
    }

    @Test
    fun talkingFlag_doesNotOverrideBlock() {
        val user = User(session = 1, name = "a", mute = true, talking = true)
        assertTrue(user.isSpeakBlocked)
    }
}
