package com.callbackreminder.app

import android.provider.CallLog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shows the popup for the first call in the queue, then the next one, until none are left. */
@Composable
fun PostCallScreen(queue: SnapshotStateList<CallInfo>, onFinished: () -> Unit) {
    var handled by remember { mutableIntStateOf(0) }
    val current = queue.firstOrNull()

    LaunchedEffect(current == null) {
        if (current == null) onFinished()
    }
    if (current == null) return

    val total = handled + queue.size
    val positionLabel = if (total > 1) "Call ${handled + 1} of $total" else null
    val onNext = {
        if (queue.isNotEmpty()) queue.removeAt(0)
        handled = handled + 1
    }
    // A call task may still be waiting or sit in To review (Call now); a text task must not be done.
    val task = current.followUpId.takeIf { it > 0 }?.let { ReminderStore.get(it) }?.takeIf { !it.done || it.review }
    val textTask = current.textTaskId.takeIf { it > 0 }?.let { SmsStore.get(it) }?.takeIf { !it.done }
    // A new task came up in the same call: ask about it next, with the same person.
    val onNewTask = {
        if (queue.isNotEmpty()) queue[0] = current.copy(followUpId = -1L, textTaskId = -1L, prefillNote = "")
    }
    key(handled, task?.id, textTask?.id) {
        when {
            task != null -> FollowUpSheet(
                call = current,
                task = task,
                positionLabel = positionLabel,
                onNext = onNext,
                onNewTask = onNewTask,
            )
            textTask != null -> TextFollowUpSheet(
                call = current,
                task = textTask,
                positionLabel = positionLabel,
                onNext = onNext,
                onNewTask = onNewTask,
            )
            else -> PostCallSheet(call = current, positionLabel = positionLabel, onNext = onNext)
        }
    }
}

/** 27 Nov (S15a) How much of "Close by itself" is left (1 → 0) for the ring round ✕; null when off or stopped. */
internal val LocalAutoClose = androidx.compose.runtime.compositionLocalOf<Float?> { null }

/** The ✕ (Skip) of the after-call popups, with the shrinking "Close by itself" ring round it. */
@Composable
internal fun SkipX(onClick: () -> Unit, description: String = "Skip") {
    val left = LocalAutoClose.current
    Box(contentAlignment = Alignment.Center) {
        if (left != null) androidx.compose.material3.CircularProgressIndicator(progress = { left }, modifier = Modifier.size(40.dp), strokeWidth = 2.5.dp)
        IconButton(onClick = onClick) { Icon(Icons.Rounded.Close, contentDescription = description) }
    }
}

/** The dimmed backdrop (tap to skip) and the sheet that slides up from the bottom. */
@Composable
internal fun PopupSheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    // 27 Nov (S15a) Close by itself: counts down every second, any touch stops it for good, at 0 it is a Skip.
    val ctx = LocalContext.current
    val total = remember { SimPopup.autoClose(ctx) }
    var left by remember { mutableIntStateOf(total) }
    var stopped by remember { mutableStateOf(total <= 0) }
    LaunchedEffect(stopped) {
        while (!stopped && left > 0) {
            kotlinx.coroutines.delay(1_000L)
            if (!stopped) left--
        }
        if (!stopped && left <= 0) onDismiss()
    }
    val stopOnTouch = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                stopped = true
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Slides up from the bottom when it appears (and again for the next call in the queue).
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(tween(170)) { it } + fadeIn(tween(115)),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(stopOnTouch)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .safeBottomPadding(0.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(14.dp))
                    if (!stopped) {
                        LinearProgressIndicator(progress = { left / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(3.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("⏱ Closes in ${left}s · touch to keep it open", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.runtime.CompositionLocalProvider(LocalAutoClose provides if (stopped) null else left / total.toFloat()) {
                        content()
                    }
                }
            }
        }
    }
}

/** Which step of the after-call popup is showing. */
private enum class PostCallStep { CHOOSE, FOLLOW_UP, MY_TASK }

/**
 * The after-call popup. First the note, then two big buttons: Create my task (it goes to My tasks) and Create
 * follow-up task (a call back and/or texts to him). Each one is set right here in the popup with the quick time
 * picker; No time and an empty note are both allowed (it then waits in To schedule).
 */
@Composable
private fun PostCallSheet(call: CallInfo, positionLabel: String?, onNext: () -> Unit) {
    val ctx = LocalContext.current

    var typedName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(call.prefillNote) }
    var step by remember { mutableStateOf(PostCallStep.CHOOSE) }
    var showPicker by remember { mutableStateOf(false) }
    // My task: how long it takes, needed when it gets a time (the Calendar books it).
    var myLength by remember { mutableIntStateOf(0) }
    var callOn by remember { mutableStateOf(true) }
    var alsoText by remember { mutableStateOf(false) }
    val text = rememberTextPlanState()
    val lists by MyTaskStore.lists.collectAsState()
    var list by remember { mutableLongStateOf(MyTaskStore.UNSORTED) }
    var listMenu by remember { mutableStateOf(false) }
    var sameAsk by remember { mutableStateOf<Pair<FollowUps.Same, Long?>?>(null) }

    val callerName = call.name.takeIf { it.isNotBlank() }
    val callerNumber = call.number.takeIf { it.isNotBlank() }
    val title = callerName ?: callerNumber ?: "Unknown caller"
    val needsName = callerName == null && callerNumber == null
    val firstName = Names.first(callerName ?: typedName)
    val person = callerName ?: typedName.takeIf { it.isNotBlank() } ?: callerNumber ?: "them"

    val baseLabel = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "Missed call"
        else -> "Call"
    }
    val typeLabel = if (call.viaWhatsApp) "WhatsApp ${baseLabel.lowercase()}" else baseLabel
    val subtitle = buildString {
        append(typeLabel)
        if (callerName != null && callerNumber != null) append(" · ").append(callerNumber)
        val round = CallAgain.roundLabel(call)
        if (round != null) append(" · ").append(round)
        else if (call.durationSec > 0) append(" · ").append(Fmt.duration(call.durationSec))
    }

    /** The task line: the note, or "Follow up on call with Ali" when nothing was typed. */
    fun taskText() = note.trim().ifBlank { "Follow up on call with $person" }

    fun saveFollowUp(due: Long?, same: FollowUps.Same? = null, checked: Boolean = false) {
        val number = callerNumber ?: ""
        val task = taskText()
        // The same task for this person is already open: ask Update it / Save as new first.
        if (!checked) FollowUps.findSame(number, task)?.let { sameAsk = it to due; return }
        val textOn = alsoText && number.isNotBlank()
        val plan = if (textOn) {
            // No text time picked yet: a sensible one from the call time.
            if (text.times.isEmpty() && due != null) {
                text.times = listOf(FollowUps.firstTextTime(due, System.currentTimeMillis()) ?: (System.currentTimeMillis() + 60_000L))
            }
            text.plan(task)
        } else null
        val who = callerName ?: typedName
        val on = callOn && number.isNotBlank()
        val mark = SavedChip.mark()
        val timed = if (same != null && same.sched == null) {
            FollowUps.update(ctx, same.linkId, who, number, task, due, on, autopilot = false, text = plan, starred = false)
        } else {
            FollowUps.create(ctx, who, number, task, due, callOn = on, autopilot = false, text = plan, starred = false, from = same?.sched)
        }
        // v0.51 the popup shrinks into "✓ Saved · View · Edit" (an update shows the old toast).
        if (same != null && same.sched == null || !SavedChip.showSince(mark, outside = true)) Toast.makeText(
            ctx,
            if (timed && due != null) "Follow-up set · ${Fmt.dueLabel(due)}" else "Saved to Follow-ups · no time yet (To schedule)",
            Toast.LENGTH_SHORT
        ).show()
        onNext()
    }

    fun saveMyTask(due: Long) {
        if (due > 0L && myLength <= 0) {
            Toast.makeText(ctx, "Pick how long first", Toast.LENGTH_SHORT).show()
            return
        }
        val made = MyTaskStore.add(
            title = taskText(),
            note = "",
            dueAt = due,
            name = callerName ?: typedName,
            number = callerNumber ?: "",
            listId = list,
            durationMin = myLength,
        )
        SavedChip.showMine(made, outside = true)
        onNext()
    }

    // One window per job: the same New follow-up / New My task windows as in the app, filled in from the call.
    // Saved closes the popup; Cancel comes back here.
    var openWindow by remember { mutableStateOf<PostCallStep?>(null) }
    fun stamp() = listOf(ReminderStore.items.value.size, ToScheduleStore.items.value.size, SmsStore.items.value.size,
        MyTaskStore.items.value.size, ReminderStore.items.value.maxOfOrNull { it.id } ?: 0L, MyTaskStore.items.value.maxOfOrNull { it.id } ?: 0L)
    val before = remember(openWindow) { stamp() }
    fun closed() { val saved = stamp() != before; openWindow = null; if (saved) onNext() }
    when (openWindow) {
        PostCallStep.FOLLOW_UP -> FollowUpDialog(
            onDismiss = { closed() },
            initialName = callerName ?: typedName,
            initialNumber = callerNumber ?: "",
            initialTask = note.trim(),
            taskRequired = false,
        )
        PostCallStep.MY_TASK -> MyTaskDialog(
            existing = null,
            onDismiss = { closed() },
            onSwitchKind = null,
            personName = callerName ?: typedName,
            personNumber = callerNumber ?: "",
            initialTitle = taskText(),
        )
        else -> {}
    }

    sameAsk?.let { (same, due) ->
        SameTaskDialog(
            same,
            onCancel = { sameAsk = null },
            onSaveNew = { sameAsk = null; saveFollowUp(due, checked = true) },
            onUpdate = { sameAsk = null; saveFollowUp(due, same, checked = true) },
        )
    }

    PopupSheetFrame(onDismiss = onNext) {
        if (positionLabel != null) {
            Text(
                text = positionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        if (step == PostCallStep.CHOOSE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(text = callerName ?: callerNumber ?: "?", size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SkipX(onNext)
            }
            Spacer(Modifier.height(16.dp))
            if (needsName) {
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Who was this? (optional)") },
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                trailingIcon = voiceMic(note) { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What did you discuss? (note)") },
                maxLines = 4,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Make it a task",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigChoice(
                    icon = Icons.Rounded.CheckBox,
                    label = "Create my task",
                    container = MaterialTheme.colorScheme.secondary,
                    content = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.weight(1f),
                    onClick = { openWindow = PostCallStep.MY_TASK }
                )
                BigChoice(
                    icon = Icons.Rounded.Notifications,
                    label = "Create follow-up task",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = { openWindow = PostCallStep.FOLLOW_UP }
                )
            }
            if (callerNumber != null) {
                Spacer(Modifier.height(14.dp))
                CallAgainButton { CallAgain.dialAgain(ctx, call, callerName ?: typedName, note); onNext() }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNext) { Text("Skip") }
            }
        } else {
            val followUp = step == PostCallStep.FOLLOW_UP
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { step = PostCallStep.CHOOSE }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Icon(
                    if (followUp) Icons.Rounded.Notifications else Icons.Rounded.CheckBox,
                    contentDescription = null,
                    tint = if (followUp) Color(0xFFF0B850) else MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (followUp) firstName?.let { "Follow-up with $it" } ?: "Follow-up" else "My task",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                trailingIcon = voiceMic(note) { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task (optional)") },
                placeholder = { Text("Follow up on call with $person") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))

            if (followUp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchCard(
                        icon = { Icon(Icons.Rounded.Call, contentDescription = null) },
                        title = "Remind me to call",
                        subtitle = if (callerNumber == null) "No number for this call" else "Rings at the time you pick below",
                        checked = callOn && callerNumber != null,
                        enabled = callerNumber != null,
                        onChange = { callOn = it },
                    )
                    if (WebRules.smsOn(ctx)) SwitchCard(
                        icon = { Icon(Icons.Rounded.Sms, contentDescription = null) },
                        title = firstName?.let { "Also text $it" } ?: "Also send texts",
                        subtitle = if (alsoText) "On: ${firstName?.let { "$it's texts" } ?: "texts"}, with Burst" else "Off: only your call back",
                        checked = alsoText && callerNumber != null,
                        enabled = callerNumber != null,
                        onChange = { alsoText = it },
                    )
                    if (alsoText && callerNumber != null) {
                        TextPlanSection(text, taskText(), null, folded = true)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (alsoText) "CALL TIME" else "WHEN?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (callerName != null || callerNumber != null || typedName.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("👤 $person", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Box {
                        Surface(
                            onClick = { listMenu = true },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                "📁 ${lists.firstOrNull { it.id == list }?.name ?: "Unsorted"} ▾",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            lists.forEach { l ->
                                DropdownMenuItem(text = { Text(l.name) }, onClick = { list = l.id; listMenu = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "WHEN?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!followUp) {
                HowLongRow(myLength, onLength = { myLength = it })
                Spacer(Modifier.height(6.dp))
            }
            // One tap on a time saves it, right here in the popup.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPickBusy provides if (followUp) null else PickBusy(myLength, -1L) { myLength = it }
            ) {
                ShortTimeRow(selected = null, plusRow = true, onPick = { if (followUp) saveFollowUp(it) else saveMyTask(it) })
            }
            Spacer(Modifier.height(8.dp))
            RemoveTimeButton(label = "No time · set it later") { if (followUp) saveFollowUp(null) else saveMyTask(0L) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showPicker = true }) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick date & time")
                }
                TextButton(onClick = onNext) { Text("Skip") }
            }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs, // Tomorrow 10 am
            onPicked = { showPicker = false; if (step == PostCallStep.FOLLOW_UP) saveFollowUp(it) else saveMyTask(it) },
            onDismiss = { showPicker = false }
        )
    }
}

/** One of the two big blocks on the after-call popup. */
@Composable
private fun BigChoice(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(90.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The green full-width button at the bottom of the after-call popups: calls the person again and keeps
 * what was typed, so the popup comes back after that call with "called twice".
 */
@Composable
internal fun CallAgainButton(onClick: () -> Unit) {
    // 27 Nov (S15b) Hold to call: it dials only after a press of the chosen seconds; a quick tap says so.
    val ctx = LocalContext.current
    val hold = remember { SimPopup.holdToCall(ctx) }
    val fill = remember { androidx.compose.animation.core.Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(hint) { if (hint) { kotlinx.coroutines.delay(2_000L); hint = false } }
    val press = if (hold <= 0) Modifier else Modifier.pointerInput(hold) {
        androidx.compose.foundation.gestures.detectTapGestures(onPress = {
            holding = true
            val run = scope.launch {
                fill.snapTo(0f)
                fill.animateTo(1f, tween(hold * 1_000, easing = androidx.compose.animation.core.LinearEasing))
                holding = false
                fill.snapTo(0f)
                onClick()
            }
            val done = tryAwaitRelease()
            if (run.isActive) {
                run.cancel()
                holding = false
                fill.snapTo(0f)
                if (done) hint = true
            }
        })
    }
    Surface(
        onClick = { if (hold <= 0) onClick() },
        modifier = Modifier.fillMaxWidth().height(if (hold > 0) 54.dp else 48.dp).then(press),
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF0FA68E),
        contentColor = Color.White,
    ) {
        Box {
            if (hold > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value).background(Color(0xFF07705F)))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (holding) "Keep holding…" else "Call again", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (hold > 0) Text(if (hint) "Hold the button ${hold}s to call" else "Hold ${hold}s to call",
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
internal fun OptionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
