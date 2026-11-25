package io.github.shaonialife.carptocar.ui

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.shaonialife.carptocar.R
import io.github.shaonialife.carptocar.session.ConnectionStageSnapshot

/**
 * 首页「连接阶段」卡片的渲染器。
 *
 * 每一段文字单独着色：**绿色只表示这一项已被本机确认**，其余一律中性灰；
 * 不上来就把整行刷绿，避免「会话建立了」被误读成「车机已经出画面/出声」。
 * 数据来自 [SessionController.connectionStageSnapshot]，本类只读、不碰会话状态。
 */
class ConnectionStageCard(root: View) {
    private val dot: View = root.findViewById(R.id.stageDot)
    private val mode: TextView = root.findViewById(R.id.stageMode)
    private val hint: TextView = root.findViewById(R.id.stageHint)
    private val activation: TextView = root.findViewById(R.id.stageActivation)
    private val control: TextView = root.findViewById(R.id.stageControl)
    private val channels: TextView = root.findViewById(R.id.stageChannels)
    private val display: TextView = root.findViewById(R.id.stageDisplay)
    private val timing: TextView = root.findViewById(R.id.stageTiming)
    private val tcp: TextView = root.findViewById(R.id.stageTcp)

    private val neutral: Int = ContextCompat.getColor(root.context, R.color.m3_mono_dim)
    private val success: Int = ContextCompat.getColor(root.context, R.color.m3_ok)
    private val error: Int = ContextCompat.getColor(root.context, R.color.m3_error)

    /** 同样内容不重设文本：1s tick 不该让 TextView 反复触发布局。 */
    private var lastSignature: String = ""

    fun render(s: ConnectionStageSnapshot) {
        val signature = listOf(
            s.active, s.hint, s.stage, s.recordAccepted, s.resourcesAccepted,
            s.controlReady, s.wifiReady, s.videoChannelReady, s.audioChannelReady,
            s.screenOwnedByPhone, s.displaySize, s.videoFormat, s.writtenFrames,
            s.sourceFrames, s.uiRequests, s.screenRebuilds, s.audioPackets,
            s.signalPackets, s.timingRequests, s.timingReplies, s.timingReplyAgeMs,
        ).joinToString("|")
        if (signature == lastSignature) return
        lastSignature = signature

        dot.alpha = if (s.active) 1f else 0.35f
        mode.text = if (s.active) "实时会话" else "等待数据"
        hint.text = if (s.active) "${s.hint} · ${s.stage}" else s.hint

        fun yes(text: String) = text to success
        fun no(text: String) = text to neutral

        facts(
            activation,
            "激活 " to neutral,
            if (s.recordAccepted) yes("RECORD ✓") else no("RECORD 等待"),
            "   ·   " to neutral,
            if (s.resourcesAccepted) yes("资源应答 ✓") else no("资源 未完成"),
            "   ·   " to neutral,
            if (s.videoChannelReady) yes("视频TCP ✓") else no("视频TCP 等待"),
        )
        facts(
            control,
            "控制 " to neutral,
            if (s.controlReady) yes("✓ 就绪") else no("协商中"),
            "   ·   Wi-Fi iAP2 " to neutral,
            if (s.wifiReady) yes("传输就绪") else no("链路协商"),
        )
        facts(
            channels,
            "视频 " to neutral,
            if (s.videoChannelReady) yes("通道就绪") else no("等待通道"),
            "   ·   音频 " to neutral,
            if (s.audioChannelReady) yes("通道就绪") else no("未就绪"),
            "   ·   归属 " to neutral,
            when (s.screenOwnedByPhone) {
                true -> yes("手机")
                false -> no("车机")
                null -> no("未知")
            },
        )

        facts(
            display,
            "车机声明 " to neutral,
            (s.displaySize ?: "待获取") to neutral,
            "   ·   视频输出 " to neutral,
            (s.videoFormat ?: "待写出") to if (s.videoFormat != null) success else neutral,
        )
        val timingOk = s.timingRequests > 0 && s.timingReplies == s.timingRequests
        facts(
            timing,
            "计时 " to neutral,
            "请求/回包 ${s.timingRequests}/${s.timingReplies}" to if (timingOk) success else neutral,
            "   ·   最近 ${age(s.timingReplyAgeMs)}" to neutral,
        )
        // 本机没有采样 TCP_INFO：ack / 发送队列 / 累计重传都拿不到真实值。
        // 这里显式写「—」，而不是把 0 伪装成已确认。
        facts(
            tcp,
            "TCP " to neutral,
            "确认 — · 队列 — · 累计重传 —" to neutral,
            "（本机未采样）" to neutral,
        )
    }

    private fun age(ms: Long?): String =
        if (ms == null) "暂无" else "${ms / 1000}s前"

    private fun facts(
        view: TextView,
        vararg parts: Pair<String, Int>,
    ) {
        val builder = SpannableStringBuilder()
        parts.forEach { (text, color) ->
            val start = builder.length
            builder.append(text)
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, 0)
        }
        view.text = builder
    }
}
