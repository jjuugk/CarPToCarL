package io.github.shaonialife.carptocar.protocol

import io.github.shaonialife.cardeck.protocol.NativeBridge

/**
 * Rust 协议核（`libcardeck_protocol.so`）加载自检。
 *
 * v2 起 CarPlay 链路的协议层改由该 so 承担（报文构造/解析、配对 m1~m6、加密、
 * HID 打包），但替换是**按段推进**的：so 就位不等于链路已切换。
 *
 * 这个自检回答三个问题，按代价从低到高：
 *  1. so 能不能加载（`System.loadLibrary`）——失败时 `UnsatisfiedLinkError` 在会话
 *     线程里会和「连不上车机」混在一起，排障看不出区别，所以钉在进程启动时；
 *  2. 符号绑不绑得上、类型映不映射得对——只调 `nativeVersion` 是不够的，那只验了
 *     一个 `String` 返回；这里额外打三个纯函数，覆盖 `String` 入参/返回、
 *     `Array<String>` 返回、`ByteArray+Int` 入参/`Int` 返回三种 JNI 映射；
 *  3. 结果是否合理（`fe80::1%wlan0` 去 scope 应得 `fe80::1`）。
 *
 * 全是纯函数调用，不建会话、不碰 socket、不影响任何现有行为。
 * 失败只落日志：Kotlin 实现照常工作。
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

            // Array<String> -> Array<String>：地址候选（v6 scope 剥离也在里面）
            val candidates = NativeBridge.nativeNetaddrCandidates(arrayOf("192.168.1.5"))
            val scopeStripped = NativeBridge.nativeNetaddrStripScope("fe80::1%wlan0")

            // ByteArray + Int -> Int：iAP2 DETECT marker 魔数 = FF 55 02 00 EE 10
            // （逆向 nativeIap2IsDetectMarker @0x9ac38：小端常量 0x10ee000255ff）
            val marker =
                NativeBridge.nativeIap2IsDetectMarker(
                    byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10),
                    6,
                )

            val abiOk = candidates.isNotEmpty() && scopeStripped == "fe80::1" && marker == 1
            "协议核就绪：$version｜ABI=${if (abiOk) "ok" else "异常"}｜" +
                "candidates=${candidates.size} 个｜stripScope=$scopeStripped｜detectMarker=$marker"
        } catch (t: Throwable) {
            // 加载成功但符号缺失/签名不符才会走到这里：多半是 so 与 NativeBridge
            // 版本不匹配（118 个 external fun 与实际导出符号对不上）
            "协议核已加载但调用失败：$t"
        }
    }
}
