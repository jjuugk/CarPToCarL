package io.github.shaonialife.carptocar.usb

/**
 * USB gadget（设备侧）能力探测。
 *
 * 发送端要走 USB，手机必须能**自己当 USB 设备**并按苹果的描述符现身：
 * 车机会先发厂商请求 `0xC0/0x52 wIndex=4`（切到 CarPlay 配置），
 * 随后才认 USBMUX + NCM。这两件事都要求能写 `/config/usb_gadget`（configfs）
 * 并挂 FunctionFS/`ncm` 功能——**只有 root 能写**，普通 App 与 Shizuku（shell 用户）都不行。
 *
 * 因此本类只做「能不能」的判定，判定结果直接决定 USB 通道是否可用；
 * 探测命令通过 [CommandRunner] 注入，单测不需要真机。
 */
object UsbGadgetProbe {
    /** 执行一条 shell 命令，返回 stdout；失败返回 null。 */
    fun interface CommandRunner {
        fun run(command: String): String?
    }

    /** 苹果 VID：车机只认这个（DiPlay `IphoneUsbHost.APPLE_VENDOR_ID = 0x05ac`）。 */
    const val APPLE_VENDOR_ID = 0x05AC

    /** 车机用来切 CarPlay 配置的厂商请求（DiPlay `CARPLAY_CONFIGURATION_REQUEST`）。 */
    const val CARPLAY_CONFIG_REQUEST = 0x52

    /** 该请求的 wIndex = 4，对应 bConfigurationValue=4（DiPlay `CARPLAY_CONFIGURATION_INDEX = 0x0004`）。 */
    const val CARPLAY_CONFIG_VALUE = 4

    /** usbmuxd 读到的模式串：未设置时 3:3:3:0，设置后 5:3:3:0（DiPlay `usbModeDiagnostic` 注释）。 */
    const val USB_MODE_UNSET = "3:3:3:0"
    const val USB_MODE_SET = "5:3:3:0"

    data class Report(
        val root: Boolean?,
        val udcList: List<String>,
        val configfsPresent: Boolean,
        val ffsPresent: Boolean,
        val kernelFunctions: Set<String>,
        val notes: List<String> = emptyList(),
    ) {
        /** FunctionFS 与 configfs 是硬门槛；NCM 缺失则只能跑通 mux、跑不了 AirPlay 承载网。 */
        val viable: Boolean
            get() = root == true && udcList.isNotEmpty() && configfsPresent && ffsPresent

        val hasNcm: Boolean get() = kernelFunctions.any { it == "usb_f_ncm" || it == "ncm" }

        fun describe(): String =
            buildString {
                append("USB 设备侧能力：root=")
                append(
                    when (root) {
                        true -> "有"
                        false -> "无"
                        null -> "未知"
                    },
                )
                append(" udc=")
                append(udcList.ifEmpty { listOf("无") }.joinToString(","))
                append(" configfs=")
                append(if (configfsPresent) "有" else "无")
                append(" ffs=")
                append(if (ffsPresent) "有" else "无")
                append(" ncm=")
                append(if (hasNcm) "有" else "无")
                append(" → ")
                append(if (viable) "USB 可尝试" else "USB 不可用")
                if (notes.isNotEmpty()) append("（${notes.joinToString("；")}）")
            }
    }

    fun probe(run: CommandRunner): Report {
        val uid = run.run("id -u")?.trim()
        val root =
            when {
                uid == null -> null
                uid == "0" -> true
                else -> false
            }
        val udcList = parseLines(run.run("ls /sys/class/udc 2>/dev/null"))
        val configfsPresent = run.run("test -d /config/usb_gadget && echo yes 2>/dev/null")?.contains("yes") == true
        val ffsPresent =
            run.run("test -d /dev/usb-ffs && echo yes 2>/dev/null")?.contains("yes") == true ||
                run.run("ls -d /dev/ffs-* 2>/dev/null")?.isNotBlank() == true
        val functions = parseFunctions(run.run("ls /sys/module 2>/dev/null"))
        val notes = ArrayList<String>()
        val viable = root == true && udcList.isNotEmpty() && configfsPresent && ffsPresent
        if (root == false) notes += "无 root：configfs 不可写，USB 设备侧无法启用"
        if (configfsPresent && udcList.isEmpty()) notes += "内核没暴露 UDC（这台机可能只支持主机模式）"
        if (viable && !functions.any { it == "usb_f_ncm" || it == "ncm" }) {
            notes += "缺 usb_f_ncm：mux 可能通，但 NCM 承载网起不来（AirPlay 走不了 USB）"
        }
        return Report(root, udcList, configfsPresent, ffsPresent, functions, notes)
    }

    /** `/sys/class/udc` 每行一个控制器名，例如 `a600000.dwc3`。 */
    fun parseLines(output: String?): List<String> =
        output
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?: emptyList()

    /** 从 `/sys/module` 里挑出与 USB gadget 相关的模块名（`usb_f_fs`、`libcomposite`…）。 */
    fun parseFunctions(output: String?): Set<String> =
        parseLines(output)
            .filter { it == "libcomposite" || it.startsWith("usb_f_") || it == "ncm" || it == "configfs" }
            .toSet()
}
