package io.github.shaonialife.carptocar.carplus

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import io.github.shaonialife.carptocar.util.Bg
import java.io.File
import java.util.concurrent.TimeUnit

internal class CarPlusDiscovery(context: Context) : ContextWrapper(context) {
    private val packet = AtomicFile(File(filesDir, "iccoa-discovery.packet"))
    private val ready = AtomicFile(File(filesDir, "iccoa-discovery.ready"))
    private val lock = Any()
    private var generation = 0
    private var starting = false
    private var watcher: java.lang.Process? = null

    override fun getApplicationContext(): Context = this

    fun advertiseReady(active: Boolean) {
        if (!active) {
            ready.delete()
            return
        }
        runCatching {
            val stream = ready.startWrite()
            try {
                stream.write("${Process.myPid()} ${System.currentTimeMillis()}".toByteArray())
                ready.finishWrite(stream)
            } catch (error: Exception) {
                ready.failWrite(stream)
                throw error
            }
        }.onFailure { Log.w(TAG, "Unable to record local advertising readiness", it) }
    }

    override fun startService(service: Intent): ComponentName? {
        if (service.component?.className == "com.share.connect.ShareLinkService" &&
            service.hasExtra("car_short_name")
        ) {
            service.putExtra("car_name", ColorOsDiscovery.RECEIVER_NAME)
            service.putExtra("car_short_name", ColorOsDiscovery.RECEIVER_NAME)
            runCatching {
                val data = ColorOsDiscovery.packet(
                    service.getStringExtra("protocol_version"),
                    service.getStringExtra("car_id"),
                    service.getStringExtra("mod_id"),
                    service.getStringExtra("car_vendor_custom_data"),
                    service.getIntExtra("ble_rssi_threshold", -60),
                )
                val stream = packet.startWrite()
                try {
                    stream.write(data)
                    packet.finishWrite(stream)
                } catch (error: Exception) {
                    packet.failWrite(stream)
                    throw error
                }
            }.onFailure { Log.w(TAG, "Unable to prepare local ICCOA advertisement", it) }
        }
        return super.startService(service)
    }

    fun watch() {
        if (!isAvailable(this)) return
        val startedAt = System.currentTimeMillis()
        val run = synchronized(lock) {
            if (starting || watcher?.isAlive == true) return
            starting = true
            ++generation
        }
        Bg.run("iccoa-discovery") {
            var child: java.lang.Process? = null
            try {
                val command = "CLASSPATH=${quote(packageCodePath)} exec /system/bin/app_process /system/bin " +
                    ColorOsDiscovery::class.java.name + " ${Process.myPid()} $startedAt ${quote(packet.baseFile.path)} " +
                    quote(packageCodePath)
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                child = process
                synchronized(lock) {
                    if (generation != run) return@run
                    watcher = process
                    starting = false
                }
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.i(TAG, it) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to run the rooted ColorOS discovery helper", error)
            } finally {
                runCatching { child?.outputStream?.close() }
                runCatching { if (child?.waitFor(5, TimeUnit.SECONDS) == false) child.destroy() }
                synchronized(lock) {
                    if (generation == run) {
                        watcher = null
                        starting = false
                    }
                }
            }
        }
    }

    fun stopWatching() = synchronized(lock) {
        generation++
        starting = false
        watcher?.let { process ->
            runCatching { process.outputStream.close() }.onFailure { process.destroy() }
        }
        watcher = null
    }

    companion object {
        private const val TAG = "CarPlusDiscovery"
        private const val NATIVE_PACKAGE = "com.oplus.ocar"
        private const val GUIDE = "$NATIVE_PACKAGE.settings.connect.WirelessConnectGuideActivity"
        private const val OPEN_GUIDE = "oplus.intent.action.ICCOA_WIRELESS_CONNECT_ACTIVITY"

        fun isAvailable(context: Context): Boolean =
            try {
                context.packageManager.getPackageInfo(NATIVE_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        fun openGuide(context: Context, onFailure: () -> Unit) {
            val intent = Intent(OPEN_GUIDE).setClassName(NATIVE_PACKAGE, GUIDE).putExtra("type", 1)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
            Bg.run("open-system-car-plus") {
                val opened = runCatching {
                    val process = ProcessBuilder(
                        "su", "-c", "/system/bin/am start -W -a $OPEN_GUIDE -n $NATIVE_PACKAGE/$GUIDE --ei type 1"
                    ).redirectErrorStream(true).start()
                    try {
                        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                            !process.inputStream.bufferedReader().use { it.readText() }.contains("Error:")
                    } finally {
                        process.destroy()
                    }
                }.getOrDefault(false)
                if (!opened) onFailure()
            }
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
