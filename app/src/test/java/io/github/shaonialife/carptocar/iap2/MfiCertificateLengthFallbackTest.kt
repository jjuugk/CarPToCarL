package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MfiCertificateLength.fallbackByParamBytes] 的边界（hypercar `MfiChallenge.kt` 规则）。
 *
 * 阈值比较的是**参数总长**（证书 DER + 4 字节 TLV 头）：> 644 → 20B，否则 32B。
 * 644 本身落在「否则」一侧——这条边界最容易写错成 `>=`，所以单列出来钉住。
 */
class MfiCertificateLengthFallbackTest {
    @Test
    fun `参数总长恰好 644 仍按 32B`() {
        // 640 + 4 = 644，不大于阈值
        assertEquals(32, MfiCertificateLength.fallbackByParamBytes(640))
    }

    @Test
    fun `参数总长 645 起按 20B`() {
        // 641 + 4 = 645
        assertEquals(20, MfiCertificateLength.fallbackByParamBytes(641))
    }

    @Test
    fun `空证书按 32B`() {
        assertEquals(32, MfiCertificateLength.fallbackByParamBytes(0))
    }

    @Test
    fun `大证书按 20B`() {
        assertEquals(20, MfiCertificateLength.fallbackByParamBytes(1_500))
    }

    @Test
    fun `参数头长度与 Iap2Csm-param 一致`() {
        // param() = len(u16) + id(u16) + value，头 4 字节。改这个常量等于改阈值语义。
        assertEquals(4, MfiCertificateLength.PARAM_HEADER_BYTES)
        assertEquals(644, MfiCertificateLength.CERTIFICATE_PARAM_THRESHOLD_BYTES)
    }

    /**
     * 兜底结果必须能被 [MfiCertificateLength.prefer] 当成「队首首选 + 原顺序回退」处理。
     *
     * 它不在基线里时不能插队——基线是实车迭代与记忆的产物，不能被粗判推翻。
     */
    @Test
    fun `兜底长度只插队首且保留原顺序`() {
        val baseline = listOf(32, 20, 16)
        assertEquals(listOf(20, 32, 16), MfiCertificateLength.prefer(baseline, 20))
        // 首选不在队首时把它提到最前，其余保持原相对顺序
        assertEquals(listOf(16, 20, 32), MfiCertificateLength.prefer(listOf(20, 32, 16), 16))
        // 识别结果为 null / 不在基线里时，基线原样不动
        assertEquals(baseline, MfiCertificateLength.prefer(baseline, null))
        assertEquals(baseline, MfiCertificateLength.prefer(baseline, 99))
    }
}
