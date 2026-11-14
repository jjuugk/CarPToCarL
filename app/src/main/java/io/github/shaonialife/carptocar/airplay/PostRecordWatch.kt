package io.github.shaonialife.carptocar.airplay

import android.os.SystemClock

/**
 * 「音频流加入会话后**补发的那一次 RECORD**」之后的怀疑窗口。
 *
 * 为什么需要它（2026-11-13 宝马 MGU-81 日志 `CarPToCarL-log-20261113-075743-all`）：
 *
 * 那一晚一共建立 8 轮会话，其中 5 轮在**补发 RECORD 之后 1.79 / 1.98 / 2.01 / 2.03 /
 * 2.19 秒**被车机整链拆掉（事件通道 `Connection reset` + RFCOMM 关闭，随后 mDNS 服务
 * 消失、车机热点掉线——即用户看到的「车机重启」）。另外 3 轮同一步骤活了下来，说明
 * 这不是「报文非法被拒」，而是接收端内部的一次竞态：**它在这 2 秒里做什么失败了**。
 *
 * 而那次补发 RECORD 本身在宝马上是**没有收益的**：日志里音频流 `controlPort` 全程
 * ICMP 端口不可达（8 分钟 480/481 次被拒），车机从未为这条流开口、从未排播。真正
 * 让声音出来的路径在这台车上本来就不成立，却每次连接都要赌一次 2 秒窗口。
 *
 * 所以这里只记一个时间戳：**补发 RECORD 的时刻 + 车机身份**。会话随后被车机拆掉时，
 * 由 [peek]/[take] 判断「是不是就在这个窗口里」。命中就归因到「这台车机对重复 RECORD
 * 过敏」，交给 [StreamOrderMemory.notePostRecordRejected] 下一轮改音频优先
 * （先 SETUP 音频，再一次 RECORD 同时激活，全程只有一次 RECORD）。
 *
 * 故意**不**用它去给音频档位定罪：同一个档位（`t102/media/0x800000` AAC-LC 48k）
 * 当晚被记了 4 次「不兼容」，可它随后在另一轮里连续活了 8.6 分钟——按 2 秒窗口判档位
 * 只会把唯一可用的档位永久拉黑。
 */
class PostRecordWatch(
    private val windowMillis: Long = SUSPECT_WINDOW_MILLIS,
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** 命中的一次怀疑：哪台车机、补发 RECORD 之后过了多久被拆。 */
    data class Suspect(
        val identity: String,
        val elapsedMillis: Long,
    )

    private val lock = Any()
    private var markedAt = 0L
    private var identity = ""

    /** 补发 RECORD 刚发出去（在真正 `client.record()` 之前调，把往返也计入窗口）。 */
    fun mark(receiverIdentity: String) {
        synchronized(lock) {
            markedAt = now()
            identity = receiverIdentity
        }
    }

    /** 只读查看，不消费（诊断/日志用）。 */
    fun peek(): Suspect? = synchronized(lock) { suspectLocked() }

    /**
     * 取走这次怀疑（一次性）。
     *
     * 用「取走」而不是「读取」：车机掉线时归因入口会被不同线程各调一次（视频通道
     * 回调 + BT 关闭回调），读两次就会记两遍记忆、打两遍日志。
     */
    fun take(): Suspect? =
        synchronized(lock) {
            val hit = suspectLocked()
            markedAt = 0L
            identity = ""
            hit
        }

    /**
     * 清掉标记（用户主动断开、或归因结论已用过）。
     *
     * 刻意**不**在新一轮开始时清：车机掉线后归因入口（视频通道收尾回调）可能比下一轮
     * 起步晚一拍，那时清空等于把结论扔掉，转而去怪音频档位。跨轮误判由时间窗口挡住——
     * 实测两轮之间最短也有 6 秒（宝马 19:32:49 断 → 19:32:58 下一轮）。
     */
    fun clear() {
        synchronized(lock) {
            markedAt = 0L
            identity = ""
        }
    }

    private fun suspectLocked(): Suspect? {
        if (markedAt == 0L) return null
        val elapsed = now() - markedAt
        // 时钟不可能倒流，但 uptimeMillis 在极端情况下会出现 0；负值一律当「不在窗口内」
        if (elapsed < 0 || elapsed > windowMillis) return null
        return Suspect(identity, elapsed)
    }

    companion object {
        /**
         * 怀疑窗口 3.5s。
         *
         * 实测 5 次全落在 1.79~2.19s；留一倍余量是因为「车机先关事件通道、再关
         * RFCOMM」之间有 0~60ms 抖动，且归因入口本身可能晚一拍才被调到。再放宽就会
         * 把「起流 3 秒后才自然断掉」的别的原因也算进来。
         */
        const val SUSPECT_WINDOW_MILLIS = 3_500L
    }
}
