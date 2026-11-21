package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音控制（0x10066）的报文约定（1.2.78 起，替代 `0x68008 0x21`）。
 *
 * 两个字段都是 `required int32`（dex 里 `CarlifeVoiceControlRequest{command=1, opt=2}`），
 * 手写 protobuf 时**一个都不能省**：漏掉 opt 会让手机端强类型解析直接丢掉整条消息，
 * 表现又是「命令已送达、引擎毫无反应」——和当初硬键那条死路一模一样。
 */
class CarLifeProtocolVoiceControlTest {
    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `消息号就是 0x10066`() {
        // CommonParams.MSG_CMD_HU_VOICE_CONTROL = 0x00010066；写成十进制便于与日志对账
        assertEquals(0x10066, CarLifeProtocol.HU_VOICE_CONTROL)
        assertEquals(65638, CarLifeProtocol.HU_VOICE_CONTROL)
    }

    @Test
    fun `起语音载荷两个字段都写出来`() {
        // field1(varint)=1 → 08 01；field2(varint)=2 → 10 02
        assertArrayEquals(
            hex("0801 1002"),
            CarLifeProtocol.voiceControlRequest(
                CarLifeProtocol.VoiceControl.START_COMMAND,
                CarLifeProtocol.VoiceControl.START_OPT,
            ),
        )
    }

    @Test
    fun `字段值超过 127 时要写多字节 varint`() {
        // 实车试不同 command/opt 组合时很容易越过 127：200 → C8 01，不能截成单字节
        assertArrayEquals(
            hex("08C801 1002"),
            CarLifeProtocol.voiceControlRequest(200, 2),
        )
    }
}
