package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Round badge with the first letter of the caller's name. */
@Composable
fun Avatar(text: String, size: Dp = 44.dp) {
    val initial = text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp
        )
    }
}

/**
 * The Recordings and Settings icons at the top right of every bottom-bar tab. The tabs page
 * provides them; anywhere else (a page opened over the tabs) shows nothing.
 */
val LocalTopActions = compositionLocalOf<@Composable () -> Unit> { {} }

@Composable
fun TopActions() {
    // One Row, so a header using SpaceBetween keeps the two icons together instead of spreading them.
    Row(verticalAlignment = Alignment.CenterVertically) { LocalTopActions.current() }
}

/** Today as "Mon 20 Oct", shown in front of the status line under each big page title. */
fun todayLabel(): String = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date())

/** The small grey date followed by the page's status (red when something is late). */
@Composable
fun DateStatusLine(status: String, red: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${todayLabel()} · ", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (red) cs.error else cs.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A slim title bar that slides in at the top once the big header has scrolled away, so the title and the
 * Recordings / Settings icons stay reachable without the big header taking space.
 */
@Composable
fun SlimTitleBar(state: androidx.compose.foundation.lazy.LazyListState, title: String, top: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shown by androidx.compose.runtime.remember(state) {
        androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex > 0 }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = shown,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { -it },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { -it },
        modifier = modifier,
    ) {
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = top).padding(start = 16.dp, end = 4.dp).height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                TopActions()
            }
        }
    }
}

/** A friendly empty page: a soft accent picture, a title, a line of help and an optional button. */
@Composable
fun EmptyPicture(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    button: String? = null,
    onButton: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(cs.primaryContainer.copy(alpha = 0.55f)))
            Box(Modifier.size(64.dp).clip(CircleShape).background(cs.primaryContainer))
            Box(Modifier.size(14.dp).align(Alignment.TopEnd).clip(CircleShape).background(cs.primary.copy(alpha = 0.35f)))
            Box(Modifier.size(9.dp).align(Alignment.BottomStart).clip(CircleShape).background(cs.tertiary.copy(alpha = 0.45f)))
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(34.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (button != null) {
            androidx.compose.material3.FilledTonalButton(onClick = onButton, modifier = Modifier.padding(top = 12.dp)) { Text(button) }
        }
    }
}

/**
 * Once a day, when the last task due today is done: a short burst of confetti over the page.
 * [left] is how many tasks due today (or late) are still open.
 */
object DayCleared {
    private const val KEY = "day_cleared_on"
    val show = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun check(c: android.content.Context, before: Int, left: Int) {
        if (before <= 0 || left != 0) return
        val today = java.time.LocalDate.now().toString()
        if (AppSettings.getString(c, KEY) == today) return
        AppSettings.setString(c, KEY, today)
        show.value = true
    }
}

/** Watches [left] and fires [DayCleared] when it drops to zero. */
@Composable
fun WatchDayCleared(left: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val last = androidx.compose.runtime.remember { intArrayOf(-1) }
    androidx.compose.runtime.LaunchedEffect(left) {
        if (last[0] >= 0) DayCleared.check(ctx, last[0], left)
        last[0] = left
    }
}

/** The confetti and "Day cleared" pill, drawn over everything while [DayCleared.show] is on. */
@Composable
fun DayClearedOverlay() {
    val on by DayCleared.show.collectAsState()
    if (!on) return
    val cs = MaterialTheme.colorScheme
    val t = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val pieces = androidx.compose.runtime.remember {
        val r = java.util.Random()
        List(70) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.3f, 0.6f + r.nextFloat() * 0.8f, r.nextFloat() * 360f, (r.nextFloat() - 0.5f) * 0.25f, r.nextInt(5).toFloat()) }
    }
    val colors = listOf(cs.primary, Color(0xFF14B8A6), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF8B5CF6))
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        t.animateTo(1f, androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearEasing))
        DayCleared.show.value = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            pieces.forEach { f ->
                val x = (f[0] + f[4] * p) * size.width
                val y = (-0.1f + f[1] + p * f[2]) * size.height
                val a = if (p < 0.75f) 1f else (1f - p) * 4f
                rotate(f[3] + p * 540f, pivot = androidx.compose.ui.geometry.Offset(x, y)) {
                    drawRect(
                        colors[f[5].toInt()].copy(alpha = a),
                        topLeft = androidx.compose.ui.geometry.Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(8.dp.toPx(), 14.dp.toPx())
                    )
                }
            }
        }
        val pillAlpha = when {
            t.value < 0.1f -> t.value * 10f
            t.value > 0.8f -> (1f - t.value) * 5f
            else -> 1f
        }
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = cs.primary,
            shadowElevation = 8.dp,
            modifier = Modifier.alpha(pillAlpha.coerceIn(0f, 1f))
        ) {
            Text(
                "🎉  Day cleared. Nice work!",
                color = cs.onPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * The compact top of every tab: big title, its status (red when something is late) and "⏱ n no time ›" on one
 * line, then 🎤 ⚙.
 */
@Composable
fun PageTitleRow(title: String, status: String, red: Boolean, noTime: Int = 0, onNoTime: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                status, fontSize = 13.sp, color = if (red) cs.error else cs.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp)
            )
            if (noTime > 0 && onNoTime != null) Text(
                " · ⏱ $noTime no time ›", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeedOrange, maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp).clickable(onClick = onNoTime)
            )
        }
        TopActions()
    }
}

/** ☆ next to the Open | Done box: show only starred. */
@Composable
fun StarToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.IconButton(onClick = { onChange(!on) }) {
        androidx.compose.material3.Icon(
            if (on) androidx.compose.material.icons.Icons.Rounded.Star else androidx.compose.material.icons.Icons.Rounded.StarBorder,
            contentDescription = if (on) "Showing starred only" else "Show starred only",
            tint = if (on) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
