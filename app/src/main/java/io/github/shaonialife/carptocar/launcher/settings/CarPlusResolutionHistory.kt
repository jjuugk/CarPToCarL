package io.github.shaonialife.carptocar.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.shaonialife.carptocar.bt.TargetDevicePrefs
import io.github.shaonialife.carptocar.carplus.CarPlusVideo
import java.util.Locale
import org.json.JSONObject

/** One snapshot per actual Bluetooth peer, keyed by address so device renames cannot duplicate it. */
internal object CarPlusResolutionHistory {
    data class Entry(
        val name: String,
        val width: Int,
        val height: Int,
        val connectedAt: Long,
    ) {
        // The display can report odd dimensions. Use the encoder's existing even alignment when filling.
        val inputSize: CarPlusVideo.Size?
            get() = CarPlusVideo.manualSize(width / 2 * 2, height / 2 * 2)
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("carplus_resolution_history", Context.MODE_PRIVATE)

    fun record(context: Context, peer: TargetDevicePrefs.Target, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val entry = JSONObject()
            .put("name", peer.name)
            .put("width", width)
            .put("height", height)
            .put("connectedAt", System.currentTimeMillis())
        preferences(context).edit().putString(peer.address.uppercase(Locale.ROOT), entry.toString()).apply()
    }

    fun get(context: Context, address: String): Entry? =
        decode(preferences(context).getString(address.uppercase(Locale.ROOT), null))

    fun entries(context: Context): List<Entry> =
        preferences(context).all.values.mapNotNull { decode(it as? String) }.sortedByDescending { it.connectedAt }

    private fun decode(value: String?): Entry? {
        if (value == null) return null
        return runCatching {
            val json = JSONObject(value)
            Entry(json.getString("name"), json.getInt("width"), json.getInt("height"), json.getLong("connectedAt"))
        }.getOrNull()
    }
}
