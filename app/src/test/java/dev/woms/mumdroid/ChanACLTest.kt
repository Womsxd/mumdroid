package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChanACL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChanACLTest {

    @Test
    fun kick_requiresKickBanOrWrite() {
        assertFalse(ChanACL.canKick(0))
        assertFalse(ChanACL.canKick(ChanACL.MUTE_DEAFEN.toLong()))
        assertTrue(ChanACL.canKick(ChanACL.KICK.toLong()))
        assertTrue(ChanACL.canKick(ChanACL.BAN.toLong()))
        assertTrue(ChanACL.canKick(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canKick((ChanACL.KICK or ChanACL.BAN or ChanACL.WRITE).toLong()))
    }

    @Test
    fun ban_requiresBanOrWrite() {
        assertFalse(ChanACL.canBan(0))
        assertFalse(ChanACL.canBan(ChanACL.KICK.toLong()))
        assertFalse(ChanACL.canBan(ChanACL.MUTE_DEAFEN.toLong()))
        assertTrue(ChanACL.canBan(ChanACL.BAN.toLong()))
        assertTrue(ChanACL.canBan(ChanACL.WRITE.toLong()))
    }

    @Test
    fun muteDeafen_isChannelScoped() {
        assertFalse(ChanACL.canMuteDeafen(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canMuteDeafen(ChanACL.MUTE_DEAFEN.toLong()))
        assertEquals(0x20, ChanACL.MOVE)
    }

    @Test
    fun move_requiresMoveBit() {
        assertFalse(ChanACL.canMove(0))
        assertFalse(ChanACL.canMove(ChanACL.MUTE_DEAFEN.toLong()))
        assertTrue(ChanACL.canMove(ChanACL.MOVE.toLong()))
        assertTrue(ChanACL.canMove(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canMove((ChanACL.WRITE or ChanACL.MOVE).toLong()))
    }

    @Test
    fun prioritySpeaker_requiresWriteOrMuteDeafen() {
        assertFalse(ChanACL.canPrioritySpeaker(0))
        assertFalse(ChanACL.canPrioritySpeaker(ChanACL.KICK.toLong()))
        assertTrue(ChanACL.canPrioritySpeaker(ChanACL.MUTE_DEAFEN.toLong()))
        assertTrue(ChanACL.canPrioritySpeaker(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canPrioritySpeaker((ChanACL.WRITE or ChanACL.MUTE_DEAFEN).toLong()))
    }

    @Test
    fun offerMute_selfOnlyWhenAlreadySilenced() {
        val md = ChanACL.MUTE_DEAFEN
        assertFalse(ChanACL.canOfferMute(0, isSelf = false, muted = false, suppressed = false))
        assertTrue(ChanACL.canOfferMute(md.toLong(), isSelf = false, muted = false, suppressed = false))
        assertTrue(ChanACL.canOfferMute(ChanACL.WRITE.toLong(), isSelf = false, muted = false, suppressed = false))
        assertFalse(ChanACL.canOfferMute(md.toLong(), isSelf = true, muted = false, suppressed = false))
        assertTrue(ChanACL.canOfferMute(md.toLong(), isSelf = true, muted = false, suppressed = true))
        assertTrue(ChanACL.canOfferMute(md.toLong(), isSelf = true, muted = true, suppressed = false))
        assertTrue(ChanACL.canOfferMute(md.toLong(), isSelf = true, muted = true, suppressed = true))
    }

    @Test
    fun selfRegister_requiresSelfRegisterOrWrite() {
        assertFalse(ChanACL.canSelfRegister(0))
        assertFalse(ChanACL.canSelfRegister(ChanACL.REGISTER.toLong()))
        assertTrue(ChanACL.canSelfRegister(ChanACL.SELF_REGISTER.toLong()))
        assertTrue(ChanACL.canSelfRegister(ChanACL.WRITE.toLong()))
    }

    @Test
    fun registerOthers_requiresRegisterOrWrite() {
        assertFalse(ChanACL.canRegisterOthers(0))
        assertFalse(ChanACL.canRegisterOthers(ChanACL.SELF_REGISTER.toLong()))
        assertTrue(ChanACL.canRegisterOthers(ChanACL.REGISTER.toLong()))
        assertTrue(ChanACL.canRegisterOthers(ChanACL.WRITE.toLong()))
    }

    @Test
    fun offerRegister_matchesDesktopMenu() {
        val selfReg = ChanACL.SELF_REGISTER
        val adminReg = ChanACL.REGISTER
        assertTrue(
            ChanACL.canOfferRegister(
                selfReg.toLong(), isSelf = true, isRegistered = false, hasCertificate = true,
            )
        )
        assertFalse(
            ChanACL.canOfferRegister(
                selfReg.toLong(), isSelf = false, isRegistered = false, hasCertificate = true,
            )
        )
        assertTrue(
            ChanACL.canOfferRegister(
                adminReg.toLong(), isSelf = false, isRegistered = false, hasCertificate = true,
            )
        )
        assertFalse(
            ChanACL.canOfferRegister(
                adminReg.toLong(), isSelf = true, isRegistered = false, hasCertificate = true,
            )
        )
        assertFalse(
            ChanACL.canOfferRegister(
                selfReg.toLong(), isSelf = true, isRegistered = true, hasCertificate = true,
            )
        )
        assertFalse(
            ChanACL.canOfferRegister(
                selfReg.toLong(), isSelf = true, isRegistered = false, hasCertificate = false,
            )
        )
    }

    @Test
    fun textMessage_requiresWriteOrTextMessage() {
        assertEquals(0x200, ChanACL.TEXT_MESSAGE)
        assertFalse(ChanACL.canTextMessage(0))
        assertFalse(ChanACL.canTextMessage(ChanACL.ENTER.toLong()))
        assertTrue(ChanACL.canTextMessage(ChanACL.TEXT_MESSAGE.toLong()))
        assertTrue(ChanACL.canTextMessage(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canTextMessage((ChanACL.WRITE or ChanACL.TEXT_MESSAGE).toLong()))
    }

    @Test
    fun listen_requiresWriteOrListen() {
        assertEquals(0x800, ChanACL.LISTEN)
        assertFalse(ChanACL.canListen(0))
        assertFalse(ChanACL.canListen(ChanACL.TEXT_MESSAGE.toLong()))
        assertTrue(ChanACL.canListen(ChanACL.LISTEN.toLong()))
        assertTrue(ChanACL.canListen(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canListen((ChanACL.WRITE or ChanACL.LISTEN).toLong()))
    }

    @Test
    fun linkChannel_requiresWriteOrLinkChannel() {
        assertEquals(0x80, ChanACL.LINK_CHANNEL)
        assertFalse(ChanACL.canLinkChannel(0))
        assertFalse(ChanACL.canLinkChannel(ChanACL.LISTEN.toLong()))
        assertTrue(ChanACL.canLinkChannel(ChanACL.LINK_CHANNEL.toLong()))
        assertTrue(ChanACL.canLinkChannel(ChanACL.WRITE.toLong()))
    }

    @Test
    fun addChannel_requiresWriteMakeOrTemp() {
        assertEquals(0x40, ChanACL.MAKE_CHANNEL)
        assertEquals(0x400, ChanACL.MAKE_TEMP_CHANNEL)
        assertFalse(ChanACL.canAddChannel(0))
        assertFalse(ChanACL.canAddChannel(ChanACL.ENTER.toLong()))
        assertTrue(ChanACL.canAddChannel(ChanACL.MAKE_CHANNEL.toLong()))
        assertTrue(ChanACL.canAddChannel(ChanACL.MAKE_TEMP_CHANNEL.toLong()))
        assertTrue(ChanACL.canAddChannel(ChanACL.WRITE.toLong()))
        assertFalse(ChanACL.canMakePermanentChannel(ChanACL.MAKE_TEMP_CHANNEL.toLong()))
        assertTrue(ChanACL.canMakePermanentChannel(ChanACL.MAKE_CHANNEL.toLong()))
        assertTrue(ChanACL.canMakePermanentChannel(ChanACL.WRITE.toLong()))
        assertFalse(ChanACL.canWrite(0))
        assertTrue(ChanACL.canWrite(ChanACL.WRITE.toLong()))
    }

    @Test
    fun officialPermissionBits_matchDesktopAclH() {
        assertEquals(0x0, ChanACL.NONE)
        assertEquals(0x1, ChanACL.WRITE)
        assertEquals(0x2, ChanACL.TRAVERSE)
        assertEquals(0x4, ChanACL.ENTER)
        assertEquals(0x8, ChanACL.SPEAK)
        assertEquals(0x10, ChanACL.MUTE_DEAFEN)
        assertEquals(0x20, ChanACL.MOVE)
        assertEquals(0x40, ChanACL.MAKE_CHANNEL)
        assertEquals(0x80, ChanACL.LINK_CHANNEL)
        assertEquals(0x100, ChanACL.WHISPER)
        assertEquals(0x200, ChanACL.TEXT_MESSAGE)
        assertEquals(0x400, ChanACL.MAKE_TEMP_CHANNEL)
        assertEquals(0x800, ChanACL.LISTEN)
        assertEquals(0x10000, ChanACL.KICK)
        assertEquals(0x20000, ChanACL.BAN)
        assertEquals(0x40000, ChanACL.REGISTER)
        assertEquals(0x80000, ChanACL.SELF_REGISTER)
        assertEquals(0x100000, ChanACL.RESET_USER_CONTENT)
        assertEquals(0x8000000, ChanACL.CACHED)
        assertEquals(
            ChanACL.WRITE or ChanACL.TRAVERSE or ChanACL.ENTER or ChanACL.SPEAK or
                ChanACL.MUTE_DEAFEN or ChanACL.MOVE or ChanACL.MAKE_CHANNEL or
                ChanACL.LINK_CHANNEL or ChanACL.WHISPER or ChanACL.TEXT_MESSAGE or
                ChanACL.MAKE_TEMP_CHANNEL or ChanACL.LISTEN or ChanACL.KICK or
                ChanACL.BAN or ChanACL.REGISTER or ChanACL.SELF_REGISTER or
                ChanACL.RESET_USER_CONTENT,
            ChanACL.ALL,
        )
        assertEquals(
            ChanACL.TRAVERSE or ChanACL.ENTER or ChanACL.SPEAK or ChanACL.WHISPER or
                ChanACL.TEXT_MESSAGE or ChanACL.LISTEN,
            ChanACL.DEFAULT,
        )
        assertFalse(ChanACL.has(ChanACL.SUPERUSER_EFFECTIVE.toLong(), ChanACL.SPEAK))
        assertFalse(ChanACL.has(ChanACL.SUPERUSER_EFFECTIVE.toLong(), ChanACL.WHISPER))
        assertTrue(ChanACL.has(ChanACL.SUPERUSER_EFFECTIVE.toLong(), ChanACL.WRITE))
    }

    @Test
    fun specialIdsAndGroups_matchDesktop() {
        assertEquals(-1, ChanACL.UserId.UNREGISTERED)
        assertEquals(0, ChanACL.UserId.SUPERUSER)
        assertEquals(0, ChanACL.ChannelId.ROOT)
        assertEquals(
            listOf("none", "all", "auth", "strong", "in", "out", "sub"),
            ChanACL.Group.META,
        )
        assertEquals('#', ChanACL.Group.ACCESS_TOKEN)
        assertEquals(1, ChanACL.DenyType.PERMISSION)
        assertEquals(13, ChanACL.DenyType.USER_LISTENER_LIMIT)
    }

    @Test
    fun speakAndWhisper_areNotImpliedByWrite() {
        assertFalse(ChanACL.canSpeak(ChanACL.WRITE.toLong()))
        assertFalse(ChanACL.canWhisper(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canSpeak(ChanACL.SPEAK.toLong()))
        assertTrue(ChanACL.canWhisper(ChanACL.WHISPER.toLong()))
        assertTrue(ChanACL.canTraverse(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canEnter(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canResetUserContent(ChanACL.WRITE.toLong()))
        assertTrue(ChanACL.canResetUserContent(ChanACL.RESET_USER_CONTENT.toLong()))
    }

    @Test
    fun joinAndEditAcl_matchDesktopMenus() {
        assertTrue(ChanACL.canJoinChannel(ChanACL.ENTER.toLong()))
        assertTrue(ChanACL.canJoinChannel(ChanACL.WRITE.toLong()))
        assertFalse(ChanACL.canJoinChannel(ChanACL.SPEAK.toLong()))
        assertTrue(ChanACL.canEditAcl(ChanACL.WRITE.toLong(), 0))
        assertTrue(ChanACL.canEditAcl(0, ChanACL.WRITE.toLong()))
        assertFalse(ChanACL.canEditAcl(ChanACL.ENTER.toLong(), ChanACL.REGISTER.toLong()))
    }

    @Test
    fun viewUserInfo_matchesDesktopMenu() {
        assertTrue(ChanACL.canViewUserInfo(0, 0, isSelf = true))
        assertTrue(ChanACL.canViewUserInfo(ChanACL.REGISTER.toLong(), 0, isSelf = false))
        assertTrue(ChanACL.canViewUserInfo(ChanACL.WRITE.toLong(), 0, isSelf = false))
        assertTrue(ChanACL.canViewUserInfo(0, ChanACL.ENTER.toLong(), isSelf = false))
        assertFalse(ChanACL.canViewUserInfo(0, ChanACL.SPEAK.toLong(), isSelf = false))
    }

    @Test
    fun fromWire_matchesOfficialUnsignedIntCast() {
        assertEquals(ChanACL.WRITE.toLong(), ChanACL.fromWire(ChanACL.WRITE.toLong()))
        assertEquals(0L, ChanACL.fromWire(1L shl 32))
        assertEquals(1L, ChanACL.fromWire((1L shl 32) or 1L))
        assertFalse(ChanACL.has(ChanACL.fromWire(1L shl 32), 1 shl 31))
        val bit31 = Integer.MIN_VALUE
        assertEquals(0x80000000L, ChanACL.fromProtoUInt32(bit31))
        assertTrue(ChanACL.has(ChanACL.fromProtoUInt32(bit31), bit31))
        assertEquals(bit31, ChanACL.toProtoUInt32(0x80000000L))
    }
}
