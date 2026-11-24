package io.github.shaonialife.carptocar.amap

import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.carlife.CarLifeNaviNextTurn

/** 状态机产出：要么是一条引导帧，要么是一帧清除。 */
internal sealed interface AmapNaviEmission {
    data class Guide(val turn: CarLifeNaviNextTurn) : AmapNaviEmission

    /** 清除仪表/HUD 引导（对应 `CarLifeNaviGuide.ACTION_END` 的语义）。 */
    data object Clear : AmapNaviEmission
}

/**
 * 高德导航事件的状态机。**零 Android 依赖，可单测。**
 *
 * 三条规则不是拍脑袋，都是拿实际行为换来的（见 `docs/archive/amap-linksdk-integration-plan-20261124.md` §6）：
 *
 * 1. **结束靠 `type=100/200`，不靠 `eventType`。** linksdk 的 `type=4` 帧里有个
 *    `eventType`，但它不表达 start/update/end 三态（实测恒为 1），
 *    真正的「导航停止」只有 `type=100`，到达/手动退出是 `type=200`。
 * 2. **结束事件后 2s 内的 `type=4` 必须丢弃。** 高德会在结束后补发最后一帧真实诱导，
 *    照发会把刚推出去的清除帧顶掉，HUD 就一直挂着上一轮的箭头。
 * 3. **15s 无新帧即清。** 高德每秒一帧，15s 静默说明数据源已经死了（进程被杀、
 *    服务被系统回收），不能指望车机自己的 30s 兜底——那段时间仪表一直显示过期距离。
 */
internal class AmapNaviStateMachine(
    private val staleTimeoutMs: Long = STALE_TIMEOUT_MS,
    private val endSettleMs: Long = END_SETTLE_MS,
) {
    private var guiding = false
    private var lastFrameAt = 0L
    private var endedAt = 0L

    /** 当前是否正在引导（供上层判断"是否真的发过诱导"）。 */
    val isGuiding: Boolean get() = guiding

    /**
     * 收到一条 `type=4` 导航状态帧。
     *
     * @return 需要下发的帧；被丢弃（迟到/无效）时返回 null。
     */
    fun onNavigation(
        snapshot: AmapNaviSnapshot,
        now: Long,
    ): AmapNaviEmission? {
        if (endedAt != 0L && now - endedAt < endSettleMs) return null
        // segmentRemainDist 为负是「无效/未定位」，高德停车时会发 -1 之类。
        // 这种帧带着 -1 米上仪表比不发更糟。
        if (snapshot.segmentRemainMeters < 0) return null
        val action = if (guiding) CarLifeNaviGuide.ACTION_UPDATE else CarLifeNaviGuide.ACTION_START
        guiding = true
        lastFrameAt = now
        return AmapNaviEmission.Guide(AmapNaviMapper.toNextTurn(snapshot, action))
    }

    /**
     * 收到 `type=100`（导航暂停/停止）或 `type=200`（到达 / 手动退出）。
     *
     * 没在引导时返回 null —— 否则会把上一轮（或别的数据源）的 HUD 清掉。
     */
    fun onStop(now: Long): AmapNaviEmission? {
        if (!guiding) return null
        guiding = false
        endedAt = now
        return AmapNaviEmission.Clear
    }

    /** 定时检查数据源是否已经沉默。由上层每秒调一次即可。 */
    fun onTick(now: Long): AmapNaviEmission? {
        if (!guiding || lastFrameAt == 0L) return null
        if (now - lastFrameAt <= staleTimeoutMs) return null
        guiding = false
        return AmapNaviEmission.Clear
    }

    /** 断开/关开关时调用，只清内部状态，不产出清除帧（由调用方决定要不要清 HUD）。 */
    fun reset() {
        guiding = false
        lastFrameAt = 0L
        endedAt = 0L
    }

    companion object {
        /** 15s 无帧判死。高德 1Hz，15 帧的容忍度足够扛住一次 GC 或短时卡顿。 */
        const val STALE_TIMEOUT_MS = 15_000L

        /** 结束事件后的静默窗：高德会补发最后一帧，必须在窗外才接受新导航。 */
        const val END_SETTLE_MS = 2_000L
    }
}

/** 便于日志与断言：把引导帧压成一行。 */
internal fun CarLifeNaviNextTurn.brief(): String =
    "${CarLifeNaviGuide.actionLabel(action)} " +
        "raw=$rawNextTurn → ${CarLifeNaviGuide.label(nextTurn)}($nextTurn)" +
        " · ${roadName.ifBlank { "-" }} · ${remainDistance}m / ${totalDistance}m"
