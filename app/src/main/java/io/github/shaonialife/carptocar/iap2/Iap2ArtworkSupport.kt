package io.github.shaonialife.carptocar.iap2

/**
 * 「这台车到底收不收封面」的**进程级记忆**。
 *
 * ## 为什么需要它
 *
 * `0x5001 NowPlayingUpdate` 里的 `0x001A` 不是图片，而是一句承诺：「编号 N 的封面随后
 * 会走文件传输会话送来」。车机收到之后会在 session 12 上等那张图。
 *
 * 但车机**可以完全不理会**：2026-11-15 奥迪 MIB3（`Audi_MMI_3232` / `AUDI 4930`）实测，
 * 笔记本式地连发 19 次 `SETUP`（每行歌词一次，见 [ArtworkDedupe] 修掉的 churn），
 * 车机一条 `START` 都没回，也没有 `SUCCESS`/`FAILURE` —— 它既不收也不拒。
 * 结果就是每一帧 `0x5001` 都替车机挂上一张**永远不会到**的图，媒体卡看着像在等封面。
 *
 * ## 语义
 *
 * 判据在 [io.github.shaonialife.carptocar.airplay.VirtualIap2Link]：一条 `SETUP` 发出后
 * 超过超时窗口还没有 `START`，就认定这台车不取封面，此后 `0x5001` **不再带 `0x001A`**
 * （宁可不显示封面，也别让它挂着一张永远不来的图）。
 *
 * 记忆是**一轮连接一次**：换成 CarPlay 隧道后再试一遍——同一条车机换个链路，行为可能不同。
 * 所以 [reset] 由 `SessionController.resetNowPlayingDedupe()` 在每轮开始时调用。
 *
 * 纯状态，无线程依赖：读写都加锁，任何线程可调。
 */
object Iap2ArtworkSupport {
    /** 判定这台车不取封面的理由；null = 还没判过（默认：照常声明封面编号）。 */
    @Volatile
    private var unsupportedReason: String? = null

    /** 该不该在 `0x5001` 里带封面编号。 */
    val unsupported: Boolean get() = unsupportedReason != null

    /** 判定理由（日志/诊断用）；null = 没判过。 */
    val reason: String? get() = unsupportedReason

    /**
     * 标记「这台车不取封面」。
     *
     * @return true = **这次**才第一次标记（调用方据此只打一行日志，别每拍刷屏）
     */
    fun markUnsupported(reason: String): Boolean =
        synchronized(this) {
            if (unsupportedReason != null) return false
            unsupportedReason = reason
            true
        }

    /** 新一轮连接：清掉记忆，重新试一次（换链路/换车/换固件都可能不一样）。 */
    fun reset() {
        synchronized(this) { unsupportedReason = null }
    }
}
