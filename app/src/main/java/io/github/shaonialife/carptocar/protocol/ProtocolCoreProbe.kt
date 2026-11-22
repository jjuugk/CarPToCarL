package io.github.shaonialife.carptocar.protocol

/**
 * Rust 协议核（`libcardeck_protocol.so`）加载自检。
 *
 * v2 起 CarPlay 链路的协议层改由该 so 承担（报文构造/解析、配对 m1~m6、加密、
 * HID 打包），但替换是**按段推进**的：so 就位不等于链路已切换。这个自检只回答
 * 一个问题——so 在当前设备上能不能加载、`nativeVersion` 能不能调通。
 *
 * 为什么要单独探一次：so 只带 arm64-v8a，且是 NDK 产物；一旦加载失败，
 * `System.loadLibrary` 抛的 `UnsatisfiedLinkError` 在会话线程里会和"连不上车机"
 * 混在一起，排障时看不出区别。把它钉在进程启动时，日志里就有一行确定的证据。
 *
 * 失败不阻断任何功能：现有 Kotlin 实现照常跑，自检只是记录。
 */
object ProtocolCoreProbe {
    /** 自检结论（单行，直接进日志）。 */
    fun run(): String {
        if (!NativeBridge.ensureLoaded()) {
            // ensureLoaded 内部已经把具体异常写进日志，这里只补一个结论标记
            return "协议核加载失败（原因见上一行），继续使用 Kotlin 实现"
        }
        return try {
            val version = NativeBridge.nativeVersion()
            "协议核就绪：$version"
        } catch (t: Throwable) {
            // 加载成功但符号缺失/签名不符才会走到这里：多半是 so 与 NativeBridge
            // 版本不匹配（118 个 external fun 与实际导出符号对不上）
            "协议核已加载但调用失败：$t"
        }
    }
}
