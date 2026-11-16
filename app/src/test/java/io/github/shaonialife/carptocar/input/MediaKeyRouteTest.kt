package io.github.shaonialife.carptocar.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方控退化（1.2.42 ~ 1.2.56）的回归护栏。
 *
 * 那版让切歌也走 CarLife 硬键，硬键返回值只是「报文写出成功」，却被当成「已接管」直接
 * return，`AudioManager` 系统焦点路由永远轮不到 → 方向盘按了没反应。切歌必须留在系统路由里。
 */
class MediaKeyRouteTest {
    @Test
    fun `切歌必须留给系统焦点路由`() {
        assertFalse(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `播放暂停类仍可由硬键接管`() {
        assertTrue(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun `未知键不接管`() {
        assertFalse(MediaKeyRoute.carLifeHardKeyTakes(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(MediaKeyRoute.carLifeHardKeyTakes(0))
    }

    @Test
    fun `日志标签能区分这四类键`() {
        assertEquals("下一曲", MediaKeyRoute.label(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals("上一曲", MediaKeyRoute.label(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals("播放/暂停", MediaKeyRoute.label(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals("keyCode=24", MediaKeyRoute.label(24))
    }
}
