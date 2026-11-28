package io.github.shaonialife.carptocar

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.carptocar.airplay.AudioFirstOption
import io.github.shaonialife.carptocar.airplay.FeatureTrimOption
import io.github.shaonialife.carptocar.audio.AudioCompatExperiment
import io.github.shaonialife.carptocar.audio.AudioQueueOption
import io.github.shaonialife.carptocar.bt.BtAudioHoldOption
import io.github.shaonialife.carptocar.bt.BluetoothStateReceiver
import io.github.shaonialife.carptocar.carlife.CarGpsOption
import io.github.shaonialife.carptocar.carlife.VoiceKeyGesture
import io.github.shaonialife.carptocar.iap2.MfiCertBytesOption
import io.github.shaonialife.carptocar.iap2.NavCardRefreshOption
import io.github.shaonialife.carptocar.input.KnobHardKeyOption
import io.github.shaonialife.carptocar.session.VideoReadyGateOption
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SessionTrace
import io.github.shaonialife.carptocar.video.FirstFramePolicy
import io.github.shaonialife.carptocar.video.VideoCanvasMode

/**
 * 进程级安全网：任一线程的未捕获异常只记录并让该线程结束，
 * 不再走默认处理器杀死整个进程（连接会话横跨多个后台线程，
 * 一个套接字异常不应毁掉全部链路与前台服务）。
 * 各线程自身仍应尽量用 try/catch 精确处理；这里是最后防线。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 会话日志起点标记：日志文件跨进程累积（2MB 上限），导出默认只取本次启动
        // 之后的部分。必须在这里写（Application 是进程最早入口），晚于任何业务日志
        // 都会让它被算进「历史」。
        DebugFileLog.markSessionStart(this)
        // VideoConfig 画布声明模式（车机画布 / 码流尺寸）——会话建立时读一次
        VideoCanvasMode.load(this)
        // 首帧策略（缓存关键帧立即起播 / 等引擎当期 IDR）——attach 时读一次
        FirstFramePolicy.load(this)
        // 「使用车机 GPS」开关（默认开）——iAP2 收帧线程每秒会读，启动时载入一次即可
        CarGpsOption.load(this)
        // 方向盘语音键手势（默认开）：短按三次重启音频。按键线程每按一次都会读
        VoiceKeyGesture.load(this)
        // 旋钮原生硬键实验（默认关）+ 方向反转（默认开，这台车实测需反转）：
        // 会话收帧线程每格会读，启动时载入一次。漏了这行会让偏好重启后丢失、开关形同虚设。
        KnobHardKeyOption.load(this)
        // 音频兼容实验（默认关，对所有车型生效）：拨档下次连接起效
        AudioCompatExperiment.load(this)
        // 音频队列治理（默认关，音频卡顿时可开启尝试）：建音频流时读一次
        AudioQueueOption.load(this)
        // 三项协议实验（都默认关）：
        //  - 视频真送达才算本轮连上（黑屏假成功时不再清零重连计数）
        //  - MFi 挑战长度按证书参数字节兜底（OID 识别不出时才用）
        //  - SETUP 特征位裁剪（去掉 7 项无实现的能力）
        VideoReadyGateOption.load(this)
        MfiCertBytesOption.load(this)
        FeatureTrimOption.load(this)
        // 奔驰 MBUX 的两条逃生开关（都默认关，保持原行为）：
        //  - 建链期不断开蓝牙音频配置文件（守卫会把 ACL 一起拽掉 → bond-loss）
        //  - 音频优先 · 只发一次 RECORD（补发 RECORD 会被同毫秒拆链）
        BtAudioHoldOption.load(this)
        AudioFirstOption.load(this)
        // 媒体卡片导航刷新的策略（间隔默认 3 秒 + 相同箭头图不换编号）：
        // 只在发帧时读当前值，改设置立即生效
        NavCardRefreshOption.load(this)
        // 完整会话报文录制（files/session_trace.log）：与上面的过程日志分开两份文件，
        // 原因见 SessionTrace 注释（报文量大会挤掉过程日志）。必须在业务起来之前 attach。
        SessionTrace.attach(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            // 不调用 previous：默认处理器会 killProcess 杀死整个 app
        }
        // 蓝牙适配器状态观测（2026-11-21 诊断）：验证「断 HFP/A2DP 会不会把部分手机的
        // 蓝牙栈带重启」。动态注册、随进程存活；只写文件日志，不改变任何连接行为。
        // 判读时与 BT-PROFILE / BT-GUARD / BT-C 的时间戳对齐。
        registerReceiver(
            BluetoothStateReceiver(),
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
