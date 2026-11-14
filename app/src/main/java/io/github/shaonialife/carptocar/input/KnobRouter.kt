package io.github.shaonialife.carptocar.input

import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import java.util.concurrent.LinkedBlockingDeque

/**
 * 旋钮 → 触摸动作的翻译。
 *
 * ## 为什么只能翻译成触摸
 *
 * CarPlay 里旋钮之所以能「移动焦点」，是因为 **iOS 侧有焦点引擎**，HID 的
 * `Wheel` 只是把「上/下一项」这一意图交给它。我们的画面是手机上的 **CarLife+**，
 * 它是一个普通 Android 触摸应用：没有焦点环、也没有旋钮焦点模型，CarLife 协议自己
 * 的硬键（`0x68008`）里只有上一曲/下一曲/播放/暂停/语音这 6 个键被 CarLife+ 处理。
 *
 * 所以旋钮**只能**翻译成触摸：转动 → 一段竖向滑动手势（列表滚动/翻页），按下 → 在锚点
 * 点一下。这条通路（`CarLifeHijack.handleTouch`）已经在实车上验证可用。
 *
 * ## 锚点为什么要记「最后一次触摸点」
 *
 * 用户通常先用手点一下列表，再转旋钮。锚点取最后一次真实触摸的位置，滑动就发生在
 * 用户正在看的区域；没有触摸记录时退回画布中心。
 *
 * ## 手势必须串行
 *
 * 一次滑动手势是 DOWN → MOVE×N → UP，中间任何一次 DOWN 插入都会把上一笔截断（引擎侧
 * 会看到连续两个 DOWN）。所以所有动作进队列，由一条线程串行执行；队列上限 3，
 * 快速连续转动时丢最旧的（滚动是相对量，丢一格只损失一点距离，不会错位）。
 */
object KnobRouter {
    /** 诊断日志出口（由 SessionController 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 一次滑动的手指行程（像素，画布 1440×540）。 */
    private const val SWIPE_HALF_PX = 70

    /** 滑动中间点数量。 */
    private const val SWIPE_MOVES = 3

    /** 相邻两个 MOVE 的间隔。 */
    private const val SWIPE_STEP_MS = 20L

    /** 一次点击的按住时长。 */
    private const val TAP_HOLD_MS = 40L

    /** 两笔手势之间的间隔（引擎侧用 220ms 判「上一笔缺抬起」，此值远小于它）。 */
    private const val GESTURE_GAP_MS = 50L

    /** 队列上限：超了丢最旧的。 */
    private const val MAX_QUEUE = 3

    /** 一次动作：转动若干格，或按下。 */
    private class Command(
        val steps: Int,
        val press: Boolean,
    )

    private val queue = LinkedBlockingDeque<Command>(MAX_QUEUE)

    @Volatile
    private var anchorX = -1

    @Volatile
    private var anchorY = -1

    @Volatile
    private var worker: Thread? = null

    private var idleLoggedAt = 0L

    /** 车机真实触摸落点：旋钮动作以此为锚。 */
    fun noteTouch(
        x: Int,
        y: Int,
    ) {
        anchorX = x
        anchorY = y
    }

    /** 旋钮转动（[steps] >0 顺时针、<0 逆时针）。 */
    fun onSteps(steps: Int) {
        if (steps == 0) return
        enqueue(Command(steps, press = false))
    }

    /** 旋钮按下。 */
    fun onPress() {
        enqueue(Command(0, press = true))
    }

    /** 会话结束/断开时调用：清锚点与队列。 */
    fun reset() {
        anchorX = -1
        anchorY = -1
        queue.clear()
    }

    private fun enqueue(command: Command) {
        if (!CarLifeHijack.isActive) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - idleLoggedAt > 5_000L) {
                idleLoggedAt = now
                logSink?.invoke("KNOB: 旋钮动作被丢弃——CarLife 尚未接管（无触摸通道可走）")
            }
            return
        }
        if (queue.remainingCapacity() == 0) queue.pollFirst()
        queue.offerLast(command)
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (worker?.isAlive == true) return
        val thread =
            Thread({ drain() }, "knob-router").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        worker = thread
        thread.start()
    }

    private fun drain() {
        while (true) {
            val command = queue.pollFirst()
            if (command == null) {
                // 队列空了就退出，下次有动作再起线程（避免常驻线程抢投屏资源）。
                // 判定必须与「置空 worker」放在同一把锁里：否则 enqueue 可能刚看到
                // worker 还活着（没起新线程），而这条线程接着退出，动作就永远留在队列里。
                synchronized(this) {
                    if (queue.isEmpty()) {
                        worker = null
                        return
                    }
                }
                continue
            }
            try {
                if (command.press) tap() else swipe(command.steps)
            } catch (error: Exception) {
                logSink?.invoke("KNOB: 动作执行异常 - ${error.message}")
            }
            sleep(GESTURE_GAP_MS)
        }
    }

    /**
     * 转动 → 竖向滑动。
     *
     * 顺时针 = 「下一项」= 内容向下滚 = 手指**向上**划（终点在起点上方）。
     */
    private fun swipe(steps: Int) {
        val (x, y) = anchor()
        val up = steps > 0
        val startY = if (up) y + SWIPE_HALF_PX else y - SWIPE_HALF_PX
        val endY = if (up) y - SWIPE_HALF_PX else y + SWIPE_HALF_PX
        logSink?.invoke(
            "KNOB: 转动 ${if (steps > 0) "+$steps" else "$steps"} → " +
                "${if (up) "向上" else "向下"}滑动 ($x,$startY)→($x,$endY)",
        )
        if (!CarLifeHijack.handleTouch(0, x, startY, true)) return
        for (index in 1..SWIPE_MOVES) {
            sleep(SWIPE_STEP_MS)
            val py = startY + (endY - startY) * index / SWIPE_MOVES
            CarLifeHijack.handleTouch(0, x, py, true)
        }
        sleep(SWIPE_STEP_MS)
        CarLifeHijack.handleTouch(0, x, endY, false)
    }

    /** 按下 → 点一下锚点。 */
    private fun tap() {
        val (x, y) = anchor()
        logSink?.invoke("KNOB: 按下 → 点击 ($x,$y)")
        if (!CarLifeHijack.handleTouch(0, x, y, true)) return
        sleep(TAP_HOLD_MS)
        CarLifeHijack.handleTouch(0, x, y, false)
    }

    private fun anchor(): Pair<Int, Int> {
        val width = CarLifeHijack.canvasWidthPx
        val height = CarLifeHijack.canvasHeightPx
        val cx = if (width > 0) width / 2 else 720
        val cy = if (height > 0) height / 2 else 270
        val x = if (anchorX >= 0) anchorX else cx
        val y = if (anchorY >= 0) anchorY else cy
        // 滑动要留出行程，锚点避开上下边缘
        return x to y.coerceIn(SWIPE_HALF_PX + 8, (height - SWIPE_HALF_PX - 8).coerceAtLeast(SWIPE_HALF_PX + 8))
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
