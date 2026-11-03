package io.github.shaonialife.carptocar.mib3

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import io.github.shaonialife.carptocar.R
import io.github.shaonialife.carptocar.ui.DarkMessageDialog
import io.github.shaonialife.carptocar.ui.PanelWindow

/**
 * 「MIB3 车机控制」面板：只读区 + 控制区。
 *
 * ## 为什么做成弹窗而不是常驻卡片
 *
 * 这套端点写下去会真的动车身（ACL 里 `/door`、`/window`、`/hvac` 都是全开），
 * 不该和「连接 / 显示 / 日志」这些日常开关并列占位。弹窗还带来两个必要的门槛：
 *  - 打开时先自动拉一轮**只读**数据（不会改变车辆状态）；
 *  - 写入必须手动拨开「允许写入」且逐个确认，确认框里显示**实际要发的 JSON**。
 *
 * ## 未验证声明
 *
 * viwi 的封帧与字段都是固件逆向推断的（见 [Mib3Viwi] 类注释），面板顶部与底部
 * 都明确标注，避免把「点了没反应」误解成「车不支持」。
 */
object Mib3ControlPanel {
    private const val REFRESH_TICK_MILLIS = 700L

    fun show(
        activity: Activity,
        log: (String) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = activity.getDrawable(R.drawable.bg_dialog_panel)
                setPadding(dp(18), dp(16), dp(18), dp(12))
            }

        // ---- 标题 + 未验证徽标 ----
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            TextView(activity).apply {
                text = "MIB3 车机控制"
                setTextColor(activity.getColor(R.color.m3_on_surface))
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            TextView(activity).apply {
                text = "未验证"
                setTextColor(activity.getColor(R.color.m3_amber))
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                background = activity.getDrawable(R.drawable.bg_chip)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            },
        )
        root.addView(header)
        root.addView(
            TextView(activity).apply {
                text = "经 iAP2 EA 隧道访问车机内部 viwi 总线（ext_devices 档位）"
                setTextColor(activity.getColor(R.color.m3_on_var))
                textSize = 11.5f
                setPadding(0, dp(4), 0, 0)
            },
        )

        // ---- 可滚动正文 ----
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isFillViewport = true
                addView(body)
            },
        )

        // 链路状态
        val linkSection = section(activity, "链路")
        val phaseRow = statusRow(activity, "状态")
        val protocolsRow = statusRow(activity, "EA 协议")
        val eaStatusRow = statusRow(activity, "EA 状态")
        val countersRow = statusRow(activity, "报文")
        listOf(phaseRow, protocolsRow, eaStatusRow, countersRow).forEach { linkSection.addView(it.first) }
        body.addView(linkSection)

        // 只读区
        val readSection = section(activity, "只读（GET，不改变车辆状态）")
        val readRows =
            Mib3Endpoints.READ_ONLY.map { (label, _) ->
                statusRow(activity, label).also { readSection.addView(it.first) }
            }
        body.addView(readSection)

        // 控制区
        val controlSection = section(activity, "控制（写入，需先允许）")
        val writeToggle =
            SwitchCompat(activity).apply {
                text = "允许写入（危险）"
                setTextColor(activity.getColor(R.color.m3_on_surface))
                textSize = 13f
                isChecked = false
            }
        controlSection.addView(writeToggle)
        val writeHint =
            TextView(activity).apply {
                setTextColor(activity.getColor(R.color.m3_on_var))
                textSize = 11.5f
                setPadding(0, dp(2), 0, dp(8))
            }
        controlSection.addView(writeHint)

        val actionButtons = ArrayList<Pair<Mib3Endpoints.Action, TextView>>()
        var buttonRow: LinearLayout? = null
        Mib3Endpoints.ACTIONS.forEachIndexed { index, action ->
            val current =
                buttonRow?.takeIf { index % 2 != 0 }
                    ?: LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, dp(6))
                        controlSection.addView(this)
                        buttonRow = this
                    }
            val leading = index % 2 == 0
            val button =
                TextView(activity).apply {
                    text = action.label
                    gravity = Gravity.CENTER
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(
                        activity.getColor(if (action.danger) R.color.m3_amber else R.color.m3_on_sec_container),
                    )
                    background =
                        activity.getDrawable(
                            if (action.danger) R.drawable.bg_btn_tonal_warn else R.drawable.bg_btn_tonal,
                        )
                    setPadding(0, dp(9), 0, dp(9))
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (leading) marginEnd = dp(5) else marginStart = dp(5)
                        }
                    setOnClickListener { confirmAndSend(activity, action, log) }
                }
            current.addView(button)
            actionButtons += action to button
        }
        body.addView(controlSection)

        // 原始报文
        val rawSection = section(activity, "原始报文（最近 8 条）")
        val rawText =
            TextView(activity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10.5f
                setTextColor(activity.getColor(R.color.m3_mono_dim))
                setPadding(0, dp(4), 0, 0)
            }
        rawSection.addView(rawText)
        body.addView(rawSection)

        // ---- 底部：免责声明 + 操作 ----
        root.addView(
            TextView(activity).apply {
                text = Mib3Endpoints.DISCLAIMER
                setTextColor(activity.getColor(R.color.m3_on_var))
                textSize = 10.5f
                setPadding(0, dp(10), 0, dp(8))
            },
        )
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val refreshButton =
            TextView(activity).apply {
                text = "拉取只读数据"
                setTextColor(activity.getColor(R.color.m3_primary))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                background = activity.getDrawable(R.drawable.bg_tbtn)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
        footer.addView(refreshButton)
        footer.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val closeButton =
            TextView(activity).apply {
                text = "关闭"
                setTextColor(activity.getColor(R.color.m3_on_primary))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                background = activity.getDrawable(R.drawable.bg_cta_fill)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }
        footer.addView(closeButton)
        root.addView(footer)

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        val handler = Handler(Looper.getMainLooper())

        fun render() {
            val status = Mib3ControlLink.status()
            phaseRow.second.text = status.phase.label
            phaseRow.second.setTextColor(
                activity.getColor(
                    when (status.phase) {
                        Mib3ControlLink.Phase.READY -> R.color.m3_ok
                        Mib3ControlLink.Phase.NO_SESSION, Mib3ControlLink.Phase.NO_PROTOCOL -> R.color.m3_error
                        else -> R.color.m3_amber
                    },
                ),
            )
            protocolsRow.second.text = status.protocols
            eaStatusRow.second.text = status.eaStatus
            countersRow.second.text =
                "发 ${status.requests} / 收 ${status.responses} · 字节 ${status.txBytes}/${status.rxBytes}"

            Mib3ControlLink.rows().forEachIndexed { index, row ->
                val target = readRows.getOrNull(index) ?: return@forEachIndexed
                target.second.text = if (row.sample != null) "${row.state} · ${row.sample}" else row.state
            }

            val writable = status.phase == Mib3ControlLink.Phase.READY
            writeToggle.isEnabled = writable
            if (!writable && writeToggle.isChecked) writeToggle.isChecked = false
            writeHint.text =
                when {
                    !writable -> "链路未就绪：${status.detail}"
                    !writeToggle.isChecked -> "打开开关后才可执行写操作，每次都会二次确认"
                    else -> "已允许写入——每次仍会弹确认框显示实际报文"
                }
            val enabled = writable && writeToggle.isChecked
            actionButtons.forEach { (_, button) ->
                button.isEnabled = enabled
                button.alpha = if (enabled) 1f else 0.4f
            }
            rawText.text =
                Mib3ControlLink
                    .recentLines()
                    .takeLast(8)
                    .ifEmpty { listOf("（还没有收到任何响应）") }
                    .joinToString("\n")
        }

        val ticker =
            object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    render()
                    handler.postDelayed(this, REFRESH_TICK_MILLIS)
                }
            }

        closeButton.setOnClickListener { dialog.dismiss() }
        writeToggle.setOnCheckedChangeListener { _, _ -> render() }
        refreshButton.setOnClickListener {
            if (!Mib3ControlLink.refreshReadOnly()) {
                log("MIB3: 只读拉取失败（${Mib3ControlLink.status().detail}）")
            }
            render()
        }

        dialog.setOnShowListener {
            PanelWindow.apply(dialog, activity, heightFraction = 0.86f)
            // 打开即拉一轮只读：读不改状态，先把「车机到底有没有这条路」探出来
            Mib3ControlLink.refreshReadOnly()
            render()
            handler.postDelayed(ticker, REFRESH_TICK_MILLIS)
        }
        val linkListener: () -> Unit = { handler.post { if (dialog.isShowing) render() } }
        dialog.setOnDismissListener {
            handler.removeCallbacks(ticker)
            Mib3ControlLink.removeListener(linkListener)
        }
        Mib3ControlLink.addListener(linkListener)

        dialog.show()
        log("MIB3: 打开车机控制面板（${Mib3ControlLink.status().phase.label}）")
    }

    /** 写入前的确认框：把**实际要发的报文**摆出来，避免「点了才知道发了什么」。 */
    private fun confirmAndSend(
        activity: Activity,
        action: Mib3Endpoints.Action,
        log: (String) -> Unit,
    ) {
        val message =
            buildString {
                appendLine(action.note)
                appendLine()
                appendLine("${action.method} ${action.path}")
                appendLine(action.body ?: "（无请求体）")
                appendLine()
                append("请求会经 EA 隧道发往车机 viwi 总线；接口与字段未经实车验证，")
                append("失败时车机回执会显示在面板与日志里。")
            }
        DarkMessageDialog.show(
            activity,
            title = "确认：${action.label}",
            message = message,
            actions =
                listOf(
                    DarkMessageDialog.Action("取消") {},
                    DarkMessageDialog.Action(
                        "发送",
                        if (action.danger) DarkMessageDialog.Emphasis.DANGER else DarkMessageDialog.Emphasis.PRIMARY,
                    ) {
                        val ok = Mib3ControlLink.perform(action)
                        log(
                            if (ok) {
                                "MIB3: 已发送 ${action.label}（${action.method} ${action.path}）"
                            } else {
                                "MIB3: ${action.label} 未发送（${Mib3ControlLink.status().detail}）"
                            },
                        )
                    },
                ),
        )
    }

    /** 一个带标题的分区。 */
    private fun section(
        activity: Activity,
        title: String,
    ): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (12 * activity.resources.displayMetrics.density).toInt()
                }
            addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(activity.getColor(R.color.m3_primary))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, 2)
                },
            )
        }

    /** 一行「标签 + 值」：返回 (行容器, 值 TextView)。 */
    private fun statusRow(
        activity: Activity,
        label: String,
    ): Pair<LinearLayout, TextView> {
        val density = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            TextView(activity).apply {
                text = label
                setTextColor(activity.getColor(R.color.m3_on_var))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams((78 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
        val value =
            TextView(activity).apply {
                text = "—"
                setTextColor(activity.getColor(R.color.m3_on_surface))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        row.addView(value)
        return row to value
    }
}
