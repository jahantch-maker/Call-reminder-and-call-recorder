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
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp)
                    )
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
fun TextPlanSection(state: TextPlanState, task: String, due: Long?) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val templates = remember { BurstTemplates.load(ctx) }
    var addTime by remember { mutableStateOf(false) }
    var repeatPick by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    val message = state.messageFor(task)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TEXT TIMES", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.times.sorted().forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { state.times = state.times - t },
                    label = { Text(Fmt.dueLabel(t)) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                )
            }
            TextButton(onClick = { addTime = true }) { Text("＋ Add time") }
        }
        if (due == null && state.times.isNotEmpty()) {
            Text(
                "Texts wait until the follow-up has a time, then move with it.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }

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
        SwitchWithHelp(
            label = "Tracking number",
            checked = state.tracked,
            onCheckedChange = { state.tracked = it },
            onHelp = { help = "Tracking number" to TRACKING_HELP }
        )
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

    if (addTime) {
        val zone = ZoneId.systemDefault()
        val suggest = state.times.maxOrNull()?.plus(3_600_000L)
            ?: due?.let { FollowUps.firstTextTime(it, System.currentTimeMillis()) }
            ?: (System.currentTimeMillis() + 3_600_000L)
        DateTimePickerFlow(
            initialMs = suggest,
            onPicked = { ms ->
                // Whole minutes, no copies.
                val m = Instant.ofEpochMilli(ms).atZone(zone).withSecond(0).withNano(0).toInstant().toEpochMilli()
                if (m !in state.times) state.times = state.times + m
                addTime = false
            },
            onDismiss = { addTime = false }
        )
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
 * due (call) time, then "Remind me to call" and "Text him" with its own times, repeat, tracking and burst. Every
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
                else -> QuickTimes.options(ctx).getOrNull(1)?.whenMs ?: (now + 3_600_000L)
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
    }

    val problem = when {
        taskRequired && !editing && item == null && task.isBlank() -> "Write the task first"
        textEff -> text.problem(task, needTimes = due != null)
        else -> null
    }
    val heading = when {
        editing && startWithText -> "Add texts to this follow-up"
        editing || item != null -> "Edit follow-up"
        else -> "New follow-up"
    }

    FormDialog(
        title = heading,
        onDismiss = cancel,
        confirmEnabled = problem == null,
        onConfirm = {
            val plan = if (textEff) text.plan(task) else null
            val pilot = autopilot && callEff && textEff
            val timed = if (editing) {
                FollowUps.update(ctx, linkId, name, number, task, due, callEff, pilot, plan, starred)
            } else {
                FollowUps.create(ctx, name, number, task, due, callEff, pilot, plan, starred, from = item)
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
        if (!hasPerson) {
            PersonPicker(prompt = "Who is this follow-up with? (optional)") { n, num -> name = n; number = num }
        } else {
            ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
        }
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

        Text("DUE · CALL TIME", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimePickerPanel(selected = due) { setDue(it) }
        OutlinedButton(onClick = { showDue = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(due?.let { Fmt.dueLabel(it) } ?: "No due time · Set time", fontWeight = FontWeight.SemiBold)
        }
        if (due != null) {
            RemoveTimeButton { setDue(null) }
        } else {
            Text(
                "No due time: it is saved to To schedule, and you set the time later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SwitchCard(
            icon = { Icon(Icons.Rounded.Call, contentDescription = null) },
            title = "Remind me to call",
            subtitle = when {
                !hasPerson -> "Pick a person first"
                due != null -> "Rings at the due time · ${Fmt.timeOnly(due!!)}"
                else -> "Rings once you set a time"
            },
            checked = callEff,
            enabled = hasPerson,
            onChange = { callOn = it },
        )
        SwitchCard(
            icon = { Icon(Icons.Rounded.Sms, contentDescription = null) },
            title = "Text ${name.ifBlank { "him" }.substringBefore(' ')}",
            subtitle = if (!hasPerson) "Pick a person first" else "Reminder texts from your SIM, at their own times",
            checked = textEff,
            enabled = hasPerson,
            onChange = { on ->
                textOn = on
                if (on && text.times.isEmpty()) {
                    due?.let { d -> FollowUps.firstTextTime(d, System.currentTimeMillis())?.let { text.times = listOf(it) } }
                }
            },
        )
        if (textEff) {
            Box(Modifier.padding(start = 12.dp)) { TextPlanSection(text, task, due) }
        }
        if (callEff && textEff) {
            SwitchCard(
                icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                title = "Autopilot ⓘ",
                subtitle = if (autopilot) "His task number reply cancels my call reminder" else "Off: my call still rings after his reply",
                checked = autopilot,
                enabled = text.tracked,
                onChange = { autopilot = it },
                onTitleClick = { showAutoHelp = true },
            )
        }
        StarSwitchRow(starred) { starred = it }
        if (onMoveToMyTasks != null) {
            ArrowCard(
                icon = { Icon(Icons.Rounded.CheckBox, contentDescription = null) },
                title = "Move to My tasks",
                subtitle = "The next step is yours",
                onClick = { onDismiss(); onMoveToMyTasks() },
            )
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
            title = { Text("Autopilot") },
            text = {
                Text(
                    "On: when he texts back the task number, your call reminder is cancelled and the " +
                        "task waits in To review.\n\nOff: his reply still moves the texts to To review, but your call " +
                        "reminder rings anyway so you can confirm by phone.\n\nNeeds Tracking number on, and " +
                        "Settings › Text messages › Stop on a task number reply."
                )
            },
            confirmButton = { TextButton(onClick = { showAutoHelp = false }) { Text("OK") } }
        )
    }
}
