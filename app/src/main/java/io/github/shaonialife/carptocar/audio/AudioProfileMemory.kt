package io.github.shaonialife.carptocar.audio

import android.content.Context

/**
 * 音频档位的**按车机持久化记忆**（与 [io.github.shaonialife.carptocar.iap2.MfiLengthMemory]
 * 同一套思路）。
 *
 * 背景（2026-10-19 Honda HFT / Alpine 实车）：选「视频+音频」连接后，会话建立成功、
 * 视频正常、音频 SETUP 也回 200 并拿到 dataPort，但音频包发出去约 6s 后车机把整条
 * AirPlay 会话拆掉（`EVENT: 读取结束 - Connection reset`）。同一台车机只推视频则稳定。
 * 这说明**具体某个音频档位**是雷，而不同车机踩雷的档位不同（奥迪 media/44.1k 可用，
 * 这台只声明 media/44.1k 却用不了）。既不能全局写死档位，也不能每次连接都盲试，
 * 所以按车机身份记住三件事：
 *
 *  - [Entry.working]：最近一次**跑满存活窗口**的档位 → 下次直接首选；
 *  - [Entry.rejected]：上线后很快被拆链的档位 → 排到最后；
 *  - [Entry.disabled]：所有档位都被拆过 → 这台车机不再尝试音频，直接只推视频
 *    （宁可没声音，也不要「一连就断」）。
 *
 * 判定「快」的窗口由调用方给（[SessionController] 用 20s）。
 */
object AudioProfileMemory {
    /** 单台车机的音频记忆。 */
    data class Entry(
        val working: String? = null,
        val rejected: Set<String> = emptySet(),
        val disabled: Boolean = false,
    ) {
        val isEmpty: Boolean get() = working == null && rejected.isEmpty() && !disabled
    }

    // v2：档位键加入「流类型 + 编码方式」（102/AAC-LC 与 100/PCM 是两条完全不同的通路）。
    // 换文件名而不是复用旧文件，是为了让 v1 时代「PCM 三档全拆链」的结论不再挡路——
    // 那套结论只覆盖 PCM，对新增的 AAC 通路没有参考价值。
    //
    // v3（2026-11-06）：**进程冻结会把一次拆链算到音频档头上**（vivo V2405A / 奥迪 CL3D：
    // 冻结 3 秒 → 车机按 keepAlive 超时撤会话 → 20s 探针窗口内被判定「48K AAC 不兼容」），
    // 于是那份名单里混进了假结论。归因逻辑已在 ProcFreeze 里修好，但**写错的旧条目无法
    // 事后区分真假**，只能换文件名让它们一次性作废：每台车下次连接重新试一轮档位
    // （代价一次连接，换来不再拿假结论排档）。
    private const val FILE = "audio_profile_memory_v3"
    private const val KEY_OK = "ok_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_DISABLED = "disabled_"

    /** 车机身份 → 存储键（只留稳定字符，避免蓝牙名/固件串里的可变部分影响命中）。 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        return Entry(
            working = prefs.getString(KEY_OK + key, null)?.takeIf { it.isNotBlank() },
            rejected = readKeys(prefs.getString(KEY_REJECTED + key, null)),
            disabled = prefs.getBoolean(KEY_DISABLED + key, false),
        )
    }

    /** 该档位跑满存活窗口：记为首选，并证伪历史 rejected 结论（固件升级后可能变好）。 */
    fun recordWorking(
        context: Context?,
        identityText: String,
        profileKey: String,
    ) {
        update(context, identityText) { entry ->
            Entry(
                working = profileKey,
                rejected = entry.rejected - profileKey,
                disabled = false,
            )
        }
    }

    /**
     * 该档位不可用：排到最后。
     *
     * @param markExhausted 全部档位都被否掉时是否直接置 [Entry.disabled]。**只有「拆链」
     *   这种确定性证据才允许置**；SETUP 被拒/超时可能只是一次网络或仲裁抖动，传 false。
     */
    fun recordRejected(
        context: Context?,
        identityText: String,
        profileKey: String,
        allCandidates: Set<String>,
        markExhausted: Boolean = true,
    ) {
        update(context, identityText) { entry ->
            if (entry.working == profileKey) {
                entry
            } else {
                val rejected = entry.rejected + profileKey
                val exhausted =
                    markExhausted && allCandidates.isNotEmpty() && allCandidates.all { it in rejected }
                Entry(working = entry.working, rejected = rejected, disabled = exhausted)
            }
        }
    }

    /** 手动恢复（换车机后复位，或车机固件升级后重新试探）。 */
    fun reset(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry() }
    }

    /** 是否存在任何记忆（决定 UI 是否显示「清除音频兼容记忆」入口）。 */
    fun hasAny(context: Context?): Boolean {
        val all = prefs(context)?.all ?: return synchronized(memory) { memory.isNotEmpty() }
        return all.any { (key, value) ->
            when {
                key.startsWith(KEY_DISABLED) -> value == true
                key.startsWith(KEY_OK) -> (value as? String)?.isNotBlank() == true
                key.startsWith(KEY_REJECTED) -> (value as? String)?.isNotBlank() == true
                else -> false
            }
        }
    }

    /** 清空全部车机的音频记忆（车机固件升级、换车后重新试探）。 */
    fun clearAll(context: Context?) {
        synchronized(memory) {
            memory.clear()
            prefs(context)?.edit()?.clear()?.apply()
        }
    }

    /** 打印用：压成一行日志。 */
    fun describe(entry: Entry): String =
        when {
            entry.disabled -> "本车机音频已判定不兼容（只推视频）"
            entry.isEmpty -> "无"
            else ->
                buildList {
                    entry.working?.let { add("可用=$it") }
                    if (entry.rejected.isNotEmpty()) add("拆链=${entry.rejected.sorted().joinToString("/")}")
                }.joinToString("，")
        }

    /**
     * 计算本轮的尝试顺序（纯函数，便于单测）。
     *
     * @param candidates 按「越靠前越推荐」排好的基线候选（来自车机 /info）。
     * @param entry 该车机的持久化记忆。
     * @return 依次尝试的档位；**空列表表示不要再试音频**（记忆已判定全部档位拆链）。
     */
    fun order(
        candidates: List<AudioProfile>,
        entry: Entry,
    ): List<AudioProfile> {
        if (candidates.isEmpty()) return emptyList()
        if (entry.disabled) return emptyList()
        val keys = candidates.map { it.key }.toSet()
        val rejected = entry.rejected.filter { it in keys }.toSet()
        val working = candidates.firstOrNull { it.key == entry.working }
        val rest = candidates.filter { it.key != working?.key }
        // 只有「还有没被判死的档位」时才把 rejected 排到最后；全被判死说明与记忆矛盾
        // （换车机/固件升级却沿用同一身份键），此时照常全试，避免永久无声。
        val allRejected = rest.isNotEmpty() && rest.all { it.key in rejected }
        val safe = if (allRejected) rest else rest.filter { it.key !in rejected }
        val risky = if (allRejected) emptyList() else rest.filter { it.key in rejected }
        return (listOfNotNull(working) + safe + risky).distinctBy { it.key }
    }

    // ---- 内部 ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val next = transform(load(context, identityText))
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.working != null) putString(KEY_OK + key, next.working) else remove(KEY_OK + key)
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                    putBoolean(KEY_DISABLED + key, next.disabled)
                }.apply()
        }
    }

    private fun readKeys(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
}
