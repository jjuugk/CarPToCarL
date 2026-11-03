package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标帧率判定：取「车机声明」与「本机出帧能力（60）」的较小值，车机没声明按 60。
 *
 * 这条规则的全部依据是「`maxFps == 0` 代表车机没声明」——所以 `AirPlayClient`
 * 里那个 `?: 30` 的兜底必须不存在，否则「车机要 30」与「车机没说话」在数据上
 * 分不开，规则永远退化成「按 30 发」。
 */
class StreamFpsTest {
    @Test
    fun `车机没声明按 60`() {
        assertEquals(60, StreamFps.resolve(0))
        assertEquals(StreamFps.DEFAULT_FPS, StreamFps.resolve(0))
        // 负数同样视为未声明（字段解析出错时的防御）
        assertEquals(60, StreamFps.resolve(-1))
    }

    @Test
    fun `车机报了且不超过本机能力就跟随`() {
        assertEquals(60, StreamFps.resolve(60))
        assertEquals(30, StreamFps.resolve(30))
        assertEquals(45, StreamFps.resolve(45))
    }

    @Test
    fun `荒谬低值夹到下限`() {
        // 车机报 10 / 15 这类值多半是字段解析错位，照发会卡成 PPT
        assertEquals(StreamFps.MIN_TARGET_FPS, StreamFps.resolve(10))
        assertEquals(StreamFps.MIN_TARGET_FPS, StreamFps.resolve(1))
    }

    @Test
    fun `车机报得再高也不超过本机出帧能力`() {
        // 参考 CarVeX 的参数决策：min(接收端 maxFPS, 宿主 hint)。
        // 声明高于编码器实际能出的帧，车机按更高节拍排帧却等不到帧，反而抖。
        assertEquals(StreamFps.DEFAULT_FPS, StreamFps.resolve(120))
        assertEquals(StreamFps.DEFAULT_FPS, StreamFps.resolve(240))
        assertEquals(StreamFps.DEFAULT_FPS, StreamFps.resolve(999))
    }

    @Test
    fun `日志说明区分四种来源`() {
        assertTrue(StreamFps.describe(0).contains("未声明"))
        assertTrue(StreamFps.describe(30).contains("跟随车机"))
        assertTrue(StreamFps.describe(5).contains("低于下限"))
        assertTrue(StreamFps.describe(999).contains("高于本机"))
    }

    @Test
    fun `默认值与显示器偏好一致`() {
        // 两者必须同步：改了一处忘了另一处，会话前与会话后就对不上
        assertEquals(
            io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs.FPS,
            StreamFps.DEFAULT_FPS,
        )
    }
}
