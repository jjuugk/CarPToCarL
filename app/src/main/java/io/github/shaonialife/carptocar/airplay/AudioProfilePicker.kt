package io.github.shaonialife.carptocar.airplay

/**
 * 从车机 `/info` 的 `audioFormats` 里挑出**指定 audioType** 的一档可用格式。
 *
 * ## 为什么抽成纯函数
 *
 * 这里判断错一步的后果不是「报错」，而是「静默建错流」：车机没声明 `alert` 档却照样
 * 发 SETUP，有的固件会回 200、给出 dataPort、然后一个样本都不消费（Honda 100/media
 * 的老症状），排查时看到的只是「导航没声音」。把规则固定成可单测的纯函数，比指望
 * 实车日志逐台验证便宜得多。
 *
 * 规则只有三条：
 *  1. `audioType` 必须**逐字符**命中车机声明的档（大小写不敏感）——没声明就是 null，
 *     绝不退回 `media`（退回等于又变回混音，实验结论会被污染成「无效」）；
 *  2. 同一 `audioType` 可能出现在多个 `type` 档上（100 / 102），按 [orderedFormats]
 *     给的顺序逐个试，先命中的赢；
 *  3. 命中条件是该档的 `audioOutputFormats` 位图**含**目标格式位。
 *
 * @param orderedFormats 候选格式，按优先级从高到低给 `(streamType, formatBit)`。
 * @return 命中的 `(streamType, formatBit)`；没声明或位图都不含 → null。
 */
internal fun pickDeclaredFormat(
    declared: List<Map<*, *>>,
    audioType: String,
    orderedFormats: List<Pair<Long, Long>>,
): Pair<Long, Long>? {
    val wanted = audioType.trim().lowercase()
    if (wanted.isEmpty()) return null
    val channels = declared.filter { (it["audioType"] as? String)?.lowercase() == wanted }
    if (channels.isEmpty()) return null
    for ((streamType, bit) in orderedFormats) {
        if (bit <= 0L) continue
        val channel =
            channels.firstOrNull { ((it["type"] as? Number)?.toLong()) == streamType } ?: continue
        val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
        if ((output and bit) == 0L) continue
        return streamType to bit
    }
    return null
}
