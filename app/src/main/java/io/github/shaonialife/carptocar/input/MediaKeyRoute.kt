package io.github.shaonialife.carptocar.input

import android.view.KeyEvent

/**
 * 媒体键的路由判定：**哪些键允许交给 CarLife `0x68008` 硬键**。
 *
 * 存在的理由是一次真实退化（1.2.42 → 1.2.57 撤回）：那版把 `KEYCODE_MEDIA_NEXT` /
 * `KEYCODE_MEDIA_PREVIOUS` 也一并塞进硬键，而 [io.github.shaonialife.carptocar.carlife.CarLifeHijack.sendMediaKey]
 * 的返回值只是「socket 写成功」，`SessionController.dispatchMediaKey` 拿它当「已接管」
 * 直接 `return`，于是 `AudioManager` 系统焦点路由永远轮不到——**方向盘方控整体失灵**。
 *
 * 证据（2026-11-16 日志）：9 次 `CARLIFE: 车机硬键 0x10/0x0F` 之后，没有任何 `0x10035`
 * 媒体信息、没有新的音频格式宣告、PCM 电平也不变 → 引擎收到但不执行。
 *
 * 现在只放行播放/暂停类：那几条路的目的是「未授通知使用权时车机按暂停也要真停住音乐」
 * （[io.github.shaonialife.carptocar.carlife.CarLifeHijack.pauseMediaFromCar]），切歌没有
 * 这个诉求，必须留给系统焦点路由。
 */
object MediaKeyRoute {
    /** @return true = 这个媒体键可以由 CarLife 硬键接管（仅播放/暂停类）。 */
    fun carLifeHardKeyTakes(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            -> true
            else -> false
        }

    /** 日志用的中文键名：只为了让人一眼看出这次按键落到哪。 */
    fun label(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> "下一曲"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一曲"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放/暂停"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "播放"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "暂停"
            KeyEvent.KEYCODE_MEDIA_STOP -> "停止"
            else -> "keyCode=$keyCode"
        }
}
