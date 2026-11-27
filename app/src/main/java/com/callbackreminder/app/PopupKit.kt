package com.callbackreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 27 Nov (S14) Every full-screen popup: the phone's side buttons (volume up/down) or the screen going off (power)
 * stop the sound and vibration at once, and the popup stays on screen. [onSilence] does the stopping.
 */
abstract class SilenceActivity : ComponentActivity() {
    abstract fun onSilence()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onSilence()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onSilence()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** The big yellow "🔇 Silence" button at the bottom of every full-screen popup; grey "🔕 Silenced" once pressed. */
@Composable
fun SilenceButton(silenced: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !silenced,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFFC83D), contentColor = Color(0xFF2A1C00),
            disabledContainerColor = Color(0x33FFFFFF), disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
    ) {
        Text(if (silenced) "🔕 Silenced" else "🔇 Silence", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** The popup ringtone (your Popup sound, only when the phone is on Normal) plus vibration, for popups without RingService. */
class PopupSound(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    fun start(vibrate: Boolean = false) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (vibrate && am.ringerMode != AudioManager.RINGER_MODE_SILENT) vibrate()
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val repeat = ReminderSound.repeats(ctx)
        val isPing = ReminderSound.POPUP.builtIn(ctx) != null
        val choices = listOf(
            ReminderSound.uri(ctx),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for ((i, uri) in choices.withIndex()) {
            if (uri == null) continue
            val gap = if (i == 0 && isPing) PING_GAP_MS else 0L
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(ctx, uri)
                mp.isLooping = repeat && gap == 0L
                if (repeat && gap > 0L) {
                    mp.setOnCompletionListener { done ->
                        handler.postDelayed({
                            if (player === done) runCatching { done.seekTo(0); done.start() }
                        }, gap)
                    }
                }
                mp.prepare()
                mp.start()
                player = mp
                // Safety net: the noise (not the popup) stops after a few minutes.
                handler.postDelayed({ stop() }, MAX_MS)
                return
            } catch (e: Exception) {
                runCatching { mp.release() }
            }
        }
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 800), 0)) }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { vibrator?.cancel() }
        vibrator = null
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
    }

    private companion object {
        const val PING_GAP_MS = 1_200L
        const val MAX_MS = 5 * 60_000L
    }
}

/**
 * 27 Nov (S2.3, S4, S6.2, S14.2) The full-screen popup for a team task the website set to "Full screen", a plot file
 * step that is due, and the dossier reminder. Same look as the call-back reminder: header, due line, big headline,
 * person pill, green button, Open in app, yellow Silence. Back (or Skip) closes it; the notification stays.
 */
class TaskAlertActivity : SilenceActivity() {
    private lateinit var sound: PopupSound
    private val silenced = mutableStateOf(false)

    override fun onSilence() {
        sound.stop()
        silenced.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        sound = PopupSound(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = skip()
        })
        if (savedInstanceState == null) sound.start(vibrate = true)
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        silenced.value = false
        sound.stop()
        sound.start(vibrate = true)
        show()
    }

    private fun show() {
        val i = intent
        setContent {
            CallbackTheme(dark = true) {
                TaskAlertScreen(
                    head = i.getStringExtra(EXTRA_HEAD).orEmpty(),
                    due = i.getStringExtra(EXTRA_DUE).orEmpty(),
                    title = i.getStringExtra(EXTRA_TITLE).orEmpty(),
                    pill = i.getStringExtra(EXTRA_PILL).orEmpty(),
                    lines = i.getStringArrayExtra(EXTRA_LINES)?.toList().orEmpty(),
                    openLabel = i.getStringExtra(EXTRA_OPEN_LABEL) ?: "↗  Open in app",
                    call = i.getStringExtra(EXTRA_CALL).orEmpty(),
                    dossier = i.getBooleanExtra(EXTRA_DOSSIER, false),
                    silenced = silenced.value,
                    onCall = {
                        dial(this, i.getStringExtra(EXTRA_CALL).orEmpty())
                        close()
                    },
                    onOpen = { open() },
                    onSkip = { skip() },
                    onSilence = { onSilence() },
                )
            }
        }
    }

    private fun open() {
        @Suppress("DEPRECATION")
        val go = intent.getParcelableExtra<Intent>(EXTRA_OPEN)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val run = {
            go?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            close()
        }
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { run() }
            })
        } else run()
    }

    /** Skip / Back: the popup goes, its notification stays. A dossier Skip is noted for the website (S4.6). */
    private fun skip() {
        if (intent.getBooleanExtra(EXTRA_DOSSIER, false)) runCatching { TeamRepo.logDossierSkip() }
        close()
    }

    private fun close() {
        sound.stop()
        finish()
    }

    override fun onDestroy() {
        sound.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HEAD = "head"
        private const val EXTRA_DUE = "due"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PILL = "pill"
        private const val EXTRA_LINES = "lines"
        private const val EXTRA_OPEN = "open"
        private const val EXTRA_OPEN_LABEL = "open_label"
        private const val EXTRA_CALL = "call"
        private const val EXTRA_DOSSIER = "dossier"

        /** Opens the popup over everything (needs "show over other apps"; otherwise the notification is all there is). */
        fun show(
            ctx: Context, head: String, title: String, open: Intent, due: String = "", pill: String = "",
            lines: List<String> = emptyList(), call: String? = null, openLabel: String? = null, dossier: Boolean = false,
        ) {
            if (!Settings.canDrawOverlays(ctx)) return
            runCatching {
                ctx.startActivity(
                    Intent(ctx, TaskAlertActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_HEAD, head).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_DUE, due).putExtra(EXTRA_PILL, pill)
                        .putExtra(EXTRA_LINES, lines.toTypedArray()).putExtra(EXTRA_OPEN, open).putExtra(EXTRA_CALL, call)
                        .putExtra(EXTRA_OPEN_LABEL, openLabel).putExtra(EXTRA_DOSSIER, dossier)
                )
            }
        }
    }
}

private val AlertGreen = Color(0xFF1FB57A)
private val AlertGlass = Color(0x24FFFFFF)

@Composable
private fun outlined(label: String, onClick: () -> Unit, mod: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = mod.height(54.dp),
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TaskAlertScreen(
    head: String, due: String, title: String, pill: String, lines: List<String>, openLabel: String, call: String,
    dossier: Boolean, silenced: Boolean, onCall: () -> Unit, onOpen: () -> Unit, onSkip: () -> Unit, onSilence: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2A6B), Color(0xFF0B0E17))))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().safeBottomPadding(0.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(head.uppercase(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (due.isNotBlank()) Text(due, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(title, color = Color.White, fontSize = if (title.length > 80) 22.sp else 28.sp,
                        lineHeight = if (title.length > 80) 28.sp else 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (pill.isNotBlank()) {
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(32.dp)).background(AlertGlass).padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(text = pill, size = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(pill, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (lines.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AlertGlass).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            lines.take(12).forEach { Text("☐ $it", color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            if (lines.size > 12) Text("+ ${lines.size - 12} more", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (call.isNotBlank()) Button(
                        onClick = onCall,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(30.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AlertGreen, contentColor = Color.White),
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Call", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    } else Button(
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(30.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AlertGreen, contentColor = Color.White),
                    ) { Text(if (dossier) "📋 Open dossier" else openLabel.trim(), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (call.isNotBlank()) outlined(openLabel, onOpen, Modifier.weight(1f))
                        outlined("Skip", onSkip, Modifier.weight(1f))
                    }
                    SilenceButton(silenced, onSilence)
                }
            }
        }
    }
}
