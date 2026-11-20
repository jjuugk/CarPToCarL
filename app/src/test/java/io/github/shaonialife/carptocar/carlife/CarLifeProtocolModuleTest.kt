package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 65574 模块状态回包（98344）的筛选回归。
 *
 * 依据 AuDiCar 1.0.47 `CarLifeVoiceProtocol.modulesToEnable()` 的实证：
 * module 4（语音）/ 6（麦克风）上报 0 是「当前没有语音会话 / 用手机麦」的真实状态，
 * 不能像其它模块那样回一条 98344 把它强行置 1——那等于压着引擎的语音态。
 * 我们这边正是「0x68008 0x21 已送达但语音模块始终=0」的可操作解释。
 */
class CarLifeProtocolModuleTest {
    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun allModules(vararg overrides: Pair<Int, Int>): List<Pair<Int, Int>> {
        val base =
            mutableListOf(
                CarLifeProtocol.Module.PHONE to 0,
                CarLifeProtocol.Module.NAVI to 0,
                CarLifeProtocol.Module.MUSIC to 0,
                CarLifeProtocol.Module.VR to 0,
                CarLifeProtocol.Module.MIC to 0,
                CarLifeProtocol.Module.CRUISE to 0,
                CarLifeProtocol.Module.CRUISE_FOLLOW to 0,
            )
        overrides.forEach { (id, status) ->
            val i = base.indexOfFirst { it.first == id }
            check(i >= 0) { "未知模块 $id" }
            base[i] = id to status
        }
        return base
    }

    @Test
    fun `全零上报时不回置语音与麦克风模块`() {
        assertEquals(
            listOf(
                CarLifeProtocol.Module.PHONE,
                CarLifeProtocol.Module.NAVI,
                CarLifeProtocol.Module.MUSIC,
                CarLifeProtocol.Module.CRUISE,
                CarLifeProtocol.Module.CRUISE_FOLLOW,
            ),
            CarLifeProtocol.modulesToEnable(allModules()),
        )
    }

    @Test
    fun `语音与麦克风为一时照旧回置`() {
        val enabled =
            CarLifeProtocol.modulesToEnable(
                allModules(
                    CarLifeProtocol.Module.VR to 1,
                    CarLifeProtocol.Module.MIC to 1,
                ),
            )
        assert(CarLifeProtocol.Module.VR in enabled)
        assert(CarLifeProtocol.Module.MIC in enabled)
        assertEquals(7, enabled.size)
    }

    @Test
    fun `增量包里只有语音模块归零时不发控制包`() {
        assertEquals(
            emptyList<Int>(),
            CarLifeProtocol.modulesToEnable(listOf(CarLifeProtocol.Module.VR to 0)),
        )
        assertEquals(
            emptyList<Int>(),
            CarLifeProtocol.modulesToEnable(
                listOf(
                    CarLifeProtocol.Module.VR to 0,
                    CarLifeProtocol.Module.MIC to 0,
                ),
            ),
        )
    }

    @Test
    fun `其它模块的零仍照旧回置`() {
        val enabled =
            CarLifeProtocol.modulesToEnable(
                listOf(
                    CarLifeProtocol.Module.MUSIC to 0,
                    CarLifeProtocol.Module.VR to 0,
                ),
            )
        assertEquals(listOf(CarLifeProtocol.Module.MUSIC), enabled)
    }

    @Test
    fun `控制包字节结构与旧版一致`() {
        assertArrayEquals(
            hex("0805120408011001120408021001120408031001120408081001120408091001"),
            CarLifeProtocol.moduleControl(
                listOf(
                    CarLifeProtocol.Module.PHONE,
                    CarLifeProtocol.Module.NAVI,
                    CarLifeProtocol.Module.MUSIC,
                    CarLifeProtocol.Module.CRUISE,
                    CarLifeProtocol.Module.CRUISE_FOLLOW,
                ),
            ),
        )
    }
}
