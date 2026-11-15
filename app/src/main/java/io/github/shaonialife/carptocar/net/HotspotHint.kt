package io.github.shaonialife.carptocar.net

/**
 * 英雄卡副标题里「正在加入车机热点」这类文案的事实校正。
 *
 * 背景（2026-11-15 用户反馈，1.2.46 / 1.2.48 / 1.2.50 都能碰到）：界面文案只在
 * 入网流程**返回之后**才由各分支重写，而系统 `onAvailable` 往往早好几秒——
 * 那晚 21:34:41 网络已经拿到 `172.16.222.105`，21:34:47 流程才结束；`WifiJoinGate`
 * 的 Wait 分支最长还要再等 40 秒。这段空窗里网络完全可用、车机的 ctrl 邀请甚至
 * 已经打进来，唯独这句话还写着「正在加入」。
 *
 * 所以文案要做两层校正，两个判据都在这里，便于单测：
 *  1. [isJoining]：这句话是不是「**正在**加入热点」阶段的（正在加入 / 正在自动加入 /
 *     正在重试加入车机热点三种写法都算，结论类文案「未能加入…」不算）；
 *  2. [ready]：网络已经可用时该改成什么（带上本机 IP 更有信息量）。
 *
 * 只做字符串与判据，不碰 Android API——真正的「网络在不在」由调用方查。
 */
object HotspotHint {
    /** 三种入网文案的共同片段（「正在加入 / 正在自动加入 / 正在重试加入车机热点」）。 */
    private const val JOINING = "加入车机热点"

    /** 「正在」这一步的标记——用来把「未能加入车机热点」这类**失败**文案排除在外。 */
    private const val IN_PROGRESS = "正在"

    /**
     * 这句话是否还停在「正在加入车机热点」阶段。
     *
     * 必须同时含 [IN_PROGRESS]：`未能加入车机热点——下一轮连接会自动再试` 里也有
     * [JOINING]，但那是**失败**结论，改成「已连上」会自相矛盾。
     */
    fun isJoining(hint: String?): Boolean =
        hint != null && hint.contains(IN_PROGRESS) && hint.contains(JOINING)

    /**
     * 已连上热点时的阶段文案。
     *
     * @param ip 本机在该热点上的 IPv4；取不到（权限不足 / DHCP 还没下发地址）时省略括号
     */
    fun ready(ip: String?): String =
        if (ip.isNullOrBlank()) {
            "已连上车机热点，正在等车机应答…"
        } else {
            "已连上车机热点（$ip），正在等车机应答…"
        }
}
