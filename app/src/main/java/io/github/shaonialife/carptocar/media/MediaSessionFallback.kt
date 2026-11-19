package io.github.shaonialife.carptocar.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * 会话轮询兜底：通知监听器**没被系统绑上**时，照样读得到播放态、照样能直投媒体键。
 *
 * **为什么需要**：荣耀 MagicOS 上「授权还在（`enabled_notification_listeners` 里有本组件）
 * 但服务没被绑上（`dumpsys notification` 的 Live 段没有）」是常态，重装 APK 就会发生。
 * 这时 [MediaListenerService.onListenerConnected] 不回调，整个媒体链路是瞎的：车机按
 * 暂停 → 直投失败 → 只剩 CarLife 硬键 → **手机上的音乐根本不停**（2026-11-19 实车复现）。
 *
 * 2026-11-19 在 PTP-AN10 上做过三条通道的对照实验：
 * - `cmd notification allow_listener`（NMS 内部接口）→ 立刻恢复绑定，但只有 shell/Shizuku 能调；
 * - `settings put secure enabled_notification_listeners` → **不触发重绑**；
 * - `NotificationListenerService.requestRebind()` → 本 ROM 静默无效（代码里早就在调）。
 *
 * 所以唯一稳的做法是**不把功能押在「服务被绑上」这件事上**：
 * `MediaSessionManager.getActiveSessions()` 的准入看的是「通知使用权是否授予」（落在
 * settings 里，重装不丢），与「服务此刻有没有被绑」无关。这里每秒自己枚举一次会话，
 * 把 [MediaSessionsState.activeController] 填上，媒体键直投随之恢复。
 *
 * 监听器一旦被系统绑上，本对象立刻让位（见 [NotificationAccess.isConnected]），
 * 由服务的 1Hz 轮询独家负责，避免两份轮询相互覆盖。
 */
object MediaSessionFallback {
    private const val TICK_MS = 1_000L
    private const val SECURITY_LOG_MS = 60_000L

    @Volatile
    private var started = false

    /** 被系统拒绝后的退避截止时刻（elapsedRealtime）——权限真被撤时别每秒撞墙。 */
    @Volatile
    private var blockedUntil = 0L

    @Volatile
    private var lastDeniedLogAt = 0L

    /** 已接管过的会话包名（变化时才写一行日志，避免每秒刷屏）。 */
    @Volatile
    private var announcedPkg: String? = null

    /**
     * 连续「服务未连上」的轮数。
     *
     * 启动瞬间服务本来就要晚 1~2 秒才 `onListenerConnected`，这期间兜底会先跑起来——
     * 那是正常竞态，不该在日志里喊「监听器未绑定」。连续 3 轮（≈3s）都还没连上才认定
     * 是真的没绑，才写那行接管日志。
     */
    @Volatile
    private var missTicks = 0

    /** 兜底是否真的在出数据（看门狗据此决定要不要提示用户）。 */
    val isWorking: Boolean get() = announcedPkg != null

    /** 封面缓存：键 = 会话 + 歌名，只缓存 metadata 内嵌位图（uri 型不在第二套线程里解码）。 */
    private var artKey: String? = null
    private var art: android.graphics.Bitmap? = null

    /** 启动轮询（幂等；进程存活期间常驻）。 */
    fun start(
        context: Context,
        log: (String) -> Unit,
    ) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // 单次异常不能打死轮询线程（厂商 ROM 偶发 IllegalStateException）
                runCatching { tick(app, log) }
            }
        }, "media-fallback").apply { isDaemon = true }.start()
    }

    private fun tick(
        context: Context,
        log: (String) -> Unit,
    ) {
        // 服务已绑上：服务自己的轮询说了算，这里立刻让位
        if (NotificationAccess.isConnected) {
            missTicks = 0
            announcedPkg = null
            return
        }
        missTicks++
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < blockedUntil) return
        val manager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return
        val controllers =
            try {
                // 刻意不看 `isNotificationListenerAccessGranted`：它读的是 NMS **内部**状态，
                // 与「服务有没有被绑上」是两件事——真实故障态正是「内部状态=已启用、
                // 后台却没绑服务」，这时授权 API 说 true、绑定却不存在。
                // 直接试一次，成不成让系统自己回答，比任何本地标志都准。
                manager.getActiveSessions(MediaListenerService.componentName(context))
            } catch (e: SecurityException) {
                // 权限真被撤销了（不是没绑）：退避 30s，别每秒撞墙
                blockedUntil = now + 30_000L
                if (now - lastDeniedLogAt >= SECURITY_LOG_MS) {
                    lastDeniedLogAt = now
                    log("MEDIA: 兜底轮询被系统拒绝（${e.message}）——媒体键直投不可用")
                }
                return
            }
        // 剔除本应用自己的保活会话（同服务的 activeSessions 口径，别把自己的
        // 「车机音频直通」当成用户正在听的东西）
        val others = controllers.filterNot { it.packageName == context.packageName }
        val picked =
            others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: others.firstOrNull { it.metadata != null }
        MediaSessionsState.accessGranted = true
        MediaSessionsState.activeController = picked
        if (picked == null) {
            if (MediaSessionsState.nowPlaying != null) MediaSessionsState.publish(null)
            return
        }
        if (announcedPkg != picked.packageName && missTicks >= 3) {
            announcedPkg = picked.packageName
            log("MEDIA: 监听器未绑定 → 会话轮询兜底接管（${picked.packageName}），媒体键直投不受影响")
        }
        val meta = picked.metadata
        val state = picked.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        MediaSessionsState.publish(
            MediaSessionsState.NowPlaying(
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist =
                    meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                playing = playing,
                art = artFor(picked, meta),
                pkg = picked.packageName,
                positionMs = state?.position ?: 0L,
                playbackSpeed = if (playing) (state?.playbackSpeed ?: 1f) else 0f,
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                anchoredAtMs = android.os.SystemClock.elapsedRealtime(),
            ),
        )
    }

    /**
     * 封面：只取 metadata 内嵌位图，按「会话 + 歌名」缓存。
     *
     * uri 型封面（content:// 走解码线程）不在这里做——那需要第二套 IO 线程与缓存策略，
     * 而兜底路径只保证「能看能按」，封面缺失可接受；服务绑上后由服务路径补齐。
     */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
    ): android.graphics.Bitmap? {
        val key = "${controller.sessionToken}/${meta?.getString(MediaMetadata.METADATA_KEY_TITLE)}"
        if (key == artKey) return art
        artKey = key
        art =
            meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        return art
    }
}
