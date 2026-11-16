package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「通用系 Cinemo 栈」判定。
 *
 * 这条判据手上挂着 0x4300 报文体（`preferDualAvailability`）、宽限 90s 和不重认证三件事，
 * 误判的代价不是日志好看不好看，而是**给非通用系车机发错报文体**。
 *
 * 2026-11-16 的现场：宝马 MGU-81 的 0x1D01 传输组件是
 * `BMW 42056` / `BMW 42056 Wifi` / **`CinemoHIDComponent`**——旧判据「整段报文体里出现
 * Cinemo 就算通用系」直接把 MGU-81 认成通用系。
 */
class CinemoStackDetectionTest {
    private fun component(
        paramId: Int,
        kind: Int,
        name: String,
    ): ByteArray =
        Iap2Csm.group(
            paramId,
            Iap2Csm.param(0, byteArrayOf(0, kind.toByte())),
            Iap2Csm.string(1, name),
            Iap2Csm.param(2, byteArrayOf(1)),
        )

    /** 宝马 MGU-81：BMW 组件 + 一个 Cinemo HID 组件混着。 */
    private fun bmwMguBody(): ByteArray =
        Iap2Csm.string(0, "BMW 42056") +
            Iap2Csm.string(2, "BMW Group") +
            component(0x11, 2, "BMW 42056") +
            component(0x12, 1, "CinemoHIDComponent") +
            component(0x18, 3, "BMW 42056 Wifi")

    /** 别克 E2LB / 凯迪拉克：三个组件全是 Cinemo。 */
    private fun gmBody(): ByteArray =
        Iap2Csm.string(0, "BUICK_3799") +
            Iap2Csm.string(2, "Saic General Motors") +
            component(0x11, 2, "CinemoBTComponent") +
            component(0x12, 1, "CinemoHIDRemote") +
            component(0x18, 3, "CinemoWIFIComponent")

    @Test
    fun bmwWithCinemoHidComponentIsNotCinemoStack() {
        assertFalse(
            "BMW 只是把 HID 中间件换成 Cinemo 组件，栈不是通用系",
            Iap2SenderSession.isCinemoStack(bmwMguBody(), "bmw 42056 mgu-81 bmw group"),
        )
    }

    @Test
    fun allCinemoComponentsAreCinemoStack() {
        val body = gmBody()
        assertTrue(
            "组件名全是 Cinemo 且厂商不是 GM（例如雪佛兰改名）时也要认出来",
            Iap2SenderSession.isCinemoStack(body, "buick_3799 e2lb chevrolet"),
        )
    }

    @Test
    fun generalMotorsManufacturerIsEnough() {
        // 厂商名带 General Motors 就直接判，哪怕组件还没解析出来
        assertTrue(
            Iap2SenderSession.isCinemoStack(ByteArray(0), "buick_3799 e2lb saic general motors"),
        )
    }

    @Test
    fun singleCinemoComponentIsNotEnough() {
        val body =
            Iap2Csm.string(2, "BMW Group") +
                component(0x11, 1, "CinemoHIDComponent")
        assertFalse(
            "只有一个组件时无法证明整条栈是 Cinemo——按通用系处理会发错 0x4300 报文体",
            Iap2SenderSession.isCinemoStack(body, "bmw group"),
        )
    }

    @Test
    fun componentNamesAreReadFromTheRightParams() {
        val names = Iap2SenderSession.transportComponentNames(bmwMguBody())
        assertTrue(names.toString(), names.contains("BMW 42056"))
        assertTrue(names.toString(), names.contains("CinemoHIDComponent"))
        assertTrue(names.toString(), names.contains("BMW 42056 Wifi"))
    }
}
