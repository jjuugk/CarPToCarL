package io.github.shaonialife.carptocar.carplus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Surface
import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.input.TouchReports.Contact

/** Holds the local encoder surface and hands a copy to the isolated ICCOA process. */
internal object CarPlusReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var bindingContext: Context? = null
    private var receiver: Messenger? = null
    private var surface: Surface? = null
    private var audio: CarPlusAudio? = null
    private var refreshPending = false
    private var bindingGeneration = 0
    private val replies = Messenger(Handler(main.looper) { message ->
        if (bindingContext != null && message.what == CarPlusReceiverService.STATUS &&
            message.arg1 == bindingGeneration
        ) {
            message.data.getString(CarPlusReceiverService.STATUS_TEXT)?.let { status = it }
            message.data.getString(CarPlusReceiverService.LOG_TEXT)?.let { SessionController.appendLog("ICCOA: $it") }
        }
        true
    })

    @Volatile
    var status = if (BuildConfig.CAR_PLUS_RUNTIME) {
        "正在准备 ICCOA Car+ 接收端"
    } else {
        "ICCOA 编译验证包 · 需要车端 SDK 才能配对"
    }
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (bindingContext == null) return
            receiver = Messenger(binder)
            startRemote(refreshPending)
            refreshPending = false
            surface?.takeIf { it.isValid }?.let { sendSurface(it) }
            sendAudioTarget()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (bindingContext == null) return
            receiver = null
            status = "ICCOA 接收进程已断开 · 正在恢复"
        }

        override fun onBindingDied(name: ComponentName) {
            unbind()
            status = "ICCOA 接收进程已停止 · 点此重试"
        }

        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }

    fun start(context: Context, refresh: Boolean = false) = onMain {
        if (!BuildConfig.CAR_PLUS_RUNTIME) {
            SessionController.appendLog("ICCOA: 当前 APK 使用接口存根，未打包运行时 SDK")
        } else if (receiver != null) {
            startRemote(refresh)
        } else {
            refreshPending = refreshPending || refresh
            if (bindingContext == null) {
                val app = context.applicationContext
                bindingGeneration++
                val intent = Intent(app, CarPlusReceiverService::class.java)
                val bound = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                    .getOrDefault(false)
                if (bound) {
                    bindingContext = app
                } else {
                    status = "ICCOA 接收进程启动失败 · 点此重试"
                }
            }
        }
    }

    fun attach(surface: Surface) = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME && surface.isValid) {
            this.surface = surface
            sendSurface(surface)
        }
    }

    fun detach(surface: Surface) = onMain {
        if (this.surface === surface) {
            this.surface = null
            sendSurface(null)
        }
    }

    fun onTouch(contact: Contact, displayWidth: Int, displayHeight: Int) {
        if (!BuildConfig.CAR_PLUS_RUNTIME || displayWidth <= 0 || displayHeight <= 0) return
        val x = (contact.xPixels * CarPlusVideo.WIDTH / displayWidth).coerceIn(0, CarPlusVideo.WIDTH - 1)
        val y = (contact.yPixels * CarPlusVideo.HEIGHT / displayHeight).coerceIn(0, CarPlusVideo.HEIGHT - 1)
        onMain {
            if (surface != null) {
                val what = if (contact.down) CarPlusReceiverService.TOUCH_PRESSED else CarPlusReceiverService.TOUCH_RELEASED
                send(Message.obtain(null, what, x, y))
            }
        }
    }

    fun stop() = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME) {
            stopAudio()
            send(Message.obtain(null, CarPlusReceiverService.STOP))
            unbind()
            surface = null
            refreshPending = false
            status = "ICCOA 接收端已停止 · 点此启动"
        }
    }

    fun startAudio() = onMain {
        if (audio == null && surface != null && SessionController.audioSender?.isRunning == true) {
            audio = CarPlusAudio()
            sendAudioTarget()
        }
    }

    fun stopAudio() = onMain {
        audio?.close()
        audio = null
        sendAudioTarget()
    }

    private fun sendAudioTarget() {
        send(Message.obtain(null, CarPlusReceiverService.AUDIO_TARGET).apply { replyTo = audio?.endpoint })
    }

    fun hasSystemCarPlus(context: Context): Boolean =
        BuildConfig.CAR_PLUS_RUNTIME && CarPlusDiscovery.isAvailable(context)

    fun openSystemCarPlus(context: Context, onFailure: () -> Unit) =
        CarPlusDiscovery.openGuide(context, onFailure)

    private fun startRemote(refresh: Boolean) {
        send(Message.obtain(null, CarPlusReceiverService.START, bindingGeneration, if (refresh) 1 else 0).apply {
            replyTo = replies
        })
    }

    private fun sendSurface(surface: Surface?) {
        send(Message.obtain(null, CarPlusReceiverService.SURFACE).apply {
            data = Bundle().apply { putParcelable(CarPlusReceiverService.SURFACE_VALUE, surface) }
        })
    }

    private fun send(message: Message) {
        try {
            receiver?.send(message)
        } catch (_: RemoteException) {
            receiver = null
            status = "ICCOA 接收进程已断开 · 正在恢复"
        }
    }

    private fun unbind() {
        val app = bindingContext
        bindingContext = null
        receiver = null
        if (app != null) app.unbindService(connection)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }
}
