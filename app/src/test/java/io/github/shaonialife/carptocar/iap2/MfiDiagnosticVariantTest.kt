package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断版 MFi 封装变体单测（2026-11-21）。
 *
 * 标准 param(0, challenge) 三档全失败后，诊断扫描会逐一试探其它封装，
 * 把车机对每种的反应记进日志。这些测试保证变体结构与十六进制摘要正确，
 * 避免采集到错误的证据。
 */
class MfiDiagnosticVariantTest {
    private val challenge20 = ByteArray(20) { (it + 1).toByte() }

    @Test
    fun param0StdMatchesIap2CsmParam() {
        // 标准变体必须和主流程用同一个 param(0, ...) 封装，否则对照失去意义
        val body = Iap2SenderSession.buildDiagChallenge(
            Iap2SenderSession.DiagChallengeVariant.PARAM0_STD,
            challenge20,
        )
        assertEquals(Iap2Csm.param(0, challenge20).toList(), body.toList())
        assertEquals(24, body.size) // 4B 参数头 + 20B challenge
        assertEquals(0, (body[2].toInt() shl 8) or (body[3].toInt() and 0xFF)) // param id = 0
    }

    @Test
    fun rawVariantHasNoParamHeader() {
        val body = Iap2SenderSession.buildDiagChallenge(
            Iap2SenderSession.DiagChallengeVariant.RAW,
            challenge20,
        )
        assertEquals(challenge20.toList(), body.toList())
        assertEquals(20, body.size)
    }

    @Test
    fun param1VariantUsesIdOne() {
        val body = Iap2SenderSession.buildDiagChallenge(
            Iap2SenderSession.DiagChallengeVariant.PARAM1,
            challenge20,
        )
        assertEquals(Iap2Csm.param(1, challenge20).toList(), body.toList())
        assertEquals(1, (body[2].toInt() shl 8) or (body[3].toInt() and 0xFF)) // param id = 1
    }

    @Test
    fun zeroNonceVariantKeepsLengthButZeroesPayload() {
        val body = Iap2SenderSession.buildDiagChallenge(
            Iap2SenderSession.DiagChallengeVariant.PARAM0_ZERO,
            challenge20,
        )
        assertEquals(24, body.size)
        assertTrue(body.copyOfRange(4, 24).all { it == 0.toByte() })
    }

    @Test
    fun eightByteVariantIsTwelveBytes() {
        val body = Iap2SenderSession.buildDiagChallenge(
            Iap2SenderSession.DiagChallengeVariant.PARAM0_8B,
            challenge20,
        )
        assertEquals(12, body.size) // 4B 头 + 8B
    }

    @Test
    fun allFiveVariantsAreDistinct() {
        val bodies = Iap2SenderSession.DiagChallengeVariant.values().map {
            Iap2SenderSession.buildDiagChallenge(it, challenge20).toList()
        }
        assertEquals(5, bodies.size)
        assertEquals(5, bodies.distinct().size)
    }

    @Test
    fun hexOfFormatsAndTruncates() {
        assertEquals("01 02 ff", Iap2SenderSession.hexOf(byteArrayOf(1, 2, -1)))
        val long = ByteArray(100) { 0 }
        val text = Iap2SenderSession.hexOf(long, limit = 4)
        assertEquals("00 00 00 00 …(100B)", text)
    }
}
