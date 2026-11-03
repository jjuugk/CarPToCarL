package io.github.shaonialife.carptocar.mib3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * viwi 报文的编解码。
 *
 * 这层是「猜出来的协议」的第一道闸：封帧一旦有错（少个换行、转义破掉 JSON），
 * 车机那头只会沉默，而沉默和「车机不支持」在日志里长得一模一样。所以这里把
 * 编码的每个字节、切行的每个边界都钉死。
 */
class Mib3ViwiTest {
    @Test
    fun `GET 请求编码成一行 JSON 加换行`() {
        val encoded = Mib3Viwi.Request(7, "GET", "/hvac").encode().decodeToString()
        assertEquals("{\"id\":7,\"method\":\"GET\",\"path\":\"/hvac\"}\n", encoded)
    }

    @Test
    fun `带体的请求把 body 原样嵌入`() {
        val line = Mib3Viwi.Request(1, "POST", "/door", "{\"locked\":true}").toJsonLine()
        assertEquals("{\"id\":1,\"method\":\"POST\",\"path\":\"/door\",\"body\":{\"locked\":true}}", line)
    }

    @Test
    fun `字符串转义不会破坏封帧`() {
        assertEquals("a\\\"b", Mib3Viwi.escape("a\"b"))
        assertEquals("a\\\\b", Mib3Viwi.escape("a\\b"))
        assertEquals("a\\nb", Mib3Viwi.escape("a\nb"))
        // 换行若原样输出，一条请求会被切成两行 → 车机按行解析必然错位
        val line = Mib3Viwi.Request(1, "GET", "/a\nb").toJsonLine()
        assertTrue(line.none { it == '\n' })
    }

    @Test
    fun `响应行能取出 id 与 status`() {
        val response = Mib3Viwi.parseLine("""  {"id":12,"status":200,"body":{"temperature":21.5}}  """)
        assertEquals(12, response.id)
        assertEquals(200, response.status)
        assertTrue(response.text.contains("\"temperature\"") )
        assertEquals("id=12 status=200", response.summary)
    }

    @Test
    fun `解析不出字段时不丢原文`() {
        val response = Mib3Viwi.parseLine("ok")
        assertNull(response.id)
        assertNull(response.status)
        assertEquals("ok", response.text)
        assertEquals("id=? status=?", response.summary)
    }

    @Test
    fun `字节流按换行切分并保留半行`() {
        val (lines, rest) = Mib3Viwi.splitLines("{\"id\":1}\n{\"id\":2}\n{\"id".encodeToByteArray())
        assertEquals(listOf("{\"id\":1}", "{\"id\":2}"), lines)
        assertEquals("{\"id", rest.decodeToString())
    }

    @Test
    fun `CRLF 行尾不会带进把正文`() {
        val (lines, _) = Mib3Viwi.splitLines("{\"id\":1}\r\n".encodeToByteArray())
        assertEquals(listOf("{\"id\":1}"), lines)
    }

    @Test
    fun `只取已知语义的键`() {
        val text = """{"vin":"WAUZZZ8V1JA123456","mileage":12345,"locked":true,"unknown":{"a":1}}"""
        val fields = Mib3Viwi.extractFields(text, Mib3Viwi.DISPLAY_KEYS)
        assertEquals("WAUZZZ8V1JA123456", fields["vin"])
        assertEquals("12345", fields["mileage"])
        assertEquals("true", fields["locked"])
        assertTrue("未列出的键不应被提取", "unknown" !in fields)
    }
}

/**
 * 端点清单的静态约束：写错的路径/方法只会在实车上表现为「车机沉默」，
 * 所以把能从代码上钉死的部分先钉死。
 */
class Mib3EndpointsTest {
    private val allowedMethods = setOf("GET", "POST", "PUT", "DELETE")

    @Test
    fun `每个动作的方法与路径都合法`() {
        Mib3Endpoints.ACTIONS.forEach { action ->
            assertTrue("${action.key} 的方法非法：${action.method}", action.method in allowedMethods)
            assertTrue("${action.key} 的路径必须以 / 开头", action.path.startsWith("/"))
        }
    }

    @Test
    fun `写动作的 body 必须是对象字面量`() {
        Mib3Endpoints.ACTIONS.forEach { action ->
            val body = action.body
            assertTrue("${action.key} 缺 body", body != null)
            assertTrue("${action.key} 的 body 不是对象：$body", body!!.startsWith("{") && body.endsWith("}"))
            // `"` 成对出现，避免手写 body 时漏引号
            assertEquals("${action.key} 的 body 引号不成对：$body", 0, body.count { it == '"' } % 2)
        }
    }

    @Test
    fun `只读端点都是 GET 且以斜杠开头`() {
        Mib3Endpoints.READ_ONLY.forEach { (label, path) ->
            assertTrue("$label 的路径非法：$path", path.startsWith("/"))
        }
        assertTrue("只读清单不应为空", Mib3Endpoints.READ_ONLY.isNotEmpty())
    }

    @Test
    fun `危险动作被标出来`() {
        val dangerKeys = Mib3Endpoints.ACTIONS.filter { it.danger }.map { it.key }.toSet()
        // 解锁与降窗在行驶中误触后果最重，必须走危险样式的确认框
        assertTrue("door_unlock" in dangerKeys)
        assertTrue("window_down" in dangerKeys)
    }

    @Test
    fun `动作键唯一`() {
        val keys = Mib3Endpoints.ACTIONS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
