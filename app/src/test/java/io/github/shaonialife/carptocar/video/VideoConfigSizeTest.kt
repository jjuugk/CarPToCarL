package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VideoConfig 声明尺寸的选择规则。
 *
 * 真车按这个尺寸建解码器与 surface，所以它是**画布契约**，不是码流尺寸的回声。
 * 2026-11-04 奥迪 3810（画布 1412×654）整屏黑的直接原因就是把优先级做成了
 * 「引擎码流优先」：引擎白名单把 1412×654@60 降档成 1280×720@20，声明随之变成
 * 720p，车机就拿着 720p 画布去解 1412×654 的屏。
 *
 * 这类故障最难查的地方是**日志里没有任何错误**：会话、送帧、写入耗时全正常，
 * 测试台（WinPlay/DiPlay 不读这几个头字段）也照样有画面。所以必须锁死优先级。
 */
class VideoConfigSizeTest {
    @Test
    fun canvasWinsOverDowngradedStream() {
        val size =
            VideoSender.resolveConfigSize(
                canvas = 1412 to 654,
                live = 1280 to 720,
                fallbackWidth = 1280,
                fallbackHeight = 720,
                sps = 1280 to 720,
            )
        assertEquals(1412 to 654, size)
    }

    @Test
    fun fallsBackToStreamWhenCanvasUnknown() {
        val size =
            VideoSender.resolveConfigSize(
                canvas = null,
                live = 1280 to 720,
                fallbackWidth = 1280,
                fallbackHeight = 720,
                sps = 1280 to 720,
            )
        assertEquals(1280 to 720, size)
    }

    @Test
    fun zeroCanvasIsTreatedAsUnknown() {
        val size =
            VideoSender.resolveConfigSize(
                canvas = 0 to 0,
                live = 1280 to 720,
                fallbackWidth = 1280,
                fallbackHeight = 720,
                sps = null,
            )
        assertEquals(1280 to 720, size)
    }

    @Test
    fun fallsBackToConstructorThenSps() {
        assertEquals(
            1130 to 523,
            VideoSender.resolveConfigSize(null, null, 1130, 523, 1920 to 1080),
        )
        assertEquals(
            1920 to 1080,
            VideoSender.resolveConfigSize(null, null, 0, 0, 1920 to 1080),
        )
    }

    @Test
    fun headerCarriesDeclaredCanvasAsTwoFloats() {
        val header = VideoSender.buildVideoConfigHeader(bodySize = 33, width = 1412, height = 654)
        val buffer = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1412f, buffer.float, 0.001f)
        assertEquals(654f, buffer.float, 0.001f)
        // small_param[1] = Encrypted：不声明的话车机按明文 NAL 解，同样黑屏。
        assertEquals(0x04, header[6].toInt() and 0xFF)
    }

    @Test
    fun noViewAreaLeavesGeometryFieldsZero() {
        // 默认形态（对齐 AMCar：只写解码尺寸、不写视口）：32..48 必须全是 0。
        // 旧车机不读这些字段，多写反而是「给车机的额外信息」，所以默认不写。
        val header = VideoSender.buildVideoConfigHeader(bodySize = 33, width = 1280, height = 720)
        for (offset in 32 until 48) {
            assertEquals("偏移 $offset 必须为 0", 0, header[offset].toInt())
        }
    }

    @Test
    fun viewAreaIsWrittenSeparatelyFromDecoderSize() {
        // 4580：解码尺寸与显示视口是两个字段。码流只能 720p 时，
        // params[1] 报 1280x720、params[4] 报车机画布 1412x654。
        val header =
            VideoSender.buildVideoConfigHeader(
                bodySize = 33,
                width = 1280,
                height = 720,
                viewArea = 1412 to 654,
            )
        val decoder = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1280f, decoder.float, 0.001f)
        assertEquals(720f, decoder.float, 0.001f)

        val origin = ByteBuffer.wrap(header, 32, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, origin.float, 0.001f)
        assertEquals(0f, origin.float, 0.001f)

        val viewport = ByteBuffer.wrap(header, 40, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1412f, viewport.float, 0.001f)
        assertEquals(654f, viewport.float, 0.001f)
    }

    @Test
    fun spsParseFailureIsAlwaysReported() {
        // 旧实现只在「解析成功且不一致」时打日志，解析失败与恰好一致在日志里一模一样。
        val line = VideoSender.describeSpsConfig(null, 1412 to 654, "缓存csd兜底", false)
        assertTrue("解析失败必须出现：$line", line.contains("解析失败"))
        assertTrue("csd 来源必须可见：$line", line.contains("缓存csd兜底"))
        assertTrue("声明尺寸必须可见：$line", line.contains("declared=1412x654"))
        assertTrue("未解析时要显式告警：$line", line.contains("⚠"))
    }

    @Test
    fun spsDiagnosisMarksMismatchAndMatch() {
        // 声明的判据是 visible（去裁剪后的真实画面），不是 coded（宏块对齐值）。
        val aligned = VideoSender.SpsSize(codedWidth = 1424, codedHeight = 656, visibleWidth = 1412, visibleHeight = 654)
        val match = VideoSender.describeSpsConfig(aligned, 1412 to 654, "当期帧自带SPS", true)
        assertTrue("一致要显式确认：$match", match.contains("✓与 visible 一致"))
        assertTrue("coded 与 visible 必须分开：$match", match.contains("coded=1424x656") && match.contains("visible=1412x654"))
        assertTrue("裁剪量必须可见：$match", match.contains("裁剪 12x2"))

        val smaller = VideoSender.SpsSize(codedWidth = 1280, codedHeight = 720, visibleWidth = 1280, visibleHeight = 720)
        val mismatch = VideoSender.describeSpsConfig(smaller, 1412 to 654, "当期帧自带SPS", true)
        assertTrue("不一致要显式告警：$mismatch", mismatch.contains("⚠与 visible 不一致"))
        assertTrue("差值要带符号：$mismatch", mismatch.contains("+132x-66"))
    }

    @Test
    fun spsCropIsReportedEvenWithoutMismatch() {
        // 对齐值本身就是画面尺寸时也要说清楚「无裁剪」，否则 coded 与 visible 相同会被误读成解析错。
        val noCrop = VideoSender.SpsSize(codedWidth = 1280, codedHeight = 720, visibleWidth = 1280, visibleHeight = 720)
        val line = VideoSender.describeSpsConfig(noCrop, 1280 to 720, "本机编码器输出SPS", true)
        assertTrue("无裁剪也要写出来：$line", line.contains("无裁剪"))

        val insane = VideoSender.SpsSize(codedWidth = 272, codedHeight = 112, visibleWidth = 272, visibleHeight = 112)
        val bad = VideoSender.describeSpsConfig(insane, 1412 to 654, "本机编码器输出SPS", false)
        assertTrue("不合理尺寸要单独标注：$bad", bad.contains("⚠visible 不合理"))
    }
}
