package io.github.shaonialife.carptocar.session

import io.github.shaonialife.carptocar.session.ConnectionPreflight.Requirement
import io.github.shaonialife.carptocar.session.PermissionRepair.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键获取缺失权限」的计划表约定（2026-11-14 用户报「通知使用权一直拿不到、也没弹窗」）。
 *
 * 这些断言守住两件事：
 * 1. **顺序**：权限在前（否则连蓝牙/WLAN 的开关状态都读不到），设置页在后；
 * 2. **不冒充**：本机蓝牙地址、目标车机这类「设置项」不能被塞进权限按钮里——
 *    跳哪个系统页面都补不上，只会让用户白跑。
 */
class PermissionRepairTest {
    @Test
    fun `全部通过时没有要补的步骤`() {
        assertEquals(emptyList<Step>(), PermissionRepair.stepsFor(emptySet()))
    }

    @Test
    fun `只缺通知使用权时只跳那一页`() {
        assertEquals(
            listOf(Step.NOTIFICATION_ACCESS),
            PermissionRepair.stepsFor(setOf(Requirement.NOTIFICATION_ACCESS)),
        )
    }

    @Test
    fun `三项运行时权限合成同一次申请`() {
        // 分开申请会连弹两个「附近的设备」+「通知」框，正是用户说的「一直要我点」
        val steps =
            PermissionRepair.stepsFor(
                setOf(
                    Requirement.BLUETOOTH_PERMISSION,
                    Requirement.WIFI_PERMISSION,
                    Requirement.NOTIFICATIONS,
                ),
            )
        assertEquals(listOf(Step.PERMISSIONS), steps)
    }

    @Test
    fun `缺权限时必须先申请权限再开开关`() {
        val steps =
            PermissionRepair.stepsFor(
                setOf(
                    Requirement.BLUETOOTH_ENABLED,
                    Requirement.BLUETOOTH_PERMISSION,
                ),
            )
        // 没有 BLUETOOTH_CONNECT 时连 adapter.isEnabled 都会抛，先弹权限才有意义
        assertEquals(listOf(Step.PERMISSIONS, Step.BLUETOOTH_ON), steps)
    }

    @Test
    fun `全部缺失时的固定顺序`() {
        val steps =
            PermissionRepair.stepsFor(
                setOf(
                    Requirement.BLUETOOTH_PERMISSION,
                    Requirement.BLUETOOTH_ENABLED,
                    Requirement.WIFI_PERMISSION,
                    Requirement.WIFI_ENABLED,
                    Requirement.LOCATION_SERVICE,
                    Requirement.NOTIFICATION_ACCESS,
                    Requirement.NOTIFICATIONS,
                    Requirement.BATTERY_OPTIMIZATION,
                ),
            )
        assertEquals(
            listOf(
                Step.PERMISSIONS,
                Step.BLUETOOTH_ON,
                Step.WIFI_ON,
                Step.LOCATION,
                Step.NOTIFICATION_ACCESS,
                Step.BATTERY,
            ),
            steps,
        )
    }

    @Test
    fun `设置项不进权限计划`() {
        val steps =
            PermissionRepair.stepsFor(
                setOf(
                    Requirement.IDENTITY,
                    Requirement.IDENTITY_NAME,
                    Requirement.TARGET_PAIRED,
                ),
            )
        assertTrue("本机蓝牙地址 / 蓝牙名 / 目标车机不是权限，不该出现计划里", steps.isEmpty())
    }
}
