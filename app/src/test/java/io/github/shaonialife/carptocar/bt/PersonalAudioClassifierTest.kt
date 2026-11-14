package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「像不像耳机/音箱」的判定（2026-11-14）。
 *
 * 这层判定直接决定两件事：耳机**会不会被当成车机候选**去拨 …caff，以及
 * 会不会为了探 iAP2 被断掉 A2DP/HFP。一加 Android 16 上 `bluetoothClass` 常常是
 * `null`，所以名字兜底必须够用；同时又不能把真车机误伤——放品牌名进来就会。
 */
class PersonalAudioClassifierTest {
    @Test
    fun `Class of Device 是音频子类就命中`() {
        assertTrue(PersonalAudioClassifier.isAudioDeviceClass(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertTrue(PersonalAudioClassifier.isAudioDeviceClass(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO))
        // 车机常见的几类都不该命中
        assertFalse(PersonalAudioClassifier.isAudioDeviceClass(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
        assertFalse(PersonalAudioClassifier.isAudioDeviceClass(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertFalse(PersonalAudioClassifier.isAudioDeviceClass(android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED))
        assertFalse(PersonalAudioClassifier.isAudioDeviceClass(null))
    }

    @Test
    fun `拿不到 Class of Device 时靠名字兜底——Redmi Buds 5 Pro 命中`() {
        // 这条正是 2026-11-14 日志里的实际设备名。
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "Redmi Buds 5 Pro"))
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "AirPods Pro"))
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "WH-1000XM5 Headphones"))
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "JBL Flip Speaker"))
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "小米蓝牙音箱"))
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "降噪耳机"))
    }

    @Test
    fun `车机名字不会被误判`() {
        // MBUX / 奥迪 / 宝马 / 测试台——这些都必须留在候选里。
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "MBUX 29573"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "MBUX 15876"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "Audi MIB3 4580"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "BMW 12345"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "DiPlay"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "xcertplay"))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "一加15T（简）"))
    }

    @Test
    fun `名字为空或拿不到时一律不当耳机——宁可多试一次，也不能把车机排除`() {
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, null))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, ""))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudio(null, "   "))
        assertFalse(PersonalAudioClassifier.looksLikePersonalAudioName(null))
    }

    @Test
    fun `Class of Device 与名字任一命中即算个人音频`() {
        assertTrue(
            PersonalAudioClassifier.looksLikePersonalAudio(
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                "MBUX 29573",
            ),
        )
        // 名字命中、Class 缺失（Android 16 的常见形态）
        assertTrue(PersonalAudioClassifier.looksLikePersonalAudio(null, "FreeBuds 6i"))
    }
}
