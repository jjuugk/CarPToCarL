package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.video.VideoSender
import java.util.concurrent.Executors

/**
 * 资源归属 → 屏幕/音频流启停，全部串行在一条工作线程上：事件读线程要立即应答车机的
 * `changeModes`，不能等 SETUP/RECORD/TEARDOWN 这些阻塞 RTSP。
 *
 * 车机借走屏幕（倒车影像/雷达 PDC）不是会话断开：只 `TEARDOWN` 屏幕流，屏幕归还或收到
 * `requestUI` 后重新 `SETUP` + `RECORD` 恢复画面。主音频同理：手机持有且有播放意图才起音频流，
 * 车机接管/静音借走时退役，归还后按播放意图恢复。
 */
internal class CarPlayResourceStreams(
    private val client: AirPlayClient,
    private val sessionKey: ByteArray?,
    private val displayUuid: String?,
    private val audioIntent: CarPlayAudioIntent,
    initialSender: VideoSender?,
    private val createSender: (AirPlayClient.ScreenStreamInfo) -> VideoSender,
    private val onScreenPaused: () -> Unit,
    private val onScreenResumed: (VideoSender) -> Boolean,
    /** true = 起音频流并激活，false = 退役；返回此后音频流是否仍在推。 */
    private val onAudioOwnership: (Boolean) -> Boolean,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
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

    /** 车机持有主屏、视频暂停中（控制会话仍在）。 */
    @Volatile
    var screenPaused = initialSender == null
        private set

    /** 首次 RECORD 与挂载完成后接管屏幕流。 */
    fun activate(initialScreenAttached: Boolean) =
        post {
            screenStarted = true
            screenStreaming = initialScreenAttached && sender?.isRunning == true
            if (sender == null) onScreenPaused()
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
            published = client.acquireScreenResource(screenRequestFromCarUi)
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
        if (screenNegotiated && !retireScreen(if (owned) "屏幕未挂载" else "让出主屏")) return
        if (owned) resumeScreen()
    }

    private fun retireScreen(reason: String): Boolean {
        log("MODE: 停止屏幕流（$reason）")
        screenPaused = true
        screenStreaming = false
        onScreenPaused()
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
        if (!onScreenResumed(replacement)) {
            retireScreen("屏幕恢复挂载被拒")
            return
        }
        screenStreaming = true
        screenPaused = false
    }

    private fun applyAudio() {
        val media = audioIntent.media
        val navigation = audioIntent.navigation
        // 媒体按用户优先级永久取得；导航按后台优先级临时借：可借原车收音机，冲不破车机静音/通话
        // 车机正在借用时不申请：`claim()` 会重置借用栈（车机那边的借用记录就没了），
        // 结果是我们在它借用的那一秒里把它抢回来，它归还时反而找不到自己的借用。
        // 借用结束后下一次 reconcile 自然会再申请。
        if (!client.carBorrowsAudio &&
            (media || navigation) &&
            (!client.phoneOwnsAudio || (media && client.phoneBorrowsAudio))
        ) {
            val acquired =
                client.acquireAudioResource(
                    if (media) AirPlayResourceState.PRIORITY_USER else AirPlayResourceState.PRIORITY_BACKGROUND,
                    temporary = !media,
                )
            log("AUDIO-INTENT: media=$media navigation=$navigation acquired=$acquired ${client.resourceDescription}")
        }
        if (!media && !navigation && client.phoneBorrowsAudio) {
            // 导航播报结束：先停音频流，再把临时借用的主音频还给车机
            if (audioActive) audioActive = onAudioOwnership(false)
            if (!audioActive) client.releaseAudioBorrow()
        }
        // 车机**只是借用/静音**主音频（方向盘语音键、原车静音键：transferType=3，
        // 带 unborrowConstraint）时，流一律不拆。
        //
        // 2026-11-17 实车（宝马 G38）证据：按一次语音键 →
        //   TEARDOWN 18:33:47.469 → 重新 SETUP 18:33:49.502 → 出声 18:33:49.630
        // 中间 2.2 秒全静音；连按三次就断三次。而车机借用本身只有 1.5~9 秒，
        // 拆流重建和借用时长是同一个量级——等于按一次键白丢一段声音。
        //
        // 借用期间保持流还有第二个好处：RTP 时间轴不断、锚点不用重建，车机那边
        // 也不需要重新缓冲；我们把媒体 PCM 清空（SessionController 的
        // handleCarAudioTransfer 已经做了），泵线程继续发静音帧，车机归还后
        // 下一帧立刻有声。
        //
        // 判据必须同时要求「手机上确实有一条在推的流」（audioActive）：车机从一开机
        // 就借走音频（例如原车电话）时，我们本来就没起流，这里不能凭空造一条。
        val keepWhileCarBorrows = audioActive && client.carBorrowsAudio && !client.phoneOwnsAudio
        val wanted =
            keepWhileCarBorrows || (client.phoneOwnsAudio && (audioActive || media || navigation))
        if (wanted != audioActive) audioActive = onAudioOwnership(wanted)
    }

    private companion object {
        const val TYPE_SCREEN = 110L
    }
}
