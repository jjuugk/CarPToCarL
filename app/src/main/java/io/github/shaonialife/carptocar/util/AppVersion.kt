package io.github.shaonialife.carptocar.util

import android.content.Context
import io.github.shaonialife.carptocar.BuildConfig

/**
 * 应用版本号的**运行时**来源。
 *
 * 为什么不直接用 `BuildConfig.VERSION_NAME`：它是 Java 的编译期常量，Kotlin 编译
 * `"CarPToCarL ${BuildConfig.VERSION_NAME} …"` 这种模板时会把它**折叠成字面量**写进
 * dex。只改了 `app/build.gradle.kts` 的 `appVersionName`、增量编译又没有重编引用方时，
 * dex 里留下的就是上一版的号——系统显示（AndroidManifest）已经变了，日志却还印旧版本。
 * 1.2.13 发布时就踩到了：同一个包里同时存在 `1.2.13`（BuildConfig）和折叠进去的
 * `CarPToCarL 1.2.12`（MainActivity 的启动日志）。
 *
 * 从 PackageManager 读的是**安装包的真实版本**，不存在陈旧问题；只有读不到时才退回
 * BuildConfig。结果缓存一次（版本号在进程生命周期内不会变）。
 */
object AppVersion {
    /**
     * 测试包的**日志专用**后缀（不写进 AndroidManifest，不改 versionName/versionCode）。
     *
     * `-test-x` 里的 x = 同一个 versionName 的第几个测试包：1.2.25 的 `-test-1` 是
     * VideoConfig 视口 A/B 开关包、`-test-2` 是 0x5001 音乐文字兜底、`-test-3`
     * 是「等当期 IDR 再建屏 + 首帧送达后重建屏激活」。正式发布置回空串即可。
     * 启动日志与「导出日志」头部的「应用版本」行都会带上它，便于一份日志判断跑的是哪个包。
     */
    private const val LOG_VERSION_TAG = ""

    @Volatile
    private var cached: String? = null

    @Suppress("DEPRECATION")
    fun name(context: Context): String {
        cached?.let { return it }
        val base =
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
                    .orEmpty()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        val name = base + LOG_VERSION_TAG
        cached = name
        return name
    }
}
