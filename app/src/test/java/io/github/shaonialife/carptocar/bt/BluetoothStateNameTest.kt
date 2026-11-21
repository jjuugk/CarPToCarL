package io.github.shaonialife.carptocar.bt

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 蓝牙适配器状态的可读名（2026-11-21 诊断日志用）。
 *
 * 这只是日志格式化，不参与任何连接判定；但状态值写错会让诊断日志自相矛盾
 * （例如把 TURNING_OFF 显示成 ON），所以把四个标准值钉死。
 */
class BluetoothStateNameTest {
    @Test
    fun mapsAllFourStandardStates() {
        assertEquals("OFF(10)", BtProfiles.stateName(BluetoothAdapter.STATE_OFF))
        assertEquals("TURNING_ON(11)", BtProfiles.stateName(BluetoothAdapter.STATE_TURNING_ON))
        assertEquals("ON(12)", BtProfiles.stateName(BluetoothAdapter.STATE_ON))
        assertEquals("TURNING_OFF(13)", BtProfiles.stateName(BluetoothAdapter.STATE_TURNING_OFF))
    }

    @Test
    fun unknownStateKeepsRawValue() {
        assertEquals("UNKNOWN(99)", BtProfiles.stateName(99))
    }
}
