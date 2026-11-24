package io.github.shaonialife.carptocar.carlife

import android.content.Context

/**
 * 「AMcar 式车机麦克风上行」实验开关（偏好 `amcar_car_mic_uplink_v1`，**默认关**）。
 *
 * 打开后才会做：
 * 1. 响应引擎的 `0x10024`，按 `/info` 挑 speechRecognition/LPCM24k 档开一路输入流；
 * 2. 解密车机麦 PCM、24k→16k；
 * 3. 用 `0x58001` 写回 CarLife VR(9242) 供语音助手识别。
 *
 * 单档单次、失败不重试、不拆会话。开关下次连接生效；会话中途关闭会立刻停输入流。
 * CarWith 侧「语音助手 → 麦克风来源」必须切到「车机」，否则引擎不发 `0x10024`。
 */
object AmcarMicUplinkOption {
    private const val PREFS = "amcar_car_mic_uplink_v1"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = false

    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    fun load(context: Context?) {
        val app = context?.applicationContext ?: return
        enabled =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun save(
        context: Context?,
        value: Boolean,
    ) {
        enabled = value
        val app = context?.applicationContext ?: return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
