package dev.kalimote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.kalimote.atvremote.DeviceInfo
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.Macro
import dev.kalimote.atvremote.TvClient
import dev.kalimote.atvremote.RemoteState
import dev.kalimote.atvremote.WakeOnLan
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs one remote command without the app's UI, for the home-screen widget,
 * Quick Settings tiles, launcher shortcuts and the sleep timer.
 *
 * Commands are URIs so they fit in shortcuts and PendingIntents:
 *   kalimote://quick/key/24       press a key code
 *   kalimote://quick/power        toggle power (Wake-on-LAN if unreachable)
 *   kalimote://quick/power_off    turn off only if on
 *   kalimote://quick/macro/<id>   run a saved macro
 *
 * The connection stays warm for a short while, so repeated taps (volume)
 * don't reconnect each time.
 */
object QuickCommand {
    private const val TAG = "Kalimote"
    private const val KEEP_WARM_SECONDS = 45L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kalimote-quick").apply { isDaemon = true }
    }
    private var warm: TvClient? = null
    private var warmKey: String? = null
    private var idle: ScheduledFuture<*>? = null

    fun uri(path: String): Uri = Uri.parse("kalimote://quick/$path")
    fun key(code: Int) = uri("key/$code")
    val POWER: Uri = uri("power")
    val POWER_OFF: Uri = uri("power_off")
    fun macro(id: String) = uri("macro/$id")

    /** The TV quick controls act on: the one selected in the app, else the first paired one. */
    fun target(context: Context): TvDevice? {
        val store = DeviceStore(context)
        val paired = store.loadDevices().filter { it.paired }
        return paired.firstOrNull { it.id == store.selectedId } ?: paired.firstOrNull()
    }

    /**
     * Runs [command] in the background. [done] receives an error message (or
     * null on success) on a background thread.
     */
    fun run(context: Context, command: Uri, deviceId: String? = null, done: (String?) -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            val error = try {
                execute(app, command, deviceId)
            } catch (e: Exception) {
                Log.w(TAG, "Quick command $command failed", e)
                e.message ?: "Something went wrong"
            }
            done(error)
        }
    }

    private fun execute(context: Context, command: Uri, deviceId: String?): String? {
        val segments = command.pathSegments
        val action = segments.firstOrNull() ?: return "Unknown command"
        val store = DeviceStore(context)
        val device = (deviceId?.let { id -> store.loadDevices().firstOrNull { it.id == id && it.paired } })
            ?: target(context)
            ?: return "Pair a TV in Kalimote first"

        idle?.cancel(false)
        val client = warm?.takeIf { warmKey == device.type + device.host } ?: run {
            warm?.shutdown()
            createTvClient(device, Identity.get(context), DeviceInfo(model = Build.MODEL ?: "Android")) {}
                .also {
                    warm = it
                    warmKey = device.type + device.host
                    it.start()
                }
        }
        val needsPower = action == "power" || action == "power_off"
        val state = waitFor(client, if (needsPower) 8_000 else 6_000) { s ->
            s.status == RemoteState.Status.UNPAIRED || (s.connected && (!needsPower || s.powered != null))
        }

        try {
            if (state.status == RemoteState.Status.UNPAIRED) {
                return "${device.name} needs pairing again. Open Kalimote."
            }
            if (!state.connected) {
                if (action == "power" && device.mac != null) {
                    WakeOnLan.wake(device.mac)
                    return null
                }
                return "Can't reach ${device.name}"
            }
            when (action) {
                "key" -> client.sendKey(segments.getOrNull(1)?.toIntOrNull() ?: return "Bad key")
                "power" -> client.sendKey(KeyCodes.POWER)
                "power_off" -> if (state.powered != false) client.sendKey(KeyCodes.POWER)
                "macro" -> {
                    val macro = store.loadMacros().firstOrNull { it.id == segments.getOrNull(1) }
                        ?: return "That macro no longer exists"
                    Macro.run(client, Macro.parse(macro.script))
                }
                else -> return "Unknown command"
            }
            Thread.sleep(250) // let the writer thread flush before we may be frozen
            return null
        } finally {
            idle = executor.schedule({
                warm?.shutdown()
                warm = null
                warmKey = null
            }, KEEP_WARM_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun waitFor(client: TvClient, timeoutMs: Long, ready: (RemoteState) -> Boolean): RemoteState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = client.state
            if (ready(s)) return s
            Thread.sleep(50)
        }
        return client.state
    }

    /** Shows [message] as a toast from any thread. */
    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Receives widget button taps (and other PendingIntents) carrying a command URI. */
class QuickCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.data ?: return
        val pending = goAsync()
        QuickCommand.run(context, command) { error ->
            if (error != null) QuickCommand.toast(context, error)
            pending.finish()
        }
    }
}
