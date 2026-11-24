package io.github.shaonialife.carptocar.carlife

import android.os.SystemClock
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 车机麦克风 PCM → CarLife VR(9242) 的有界写入器，按 AMcar 实现移植。
 *
 * RTP 接收线程绝不能被本地 TCP 写阻塞，也不能无限堆积：固定 32 帧队列，满则丢最旧；
 * 独立 2 秒 stall 检测把卡死的 socket 关掉回退。`record(false)` 换代并清空队列，
 * 上一句排队的帧不会混进下一次识别。
 */
internal class AmcarMicWriter(
    private val output: OutputStream,
    private val closeSocket: () -> Unit,
    private val failed: () -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : AutoCloseable {
    private data class Frame(
        val generation: Long,
        val bytes: ByteArray,
    )

    private val queue = ArrayBlockingQueue<Frame>(32)
    private val closed = AtomicBoolean(false)
    private val writingSince = AtomicLong(-1)
    private var generation = 0L

    @Volatile
    private var recording = false

    private val worker =
        Thread(::run, "amcar-mic-writer").apply {
            isDaemon = true
            start()
        }
    private val watchdog =
        Executors
            .newSingleThreadScheduledExecutor {
                Thread(it, "amcar-mic-watchdog").apply { isDaemon = true }
            }.apply { scheduleWithFixedDelay(::checkStall, 1, 1, TimeUnit.SECONDS) }

    val ready: Boolean get() = !closed.get()

    @Synchronized
    fun record(active: Boolean) {
        generation++
        recording = active && ready
        queue.clear()
    }

    @Synchronized
    fun offer(pcm: ByteArray): Boolean {
        if (!recording || !ready || pcm.isEmpty() || pcm.size > 8192 || pcm.size % 2 != 0) return false
        return queue.offer(Frame(generation, AmcarVoiceProtocol.microphone(pcm, now().toInt())))
    }

    private fun run() {
        try {
            while (ready) {
                val frame = queue.take()
                if (!synchronized(this) { recording && generation == frame.generation && ready }) continue
                writingSince.set(now())
                output.write(frame.bytes)
                output.flush()
                writingSince.set(-1)
            }
        } catch (_: InterruptedException) {
            if (ready) fail()
        } catch (_: Exception) {
            if (ready) fail()
        }
    }

    /** 独立于写线程与命令通道：两者都可能被本地 socket 卡住。 */
    fun checkStall() {
        val since = writingSince.get()
        if (since >= 0 && now() - since >= 2_000) fail()
    }

    private fun fail() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            watchdog.shutdownNow()
            runCatching(closeSocket)
            worker.interrupt()
            failed()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            watchdog.shutdownNow()
            runCatching(closeSocket)
            worker.interrupt()
        }
    }
}
