package io.github.shaonialife.carptocar.airplay

/**
 * 会话级 RECORD 的发送预算：**同一会话最多允许发几次 RECORD**。
 *
 * ## 为什么需要它
 *
 * AirPlay 的 RECORD 报文本体是空 plist，它激活的是**整个会话**，不是某一条流
 * （`AirPlayClient.record()` 里发的是空 bplist）。所以「会话已经激活之后再发一次
 * RECORD」在协议语义上没有任何必要性——它的唯一用途是当 **nudge**：某些接收端
 * （奥迪 MIB3）在音频流事后 SETUP 加入会话时，不再发一次 RECORD 就不排播音频。
 *
 * 问题在于另一类接收端对**重复 RECORD 过敏**，收到第二次就直接关掉自己的 AirPlay
 * 服务（用户侧表现为「一放声音车机就重启」）：
 *
 * - 宝马 iD6 / NBT Evo（`F49-NBTEvo-0716` / `G38-NBTEvo-0716`）：2026-11-14 现场日志
 *   `22:44:12.356 远端控制通道结束 - control EOF` → `22:44:18.485 NSD: 服务消失 CarPlay`；
 *   同一次会话里第一次 RECORD 之后视频稳跑了 106 秒，死的永远是第二次。
 * - JYBOX-80 / Magic-Car-Link（issue #14）：补发 RECORD 直接回非 200，并顺手 0x4301 置 0。
 *
 * ## 为什么默认仍然允许重复
 *
 * 奥迪 MIB3 依赖第二次 RECORD 才会真正排播后加入的音频流；把默认值改成「只允许一次」
 * 会直接让 MIB3 没声音。所以默认 [allowRepeat] = true，只有主控按接收端身份
 * （`StreamOrderMemory.matchesKnown` / 学到的 `AUDIO_FIRST`）显式
 * [restrictToSingle] 之后才收紧。
 *
 * ## 线程安全
 *
 * `record()` 会被资源线程（主屏归还）、音频看门狗线程各调一次，可能并发进入；
 * 计数与判定都放在同一把锁里，保证「先到的那一次放行、后到的一律拦下」，
 * 不会出现两次都读到 `sent == 0` 而双双放行的竞态。
 */
class RecordBudget(
    allowRepeat: Boolean = true,
) {
    private val lock = Any()

    /** 是否允许重复发 RECORD。默认 true（奥迪 MIB3 需要 nudge）。 */
    @Volatile
    private var allowRepeat: Boolean = allowRepeat

    /** 已经真正发出去的 RECORD 次数。 */
    private var sent: Int = 0

    /** 已发次数（诊断/测试用）。 */
    val sentCount: Int
        get() = synchronized(lock) { sent }

    /** 这一次调用还应不应该真的发 RECORD。 */
    fun shouldSend(): Boolean = synchronized(lock) { allowRepeat || sent == 0 }

    /** 真正把 RECORD 发出去之后登记一次（被拦下的跳过**不计**）。 */
    fun noteSent() {
        synchronized(lock) { sent++ }
    }

    /**
     * 把预算收成「同一会话只允许一次 RECORD」。
     *
     * 由 `AirPlayClient.restrictSingleRecord` 在解析出接收端顺序后调用，一轮会话一次。
     * 已经发过（`sent > 0`）之后再收紧也安全：后续调用一律被拦。
     */
    fun restrictToSingle() {
        allowRepeat = false
    }

    /** 拦截时写进日志的原因（拼在「跳过重复 RECORD」后面）。 */
    fun describeSkip(): String =
        "本车机收到第二次 RECORD 会关掉自己的 AirPlay 服务" +
            if (sent > 0) "，已发 $sent 次" else ""
}
