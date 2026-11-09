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
import android.os.RemoteException
import android.view.Surface

/** Runs the SDK outside the process whose default network is bound to CarPlay. */
internal class CarPlusReceiverService : Service() {
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> {
                val replies = message.replyTo
                val generation = message.arg1
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
                CarPlusSdk.start(applicationContext, refresh = message.arg2 == 1)
            }
            SURFACE -> {
                val data = message.data
                val surface = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelable(SURFACE_VALUE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelable<Surface>(SURFACE_VALUE)
                }
                CarPlusSdk.setSurface(surface)
            }
            STOP -> CarPlusSdk.stop()
            AUDIO_TARGET -> CarPlusAudioTap.setTarget(message.replyTo)
            TOUCH_PRESSED, TOUCH_RELEASED -> {
                CarPlusSdk.onTouch(message.what == TOUCH_PRESSED, message.arg1, message.arg2)
            }
            else -> return@Handler false
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    override fun onDestroy() {
        CarPlusAudioTap.setTarget(null)
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
        const val SURFACE_VALUE = "surface"
        const val STATUS_TEXT = "status"
        const val LOG_TEXT = "log"
    }
}
