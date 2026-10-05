package com.callbackreminder.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps track of which reminders are ringing right now.
 * A reminder rings from the moment it is due until you Call back, Snooze, mark it Done or delete it.
 */
object RingController {
    private val _active = MutableStateFlow<List<Long>>(emptyList())
    val active: StateFlow<List<Long>> = _active.asStateFlow()

    @Synchronized
    fun start(ctx: Context, r: Reminder) {
        val app = ctx.applicationContext
        if (r.id !in _active.value) _active.value = _active.value + r.id
        try {
            ContextCompat.startForegroundService(app, Intent(app, ReminderRingService::class.java))
        } catch (e: Exception) {
            // The phone refused to start the ringing service: fall back to a normal banner.
            _active.value = _active.value - r.id
            Notifier.showReminder(app, r)
        }
    }

    @Synchronized
    fun dismiss(id: Long) {
        if (id in _active.value) _active.value = _active.value - id
    }
}

/**
 * Plays the chosen ringtone (looping, at the phone's ringer volume) and vibrates, and opens the
 * full-screen reminder. It keeps going until every ringing reminder has been dealt with.
 */
class ReminderRingService : Service() {

    companion object {
        // Safety net: stop the noise (not the reminder) if nobody reacts for half an hour.
        private const val MAX_RING_MS = 30 * 60_000L
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var silenceTimer: Job? = null
    private var ringing = false
    private var observing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReminderStore.init(this)
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val due = currentReminders()
        // Android requires the service to go "foreground" right away, even if we then stop.
        enterForeground(Notifier.buildRingNotification(this, due))

        if (due.isEmpty()) {
            shutDown()
            return START_NOT_STICKY
        }

        // If you are on a phone call right now, stay quiet: the notification still waits for you.
        if (!inCall()) {
            if (!ringing) startRinging()
            openAlertScreen()
        }

        if (!observing) {
            observing = true
            scope.launch {
                RingController.active.collect { onActiveChanged() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    private fun currentReminders(): List<Reminder> =
        RingController.active.value.mapNotNull { id -> ReminderStore.get(id)?.takeIf { !it.done } }

    private fun enterForeground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.NOTIF_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifier.NOTIF_RING, n)
        }
    }

    private fun onActiveChanged() {
        val due = currentReminders()
        if (due.isEmpty()) {
            shutDown()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(Notifier.NOTIF_RING, Notifier.buildRingNotification(this, due))
    }

    private fun shutDown() {
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun inCall(): Boolean = try {
        getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: Exception) {
        false
    }

    private fun openAlertScreen() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            startActivity(
                Intent(this, ReminderAlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            // The full-screen notification is the backup.
        }
    }

    // ---- Sound and vibration ----

    private fun startRinging() {
        ringing = true
        val am = getSystemService(AudioManager::class.java)
        val mode = am.ringerMode

        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callback:ring").apply {
                setReferenceCounted(false)
                acquire(MAX_RING_MS + 60_000L)
            }
        } catch (e: Exception) {
            wakeLock = null
        }

        // Normal mode: ring. Vibrate mode: vibrate only. Silent mode: nothing, just the screen.
        if (mode == AudioManager.RINGER_MODE_NORMAL) startSound(am)
        if (mode != AudioManager.RINGER_MODE_SILENT) startVibration()

        silenceTimer = scope.launch {
            delay(MAX_RING_MS)
            stopRinging()
        }
    }

    private fun stopRinging() {
        ringing = false
        silenceTimer?.cancel()
        silenceTimer = null
        try {
            player?.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // Already released.
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // Nothing to cancel.
        }
        vibrator = null
        val req = focusRequest
        if (req != null) {
            try {
                getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
            } catch (e: Exception) {
                // Ignore.
            }
        }
        focusRequest = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Ignore.
        }
        wakeLock = null
    }

    private fun startSound(am: AudioManager) {
        // "Ringtone" usage means the phone's ring volume and silent switch decide how loud it is.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) {
            focusRequest = null
        }

        val choices = listOf(
            ReminderSound.uri(this),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in choices) {
            player = tryPlay(uri, attrs)
            if (player != null) break
        }
    }

    private fun tryPlay(uri: Uri?, attrs: AudioAttributes): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (e2: Exception) {
                // Ignore.
            }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 800), 0), attrs)
        } catch (e: Exception) {
            // Vibration is a bonus; the sound and screen still work.
        }
    }
}
