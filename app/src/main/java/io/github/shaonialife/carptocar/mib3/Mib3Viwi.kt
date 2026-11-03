package io.github.shaonialife.carptocar.mib3

/**
 * MIB3 车机内部 viwi 总线的报文编解码。
 *
 * ## 这条路是什么
 *
 * CarPlay 的 iAP2 协议**没有**任何控制车辆的消息（`vehicleStatus` 在 MIB3 上还是
 * `enabled:false`，方向也只有「车 → 手机」）。车机把 iAP2 的 ExternalAccessoryProtocol
 * 隧道桥接到了内部 viwi 总线的 **127.0.0.1:14751**（明文、`token_enforcement=false`），
 * 该端口的 ACL 档位 `ext_devices` 对 `/hvac`、`/door`、`/window`、`/lighting`、
 * `/seating` 等 27 个端点开放 POST/PUT/DELETE。手机侧的入口就是 EA 会话
 * （0xEA00 建、0xEA03 状态、0xEA04 数据），详见 `docs/mib3-viwi-control.md`。
 *
 * ## 报文格式是**推断的**
 *
 * 固件里能读到的是「viwi 是 RESTful 风格：方法 + 路径 + JSON body」，但**具体的
 * 封帧方式没有以可读形式出现**（RUDI 资源定义是编译产物）。这里采用最朴素的一种：
 *
 * ```
 * {"id":7,"method":"GET","path":"/hvac"}\n
 * {"id":7,"method":"POST","path":"/door","body":{"locked":true}}\n
 * ```
 *
 * 每行一条、`\n` 分隔。**这是全项目唯一一处「猜的协议」**，所以：
 *  - 编码集中在本文件，实车抓包后只改这里；
 *  - 收到的任何一行都原样保留（[Response.text]）并写进日志，解析失败也不丢；
 *  - 面板上明确标注「未验证」，字段名与形态都可能要改。
 *
 * 只做纯 Kotlin 字符串处理（不依赖 `org.json`），这样单元测试能在纯 JVM 里跑。
 */
object Mib3Viwi {
    /** 奥迪 EA 协议名（`0x1D01` 里声明的那两个，数据口优先 eadata0）。 */
    const val PROTOCOL_DATA_0 = "com.audi.cpp.eadata0"
    const val PROTOCOL_DATA_1 = "com.audi.cpp.eadata1"

    /** 一条 viwi 请求。[body] 是已经序列化好的 JSON 文本（null = 不带体）。 */
    data class Request(
        val id: Int,
        val method: String,
        val path: String,
        val body: String? = null,
    ) {
        /** 序列化成一行 JSON（含行尾 `\n`），直接喂给 EA 隧道。 */
        fun encode(): ByteArray = (toJsonLine() + "\n").encodeToByteArray()

        fun toJsonLine(): String =
            buildString {
                append("{\"id\":").append(id)
                append(",\"method\":\"").append(escape(method)).append('"')
                append(",\"path\":\"").append(escape(path)).append('"')
                if (body != null) append(",\"body\":").append(body)
                append('}')
            }
    }

    /** 车机回的一行。字段解析不出来时 [id]/[status] 为 null，[text] 仍然保留原文。 */
    data class Response(
        val id: Int?,
        val status: Int?,
        val text: String,
    ) {
        /** 面板/日志里的一行摘要。 */
        val summary: String
            get() {
                val code = status?.toString() ?: "?"
                return "id=${id ?: "?"} status=$code"
            }
    }

    /** JSON 字符串转义（只处理会破坏封帧的字符，够用且不引依赖）。 */
    fun escape(value: String): String =
        buildString(value.length + 8) {
            value.forEach { ch ->
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
        }

    /**
     * 把一条响应行解析成 [Response]。
     *
     * 只取两个自用字段（`id` 用于配对请求、`status` 用于界面上判断成败），
     * 其余一律留在 [Response.text] 里——viwi 的具体 schema 未知，过早解释没有意义。
     */
    fun parseLine(line: String): Response {
        val trimmed = line.trim()
        return Response(
            id = ID_FIELD.find(trimmed)?.groupValues?.get(1)?.toIntOrNull(),
            status = STATUS_FIELD.find(trimmed)?.groupValues?.get(1)?.toIntOrNull(),
            text = trimmed,
        )
    }

    /**
     * 从原始文本里挑出「已知语义」的字段值，用于只读区展示。
     *
     * 这些键名是从报告与常见服务名里挑的**候选**，命中就显示、没命中就算了——
     * 不猜结构、不做容错解析，避免把猜出来的东西当成读数。
     */
    fun extractFields(
        text: String,
        keys: List<String>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        keys.forEach { key ->
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\"[^\"]*\"|-?[0-9]+(?:\\.[0-9]+)?|true|false)")
            val raw = regex.find(text)?.groupValues?.get(1) ?: return@forEach
            out[key] = raw.removeSurrounding("\"")
        }
        return out
    }

    /**
     * 按 `\n` 切行：返回 (完整行, 剩余半行)。
     *
     * EA 隧道是字节流，一条 JSON 可能跨多个 0xEA04 帧到齐，也可能一帧里塞多条。
     */
    fun splitLines(buffer: ByteArray): Pair<List<String>, ByteArray> {
        val lines = ArrayList<String>()
        var start = 0
        for (index in buffer.indices) {
            if (buffer[index] == '\n'.code.toByte()) {
                lines += buffer.decodeToString(start, index).trimEnd('\r')
                start = index + 1
            }
        }
        return lines to buffer.copyOfRange(start, buffer.size)
    }

    /** 只读区展示用的候选键（命中即显示；没命中就不占位置）。 */
    val DISPLAY_KEYS: List<String> =
        listOf(
            "vin", "modelName", "model", "brand",
            "mileage", "odometer", "totalDistance",
            "range", "remainingRange", "electricRange",
            "speed", "gear", "gearPosition",
            "locked", "lockState", "open",
            "currentTemperature", "targetTemperature", "temperature",
            "serviceDistance", "serviceDue", "nextService",
        )

    /** 响应行里的 `"id":N` 与 `"status":N`（viwi 用这两个字段配对请求）。 */
    private val ID_FIELD = Regex("\"id\"\\s*:\\s*(-?[0-9]+)")
    private val STATUS_FIELD = Regex("\"status\"\\s*:\\s*(-?[0-9]+)")
}
