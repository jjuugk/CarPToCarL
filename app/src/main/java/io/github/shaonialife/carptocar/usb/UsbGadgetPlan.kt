package io.github.shaonialife.carptocar.usb

/**
 * 用 configfs 搭一个「苹果发送端」gadget 的**纯函数**计划书：只生成路径与内容，不执行。
 *
 * 依据（DiPlay 车机侧代码反推的硬约束）：
 *
 * 1. **VID 必须是 0x05AC**：车机侧枚举过滤 `vendorId == 0x05ac`（`IphoneUsbHost.APPLE_VENDOR_ID`），
 *    否则连配置切换都不会发；
 * 2. **CarPlay 配置号必须是 4**：车机发 `0xC0/0x52 wIndex=0x0004` 要求切到第 4 套配置
 *    （`CARPLAY_CONFIGURATION_INDEX = 0x0004`）。configfs 的 `bConfigurationValue` 由
 *    内核按注册顺序从 1 递增分配，所以**必须建 4 个 config，CarPlay 功能挂第 4 个**；
 * 3. 功能三件套：`ffs.usbmux`（USBMUX v2 bulk 对）、`ffs.iap2`（iAP2 通道）、
 *    `ncm.usb0`（CDC-NCM，AirPlay 的 IP 承载）；
 * 4. **UDC 绑定必须在 FunctionFS 守护进程写好描述符之后**，否则 `echo > UDC` 直接 -ENODEV。
 *    因此本计划把绑定拆成 [bindCommand] 单独一步。
 */
object UsbGadgetPlan {
    const val DEFAULT_GADGET = "carptocar"
    const val DEFAULT_PRODUCT_ID = 0x12A8
    const val APPLE_VID = UsbGadgetProbe.APPLE_VENDOR_ID
    const val CARPLAY_CONFIG_VALUE = UsbGadgetProbe.CARPLAY_CONFIG_VALUE

    const val FUNCTION_USBMUX = "ffs.usbmux"
    const val FUNCTION_IAP2 = "ffs.iap2"
    const val FUNCTION_NCM = "ncm.usb0"

    sealed interface Step {
        data class MkDir(val path: String) : Step

        data class Write(val path: String, val value: String) : Step

        data class Symlink(val path: String, val target: String) : Step
    }

    /**
     * 生成 gadget 搭建步骤（不含 UDC 绑定）。
     *
     * @param root configfs 挂载点，通常是 `/config/usb_gadget`。
     * @param dropAndroidGadget true 时先生成「解绑 Android 自带 gadget」的步骤——
     *   同一 UDC 只能绑一个 gadget，不先解绑 `g1` 会 `EBUSY`。**注意：这会断掉 adb/USB 网络共享**，
     *   调试时务必先确认已插线以外还有无线调试手段。
     */
    fun build(
        root: String = "/config/usb_gadget",
        name: String = DEFAULT_GADGET,
        productId: Int = DEFAULT_PRODUCT_ID,
        serial: String = "CPTOCAR0001",
        dropAndroidGadget: Boolean = false,
    ): List<Step> {
        val gadget = "$root/$name"
        val steps = ArrayList<Step>()

        if (dropAndroidGadget) {
            // Android 自带 gadget 一般叫 g1；解绑是「写空 UDC」，不是删目录。
            steps += Step.Write("$root/g1/UDC", "")
        }

        steps += Step.MkDir(root)
        steps += Step.MkDir(gadget)
        steps += Step.Write("$gadget/idVendor", hex16(APPLE_VID))
        steps += Step.Write("$gadget/idProduct", hex16(productId))
        steps += Step.Write("$gadget/bcdUSB", "0x0200")
        steps += Step.Write("$gadget/bcdDevice", "0x0100")
        steps += Step.Write("$gadget/strings/0x409/serialnumber", serial)
        steps += Step.Write("$gadget/strings/0x409/manufacturer", "Apple Inc.")
        steps += Step.Write("$gadget/strings/0x409/product", "iPhone")

        // 1..4 四套配置：内核按注册顺序给 bConfigurationValue，CarPlay 必须落在第 4 套。
        for (index in 1..CARPLAY_CONFIG_VALUE) {
            val config = "$gadget/configs/c.$index"
            steps += Step.MkDir(config)
            steps += Step.Write(
                "$config/strings/0x409/configuration",
                if (index == CARPLAY_CONFIG_VALUE) "CarPlay" else "config-$index",
            )
            if (index == CARPLAY_CONFIG_VALUE) {
                steps += Step.Write("$config/MaxPower", "500")
            }
        }

        listOf(FUNCTION_USBMUX, FUNCTION_IAP2, FUNCTION_NCM).forEach { function ->
            steps += Step.MkDir("$gadget/functions/$function")
        }
        listOf(FUNCTION_USBMUX, FUNCTION_IAP2, FUNCTION_NCM).forEach { function ->
            steps += Step.Symlink(
                path = "$gadget/configs/c.$CARPLAY_CONFIG_VALUE/$function",
                target = "../../functions/$function",
            )
        }
        return steps
    }

    /** 绑定 UDC——必须排在 FunctionFS 守护进程写好描述符之后。 */
    fun bindCommand(
        udc: String,
        root: String = "/config/usb_gadget",
        name: String = DEFAULT_GADGET,
    ): String = "echo '$udc' > '$root/$name/UDC'"

    fun unbindCommand(
        root: String = "/config/usb_gadget",
        name: String = DEFAULT_GADGET,
    ): String = "echo '' > '$root/$name/UDC'"

    /** 把步骤渲染成可直接交给 `su -c sh -s` 的脚本（幂等：目录已存在不算错）。 */
    fun toShell(steps: List<Step>): String =
        buildString {
            appendLine("set -e")
            steps.forEach { step ->
                when (step) {
                    is Step.MkDir -> appendLine("mkdir -p '${step.path}' 2>/dev/null || true")
                    is Step.Write -> appendLine("printf '%s' '${step.value}' > '${step.path}'")
                    is Step.Symlink ->
                        appendLine(
                            "ln -sfn '${step.target}' '${step.path}' 2>/dev/null || true",
                        )
                }
            }
        }

    fun hex16(value: Int): String = "0x%04x".format(value)
}
