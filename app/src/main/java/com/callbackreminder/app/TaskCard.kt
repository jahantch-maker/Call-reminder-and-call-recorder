package com.callbackreminder.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonAdd
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
    /** False in the review session, where the ✓ Done button does it. */
    showTick: Boolean = true,
    /** WhatsApp-style ticks pinned top-right over the card (team tasks you gave): the card never grows or moves for them. */
    corner: (@Composable () -> Unit)? = null,
    /** The thin coloured line across the top (new messages, a question, stuck, a deadline request). */
    banner: CardBanner? = null,
    /** 🔴 Urgent: the whole card in a deep red tint (light pink in day mode) until it is done. */
    urgent: Boolean = false,
    /** False hides "⏱ No time yet" (a task given to you shows its promise chip instead). */
    noTimeChip: Boolean = true,
    /** Plot files: the sand card tint (a plot step). */
    tint: Color? = null,
    /** Plot files: the opened part starts at the card's left edge (the checklist), not under the text. */
    panelFromEdge: Boolean = false,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val base = tint ?: if (urgent && !done) Tones.urgentCard else cs.surface
    val bg by animateColorAsState(
        if (expanded) cs.primary.copy(alpha = if (LocalDarkTheme.current) 0.10f else 0.05f).compositeOver(base)
        else base,
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
        CardBannerLine(banner, onClick)
        Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showTick) {
                TickCircle(done = done, enabled = tickEnabled, onClick = onTick)
                Spacer(Modifier.width(12.dp))
            }
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
                Row(verticalAlignment = Alignment.Bottom) {
                    FlowRow(
                        Modifier.weight(1f).padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (time != null || noTimeChip) TimeBox(time, late && !done, timeIcons)
                        if (!person.isNullOrBlank()) {
                            TaskChip(person, CHIP_PLAIN, icon = { Icon(Icons.Rounded.Person, null, Modifier.size(14.dp).padding(end = 2.dp)) })
                        }
                        chips.forEach { SmallChip(it) }
                    }
                }
            }
            // Opened, 📞 and WA move into the bar under the details.
            AnimatedVisibility(
                visible = (onCall != null || onWhatsApp != null) && !(expanded && expandedContent != null),
                enter = expandHorizontally(tween(200)) + fadeIn(tween(200)),
                exit = shrinkHorizontally(tween(180)) + fadeOut(tween(140)),
            ) {
                Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (corner != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 8.dp)) { corner() }
        }
        AnimatedVisibility(
            visible = expanded && expandedContent != null,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = if (showTick && !panelFromEdge) 52.dp else 12.dp, end = 12.dp, bottom = 10.dp)) {
                val acts = CardActions(done, if (tickEnabled && !done) onTick else null, onCall, onWhatsApp)
                CompositionLocalProvider(LocalCardActions provides acts) { expandedContent?.invoke(this) }
            }
        }
    }
}

/**
 * The thin line on top of a card: what waits on it, in its colour. v0.44: tapping it does what tapping the card does
 * (opens it in place); it never calls or jumps away. [onClick] and [action] are kept for older callers but unused.
 */
data class CardBanner(val text: String, val tone: ChipTone, val onClick: (() -> Unit)? = null, val action: String? = null)

@Composable
private fun CardBannerLine(banner: CardBanner?, onCardClick: () -> Unit) {
    // Kept while it slides away, so it doesn't go blank first.
    var last by remember { androidx.compose.runtime.mutableStateOf(banner) }
    if (banner != null) last = banner
    AnimatedVisibility(
        visible = banner != null,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
    ) {
        val b = last ?: return@AnimatedVisibility
        val (bbg, bfg) = chipColors(b.tone)
        Row(
            Modifier
                .fillMaxWidth()
                .background(bbg)
                .clickable(onClick = onCardClick)
                .padding(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(b.text, color = bfg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
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

/** The opened card's own Done (its tick), 📞 and WA, for the bar in its panel. */
class CardActions(val done: Boolean, val onDone: (() -> Unit)?, val onCall: (() -> Unit)?, val onWhatsApp: (() -> Unit)?)

val LocalCardActions = compositionLocalOf<CardActions?> { null }

/**
 * What an opened card shows: a few detail lines, the same bar as the review session (✓ Done · 🕘 New time, or
 * [buttonLabel] like "Set time" · 📞 Call · WA) and small links. A finished card keeps only its links.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardPanel(
    details: List<String>,
    links: List<RowLink>,
    onNewTime: (() -> Unit)?,
    buttonLabel: String = "New time",
    /** A Done from your team waiting for your check: ✓ Received · ↩ Not done in the first two places. */
    onNotDone: (() -> Unit)? = null,
    /** …and ✓ Done, ➜ next part in the third. */
    onNextPart: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    details.forEach {
        Text(it, fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
    }
    val acts = LocalCardActions.current
    if (acts != null && !acts.done && (onNewTime != null || acts.onDone != null || onNotDone != null)) {
        Spacer(Modifier.height(6.dp))
        TaskActionBar(
            onDone = acts.onDone,
            onNewTime = onNewTime,
            onCall = acts.onCall,
            onWhatsApp = acts.onWhatsApp,
            newTimeLabel = buttonLabel,
            compact = true,
            onNotDone = onNotDone,
            onNextPart = onNextPart,
        )
    } else if (onNewTime != null) {
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

/**
 * The one action bar, the same in the review session and in every opened card: ✓ Done · 🕘 New time · 📞 Call ·
 * WA, always in these 4 places. A missing action is greyed in its place, so the others never move.
 * [onAddPerson] puts one "Add person" where 📞 and WA go (a follow-up with no person yet).
 */
@Composable
fun TaskActionBar(
    onDone: (() -> Unit)?,
    onNewTime: (() -> Unit)?,
    onCall: (() -> Unit)?,
    onWhatsApp: (() -> Unit)?,
    newTimeLabel: String = "New time",
    onAddPerson: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /** His Done waits for your check: Done reads "Received" and New time becomes ↩ Not done. */
    onNotDone: (() -> Unit)? = null,
    /** v0.44 with [onNotDone]: a third check button, ✓ Done, ➜ next part. */
    onNextPart: (() -> Unit)? = null,
) {
    val green = Tones.done.pair
    val orange = chipColors(ChipTone.ORANGE)
    val blue = Tones.call.pair
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BarSlot(Icons.Rounded.Check, if (onNotDone != null) "Received" else "Done", green, onDone, compact, Modifier.weight(1f))
        if (onNotDone != null) BarSlot(Icons.AutoMirrored.Rounded.Undo, "Not done", Tones.late.pair, onNotDone, compact, Modifier.weight(1f))
        else BarSlot(Icons.Rounded.Schedule, newTimeLabel, orange, onNewTime, compact, Modifier.weight(1f))
        if (onNotDone != null && onNextPart != null) BarSlot(null, NEXT_PART_LABEL, Tones.call.pair, onNextPart, compact, Modifier.weight(1f), lines = 2)
        if (onAddPerson != null) {
            BarSlot(Icons.Rounded.PersonAdd, "Add person", blue, onAddPerson, compact, Modifier.weight(2f))
        } else {
            BarSlot(Icons.Rounded.Call, "Call", blue, onCall, compact, Modifier.weight(1f))
            BarSlot(null, "WhatsApp", WhatsAppGreen to WhatsAppInk, onWhatsApp, compact, Modifier.weight(1f))
        }
    }
}

/** One place in [TaskActionBar]: icon over label ([icon] null draws "WA"); greyed when [onClick] is null. */
@Composable
internal fun BarSlot(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    tone: Pair<Color, Color>,
    onClick: (() -> Unit)?,
    compact: Boolean,
    modifier: Modifier,
    /** 2: the label on two short lines with no icon ("✓ Done, / ➜ next part"). */
    lines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val on = onClick != null
    val bg by animateColorAsState(if (on) tone.first else cs.surfaceVariant, tween(200), label = "barBg")
    val fg by animateColorAsState(if (on) tone.second else cs.onSurfaceVariant.copy(alpha = 0.45f), tween(200), label = "barFg")
    Surface(
        onClick = { onClick?.invoke() },
        enabled = on,
        shape = RoundedCornerShape(if (compact) 12.dp else 14.dp),
        color = bg,
        contentColor = fg,
        modifier = modifier.height(if (compact) 44.dp else 54.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (lines > 1) {
                FitText(label, if (compact) 10.5.sp else 12.sp, Modifier.padding(horizontal = 2.dp), color = fg, fontWeight = FontWeight.Bold,
                    maxLines = lines, lineHeight = if (compact) 12.sp else 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, minScale = 0.62f)
                return@Column
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 17.dp else 20.dp))
            else Text("WA", color = fg, fontWeight = FontWeight.Black, fontSize = if (compact) 12.sp else 14.sp, lineHeight = 17.sp)
            FitText(label, if (compact) 10.5.sp else 12.sp, Modifier.padding(horizontal = 2.dp), color = fg, fontWeight = FontWeight.Bold, minScale = 0.62f)
        }
    }
}

/**
 * Text that never hides part of itself: on a narrow screen or a big display size it first shrinks a little (down to
 * [minScale]) so it fits in [maxLines] without "…" and without breaking a word in half; only then would it cut.
 */
@Composable
fun FitText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    minScale: Float = 0.72f,
) {
    var scale by remember(text, fontSize, maxLines) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var ready by remember(text, fontSize, maxLines) { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        text,
        modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        fontSize = fontSize * scale,
        fontWeight = fontWeight,
        lineHeight = if (lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight else lineHeight * scale,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            val brokenWord = (0 until r.lineCount - 1).any { i ->
                val end = r.getLineEnd(i)
                end in 1 until text.length && (text[end].isLetterOrDigit() && text[end - 1].isLetterOrDigit() || text[end] in ",.:;!?")
            }
            val cut = r.hasVisualOverflow || (0 until r.lineCount).any { r.isLineEllipsized(it) }
            if ((cut || brokenWord) && scale > minScale) scale = maxOf(minScale, scale - 0.06f) else ready = true
        },
    )
}

/** The box with two or three options (Tasks | Dossier, Open | Done, Follow-ups | My tasks…), used for every top choice. */
@Composable
fun BoxTabs(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    /** Many tabs (Needs you): smaller text on two lines ("Overdue\n6"), so none is cut at any display size. */
    compact: Boolean = false,
) {
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
                    modifier = Modifier.weight(1f).height(if (compact) 44.dp else 40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (compact) Text(label, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 2,
                            fontSize = 12.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
                        else Text(label, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1)
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
