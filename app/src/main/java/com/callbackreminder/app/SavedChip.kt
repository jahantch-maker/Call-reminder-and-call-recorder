package com.callbackreminder.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** True inside a popup over another app (after-call popup, widget): its saves show the chip there. */
val LocalSavedOutside = compositionLocalOf { false }

/**
 * v0.51 "✓ Saved · task · View · Edit": after a new task or follow-up is saved, its window shrinks into this slim
 * bar at the bottom for a few seconds. View shows it in its list (outlined); Edit opens the same task again.
 */
object SavedChip {
    enum class Kind { MINE, CALL, TEXT, SCHED }

    data class Saved(
        val kind: Kind,
        val id: Long,
        val title: String,
        val outside: Boolean,
        val stamp: Long = System.currentTimeMillis(),
        /** "Saved", "Saved to To schedule", "Sent to Hasnain"… */
        val label: String = "Saved",
    )

    val shown = MutableStateFlow<Saved?>(null)

    /** The newest id of each kind, taken before a save, so what the save made can be found after it. */
    data class Mark(val call: Long, val text: Long, val sched: Long, val mine: Long)

    fun mark(): Mark = Mark(
        ReminderStore.items.value.maxOfOrNull { it.id } ?: 0L,
        SmsStore.items.value.maxOfOrNull { it.id } ?: 0L,
        ToScheduleStore.items.value.maxOfOrNull { it.id } ?: 0L,
        MyTaskStore.items.value.maxOfOrNull { it.id } ?: 0L,
    )

    /** What a save made since [m] (a call first, then its texts, To schedule, a My task); null when nothing new. */
    fun since(m: Mark, outside: Boolean, label: String = "Saved"): Saved? {
        ReminderStore.items.value.filter { it.id > m.call }.maxByOrNull { it.id }
            ?.let { return Saved(Kind.CALL, it.id, it.note.ifBlank { "Call back" }, outside, label = label) }
        SmsStore.items.value.filter { it.id > m.text }.minByOrNull { it.id }
            ?.let { return Saved(Kind.TEXT, it.id, it.message, outside, label = label) }
        ToScheduleStore.items.value.filter { it.id > m.sched }.maxByOrNull { it.id }
            ?.let { return Saved(Kind.SCHED, it.id, it.headline, outside, label = if (label == "Saved") "Saved to To schedule" else label) }
        MyTaskStore.items.value.filter { it.id > m.mine }.maxByOrNull { it.id }
            ?.let { return Saved(Kind.MINE, it.id, it.title, outside, label = label) }
        return null
    }

    /** Shows the chip for what was saved since [m]; false when nothing new was made. */
    fun showSince(m: Mark, outside: Boolean, label: String = "Saved"): Boolean {
        val s = since(m, outside, label) ?: return false
        shown.value = s
        return true
    }

    fun showMine(t: MyTask, outside: Boolean) {
        shown.value = Saved(Kind.MINE, t.id, t.title, outside)
    }

    /** A save just shown on the chip (a popup then waits for the chip before it closes). */
    fun fresh(): Boolean = shown.value?.let { System.currentTimeMillis() - it.stamp < 3_000L } == true

    /** View: the app on its list, the task outlined (the same jump as search and Show in list). */
    fun view(ctx: Context, s: Saved) {
        val open = when (s.kind) {
            Kind.MINE -> MainActivity.OPEN_MY_TASKS
            Kind.CALL, Kind.TEXT -> MainActivity.OPEN_FOLLOW_UPS
            Kind.SCHED -> MainActivity.OPEN_TO_SCHEDULE
        }
        val i = Intent(ctx, MainActivity::class.java)
            .setData(Uri.parse("cbsaved://app/$open/${s.kind}/${s.id}"))
            .putExtra(MainActivity.EXTRA_OPEN, open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (s.kind) {
            Kind.MINE, Kind.CALL -> i.putExtra(MainActivity.EXTRA_TASK, s.id)
            Kind.TEXT -> i.putExtra(MainActivity.EXTRA_SHOW_TEXT, s.id)
            Kind.SCHED -> {}
        }
        runCatching { ctx.startActivity(i) }
    }

    fun taskOf(s: Saved): OpenTask = when (s.kind) {
        Kind.MINE -> OpenTask.Mine(s.id)
        Kind.CALL -> OpenTask.Call(s.id)
        Kind.TEXT -> OpenTask.Text(s.id)
        Kind.SCHED -> OpenTask.Sched(s.id)
    }
}

private val CheckGreen = Color(0xFF1FA45B)

/**
 * The chip's place: over the whole screen, letting taps through. It starts the size of the window that just closed
 * and shrinks smoothly into the slim bar at the bottom. [outside]: in a popup over another app, which closes
 * ([onGone]) once the chip is gone or View is tapped.
 */
@Composable
fun SavedChipHost(outside: Boolean = false, onGone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val s by SavedChip.shown.collectAsState()
    var editing by remember { mutableStateOf<SavedChip.Saved?>(null) }
    editing?.let { e ->
        TaskEditorDialog(task = SavedChip.taskOf(e), onDismiss = { editing = null; if (outside) onGone() })
    }
    val saved = s?.takeIf { it.outside == outside } ?: return
    val grow = remember(saved.stamp) { Animatable(0f) }
    val fade = remember(saved.stamp) { Animatable(1f) }
    LaunchedEffect(saved.stamp) {
        grow.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        delay(if (outside) 4_000L else 5_000L)
        fade.animateTo(0f, tween(220))
        if (SavedChip.shown.value?.stamp == saved.stamp) SavedChip.shown.value = null
        if (outside && editing == null) onGone()
    }
    val cs = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
        val p = grow.value
        // From the window (wide, tall, 28dp corners, in the middle) to the slim pill near the bottom.
        val winH = (maxHeight * 0.42f).coerceAtMost(380.dp)
        val w = lerp(maxWidth - 24.dp, maxWidth - 32.dp, p)
        val h = lerp(winH, 40.dp, p)
        val lift = lerp((maxHeight - winH) / 2, 20.dp, p)
        Surface(
            shape = RoundedCornerShape(lerp(28.dp, 20.dp, p)),
            color = if (dark) cs.surfaceContainerHigh else Color(0xFFF4F2F8),
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset(y = -lift)
                .width(w)
                .height(h)
                .graphicsLayer { alpha = fade.value },
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 4.dp).graphicsLayer { alpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).background(CheckGreen, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${saved.label} · ${saved.title}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ChipLink("View") {
                    SavedChip.shown.value = null
                    SavedChip.view(ctx, saved)
                    if (outside) onGone()
                }
                ChipLink("Edit") {
                    SavedChip.shown.value = null
                    editing = saved
                }
            }
        }
    }
}

@Composable
private fun ChipLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** For a popup activity: whether it should wait for the chip before it closes. */
fun Activity.holdForSavedChip(): Boolean = SavedChip.fresh() && SavedChip.shown.value?.outside == true
