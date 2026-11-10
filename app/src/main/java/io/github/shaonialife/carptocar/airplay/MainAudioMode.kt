package io.github.shaonialife.carptocar.airplay

/** MainAudio ownership reported in modesChanged. Entity 1 is the phone, 2 is the car. */
internal data class MainAudioMode(
    val carTookAudio: Boolean = false,
    val carBorrowedAudio: Boolean = false,
) {
    val carOwnsAudio: Boolean get() = carTookAudio || carBorrowedAudio

    fun resource(): Map<String, Long> = mapOf(
        "resourceID" to 2L,
        "entity" to if (carOwnsAudio) 2L else 1L,
        "permanentEntity" to if (carTookAudio) 2L else 1L,
    )

    fun applyRequest(resources: List<*>): MainAudioMode {
        var mode = this
        for (entry in resources) {
            val resource = entry as? Map<*, *> ?: continue
            if ((resource["resourceID"] as? Number)?.toLong() != 2L) continue
            mode = when ((resource["transferType"] as? Number)?.toLong()) {
                1L -> mode.copy(carTookAudio = true) // Take
                2L -> mode.copy(carTookAudio = false) // Untake
                3L -> mode.copy(carBorrowedAudio = true) // Borrow
                4L -> mode.copy(carBorrowedAudio = false) // Unborrow
                else -> mode
            }
        }
        return mode
    }
}
