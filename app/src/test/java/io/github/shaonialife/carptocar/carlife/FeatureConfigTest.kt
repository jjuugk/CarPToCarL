package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 98386 特征配置的开关清单。
 *
 * 这几项是「引擎读不改、但会改变编码行为」的声明：漏一项不等于报错，只是行为悄悄
 * 回落到引擎默认（例如漏 `DISABLE_RESOLUTION_OPTION` 就把 1412×654 压成 1280×720）。
 * 所以清单本身要钉死，靠单测而不是靠日志里数逗号。
 */
class FeatureConfigTest {
    private fun asText(payload: ByteArray) = String(payload, Charsets.ISO_8859_1)

    @Test
    fun featureConfigCarriesContentEncryption() {
        // 参考 OcarPlay 的同一份清单：CONTENT_ENCRYPTION 与 DISABLE_RESOLUTION_OPTION 必须同批下发。
        val payload = CarLifeProtocol.featureConfig(connectType = CarLifeProtocol.CONNECT_TYPE_WIFI)
        val text = asText(payload)
        assertTrue("必须带 CONTENT_ENCRYPTION：$text", text.contains("CONTENT_ENCRYPTION"))
        assertTrue("必须带 DISABLE_RESOLUTION_OPTION：$text", text.contains("DISABLE_RESOLUTION_OPTION"))
        assertTrue("必须带 MEDIA_LYRIC_SUPPORT：$text", text.contains("MEDIA_LYRIC_SUPPORT"))
    }

    @Test
    fun itemCountMatchesPayload() {
        // 首字段是条数（field1 varint）。加了一项却忘了条数，引擎会只读到前 N 项——
        // 表现是「日志说发了 5 项，实际只有前 4 项生效」，最难查。
        val payload = CarLifeProtocol.featureConfig()
        assertEquals("field1 tag", 0x08, payload[0].toInt())
        assertEquals("条数必须与清单长度一致", 5, payload[1].toInt())
    }

    @Test
    fun contentEncryptionCanBeSwitchedOff() {
        val payload = CarLifeProtocol.featureConfig(contentEncryption = false)
        val text = asText(payload)
        assertTrue("关掉时仍要有这一项（值为 0）而不是消失", text.contains("CONTENT_ENCRYPTION"))
    }
}
