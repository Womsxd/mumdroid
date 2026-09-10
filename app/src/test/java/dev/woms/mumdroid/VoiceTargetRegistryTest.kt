package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.VoiceTargetId
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.net.VoiceTargetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTargetRegistryTest {

    private fun registry(capacity: Int = 3) = VoiceTargetRegistry(capacity)

    private fun users(vararg sessions: Int) = VoiceTargetSpec.Users(sessions.toList())

    private fun channel(id: Int) = VoiceTargetSpec.Channel(id)

    private fun VoiceTargetRegistry.Op.Register.targetAsSpec(): VoiceTargetSpec =
        VoiceTargetSpec.Users(target.sessions)

    @Test
    fun firstTarget_getsLowestIdAndRegisters() {
        val r = registry()
        val resolution = r.resolve(users(1))
        assertEquals(VoiceTargetId.MIN, resolution.targetId)
        assertEquals(1, resolution.ops.size)
        val op = resolution.ops[0] as VoiceTargetRegistry.Op.Register
        assertEquals(VoiceTargetId.MIN, op.id)
        assertEquals(listOf(1), op.target.sessions)
        assertNull(op.target.channelId)
    }

    @Test
    fun ids_areAllocatedInAscendingOrder() {
        val r = registry()
        assertEquals(1, r.resolve(users(1)).targetId)
        assertEquals(2, r.resolve(users(2)).targetId)
        assertEquals(3, r.resolve(channel(5)).targetId)
    }

    @Test
    fun sameReceiverSet_isACacheHitWithoutMessages() {
        val r = registry()
        r.resolve(users(1, 2))
        val again = r.resolve(users(1, 2))
        assertEquals(1, again.targetId)
        assertTrue(again.ops.isEmpty())
    }

    @Test
    fun receiverSetEquality_ignoresArgumentOrderInOpsButNotInSpec() {
        // Distinct session lists are distinct targets: murmur stores them
        // separately, so the client must not collapse them.
        val r = registry()
        val a = r.resolve(users(1, 2)).targetId
        val b = r.resolve(users(2, 1)).targetId
        assertTrue(a != b)
    }

    @Test
    fun channelFlags_areDistinctTargets() {
        val r = registry()
        val plain = r.resolve(VoiceTargetSpec.Channel(4)).targetId
        val children = r.resolve(VoiceTargetSpec.Channel(4, children = true)).targetId
        val links = r.resolve(VoiceTargetSpec.Channel(4, links = true)).targetId
        assertEquals(3, setOf(plain, children, links).size)
    }

    @Test
    fun channelGroup_isEncoded() {
        val r = registry()
        val op = r.resolve(VoiceTargetSpec.Channel(4, group = "admin")).ops.single()
            as VoiceTargetRegistry.Op.Register
        assertEquals("admin", op.target.group)
        assertEquals(4, op.target.channelId)
    }

    @Test
    fun emptyTarget_resolvesToNoneAndRegistersNothing() {
        val r = registry()
        val resolution = r.resolve(VoiceTargetSpec.Users(emptyList()))
        assertEquals(VoiceTargetId.NONE, resolution.targetId)
        assertTrue(resolution.ops.isEmpty())
        assertTrue(r.activeIds.isEmpty())
    }

    @Test
    fun fullPool_evictsLeastRecentlyUsedAndClearsBeforeReusing() {
        val r = registry(capacity = 2)
        assertEquals(1, r.resolve(users(1)).targetId)
        assertEquals(2, r.resolve(users(2)).targetId)
        // Touch id 1 so id 2 becomes the least recently used.
        r.resolve(users(1))

        val resolution = r.resolve(users(3))
        // The stale registration must be revoked in the SAME ordered stream
        // that reuses the id (the TCP control channel), never after it.
        assertEquals(2, resolution.ops.size)
        assertEquals(VoiceTargetRegistry.Op.Clear(2), resolution.ops[0])
        val register = resolution.ops[1] as VoiceTargetRegistry.Op.Register
        assertEquals(2, register.id)
        assertEquals(2, resolution.targetId)
    }

    @Test
    fun evictedTarget_isReRegisteredWhenUsedAgain() {
        val r = registry(capacity = 2)
        assertEquals(1, r.resolve(users(1)).targetId)
        assertEquals(2, r.resolve(users(2)).targetId)
        // Full pool: id 1 was least recently used, so users(3) takes it over.
        val third = r.resolve(users(3))
        assertEquals(1, third.targetId)
        assertEquals(VoiceTargetRegistry.Op.Clear(1), third.ops[0])

        // users(1) has to be registered again; the only free slot needs the
        // current occupant (id 2) revoked first, in the same ordered stream.
        val back = r.resolve(users(1))
        assertEquals(
            listOf(VoiceTargetRegistry.Op.Clear(2)),
            back.ops.dropLast(1),
        )
        val register = back.ops.last() as VoiceTargetRegistry.Op.Register
        assertEquals(back.targetId, register.id)
        assertEquals(users(1), register.targetAsSpec())
    }

    @Test
    fun ids_stayInsideTheProtocolRangeAtFullCapacity() {
        val r = VoiceTargetRegistry()
        val ids = (1..VoiceTargetId.MAX).map { r.resolve(users(it)).targetId }
        assertEquals((VoiceTargetId.MIN..VoiceTargetId.MAX).toList(), ids)
        assertTrue(ids.all { it in VoiceTargetId.RANGE })
    }

    @Test
    fun cap_neverOverflowsIntoTheReservedIds() {
        val r = VoiceTargetRegistry()
        (1..VoiceTargetId.MAX).forEach { r.resolve(users(it)) }
        // The pool is full; the next target must recycle an existing slot,
        // never grow into 31 (server loopback) or beyond.
        val next = r.resolve(users(999))
        assertTrue(next.targetId in VoiceTargetId.RANGE)
        assertTrue(next.ops.any { it is VoiceTargetRegistry.Op.Clear })
        assertEquals(VoiceTargetId.MAX, r.activeIds.size)
    }

    @Test
    fun releaseAll_revokesEveryRegistrationInIdOrder() {
        val r = registry()
        r.resolve(users(1))
        r.resolve(users(2))
        val ops = r.releaseAll()
        assertEquals(
            listOf(VoiceTargetRegistry.Op.Clear(1), VoiceTargetRegistry.Op.Clear(2)),
            ops,
        )
        assertTrue(r.activeIds.isEmpty())
        // A released target allocates from the bottom again.
        assertEquals(1, r.resolve(users(9)).targetId)
    }

    @Test
    fun reset_forgetsStateSilently() {
        val r = registry()
        r.resolve(users(1))
        r.reset()
        assertTrue(r.activeIds.isEmpty())
        assertTrue(r.resolve(users(1)).ops.isNotEmpty())
    }

    @Test
    fun forget_returnsTheIdAndMakesTheNextResolveReRegister() {
        val r = registry()
        r.resolve(users(1))
        assertEquals(1, r.forget(users(1)))
        assertEquals(null, r.forget(users(1)))
        val again = r.resolve(users(1))
        assertEquals(1, again.ops.size)
        assertTrue(again.ops[0] is VoiceTargetRegistry.Op.Register)
    }

    @Test
    fun specFor_exposesTheRegisteredSet() {
        val r = registry()
        r.resolve(users(7))
        assertEquals(users(7), r.specFor(1))
        assertNull(r.specFor(2))
    }
}
