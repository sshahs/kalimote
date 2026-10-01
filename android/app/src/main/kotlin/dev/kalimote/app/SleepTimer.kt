package dev.kalimote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Turns a TV off after a delay, even if the app is closed: an alarm wakes
 * [SleepTimerReceiver], which turns the TV off via [QuickCommand] (only if it is on).
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
        val store = DeviceStore(context.applicationContext)
        val deviceId = store.sleepDeviceId ?: return
        store.sleepAt = 0
        val pending = goAsync()
        // power_off only presses POWER if the TV reports that it is on.
        QuickCommand.run(context, QuickCommand.POWER_OFF, deviceId) { pending.finish() }
    }
}
