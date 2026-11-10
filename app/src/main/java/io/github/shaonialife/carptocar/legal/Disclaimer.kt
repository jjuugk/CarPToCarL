package io.github.shaonialife.carptocar.legal

import android.content.Context

/**
 * 免责声明的接受状态与放行规则。
 *
 * 用**版本号**而非布尔值记录：分享出去后文案必然迭代（新增测试车型、补充条款），
 * 把 [CURRENT_VERSION] +1 即可让老用户重新确认一次；布尔值只能靠卸载重装才会再弹。
 */
object DisclaimerPrefs {
    /**
     * 当前免责声明版本。**修改 disclaimer_content 文案时必须同步 +1**——
     * 用户接受的是「当时那一版」内容，条款变了就该重新确认。
     *
     * v2（2026-10-18）：应用改名 CarPToCarL，补「非官方」「非 MFi」与 MIT 开源说明。
     * v3（2026-11-10）：适配 ICCOA Car+ 劫持，更名为 CarPToCar+ICCOA，更新测试机型（一加Ace7Pro / ColorOS 17），移除 Shizuku 权限说明。
     * v4（2026-11-10）：说明一加单机 Car+ 的 Root 要求与音频授权范围。
     */
    const val CURRENT_VERSION = 4

    private const val PREFS = "disclaimer"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    fun acceptedVersion(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun hasAcceptedCurrent(context: Context): Boolean = acceptedVersion(context) >= CURRENT_VERSION

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            .apply()
    }

    /** 仅测试/诊断用：撤销接受（下次启动会重新弹）。 */
    fun reset(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCEPTED_VERSION)
            .apply()
    }
}

/**
 * 「接受」按钮的可用条件（纯函数，可单测）。
 *
 * 三个条件同时成立才放行：
 * 1. 倒计时结束（[countdownDone]）——强制用户有时间意识到这是安全相关声明；
 * 2. 内容未溢出（[contentScrollable]=false），或已滑到底（[scrolledToBottom]）——
 *    长文案必须真的看得到末尾，不能靠「内容是否溢出」蒙混；
 * 3. （由调用方保证）用户未点「放弃使用」。
 *
 * 注意：倒计时与滚动是**并列**条件，不是串行——否则长文案用户滑 30 秒后
 * 还要再等 10 秒，体验很差。
 */
internal fun canAcceptDisclaimer(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
): Boolean = countdownDone && (!contentScrollable || scrolledToBottom)

/**
 * 是否还需要展示启动时的强制声明：本机没有接受过**当前版本**。
 */
internal fun needsDisclaimerAtLaunch(
    acceptedVersion: Int,
    currentVersion: Int,
): Boolean = acceptedVersion < currentVersion

/**
 * 底部提示文案（让用户知道「为什么按钮点不动」）。返回 null 表示可以点。
 */
internal fun disclaimerBlockReason(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
    remainingSeconds: Int,
): String? =
    when {
        !countdownDone && contentScrollable && !scrolledToBottom ->
            "请滑动阅读至底部，并等待 $remainingSeconds 秒"

        !countdownDone -> "请等待 $remainingSeconds 秒后接受"
        contentScrollable && !scrolledToBottom -> "请滑动阅读至底部"
        else -> null
    }
