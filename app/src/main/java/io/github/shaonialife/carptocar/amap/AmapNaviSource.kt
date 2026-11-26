package io.github.shaonialife.carptocar.amap

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.carptocar.carlife.CarLifeNaviGuide
import io.github.shaonialife.carptocar.media.ManeuverArrow
import io.github.shaonialife.carptocar.media.NavigationDisplay
import io.github.shaonialife.carptocar.media.NavigationText

/** 一轮导航里最多因 STALE 重建几次链路：防止高德长期不给帧时无限重绑。 */
internal const val AMAP_MAX_STALE_RESTARTS = 2

/**
 * 该不该在 15s 静默清帧后重建高德数据源。
 *
 * 纯函数，单测直接钉真值表。`wasGuiding` 必须是 `onTick` **之前**的状态：
 * 正常导航结束走 `onStop()` 已经把 guiding 置 false，不会误触发重连。
 */
internal fun shouldRestartAfterStale(
    wasGuiding: Boolean,
    emission: AmapNaviEmission?,
    registered: Boolean,
    frameCount: Int,
    staleRestarts: Int,
): Boolean =
    wasGuiding &&
        emission is AmapNaviEmission.Clear &&
        registered &&
        frameCount > 0 &&
        staleRestarts < AMAP_MAX_STALE_RESTARTS

/**
 * 高德数据源的进程级门面：管开关、管生命周期、管状态机节拍。
 *
 * ## 开关语义：**替换**，不是兜底
 *
 * 开关（设置里叫「使用手机高德地图获取导航数据」）打开后，仪表/HUD 的导航数据
 * **只认高德**：`CarLife 0x10030` 的诱导会被 `SessionController` 直接拦掉不发。
 * 关掉开关则回到原行为，CarLife 一手包办。
 *
 * 之所以不做「谁有数据用谁」的自动切换：两路同时往一个 HUD 推，箭头会互相顶，
 * 用户看到的是抖动的图标；而且"为什么这次是 A 下次是 B"根本没法排查。
 * 开关是显式的，行为就必须是确定的。
 *
 * ## 生命周期
 *
 * 绑定与开关同寿——不随车机会话启停。认证一次成本不低（要等高德回 token），
 * 而每秒一帧的开销可以忽略；会话结束不解绑，下一轮连上立刻就有数据。
 */
internal object AmapNaviSource {

    /** 开关镜像。`SessionController` 用它决定要不要拦掉 CarLife 的 0x10030。 */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 状态机产出（引导帧 / 清除）。由 `SessionController` 装配。 */
    @Volatile
    var onEmission: ((AmapNaviEmission) -> Unit)? = null

    /**
     * 媒体卡片（iAP2 `0x5001` NowPlaying 覆盖层）用的引导。
     *
     * 与仪表那条 [onEmission] 是**同一份数据、两条出口**：仪表吃 `0x5201/0x5202`，
     * 媒体卡片吃这一份。放在这里而不是让 `SessionController` 再订阅一次，
     * 是为了保证两条链路的「当前状态」永远同步——否则清除帧先到一边晚到另一边，
     * 会出现仪表已经空了、媒体卡片还挂着上一个路口箭头的情况。
     *
     * `null` = 当前没有导航（或已被清除）。
     */
    @Volatile
    private var guide: NavigationDisplay.Guide? = null

    /** 当前引导。媒体卡片覆盖层每秒读取一次，命中就是一次字段读。 */
    fun currentGuide(): NavigationDisplay.Guide? = guide

    private val machine = AmapNaviStateMachine()

    /** 本轮导航已因 STALE 重建过几次；收到新引导帧时归零。 */
    @Volatile
    private var staleRestarts = 0

    /**
     * **必须懒创建**：JVM 单测里 `Looper.getMainLooper()` 返回 null，
     * 构造期 `Handler(null)` 会抛异常，把引用本对象的 `SessionController` 类初始化整个带崩
     * （现象是 `ExceptionInInitializerError` 到一串 `NoClassDefFoundError`，与真实缺陷无关）。
     */
    private var tickerHandler: Handler? = null

    private fun ticker(): Handler =
        tickerHandler ?: Handler(Looper.getMainLooper()).also { tickerHandler = it }

    // 客户端持有的是 applicationContext（进程单例，与 Activity 生命周期无关），
    // lint 只看"静态字段里有 Context"就报泄漏，这里按实际情况抑制。
    @Suppress("StaticFieldLeak")
    private var client: AmapLinkClient? = null
    private var appContext: Context? = null
    private var logger: ((String) -> Unit)? = null

    private val tick =
        object : Runnable {
            override fun run() {
                if (!enabled) return
                val now = System.currentTimeMillis()
                // 必须取 tick 之前的状态：正常结束（onStop）已经把 guiding 置 false，
                // 只有「导航中途静默」才会在 tick 里产出 Clear 且 wasGuiding=true。
                val wasGuiding =
                    synchronized(machine) {
                        machine.isGuiding
                    }
                val emission =
                    synchronized(machine) {
                        machine.onTick(now)
                    }
                emission?.let { dispatch(it) }
                val c = client
                if (shouldRestartAfterStale(
                        wasGuiding = wasGuiding,
                        emission = emission,
                        registered = c?.isRegistered == true,
                        frameCount = c?.frameCount ?: 0,
                        staleRestarts = staleRestarts,
                    )
                ) {
                    staleRestarts++
                    logger?.invoke("AMAP: 导航中途静默 15s，重建链接服务（第 $staleRestarts 次）")
                    c?.restartAfterStale()
                }
                ticker().postDelayed(this, 1_000L)
            }
        }

    /** 进程启动时登记上下文与日志出口（幂等）。 */
    fun configure(
        context: Context,
        log: (String) -> Unit,
    ) {
        appContext = context.applicationContext
        logger = log
        // 媒体卡片的箭头图标一次性读进内存（12 张共 16KB）。放这里而不是 start()：
        // 开关关着也可能被 nowPlayingGuide() 读到旧 guide，图得随时可用。
        val loaded = ManeuverArrow.prepare(context.applicationContext)
        if (loaded < ManeuverArrow.expectedCount) {
            log("AMAP: 箭头图标仅加载 $loaded/${ManeuverArrow.expectedCount} 张，缺的形状只显示文字")
        }
    }

    /** 读开关并存进镜像；由 `SessionController.ensure` 与设置面板的开关共同驱动。 */
    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        // 设置面板可能在 SessionController.ensure 之前切开关（Activity onCreate 顺序不定），
        // 这里兜底登记上下文，否则 start() 会因为 appContext 为 null 静默什么都不做。
        if (appContext == null) appContext = context.applicationContext
        if (enabled == value) {
            // 同值也要确保真的在跑：进程刚起时 enabled=false、prefs=true，
            // 靠 ensure 再调一次本函数才启动，不能因为"值没变"就跳过。
            if (value) start(context.applicationContext)
            return
        }
        enabled = value
        if (value) start(context.applicationContext) else stopInternal(clearHud = true)
    }

    /** 开关打开时确保客户端在跑。 */
    fun startIfEnabled() {
        val ctx = appContext ?: return
        if (enabled) start(ctx)
    }

    /** 是否由高德接管仪表导航数据（开关打开即接管）。 */
    fun isReplacingCarLife(): Boolean = enabled

    private fun start(context: Context) {
        if (!enabled) return
        val existing = client
        if (existing != null) {
            existing.start()
            return
        }
        val created =
            AmapLinkClient(
                context = context,
                log = { logger?.invoke(it) },
                onSnapshot = ::handleSnapshot,
                onNavigateStop = ::handleStop,
            )
        client = created
        created.start()
        tickerHandler?.removeCallbacks(tick)
        ticker().postDelayed(tick, 1_000L)
    }

    /** 用于「关开关」与「进程收尾」。 */
    fun stop(clearHud: Boolean = true) {
        stopInternal(clearHud)
    }

    private fun stopInternal(clearHud: Boolean) {
        // 不用 ticker()：停止路径不该为了撤一个回调去创建 Handler（单测里那会崩）。
        tickerHandler?.removeCallbacks(tick)
        staleRestarts = 0
        val wasGuiding =
            synchronized(machine) {
                val guiding = machine.isGuiding
                machine.reset()
                guiding
            }
        client?.stop()
        client = null
        // 无论要不要清 HUD，媒体卡片的影子状态都该归零：留着它等于让覆盖层
        // 继续显示一份已经没人更新的转向（数据源都被关了）。
        guide = null
        if (clearHud && wasGuiding) dispatch(AmapNaviEmission.Clear)
    }

    private fun handleSnapshot(snapshot: AmapNaviSnapshot) {
        val now = System.currentTimeMillis()
        val emission =
            synchronized(machine) {
                machine.onNavigation(snapshot, now)
            }
        emission?.let { dispatch(it) }
    }

    private fun handleStop() {
        val now = System.currentTimeMillis()
        val emission =
            synchronized(machine) {
                machine.onStop(now)
            }
        emission?.let { dispatch(it) }
    }

    private fun dispatch(emission: AmapNaviEmission) {
        // 新引导帧说明数据源活了：重置 STALE 重建计数，下一轮静默仍可再救一次。
        if (emission is AmapNaviEmission.Guide) staleRestarts = 0
        // 先更新媒体卡片的影子状态，再交给仪表出口：反过来的话，出口若同步触发一次
        // 0x5001 重推，读到的还是上一帧的转向。
        guide = emission.toGuide()
        onEmission?.invoke(emission)
    }

    /**
     * 引导帧 → 媒体卡片文案 + 箭头。
     *
     * 转向名用 [CarLifeNaviGuide.label]（ICON 码表），**不要**换成
     * `NavigationDisplay` 的 turnName——那张表吃的是 CarLife protobuf raw 码，
     * 数值在 2..9 上与 ICON 撞车但语义不同，混用会把「左转」显示成「左后方」。
     *
     * 距离取 `remainDistance`（段剩余，到下一个动作的米数），不是 `totalDistance`
     * （全程剩余），与仪表 `0x5201` 的 `0x0a` 语义一致。
     */
    private fun AmapNaviEmission.toGuide(): NavigationDisplay.Guide? =
        when (this) {
            is AmapNaviEmission.Guide ->
                NavigationDisplay.Guide(
                    title = NavigationText.maneuver(turn.remainDistance, CarLifeNaviGuide.label(turn.nextTurn)),
                    road = turn.roadName,
                    artwork = ManeuverArrow.render(turn.nextTurn),
                )

            AmapNaviEmission.Clear -> null
        }

    /**
     * 自检文案：设置面板直接显示这几行。
     *
     * 三种失败要能一眼分开——「没装」「被系统拦」「装了但没开始导航」，
     * 否则用户只会看到"没反应"然后来问。
     */
    fun diagnose(): String {
        val c = client
        return buildString {
            append("开关：").append(if (enabled) "已开启" else "已关闭")
            if (c == null) {
                append("\n状态：未启动")
                return@buildString
            }
            append("\n高德版本：").append(c.amapVersion ?: "未安装")
            append("\n连接状态：").append(
                when (c.state) {
                    AmapLinkClient.State.IDLE -> "空闲"
                    AmapLinkClient.State.BINDING -> "绑定中"
                    AmapLinkClient.State.AUTHENTICATING -> "认证中"
                    AmapLinkClient.State.REGISTERED -> "已注册（可收数据）"
                    AmapLinkClient.State.FAILED -> "失败"
                },
            )
            append("\n已收导航帧：").append(c.frameCount).append(" 帧")
            val last = c.lastFrameAt
            if (last > 0L) {
                val age = System.currentTimeMillis() - last
                append("（最后 ").append(age / 1000).append(" 秒前）")
            }
            // 媒体卡片那张图读不出来时，仪表有箭头、屏幕只剩文字，外观像"车机不认这张图"。
            // 把张数摆在这里，省得再去翻日志。
            append("\n媒体卡片图标：").append(ManeuverArrow.loadedCount)
                .append('/').append(ManeuverArrow.expectedCount).append(" 张")
            c.lastError?.let { append("\n最近错误：").append(it) }
            append("\n结论：").append(c.diagnose())
        }
    }
}
