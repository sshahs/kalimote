package dev.kalimote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.kalimote.atvremote.Apps
import dev.kalimote.atvremote.KeyCodes

/**
 * Keeps a media notification for the selected TV, also shown on the lock
 * screen: volume, previous, play/pause and next. It holds a media session with
 * remote volume, so the phone's volume keys control the TV even when the
 * phone is locked or another app is open.
 */
class MediaControlsService : Service() {
    private lateinit var session: MediaSession
    private var title: String? = null
    private var subtitle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        session = MediaSession(this, "Kalimote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send(KeyCodes.MEDIA_PLAY_PAUSE)
                override fun onPause() = send(KeyCodes.MEDIA_PLAY_PAUSE)
                override fun onSkipToNext() = send(KeyCodes.MEDIA_NEXT)
                override fun onSkipToPrevious() = send(KeyCodes.MEDIA_PREVIOUS)
                override fun onStop() = send(KeyCodes.MEDIA_PLAY_PAUSE)
            })
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    when {
                        direction > 0 -> send(KeyCodes.VOLUME_UP)
                        direction < 0 -> send(KeyCodes.VOLUME_DOWN)
                    }
                }
            })
            // "Playing" keeps the session in charge of the volume keys.
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            isActive = true
        }
        title = QuickCommand.target(this)?.name
        instance = this
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        title = QuickCommand.target(this)?.name ?: title
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun send(code: Int) {
        QuickCommand.run(this, QuickCommand.key(code)) { error ->
            if (error != null) QuickCommand.toast(this, error)
        }
    }

    private fun update(tvName: String?, appName: String?) {
        title = tvName ?: title
        subtitle = appName
        refresh()
    }

    private fun refresh() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: getString(R.string.widget_no_tv))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle ?: getString(R.string.app_name))
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        fun action(icon: Int, label: Int, code: Int, request: Int): Notification.Action {
            val intent = Intent(this, QuickCommandReceiver::class.java).setData(QuickCommand.key(code))
            val pending = PendingIntent.getBroadcast(
                this,
                request,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending).build()
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_tv)
            .setContentTitle(title ?: getString(R.string.widget_no_tv))
            .setContentText(subtitle ?: getString(R.string.media_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_vol_down, R.string.volume_down, KeyCodes.VOLUME_DOWN, 300))
            .addAction(action(R.drawable.ic_notif_prev, R.string.previous, KeyCodes.MEDIA_PREVIOUS, 301))
            .addAction(action(R.drawable.ic_qs_play_pause, R.string.play_pause, KeyCodes.MEDIA_PLAY_PAUSE, 302))
            .addAction(action(R.drawable.ic_notif_next, R.string.next, KeyCodes.MEDIA_NEXT, 303))
            .addAction(action(R.drawable.ic_vol_up, R.string.volume_up, KeyCodes.VOLUME_UP, 304))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3),
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "media_controls"
        private const val NOTIFICATION_ID = 7
        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MediaControlsService? = null

        /** Starts or stops the controls. */
        fun apply(context: Context, on: Boolean) {
            val intent = Intent(context, MediaControlsService::class.java)
            if (on) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }

        /** Shows the TV and its foreground app in the notification, if it is running. */
        fun updateNowPlaying(tvName: String?, currentApp: String?, powered: Boolean?) {
            val app = when {
                powered == false -> "Off"
                currentApp.isNullOrEmpty() -> null
                else -> Apps.name(currentApp)
            }
            main.post { instance?.update(tvName, app) }
        }
    }
}
