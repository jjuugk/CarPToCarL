package io.github.shaonialife.carptocar.bt

import android.content.Context

/**
 * 「建链期不主动断开蓝牙音频配置文件」开关（默认关 = 保持原行为）。
 *
 * ## 默认行为（关）
 *
 * 建链期有三处会主动断开目标车机的 HFP/A2DP：
 * 1. [BtProfiles.guardAudioProfiles]（BT-GUARD）：每 1.5 秒查一次，车机把音频配置文件
 *    拉回来就再断一次，最多 3 次、按 3/6/12 秒退避、5 分钟封顶；
 * 2. 出站连接重试：先直接拨 `…caff`，失败后断一次配置文件再拨第二次；
 * 3. 配对完成后：系统/车机常会自动连上 HFP/A2DP，配对回调里立即与 1.5s 后各断一次。
 *
 * 宝马（MGU-81 / NBT Evo）、别克这类车机在 HFP/A2DP 在线时不开放 iAP2 服务窗口，
 * 上面几处是它们能建链的前提，所以默认必须保留。
 *
 * ## 打开后
 *
 * 两处都跳过：建链期**完全不碰**车机的音频配置文件。
 *
 * 依据是 2026-11-26 的 MBUX issue（NTG7，`MB_D-M-6`）：守卫在车机还没连入时反复断
 * HFP/A2DP，车机把整条 ACL 拽掉（`0x13 REMOTE_USER_TERMINATED`），HyperOS 蓝牙栈在
 * 这种异常断链下丢链路密钥，下一轮车机拿旧密钥加密失败 → `bond-loss reason: 2`，
 * 十几分钟就要重新配对一次。日志里守卫自己也写了「再踢只会把 ACL 一起打掉」。
 *
 * 开关做成全局而不是按车型门控：MBUX 之外的「刚连上就断 / 配对反复失效」也用得上，
 * 而品牌串（`Mercedes-Benz` / `MBUX 35885`）在建链早期还拿不到，车型判定不可靠。
 */
object BtAudioHoldOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "bt_audio_hold_v1"

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled = prefs(context).getBoolean(KEY, false)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val label: String
        get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "已开启：建链期完全不主动断开车机的 HFP/A2DP（BT-GUARD、出站重试、配对后断开都跳过）。" +
                    "奔驰 MBUX、或出现「刚连上就断 / 配对反复丢失」的车机可保持开启。下次连接起效。"
            } else {
                "默认关（原行为）：建链期会断开目标车机的音频配置文件，宝马/别克等车机不打开 " +
                    "iAP2 窗口时需要它。奔驰 MBUX 等「刚连上就断、配对反复失效」的车机，尝试打开此项。下次连接起效。"
            }
}
