package com.callbackreminder.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The one task card used everywhere (My tasks, Follow-ups, To schedule, Overdue, the person page, Home):
 * tick on the left = Done, the task in bold, a time box (📞 💬 inside when it is a follow-up, red when late)
 * and a person box, then 📞 and WA when there is a person, and ★. No ⋮: tapping opens the card in place.
 */

/** Which part of a follow-up the time box stands for: 📞, 💬 or both. */
enum class TimeIcons { NONE, CALL, TEXT, BOTH }

/**
 * One task. [time] null shows the orange "⏱ No time yet". [pos] set draws the card's own block (rounded at the
 * ends of its group, thin lines between); left null it sits in a [TaskGroup]. [expandedContent] slides open under
 * the text when [expanded].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskCard(
    title: String,
    done: Boolean,
    onTick: () -> Unit,
    time: String?,
    onClick: () -> Unit,
    late: Boolean = false,
    timeIcons: TimeIcons = TimeIcons.NONE,
    person: String? = null,
    chips: List<RowChip> = emptyList(),
    note: String? = null,
    sub: String? = null,
    starred: Boolean = false,
    onStar: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onWhatsApp: (() -> Unit)? = null,
    expanded: Boolean = false,
    pos: RowPos? = null,
    highlighted: Boolean = false,
    handle: Modifier? = null,
    tickEnabled: Boolean = true,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (expanded) cs.primary.copy(alpha = if (LocalDarkTheme.current) 0.10f else 0.05f).compositeOver(cs.surface)
        else cs.surface,
        tween(200), label = "cardBg"
    )
    val shape = pos?.let { rowShape(it) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (shape != null) Modifier.clip(shape).background(bg) else Modifier.background(bg))
            .then(if (highlighted) Modifier.border(BorderStroke(2.dp, cs.primary), shape ?: RoundedCornerShape(14.dp)) else Modifier)
    ) {
        if (pos == RowPos.MIDDLE || pos == RowPos.LAST) HorizontalDivider(color = cs.surfaceVariant, thickness = 1.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TickCircle(done = done, enabled = tickEnabled, onClick = onTick)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val titleColor by animateColorAsState(
                    if (done) cs.onSurfaceVariant else if (highlighted) cs.primary else cs.onSurface,
                    tween(220), label = "cardTitle"
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!note.isNullOrBlank() && !expanded) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TimeBox(time, late && !done, timeIcons)
                    if (!person.isNullOrBlank()) {
                        TaskChip(person, CHIP_PLAIN, icon = { Icon(Icons.Rounded.Person, null, Modifier.size(14.dp).padding(end = 2.dp)) })
                    }
                    chips.forEach { SmallChip(it) }
                }
            }
            if (onCall != null || onWhatsApp != null) {
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onCall != null) RoundAction(Icons.Rounded.Call, "Call", cs.primaryContainer, cs.onPrimaryContainer, onClick = onCall)
                    if (onWhatsApp != null) WhatsAppAction(onClick = onWhatsApp)
                }
            }
            if (handle != null) {
                Box(handle.padding(start = 4.dp, end = 2.dp)) {
                    Icon(Icons.Rounded.DragIndicator, contentDescription = "Drag to move", tint = cs.outline, modifier = Modifier.size(22.dp))
                }
            }
            if (onStar != null) StarButton(starred, onStar) else Spacer(Modifier.width(6.dp))
        }
        AnimatedVisibility(
            visible = expanded && expandedContent != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 12.dp, bottom = 10.dp)) {
                expandedContent?.invoke(this)
            }
        }
    }
}

/** The time box: "Today 12:00 PM", with 📞 💬 inside for a follow-up; red when late, orange with no time. */
@Composable
fun TimeBox(time: String?, late: Boolean, icons: TimeIcons = TimeIcons.NONE) {
    val iconRow: (@Composable () -> Unit)? = if (icons == TimeIcons.NONE) null else ({
        Row(Modifier.padding(end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icons == TimeIcons.CALL || icons == TimeIcons.BOTH) Icon(Icons.Rounded.Call, null, Modifier.size(13.dp))
            if (icons == TimeIcons.TEXT || icons == TimeIcons.BOTH) Icon(Icons.Rounded.Sms, null, Modifier.size(13.dp))
        }
    })
    if (time == null) TaskChip("⏱ No time yet", CHIP_ORANGE, icon = iconRow)
    else TaskChip(time, if (late) CHIP_RED else CHIP_PLAIN, icon = iconRow)
}

/**
 * What an opened card shows: a few detail lines, the one 🕘 New time button (or [buttonLabel], like "Set time")
 * and small links. No Call or Done here: 📞 and the tick are already on the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardPanel(
    details: List<String>,
    links: List<RowLink>,
    onNewTime: (() -> Unit)?,
    buttonLabel: String = "New time",
) {
    val cs = MaterialTheme.colorScheme
    details.forEach {
        Text(it, fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
    }
    if (onNewTime != null) {
        Spacer(Modifier.height(6.dp))
        val (obg, ofg) = chipColors(ChipTone.ORANGE)
        Button(
            onClick = onNewTime,
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = obg, contentColor = ofg)
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(buttonLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (links.isNotEmpty()) {
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            links.forEach { l ->
                Text(
                    l.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (l.danger) cs.error else cs.primary,
                    modifier = Modifier.clickable(onClick = l.onClick).padding(vertical = 5.dp)
                )
            }
        }
    }
}

/** The box with two or three options (Tasks | Dossier, Open | Done, Follow-ups | My tasks…), used for every top choice. */
@Composable
fun BoxTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = modifier) {
        Row(Modifier.padding(4.dp)) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val fill by animateColorAsState(if (on) cs.surface else Color.Transparent, tween(200), label = "boxTab")
                val ink by animateColorAsState(if (on) cs.primary else cs.onSurfaceVariant, tween(200), label = "boxTabInk")
                Surface(
                    onClick = { onSelect(i) },
                    shape = RoundedCornerShape(11.dp),
                    color = fill,
                    shadowElevation = if (on) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** "🕘 2 waiting for a time ›": the slim link to the one To schedule list. */
@Composable
fun WaitingLink(count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = NoTimeFg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "$count waiting for a time",
            color = NoTimeFg,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text("›", color = NoTimeFg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

/** ★ on a follow-up that has no call (texts only); a call keeps its own star. */
object FuStars {
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys
    private var loaded = false

    private fun p(c: Context) = c.getSharedPreferences("fu_stars", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _keys.value = p(c).getStringSet("keys", emptySet())?.toSet() ?: emptySet()
    }

    fun toggle(c: Context, key: String) {
        load(c)
        val next = if (key in _keys.value) _keys.value - key else _keys.value + key
        _keys.value = next
        p(c).edit().putStringSet("keys", next).apply()
    }
}

/**
 * Rows that come back (Undo, Move back) slide in instead of popping up. Call [arrive] with the row's key just
 * before it returns; [AppearOnce] plays the entry once for that key.
 */
object Arrivals {
    val keys = MutableStateFlow<Set<String>>(emptySet())
    fun arrive(key: String) { keys.value = keys.value + key }
    fun seen(key: String) { keys.value = keys.value - key }
}

@Composable
fun AppearOnce(key: String, content: @Composable () -> Unit) {
    val arriving = remember(key) { key in Arrivals.keys.value }
    val state = remember(key) { androidx.compose.animation.core.MutableTransitionState(!arriving) }
    LaunchedEffect(key) {
        if (arriving) {
            state.targetState = true
            Arrivals.seen(key)
        }
    }
    AnimatedVisibility(
        visibleState = state,
        enter = expandVertically(tween(260)) + fadeIn(tween(260)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(160)),
    ) { content() }
}
