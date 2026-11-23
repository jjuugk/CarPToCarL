package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeHudSubscriptionTest {
    @Test
    fun confirmedModulesAreStartedOnce() {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val subscription =
            CarLifeHudSubscription { id, payload ->
                sent += id to payload
                true
            }

        subscription.tick(0)
        subscription.receive(byteArrayOf(8, 2, 18, 4, 8, 0, 16, 1, 18, 4, 8, 1, 16, 0))
        subscription.tick(1_000)
        subscription.tick(10_000)

        assertEquals(listOf(0x18043, 0x18045), sent.map { it.first })
        assertArrayEquals(byteArrayOf(8, 2, 18, 4, 8, 0, 16, 1, 18, 4, 8, 1, 16, 1), sent[0].second)
        assertArrayEquals(byteArrayOf(8, 1, 18, 4, 8, 0, 16, 1), sent[1].second)
    }

    @Test
    fun timeoutUsesTestedModuleListButMalformedReplySkipsStart() {
        val sent = mutableListOf<Int>()

        fun subscription() =
            CarLifeHudSubscription { id, _ ->
                sent += id
                true
            }

        val noReply = subscription()
        noReply.tick(0)
        noReply.tick(4_999)
        assertEquals(listOf(0x18043), sent)
        noReply.tick(5_000)
        assertEquals(listOf(0x18043, 0x18045), sent)

        sent.clear()
        val malformed = subscription()
        malformed.tick(0)
        malformed.receive(byteArrayOf(8, 1, 18, 4, 8, 0))
        malformed.tick(5_000)
        assertEquals(listOf(0x18043), sent)
    }

    @Test
    fun q4DisplayUsesRawTurnAndFieldFourAndRetainsArrow() {
        val payload = byteArrayOf(8, 2, 16, 7, 26, 1, 65, 32, 30, 40, 10, 50, 2, 1, 2)
        val turn = checkNotNull(CarLifeHudProtocol.turn(payload))
        assertEquals(7, turn.turn)
        assertEquals(30, turn.distance)
        assertEquals("A", turn.road)
        assertArrayEquals(byteArrayOf(1, 2), turn.icon)
        assertNull(CarLifeHudProtocol.turn(payload.copyOf(payload.size - 1)))
        assertTrue(checkNotNull(CarLifeHudProtocol.turn(byteArrayOf(8, 3))).ended)
    }
}
