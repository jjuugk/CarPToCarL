package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按通用系 Cinemo 那套连接容错对待」的判定。
 *
 * 这条判据手上挂着 0x4300 报文体（`preferDualAvailability`）、宽限 90s 和不重认证三件事，
 * 判错的代价是**连接容错被拿掉**：11-16 曾收紧成「全部组件名都含 Cinemo」，
 * 把带 `CinemoHIDComponent` 的宝马 MGU-81 排除在外，实测入网成功率和 RECORD 都掉下来
 * （详见日志 2026-11-17），11-17 回退到「报文体出现 Cinemo 即命中」。
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
    fun bmwWithCinemoHidComponentIsTreatedAsCinemoStack() {
        assertTrue(
            "带 Cinemo 组件就给足三档连接容错——MGU-81 实测需要双标识 + 90s + 不重认证",
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
    fun singleCinemoComponentIsEnough() {
        val body =
            Iap2Csm.string(2, "BMW Group") +
                component(0x11, 1, "CinemoHIDComponent")
        assertTrue(
            "哪怕只有一个 Cinemo 组件也按通用系给足容错——宁可多给宽限，不要连不上",
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
