package io.github.shaonialife.carptocar.audio

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 导航播报的压低 / 恢复（Apple CarPlay 规范 §23.3.7.1/.2）。
 *
 * 车机只有收到 `duckAudio` 才把这条 101 导航流当独立音轨处理——实车证据：
 * 不加时音乐虽被压低，但方向盘音量类别仍显示 Entertainment；加了之后 9~11 对
 * `duckAudio`/`unduckAudio` 全部 RTSP 200，且类别变成 Navigation。
 *
 * ## 为什么单独一个 worker
 *
 * 触发源是 CarLife 9241 的 TTS 边沿回调（音频读线程）。`POST /command` 是一次
 * 阻塞的 RTSP 往返，绝不能在那条线程上做——会卡住 PCM 收包与 ACK。
 *
 * ## 两条纪律（与 Q4 分支实现一致）
 *
 * - **短间隔续播取消恢复**：连续播报之间常有几百毫秒空档，结束边沿先挂一个很短的
 *   恢复任务，若下一条播报赶在它执行前开始就取消，避免音乐被反复压低/恢复。
 * - **应答丢失仍恢复**：duck 写出后哪怕没收到 200，也记 [restoreNeeded]；否则一次
 *   丢失应答就会让音乐永久压低。
 */
internal class NavigationDucking(
    private val send: (duck: Boolean) -> Boolean,
    private val log: (String) -> Unit = {},
    /** 结束边沿后延迟多久才真发 unduck，用来吞掉连续播报之间的短空档。 */
    private val releaseDelayMs: Long = 300L,
) : AutoCloseable {
    private val worker =
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "nav-ducking").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    private var release: ScheduledFuture<*>? = null
    private var generation = 0L
    private var promptActive = false
    private var closed = false

    // 只在 worker 线程上读写。
    private var restoreNeeded = false

    @Synchronized
    fun begin() {
        if (closed) return
        generation++
        release?.cancel(false)
        release = null
        if (promptActive) return
        promptActive = true
        worker.execute {
            val accepted = runCatching { send(true) }.getOrDefault(false)
            restoreNeeded = true // 即便应答丢失也要恢复，绝不留永久压低
            if (!accepted) log("NAV-PROMPT: duckAudio 未获 200，仍安排恢复")
        }
    }

    @Synchronized
    fun end() {
        if (closed || !promptActive) return
        val ticket = ++generation
        release?.cancel(false)
        release =
            worker.schedule({
                val current =
                    synchronized(this) {
                        (ticket == generation && !closed).also { if (it) promptActive = false }
                    }
                if (current) restore()
            }, releaseDelayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        release?.cancel(false)
        worker.execute(::restore)
        worker.shutdown()
    }

    private fun restore() {
        if (!restoreNeeded) return
        restoreNeeded = false
        runCatching { send(false) }
            .onFailure { log("NAV-PROMPT: unduckAudio 失败 - ${it.message}") }
    }
}
