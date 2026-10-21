package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.draw.clip

/**
 * A tall edit window whose Cancel and Save stay fixed at the bottom, above the phone's Home/Back buttons,
 * while the form scrolls. (Android 15 lets windows draw under those buttons, so the window keeps clear of
 * them itself.)
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "Save",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    dismissLabel: String = "Cancel",
    titleAction: (@Composable () -> Unit)? = null,
    summary: (@Composable () -> Unit)? = null,
    /** Small buttons at the left of the Cancel / Save bar (History, WhatsApp), so the window stays the same size. */
    bottomStart: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .safeBottomPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 6.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                        )
                        titleAction?.invoke()
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                    if (summary != null) {
                        Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { summary() }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (bottomStart != null) {
                            bottomStart()
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = onDismiss) { Text(dismissLabel) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

private val NoTimeOrange = Color(0xFFF0B850)

/** The big Google Tasks style "⊘ Remove time (set it later)" button. */
@Composable
fun RemoveTimeButton(label: String = "Remove time (set it later)", onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, NoTimeOrange),
    ) {
        Icon(Icons.Rounded.Block, contentDescription = null, tint = NoTimeOrange, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = NoTimeOrange, fontWeight = FontWeight.Bold)
    }
}

/** What the text part of a follow-up is set to while it is being edited. */
@Stable
class TextPlanState(
    times: List<Long>,
    message: String,
    tracked: Boolean,
    burst: BurstTemplate?,
    repeat: Repeat?,
    fallbackBurst: BurstTemplate?,
) {
    var times by mutableStateOf(times)
    var message by mutableStateOf(message)
    var messageTouched by mutableStateOf(message.isNotBlank())
    var tracked by mutableStateOf(tracked)
    var burstOn by mutableStateOf(burst != null)
    var burst by mutableStateOf(burst ?: fallbackBurst)
    var repeat by mutableStateOf(repeat)
    /** Remove time was tapped (or it waits in To schedule): nothing is timed until a new time is picked. */
    var parked by mutableStateOf(false)

    fun messageFor(task: String): String = if (messageTouched) message else task

    fun plan(task: String) = FollowUps.TextPlan(
        times, messageFor(task).trim(), tracked, burst?.takeIf { burstOn && it.count > 1 }, repeat
    )

    fun snapshot(): List<Any?> = listOf(times, message, tracked, burstOn, burst, repeat)

    /** What is missing before these texts can be saved, or null. */
    fun problem(task: String, needTimes: Boolean): String? = when {
        needTimes && times.isEmpty() -> "Add a time for the texts"
        messageFor(task).isBlank() -> "Write the text message"
        burstOn && burst == null -> "Add a burst template in Settings › Text messages"
        else -> null
    }
}

@Composable
fun rememberTextPlanState(from: SmsReminder? = null, item: ToScheduleItem? = null, times: List<Long> = emptyList()): TextPlanState {
    val ctx = LocalContext.current
    return remember {
        val templates = BurstTemplates.load(ctx)
        when {
            from != null -> TextPlanState(from.baseTimes, from.message, from.tracked, from.eachBurst, from.repeat, templates.firstOrNull())
            item != null && item.textOn -> TextPlanState(item.textTimes, item.message, item.tracked, item.burst, item.repeat, templates.firstOrNull())
            else -> TextPlanState(times, "", true, null, null, templates.firstOrNull())
        }
    }
}

/**
 * The texts part of a follow-up, the same everywhere texts are set: TEXT TIMES with ＋ Add time, Repeat,
 * the message, Tracking number and Burst (sent at each text time).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextPlanSection(
    state: TextPlanState,
    task: String,
    due: Long?,
    latest: Long? = null,
    /** The follow-up editor: Burst, Reply code, Repeat and [extra] fold under "Customise ▾". */
    folded: Boolean = false,
    firstName: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val templates = remember { BurstTemplates.load(ctx) }
    var repeatPick by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    val message = state.messageFor(task)
    val repeatBlock: @Composable () -> Unit = {
        // Repeat: daily / weekly for the whole set of texts, picked like a text's date & time.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { repeatPick = true }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Repeat, contentDescription = null, tint = cs.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Repeat", fontWeight = FontWeight.SemiBold)
                    val first = state.times.minOrNull() ?: due ?: System.currentTimeMillis()
                    Text(
                        state.repeat?.label(Repeat.at(first)) ?: "Off · tap to repeat daily / weekly",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.repeat != null) {
                    IconButton(onClick = { state.repeat = null }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Stop repeating", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

    }
    val trackBlock: @Composable () -> Unit = {
        SwitchWithHelp(
            label = if (folded) "Reply code" else "Tracking number",
            checked = state.tracked,
            onCheckedChange = { state.tracked = it },
            onHelp = { help = "Tracking number" to TRACKING_HELP }
        )
    }
    val burstBlock: @Composable () -> Unit = {
        SwitchWithHelp(
            label = "Burst",
            checked = state.burstOn,
            onCheckedChange = { state.burstOn = it },
            onHelp = { help = "Burst" to "$BURST_HELP The burst is sent at each text time." }
        )
        if (state.burstOn) {
            val b = state.burst
            if (b == null || templates.isEmpty()) {
                Text(
                    "No burst templates yet. Add them in Settings › Text messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error
                )
            } else {
                Box {
                    OutlinedButton(
                        onClick = { templateMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(b.label, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose template")
                    }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        templates.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { state.burst = t; templateMenu = false })
                        }
                    }
                }
                Text(
                    "Sent at each text time" + if (state.times.size > 1) " (${state.times.size} times × ${b.count} texts)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TEXT TIMES", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.times.sorted().forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { state.times = state.times - t },
                    label = { Text("🕒 " + Fmt.dueLabel(t)) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                )
            }
        }
        ShortTimeRow(
            selected = null,
            showSelected = false,
            pickLabel = "＋ Add time",
            dialogTitle = "Add text time",
            latest = latest,
            latestNote = latest?.let { "Times after the call (${Fmt.dueLabel(it)}) are greyed" },
            onPick = { ms ->
                // Whole minutes, no copies.
                val m = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).withSecond(0).withNano(0).toInstant().toEpochMilli()
                if (m !in state.times) state.times = state.times + m
                state.parked = false
            },
        )
        if (due == null && state.times.isNotEmpty()) {
            Text(
                "Texts wait until the follow-up has a time, then move with it.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = message,
            onValueChange = { state.message = it; state.messageTouched = true },
            trailingIcon = voiceMic(message) { state.message = it; state.messageTouched = true },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            supportingText = { due?.let { Text("The app adds “(Due: ${Fmt.fullDate(it)})” and updates it on every New time.") } },
            maxLines = 4,
            shape = RoundedCornerShape(14.dp)
        )
        if (!folded) {
            repeatBlock()
            trackBlock()
            burstBlock()
        } else {
            var open by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (open) "Customise ▴" else "Customise ▾", color = cs.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                if (!open) {
                    Text(
                        listOfNotNull(
                            "Burst on".takeIf { state.burstOn },
                            "Reply code".takeIf { state.tracked },
                            if (state.repeat != null) "Repeats" else "Once",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = open,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    burstBlock()
                    trackBlock()
                    repeatBlock()
                    extra?.invoke()
                }
            }
        }
    }

    if (repeatPick) {
        val first = state.times.minOrNull() ?: due?.let { FollowUps.firstTextTime(it, System.currentTimeMillis()) }
            ?: (System.currentTimeMillis() + 3_600_000L)
        DateTimeDialog(
            initialMs = first,
            initialRepeat = state.repeat,
            allowRepeat = true,
            onDone = { ms, r ->
                state.repeat = r
                // The picked time becomes the first text time.
                val old = state.times.minOrNull()
                state.times = ((if (old != null) state.times - old else state.times) + ms).distinct()
                repeatPick = false
            },
            onDismiss = { repeatPick = false }
        )
    }
    help?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { help = null }) { Text("OK") } }
        )
    }
}

/** A switch row in a soft card, for the follow-up window and the after-call popup. */
@Composable
internal fun SwitchCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onTitleClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (checked) cs.primaryContainer.copy(alpha = 0.6f) else cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

/** A tappable card with an arrow, like "Move to My tasks · The next step is yours". */
@Composable
internal fun ArrowCard(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * New follow-up, or Edit (with [linkId], or [item] for one waiting in To schedule): the person, the task, the
 * due (call) time, then "Remind me to call" and "Text <first name>" with its own times, repeat, tracking and burst. Every
 * follow-up can switch either part on or off. Remove time sends it to To schedule; only the task is needed to
 * save (a quick save goes to To schedule as "Needs details"). [fromMyTask] is a My task being moved here: it
 * leaves My tasks on Save. [onMoveToMyTasks] adds "Move to My tasks" when editing.
 */
@Composable
fun FollowUpDialog(
    onDismiss: () -> Unit,
    linkId: Long = 0L,
    item: ToScheduleItem? = null,
    initialName: String = "",
    initialNumber: String = "",
    initialTask: String = "",
    initialDue: Long? = -1L,
    taskRequired: Boolean = true,
    startWithText: Boolean = false,
    fromMyTask: MyTask? = null,
    onMoveToMyTasks: (() -> Unit)? = null,
    /** Opened in place (a person page, Home, the review session): "Show in Follow-ups ›", History and WA. */
    extras: TaskExtras? = null,
) {
    val ctx = LocalContext.current
    val editCall = remember { FollowUps.call(linkId) }
    val editText = remember { FollowUps.text(linkId) }
    val editing = linkId != 0L
    val now = remember { System.currentTimeMillis() }
    val pendingCall = editCall?.takeIf { !it.done }
    val pendingText = editText?.takeIf { !it.done }

    var name by remember { mutableStateOf(item?.name ?: editCall?.name ?: editText?.name ?: fromMyTask?.name ?: initialName) }
    var number by remember { mutableStateOf(item?.number ?: editCall?.number ?: editText?.number ?: fromMyTask?.number ?: initialNumber) }
    var task by remember {
        mutableStateOf(
            item?.note ?: editCall?.note?.takeIf { it.isNotBlank() } ?: editText?.message ?: fromMyTask?.title ?: initialTask
        )
    }
    var due by remember {
        mutableStateOf<Long?>(
            when {
                item != null -> null
                editing -> pendingCall?.dueAt ?: editText?.taskDue?.takeIf { it > 0L } ?: pendingText?.dueAt
                fromMyTask != null -> fromMyTask.dueAt.takeIf { it > 0L }
                initialDue == null -> null
                initialDue > 0L -> initialDue
                // No time until there is a person: a quick save of just the task goes to To schedule.
                (item?.number ?: initialNumber).isBlank() -> null
                else -> defaultCallTime(ctx, now)
            }
        )
    }
    // Where the texts move from when a time is set again after Remove time.
    var anchor by remember { mutableLongStateOf(item?.oldDue ?: 0L) }
    var callOn by remember {
        mutableStateOf(
            when {
                item != null -> item.callOn
                editing -> pendingCall != null || (pendingText == null && editCall != null)
                else -> true
            }
        )
    }
    var textOn by remember { mutableStateOf(item?.textOn ?: ((editing && pendingText != null) || startWithText)) }
    val text = rememberTextPlanState(
        from = pendingText,
        item = item,
        times = if (startWithText) listOfNotNull(due?.let { FollowUps.firstTextTime(it, now) }) else emptyList(),
    )
    remember { if (item != null) text.parked = true }
    var autopilot by remember { mutableStateOf(item?.autopilot ?: editCall?.autopilot ?: false) }
    var starred by remember { mutableStateOf(item?.starred ?: editCall?.starred ?: fromMyTask?.starred ?: false) }
    var showDue by remember { mutableStateOf(false) }
    var showAutoHelp by remember { mutableStateOf(false) }

    val hasPerson = number.isNotBlank()
    val callEff = callOn && hasPerson
    val textEff = textOn && hasPerson

    fun snapshot() = listOf(name, number, task, due, callOn, textOn, autopilot, starred) + text.snapshot()
    val start = remember { snapshot() }
    val cancel = rememberDiscardGuard(changed = { start != snapshot() }, onClose = onDismiss)

    fun setDue(ms: Long?) {
        val old = due
        if (ms != null && textOn) {
            val from = old ?: anchor
            text.times = when {
                old == null && from > 0L && text.times.isNotEmpty() -> FollowUps.movedTimes(text.times, from, ms)
                text.times.isEmpty() -> listOfNotNull(FollowUps.firstTextTime(ms, System.currentTimeMillis()))
                else -> text.times
            }
        }
        if (old != null) anchor = old
        due = ms
        if (ms != null) text.parked = false
    }

    // The task's due time: the call time, or with the call off, the first text time.
    val saveDue: Long? = when {
        text.parked -> null
        callEff -> due
        textEff -> text.times.minOrNull()
        else -> due
    }
    val anyTime = !text.parked && ((callOn && due != null) || (textOn && text.times.isNotEmpty()))
    val problem = when {
        taskRequired && !editing && item == null && task.isBlank() -> "Write the task first"
        !hasPerson && anyTime -> "Pick a person or remove the time"
        textEff -> text.problem(task, needTimes = callEff && due != null)
        else -> null
    }
    val heading = when {
        editing && startWithText -> "Add texts to this follow-up"
        editing || item != null -> "Edit follow-up"
        else -> "New follow-up"
    }
    val first = Names.first(name)

    FormDialog(
        title = heading,
        onDismiss = cancel,
        confirmEnabled = problem == null,
        bottomStart = extras?.let { x -> { TaskExtraButtons(x) } },
        titleAction = {
            extras?.onShowInList?.let { go -> ShowInListLink(extras.listName) { onDismiss(); go() } }
            IconButton(onClick = { starred = !starred }) {
                Icon(
                    if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (starred) "Important" else "Mark important",
                    tint = if (starred) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        summary = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FollowUpSummary(callEff && !text.parked, due, textEff && !text.parked, text.takeIf { textEff }?.plan(task))
                if (textEff && !text.parked) {
                    // The exact SMS, as it goes out.
                    val plan = text.plan(task)
                    val code = editText?.code?.takeIf { it.isNotEmpty() } ?: "task no."
                    val body = plan.message + (saveDue?.let { " (Due: ${Fmt.fullDate(it)})" } ?: "")
                    val sms = when {
                        !plan.tracked -> body
                        plan.burst != null -> "Reminder 1 ($code): $body"
                        else -> "($code) $body"
                    }
                    if (plan.tracked) {
                        Text(
                            "✅ A reply of $code moves it to Review",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "${(first ?: "THEY").uppercase()} WILL READ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(sms, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                // Staff on a daily list get this task in it anyway.
                if (hasPerson && DailyList.isOn(ctx, RecentPeople.key(number))) {
                    Text(
                        "📋 ${first ?: "They"} also gets this in the daily list (${DailyList.timesLabel(ctx)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tones.amber.fg
                    )
                }
            }
        },
        onConfirm = {
            val plan = if (textEff) text.plan(task) else null
            val pilot = autopilot && callEff && textEff
            val timed = if (editing) {
                FollowUps.update(ctx, linkId, name, number, task, saveDue, callEff, pilot, plan, starred)
            } else {
                FollowUps.create(ctx, name, number, task, saveDue, callEff, pilot, plan, starred, from = item)
            }
            fromMyTask?.let { MyTaskStore.delete(it.id) }
            if (!timed) {
                val needs = !hasPerson || (!callEff && !textEff)
                Toast.makeText(ctx, if (needs) "Saved · Needs details, in To schedule" else "Saved to To schedule", Toast.LENGTH_SHORT).show()
            } else if (fromMyTask != null) {
                Toast.makeText(ctx, "Moved to Follow-ups", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        },
    ) {
        // A. Person
        if (!hasPerson) {
            PersonPicker(prompt = "Who is this follow-up with? (optional)") { n, num ->
                name = n
                number = num
                if (due == null && callOn && !editing && item == null && anchor == 0L) due = defaultCallTime(ctx, System.currentTimeMillis())
            }
        } else {
            ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
        }
        // B. Task
        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            trailingIcon = voiceMic(task) { task = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (taskRequired && !editing && item == null) "Task *" else "Task (optional)") },
            placeholder = { Text("e.g. Send the March invoice") },
            maxLines = 4,
            shape = RoundedCornerShape(14.dp)
        )

        // C. How should I follow up? Three presets instead of the call and text switches.
        val preset = when {
            textOn && callOn -> 1
            textOn -> 2
            else -> 0
        }
        Text("HOW SHOULD I FOLLOW UP?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxTabs(listOf("Just remind me", "Remind + text", "Text only"), preset, { p ->
            val wantCall = p != 2
            val wantText = p != 0
            callOn = wantCall
            if (wantCall && due == null && anchor == 0L && hasPerson) setDue(defaultCallTime(ctx, System.currentTimeMillis()))
            textOn = wantText
            if (wantText && text.times.isEmpty()) {
                val nowMs = System.currentTimeMillis()
                val t = if (wantCall && due != null) FollowUps.firstTextTime(due!!, nowMs) else FollowUps.firstTextTimeNoCall(nowMs)
                t?.let { text.times = listOf(it) }
            }
        })
        if (!hasPerson) {
            Text(
                "Pick a person to set a call or texts. Saved without one, it waits in To schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 📞 CALL <FIRST>: the call time, +15m to +2h, recent times and 📅 Pick.
        androidx.compose.animation.AnimatedVisibility(
            visible = callEff,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            PartCard(CallCardBg) {
                Text(
                    "📞 CALL ${first?.uppercase() ?: ""}".trim(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ShortTimeRow(
                    selected = due,
                    onClear = if (due != null) ({ setDue(null) }) else null,
                    dialogTitle = "Pick call time",
                    pickLabel = "📅 Pick",
                    plusRow = true,
                    onPick = { setDue(it) },
                )
                if (due == null) {
                    Text(
                        "No call time: it waits in To schedule until you set one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 💬 TEXT <FIRST>: text times and the message; Burst, Reply code, Repeat and Skip my call under Customise.
        androidx.compose.animation.AnimatedVisibility(
            visible = textEff,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            TextGreenTheme {
                PartCard(TextCardBg) {
                    Text(
                        "💬 TEXT ${first?.uppercase() ?: ""}".trim(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextPlanSection(
                        text, task, if (text.parked) null else if (callEff) due else text.times.minOrNull(),
                        latest = if (callEff) due else null,
                        folded = true,
                        firstName = first,
                        extra = if (callEff) ({
                            SwitchCard(
                                icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                                title = "Skip my call if ${first ?: "they"} replies ⓘ",
                                subtitle = if (autopilot) "On: a reply with the task number cancels my call"
                                else "Off: your call rings anyway",
                                checked = autopilot,
                                enabled = text.tracked,
                                onChange = { autopilot = it },
                                onTitleClick = { showAutoHelp = true },
                            )
                        }) else null,
                    )
                }
            }
        }
        if (anyTime) {
            RemoveTimeButton {
                setDue(null)
                text.parked = true
            }
        }
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.size(4.dp))
    }

    if (showDue) {
        DateTimePickerFlow(
            initialMs = due ?: (System.currentTimeMillis() + 3_600_000L),
            onPicked = { setDue(it); showDue = false },
            onDismiss = { showDue = false }
        )
    }
    if (showAutoHelp) {
        AlertDialog(
            onDismissRequest = { showAutoHelp = false },
            title = { Text("Skip my call if ${Names.first(name) ?: "they"} replies") },
            text = {
                Text(
                    "On: when ${Names.first(name) ?: "the person"} texts back the task number, your call reminder is cancelled and the " +
                        "task waits in To review.\n\nOff: the reply still moves the texts to To review, but your call " +
                        "reminder rings anyway so you can confirm by phone.\n\nNeeds Tracking number on, and " +
                        "Settings › Text messages › Stop on a task number reply."
                )
            },
            confirmButton = { TextButton(onClick = { showAutoHelp = false }) { Text("OK") } }
        )
    }
}

private val CallCardBg = Color(0xFF3D5AFE).copy(alpha = 0.16f)
private val TextCardBg = Color(0xFF14B88A).copy(alpha = 0.16f)

/** A new follow-up's call time to start with: the second quick time, else in an hour. */
fun defaultCallTime(ctx: android.content.Context, now: Long): Long =
    QuickTimes.options(ctx).getOrNull(1)?.whenMs ?: (now + 3_600_000L)

/** A switch card that shows its own settings below the switch while it is on (the call and text cards). */
@Composable
internal fun FeatureCard(
    tint: Color,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (checked) tint else cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
            }
            if (checked) {
                HorizontalDivider(color = cs.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

/** A soft tinted card holding the call or the text part of the editor. */
@Composable
internal fun PartCard(tint: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = tint, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** The plain summary above Save: "📞 Call reminder Today, 4:00 pm" / "💬 5 texts from Today, 9:00 am (15 min apart)". */
@Composable
internal fun FollowUpSummary(callOn: Boolean, due: Long?, textOn: Boolean, plan: FollowUps.TextPlan?) {
    val cs = MaterialTheme.colorScheme
    val lines = buildList {
        if (callOn && due != null) add("📞 " to ("Call reminder" to Fmt.dueLabel(due)))
        if (textOn && plan != null && plan.times.isNotEmpty()) {
            val n = plan.expanded().size
            val gap = plan.burst?.takeIf { it.count > 1 }?.let { " (${it.gapMin} min apart)" }.orEmpty()
            val rep = if (plan.repeat != null) " · repeats" else ""
            add("💬 " to ("$n ${if (n == 1) "text" else "texts"}" to "from ${Fmt.dueLabel(plan.sorted.first())}$gap$rep"))
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (lines.isEmpty()) {
                Text("🕘 No time yet: saved to To schedule", style = MaterialTheme.typography.bodyMedium)
            }
            lines.forEach { (emoji, rest) ->
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(emoji)
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(rest.first)
                        pop()
                        append(" ")
                        append(rest.second)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
