package io.github.shaonialife.carptocar.carplus

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface

/** Runs the SDK outside the process whose default network is bound to CarPlay. */
internal class CarPlusReceiverService : Service() {
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> {
                val replies = message.replyTo
                val generation = message.arg1
                val canvas = canvasFrom(message)
                CarPlusVideoTap.requestedCanvas = canvas
                CarPlusVideoTap.onSourceSize = { size ->
                    try {
                        replies.send(Message.obtain(null, VIDEO_SOURCE, generation, 0).apply {
                            data = Bundle().apply {
                                putInt(VIDEO_WIDTH, size.width)
                                putInt(VIDEO_HEIGHT, size.height)
                            }
                        })
                    } catch (_: RemoteException) {
                        // The client has unbound.
                    }
                }
                CarPlusSdk.report = { status, log ->
                    try {
                        replies.send(Message.obtain(null, STATUS, generation, 0).apply {
                            data = Bundle().apply {
                                putString(STATUS_TEXT, status)
                                putString(LOG_TEXT, log)
                            }
                        })
                    } catch (_: RemoteException) {
                        // The service is bound to the sender. onDestroy stops the SDK when it goes away.
                    }
                }
                CarPlusSdk.start(applicationContext, refresh = message.arg2 == 1, canvas = canvas ?: CarPlusVideo.defaultSize)
            }
            VIDEO_CANVAS -> CarPlusVideoTap.requestedCanvas = canvasFrom(message)
            SURFACE -> {
                val data = message.data
                val surface = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelable(SURFACE_VALUE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelable<Surface>(SURFACE_VALUE)
                }
                CarPlusVideoTap.setTarget(null)
                CarPlusSdk.setVideoOutput(surface, encoded = false)
            }
            VIDEO_TARGET -> {
                val data = message.data
                val descriptor = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelable(VIDEO_PIPE, ParcelFileDescriptor::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelable<ParcelFileDescriptor>(VIDEO_PIPE)
                }
                CarPlusVideoTap.setTarget(descriptor)
                CarPlusSdk.setVideoOutput(null, encoded = descriptor != null)
            }
            STOP -> {
                CarPlusVideoTap.setTarget(null)
                CarPlusSdk.stop()
            }
            AUDIO_TARGET -> CarPlusAudioTap.setTarget(message.replyTo)
            RECONNECT_IF_PAIRED -> CarPlusSdk.reconnectIfPaired(restartCurrent = true)
            TOUCH_PRESSED, TOUCH_RELEASED -> {
                CarPlusSdk.onTouch(message.what == TOUCH_PRESSED, message.arg1, message.arg2)
            }
            else -> return@Handler false
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    private fun canvasFrom(message: Message): CarPlusVideo.Size? =
        CarPlusVideo.displaySize(message.data.getInt(VIDEO_WIDTH), message.data.getInt(VIDEO_HEIGHT))

    override fun onDestroy() {
        CarPlusAudioTap.setTarget(null)
        CarPlusVideoTap.setTarget(null)
        CarPlusVideoTap.onSourceSize = null
        CarPlusVideoTap.requestedCanvas = null
        CarPlusSdk.report = null
        CarPlusSdk.stop()
        super.onDestroy()
    }

    companion object {
        const val START = 1
        const val SURFACE = 2
        const val STOP = 3
        const val STATUS = 4
        const val TOUCH_PRESSED = 5
        const val TOUCH_RELEASED = 6
        const val AUDIO_TARGET = 7
        const val VIDEO_TARGET = 8
        const val VIDEO_CANVAS = 9
        const val VIDEO_SOURCE = 10
        const val RECONNECT_IF_PAIRED = 11
        const val VIDEO_WIDTH = "videoWidth"
        const val VIDEO_HEIGHT = "videoHeight"
        const val VIDEO_PIPE = "videoPipe"
        const val SURFACE_VALUE = "surface"
        const val STATUS_TEXT = "status"
        const val LOG_TEXT = "log"
    }
}
