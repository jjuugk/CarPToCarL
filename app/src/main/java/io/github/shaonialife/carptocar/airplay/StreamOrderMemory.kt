package io.github.shaonialife.carptocar.airplay

import android.content.Context

/**
 * 「音频 SETUP 与 RECORD 的先后」**按接收端记忆**（issue #14）。
 *
 * ## 两种顺序，各有各的死法
 *
 * - **RECORD 优先**（默认，2026-10-17 奥迪定谳）：
 *   屏幕 SETUP → 连数据口 → RECORD → 起编码器 → 音频 SETUP → **补发 RECORD**。
 *   车机在屏幕 SETUP 后有 ~2s 看门狗，而音频 SETUP 实测要 ~2.05-2.16s，排在
 *   RECORD 之前必然超时拆链。音频流是「会话激活后」才加入的，按 AirPlay 语义必须
 *   再发一次 RECORD 把它纳入会话，否则车机给端口但不排播（奥迪实测无声）。
 * - **音频优先**（本文件新增，JYBOX-80 实车）：
 *   屏幕 SETUP → 连数据口 → 音频 SETUP → **一次 RECORD** 同时激活视频+音频。
 *   有些接收端对「流后补 RECORD」直接回 `500 Internal Server Error`，并且**顺手关掉
 *   自己的 AirPlay 服务**（`0x4301` 从此 `port=0`、mDNS 扫不到）——整轮作废，用户看到
 *   「连上→闪一下画面→黑屏/回连接页」。这类接收端（issue #14 的
 *   `Magic-Car-Link-1.00` / `Magic Tec.`，JYBOX-80）只能一次 RECORD 搞定。
 *
 * ## 为什么是「学」而不是「猜」
 *
 * 顺序不能全局改：改了就把奥迪 MIB3 的 2s 看门狗踩爆（那条路径已经实车定谳）。
 * 也不能只按型号字符串白名单：陌生接收端一样可能回 500。所以两层：
 *
 * 1. **快路径**：已知型号（[KNOWN_AUDIO_FIRST]）直接音频优先，省掉一次失败的连接；
 * 2. **学出来的结论**：RECORD 优先那一轮里，如果「音频 SETUP 之后补发的 RECORD」
 *    返回非 200，就把该接收端记成音频优先（[notePostRecordRejected]），下一轮自动换序。
 *    代价是一轮失败，换来之后都不再踩——比每次盲试便宜。
 * 3. **反向纠正**：音频优先那一轮若连第一次 RECORD 都非 200，说明这台接收端不是
 *    「补发 RECORD 过敏」而是别的问题，记回 RECORD 优先（[noteRecordFirstForced]），
 *    避免被快路径永久锁死在错误顺序上。
 *
 * key 口径与 [AudioProfileMemory.receiverKey] 一致（manufacturer + model 归一化），
 * 同一套 `receiverIdentity()` 来源，两处不会各算各的。
 */
object StreamOrderMemory {
    /** 本轮采用哪种顺序。 */
    enum class Order {
        /** 默认：先 RECORD 激活会话与视频，音频事后加入并补发 RECORD。 */
        RECORD_FIRST,

        /** 一次 RECORD 激活全部：音频 SETUP 提前到 RECORD 之前。 */
        AUDIO_FIRST,
    }

    /** 该接收端学到的结论（存储值，[NONE] = 还没学过，交给快路径/默认值）。 */
    enum class Learned {
        NONE,
        AUDIO_FIRST,
        RECORD_FIRST,
    }

    private const val FILE = "stream_order_memory_v1"
    private const val KEY_STATE = "order_"
    private const val STATE_AUDIO_FIRST = "audio_first"
    private const val STATE_RECORD_FIRST = "record_first"

    /**
     * 型号快路径：**只放已被实车证实「流后补 RECORD 会 500」的接收端**。
     *
     * 目前只有 issue #14 的 JYBOX-80（`/info`：model=`Magic-Car-Link-1.00`、
     * manufacturer=`Magic Tec.`、固件 2024.03.21，沃尔沃 Sensus 经盒子）。匹配串
     * 取小写子串，`magic-car-link` 与 `jybox` 两种自报口径都能命中。
     */
    private val KNOWN_AUDIO_FIRST = listOf("magic-car-link", "jybox")

    /** 纯函数：结论 → 本轮顺序。便于单测，不需要 Context。 */
    fun resolve(
        learned: Learned,
        identityText: String,
    ): Order =
        when {
            learned == Learned.AUDIO_FIRST -> Order.AUDIO_FIRST
            learned == Learned.RECORD_FIRST -> Order.RECORD_FIRST
            matchesKnown(identityText) -> Order.AUDIO_FIRST
            else -> Order.RECORD_FIRST
        }

    /** 型号是否落在已知需要音频优先的名单里（子串、忽略大小写）。 */
    fun matchesKnown(identityText: String): Boolean {
        val text = identityText.lowercase()
        return KNOWN_AUDIO_FIRST.any { it in text }
    }

    /** 车机身份 → 存储键（与 [AudioProfileMemory.receiverKey] 同口径）。 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** 读该接收端本轮应采用的顺序。 */
    fun load(
        context: Context?,
        identityText: String,
    ): Order = resolve(readLearned(context, identityText), identityText)

    /** 读出存储结论（诊断/测试用）。 */
    fun readLearned(
        context: Context?,
        identityText: String,
    ): Learned {
        val raw = prefs(context)?.getString(KEY_STATE + receiverKey(identityText), null)
        return when (raw) {
            STATE_AUDIO_FIRST -> Learned.AUDIO_FIRST
            STATE_RECORD_FIRST -> Learned.RECORD_FIRST
            else -> Learned.NONE
        }
    }

    /**
     * 「音频 SETUP 之后补发的 RECORD」返回非 200：记住这台接收端要用音频优先。
     *
     * @return 下一轮将采用的顺序（恒为 [Order.AUDIO_FIRST]）。
     */
    fun notePostRecordRejected(
        context: Context?,
        identityText: String,
    ): Order {
        write(context, identityText, STATE_AUDIO_FIRST)
        return Order.AUDIO_FIRST
    }

    /**
     * 音频优先那一轮的第一次 RECORD 也非 200：说明不是「补发 RECORD 过敏」，
     * 记回 RECORD 优先（并压过快路径），别把接收端永久锁在错误顺序上。
     */
    fun noteRecordFirstForced(
        context: Context?,
        identityText: String,
    ): Order {
        write(context, identityText, STATE_RECORD_FIRST)
        return Order.RECORD_FIRST
    }

    /** 清掉该接收端学到的结论（回到快路径/默认值）。 */
    fun clear(
        context: Context?,
        identityText: String,
    ) {
        prefs(context)?.edit()?.remove(KEY_STATE + receiverKey(identityText))?.apply()
    }

    /** 打印用：一行说清本轮顺序与它的来由。 */
    fun describe(
        order: Order,
        learned: Learned,
        identityText: String,
    ): String {
        val why =
            when {
                learned == Learned.AUDIO_FIRST -> "本机记忆（上次补发 RECORD 被拒）"
                learned == Learned.RECORD_FIRST -> "本机记忆（音频优先时 RECORD 也被拒）"
                order == Order.AUDIO_FIRST -> "已知型号快路径"
                else -> "默认（奥迪 MIB3 时序）"
            }
        val what =
            when (order) {
                Order.RECORD_FIRST -> "RECORD 优先：先激活会话与视频，音频 SETUP 后补 RECORD"
                Order.AUDIO_FIRST -> "音频优先：先 SETUP 音频，再一次 RECORD 同时激活"
            }
        return "$what · 依据=$why · 接收端=$identityText"
    }

    // ---- 内部 ----

    private fun prefs(context: Context?): android.content.SharedPreferences? =
        context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun write(
        context: Context?,
        identityText: String,
        state: String,
    ) {
        prefs(context)?.edit()?.putString(KEY_STATE + receiverKey(identityText), state)?.apply()
    }
}
