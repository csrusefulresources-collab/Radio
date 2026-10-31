package com.radio.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Single place that owns the player and all radio state (used by the Activity and the Service). */
object RadioController {

    interface Listener { fun onRadioStateChanged() }

    private const val SCAN_DWELL_MS = 6000L
    private const val ERROR_SKIP_MS = 1500L
    private const val RETRY_DELAY_MS = 3000L
    private const val MAX_RETRIES = 3
    private val SLEEP_OPTIONS = intArrayOf(0, 15, 30, 60)

    private lateinit var appContext: Context
    private var player: ExoPlayer? = null
    private var stations: List<Station> = emptyList()
    private val listeners = LinkedHashSet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    var band: Band = Band.FM
        private set
    var index: Int = 0
        private set
    var hasError: Boolean = false
        private set
    var isBuffering: Boolean = false
        private set
    var isMuted: Boolean = false
        private set
    var isScanning: Boolean = false
        private set
    var sleepMinutes: Int = 0
        private set

    val isActive: Boolean get() = player?.playWhenReady == true

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return
            step(1)
            handler.postDelayed(this, SCAN_DWELL_MS)
        }
    }

    private val retryRunnable = Runnable {
        if (isActive && !isScanning && retries < MAX_RETRIES) {
            retries++
            startCurrent(resetRetries = false)
        }
    }

    private val sleepRunnable = Runnable {
        sleepMinutes = 0
        pause()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                hasError = false
                retries = 0
            }
            notifyChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            notifyChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            hasError = true
            isBuffering = false
            if (isScanning) {
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, ERROR_SKIP_MS)
            } else if (isActive && retries < MAX_RETRIES) {
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
            }
            notifyChanged()
        }
    }

    fun init(context: Context) {
        if (player != null) return
        appContext = context.applicationContext
        stations = StationRepository.load(appContext)

        val prefs = appContext.getSharedPreferences("radio_state", Context.MODE_PRIVATE)
        band = Band.values().getOrElse(prefs.getInt("band", 0)) { Band.FM }
        index = prefs.getInt("index", 0)
        isMuted = prefs.getBoolean("muted", false)
        val n = stationsInBand().size
        index = if (n == 0) 0 else index.coerceIn(0, n - 1)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.volume = if (isMuted) 0f else 1f
                it.addListener(playerListener)
            }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyChanged() {
        appContext.getSharedPreferences("radio_state", Context.MODE_PRIVATE).edit()
            .putInt("band", band.ordinal).putInt("index", index).putBoolean("muted", isMuted).apply()
        listeners.toList().forEach { it.onRadioStateChanged() }
    }

    fun stationsInBand(): List<Station> = stations.filter { it.band == band }
    fun currentStation(): Station? = stationsInBand().getOrNull(index)
    fun nextBandOf(): Band = Band.values()[(band.ordinal + 1) % Band.values().size]

    // ---- actions ----

    fun togglePlay() { if (isActive) pause() else play() }

    fun play() { stopScanInternal(); startCurrent() }

    fun pause() {
        stopScanInternal()
        handler.removeCallbacks(retryRunnable)
        player?.playWhenReady = false
        player?.stop()
        isBuffering = false
        hasError = false
        notifyChanged()
    }

    fun stop() { pause() }

    fun next() { stopScanInternal(); step(1) }
    fun previous() { stopScanInternal(); step(-1) }

    fun select(i: Int) {
        stopScanInternal()
        if (i in stationsInBand().indices) { index = i; startCurrent() }
    }

    fun nextBand() {
        stopScanInternal()
        val wasActive = isActive
        band = nextBandOf()
        index = 0
        if (wasActive && stationsInBand().isNotEmpty()) {
            startCurrent()
        } else {
            handler.removeCallbacks(retryRunnable)
            player?.playWhenReady = false
            player?.stop()
            hasError = false
            notifyChanged()
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        player?.volume = if (isMuted) 0f else 1f
        notifyChanged()
    }

    fun toggleScan() {
        if (isScanning) { stopScanInternal(); notifyChanged(); return }
        if (stationsInBand().isEmpty()) return
        isScanning = true
        step(1)
        handler.postDelayed(scanRunnable, SCAN_DWELL_MS)
    }

    fun cycleSleepTimer() {
        sleepMinutes = SLEEP_OPTIONS[(SLEEP_OPTIONS.indexOf(sleepMinutes) + 1) % SLEEP_OPTIONS.size]
        handler.removeCallbacks(sleepRunnable)
        if (sleepMinutes > 0) handler.postDelayed(sleepRunnable, sleepMinutes * 60_000L)
        notifyChanged()
    }

    /** Adds a user station to the current band and starts playing it. */
    fun addStation(name: String, frequency: String, url: String) {
        StationRepository.addUser(appContext, Station(name, frequency, band, url, true))
        stations = StationRepository.load(appContext)
        index = stationsInBand().indexOfLast { it.isUser && it.url == url }.coerceAtLeast(0)
        stopScanInternal()
        startCurrent()
    }

    fun removeCurrentStation() {
        val st = currentStation() ?: return
        if (!st.isUser) return
        stopScanInternal()
        StationRepository.removeUser(appContext, st)
        stations = StationRepository.load(appContext)
        val n = stationsInBand().size
        index = if (n == 0) 0 else index.coerceAtMost(n - 1)
        if (isActive && n > 0) {
            startCurrent()
        } else {
            player?.playWhenReady = false
            player?.stop()
            notifyChanged()
        }
    }

    // ---- internals ----

    private fun stopScanInternal() {
        isScanning = false
        handler.removeCallbacks(scanRunnable)
    }

    private fun step(delta: Int) {
        val n = stationsInBand().size
        if (n == 0) { notifyChanged(); return }
        index = ((index + delta) % n + n) % n
        startCurrent()
    }

    private fun startCurrent(resetRetries: Boolean = true) {
        val st = currentStation()
        val p = player
        if (st == null || p == null) { notifyChanged(); return }
        if (resetRetries) retries = 0
        handler.removeCallbacks(retryRunnable)
        hasError = false
        p.setMediaItem(MediaItem.fromUri(st.url))
        p.volume = if (isMuted) 0f else 1f
        p.prepare()
        p.playWhenReady = true
        ContextCompat.startForegroundService(appContext, Intent(appContext, RadioService::class.java))
        notifyChanged()
    }
}
