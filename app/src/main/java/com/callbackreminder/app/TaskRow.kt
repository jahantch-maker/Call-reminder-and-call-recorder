package com.callbackreminder.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The slim task row used by Calls, Texts, Home and the person page ("option C"): time on the left,
 * the task in bold, the person under it, small chips only when there is something to say, and one
 * round button on the right. Tapping a row opens it in place with every action.
 */

enum class ChipTone { BLUE, MUTED, RED, GREEN, ORANGE }

data class RowChip(
    val text: String,
    val tone: ChipTone,
    val icon: ImageVector? = null,
    val onClick: (() -> Unit)? = null,
)

/** Where a row sits in its day block, so the block has rounded corners only at its ends. */
enum class RowPos { SINGLE, FIRST, MIDDLE, LAST }

fun rowPos(index: Int, size: Int): RowPos = when {
    size <= 1 -> RowPos.SINGLE
    index == 0 -> RowPos.FIRST
    index == size - 1 -> RowPos.LAST
    else -> RowPos.MIDDLE
}

fun rowShape(pos: RowPos): Shape {
    val r = 16.dp
    return when (pos) {
        RowPos.SINGLE -> RoundedCornerShape(r)
        RowPos.FIRST -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPos.LAST -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        RowPos.MIDDLE -> RoundedCornerShape(0.dp)
    }
}

/** How the bold line reads: a normal task, "Call back Ali" with no task written, or a finished one. */
enum class TitleStyle { NORMAL, NO_TASK, DONE }

@Composable
private fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun chipColors(tone: ChipTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        ChipTone.BLUE -> cs.primaryContainer to cs.onPrimaryContainer
        ChipTone.MUTED -> cs.surfaceVariant to cs.onSurfaceVariant
        ChipTone.RED -> cs.errorContainer to cs.onErrorContainer
        ChipTone.GREEN -> cs.secondaryContainer to cs.onSecondaryContainer
        ChipTone.ORANGE -> if (isDark()) Color(0xFF4A3510) to Color(0xFFFFD58A) else Color(0xFFFFF1D6) to Color(0xFF7A4B00)
    }
}

@Composable
fun SmallChip(c: RowChip) {
    val (bg, fg) = chipColors(c.tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .then(if (c.onClick != null) Modifier.clickable(onClick = c.onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (c.icon != null) {
            Icon(c.icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(c.text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The round one-tap button on the right of a row. [size] 44.dp is the bigger Follow-ups button; [finished] greys
 * it out with a ✓ badge (that part of the follow-up is done) but it still works.
 */
@Composable
fun RoundAction(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    finished: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        !enabled || finished -> cs.surfaceVariant
        else -> container
    }
    val fg = if (!enabled || finished) cs.onSurfaceVariant else content
    Box(Modifier.size(size + if (finished) 4.dp else 0.dp)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(size / 2))
        }
        if (finished) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(cs.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(cs.outline),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Finished", tint = cs.surface, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** The green WhatsApp round button (same size as [RoundAction]). */
@Composable
fun WhatsAppAction(size: Dp = 36.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) WhatsAppGreen else cs.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("WA", color = if (enabled) WhatsAppInk else cs.onSurfaceVariant, fontWeight = FontWeight.Black, fontSize = (size.value * 0.32f).sp)
    }
}

val WhatsAppGreen = Color(0xFF25D366)
val WhatsAppInk = Color(0xFF053D1E)

/**
 * One task row. [timeTop]/[timeSub] fill the time column; [person]/[personDetail] the line under the task
 * (both null hides it, as on the person page); [action] is the round button, swapped for a close arrow while
 * the row is open and [expandedContent] shows. [bottomActions] puts small buttons on the chips line instead,
 * so a row with several buttons stays as short as its text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    pos: RowPos,
    timeTop: String,
    timeSub: String?,
    timeRed: Boolean,
    title: String,
    titleStyle: TitleStyle,
    person: String?,
    personDetail: String?,
    chips: List<RowChip>,
    expanded: Boolean,
    onClick: () -> Unit,
    quote: String? = null,
    highlighted: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    bottomActions: (@Composable () -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = rowShape(pos)
    val bg = if (expanded) cs.primary.copy(alpha = if (isDark()) 0.10f else 0.04f).compositeOn(cs.surface) else cs.surface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (highlighted) Modifier.border(BorderStroke(2.dp, cs.primary), shape) else Modifier)
    ) {
        if (pos == RowPos.MIDDLE || pos == RowPos.LAST) {
            HorizontalDivider(color = cs.surfaceVariant, thickness = 1.dp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .animateContentSize(tween(180))
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.width(56.dp)) {
                Text(
                    timeTop,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (timeRed) cs.error else cs.onSurface,
                )
                if (!timeSub.isNullOrBlank()) {
                    Text(timeSub, fontSize = 11.sp, lineHeight = 13.sp, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (titleStyle == TitleStyle.NORMAL) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (titleStyle == TitleStyle.NORMAL) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (person != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(text = person, size = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            person,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!personDetail.isNullOrBlank()) {
                            Text(
                                " · $personDetail",
                                fontSize = 13.sp,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (bottomActions != null) {
                    // Small round buttons at the bottom right, on the chips line; chips wrap in the space left.
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        FlowRow(
                            Modifier.weight(1f).padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chips.forEach { SmallChip(it) }
                        }
                        Spacer(Modifier.width(6.dp))
                        bottomActions()
                    }
                } else if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        chips.forEach { SmallChip(it) }
                    }
                }
                if (!quote.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = cs.surfaceVariant) {
                        Text(
                            quote,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
                if (expanded && expandedContent != null) {
                    Spacer(Modifier.height(10.dp))
                    expandedContent()
                }
            }
            if (expanded && expandedContent != null) {
                Spacer(Modifier.width(8.dp))
                RoundAction(
                    Icons.Rounded.ExpandLess, "Close", cs.surfaceVariant, cs.onSurfaceVariant, onClick = onClick
                )
            } else if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

private fun Color.compositeOn(base: Color): Color {
    val a = alpha
    return Color(
        red = red * a + base.red * (1 - a),
        green = green * a + base.green * (1 - a),
        blue = blue * a + base.blue * (1 - a),
        alpha = 1f
    )
}

enum class ActionStyle { FILLED, GREEN, OUTLINED, ORANGE }

data class RowAction(val label: String, val icon: ImageVector?, val style: ActionStyle, val onClick: () -> Unit)

data class RowLink(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** The buttons of an open row, two per line, then a line of small text links. */
@Composable
fun RowActions(actions: List<RowAction>, links: List<RowLink>) {
    val cs = MaterialTheme.colorScheme
    actions.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { a ->
                val mod = Modifier.weight(1f).height(36.dp)
                val pad = PaddingValues(horizontal = 6.dp)
                val inner: @Composable () -> Unit = {
                    if (a.icon != null) {
                        Icon(a.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(a.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                when (a.style) {
                    ActionStyle.FILLED -> Button(onClick = a.onClick, modifier = mod, contentPadding = pad) { inner() }
                    ActionStyle.GREEN -> Button(
                        onClick = a.onClick, modifier = mod, contentPadding = pad,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary)
                    ) { inner() }
                    ActionStyle.ORANGE -> {
                        val (bg, fg) = chipColors(ChipTone.ORANGE)
                        Button(
                            onClick = a.onClick, modifier = mod, contentPadding = pad,
                            colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)
                        ) { inner() }
                    }
                    ActionStyle.OUTLINED -> OutlinedButton(onClick = a.onClick, modifier = mod, contentPadding = pad) { inner() }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (links.isNotEmpty()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            links.forEach { l ->
                Text(
                    l.label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (l.danger) cs.error else cs.primary,
                    modifier = Modifier.clickable(onClick = l.onClick).padding(vertical = 4.dp)
                )
            }
        }
    }
}

/** "OVERDUE · 1", "TODAY · 3", etc. above a day block. */
@Composable
fun DayHeader(title: String, count: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(Locale.getDefault()),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Text(count, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

object RowFmt {
    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** "Today", "Tomorrow", "Yesterday" or "Wed 15 Oct". */
    fun dayTitle(day: LocalDate): String {
        val today = LocalDate.now(ZoneId.systemDefault())
        return when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(shortDay)
        }
    }

    /** Under the time: "in 1 h" for later today, the day for an older overdue one, else nothing. */
    fun timeSub(ms: Long, now: Long): String? {
        val day = dayOf(ms)
        val today = LocalDate.now(ZoneId.systemDefault())
        return when {
            ms > now && day == today -> Fmt.relative(ms, now).replaceFirstChar { it.lowercase() }
            day != today && ms <= now -> dayTitle(day)
            else -> null
        }
    }

    /** "40 min late". */
    fun late(ms: Long, now: Long): String {
        val r = Fmt.relative(ms, now)
        return if (r.startsWith("Overdue by ")) r.removePrefix("Overdue by ") + " late" else "Due now"
    }

    /** Under "Done": the time for today, else the day. */
    fun doneSub(ms: Long): String {
        val day = dayOf(ms)
        return if (day == LocalDate.now(ZoneId.systemDefault())) Fmt.timeOnly(ms) else dayTitle(day)
    }
}

/** Groups a list by day, keeping its order. */
fun <T> byDay(list: List<T>, at: (T) -> Long): List<Pair<LocalDate, List<T>>> =
    list.groupBy { RowFmt.dayOf(at(it)) }.toList()

/**
 * Swipe right for Done, left for Snooze. The row springs back and the list updates on its own.
 * Icon only, Gmail style: a pale strip with a small icon while dragging, then full colour, a bigger
 * icon and a light buzz at the lock point (40% of the row). Letting go before that does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActions(
    enabled: Boolean,
    shape: Shape,
    doneLabel: String = "Done",
    snoozeLabel: String = "Snooze",
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val haptics = LocalHapticFeedback.current
    val done by rememberUpdatedState(onDone)
    val snooze by rememberUpdatedState(onSnooze)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> done()
                SwipeToDismissBoxValue.EndToStart -> snooze()
                SwipeToDismissBoxValue.Settled -> {}
            }
            false
        },
        positionalThreshold = { total -> total * 0.4f }
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.clip(shape),
        backgroundContent = {
            val toDone = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toSnooze = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val locked = state.targetValue != SwipeToDismissBoxValue.Settled
            val green = MaterialTheme.colorScheme.secondary
            val (bgTarget, fgTarget) = when {
                toDone && locked -> green to MaterialTheme.colorScheme.onSecondary
                toDone -> green.copy(alpha = 0.18f) to green
                toSnooze && locked -> Color(0xFFF0B850) to Color(0xFF4A2F00)
                toSnooze -> Color(0xFFFBEBC9) to Color(0xFF5A3D00)
                else -> Color.Transparent to Color.Transparent
            }
            val bg by animateColorAsState(bgTarget, tween(120), label = "swipeBg")
            val fg by animateColorAsState(fgTarget, tween(120), label = "swipeFg")
            val iconSize by animateDpAsState(if (locked) 28.dp else 20.dp, tween(120), label = "swipeIcon")
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 22.dp),
                contentAlignment = if (toDone) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                if (toDone || toSnooze) {
                    Icon(
                        if (toDone) Icons.Rounded.Check else Icons.Rounded.Snooze,
                        contentDescription = if (toDone) doneLabel else snoozeLabel,
                        tint = fg,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    ) {
        content()
    }
}

/** The time picker in a sheet: one tap on a time sets it and closes. [plusRow] adds +15m to +2h (Snooze). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(
    heading: String,
    about: String,
    onPick: (Long) -> Unit,
    onPickTime: () -> Unit,
    onDismiss: () -> Unit,
    plusRow: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp + navBarMissing())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("One tap sets it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                about,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            footer?.invoke()
            ShortTimeRow(selected = null, plusRow = plusRow, onPick = onPick)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onPickTime) { Text("📅 Custom date & time") }
        }
    }
}

/** "What is this call about?" for a call reminder with no task written. */
@Composable
fun AddTaskDialog(r: Reminder, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val cancel = rememberDiscardGuard(changed = { text.isNotBlank() }, onClose = onDismiss)
    AlertDialog(
        onDismissRequest = cancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text(r.title) },
        text = {
            Column {
                Text(
                    listOfNotNull(
                        r.number.takeIf { it.isNotBlank() && r.name.isNotBlank() },
                        Fmt.dueLabel(if (r.done) r.doneAt else r.dueAt)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What is this call about?") },
                    trailingIcon = voiceMic(text) { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    ReminderStore.edit(r.id, r.name, r.number, text, r.dueAt, r.repeat)
                    onDismiss()
                }
            ) { Text("Save task") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

/** A black bar with UNDO for 5 seconds after Done, Snooze, Delete and the like. */
fun CoroutineScope.offerUndo(host: SnackbarHostState, message: String, undo: () -> Unit) {
    launch {
        host.currentSnackbarData?.dismiss()
        val closer = launch {
            kotlinx.coroutines.delay(5_000L)
            host.currentSnackbarData?.dismiss()
        }
        val res = host.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        closer.cancel()
        if (res == SnackbarResult.ActionPerformed) undo()
    }
}
