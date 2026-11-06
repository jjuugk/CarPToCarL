package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首帧策略：RECORD 之后到底「立即用缓存关键帧起播」还是「等引擎当期 IDR」。
 *
 * 依据是两轮实车日志对着看（2026-11-06）：
 * - 同源项目在奥迪 CL39 上回放缓存 GOP，`RECORD 完成` 到 `帧 #1` 只隔 **4 毫秒**；
 * - 我们 1.2.25 在奥迪 3810 上清空缓存等当期 IDR，同一个间隔是 **5.5 秒**。
 *
 * 4580 的 HMI 在屏幕流 SETUP 后只等很短时间就要画面，所以默认改成立即起播；
 * 但只有「缓存首帧是关键帧且自带 SPS」才允许——否则会退化成
 * 「旧 SPS + 新参考帧」的混搭，那正是 1.2.25 当初要躲的坑。
 */
class FirstFramePolicyTest {
    @Test
    fun `缓存首帧自带 SPS 的关键帧时立即起播`() {
        assertTrue(
            FirstFramePolicy.shouldReplay(
                policyEnabled = true,
                firstFrameIsKey = true,
                firstFrameHasSps = true,
            ),
        )
    }

    @Test
    fun `首帧不是关键帧就等当期 IDR`() {
        assertFalse(
            FirstFramePolicy.shouldReplay(
                policyEnabled = true,
                firstFrameIsKey = false,
                firstFrameHasSps = true,
            ),
        )
    }

    @Test
    fun `关键帧不带 SPS 也不回放——避免旧 csd 配新参考帧`() {
        assertFalse(
            FirstFramePolicy.shouldReplay(
                policyEnabled = true,
                firstFrameIsKey = true,
                firstFrameHasSps = false,
            ),
        )
    }

    @Test
    fun `关掉策略就回到 1_2_25 行为`() {
        assertFalse(
            FirstFramePolicy.shouldReplay(
                policyEnabled = false,
                firstFrameIsKey = true,
                firstFrameHasSps = true,
            ),
        )
    }

    @Test
    fun `默认值是可以立即起播的`() {
        // 默认必须是「立即起播」，否则 CL3D 上又会空等 5 秒
        assertTrue(FirstFramePolicy.DEFAULT_USE_CACHED_GOP)
    }
}
