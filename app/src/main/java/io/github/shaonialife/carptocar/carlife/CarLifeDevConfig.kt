package io.github.shaonialife.carptocar.carlife

import android.content.Context
import android.os.Environment
import android.util.Base64
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CarLife 引擎的画质参数，走官方调试通道写入。
 *
 * 手机端 `DeveloperVideoConfigs.isDevelopDebug()` 只判断 `/sdcard/BaiduCarlife/vehicle`
 * 这个文件存不存在；存在时 `isDevelopDebug()` 返回 true，随后
 * `getMediaFormatFormat()` 在**最后一步**调用 `readConfigFile()`，把
 * `/sdcard/BaiduCarlife/video_config.json` 里的每个键原样并进编码器 MediaFormat。
 *
 * **但实测这条通道对码率/帧率是无效的**（见 [CONFIG_JSON] 的注释）：键确实被读进去了
 * （否则 `prepend-sps-pps-to-idr-frames` 不会把编码器搞挂），但 CarLife 之后又用自己
 * 的档位把码率覆盖掉。真正能改变码流的是协议层的
 * [CarLifeProtocol.I_FRAME_INTERVAL_DEFAULT]。
 *
 * 保留它的唯一理由是 i-frame-interval，以及方便以后继续探测——不是因为它真能提画质。
 */
object CarLifeDevConfig {

    private const val DIR = "/sdcard/BaiduCarlife"
    private const val FLAG_FILE = "vehicle"
    private const val CONFIG_FILE = "video_config.json"

    /**
     * 只放实测过「确实有帧、且对观感有利」的键。
     *
     * **实测（2026-10-24）这条通道对码率完全无效**：把 `bitrate` 设成 100000（0.1Mbps）
     * 与 8000000（8Mbps），实测输出码率**完全一样**（画面动时 1.06Mbps、静止 0.17Mbps）——
     * 说明 CarLife 在 `readConfigFile()` 之后又用自己的档位覆盖了码率。
     * 所以这里**不再写 bitrate / bitrate-mode**，写了也只是一厢情愿；
     * 真正会改变码流的是协议层的 [CarLifeProtocol.I_FRAME_INTERVAL_DEFAULT]。
     *
     * **绝对不要加 `prepend-sps-pps-to-idr-frames`。** 真机逐个键实测（.tools/probe_keys.sh）：
     * 单独喂这个键时，手机照样回 `MD_VIDEO_ENC_INIT 1920x1080@20` 并「启动推流」，
     * 但**一帧都不出**——编码器配置被它弄挂了，且没有任何报错，只能靠「几秒无帧」发现。
     * 加它的本意是让每个 IDR 都带 SPS/PPS，但这件事我们已经在 `CarLifeHijack.ensureSpsPps`
     * 里用软件方式做了（留存 SPS/PPS 后前置到裸 IDR），不需要动编码器，也就不必冒这个险。
     */
    private val CONFIG_JSON = """
        {
          "i-frame-interval": 1
        }
    """.trimIndent() + "\n"

    /** 是否已经拿到「所有文件访问」权限（写 /sdcard 根下目录必须要它）。 */
    fun hasPermission(): Boolean = runCatching { Environment.isExternalStorageManager() }
        .getOrDefault(false)

    /**
     * 写好 CarLife 调试开关 + 画质配置。
     *
     * @return 给运行日志用的一句话；失败时返回原因。
     */
    fun provision(): String {
        if (!hasPermission()) {
            return "CARLIFE: 画质配置跳过 —— 未授予「所有文件访问」权限"
        }
        return runCatching {
            val dir = File(DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                return "CARLIFE: 画质配置失败 —— 无法创建 $DIR"
            }
            val flag = File(dir, FLAG_FILE)
            if (!flag.exists()) flag.writeText("dev\n")
            val cfg = File(dir, CONFIG_FILE)
            val wanted = CONFIG_JSON
            if (!cfg.exists() || cfg.readText() != wanted) cfg.writeText(wanted)
            "CARLIFE: 画质配置已写入 $DIR（i-frame 1s）；实测码率/帧率由引擎决定，此配置不保证提升"
        }.getOrElse { e ->
            "CARLIFE: 画质配置失败 —— ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 撤销调试通道（删除开关文件），让 CarLife 回到自己的默认档位。 */
    fun revoke(): String = runCatching {
        val flag = File(DIR, FLAG_FILE)
        val removed = flag.exists() && flag.delete()
        if (removed) "CARLIFE: 已移除调试开关，CarLife 恢复默认档位（需重启 CarLife+）" else "CARLIFE: 调试开关本来就不存在"
    }.getOrElse { e -> "CARLIFE: 移除失败 —— ${e.message}" }

    /** 供 UI 提示用：权限被拒时引导到系统设置页。 */
    @Suppress("unused")
    fun requiresStorageAccess(context: Context): Boolean = !hasPermission() && context.packageName.isNotEmpty()

    // ---------------------------------------------------------------------
    // 自定义分辨率（ratio.json）—— 逆向自引擎 VideoResolution 类，诊断/实测用。
    //
    // 引擎 `getResolution()` 决策链：getUserResolutionSwitch(默认 true) → getUserResolution()
    // （读 ratio.json 的 user_resolution_width/heigh，要求 user_cn == 当前渠道号）→ 白名单
    // → 默认 1280×720。但 `isBoxSupportResolution` 白名单只认 12 档：
    //   768x432/448, 832x480, 848x480, 1024x384/576/608, 1280x480/720/768, 1920x720/1080, 1920x1140
    // 非白名单（如别克大连屏 1972×520）会被 "not support resolution, reset2Default!" 降回默认。
    // 所以「写车机分辨率」对超宽屏无效；本工具只对白名单内档位（如 1920×720）有意义。
    // ---------------------------------------------------------------------

    /** 引擎 ratio.json 的加密 key（DES 8 字节，EncodeUtil 源码实证）。 */
    private const val RESOLUTION_KEY = "bdcl2021"

    /** 引擎 ratio.json 的 DES-CBC IV（VideoResolution.<clinit> 的 VIDEO_RESOLUTION_IV）。 */
    private val RESOLUTION_IV = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    /**
     * 生成引擎 ratio.json 的**加密后文件内容**（DES/CBC/PKCS5 + Base64 + "aZ" 前缀）。
     *
     * 纯函数、无副作用，可单测；写入引擎私有目录需 shell/root 权限，见 [buildRatioShellCommand]。
     *
     * @param width  目标宽（须在引擎白名单内才有效）
     * @param height 目标高
     * @param userCn 车机渠道号（如别克 "20191100"）；必须与引擎当前 getVechileChannel() 一致，
     *               否则 getUserResolution 的 isLastUserCn() 判 false、自定义分辨率不生效。
     */
    fun buildRatioContent(width: Int, height: Int, userCn: String): String {
        val json = buildString {
            append('{')
            append("\"user_resolution_switch\":true,")
            append("\"user_resolution_width\":").append(width).append(',')
            append("\"user_resolution_heigh\":").append(height).append(',')
            append("\"user_cn\":\"").append(userCn).append('"')
            append('}')
        }
        return encryptRatio(json)
    }

    /** 撤销自定义分辨率：把 width/height 都置 0（引擎 getUserResolution 判定无效走白名单/默认）。 */
    fun buildRatioResetContent(userCn: String): String = buildRatioContent(0, 0, userCn)

    private fun encryptRatio(plain: String): String = runCatching {
        val key = SecretKeySpec(RESOLUTION_KEY.toByteArray(Charsets.UTF_8), "DES")
        val iv = IvParameterSpec(RESOLUTION_IV)
        val cipher = Cipher.getInstance("DES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "aZ" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }.getOrElse { e ->
        "CARLIFE: ratio 加密失败 —— ${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * 拼一条 adb shell 命令，把自定义分辨率写进引擎私有目录的 ratio.json。
     *
     * 引擎路径 = `getExternalFilesDir(null)` = `/sdcard/Android/data/com.baidu.carlife.honor/files/`，
     * 应用层（无 root）写不进去，所以返回命令文本供 adb 侧执行（PC 上有 adb 时）。
     * 荣耀机第三方包 logcat 加密、release 不可 run-as，但 shell 写文件不受影响。
     *
     * @param pkgName CarLife 引擎包名（荣耀机一般是 com.baidu.carlife.honor）
     */
    fun buildRatioShellCommand(
        width: Int,
        height: Int,
        userCn: String,
        pkgName: String = "com.baidu.carlife.honor",
    ): String {
        val content = buildRatioContent(width, height, userCn)
        // printf 直接落盘，避免 shell 引号/转义问题；路径按引擎 getExternalFilesDir(null) 拼。
        val path = "/sdcard/Android/data/$pkgName/files/ratio.json"
        return "adb shell \"printf '%s' '$content' > $path\""
    }
}
