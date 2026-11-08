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
     * 本轮测试包的构建标记（日志里显示成 `1.2.27 #wifi-ssid`）。
     *
     * 为什么需要：同一个 `appVersionName` 下会出好几个测试包，而日志头只能读到
     * **安装包的 versionName**——2026-11-07 就因为一条「1.2.27」的日志误判成新包已生效，
     * 白白跑了一轮实车（日志里 0x4E09 还在发「未知设备」，那两项修复其实没装）。
     * 每次打测试包改这里的值；正式发版前清空。
     */
    const val BUILD_TAG = "bmw-port"

    @Volatile
    private var cached: String? = null

    /** 日志/界面展示用：`版本号 #构建标记`（构建标记为空时只有版本号）。 */
    fun nameWithTag(context: Context): String {
        val base = name(context)
        return if (BUILD_TAG.isBlank()) base else "$base #$BUILD_TAG"
    }

    @Suppress("DEPRECATION")
    fun name(context: Context): String {
        cached?.let { return it }
        val name =
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
                    .orEmpty()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        cached = name
        return name
    }
}
