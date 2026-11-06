package io.github.shaonialife.carptocar.airplay

/**
 * 车机 `/feedback` 里的**接收端收帧统计**（`statsDictionary`）。
 *
 * ## 为什么需要它
 *
 * AirPlay 的 `/feedback` 默认只回报音频流（`streams[]` 的 `sampleTime`）——屏幕流根本
 * 不出现在里面。于是「车机到底收没收到视频帧」在手机侧一直是个黑箱：我们只能看到
 * `VIDEO: 帧 #N 已发送`（写进 socket 成功），看不到车机侧有没有接住。
 * 2026-11-06 奥迪 CL3D 那轮 37 秒全绿却全黑，最缺的就是这一刀。
 *
 * 奥迪 4580（CL3D）的 `libairplay` 在会话 SETUP 里 `statsCollectionEnabled=true` 时，
 * 会在 `/feedback` 里额外回一个 `statsDictionary`：键是流号，值是短名键值对
 *
 * ```
 * statsDictionary = { "1" = { stty = 110, rfps = 19.8, ltnc = …, ahea = … }, … }
 * ```
 *
 * - `stty`：流类型（110 主屏 / 111 副屏，音频是 102 等）；
 * - `rfps`：接收端**实收帧率**（不是我们送出的帧率，是它自己数出来的）。
 *
 * ## 语义边界（别过度解读）
 *
 * 这组计数发生在「认证之后、解码器之前」（`stage=pre-decode`）。所以：
 *
 * - `rfps > 0` ⇒ 帧确实进了接收端前级（网络/解密/AirPlay 组装都没问题）；
 * - `rfps ≈ 我们送的帧率` ⇒ 车机在整速接收，问题不在传输；
 * - **它不能证明解码成功，也不能证明 HMI 已切屏**——那两件事在车机侧的
 *   `CDSICarplayImpl` 日志里（`Video state=active` / `Failed to overtake the screen`）。
 *
 * 但它足以把「我们没送出去」和「送出去了车机没解」切成两类完全不同的故障。
 */
internal data class ScreenReceiveStat(
    val streamId: String,
    val streamType: Int,
    val receiveFps: Double?,
    /** 接收端报告的延迟量（`ltnc`，随固件而异）：只记录，不做判断。 */
    val latency: Number?,
    /** 接收端报告的前瞻/缓冲（`ahea`）：只记录，不做判断。 */
    val ahead: Number?,
)

internal object AirPlayReceiverStats {
    const val SCREEN_STREAM_TYPE = 110
    const val ALT_SCREEN_STREAM_TYPE = 111

    /** 固件就用这几个短名，别改成「全称」——它是 Apple 的私有键。 */
    private const val KEY_STATS = "statsDictionary"
    private const val KEY_TYPE = "stty"
    private const val KEY_TYPE_FALLBACK = "type"
    private const val KEY_FPS = "rfps"
    private const val KEY_LATENCY = "ltnc"
    private const val KEY_AHEAD = "ahea"

    fun isScreenType(type: Int): Boolean = type == SCREEN_STREAM_TYPE || type == ALT_SCREEN_STREAM_TYPE

    /** 这份 `/feedback` 有没有带 `statsDictionary`（不论里面有没有屏幕流）。 */
    fun present(root: Map<*, *>): Boolean = root[KEY_STATS] is Map<*, *>

    /**
     * 键在、但**不是字典**（固件换了形态，或被别的类型占了）。
     *
     * 单独判一次，是为了别把「车机回了但我们解不动」写成「车机不回报」——
     * 那句错误结论会让人下一轮直接放弃这条探针。
     */
    fun presentButUnexpected(root: Map<*, *>): Boolean = root.containsKey(KEY_STATS) && !present(root)

    /** 取屏幕流的收帧统计；没有 `statsDictionary` 时返回空表。 */
    fun screenStats(root: Map<*, *>): List<ScreenReceiveStat> {
        val dict = root[KEY_STATS] as? Map<*, *> ?: return emptyList()
        return dict.entries.mapNotNull { (key, value) ->
            val entry = value as? Map<*, *> ?: return@mapNotNull null
            val type =
                (entry[KEY_TYPE] as? Number)?.toInt()
                    ?: (entry[KEY_TYPE_FALLBACK] as? Number)?.toInt()
                    ?: return@mapNotNull null
            if (!isScreenType(type)) return@mapNotNull null
            ScreenReceiveStat(
                streamId = key?.toString().orEmpty(),
                streamType = type,
                receiveFps = (entry[KEY_FPS] as? Number)?.toDouble(),
                latency = entry[KEY_LATENCY] as? Number,
                ahead = entry[KEY_AHEAD] as? Number,
            )
        }
    }

    /** `statsDictionary` 里出现过哪些流类型（诊断「为什么没有屏幕条目」用）。 */
    fun streamTypes(root: Map<*, *>): List<Int> {
        val dict = root[KEY_STATS] as? Map<*, *> ?: return emptyList()
        return dict.values.mapNotNull { value ->
            val entry = value as? Map<*, *> ?: return@mapNotNull null
            (entry[KEY_TYPE] as? Number)?.toInt() ?: (entry[KEY_TYPE_FALLBACK] as? Number)?.toInt()
        }
    }

    /** 一行可读文案（日志与门禁共用一份，避免两处措辞漂移）。 */
    fun describe(stat: ScreenReceiveStat): String =
        "stream=${stat.streamId} type=${stat.streamType} rfps=" +
            (stat.receiveFps?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "无") +
            (stat.latency?.let { " ltnc=$it" } ?: "") +
            (stat.ahead?.let { " ahea=$it" } ?: "")

    /** 把实收帧率写成人话（门禁/界面对用户那一层）。 */
    fun describeFps(fps: Double?): String =
        if (fps == null) "未知" else String.format(java.util.Locale.US, "%.1f fps", fps)
}
