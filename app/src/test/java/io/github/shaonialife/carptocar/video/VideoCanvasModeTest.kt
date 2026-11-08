package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸声明形态的解析与派生开关。
 *
 * 这是实车排查的开关：三种形态在真车上表现完全不同，而偏好是手写配置，
 * 解析错一次就会把整轮实验带偏。
 */
class VideoCanvasModeTest {

    @Test
    fun `raw 是构建默认 对齐 AMCar`() {
        assertEquals(VideoCanvasMode.Mode.RAW, VideoCanvasMode.DEFAULT_MODE)
    }

    @Test
    fun `stream 表示分离声明`() {
        assertEquals(VideoCanvasMode.Mode.SPLIT, VideoCanvasMode.parse("stream"))
        assertEquals(VideoCanvasMode.Mode.SPLIT, VideoCanvasMode.parse(" STREAM "))
    }

    @Test
    fun `car 表示声明车机画布`() {
        assertEquals(VideoCanvasMode.Mode.CAR, VideoCanvasMode.parse("car"))
    }

    @Test
    fun `raw 表示只写解码尺寸`() {
        assertEquals(VideoCanvasMode.Mode.RAW, VideoCanvasMode.parse("raw"))
        assertEquals(VideoCanvasMode.Mode.RAW, VideoCanvasMode.parse(" RAW "))
    }

    @Test
    fun `无法识别的值回退到构建默认`() {
        assertEquals(VideoCanvasMode.DEFAULT_MODE, VideoCanvasMode.parse(null))
        assertEquals(VideoCanvasMode.DEFAULT_MODE, VideoCanvasMode.parse(""))
        assertEquals(VideoCanvasMode.DEFAULT_MODE, VideoCanvasMode.parse("码流"))
    }

    @Test
    fun `只有 car 用画布当解码尺寸`() {
        assertFalse(VideoCanvasMode.sizeFromCanvasFor(VideoCanvasMode.Mode.RAW))
        assertFalse(VideoCanvasMode.sizeFromCanvasFor(VideoCanvasMode.Mode.SPLIT))
        assertTrue(VideoCanvasMode.sizeFromCanvasFor(VideoCanvasMode.Mode.CAR))
    }

    @Test
    fun `只有 stream 写视口`() {
        assertFalse(VideoCanvasMode.writeViewportFor(VideoCanvasMode.Mode.RAW))
        assertTrue(VideoCanvasMode.writeViewportFor(VideoCanvasMode.Mode.SPLIT))
        assertFalse(VideoCanvasMode.writeViewportFor(VideoCanvasMode.Mode.CAR))
    }

    @Test
    fun `回写偏好用协议值而不是枚举名`() {
        assertEquals("raw", VideoCanvasMode.nameOf(VideoCanvasMode.Mode.RAW))
        assertEquals("stream", VideoCanvasMode.nameOf(VideoCanvasMode.Mode.SPLIT))
        assertEquals("car", VideoCanvasMode.nameOf(VideoCanvasMode.Mode.CAR))
    }
}
