package io.github.shaonialife.carptocar.bt

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * 本机上「某台车机确实走通过 B233 协议」的观察结果。
 *
 * 蓝牙名车主可以改，所以只存配对地址的单向哈希作为本机偏好键，绝不上传。
 * 这是**路由线索，不是身份认证**：只有 0x1D01 身份闸门通过、且完整走通
 * 0x4301 之后才会记下 B233，下次该地址再连入就直接走专用流程。
 */
object ReceiverCompatibilityMemory {
    private const val PREFS = "receiver_protocol_observations_v1"
    private const val PREFIX = "observed_b233_"
    private val addressPattern = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** 地址归一化后的稳定键；非法地址返回 null（不写任何东西）。 */
    fun preferenceKey(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.US) ?: return null
        if (!addressPattern.matches(normalized)) return null
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun wasB233Observed(
        context: Context?,
        address: String?,
    ): Boolean {
        val key = preferenceKey(address) ?: return false
        val app = context?.applicationContext ?: return false
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    /** 记下「该地址走通了 B233」；已记录过返回 false（调用方据此避免重复打日志）。 */
    fun rememberB233(
        context: Context?,
        address: String?,
    ): Boolean {
        val key = preferenceKey(address) ?: return false
        val app = context?.applicationContext ?: return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}
