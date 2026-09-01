package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.UserModeration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserModerationTest {

    @Test
    fun kick_setsBanFalse() {
        val msg = UserModeration.kick(7, "spam")
        assertEquals(7, msg.session)
        assertEquals("spam", msg.reason)
        assertFalse(msg.ban)
        assertFalse(msg.hasBanCertificate())
        assertFalse(msg.hasBanIp())
    }

    @Test
    fun ban_setsSelectiveFlags() {
        val msg = UserModeration.ban(3, "abuse", banCertificate = true, banIp = false)
        assertEquals(3, msg.session)
        assertEquals("abuse", msg.reason)
        assertTrue(msg.ban)
        assertTrue(msg.banCertificate)
        assertFalse(msg.banIp)
    }

    @Test
    fun selectiveBan_requiresVersion16() {
        val v15 = (1L shl 48) or (5L shl 32)
        val v16 = (1L shl 48) or (6L shl 32)
        assertFalse(UserModeration.supportsSelectiveBan(v15))
        assertTrue(UserModeration.supportsSelectiveBan(v16))
        assertTrue(UserModeration.supportsSelectiveBan(0, legacyVersion = 0x010600))
        assertFalse(UserModeration.supportsSelectiveBan(0, legacyVersion = 0x010500))
    }

    @Test
    fun register_setsUserIdZero() {
        val msg = UserModeration.register(9)
        assertEquals(9, msg.session)
        assertEquals(0, msg.userId)
        assertTrue(msg.hasUserId())
    }

    @Test
    fun moveToChannel_setsSessionAndChannel() {
        val msg = UserModeration.moveToChannel(session = 8, channelId = 3)
        assertEquals(8, msg.session)
        assertEquals(3, msg.channelId)
        assertTrue(msg.hasChannelId())
        assertFalse(msg.hasMute())
        assertFalse(msg.hasSuppress())
    }

    @Test
    fun prioritySpeaker_setsSessionAndFlag() {
        val on = UserModeration.prioritySpeaker(session = 5, enabled = true)
        assertEquals(5, on.session)
        assertTrue(on.hasPrioritySpeaker())
        assertTrue(on.prioritySpeaker)
        val off = UserModeration.prioritySpeaker(session = 5, enabled = false)
        assertTrue(off.hasPrioritySpeaker())
        assertFalse(off.prioritySpeaker)
    }

    @Test
    fun remoteMute_setsMuteOnly() {
        val msg = UserModeration.remoteMute(
            session = 4,
            currentlyMuted = false,
            currentlySuppressed = false,
            wantMuted = true,
        )
        assertEquals(4, msg.session)
        assertTrue(msg.hasMute())
        assertTrue(msg.mute)
        assertFalse(msg.hasSuppress())
    }

    @Test
    fun remoteMute_unmutesServerMute() {
        val msg = UserModeration.remoteMute(
            session = 4,
            currentlyMuted = true,
            currentlySuppressed = false,
            wantMuted = false,
        )
        assertTrue(msg.hasMute())
        assertFalse(msg.mute)
        assertFalse(msg.hasSuppress())
    }

    @Test
    fun remoteMute_liftsAclSuppress() {
        val msg = UserModeration.remoteMute(
            session = 4,
            currentlyMuted = false,
            currentlySuppressed = true,
            wantMuted = false,
        )
        assertFalse(msg.hasMute())
        assertTrue(msg.hasSuppress())
        assertFalse(msg.suppress)
    }

    @Test
    fun remoteMute_liftsMuteAndSuppressTogether() {
        val msg = UserModeration.remoteMute(
            session = 4,
            currentlyMuted = true,
            currentlySuppressed = true,
            wantMuted = false,
        )
        assertTrue(msg.hasMute())
        assertFalse(msg.mute)
        assertTrue(msg.hasSuppress())
        assertFalse(msg.suppress)
    }

    @Test
    fun initialBanOptions_matchDesktop() {
        val old = UserModeration.initialBanOptions(showBanOptions = false, hasCertificate = true)
        assertTrue(old.banCertificate)
        assertTrue(old.banIp)
        assertFalse(old.optionsEnabled)

        val withCert = UserModeration.initialBanOptions(showBanOptions = true, hasCertificate = true)
        assertTrue(withCert.banCertificate)
        assertFalse(withCert.banIp)
        assertTrue(withCert.optionsEnabled)

        val noCert = UserModeration.initialBanOptions(showBanOptions = true, hasCertificate = false)
        assertFalse(noCert.banCertificate)
        assertTrue(noCert.banIp)
        assertFalse(noCert.optionsEnabled)
    }

    @Test
    fun setChannelListening_addsOrRemoves() {
        val add = UserModeration.setChannelListening(session = 4, channelId = 9, listen = true)
        assertEquals(4, add.session)
        assertEquals(listOf(9), add.listeningChannelAddList)
        assertTrue(add.listeningChannelRemoveList.isEmpty())

        val remove = UserModeration.setChannelListening(session = 4, channelId = 9, listen = false)
        assertEquals(4, remove.session)
        assertTrue(remove.listeningChannelAddList.isEmpty())
        assertEquals(listOf(9), remove.listeningChannelRemoveList)
    }

    @Test
    fun channelListen_requiresVersion14() {
        val v13 = (1L shl 48) or (3L shl 32)
        val v14 = (1L shl 48) or (4L shl 32)
        assertFalse(UserModeration.supportsChannelListen(v13))
        assertTrue(UserModeration.supportsChannelListen(v14))
        assertTrue(UserModeration.supportsChannelListen(0, legacyVersion = 0x010400))
        assertFalse(UserModeration.supportsChannelListen(0, legacyVersion = 0x010300))
    }
}
