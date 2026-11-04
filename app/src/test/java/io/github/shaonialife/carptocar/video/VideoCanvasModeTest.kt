package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布声明模式的解析。
 *
 * 这是实车排查的开关：声明「车机画布」还是「码流尺寸」，两种模式在真车上表现完全不同，
 * 而偏好是手写配置，解析错一次就会把整轮实验带偏。
 */
class VideoCanvasModeTest {

    @Test
    fun `stream 表示声明码流尺寸`() {
        assertTrue(VideoCanvasMode.parse("stream"))
        assertTrue(VideoCanvasMode.parse(" STREAM "))
    }

    @Test
    fun `car 表示声明车机画布`() {
        assertFalse(VideoCanvasMode.parse("car"))
    }

    @Test
    fun `无法识别的值回退到构建默认`() {
        val expected = VideoCanvasMode.DEFAULT_MODE == VideoCanvasMode.MODE_STREAM
        assertEquals(expected, VideoCanvasMode.parse(null))
        assertEquals(expected, VideoCanvasMode.parse(""))
        assertEquals(expected, VideoCanvasMode.parse("码流"))
    }
}
