package com.callbackreminder.app

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full-screen "it is time to call back" screen. It stays up (and the phone keeps ringing)
 * until you tap Call back, Snooze or Done.
 */
class ReminderAlertActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALLBACK_ID = "callback_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the display, like an incoming call.
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        ReminderStore.init(this)
        handleCallBack(intent.getLongExtra(EXTRA_CALLBACK_ID, -1L))
        intent.removeExtra(EXTRA_CALLBACK_ID)

        setContent {
            CallbackTheme(dark = true) {
                AlertScreen(onAllDone = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallBack(intent.getLongExtra(EXTRA_CALLBACK_ID, -1L))
        intent.removeExtra(EXTRA_CALLBACK_ID)
    }

    /** The "Call back" button on the notification lands here. */
    private fun handleCallBack(id: Long) {
        if (id < 0) return
        val r = ReminderStore.get(id)
        // Place the call first, then stop the ringing (which closes this screen).
        if (r != null) dial(this, r.number)
        RingController.dismiss(id)
    }
}

private val CallGreen = Color(0xFF1FB57A)
private val GlassWhite = Color(0x24FFFFFF)

@Composable
private fun AlertScreen(onAllDone: () -> Unit) {
    val ctx = LocalContext.current
    val ids by RingController.active.collectAsState()
    val all by ReminderStore.items.collectAsState()
    val ringing = ids.mapNotNull { id -> all.firstOrNull { it.id == id && !it.done } }
    var pickFor by remember { mutableStateOf<Reminder?>(null) }

    // Nothing left ringing: close the screen.
    LaunchedEffect(ringing.isEmpty()) {
        if (ringing.isEmpty()) onAllDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2A6B), Color(0xFF0B0E17))))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            item(key = "header") {
                Text(
                    text = "CALL-BACK REMINDER",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
            }
            items(ringing, key = { it.id }) { r ->
                Box(Modifier.animateItem()) {
                    AlertCard(
                        r = r,
                        onCallBack = {
                            // Calls directly (even on the lock screen), then stops the ringing.
                            dial(ctx, r.number)
                            RingController.dismiss(r.id)
                        },
                        onSnooze = { ms -> ReminderStore.reschedule(r.id, ms) },
                        onPickTime = { pickFor = r },
                        onDone = { ReminderStore.markDone(r.id) },
                    )
                }
            }
        }
    }

    pickFor?.let { r ->
        DateTimePickerFlow(
            initialMs = System.currentTimeMillis() + 3_600_000L,
            onPicked = { ms ->
                pickFor = null
                ReminderStore.reschedule(r.id, ms)
            },
            onDismiss = { pickFor = null }
        )
    }
}

@Composable
private fun AlertCard(
    r: Reminder,
    onCallBack: () -> Unit,
    onSnooze: (Long) -> Unit,
    onPickTime: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    var snoozeOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(text = r.title, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = r.title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (r.name.isNotBlank() && r.number.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = r.number,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 17.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Due ${Fmt.dueLabel(r.dueAt)}",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
        )

        if (r.note.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GlassWhite,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = r.note,
                    modifier = Modifier.padding(14.dp),
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCallBack,
            enabled = r.number.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CallGreen,
                contentColor = Color.White,
                disabledContainerColor = GlassWhite,
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        ) {
            Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (r.number.isNotBlank()) "Call back" else "No number saved",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { snoozeOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Snooze", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = snoozeOpen, onDismissRequest = { snoozeOpen = false }) {
                    // Built when the menu opens, so "15 min" counts from the moment you tap.
                    QuickTimes.options(ctx).forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.title) },
                            trailingIcon = {
                                Text(
                                    o.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                snoozeOpen = false
                                onSnooze(o.whenMs)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Pick date & time…") },
                        onClick = {
                            snoozeOpen = false
                            onPickTime()
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
