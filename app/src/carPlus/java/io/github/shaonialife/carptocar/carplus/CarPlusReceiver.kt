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
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import io.github.shaonialife.carptocar.BuildConfig
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.input.TouchReports.Contact
import io.github.shaonialife.carptocar.launcher.settings.DisplayPrefs
import io.github.shaonialife.carptocar.video.VideoSender
import java.io.IOException

/** Connects the CarPlay video sender to the isolated ICCOA process. */
internal object CarPlusReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var bindingContext: Context? = null
    private var receiver: Messenger? = null
    private var surface: Surface? = null
    private var scaler: CarPlusSurfaceScaler? = null
    private var requestedCanvas: CarPlusVideo.Size? = null
    @Volatile private var sourceSize = CarPlusVideo.defaultSize
    private var touchInImage = false
    private var encodedSender: VideoSender? = null
    private var encodedSize = CarPlusVideo.defaultSize
    private var encodedVideo: CarPlusEncodedVideo? = null
    private var allowOtherProfiles = false
    @Volatile private var audio: CarPlusAudio? = null
    private var refreshPending = false
    private var bindingGeneration = 0
    private val replies = Messenger(Handler(main.looper) { message ->
        if (bindingContext != null && message.arg1 == bindingGeneration) {
            when (message.what) {
                CarPlusReceiverService.STATUS -> {
                    message.data.getString(CarPlusReceiverService.STATUS_TEXT)?.let { status = it }
                    message.data.getString(CarPlusReceiverService.LOG_TEXT)?.let { SessionController.appendLog("ICCOA: $it") }
                }
                CarPlusReceiverService.VIDEO_SOURCE -> CarPlusVideo.displaySize(
                    message.data.getInt(CarPlusReceiverService.VIDEO_WIDTH),
                    message.data.getInt(CarPlusReceiverService.VIDEO_HEIGHT),
                )?.let { size ->
                    sourceSize = size
                    scaler?.sourceSize = size
                    SessionController.appendLog("ICCOA: Car+ 实际源画面 ${size.width}×${size.height}")
                }
            }
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
            connectEncodedVideo()
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

    fun attach(surface: Surface) = attach(surface, null)

    private fun attach(surface: Surface, scaling: CarPlusSurfaceScaler?) = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME && surface.isValid && scaling?.output?.isValid != false) {
            scaler?.close()
            scaler = scaling
            scaling?.sourceSize = sourceSize
            this.surface = surface
            sendSurface(surface)
        } else {
            scaling?.close()
        }
    }

    fun setCanvas(width: Int, height: Int) = onMain {
        val canvas = CarPlusVideo.displaySize(width, height) ?: return@onMain
        requestedCanvas = canvas
        send(Message.obtain(null, CarPlusReceiverService.VIDEO_CANVAS).apply {
            data = canvasBundle(canvas)
        })
    }

    fun startVideo(sender: VideoSender, receiverIdentity: String, width: Int, height: Int): Boolean {
        val size = CarPlusVideo.Size(width, height)
        if (!BuildConfig.CAR_PLUS_VIDEO_RELAY || !sender.startPassthrough(strictKeyFrames = true)) {
            return sender.start { attachScaled(it, sender, size) }
        }
        onMain {
            if (sender.isRunning) {
                encodedVideo?.close()
                encodedVideo = null
                encodedSender = sender
                encodedSize = size
                allowOtherProfiles = receiverIdentity.endsWith("AllPlay1,1")
                connectEncodedVideo()
            }
        }
        return true
    }

    private fun attachScaled(output: Surface, sender: VideoSender, size: CarPlusVideo.Size) {
        val scaling = CarPlusSurfaceScaler(output, size.width, size.height, sender::stop)
        scaling.sourceSize = sourceSize
        attach(scaling.open(), scaling)
    }

    fun stopVideo(sender: VideoSender?) = onMain {
        if (sender != null && encodedSender === sender) {
            encodedSender = null
            encodedVideo?.close()
            encodedVideo = null
            touchInImage = false
            sendSurface(null)
        }
    }

    private fun connectEncodedVideo() {
        val sender = encodedSender ?: return
        val remote = receiver ?: return
        if (encodedVideo != null || !sender.isRunning) return
        try {
            val (input, output) = ParcelFileDescriptor.createSocketPair()
            val video = CarPlusEncodedVideo(input, sender, encodedSize, allowOtherProfiles, main) { failed, reason ->
                if (encodedVideo === failed) fallBack(sender, reason)
            }
            encodedVideo = video
            video.start()
            output.use {
                remote.send(Message.obtain(null, CarPlusReceiverService.VIDEO_TARGET).apply {
                    data = Bundle().apply { putParcelable(CarPlusReceiverService.VIDEO_PIPE, it) }
                })
            }
        } catch (error: IOException) {
            fallBack(sender, error.message ?: "无法创建视频管道")
        } catch (_: RemoteException) {
            receiver = null
            fallBack(sender, "ICCOA 接收进程已断开")
        }
    }

    private fun fallBack(sender: VideoSender, reason: String) {
        if (encodedSender !== sender) return
        encodedVideo?.close()
        encodedVideo = null
        encodedSender = null
        if (!sender.isRunning) return
        SessionController.appendLog("ICCOA: $reason · 切回 Surface")
        val size = encodedSize
        sender.fallBackToSurface { attachScaled(it, sender, size) }
    }

    fun detach(surface: Surface) = onMain {
        if (this.surface === surface || scaler?.output === surface) {
            this.surface = null
            sendSurface(null)
            scaler?.close()
            scaler = null
            touchInImage = false
        }
    }

    fun onTouch(contact: Contact, displayWidth: Int, displayHeight: Int) {
        if (!BuildConfig.CAR_PLUS_RUNTIME || displayWidth <= 0 || displayHeight <= 0) return
        onMain {
            if (surface != null || encodedSender?.isRunning == true) {
                val display = CarPlusVideo.Size(displayWidth, displayHeight)
                val viewport = if (scaler != null) CarPlusVideo.fit(sourceSize, display)
                else CarPlusVideo.Viewport(0, 0, displayWidth, displayHeight)
                val dx = contact.xPixels - viewport.x
                val dy = contact.yPixels - viewport.y
                if (!touchInImage && (!contact.down || dx !in 0 until viewport.width || dy !in 0 until viewport.height)) {
                    return@onMain
                }
                touchInImage = contact.down
                val x = (dx * CarPlusVideo.WIDTH / viewport.width).coerceIn(0, CarPlusVideo.WIDTH - 1)
                val y = (dy * CarPlusVideo.HEIGHT / viewport.height).coerceIn(0, CarPlusVideo.HEIGHT - 1)
                val what = if (contact.down) CarPlusReceiverService.TOUCH_PRESSED else CarPlusReceiverService.TOUCH_RELEASED
                send(Message.obtain(null, what, x, y))
            }
        }
    }

    fun stop() = onMain {
        if (BuildConfig.CAR_PLUS_RUNTIME) {
            stopAudio()
            stopVideo(encodedSender)
            send(Message.obtain(null, CarPlusReceiverService.STOP))
            unbind()
            surface = null
            scaler?.close()
            scaler = null
            touchInImage = false
            refreshPending = false
            status = "ICCOA 接收端已停止 · 点此启动"
        }
    }

    fun startAudio() = onMain {
        if (audio == null && (surface != null || encodedSender?.isRunning == true) &&
            SessionController.audioSender?.isRunning == true
        ) {
            audio = CarPlusAudio(bindingContext)
            sendAudioTarget()
        }
    }

    fun stopAudio() = onMain {
        audio?.close()
        audio = null
        sendAudioTarget()
    }

    fun hasRecentMusic(): Boolean = audio?.hasRecentMusic() == true

    private fun sendAudioTarget() {
        send(Message.obtain(null, CarPlusReceiverService.AUDIO_TARGET).apply { replyTo = audio?.endpoint })
    }

    fun hasSystemCarPlus(context: Context): Boolean =
        BuildConfig.CAR_PLUS_RUNTIME && CarPlusDiscovery.isAvailable(context)

    fun openSystemCarPlus(context: Context, onFailure: () -> Unit) =
        CarPlusDiscovery.openGuide(context, onFailure)

    fun requestReconnect(): Boolean =
        SessionController.videoSender?.isRunning == true &&
            send(Message.obtain(null, CarPlusReceiverService.RECONNECT_IF_PAIRED))

    private fun startRemote(refresh: Boolean) {
        val last = bindingContext?.let(DisplayPrefs::lastSession)?.takeIf { it.carFromCar }
        val canvas = requestedCanvas ?: bindingContext?.let(DisplayPrefs::carPlusStreamSize)
            ?: last?.let { CarPlusVideo.displaySize(it.carWidth, it.carHeight) }
        send(Message.obtain(null, CarPlusReceiverService.START, bindingGeneration, if (refresh) 1 else 0).apply {
            replyTo = replies
            data = canvasBundle(canvas)
        })
    }

    private fun canvasBundle(canvas: CarPlusVideo.Size?): Bundle = Bundle().apply {
        if (canvas != null) {
            putInt(CarPlusReceiverService.VIDEO_WIDTH, canvas.width)
            putInt(CarPlusReceiverService.VIDEO_HEIGHT, canvas.height)
        }
    }

    private fun sendSurface(surface: Surface?) {
        send(Message.obtain(null, CarPlusReceiverService.SURFACE).apply {
            data = Bundle().apply { putParcelable(CarPlusReceiverService.SURFACE_VALUE, surface) }
        })
    }

    private fun send(message: Message): Boolean {
        val remote = receiver ?: return false
        return try {
            remote.send(message)
            true
        } catch (_: RemoteException) {
            receiver = null
            status = "ICCOA 接收进程已断开 · 正在恢复"
            false
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
