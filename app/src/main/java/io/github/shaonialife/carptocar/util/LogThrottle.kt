package io.github.shaonialife.carptocar.util

/**
 * 同类日志限流器（线程安全）。
 *
 * 典型场景：CarLife 引擎每秒推一次「媒体进度」、媒体信息解析失败每次媒体变化都报一次，
 * 进程被系统冻结后解冻的瞬间还会把积压的几百条一起吐出来——日志被这些行淹掉，
 * 真正要看的那几行（配对、SETUP、断链）反而找不到。
 *
 * 用法：只在**要写日志之前**问一句 `allow("key")`，false 就安静跳过。
 * 计数类信息（如累计丢弃字节）应由调用方在放行时读走并清零，避免限流期丢数据。
 */
class LogThrottle(
    private val minIntervalMillis: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lastAt = HashMap<String, Long>()

    /** @return true = 本次可以写；false = 距上次同类日志不足 [minIntervalMillis]，应跳过。 */
    fun allow(key: String): Boolean =
        synchronized(lastAt) {
            val now = clock()
            val previous = lastAt[key]
            if (previous != null && now - previous < minIntervalMillis) {
                false
            } else {
                lastAt[key] = now
                true
            }
        }

    fun reset() {
        synchronized(lastAt) { lastAt.clear() }
    }
}
