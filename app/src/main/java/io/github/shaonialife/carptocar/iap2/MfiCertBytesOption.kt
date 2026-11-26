package io.github.shaonialife.carptocar.iap2

import android.content.Context

/**
 * MFi 挑战长度「按证书参数字节数猜」的兜底开关（默认关闭）。
 *
 * 借鉴 hypercar `MfiChallenge.kt`：
 * ```
 * CERTIFICATE_PARAM_THRESHOLD_BYTES = 644
 * if (AA01 参数总字节 > 644) 20 else 32
 * ```
 * 它注释说明这个阈值来自 xiap2：挑战长度由 `0xAA01` 证书**参数总长**（含 4 字节 TLV
 * 参数头）决定，而不是按 X.509 版本号。
 *
 * 我们这边已经有更精细的 [MfiCertificateLength]——从协议核逆向回灌的 Apple OID 规则
 * （32 / 16 / 20 三档，命中即首选）。但那一套**只认 Apple 私有 OID**：证书里没有
 * `1.2.840.113635.100.6.36 / .6.71.1` 这类扩展时返回 null，只能退回「基线顺序 + 按车机记忆」
 * 逐档试探——新车型第一次连接、记忆还是空的时候，最坏要白等 3+6+3 秒。
 *
 * 打开后：OID 识别失败时，用 644 字节阈值给一个首选（它同样只**插到队首**，
 * 失败仍按原顺序完整回退，见 [MfiCertificateLength.prefer]），把首次连接的盲试成本
 * 从「最坏 12 秒」压到「最多错一档」。
 *
 * 关闭时识别不出就保持 null，完全走原来的基线 + 记忆试探，行为与改动前一致。
 */
object MfiCertBytesOption {
    private const val PREFS_NAME = "settings"
    private const val KEY = "mfi_cert_bytes_v1"

    /** 进程内当前值。启动时 [load] 一次；界面拨动时 [save] 立即改字段。 */
    @Volatile
    var enabled: Boolean = false
        private set

    fun load(context: Context) {
        enabled =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
    }

    fun save(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }

    val label: String
        get() = if (enabled) "开" else "关"

    val note: String
        get() =
            if (enabled) {
                "已开启：证书里没有 Apple OID 时，按参数总长 >644B 猜 20B、否则猜 32B 作首选。" +
                    "猜错会自动回退原来的逐档试探。下次连接起效。"
            } else {
                "默认关：只用证书 OID 识别 + 按车机记忆。新车型首连可能多等几秒（逐档试探）。" +
                    "若某台新车每次首连都慢，可开启此项试试。下次连接起效。"
            }
}
