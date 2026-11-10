package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainAudioModeTest {
    private fun resource(id: Long, transfer: Long) = mapOf("resourceID" to id, "transferType" to transfer)

    @Test
    fun mainAudioTakeAndUntakeChangeOwnershipButScreenRequestsDoNot() {
        val phone = MainAudioMode()
        val car = phone.applyRequest(listOf(resource(2, 1)))
        assertTrue(car.carOwnsAudio)
        assertEquals(2L, car.resource()["permanentEntity"])
        assertEquals(car, car.applyRequest(listOf(resource(1, 2), resource(2, 1))))
        assertEquals(phone, car.applyRequest(listOf(resource(2, 2))))
    }

    @Test
    fun unborrowRestoresThePermanentOwner() {
        val phone = MainAudioMode()
        val borrowed = phone.applyRequest(listOf(resource(2, 3)))
        assertTrue(borrowed.carOwnsAudio)
        assertEquals(2L, borrowed.resource()["entity"])
        assertEquals(1L, borrowed.resource()["permanentEntity"])
        assertFalse(borrowed.applyRequest(listOf(resource(2, 4))).carOwnsAudio)

        val car = phone.applyRequest(listOf(resource(2, 1)))
        assertEquals(car, car.applyRequest(listOf(resource(2, 3), resource(2, 4))))
    }
}
