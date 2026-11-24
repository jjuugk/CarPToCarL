package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.audio.AudioCompatExperiment
import io.github.shaonialife.carptocar.video.VideoSender
import java.util.concurrent.Executors

/**
 * 资源归属 → 屏幕/音频流启停，全部串行在一条工作线程上：事件读线程要立即应答车机的
 * `changeModes`，不能等 SETUP/RECORD/TEARDOWN 这些阻塞 RTSP。
 *
 * 车机借走屏幕（倒车影像/雷达 PDC）不是会话断开：默认按 `TEARDOWN` 屏幕流、归还或收到
 * `requestUI` 后重新 `SETUP` + `RECORD` 恢复画面；**实验开关**（[keepScreenStreamOnTake]）
 * 打开时改为保留视频流、只承认归属不是手机，归还后补关键帧即可——车机反复改判时不再付
 * 每次都重建的代价（别克 6369 实测每轮 100~170ms 就被夺，一帧留不住）。
 * 主音频同理：手机持有且有播放意图才起音频流，车机接管/静音借走时退役，归还后按播放意图恢复。
 */
internal class CarPlayResourceStreams(
    private val client: AirPlayClient,
    private val sessionKey: ByteArray?,
    private val displayUuid: String?,
    private val audioIntent: CarPlayAudioIntent,
    initialSender: VideoSender?,
    private val createSender: (AirPlayClient.ScreenStreamInfo) -> VideoSender,
    /** [streamKept] = true 表示实验开关生效、视频流被保留（调用方不要 detach 采集）。 */
    private val onScreenPaused: (Boolean) -> Unit,
    /** [soft] = true 表示沿用现有视频流（没有重新 SETUP），只需补关键帧与重武装 HID。 */
    private val onScreenResumed: (VideoSender, Boolean) -> Boolean,
    /** true = 起音频流并激活，false = 退役；返回此后音频流是否仍在推。 */
    private val onAudioOwnership: (Boolean) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
    /** 本轮会话是否具备可发送的导航独立音频流能力；false 时仅为导航意图不借用主音频。 */
    private val canPlayNavigationAudio: () -> Boolean = { true },
    /** 单调时钟（只用于日志里的「存活多久 / 等了多久」，可注入便于单测）。 */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-resource-streams").apply { isDaemon = true }
        }

    @Volatile
    private var closed = false

    // 以下状态只在工作线程上读写
    private var sender: VideoSender? = initialSender
    private var screenNegotiated = initialSender != null
    private var screenStreaming = false
    private var screenStarted = false
    private var audioStarted = false
    private var audioActive = false
    private var screenRequested = initialSender == null
    private var screenRequestFromCarUi = false

    /**
     * 实验开关下的「软挂起」：归属已不是手机，但视频流没拆、编码器还在跑。
     *
     * 与 [screenPaused] 的区别：后者 = 流已 TEARDOWN，恢复要重新 SETUP；这里只等归属回来。
     */
    private var softHold = false

    // 诊断计数（只在工作线程上读写）：一轮会话里「让屏 → 等 → 起流 → 又被抢」到底循环了几次、
    // 每次送流实际活了多久。没有这两个数就只能靠人工比对时间戳，且看不出「越抢越快」。
    private var screenLosses = 0
    private var screenResumes = 0
    private var streamStartedAtMs = 0L
    private var waitStartedAtMs = 0L
    /** 仅在导航流不可用且跳过借用时记录一次，避免每条播报包刷屏。 */
    private var navBorrowSkippedLogged = false

    /** 车机持有主屏、视频暂停中（控制会话仍在）。 */
    @Volatile
    var screenPaused = initialSender == null
        private set

    /** 首次 RECORD 与挂载完成后接管屏幕流。 */
    fun activate(initialScreenAttached: Boolean) =
        post {
            screenStarted = true
            screenStreaming = initialScreenAttached && sender?.isRunning == true
            if (sender == null) onScreenPaused(false) else streamStartedAtMs = nowMs()
        }

    /** 建链阶段的音频处理完成后接管音频流；[initialAudioActive] = 音频已随 RECORD 激活。 */
    fun activateAudio(initialAudioActive: Boolean) =
        post {
            audioStarted = true
            audioActive = initialAudioActive
        }

    /** 申请主屏；[fromRequestUi] = 车机 `requestUI` 主动请回手机画面。 */
    fun requestScreen(fromRequestUi: Boolean = false) =
        post {
            screenRequested = true
            if (fromRequestUi) screenRequestFromCarUi = true
        }

    /** 车机改判资源或源端播放意图变化后对齐屏幕/音频流。 */
    fun reconcile() = post {}

    fun close() {
        closed = true
        screenPaused = true
        worker.shutdownNow()
    }

    private fun alive(): Boolean = !closed && !client.cancelled && client.controlSessionAlive

    private fun post(update: () -> Unit) {
        if (closed) return
        worker.execute {
            update()
            if (!alive()) return@execute
            try {
                if (screenStarted) applyScreen()
                if (audioStarted && alive()) applyAudio()
            } catch (error: Exception) {
                if (alive()) fail("资源切换失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun fail(reason: String) {
        log("MODE: 资源流故障 - $reason")
        onFailure(reason)
    }

    private fun applyScreen() {
        var published = false
        if (screenRequested && !client.phoneOwnsScreen) {
            // 实验开关打开时「尊重借用」：车机说有人在借（转向影像/倒车）就不去抢。
            // 旧行为 overrideBorrow=true 会在借用期间直接冲掉借用，车机随即把它夺回去，
            // 我们白付一轮 SETUP/RECORD。
            published = client.acquireScreenResource(screenRequestFromCarUi, respectBorrow = keepScreenStreamOnTake)
            log("MODE: 申请主屏 fromRequestUi=$screenRequestFromCarUi acquired=$published")
        }
        screenRequested = false
        screenRequestFromCarUi = false
        val owned = client.phoneOwnsScreen
        if (owned && screenStreaming) {
            screenPaused = false
            return
        }
        if (!owned && !screenNegotiated) return
        // 车机改判后复述一次当前归属（已由本机申请发布过的除外）
        if (!published) client.republishModes()
        if (softHoldScreen(owned)) return
        if (screenNegotiated && !retireScreen(if (owned) "屏幕未挂载" else "让出主屏")) return
        if (owned) resumeScreen()
    }

    /**
     * 实验开关 `keepScreenStreamOnTake`：车机占屏/还屏时**不拆流**，只切换归属与计数。
     *
     * @return true = 已按软挂起/软恢复处理完，调用方不要再走 TEARDOWN/SETUP。
     */
    private fun softHoldScreen(owned: Boolean): Boolean {
        if (!keepScreenStreamOnTake) {
            // 开关在会话中途被关掉：软挂起的流要退回常规路径，由调用方拆干净
            if (softHold) {
                log("MODE: 保留视频流开关已关闭——软挂起退回常规让屏")
                softHold = false
            }
            return false
        }
        val current = sender
        if (current == null || !current.isRunning || !screenNegotiated) return false
        if (!owned) {
            if (softHold) return true
            softHold = true
            screenPaused = true
            screenStreaming = false
            waitStartedAtMs = nowMs()
            screenLosses++
            val aliveMs = if (streamStartedAtMs == 0L) -1L else nowMs() - streamStartedAtMs
            log(
                "MODE: 车机占用主屏——保留视频流（实验开关：不 TEARDOWN、不停编码器）" +
                    " · 本轮第 $screenLosses 次让屏" +
                    if (aliveMs >= 0) " · 这次送流已活 ${aliveMs}ms" else "",
            )
            onScreenPaused(true)
            return true
        }
        if (!softHold) return false
        softHold = false
        screenPaused = false
        screenStreaming = true
        val waited = if (waitStartedAtMs == 0L) -1L else nowMs() - waitStartedAtMs
        waitStartedAtMs = 0L
        streamStartedAtMs = nowMs()
        screenResumes++
        log(
            "MODE: 主屏归还——沿用现有视频流（实验开关）· 补关键帧 + 重武装 HID" +
                if (waited >= 0) " · 车机占屏 ${waited}ms" else "",
        )
        if (!onScreenResumed(current, true)) {
            log("MODE: 沿用现有视频流被拒——退回重申请屏幕流")
            screenStreaming = false
            softHold = false
            if (!retireScreen("沿用视频流被拒")) return true
            resumeScreen()
        }
        return true
    }

    private fun retireScreen(reason: String): Boolean {
        // 本轮流活了多久：车机「给屏→抢屏」的间隔就藏在这个数里。反复在 100~300ms
        // 被杀说明是它一收到我们的帧就出事，而不是用户切走。
        val aliveMs = if (streamStartedAtMs == 0L) -1L else nowMs() - streamStartedAtMs
        streamStartedAtMs = 0L
        waitStartedAtMs = nowMs()
        softHold = false
        screenLosses++
        log(
            "MODE: 停止屏幕流（$reason）· 本轮第 $screenLosses 次让屏" +
                if (aliveMs >= 0) " · 这次送流活了 ${aliveMs}ms" else "",
        )
        screenPaused = true
        screenStreaming = false
        onScreenPaused(false)
        sender?.stop()
        sender = null
        screenNegotiated = false
        if (!client.teardownStream(TYPE_SCREEN, displayUuid)) {
            if (alive()) fail("主屏资源让出时 TEARDOWN 失败")
            return false
        }
        return alive()
    }

    private fun resumeScreen() {
        val screen = client.runScreenStream(sessionKey, displayUuid) ?: return fail("主屏资源归还后 SETUP 失败")
        if (!alive()) return
        val replacement = createSender(screen)
        sender = replacement
        screenNegotiated = true
        if (!(replacement.openDataPort() && client.record() && replacement.startPassthrough())) {
            return fail("主屏资源归还后视频通道未能恢复")
        }
        if (!onScreenResumed(replacement, false)) {
            retireScreen("屏幕恢复挂载被拒")
            return
        }
        val waited = if (waitStartedAtMs == 0L) -1L else nowMs() - waitStartedAtMs
        waitStartedAtMs = 0L
        streamStartedAtMs = nowMs()
        screenResumes++
        log(
            "MODE: 主屏恢复 · 第 $screenResumes 次起流" +
                if (waited >= 0) " · 车机占屏 ${waited}ms" else "",
        )
        screenStreaming = true
        screenPaused = false
    }

    private fun applyAudio() {
        val media = audioIntent.media
        val rawNavigation = audioIntent.navigation
        val navSupported = !rawNavigation || canPlayNavigationAudio()
        if (rawNavigation && !navSupported && !media && !navBorrowSkippedLogged) {
            navBorrowSkippedLogged = true
            log("AUDIO-INTENT: 车机无可用导航独立音频流，跳过主音频临时借用（避免误静音原车音源）")
        } else if (navSupported) {
            navBorrowSkippedLogged = false
        }
        // 音频兼容实验 B：对所有车型生效，拨了就算。判据在 applyAudio 里现算——切换
        // 档位后下次 reconcile 就按新策略，不必重连。OFF 时与改动前逐字节一致。
        val plan =
            planAudioStep(
                media = media,
                navigation = rawNavigation,
                canPlayNavigationAudio = navSupported,
                phoneOwnsAudio = client.phoneOwnsAudio,
                phoneBorrowsAudio = client.phoneBorrowsAudio,
                borrowMediaAudio = AudioCompatExperiment.current.borrowAudio,
            )
        val acquirePriority = plan.acquirePriority
        if (acquirePriority != null) {
            val acquired =
                client.acquireAudioResource(
                    acquirePriority,
                    temporary = plan.acquireTemporary,
                )
            log(
                "AUDIO-INTENT: media=$media navigation=${plan.effectiveNavigation} " +
                    "acquired=$acquired ${client.resourceDescription}" +
                    if (plan.borrowMediaAudio) " · 音频兼容实验 B：媒体按临时借用申请" else "",
            )
        }
        if (plan.releaseBorrow) {
            // 导航播报结束：先停音频流，再把临时借用的主音频还给车机
            if (audioActive) audioActive = onAudioOwnership(false)
            if (!audioActive) client.releaseAudioBorrow()
        }
        val wanted = client.phoneOwnsAudio && (audioActive || media || plan.effectiveNavigation)
        if (wanted != audioActive) audioActive = onAudioOwnership(wanted)
    }

    internal data class AudioStepPlan(
        val effectiveNavigation: Boolean,
        val acquirePriority: Int?,
        val acquireTemporary: Boolean,
        val releaseBorrow: Boolean,
        /** 诊断标记：本次媒体申请走了音频兼容实验 B 的临时借用。 */
        val borrowMediaAudio: Boolean = false,
    )

    companion object {
        const val TYPE_SCREEN = 110L

        /**
         * 纯状态转移决策：根据播放意图、导航流可用性与当前归属决定是否借用/申请/归还主音频。
         *
         * 当车机无可用导航独立流（例如 101/default 仅声明当前未编码的 Opus）时，导航播报本身无法送出，
         * 此时若仍临时借用主音频只会把原车收音机/媒体掐成静音却无播报声，因此将有效导航意图视作 false。
         */
        internal fun planAudioStep(
            media: Boolean,
            navigation: Boolean,
            canPlayNavigationAudio: Boolean,
            phoneOwnsAudio: Boolean,
            phoneBorrowsAudio: Boolean,
            /**
             * 音频兼容实验 B：媒体主音频也走临时借用（优先级仍是 USER，保证能借到），
             * 而不是永久 claim。默认 false —— 所有既有调用与单测保持原行为。
             *
             * `borrowForPhone(USER)` 在永久归属是车机时设 `phoneBorrowing=true`，此时
             * `entity == ENTITY_PHONE`，[startAudioChannel] 的 `phoneOwnsAudio` 门禁照过，
             * 音频流照常建；区别只在 `modesChanged` 里永久归属留车机、多一条借用标记。
             */
            borrowMediaAudio: Boolean = false,
        ): AudioStepPlan {
            val effectiveNavigation = navigation && canPlayNavigationAudio
            val shouldAcquire =
                (media || effectiveNavigation) && (!phoneOwnsAudio || (media && phoneBorrowsAudio))
            val acquirePriority =
                when {
                    !shouldAcquire -> null
                    media -> AirPlayResourceState.PRIORITY_USER
                    else -> AirPlayResourceState.PRIORITY_BACKGROUND
                }
            val releaseBorrow = !media && !effectiveNavigation && phoneBorrowsAudio
            return AudioStepPlan(
                effectiveNavigation = effectiveNavigation,
                acquirePriority = acquirePriority,
                acquireTemporary = shouldAcquire && (!media || borrowMediaAudio),
                releaseBorrow = releaseBorrow,
                borrowMediaAudio = shouldAcquire && media && borrowMediaAudio,
            )
        }

        /**
         * 车机拿走主屏时保留视频流（实验开关，默认关；持久化见 `DisplayPrefs`）。
         *
         * 放静态字段是因为它要能**即时生效**（连线中拨开关就改变下一轮处置），
         * 与 `CarLifeHijack.adaptiveTouchDomain` 同一套做法。
         */
        @Volatile
        var keepScreenStreamOnTake: Boolean = false
    }
}
