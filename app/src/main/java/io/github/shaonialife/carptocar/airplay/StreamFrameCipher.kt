package io.github.shaonialife.carptocar.airplay

/**
 * 屏幕/音频流帧加密方式（AirPlay `et` 字段的三种形态）。
 *
 * catplay `ScreenFrameCodec::new` 的选择依据：
 *  - 走过 /pair-setup/pair-verify → ChaCha20-Poly1305（共享密钥派生）；
 *  - 只走过 /auth-setup（MFi-SAP）→ AES-128-CTR（会话密钥）；
 *  - 都没走（`et = NONE`，老车机 pre-HomeKit）→ **AES-128-CTR 且会话密钥全零**
 *    （catplay 注释："There was never support for unencrypted screen sessions.
 *     ET = None maps to AES session where session_key before derivation is all zeroes."）
 *
 * 2026-10-19 数据点：奥迪 4xxx（如 4851）可能属于第三类——对它强制
 * pair-setup 必然失败，而 catplay 先用明文 SETUP 起步、屏幕流走 AES 零密钥即可连上。
 */
sealed class StreamFrameCipher {
    /** 视频帧 ChaCha20-Poly1305 密封（16B tag），keepAlive/config 帧不加密。 */
    class ChaCha(
        val key: ByteArray,
    ) : StreamFrameCipher()

    /** 视频帧 AES-128-CTR 流式加密（无 tag，密钥流跨帧连续）。 */
    class AesCtr(
        val key: ByteArray,
        val iv: ByteArray,
    ) : StreamFrameCipher()

    /**
     * 加密**由协议核在 native 内完成**（`nativeAirplayScreenSealFrame`）。
     *
     * 只有 `protocolcore/AirPlayLink` 这条链路会给出该变体：数据面把 Annex-B 裸帧交给
     * 协议核，密文与帧头都由 Rust 产出。Kotlin 侧的内嵌发送器拿到它时**不得再加密**
     * （再加密一次 = 对端解出乱码），只能原样透传或直交协议核。
     *
     * 自研链路 `AirPlayClient` 永不产生该变体。
     */
    object CoreSealed : StreamFrameCipher()
}

object AirPlayStreamKeys {
    /**
     * ChaCha 流密钥：HKDF-SHA512(shared_secret, "DataStream-Salt<connId>", "DataStream-Output-Encryption-Key")。
     * 与 catplay `create_chacha_ciphers` / 接收端 ScreenStream 的派生规则一致。
     */
    fun chachaStreamKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** `et=NONE` 时的会话密钥：16 字节全零（catplay ScreenFrameCodec::new 同款）。 */
    val ZERO_SESSION_KEY: ByteArray = ByteArray(16)

    /**
     * AES 流密钥/IV（catplay `derive_aes_stream_keys` 逐字节对齐）：
     *   key = SHA512("AirPlayStreamKey" + connId(十进制) + sessionKey)[0..16]
     *   iv  = SHA512("AirPlayStreamIV"  + connId(十进制) + sessionKey)[0..16]
     */
    fun aesStreamKeys(
        sessionKey: ByteArray,
        connectionId: Long,
    ): Pair<ByteArray, ByteArray> {
        fun digest(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    connectionId.toString().toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)
        return digest("AirPlayStreamKey") to digest("AirPlayStreamIV")
    }
}
