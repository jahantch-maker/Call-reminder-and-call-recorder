package com.callbackreminder.app

import android.graphics.Color as AndroidColor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The review reminder popup: what is waiting, with Review now, Snooze and Later. With the
 * "Full screen, then notification" style it closes by itself after a few seconds and leaves a
 * notification behind.
 */
class ReviewAlertActivity : ComponentActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeOut = Runnable { leaveNotification() }
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        // Back works like Later: the popup goes, the notification stays so it can be found later.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveNotification()
        })

        val counts = ReviewReminder.counts(this)
        if (savedInstanceState == null) startSound()
        if (ReminderStyle.mode(this) == ReminderStyle.Mode.FULL_SCREEN_THEN_NOTIFY) {
            handler.postDelayed(timeOut, ReminderStyle.seconds(this) * 1_000L)
        }

        setContent {
            CallbackTheme(dark = true) {
                ReviewScreen(
                    counts = counts,
                    onReview = {
                        ReviewReminder.cancelNotification(this)
                        startActivity(ReviewReminder.reviewIntent(this))
                        close()
                    },
                    onSnooze = { minutes ->
                        ReviewReminder.snooze(this, minutes)
                        close()
                    },
                    onLater = { leaveNotification() },
                )
            }
        }
    }

    private fun leaveNotification() {
        ReviewReminder.notify(this, ReviewReminder.counts(this), quiet = true, fullScreen = false)
        close()
    }

    private fun close() {
        handler.removeCallbacks(timeOut)
        stopSound()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeOut)
        stopSound()
        super.onDestroy()
    }

    /** Your Popup ringtone, like the full-screen call-back reminder (only when the phone is on Normal). */
    private fun startSound() {
        if (getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val repeat = ReminderSound.repeats(this)
        val isPing = ReminderSound.POPUP.builtIn(this) != null
        val choices = listOf(
            ReminderSound.uri(this),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for ((i, uri) in choices.withIndex()) {
            if (uri == null) continue
            val gap = if (i == 0 && isPing) PING_GAP_MS else 0L
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = repeat && gap == 0L
                if (repeat && gap > 0L) {
                    mp.setOnCompletionListener { done ->
                        handler.postDelayed({
                            if (player === done) {
                                try {
                                    done.seekTo(0)
                                    done.start()
                                } catch (e: Exception) {
                                    // Stopped meanwhile.
                                }
                            }
                        }, gap)
                    }
                }
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                try {
                    mp.release()
                } catch (e2: Exception) {
                    // Ignore.
                }
            }
        }
    }

    private fun stopSound() {
        val mp = player ?: return
        player = null
        try {
            mp.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        try {
            mp.release()
        } catch (e: Exception) {
            // Already released.
        }
    }

    private companion object {
        const val PING_GAP_MS = 1_200L
    }
}

private val GlassWhite = Color(0x24FFFFFF)

@Composable
private fun ReviewScreen(
    counts: ReviewReminder.Counts,
    onReview: () -> Unit,
    onSnooze: (Int) -> Unit,
    onLater: () -> Unit,
) {
    var snoozeOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF5A3D00), Color(0xFF0B0E17))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "REVIEW REMINDER",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
            Icon(
                Icons.Rounded.PendingActions,
                contentDescription = null,
                tint = Color(0xFFFFD36B),
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "Time to review",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Surface(shape = RoundedCornerShape(16.dp), color = GlassWhite, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountLine("Calls overdue", counts.overdueCalls)
                    CountLine("Calls to review", counts.callsToReview)
                    CountLine("Texts to review", counts.textsToReview)
                    CountLine("To schedule", counts.toSchedule)
                    CountLine("Dossier not ticked", counts.dossierLeft)
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onReview,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF2A1C00)),
            ) {
                Text("Review now", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { snoozeOpen = true },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(27.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Snooze", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = snoozeOpen, onDismissRequest = { snoozeOpen = false }) {
                        ReviewReminder.SNOOZE_MINUTES.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(if (m < 60) "$m min" else if (m == 60) "1 hour" else "${m / 60} hours") },
                                onClick = {
                                    snoozeOpen = false
                                    onSnooze(m)
                                }
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Later", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CountLine(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = if (count > 0) 1f else 0.5f), fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            "$count",
            color = if (count > 0) Color(0xFFFFD36B) else Color.White.copy(alpha = 0.5f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
