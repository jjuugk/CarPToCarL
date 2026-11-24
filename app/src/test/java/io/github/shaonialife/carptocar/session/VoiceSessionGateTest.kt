package io.github.shaonialife.carptocar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionGateTest {
    private data class Resource(val name: String)

    @Test
    fun oneRequestOwnsTheGate() {
        val gate = VoiceSessionGate<Resource>()
        val first = gate.begin()!!
        assertTrue(gate.attach(first, Resource("first")))
        assertNull(gate.begin())
        gate.cancel()
        assertNotNullAfterCancel(gate)
    }

    private fun assertNotNullAfterCancel(gate: VoiceSessionGate<Resource>) {
        val next = gate.begin()
        assertTrue(next != null)
        assertTrue(gate.isCurrent(next!!))
        assertEquals(VoiceSessionGate.Phase.PREPARING, gate.phase)
    }

    @Test
    fun staleTokenCannotForward() {
        val gate = VoiceSessionGate<Resource>()
        val old = gate.begin()!!
        gate.attach(old, Resource("old"))
        gate.markWaiting(old)
        gate.cancel()
        val fresh = gate.begin()!!
        gate.attach(fresh, Resource("fresh"))
        gate.markWaiting(fresh)
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.offerIfCurrent(old) { true })
        assertTrue(gate.offerIfCurrent(fresh) { true })
    }

    @Test
    fun failDoesNotLeakResourceToNextGeneration() {
        val gate = VoiceSessionGate<Resource>()
        val token = gate.begin()!!
        gate.attach(token, Resource("failed"))
        val failed = gate.fail(token)
        assertEquals("failed", failed?.name)
        assertEquals(VoiceSessionGate.Phase.FAILED, gate.phase)
        val next = gate.begin()!!
        assertTrue(gate.attach(next, Resource("ok")))
    }

    @Test
    fun forwardingPublishesOnlyForCurrentToken() {
        val gate = VoiceSessionGate<Resource>()
        val token = gate.begin()!!
        gate.attach(token, Resource("r"))
        gate.markWaiting(token)
        var published = 0
        assertTrue(gate.forwarding(token) { published++ })
        assertTrue(gate.forwarding(token) { published++ })
        assertEquals(2, published)
    }
}
