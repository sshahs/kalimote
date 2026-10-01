package dev.kalimote.app

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.kalimote.atvremote.KeyCodes

/** Home-screen widget: power, volume, play/pause and mute for the selected TV. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        private val BUTTONS = listOf(
            R.id.widget_power to QuickCommand.POWER,
            R.id.widget_vol_down to QuickCommand.key(KeyCodes.VOLUME_DOWN),
            R.id.widget_play_pause to QuickCommand.key(KeyCodes.MEDIA_PLAY_PAUSE),
            R.id.widget_vol_up to QuickCommand.key(KeyCodes.VOLUME_UP),
            R.id.widget_mute to QuickCommand.key(KeyCodes.VOLUME_MUTE),
        )

        fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_remote)
            views.setTextViewText(R.id.widget_title, QuickCommand.target(context)?.name ?: context.getString(R.string.widget_no_tv))
            views.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            BUTTONS.forEachIndexed { i, (id, command) ->
                val intent = Intent(context, QuickCommandReceiver::class.java).setData(command)
                views.setOnClickPendingIntent(
                    id,
                    PendingIntent.getBroadcast(context, 100 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            return views
        }

        /** Call when the selected TV or its name changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RemoteWidget::class.java))
            if (ids.isNotEmpty()) {
                val views = views(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }
    }
}

/** Shared behaviour for the Quick Settings tiles. */
abstract class QuickTile(private val command: Uri) : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val tv = QuickCommand.target(this)
        tile.state = if (tv != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = tv?.name ?: getString(R.string.widget_no_tv)
        tile.updateTile()
    }

    override fun onClick() {
        val tile = qsTile
        tile?.state = Tile.STATE_ACTIVE
        tile?.updateTile()
        QuickCommand.run(this, command) { error ->
            if (error != null) QuickCommand.toast(this, error)
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                it.updateTile()
            }
        }
    }
}

class PowerTileService : QuickTile(QuickCommand.POWER)

class PlayPauseTileService : QuickTile(QuickCommand.key(KeyCodes.MEDIA_PLAY_PAUSE))

/**
 * Invisible activity behind launcher shortcuts ("long-press the app icon"):
 * runs the command in the intent's data and finishes immediately.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent?.data
        if (command != null && command.scheme == "kalimote") {
            val app = applicationContext
            QuickCommand.run(app, command) { error -> if (error != null) QuickCommand.toast(app, error) }
        }
        finish()
    }
}

/** Publishes the first few macros as launcher shortcuts next to the static Power / Play-Pause ones. */
object MacroShortcuts {
    fun update(context: Context, macros: List<SavedMacro>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val limit = (manager.maxShortcutCountPerActivity - 2).coerceIn(0, 3)
            manager.dynamicShortcuts = macros.take(limit).mapIndexed { i, m ->
                ShortcutInfo.Builder(context, "macro-${m.id}")
                    .setShortLabel(m.name.take(25))
                    .setLongLabel(context.getString(R.string.shortcut_run_macro, m.name))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_macro))
                    .setRank(i)
                    .setIntent(Intent(Intent.ACTION_VIEW, QuickCommand.macro(m.id)).setClass(context, ShortcutActivity::class.java))
                    .build()
            }
        }
    }
}
