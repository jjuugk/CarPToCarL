package io.github.shaonialife.carptocar.iap2

/**
 * MFi 挑战长度的**证书识别**（从协议核 `libcardeck_protocol.so` 逆向回灌，2026-11-22）。
 *
 * 背景：协议核 `nativeMfiChallengeSizeForCertificate`（@0x9addc）拿到 0xAA01 证书 DER 后，
 * 调用解析函数 @0xf8a58 返回一个 0~5 的枚举 `v`，再以 `idx = v - 2` 查表 @0x22520
 * `[20, 32, -1, 16]` 得到长度；`main` 原先只会逐档试探（+ 按车机记忆），
 * 新车机 / 记忆丢失时最坏要试 3 档（每档 3~6 秒）。本文件把「识别」补回来。
 *
 * 逆向还原的精确规则（`@0xf8a58` 的四个出口）：
 * ```
 * v=3 → 32B ：找到 X.509 扩展里的 Apple OID 1.2.840.113635.100.6.36
 *             （9 字节 2a 86 48 86 f7 63 64 06 24）
 * v=4 → -1  ：找到 Apple OID 1.2.840.113635.100.6.59.1
 *             （10 字节 2a 86 48 86 f7 63 64 06 3b 01）—— 视为无法识别
 * v=5 → 16B ：找到 Apple OID 1.2.840.113635.100.6.71.1
 *             （10 字节 2a 86 48 86 f7 63 64 06 47 01）
 * v=2 → 20B ：找到 15 字节 ASN.1 INTEGER，其 [2]/[6]/[8]/[11] 字节均为 0xAA
 * v=0/1 → -1：解析失败
 * ```
 * 优先级按反汇编的检查顺序：32 → 无效 → 16 → 20。
 * 注意这些 OID 位于**证书扩展区**（解析函数逐层下钻 SEQUENCE→0xA3 extensions），
 * 不是公钥算法 OID——此前的解包报告写成「公钥 OID」并不准确，已更正。
 *
 * 协议核另外两个分支（`negotiatedMfi4`、`authVerMajor` 同时成立 → 固定 16B）依赖
 * MFi4 协商结果；main 跳过证书/签名校验，拿不到这两个量，故只回灌证书识别部分。
 *
 * **失败必须回退试探**：识别只是"首选"，[prefer] 把它插到尝试顺序第一位，
 * 原顺序（记忆重排后的基线）原样保留在后面。识别错或车机不认时，
 * `runMfiChallenge` / `startNextMfiChallengeLocked` 会自然继续试后续档位。
 */
object MfiCertificateLength {
    /** Apple 私有 OID 1.2.840.113635.100.6.36 → 32B。 */
    private val OID_32 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x63, 0x64, 0x06, 0x24)

    /** Apple 私有 OID 1.2.840.113635.100.6.59.1 → 协议核返回 -1（无法识别）。 */
    private val OID_INVALID = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x63, 0x64, 0x06, 0x3B, 0x01)

    /** Apple 私有 OID 1.2.840.113635.100.6.71.1 → 16B。 */
    private val OID_16 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x63, 0x64, 0x06, 0x47, 0x01)

    private val EXTENSION_MARKERS = intArrayOf(2, 6, 8, 11)

    /**
     * `0xAA01` 证书参数总长阈值（含 4 字节 TLV 参数头），来自 hypercar `MfiChallenge.kt`
     * （它注释说是 xiap2 的实现：挑战长度由证书**参数总长**决定）。
     *
     * 只作为 [recognize] 失败时的兜底首猜，见 [parseParamBytes]。
     */
    const val CERTIFICATE_PARAM_THRESHOLD_BYTES = 644

    /** `0xAA01` 参数头长度：len(u16) + id(u16)，与 [Iap2Csm.param] 一致。 */
    const val PARAM_HEADER_BYTES = 4

    /**
     * 按证书参数**总字节**猜首选挑战长度（>644 → 20B，否则 32B）。
     *
     * 与 [recognize] 的分工：OID 规则精确但只认 Apple 私有扩展；证书里没有那些 OID 时
     * 返回 null，此时这个粗判能把「首连盲试最坏 12 秒」压到「最多错一档」。
     *
     * **只作首选**，调用方必须仍走 [prefer] 的「插队首 + 原顺序回退」语义。
     *
     * @param derBytes 证书 DER 本身的字节数（不含参数头）。
     */
    fun fallbackByParamBytes(derBytes: Int): Int =
        if (derBytes + PARAM_HEADER_BYTES > CERTIFICATE_PARAM_THRESHOLD_BYTES) 20 else 32

    /**
     * 从证书 DER 识别首选挑战长度。
     * @return 20 / 32 / 16；`null` = 识别不出来（调用方按原试探顺序走）。
     */
    fun recognize(der: ByteArray): Int? {
        if (der.size < 2) return null
        val oids = tlvContents(der, 0x06)
        if (oids.any { it.contentEquals(OID_32) }) return 32
        if (oids.any { it.contentEquals(OID_INVALID) }) return null
        if (oids.any { it.contentEquals(OID_16) }) return 16
        return tlvContents(der, 0x02)
            .any { value ->
                value.size == 15 && EXTENSION_MARKERS.all { value[it] == 0xAA.toByte() }
            }
            .let { if (it) 20 else null }
    }

    /**
     * 把识别长度插到尝试顺序首位，**完整保留**原顺序作为失败后的兜底。
     *
     * 若识别结果不在基线里（例如车机只接受 16B 而基线被改过），不插队——
     * 基线由实车迭代与持久化记忆共同决定，不能被证书推断推翻。
     */
    fun prefer(
        baseline: List<Int>,
        recognized: Int?,
    ): List<Int> =
        if (recognized != null && recognized in baseline) {
            listOf(recognized) + baseline.filter { it != recognized }
        } else {
            baseline
        }

    /**
     * 扫描 DER 中所有指定 tag 的 TLV 内容字节。
     *
     * 有意**不跳过内容区**继续递进：证书 OID 藏在多层 SEQUENCE / 0xA3 扩展里，
     * 只解析顶层会漏掉；命中靠完整字节串比对，内容区里的偶然同 tag 不会误判。
     */
    private fun tlvContents(
        der: ByteArray,
        wantedTag: Int,
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 1 < der.size) {
            if (der[i].toInt() and 0xFF != wantedTag) {
                i++
                continue
            }
            var p = i + 1
            val lenByte = der[p].toInt() and 0xFF
            p++
            val len: Int
            when {
                lenByte < 0x80 -> len = lenByte
                lenByte == 0x81 -> {
                    if (p >= der.size) break
                    len = der[p].toInt() and 0xFF
                    p++
                }
                lenByte == 0x82 -> {
                    if (p + 1 >= der.size) break
                    len = ((der[p].toInt() and 0xFF) shl 8) or (der[p + 1].toInt() and 0xFF)
                    p += 2
                }
                else -> {
                    i++
                    continue
                }
            }
            if (len > 0 && p + len <= der.size) out.add(der.copyOfRange(p, p + len))
            i++
        }
        return out
    }
}
