package com.callbackreminder.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The places a call can be recorded from, and how "Automatic" chooses between them. */
object RecSource {
    data class Option(val key: String, val label: String, val hint: String)

    val options = listOf(
        Option(
            "auto", "Automatic (recommended)",
            "Starts with Voice recognition. If a call comes out silent, the next call uses the next source."
        ),
        Option("recognition", "Voice recognition", "Plain, unprocessed microphone audio."),
        Option("communication", "Voice communication", "Microphone with echo cancelling."),
        Option("mic", "Microphone", "The standard microphone source."),
        Option("call", "Call audio", "Both sides of the call. Most phones only allow this for the built-in dialer."),
        Option("unprocessed", "Unprocessed", "Raw microphone audio with no filtering."),
    )

    private val autoOrder = listOf("recognition", "communication", "mic", "call")

    fun audioSource(key: String): Int = when (key) {
        "recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "call" -> MediaRecorder.AudioSource.VOICE_CALL
        "unprocessed" -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.MIC
    }

    fun label(key: String): String = options.firstOrNull { it.key == key }?.label ?: key

    fun chosen(ctx: Context): String = AppSettings.getString(ctx, AppSettings.REC_SOURCE, "auto")

    fun isAuto(ctx: Context): Boolean = chosen(ctx) == "auto"

    private fun autoIndex(ctx: Context): Int =
        (AppSettings.getString(ctx, AppSettings.REC_AUTO_INDEX, "0").toIntOrNull() ?: 0)
            .mod(autoOrder.size)

    /** The sources to try, best first. Later ones are only used if the earlier ones cannot start. */
    fun order(ctx: Context): List<String> {
        val chosen = chosen(ctx)
        return if (chosen == "auto") {
            val start = autoIndex(ctx)
            autoOrder.indices.map { autoOrder[(start + it) % autoOrder.size] }
        } else {
            listOf(chosen) + autoOrder.filter { it != chosen }
        }
    }

    /** The last call in Automatic mode came out silent, so try the next source next time. */
    fun advance(ctx: Context) {
        AppSettings.setString(ctx, AppSettings.REC_AUTO_INDEX, (autoIndex(ctx) + 1).toString())
    }
}

/** The small "recording" notification that keeps the phone from stopping the app mid-call. */
object RecNotif {
    const val ID = 77

    fun build(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, Notifier.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Recording call")
            .setContentText("RB360 is recording this call")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }
}

/**
 * Records calls. Only one recording runs at a time.
 *
 * Android does not let ordinary apps record the call line itself, so this records through the
 * microphone. Having the app's Accessibility service switched on is what stops the phone from
 * silencing the microphone during a call (see [RecorderAccessibilityService]).
 */
object CallRecorder {
    /** A recording whose loudest moment is quieter than this counts as silent. */
    private const val SILENT_PEAK = 120
    private const val MIN_KEEP_MS = 3_000L

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Active(
        val recorder: MediaRecorder,
        val id: Long,
        val out: RecordingFiles.Output,
        val startedMs: Long,
        val sourceKey: String,
        var whatsApp: Boolean,
        val test: Boolean,
    ) {
        var peak: Int = 0
    }

    class Outcome(val ok: Boolean, val peak: Int, val seconds: Long, val silent: Boolean, val source: String)

    private var active: Active? = null
    private var sampler: ScheduledFuture<*>? = null

    /** The recording of the latest WhatsApp call, so its name can be filled in when the call ends. */
    private var lastWhatsAppId = -1L

    // ---- Public entry points (all work happens on one background thread, in order) ----

    fun startAsync(
        ctx: Context,
        sessionKey: Long,
        whatsApp: Boolean,
        type: Int,
        name: String,
        number: String,
        done: () -> Unit = {},
    ) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                startNow(app, sessionKey, whatsApp, type, name, number, test = false)
            } catch (e: Throwable) {
                note(app, "Could not start recording: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                done()
            }
        }
    }

    /** Stops the recording. With [onlyWhatsApp], a phone-call recording is left running. */
    fun stopAsync(ctx: Context, finalName: String? = null, onlyWhatsApp: Boolean = false) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                val a = active
                if (a != null && (!onlyWhatsApp || a.whatsApp)) stopNow(app, finalName)
            } catch (e: Throwable) {
                note(app, "Problem while stopping: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * A WhatsApp call has ended: put the caller's name and number on its recording.
     * If WhatsApp never told us the call connected, fall back to a nameless recording made since [sinceMs]
     * (on some phones a WhatsApp call also shows up as a phone call, which starts the recording).
     */
    fun nameWhatsAppAsync(ctx: Context, name: String, sinceMs: Long) {
        if (name.isBlank()) return
        val app = ctx.applicationContext
        exec.execute {
            try {
                val id = lastWhatsAppId.takeIf { it > 0L && RecordingStore.get(it) != null }
                    ?: RecordingStore.items.value
                        .filter { it.startedAt >= sinceMs && it.name.isBlank() && it.number.isBlank() }
                        .maxByOrNull { it.startedAt }
                        ?.also { RecordingStore.markWhatsApp(it.id, it.type, name) }
                        ?.id
                    ?: return@execute
                lastWhatsAppId = -1L
                RecordingStore.setCaller(id, name, ContactLookup.numberFor(app, name))
            } catch (e: Throwable) {
                // The recording keeps whatever name it already had.
            }
        }
    }

    /**
     * An outgoing phone call has ended and the call log says how long you really talked.
     * Cut the ringing from the start of that call's recording. Runs after the stop, on the same thread.
     */
    fun trimRingingAsync(ctx: Context, sessionKey: Long, talkSec: Long) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                val r = RecordingStore.items.value.firstOrNull {
                    it.sessionKey == sessionKey && !it.whatsApp && it.finished && it.id != active?.id
                } ?: return@execute
                if (RingTrimmer.trim(app, r, talkSec)) note(app, "Last recording saved, ringing cut off.")
            } catch (e: Throwable) {
                // The full recording is kept.
            }
        }
    }

    /** Records a few seconds with the chosen source so the user can check that it works. */
    fun testAsync(ctx: Context, seconds: Int, onResult: (String) -> Unit) {
        val app = ctx.applicationContext
        fun report(message: String) {
            mainHandler.post { onResult(message) }
        }
        exec.execute {
            try {
                if (active != null) {
                    report("A recording is already running.")
                } else if (!startNow(app, System.currentTimeMillis(), false, 0, "Test", "", test = true)) {
                    report("The test could not start. " + AppSettings.getString(app, AppSettings.REC_STATUS))
                } else {
                    // Do not sleep here: the level meter runs on this same thread.
                    exec.schedule({
                        val message = try {
                            val o = stopNow(app, null)
                            when {
                                o == null || !o.ok -> "The test recording failed. " +
                                    AppSettings.getString(app, AppSettings.REC_STATUS)
                                o.silent -> "Heard nothing (level ${o.peak}) using ${RecSource.label(o.source)}."
                                else -> "Microphone works (level ${o.peak}, ${RecSource.label(o.source)}). " +
                                    "Phone calls can behave differently, so make a short real call too."
                            }
                        } catch (e: Throwable) {
                            "Test failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                        report(message)
                    }, seconds.toLong(), TimeUnit.SECONDS)
                }
            } catch (e: Throwable) {
                report("Test failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---- The work itself ----

    private fun enabled(ctx: Context, whatsApp: Boolean): Boolean =
        TeamAccess.recordingAllowed() && AppSettings.get(ctx, if (whatsApp) AppSettings.REC_WHATSAPP else AppSettings.REC_PHONE)

    private fun startNow(
        app: Context,
        sessionKey: Long,
        whatsApp: Boolean,
        type: Int,
        name: String,
        number: String,
        test: Boolean,
    ): Boolean {
        active?.let { a ->
            // Some phones report a WhatsApp call as a phone call too, so the phone side may have
            // started this recording already. It belongs to the WhatsApp call: let it take over.
            if (whatsApp && !test && !a.test && !a.whatsApp) {
                a.whatsApp = true
                lastWhatsAppId = a.id
                RecordingStore.markWhatsApp(a.id, type, name)
            }
            return true
        }
        if (!test && !enabled(app, whatsApp)) return false
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            note(app, "Microphone permission is not allowed. Open Settings and tap Allow.")
            return false
        }

        promoteForeground(app)

        val startedAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startedAt))
        val baseName = (if (test) "Test_" else "Call_") + stamp
        var lastError = "unknown error"

        for (key in RecSource.order(app)) {
            val out = RecordingFiles.create(app, baseName)
            if (out == null) {
                lastError = "could not create the recording file"
                continue
            }
            val recorder = newRecorder(app)
            try {
                recorder.setAudioSource(RecSource.audioSource(key))
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(96000)
                val fd = out.pfd
                if (fd != null) {
                    recorder.setOutputFile(fd.fileDescriptor)
                } else {
                    recorder.setOutputFile(out.file!!.absolutePath)
                }
                recorder.prepare()
                recorder.start()
                try {
                    recorder.maxAmplitude // the first reading is always 0; this primes the meter
                } catch (x: Exception) {
                    // ignore
                }
            } catch (e: Exception) {
                lastError = "${RecSource.label(key)}: ${e.message ?: e.javaClass.simpleName}"
                try {
                    recorder.reset()
                } catch (x: Exception) {
                    // ignore
                }
                try {
                    recorder.release()
                } catch (x: Exception) {
                    // ignore
                }
                try {
                    out.pfd?.close()
                } catch (x: Exception) {
                    // ignore
                }
                RecordingFiles.delete(app, out.uri.toString())
                continue
            }

            val entry = RecordingStore.begin(
                uri = out.uri.toString(),
                sessionKey = sessionKey,
                whatsApp = whatsApp,
                type = type,
                name = name,
                number = number,
                source = key,
            )
            val a = Active(recorder, entry.id, out, SystemClock.elapsedRealtime(), key, whatsApp, test)
            active = a
            if (whatsApp && !test) lastWhatsAppId = entry.id
            sampler = exec.scheduleWithFixedDelay({ sample() }, 500, 500, TimeUnit.MILLISECONDS)
            note(app, "Recording started with ${RecSource.label(key)}.")
            return true
        }

        demoteForeground(app)
        note(app, "Could not start recording ($lastError).")
        return false
    }

    private fun sample() {
        val a = active ?: return
        try {
            a.peak = maxOf(a.peak, a.recorder.maxAmplitude)
        } catch (e: Exception) {
            // The recorder is not ready, or already stopping.
        }
    }

    private fun stopNow(app: Context, finalName: String?): Outcome? {
        val a = active ?: return null
        active = null
        sampler?.cancel(false)
        sampler = null

        try {
            a.peak = maxOf(a.peak, a.recorder.maxAmplitude)
        } catch (e: Exception) {
            // ignore
        }
        var ok = true
        try {
            a.recorder.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            a.recorder.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            a.out.pfd?.close()
        } catch (e: Exception) {
            // ignore
        }
        demoteForeground(app)

        val elapsedMs = SystemClock.elapsedRealtime() - a.startedMs
        val seconds = elapsedMs / 1000L
        val silent = a.peak < SILENT_PEAK

        // Test recordings, failed ones and accidental ones (hung up straight away) are not kept.
        if (a.test || !ok || elapsedMs < MIN_KEEP_MS) {
            RecordingFiles.delete(app, a.out.uri.toString())
            RecordingStore.remove(a.id)
            return Outcome(ok, a.peak, seconds, silent, a.sourceKey)
        }

        RecordingFiles.publish(app, a.out)
        val size = RecordingFiles.size(app, a.out)
        RecordingStore.finish(a.id, seconds, silent, size, finalName)

        if (silent && seconds >= 5 && RecSource.isAuto(app)) RecSource.advance(app)
        note(
            app,
            if (silent) {
                "Last recording had no sound (${RecSource.label(a.sourceKey)}). " +
                    if (RecSource.isAuto(app)) "Next call will try another source."
                    else "Try another source in Settings."
            } else {
                "Last recording saved (${RecSource.label(a.sourceKey)}, level ${a.peak})."
            }
        )
        RecordingStore.purgeOld(app)
        return Outcome(true, a.peak, seconds, silent, a.sourceKey)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    // ---- Keeping the app alive and allowed to use the microphone ----

    private fun promoteForeground(app: Context) {
        val svc = RecorderAccessibilityService.instance
        if (svc != null && svc.enterForeground()) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, CallRecordingService::class.java))
        } catch (e: Throwable) {
            // The phone refused; recording is still attempted below.
        }
    }

    private fun demoteForeground(app: Context) {
        RecorderAccessibilityService.instance?.leaveForeground()
        try {
            app.stopService(Intent(app, CallRecordingService::class.java))
        } catch (e: Throwable) {
            // ignore
        }
    }

    /** A one-line "what happened last" shown in Settings, to help find problems. */
    private fun note(app: Context, text: String) {
        val stamp = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        AppSettings.setString(app, AppSettings.REC_STATUS, "$stamp: $text")
    }
}

/**
 * Does nothing by itself. Android silences the microphone for apps recording during a call,
 * except for apps that have an Accessibility service switched on, so the recorder needs this
 * to be enabled once in Settings → Accessibility. It does not read the screen.
 */
class RecorderAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RecorderAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            val enabled = try {
                Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Exception) {
                null
            } ?: return false
            val me = ComponentName(ctx, RecorderAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Switched back on after banking: no more reminders needed.
        BankingPause.end(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service never reads what is on the screen.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            RecNotif.ID,
            RecNotif.build(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        true
    } catch (e: Throwable) {
        false
    }

    fun leaveForeground() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            // ignore
        }
    }
}

/** Fallback way to keep the app in the foreground while recording, when Accessibility is off. */
class CallRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                RecNotif.ID,
                RecNotif.build(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
