package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Which view the Follow-ups tab shows: everything together, or only calls or only texts. */
object FollowUpsUi {
    const val ALL = 0
    const val CALLS = 1
    const val TEXTS = 2

    /** Another screen asked for a view (e.g. Home's Calls tile). */
    val view = MutableStateFlow<Int?>(null)
}

/**
 * The Follow-ups tab: every call-back and text in one place. "All" shows each follow-up once (a call and
 * its texts together, with a 📞 and a 💬 button); "Calls" and "Texts" are the full lists with their own
 * To review and Done chips.
 */
@Composable
fun FollowUpsScreen(resumeKey: Int, onOpenTextSettings: () -> Unit, onOpenToSchedule: () -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(FollowUpsUi.ALL) }
    val asked by FollowUpsUi.view.collectAsState()
    LaunchedEffect(asked) {
        val v = asked ?: return@LaunchedEffect
        FollowUpsUi.view.value = null
        view = v
    }
    // A request for a call or a text (a notification, search, Home) opens the view that shows it.
    val callShow by CallsUi.showRequest.collectAsState()
    val callTab by CallsUi.tabRequest.collectAsState()
    val textShow by SmsUi.showRequest.collectAsState()
    val textTab by SmsUi.tabRequest.collectAsState()
    val textEdit by SmsUi.editRequest.collectAsState()
    LaunchedEffect(callShow, callTab) { if (callShow != null || callTab != null) view = FollowUpsUi.CALLS }
    LaunchedEffect(textShow, textTab, textEdit) { if (textShow != null || textTab != null || textEdit != null) view = FollowUpsUi.TEXTS }

    val switcher: @Composable () -> Unit = { FollowUpsSwitcher(view) { view = it } }
    when (view) {
        FollowUpsUi.CALLS -> CallsScreen(resumeKey = resumeKey, switcher = switcher)
        FollowUpsUi.TEXTS -> MessagesScreen(resumeKey = resumeKey, onBack = null, onOpenSettings = onOpenTextSettings, switcher = switcher)
        else -> FollowUpsAll(
            resumeKey = resumeKey,
            switcher = switcher,
            onOpenCall = { id ->
                CallsUi.showRequest.value = id
                view = FollowUpsUi.CALLS
            },
            onOpenText = { id ->
                SmsUi.showRequest.value = id
                view = FollowUpsUi.TEXTS
            },
            onOpenToSchedule = onOpenToSchedule,
        )
    }
}

/** All · Calls · Texts, under the Follow-ups title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpsSwitcher(view: Int, onView: (Int) -> Unit) {
    val labels = listOf("All", "Calls", "Texts")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = view == i,
                onClick = { onView(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
            ) { Text(label) }
        }
    }
}

/** One follow-up in the All view: a call, its texts, or both. */
private class FuEntry(val call: Reminder?, val text: SmsReminder?) {
    val key: String = when {
        call?.linkId?.let { it != 0L } == true -> "link-${call.linkId}"
        text?.linkId?.let { it != 0L } == true -> "link-${text.linkId}"
        call != null -> "call-${call.id}"
        else -> "text-${text!!.id}"
    }
    val callPending: Boolean get() = call != null && !call.done
    val textActive: Boolean get() = text != null && text.active
    val callReview: Boolean get() = call != null && call.done && call.review
    val textReview: Boolean get() = text != null && !text.done && text.review != null
    val inReview: Boolean get() = callReview || textReview

    /** When it sorts: the call's due time, else the next text. */
    val at: Long
        get() = when {
            callPending -> call!!.dueAt
            textActive -> text!!.alarmAt
            textReview -> text!!.reviewAt
            else -> call?.doneAt ?: 0L
        }
}

private fun entries(calls: List<Reminder>, texts: List<SmsReminder>, now: Long): List<FuEntry> {
    val out = mutableListOf<FuEntry>()
    val links = (calls.map { it.linkId } + texts.map { it.linkId }).filter { it != 0L }.toSet()
    links.forEach { link ->
        val c = calls.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done || it.review } ?: l.maxByOrNull { it.id } }
        val t = texts.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } }
        out += FuEntry(c, t)
    }
    calls.filter { it.linkId == 0L }.forEach { out += FuEntry(it, null) }
    texts.filter { it.linkId == 0L }.forEach { out += FuEntry(null, it) }
    // Open ones only (Done lives in the Calls and Texts views), and not the ones set to "Review later".
    return out.filter { e ->
        val open = e.callPending || e.textActive ||
            (e.callReview && e.call!!.reviewLaterUntil <= now) || (e.textReview && e.text!!.reviewLaterUntil <= now)
        open
    }
}

@Composable
private fun FollowUpsAll(
    resumeKey: Int,
    switcher: @Composable () -> Unit,
    onOpenCall: (Long) -> Unit,
    onOpenText: (Long) -> Unit,
    onOpenToSchedule: () -> Unit,
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var sendFor by remember { mutableStateOf<SmsReminder?>(null) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    val all = entries(calls, texts, now)
    val review = all.filter { it.inReview }.sortedByDescending { it.at }
    val open = all.filter { !it.inReview }.sortedBy { it.at }
    val overdue = open.filter { it.callPending && it.at <= now }
    val ahead = open.filter { !(it.callPending && it.at <= now) }
    val today = LocalDate.now(ZoneId.systemDefault())
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Follow-up") },
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
        ) {
            item(key = "header") {
                FollowUpsHeader(
                    when {
                        overdue.isNotEmpty() && review.isNotEmpty() -> "${overdue.size} overdue · ${review.size} to review"
                        overdue.isNotEmpty() -> "${overdue.size} overdue"
                        review.isNotEmpty() -> "${review.size} to review"
                        open.isEmpty() -> "All caught up"
                        else -> "${open.size} open"
                    },
                    red = overdue.isNotEmpty() || review.isNotEmpty(),
                )
            }
            item(key = "switch") { switcher() }
            if (toSchedule.isNotEmpty()) {
                item(key = "to-schedule") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = cs.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable(onClick = onOpenToSchedule)
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = cs.onTertiaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "To schedule (${toSchedule.size}) · no time set yet",
                                color = cs.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Open", color = cs.onTertiaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (all.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing to follow up. Tap ＋ Follow-up to remind yourself to call someone, text them, or both.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            fun block(key: String, title: String, count: String, color: Color, list: List<FuEntry>) {
                if (list.isEmpty()) return
                item(key = "group-$key") { DayHeader(title, count, color) }
                itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                    Box(Modifier.animateItem()) {
                        FollowUpRow(
                            e = e,
                            pos = rowPos(i, list.size),
                            now = now,
                            onOpen = { if (e.call != null && (e.callPending || e.callReview || e.text == null)) onOpenCall(e.call.id) else e.text?.let { onOpenText(it.id) } },
                            onCall = { r ->
                                if (r.review || r.dueAt <= System.currentTimeMillis()) FollowUp.mark(ctx, r)
                                dial(ctx, r.number)
                            },
                            onTexts = { onOpenText(it.id) },
                            onSend = { sendFor = it },
                        )
                    }
                }
            }
            block("review", "Waiting for you", "${review.size}", cs.error, review)
            block("overdue", "Overdue", "${overdue.size}", cs.error, overdue)
            byDay(ahead) { it.at }.forEach { (day, list) ->
                block("day-$day", RowFmt.dayTitle(day), if (day == today) "${list.size} left" else "${list.size}",
                    if (day == today) cs.primary else cs.onSurfaceVariant, list)
            }
            item(key = "done-note") {
                Text(
                    "Finished follow-ups are under Calls › Done and Texts › Done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp)
                )
            }
        }
    }

    if (showNew) FollowUpDialog(onDismiss = { showNew = false })

    sendFor?.let { r ->
        AlertDialog(
            onDismissRequest = { sendFor = null },
            title = { Text("Send now?") },
            text = { Text("Sends “${r.message}” to ${r.title} now.") },
            confirmButton = {
                TextButton(onClick = {
                    sendFor = null
                    if (!r.isBurst && SmsSender.InFlight.busy(ctx, r.id, System.currentTimeMillis())) {
                        Toast.makeText(ctx, "Already sending this message", Toast.LENGTH_SHORT).show()
                    } else {
                        SmsSender.sendDue(ctx, r.id, manual = true)
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sendFor = null }) { Text("Cancel") } }
        )
    }
}

/** "Follow-ups" with a status line and the Recordings / Settings icons. */
@Composable
fun FollowUpsHeader(status: String, red: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Follow-ups", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TopActions()
    }
}

/** A follow-up as one slim row. A call with texts gets two buttons: 📞 calls now, 💬 opens the texts. */
@Composable
private fun FollowUpRow(
    e: FuEntry,
    pos: RowPos,
    now: Long,
    onOpen: () -> Unit,
    onCall: (Reminder) -> Unit,
    onTexts: (SmsReminder) -> Unit,
    onSend: (SmsReminder) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val call = e.call
    val text = e.text
    val overdue = e.callPending && call!!.dueAt <= now

    val title = when {
        call != null && call.note.isNotBlank() -> call.note
        text != null -> text.message
        else -> call!!.headline()
    }
    val noTask = call != null && call.note.isBlank() && text == null
    val person = call?.title ?: text!!.title
    val number = call?.number ?: text!!.number
    val name = call?.name ?: text!!.name

    val chips = buildList {
        if (overdue) add(RowChip(RowFmt.late(call!!.dueAt, now), ChipTone.RED))
        if (e.callPending && text != null) add(RowChip(Fmt.timeOnly(call!!.dueAt), ChipTone.BLUE, Icons.Rounded.Call))
        if (text != null) {
            when {
                e.textActive -> {
                    val label = when {
                        text.slots.isNotEmpty() -> (text.burstIndex until text.burstCount).joinToString(" · ") { Fmt.timeOnly(text.textAt(it)) }
                        text.isBurst && text.burstIndex > 0 -> "${text.burstIndex} of ${text.burstCount} sent"
                        text.isBurst -> text.burstLabel ?: ""
                        else -> Fmt.timeOnly(text.alarmAt)
                    }
                    add(RowChip(label, ChipTone.GREEN, Icons.Rounded.Sms))
                }
                text.review == SmsReminder.Review.REPLIED -> add(RowChip("Replied “${text.doneReply}”", ChipTone.GREEN, Icons.Rounded.Check))
                text.review == SmsReminder.Review.NOT_SENT -> add(RowChip("Text not sent", ChipTone.RED))
                text.review != null -> add(RowChip(if (text.tracked) "No reply" else "Texts sent", if (text.tracked) ChipTone.ORANGE else ChipTone.MUTED))
            }
            if (text.tracked && text.code.isNotEmpty()) add(RowChip(text.code, ChipTone.BLUE, Icons.Rounded.Tag))
        }
        if (e.callReview) add(RowChip(if (call!!.note.isBlank()) "No task written" else "Done, review it", ChipTone.GREEN))
        if (call != null && call.autopilot && text != null) add(RowChip("Autopilot", ChipTone.MUTED, Icons.Rounded.Bolt))
        if (call != null && call.delays.isNotEmpty()) add(RowChip("Delayed ${call.delays.size}×", ChipTone.ORANGE, Icons.Rounded.History))
        if (call != null && call.repeatLabel != null && e.callPending) add(RowChip(call.repeatLabel!!, ChipTone.MUTED, Icons.Rounded.AutoMode))
    }

    val (timeTop, timeSub) = when {
        e.inReview -> when {
            e.textReview && text!!.review == SmsReminder.Review.REPLIED -> "Replied" to RowFmt.doneSub(text.reviewAt)
            e.textReview -> (if (text!!.review == SmsReminder.Review.NOT_SENT) "Failed" else "Sent") to RowFmt.doneSub(text.reviewAt)
            else -> "Done" to RowFmt.doneSub(call!!.doneAt)
        }
        else -> Fmt.timeOnly(e.at) to (if (!e.callPending && e.textActive && text!!.burstIndex > 0) "next text" else RowFmt.timeSub(e.at, now))
    }

    TaskRow(
        pos = pos,
        timeTop = timeTop,
        timeSub = timeSub,
        timeRed = overdue,
        title = title,
        titleStyle = if (noTask) TitleStyle.NO_TASK else TitleStyle.NORMAL,
        person = person,
        personDetail = number.takeIf { name.isNotBlank() && it.isNotBlank() },
        chips = chips,
        expanded = false,
        onClick = onOpen,
        action = {
            val hasNumber = number.isNotBlank()
            when {
                call != null && (e.callPending || e.callReview) && text != null && (e.textActive || e.textReview) -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RoundAction(Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, hasNumber) { onCall(call) }
                    RoundAction(Icons.Rounded.Sms, "Texts", cs.secondary, cs.onSecondary) { onTexts(text) }
                }
                call != null && (e.callPending || e.callReview) ->
                    RoundAction(Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, hasNumber) { onCall(call) }
                text != null && e.textActive ->
                    RoundAction(Icons.AutoMirrored.Rounded.Send, "Send now", cs.secondary, cs.onSecondary) { onSend(text) }
                text != null ->
                    RoundAction(Icons.Rounded.Sms, "Texts", cs.secondaryContainer, cs.onSecondaryContainer) { onTexts(text) }
            }
        },
    )
}

// ---------------------------------------------------------------- New / edit follow-up

/** A sensible first text time for a task due at [due]: 9:00 AM that day, else an hour before, else soon. */
private fun firstTextTime(due: Long, now: Long): Long? {
    val zone = ZoneId.systemDefault()
    val nine = Instant.ofEpochMilli(due).atZone(zone).toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
    return when {
        nine in (now + 60_000L) until due -> nine
        due - 3_600_000L > now + 60_000L -> due - 3_600_000L
        due > now + 120_000L -> now + 60_000L * ((due - now) / 120_000L).coerceAtLeast(1)
        else -> null
    }
}

/**
 * New follow-up (or Edit, with [linkId]): the person, the task, the due time, then two switches:
 * "Remind me to call" (on) and "Text him" with its own times. With both on, Autopilot lets his
 * "Done" reply cancel the call. [taskRequired] is false for the after-call popup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowUpDialog(
    onDismiss: () -> Unit,
    linkId: Long = 0L,
    initialName: String = "",
    initialNumber: String = "",
    initialTask: String = "",
    taskRequired: Boolean = true,
    startWithText: Boolean = false,
) {
    val ctx = LocalContext.current
    val editCall = remember { FollowUps.call(linkId) }
    val editText = remember { FollowUps.text(linkId) }
    val editing = linkId != 0L
    val now = remember { System.currentTimeMillis() }

    var name by remember { mutableStateOf(editCall?.name ?: editText?.name ?: initialName) }
    var number by remember { mutableStateOf(editCall?.number ?: editText?.number ?: initialNumber) }
    var task by remember { mutableStateOf(editCall?.note?.takeIf { it.isNotBlank() } ?: editText?.message ?: initialTask) }
    var due by remember {
        mutableStateOf<Long?>(
            editCall?.dueAt ?: editText?.taskDue?.takeIf { it > 0L }
                ?: QuickTimes.options(ctx).getOrNull(1)?.whenMs ?: (now + 3_600_000L)
        )
    }
    var callOn by remember { mutableStateOf(if (editing) editCall != null && !editCall.done else true) }
    var textOn by remember { mutableStateOf((editing && editText != null && !editText.done) || startWithText) }
    var times by remember {
        mutableStateOf<List<Long>>(
            editText?.takeIf { !it.done }?.let { t -> (0 until t.burstCount).map { t.textAt(it) } }
                ?: if (startWithText) listOfNotNull(due?.let { firstTextTime(it, now) }) else emptyList()
        )
    }
    var message by remember { mutableStateOf(editText?.message ?: "") }
    var messageTouched by remember { mutableStateOf(editText != null) }
    var tracked by remember { mutableStateOf(editText?.tracked ?: true) }
    var autopilot by remember { mutableStateOf(editCall?.autopilot ?: false) }
    var starred by remember { mutableStateOf(editCall?.starred ?: false) }
    var showDue by remember { mutableStateOf(false) }
    var addTime by remember { mutableStateOf(false) }
    var showAutoHelp by remember { mutableStateOf(false) }

    val start = remember { listOf(name, number, task, due, callOn, textOn, times, message, tracked, autopilot, starred) }
    val cancel = rememberDiscardGuard(
        changed = { start != listOf(name, number, task, due, callOn, textOn, times, message, tracked, autopilot, starred) },
        onClose = onDismiss,
    )
    val textMessage = if (messageTouched) message else task

    val problem = when {
        number.isBlank() -> "Pick a person"
        taskRequired && !editing && task.isBlank() -> "Write the task first"
        !callOn && !textOn -> "Turn on a call reminder or texts"
        textOn && due == null -> "Texts need a due time"
        textOn && times.isEmpty() -> "Add a time for the texts"
        textOn && textMessage.isBlank() -> "Write the text message"
        editing && due == null -> "Set a due time"
        else -> null
    }

    AlertDialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp),
        title = { Text(if (editing && startWithText) "Add texts to this follow-up" else if (editing) "Edit follow-up" else "New follow-up") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (number.isBlank()) {
                    PersonPicker(prompt = "Who is this follow-up with?") { n, num -> name = n; number = num }
                } else {
                    ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
                }
                OutlinedTextField(
                    value = task,
                    onValueChange = { task = it },
                    trailingIcon = voiceMic(task) { task = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (taskRequired && !editing) "Task *" else "Task (optional)") },
                    placeholder = { Text("e.g. Send the March invoice") },
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp)
                )

                Text("DUE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TimePickerPanel(selected = due) { due = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showDue = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(due?.let { Fmt.dueLabel(it) } ?: "No due time", fontWeight = FontWeight.SemiBold)
                    }
                    if (due != null && !editing) {
                        TextButton(onClick = { due = null; textOn = false }) { Text("No time") }
                    }
                }
                if (due == null) {
                    Text(
                        "No due time: it is saved to To schedule, and you set the time later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SwitchCard(
                    icon = { Icon(Icons.Rounded.Call, contentDescription = null) },
                    title = "Remind me to call",
                    subtitle = due?.let { "Rings at the due time · ${Fmt.timeOnly(it)}" } ?: "Rings once you set a time",
                    checked = callOn,
                    onChange = { callOn = it },
                )
                SwitchCard(
                    icon = { Icon(Icons.Rounded.Sms, contentDescription = null) },
                    title = "Text ${name.ifBlank { "him" }.substringBefore(' ')}",
                    subtitle = if (due == null) "Set a due time first" else "Reminder texts from your SIM, at their own times",
                    checked = textOn,
                    enabled = due != null,
                    onChange = { on ->
                        textOn = on
                        if (on && times.isEmpty()) firstTextTime(due ?: 0L, System.currentTimeMillis())?.let { times = listOf(it) }
                    },
                )
                if (textOn) {
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("TEXT TIMES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            times.sorted().forEach { t ->
                                InputChip(
                                    selected = false,
                                    onClick = { times = times - t },
                                    label = { Text(Fmt.dueLabel(t)) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                                )
                            }
                            TextButton(onClick = { addTime = true }) { Text("＋ Add time") }
                        }
                        OutlinedTextField(
                            value = textMessage,
                            onValueChange = { message = it; messageTouched = true },
                            trailingIcon = voiceMic(textMessage) { message = it; messageTouched = true },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Message") },
                            supportingText = { due?.let { Text("The app adds “(Due: ${Fmt.fullDate(it)})” and updates it on every New time.") } },
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tracking number", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (tracked) "Adds a task number, so his “Done <number>” reply counts"
                                    else "Plain texts; mark it done yourself",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = tracked, onCheckedChange = { tracked = it })
                        }
                    }
                }
                if (callOn && textOn) {
                    SwitchCard(
                        icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                        title = "Autopilot ⓘ",
                        subtitle = if (autopilot) "His “Done” reply cancels my call reminder" else "Off: my call still rings after his “Done”",
                        checked = autopilot,
                        enabled = tracked,
                        onChange = { autopilot = it },
                        onTitleClick = { showAutoHelp = true },
                    )
                }
                StarSwitchRow(starred) { starred = it }
                if (problem != null) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    val plan = if (textOn) FollowUps.TextPlan(times, textMessage.trim(), tracked) else null
                    if (editing) {
                        FollowUps.update(ctx, linkId, name, number, task, due!!, callOn, autopilot && callOn && textOn, plan, starred)
                    } else {
                        FollowUps.create(ctx, name, number, task, due, callOn, autopilot && callOn && textOn, plan, starred)
                        if (due == null) Toast.makeText(ctx, "Saved to To schedule", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )

    if (showDue) {
        DateTimePickerFlow(
            initialMs = due ?: (System.currentTimeMillis() + 3_600_000L),
            onPicked = { due = it; showDue = false },
            onDismiss = { showDue = false }
        )
    }
    if (addTime) {
        val zone = ZoneId.systemDefault()
        val suggest = times.maxOrNull()?.plus(3_600_000L)
            ?: firstTextTime(due ?: 0L, System.currentTimeMillis())
            ?: (System.currentTimeMillis() + 3_600_000L)
        DateTimePickerFlow(
            initialMs = suggest,
            onPicked = { ms ->
                // Whole minutes, no copies.
                val m = Instant.ofEpochMilli(ms).atZone(zone).withSecond(0).withNano(0).toInstant().toEpochMilli()
                if (m !in times) times = times + m
                addTime = false
            },
            onDismiss = { addTime = false }
        )
    }
    if (showAutoHelp) {
        AlertDialog(
            onDismissRequest = { showAutoHelp = false },
            title = { Text("Autopilot") },
            text = {
                Text(
                    "On: when he replies “Done <task number>” to your texts, your call reminder is cancelled and the " +
                        "task waits in To review.\n\nOff: his reply still moves the texts to To review, but your call " +
                        "reminder rings anyway so you can confirm by phone.\n\nNeeds Tracking number on, and " +
                        "Settings › Text messages › Stop on a \"Done\" reply."
                )
            },
            confirmButton = { TextButton(onClick = { showAutoHelp = false }) { Text("OK") } }
        )
    }
}

/** A switch row in a soft card, for the follow-up window. */
@Composable
private fun SwitchCard(
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

// ---------------------------------------------------------------- Projects, and the one-time card

/** The Projects tab: a placeholder until projects are designed. */
@Composable
fun ProjectsScreen() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TopActions()
        }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Projects", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFFF1D6)) {
                Text(
                    "Coming soon",
                    color = Color(0xFF7A4B00),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Manage projects running now or in plan.\nEach task will get a Project field.",
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/** Shown once after this update: what moved where. */
@Composable
fun WhatMovedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What moved where") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔔  Calls and Texts are now one tab: Follow-ups. All your old items are there, task numbers kept. Use All · Calls · Texts at the top.")
                Text("📞💬  One follow-up can remind you to call and text him, at different times.")
                Text("⚡  Autopilot (off by default): his “Done” reply cancels your call.")
                Text("🕘  New time moves his texts too, and updates the due time in the message.")
                Text("👥  People tab: everyone you follow up with. 📁 Projects is coming soon.")
                Text("🎙 ⚙  Recordings and Settings stay at the top right.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/** The switch at the top of New time for a follow-up whose texts are still going: "His texts move too". */
@Composable
fun TextsMoveSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("His texts move too", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Same clock times on the new day, with the new due time in the message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
