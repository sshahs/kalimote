package dev.kalimote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.kalimote.atvremote.DeviceInfo
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.RemoteClient
import dev.kalimote.atvremote.RemoteState

/**
 * Turns a TV off after a delay, even if the app is closed: an alarm wakes
 * [SleepTimerReceiver], which connects, and presses POWER only if the TV is on.
 */
object SleepTimer {
    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SleepTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, deviceId: String, minutes: Int): Long {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val store = DeviceStore(context)
        store.sleepAt = at
        store.sleepDeviceId = deviceId
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Inexact but allowed in Doze and needs no special permission; a
        // sleep timer firing a minute late is fine.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        return at
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context))
        DeviceStore(context).sleepAt = 0
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val store = DeviceStore(app)
        val deviceId = store.sleepDeviceId
        store.sleepAt = 0
        val device = store.loadDevices().firstOrNull { it.id == deviceId && it.paired } ?: return
        val pending = goAsync()
        Thread {
            var client: RemoteClient? = null
            try {
                client = RemoteClient(device.host, Identity.get(app), DeviceInfo(model = Build.MODEL ?: "Android")) {}
                client.start()
                // Wait for the connection and the TV's power report.
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val s = client.state
                    if (s.status == RemoteState.Status.UNPAIRED) break
                    if (s.connected && s.powered != null) break
                    Thread.sleep(100)
                }
                val s = client.state
                if (s.connected && s.powered != false) {
                    client.sendKey(KeyCodes.POWER)
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                Log.w("Kalimote", "Sleep timer failed", e)
            } finally {
                client?.shutdown()
                pending.finish()
            }
        }.start()
    }
}
