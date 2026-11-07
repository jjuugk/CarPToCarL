package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设备名净化（0x4E09 DeviceInformationUpdate 用）。
 *
 * 现场（2026-11-07 别克 E2LB）：车机蓝牙列表把我们这台手机显示成「未知设备」。
 * 根因是 MagicOS 在本地蓝牙名未设置时 `BluetoothAdapter.getName()` 直接返回这个
 * 本地化占位串，而旧实现只判 `isNotBlank()`，于是把它原样发给了车机。
 */
class DeviceIdentityTest {
    @Test
    fun placeholderNamesAreRejected() {
        assertNull(DeviceIdentity.displayName("未知设备"))
        assertNull(DeviceIdentity.displayName("  未知设备  "))
        assertNull(DeviceIdentity.displayName("Unknown Device"))
        assertNull(DeviceIdentity.displayName("unknown"))
        assertNull(DeviceIdentity.displayName("null"))
        assertNull(DeviceIdentity.displayName("N/A"))
        assertNull(DeviceIdentity.displayName(""))
        assertNull(DeviceIdentity.displayName("   "))
        assertNull(DeviceIdentity.displayName(null))
    }

    @Test
    fun realNamesPassThroughTrimmed() {
        assertEquals("HONOR Magic7", DeviceIdentity.displayName("  HONOR Magic7  "))
        assertEquals("张三的 iPhone", DeviceIdentity.displayName("张三的 iPhone"))
        // 「Android」是 AOSP 的默认名，不是占位串——不能因为不够好看就改用户的手机名
        assertEquals("Android", DeviceIdentity.displayName("Android"))
    }
}
