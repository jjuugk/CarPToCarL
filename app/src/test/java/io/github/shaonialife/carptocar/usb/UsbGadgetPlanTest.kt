package io.github.shaonialife.carptocar.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测与配置计划单测。
 *
 * 这些用例的价值不在「跑通 USB」（那要 root 真机），而在钉死两个**真机必踩**的判据：
 * 没有 root 就不能宣称 USB 可用；CarPlay 必须落在第 4 套配置上。
 */
class UsbGadgetProbeTest {
    private fun runner(vararg pairs: Pair<String, String?>): UsbGadgetProbe.CommandRunner =
        UsbGadgetProbe.CommandRunner { command ->
            pairs.firstOrNull { command == it.first || command.contains(it.first) }?.second
        }

    @Test
    fun reportsViableWhenRootAndUdcAndFfsExist() {
        val report =
            UsbGadgetProbe.probe(
                runner(
                    "id -u" to "0\n",
                    "ls /sys/class/udc" to "a600000.dwc3\n",
                    "/config/usb_gadget" to "yes\n",
                    "/dev/usb-ffs" to "yes\n",
                    "ls /sys/module" to "usb_f_fs\nusb_f_ncm\nlibcomposite\nxyz\n",
                ),
            )
        assertTrue(report.viable)
        assertTrue(report.hasNcm)
        assertEquals(listOf("a600000.dwc3"), report.udcList)
        assertTrue(report.describe().contains("USB 可尝试"))
    }

    @Test
    fun notViableWithoutRoot() {
        val report =
            UsbGadgetProbe.probe(
                runner(
                    "id -u" to "2000\n",
                    "ls /sys/class/udc" to "a600000.dwc3\n",
                    "/config/usb_gadget" to null,
                    "/dev/usb-ffs" to null,
                ),
            )
        assertFalse(report.viable)
        assertTrue(report.describe().contains("USB 不可用"))
    }

    @Test
    fun warnsWhenNcmModuleMissing() {
        val report =
            UsbGadgetProbe.probe(
                runner(
                    "id -u" to "0\n",
                    "ls /sys/class/udc" to "dwc3.0\n",
                    "/config/usb_gadget" to "yes\n",
                    "/dev/usb-ffs" to "yes\n",
                    "ls /sys/module" to "usb_f_fs\nlibcomposite\n",
                ),
            )
        assertTrue(report.viable)
        assertFalse(report.hasNcm)
        assertTrue(report.notes.any { it.contains("NCM") })
    }
}

class UsbGadgetPlanTest {
    @Test
    fun gadgetUsesAppleVidAndFourConfigs() {
        val steps = UsbGadgetPlan.build(productId = 0x12A8)
        val writes = steps.filterIsInstance<UsbGadgetPlan.Step.Write>().associate { it.path to it.value }
        val dirs = steps.filterIsInstance<UsbGadgetPlan.Step.MkDir>().map { it.path }

        assertEquals("0x05ac", writes["/config/usb_gadget/carptocar/idVendor"])
        assertEquals("0x12a8", writes["/config/usb_gadget/carptocar/idProduct"])
        // 车机发 wIndex=4，所以第 4 套配置必须存在且叫 CarPlay
        for (index in 1..4) {
            assertTrue("缺 c.$index", dirs.contains("/config/usb_gadget/carptocar/configs/c.$index"))
        }
        assertEquals(
            "CarPlay",
            writes["/config/usb_gadget/carptocar/configs/c.4/strings/0x409/configuration"],
        )
    }

    @Test
    fun onlyCarplayConfigCarriesFunctions() {
        val steps = UsbGadgetPlan.build()
        val links = steps.filterIsInstance<UsbGadgetPlan.Step.Symlink>()
        assertEquals(3, links.size)
        assertTrue(links.all { it.path.contains("/configs/c.4/") })
        assertTrue(links.any { it.path.endsWith(UsbGadgetPlan.FUNCTION_NCM) })
        assertTrue(links.any { it.path.endsWith(UsbGadgetPlan.FUNCTION_USBMUX) })
        assertTrue(links.any { it.path.endsWith(UsbGadgetPlan.FUNCTION_IAP2) })
    }

    @Test
    fun shellScriptIsOrderedAndQuotesPaths() {
        val script = UsbGadgetPlan.toShell(UsbGadgetPlan.build())
        // UDC 绑定不在脚本里——必须等 FunctionFS 守护进程写好描述符
        assertFalse(script.contains("/UDC"))
        assertTrue(script.indexOf("mkdir -p '/config/usb_gadget/carptocar'") <
            script.indexOf("printf '%s' '0x05ac'"))
        assertTrue(UsbGadgetPlan.bindCommand("a600000.dwc3")
            .contains("'a600000.dwc3' > '/config/usb_gadget/carptocar/UDC'"))
    }

    @Test
    fun droppingAndroidGadgetUnbindsG1() {
        val steps = UsbGadgetPlan.build(dropAndroidGadget = true)
        val first = steps.first()
        assertTrue(first is UsbGadgetPlan.Step.Write)
        assertEquals("/config/usb_gadget/g1/UDC", (first as UsbGadgetPlan.Step.Write).path)
        assertEquals("", first.value)
    }
}
