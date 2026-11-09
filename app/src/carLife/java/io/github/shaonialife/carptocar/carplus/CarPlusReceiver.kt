package io.github.shaonialife.carptocar.carplus

import android.content.Context
import android.view.Surface
import io.github.shaonialife.carptocar.input.TouchReports.Contact

@Suppress("UNUSED_PARAMETER")
internal object CarPlusReceiver {
    val status: String get() = ""

    fun start(context: Context, refresh: Boolean = false) = Unit

    fun hasSystemCarPlus(context: Context): Boolean = false

    fun openSystemCarPlus(context: Context, onFailure: () -> Unit) = Unit

    fun attach(surface: Surface) = Unit

    fun detach(surface: Surface) = Unit

    fun onTouch(contact: Contact, displayWidth: Int, displayHeight: Int) = Unit

    fun startAudio() = Unit

    fun stopAudio() = Unit

    fun stop() = Unit
}
