package io.github.shaonialife.carptocar.audio

/**
 * 车机麦克风「输入流」档位，按 AMcar 在 MIB3 3874 上实车验证的那一路移植。
 *
 * 方向：AirPlay 的 `input=true` 是请车机把它的麦克风推给我们（车机 → 手机），
 * `dataPort` / `controlPort` 必须由我们在 SETUP 里声明。
 * 只挑 `type=100 / audioType=speechRecognition` 的 `audioInputFormats` 位图里的
 * bit6（LPCM 24k mono）；OPUS 位不认，避免引入 MediaCodec 与另一套字节格式。
 */
enum class AmcarMicStreamFormat(
    val bit: Long,
    val rate: Int,
    val framesPerPacket: Int,
) {
    PCM24(1L shl 6, 24_000, 480),
    ;

    fun description(
        connectionId: Long,
        dataPort: Int,
        controlPort: Int,
    ): Map<String, Any> {
        require(connectionId > 0 && dataPort in 1..65535 && controlPort in 1..65535)
        return linkedMapOf(
            "type" to 100L,
            "audioType" to "speechRecognition",
            "input" to true,
            "audioFormat" to bit,
            "streamConnectionID" to connectionId,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "spf" to framesPerPacket.toLong(),
            "framesPerPacket" to framesPerPacket.toLong(),
            "audioLatencyMs" to 100L,
            "supportsHighAccuracyTimestamps" to false,
            "supportsRTPPacketRedundancy" to false,
        )
    }

    companion object {
        private const val AUDIO_TYPE_SPEECH = "speechRecognition"

        /**
         * 只认 `type=100 / speechRecognition` 这一档自己的输入位。
         * `audioType` 大小写不可靠（个别固件写 SpeechRecognition），这里放宽挑选，
         * SETUP 里仍回固定小写值。
         */
        fun select(info: Map<*, *>?): AmcarMicStreamFormat? {
            val mask =
                (info?.get("audioFormats") as? List<*>)
                    ?.filterIsInstance<Map<*, *>>()
                    ?.filter {
                        (it["type"] as? Number)?.toInt() == 100 &&
                            (it["audioType"] as? String)?.equals(AUDIO_TYPE_SPEECH, ignoreCase = true) == true
                    }?.fold(0L) { bits, entry ->
                        bits or ((entry["audioInputFormats"] as? Number)?.toLong() ?: 0L)
                    } ?: 0L
            return entries.firstOrNull { mask and it.bit != 0L }
        }
    }
}
