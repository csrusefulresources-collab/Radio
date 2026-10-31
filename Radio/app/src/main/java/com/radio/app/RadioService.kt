package com.radio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service: keeps audio playing with the screen off, handles media buttons, shows controls. */
class RadioService : Service(), RadioController.Listener {

    companion object {
        const val ACTION_PREV = "com.radio.app.PREV"
        const val ACTION_NEXT = "com.radio.app.NEXT"
        const val ACTION_TOGGLE = "com.radio.app.TOGGLE"
        const val ACTION_STOP = "com.radio.app.STOP"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIF_ID = 1
    }

    private lateinit var session: MediaSessionCompat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RadioController.init(this)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        session = MediaSessionCompat(this, "Radio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { RadioController.play() }
                override fun onPause() { RadioController.pause() }
                override fun onSkipToNext() { RadioController.next() }
                override fun onSkipToPrevious() { RadioController.previous() }
                override fun onStop() { shutDown() }
            })
            isActive = true
        }
        updateSession()
        RadioController.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> RadioController.previous()
            ACTION_NEXT -> RadioController.next()
            ACTION_TOGGLE -> RadioController.togglePlay()
            ACTION_STOP -> { shutDown(); return START_NOT_STICKY }
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_NOT_STICKY
    }

    override fun onRadioStateChanged() {
        updateSession()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        RadioController.removeListener(this)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun shutDown() {
        RadioController.removeListener(this)
        RadioController.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateSession() {
        val st = RadioController.currentStation()
        val state = when {
            RadioController.isActive && RadioController.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            RadioController.isActive -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st?.name ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, bandText())
                .build()
        )
    }

    private fun bandText(): String {
        val st = RadioController.currentStation()
        val bandName = getString(RadioController.band.nameRes)
        val freq = st?.frequency.orEmpty()
        return if (freq.isBlank()) bandName else "$bandName • $freq ${getString(RadioController.band.unitRes)}"
    }

    private fun serviceIntent(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req, Intent(this, RadioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val st = RadioController.currentStation()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val active = RadioController.isActive
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(st?.name ?: getString(R.string.app_name))
            .setContentText(bandText())
            .setContentIntent(open)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, getString(R.string.btn_prev), serviceIntent(ACTION_PREV, 1))
            .addAction(
                if (active) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (active) R.string.btn_pause else R.string.btn_play),
                serviceIntent(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.btn_next), serviceIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, getString(R.string.btn_close), serviceIntent(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
