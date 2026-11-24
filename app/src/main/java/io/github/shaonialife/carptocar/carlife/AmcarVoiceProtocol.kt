package io.github.shaonialife.carptocar.carlife

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CarLife VR 通道（9242）的报文常量与麦克风帧封装，按 AMcar 实现移植。
 *
 * 9242 双向：MD→HU 的 `0x5000x` 是助手回答 PCM / 语音状态，HU→MD 的 `0x58001`
 * 是车机麦克风 PCM。下行始终读取；上行由默认关闭的实验开关控制。
 * 帧格式统一为 12B 大端头 `length | timestamp | serviceType` + payload。
 */
internal object AmcarVoiceProtocol {
    const val MIC_WAKEUP = 0x10022
    const val MIC_END = 0x10023
    const val MIC_RECOGNITION = 0x10024
    const val MIC_PREPARE = 0x10071
    const val MIC_PREPARED = 0x18072
    const val MIC_DATA = 0x58001

    const val VOICE_INIT = 0x50002
    const val VOICE_DATA = 0x50003
    const val VOICE_STOP = 0x50004
    const val VOICE_STATE = 0x50005
    const val VOICE_INTERRUPT = 0x50006
    const val VOICE_FEED_END = 0x50008

    /** HU → MD：一帧 16kHz mono PCM16LE 的车机麦克风数据。 */
    fun microphone(
        pcm: ByteArray,
        timestamp: Int,
    ): ByteArray {
        require(pcm.isNotEmpty() && pcm.size <= 8192 && pcm.size % 2 == 0)
        return ByteBuffer
            .allocate(12 + pcm.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(pcm.size)
            .putInt(timestamp)
            .putInt(MIC_DATA)
            .put(pcm)
            .array()
    }
}
