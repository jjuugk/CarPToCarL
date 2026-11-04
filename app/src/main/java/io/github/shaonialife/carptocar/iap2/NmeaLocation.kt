package io.github.shaonialife.carptocar.iap2

/**
 * iAP2 `0xFFFB LocationInformation` 的 NMEA 解析。
 *
 * ## 报文长什么样
 *
 * 车机每秒推一条 0xFFFB，报文体是**一条 CSM 参数**（id=0，value 是 ASCII 文本，
 * 末尾用 NUL 补齐）。实测两种形态（见 docs/PROTOCOL-NOTES.md §12 与
 * `.tmp/log1623.txt`）：
 *
 * ```
 * 43B  $PASCD,1583.410,C,D,0,1,0.00,0.000*4B      ← 奥迪 4580（PASCD，无经纬度）
 * 201B $GPGGA,125944.64,3014.842036,N,...*46      ← 别的车（标准 NMEA，有经纬度）
 * ```
 *
 * 一条报文里可能塞了**多条**句子（DiPlay 的实现说明：每条 LocationInformation 携带
 * 「自上次以来的所有样本」），所以这里按 `$` 切分后逐条解析，再由 [NmeaAssembler]
 * 合并（GGA 给经纬度/海拔/星数、RMC 给速度/航向、GSA 给 PDOP/VDOP）。
 *
 * ## 为什么 PASCD 只当「无定位」
 *
 * `$PASCD` 是 Apple 的私有语句，字段含义没有公开文档（DiPlay 在 BYD 文档里也写明
 * 「what C and 0 stand for is not public」）。我们实测到的形态里根本没有 ddmm.mmmm
 * 形态的坐标字段，硬猜只会把 0,0 或错误坐标喂给导航。所以：识别它、把「车机发了
 * 定位样本但没有经纬度」这件事如实报上去，由上层决定要不要提示用户。
 */
object NmeaLocation {
    /** 解析出来的一条定位快照；字段为 null 表示「这句没给」。 */
    data class Fix(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeMeters: Double? = null,
        val speedKmh: Double? = null,
        val headingDeg: Double? = null,
        /** NMEA fix quality：0=无效、1=GPS、2=DGPS、4=RTK 固定…（PASCD 恒为 0）。 */
        val fixQuality: Int = 0,
        val satellitesUsed: Int = 0,
        val satellitesVisible: Int = 0,
        val hdop: Double? = null,
        val pdop: Double? = null,
        val vdop: Double? = null,
        val utcYear: Int = 0,
        val utcMonth: Int = 0,
        val utcDay: Int = 0,
        val utcHour: Int = 0,
        val utcMinute: Int = 0,
        val utcSecond: Int = 0,
        /** 这句是什么（`GGA`/`RMC`/`GSA`/`PASCD`），只用于日志。 */
        val source: String = "",
    ) {
        /** 有经纬度且 fix 有效，才值得发给 CarLife。 */
        val hasPosition: Boolean
            get() = latitude != null && longitude != null && fixQuality > 0
    }

    /** 语料里出现过的语句类型（其余一律不认，避免误解析）。 */
    private val KNOWN = setOf("GGA", "RMC", "GSA", "PASCD")

    /**
     * 从 0xFFFB 的 CSM 报文体里抽出 NMEA 句子。
     *
     * 报文体是 `[len u16][id u16][value]`：value 里的 ASCII 用 NUL 补齐。这里不解 CSM
     * （那是 [Iap2Csm] 的活），只做「把可打印字符捞出来、按 `$` 切开」这一件事——
     * 车机偶尔会把两条句子塞进同一个参数，所以不能假设一条报文只有一句。
     *
     * 不做校验和过滤：校验和错误在 [parseSentence] 里判，那里能拿到完整句子。
     */
    fun splitSentences(body: ByteArray): List<String> {
        if (body.isEmpty()) return emptyList()
        val text =
            buildString(body.size) {
                for (b in body) {
                    val v = b.toInt() and 0xFF
                    // NUL 作为分隔符（车机用它补齐），其余不可打印字符直接丢弃。
                    append(if (v == 0) ';' else if (v in 0x20..0x7E) v.toChar() else ';')
                }
            }
        if (!text.contains('$')) return emptyList()
        return text
            .split('$')
            .drop(1)
            .map { "$" + it.trim().trimEnd(';').trim() }
            .filter { it.length > 6 }
    }

    /**
     * 解析一条 NMEA 句子；不认识 / 校验和不对 / 字段不足都返回 null。
     *
     * 校验和按 NMEA 0183：`$` 与 `*` 之间所有字符 XOR。校验和缺失时**放行**
     * （部分车机不发 `*HH`），校验和存在但不对时**丢弃**（半条短信道会拼出错误坐标）。
     */
    fun parseSentence(sentence: String): Fix? {
        if (!sentence.startsWith("$")) return null
        val star = sentence.lastIndexOf('*')
        val bodyEnd = if (star > 0) star else sentence.length
        val payload = sentence.substring(1, bodyEnd)
        if (star > 0 && star + 3 <= sentence.length) {
            val expected = sentence.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
            var actual = 0
            for (c in payload) actual = actual xor c.code
            if (actual != expected) return null
        }
        val fields = payload.split(',')
        if (fields.isEmpty()) return null
        // 头 5 字符：talker(2) + type(3)，兼容 GP/GN/BD 等前缀。
        // `$PASCD` 是 Apple 私有语句（P + ASCD 四字符助记符），不能按「取末 3 位」判型，
        // 否则会得到 `SCD` 而永远匹配不上。
        val head = fields[0]
        if (head.length < 5) return null
        val type =
            if (head.equals("PASCD", ignoreCase = true)) {
                "PASCD"
            } else {
                head.takeLast(3).uppercase()
            }
        if (type !in KNOWN) return null
        return try {
            when (type) {
                "GGA" -> parseGga(fields)
                "RMC" -> parseRmc(fields)
                "GSA" -> parseGsa(fields)
                else -> parsePascd(fields)
            }
        } catch (_: RuntimeException) {
            // 截断 / 畸形句：绝不把异常抛给链路线程（会拆掉整条 iAP2 控制会话）
            null
        }
    }

    private fun parseGga(f: List<String>): Fix? {
        if (f.size < 10) return null
        val time = parseUtcTime(f[1])
        val lat = parseCoordinate(f[2], f[3], degreeDigits = 2)
        val lon = parseCoordinate(f[4], f[5], degreeDigits = 3)
        return Fix(
            latitude = lat,
            longitude = lon,
            altitudeMeters = f[9].toDoubleOrNull(),
            fixQuality = f[6].toIntOrNull() ?: 0,
            satellitesUsed = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull(),
            utcHour = time?.first ?: 0,
            utcMinute = time?.second ?: 0,
            utcSecond = time?.third ?: 0,
            source = "GGA",
        )
    }

    private fun parseRmc(f: List<String>): Fix? {
        if (f.size < 9) return null
        val time = parseUtcTime(f[1])
        val status = f[2].uppercase()
        val lat = parseCoordinate(f[3], f[4], degreeDigits = 2)
        val lon = parseCoordinate(f[5], f[6], degreeDigits = 3)
        val knots = f[7].toDoubleOrNull()
        val date = parseUtcDate(f.getOrNull(9))
        return Fix(
            latitude = lat,
            longitude = lon,
            // RMC 的 status 就是 fix 有效性（A=有效、V=无效）；有坐标时按 GPS fix=1 记。
            fixQuality = if (status == "A" && lat != null && lon != null) 1 else 0,
            speedKmh = knots?.let { it * 1.852 },
            headingDeg = f[8].toDoubleOrNull(),
            utcYear = date?.first ?: 0,
            utcMonth = date?.second ?: 0,
            utcDay = date?.third ?: 0,
            utcHour = time?.first ?: 0,
            utcMinute = time?.second ?: 0,
            utcSecond = time?.third ?: 0,
            source = "RMC",
        )
    }

    private fun parseGsa(f: List<String>): Fix? {
        // `$GPGSA,A,3,01,…,12,PDOP,HDOP,VDOP`：0=头 1=模式 2=fixType 3..14=卫星号
        // 15/16/17=DOP。**不能写死下标**：车机常把未用的卫星槽整段省略（字段数 < 18），
        // 但 PDOP/HDOP/VDOP 永远在最后三个位置，按「倒数三个」取才稳。
        if (f.size < 6) return null
        val fixType = f.getOrNull(2)?.toIntOrNull() ?: 0
        val used = if (f.size > 5) f.subList(3, f.size - 3).count { it.isNotBlank() } else 0
        return Fix(
            fixQuality = if (fixType >= 2) 1 else 0,
            satellitesUsed = used,
            pdop = f[f.size - 3].toDoubleOrNull(),
            hdop = f[f.size - 2].toDoubleOrNull(),
            vdop = f[f.size - 1].toDoubleOrNull(),
            source = "GSA",
        )
    }

    /**
     * `$PASCD`：Apple 私有语句，公开资料没有字段表。
     *
     * 实测（奥迪 4580，43B）：`$PASCD,1583.410,C,D,0,1,0.00,0.000*4B`
     * —— 第一个字段每秒 +1.00（单调秒计数），其余恒定，**没有任何 ddmm.mmmm 形态的
     * 坐标**。所以这里只认「它是一条定位样本」，fix 记为 0（无效），不编造坐标。
     *
     * 若将来某台车的 PASCD 真带了坐标，字段数会明显多于 7，那时再按抓包补解析；
     * 现在的实现至少保证「不会把垃圾当坐标用」。
     */
    private fun parsePascd(f: List<String>): Fix =
        Fix(
            fixQuality = 0,
            satellitesUsed = f.getOrNull(4)?.toIntOrNull() ?: 0,
            source = "PASCD",
        )

    /** `ddmm.mmmm` / `dddmm.mmmm` + 半球字母 → 十进制度。 */
    private fun parseCoordinate(
        value: String,
        hemisphere: String,
        degreeDigits: Int,
    ): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        if (raw <= 0.0) return null
        val degrees = Math.floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        if (minutes >= 60.0) return null
        val result = degrees + minutes / 60.0
        val hemi = hemisphere.uppercase()
        // 只有 S/W 取负；空半球（部分车机不发）按正值，宁可稍偏也不要整条丢
        val signed = if (hemi == "S" || hemi == "W") -result else result
        return if (degreeDigits == 2) signed.coerceIn(-90.0, 90.0) else signed.coerceIn(-180.0, 180.0)
    }

    /** `hhmmss.ss` → (时, 分, 秒)。 */
    private fun parseUtcTime(value: String): Triple<Int, Int, Int>? {
        if (value.length < 6) return null
        val hh = value.substring(0, 2).toIntOrNull() ?: return null
        val mm = value.substring(2, 4).toIntOrNull() ?: return null
        val ss = value.substring(4, 6).toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59 || ss !in 0..60) return null
        return Triple(hh, mm, ss)
    }

    /** `ddmmyy` → (年, 月, 日)，年份按 2000 起算。 */
    private fun parseUtcDate(value: String?): Triple<Int, Int, Int>? {
        if (value == null || value.length < 6) return null
        val dd = value.substring(0, 2).toIntOrNull() ?: return null
        val mm = value.substring(2, 4).toIntOrNull() ?: return null
        val yy = value.substring(4, 6).toIntOrNull() ?: return null
        if (dd !in 1..31 || mm !in 1..12) return null
        return Triple(2000 + yy, mm, dd)
    }
}

/**
 * 把同一秒内陆续到达的 GGA/RMC/GSA 合并成一份完整快照。
 *
 * 为什么需要合并：车机把不同语句拆在**不同报文**里（GGA 有坐标、RMC 有速度和日期、
 * GSA 有 PDOP/VDOP），单看任何一条都缺字段。这里保留上一份的有效值，只在「新句子
 * 明确给了值」时覆盖——注意 **fixQuality 不能被 GSA 的 0 覆盖掉**（GSA 只说明
 * 解算模式，不代表这次没有定位）。
 *
 * 非线程安全：只在一个线程（iAP2 收帧线程）里用。
 */
class NmeaAssembler {
    private var lat: Double? = null
    private var lon: Double? = null
    private var altitude: Double? = null
    private var speedKmh: Double? = null
    private var heading: Double? = null
    private var fixQuality = 0
    private var satsUsed = 0
    private var satsVisible = 0
    private var hdop: Double? = null
    private var pdop: Double? = null
    private var vdop: Double? = null
    private var year = 0
    private var month = 0
    private var day = 0
    private var hour = 0
    private var minute = 0
    private var second = 0

    /** 最近一次收到的句子类型（诊断：车机到底发了什么）。 */
    var lastSource: String = ""
        private set

    /** 累计解析成功的句子数。 */
    var sentenceCount: Int = 0
        private set

    /** 喂入一条句子，返回合并后的快照；不认识的句子返回 null。 */
    fun feed(sentence: String): NmeaLocation.Fix? {
        val fix = NmeaLocation.parseSentence(sentence) ?: return null
        sentenceCount++
        lastSource = fix.source
        if (fix.source == "GGA" || fix.source == "RMC") {
            // 有坐标的句子优先：RMC 的 status=A 与 GGA 的 quality>0 都表示「这次定位有效」，
            // 但 GGA 的 quality 更细（1/2/4），有 GGA 时以后者为准。
            if (fix.latitude != null && fix.longitude != null) {
                lat = fix.latitude
                lon = fix.longitude
                if (fix.fixQuality > 0) fixQuality = fix.fixQuality
            } else if (fix.source == "GGA") {
                // GGA 明确报了「无定位」：坐标作废，别拿上一秒的旧坐标继续用
                fixQuality = 0
            }
        }
        if (fix.altitudeMeters != null) altitude = fix.altitudeMeters
        if (fix.speedKmh != null) speedKmh = fix.speedKmh
        if (fix.headingDeg != null) heading = fix.headingDeg
        if (fix.satellitesUsed > 0) satsUsed = fix.satellitesUsed
        if (fix.satellitesVisible > 0) satsVisible = fix.satellitesVisible
        if (fix.hdop != null) hdop = fix.hdop
        if (fix.pdop != null) pdop = fix.pdop
        if (fix.vdop != null) vdop = fix.vdop
        if (fix.utcYear > 0) {
            year = fix.utcYear
            month = fix.utcMonth
            day = fix.utcDay
        }
        if (fix.utcHour > 0 || fix.utcMinute > 0 || fix.utcSecond > 0) {
            hour = fix.utcHour
            minute = fix.utcMinute
            second = fix.utcSecond
        }
        return snapshot()
    }

    /** 当前合并结果；还什么都没收到时返回 null。 */
    fun snapshot(): NmeaLocation.Fix? {
        if (sentenceCount == 0) return null
        return NmeaLocation.Fix(
            latitude = lat,
            longitude = lon,
            altitudeMeters = altitude,
            speedKmh = speedKmh,
            headingDeg = heading,
            fixQuality = fixQuality,
            satellitesUsed = satsUsed,
            satellitesVisible = satsVisible,
            hdop = hdop,
            pdop = pdop,
            vdop = vdop,
            utcYear = year,
            utcMonth = month,
            utcDay = day,
            utcHour = hour,
            utcMinute = minute,
            utcSecond = second,
            source = lastSource,
        )
    }

    /** 新一轮会话开始：清空上一轮的定位，避免把停车前的位置当成本次定位。 */
    fun reset() {
        lat = null
        lon = null
        altitude = null
        speedKmh = null
        heading = null
        fixQuality = 0
        satsUsed = 0
        satsVisible = 0
        hdop = null
        pdop = null
        vdop = null
        year = 0
        month = 0
        day = 0
        hour = 0
        minute = 0
        second = 0
        lastSource = ""
        sentenceCount = 0
    }
}
