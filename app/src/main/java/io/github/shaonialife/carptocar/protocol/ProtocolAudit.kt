package io.github.shaonialife.carptocar.protocol

/**
 * 新旧协议的**一致性审计**：把「协议规范说了什么」与「我们本轮实际做了什么 / 车机实际回了什么」
 * 对不上的地方，逐条落成可导出的问题清单。
 *
 * 规范出处：[docs/CARPLAY-WIRELESS-HEADUNIT-SPEC.md]（每个 [Code] 都带章节号，
 * 日志里直接写 `依据 SPEC §x.y`，排查时不用再翻代码猜「为什么判定它有问题」）。
 *
 * 为什么单独一个对象而不是散在各处打日志：同一个根因会在链路上重复出现
 * （0x5702 重发、端点热更新、配对重试），散着打会把日志刷成一锅；这里按
 * `(code, detail)` 去重，**同一问题本轮只说一次**，并保留一份结构化清单供
 * 导出/界面展示。
 *
 * 线程模型：iAP2 收帧线程、AirPlay 连接线程、主控线程都会进来，全部走 [lock]；
 * [sink] 在锁外调用，避免日志回调里再调 [record] 造成死锁。
 */
object ProtocolAudit {
    /** 严重度：只有 ERROR 表示「协议层面明确不一致」，WARN = 判据可能误导，INFO = 只作记录。 */
    enum class Severity(
        val label: String,
    ) {
        INFO("提示"),
        WARN("警告"),
        ERROR("错误"),
    }

    /**
     * 审计条目。
     *
     * @param spec 规范章节号（`CARPLAY-WIRELESS-HEADUNIT-SPEC.md`），用于「这条为什么算问题」
     * @param title 一句话结论（日志与界面都用它，不含变量部分）
     */
    enum class Code(
        val severity: Severity,
        val spec: String,
        val title: String,
    ) {
        // ---- WiFi 凭据两代协议（SPEC §2.10）----
        WIFI_GEN_MISMATCH(Severity.WARN, "§2.10", "新旧 WiFi 凭据代际判错"),
        LEGACY_WIFI_SILENT(Severity.WARN, "§2.10 / §7", "旧代 0x5702 无应答（该车实际走 0x4301）"),
        WIFI_NEW_WITHOUT_AP(Severity.WARN, "§3.2", "新代车机既无 0x4301 也无 mDNS 端点"),

        // ---- AirPlay 时代（SPEC §3.3 的 bit38）----
        PAIRING_POLICY_MISMATCH(Severity.WARN, "§3.3", "TXT 声明的配对能力与实测不符"),
        ERA_SWITCHED(Severity.INFO, "§3.3", "同一台车机的协议代际发生变化"),

        // ---- 通告一致性（SPEC §3.2 / §8 步 7 / §7）----
        FEATURES_TXT_VS_INFO(Severity.ERROR, "§3.2 / §8 步 7", "TXT features 与 /info features 不一致"),
        DEVICE_ID_MISMATCH(Severity.ERROR, "§3.2 / §7", "TXT deviceid 与 /info deviceID 不一致"),

        // ---- 越权（SPEC §2.8 / §7 末行）----
        UNSOLICITED_MESSAGE(Severity.WARN, "§2.8 / §7", "发了车机未声明可收的消息"),

        // ---- MFi（SPEC §2.9）----
        MFI_LENGTH_REJECTED(Severity.INFO, "§2.9", "MFi 挑战长度被车机拒绝"),
    }

    /** 一条问题。`detail` 带变量（发/收方、地址、长度），`evidence` 是原始字节或计数。 */
    data class Issue(
        val code: Code,
        val detail: String,
        val evidence: String? = null,
        val atMillis: Long = System.currentTimeMillis(),
    )

    /** 上限：异常链路可能每轮都产出新 detail，不封顶会吃内存（日志文件才是完整记录）。 */
    private const val MAX_ISSUES = 64

    private val lock = Any()
    private val issues = ArrayList<Issue>()
    private val seen = HashSet<String>()

    /**
     * 实时输出口：由主控在会话开始时装成 `appendLog`（进 logcat + 文件 + 界面）。
     * 为 null 时只记录不打印（单测/纯 JVM）。
     */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /**
     * 记一条问题。
     *
     * @return 本次新记下的条目；重复（同 code + detail）或超限时返回 null
     */
    fun record(
        code: Code,
        detail: String,
        evidence: String? = null,
    ): Issue? {
        val issue = Issue(code, detail, evidence)
        val line: String
        synchronized(lock) {
            if (issues.size >= MAX_ISSUES) return null
            if (!seen.add(code.name + '|' + detail)) return null
            issues += issue
            line = format(issue)
        }
        sink?.invoke(line)
        return issue
    }

    /** 新会话开始前清空（跨会话累积会让「本轮有没有问题」失去意义）。 */
    fun reset() {
        synchronized(lock) {
            issues.clear()
            seen.clear()
        }
    }

    fun snapshot(): List<Issue> = synchronized(lock) { issues.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { issues.isEmpty() }

    fun count(severity: Severity): Int = synchronized(lock) { issues.count { it.code.severity == severity } }

    /** 是否有「协议层面明确不一致」的条目。 */
    fun hasError(): Boolean = count(Severity.ERROR) > 0

    /** 汇总一行（连接成功/拆链时打一次，避免用户翻几百行日志找问题）。 */
    fun summary(): String {
        val all = snapshot()
        if (all.isEmpty()) return "PROTO 一致性审计通过：未发现与本规范不一致的现象"
        val bySeverity =
            Severity.values().mapNotNull { severity ->
                val n = all.count { it.code.severity == severity }
                if (n == 0) null else "$n ${severity.label}"
            }
        val codes = all.map { it.code.name }.distinct().joinToString(" ")
        return "PROTO 一致性审计：共 ${all.size} 项（${bySeverity.joinToString(" / ")}）→ $codes"
    }

    /** 单条格式化：`PROTO! ERROR [CODE] 结论 —— 细节（依据 SPEC §x.y；证据 …）`。 */
    fun format(issue: Issue): String {
        val evidence =
            issue.evidence
                ?.takeIf { it.isNotBlank() }
                ?.let { "；证据 $it" }
                .orEmpty()
        return "PROTO! ${issue.code.severity.name} [${issue.code.name}] ${issue.code.title}" +
            " —— ${issue.detail}（依据 SPEC ${issue.code.spec}$evidence）"
    }

    /** 把当前清单整份打出去（拆链/导出日志前调用）。 */
    fun report(log: (String) -> Unit) {
        val all = snapshot()
        if (all.isEmpty()) {
            log(summary())
            return
        }
        all.forEach { log(format(it)) }
        log(summary())
    }

    /** 多行文本（界面「复制问题清单」用，格式与日志一致）。 */
    fun render(): String {
        val all = snapshot()
        if (all.isEmpty()) return summary()
        return all.joinToString("\n") { format(it) } + "\n" + summary()
    }
}
