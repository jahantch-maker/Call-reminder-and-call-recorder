package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of "Next up": a call-back or a text message due today. */
private data class NextItem(
    val key: String,
    val at: Long,
    val title: String,
    val detail: String,
    val isCall: Boolean,
    val smsId: Long = -1L,
)

/** The Home tab: today's counts and one time-ordered list of the calls and texts still to come today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeKey: Int,
    onOpenCalls: () -> Unit,
    onOpenTexts: (tab: Int, showId: Long?) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenRecordingSettings: () -> Unit,
    onOpenToSchedule: () -> Unit,
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val status = remember(resumeKey) { SetupStatus.read(ctx) }
    var chooseNew by remember { mutableStateOf(false) }
    var newCall by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val callsDue = calls.filter { !it.done && it.dueAt < endOfToday }
    val textsToday = texts.filter { it.active && it.alarmAt < endOfToday }
    val textsToReview = texts.count { !it.done && it.review != null }
    val callsToReview = calls.count { it.done && it.review }
    val toReview = textsToReview + callsToReview

    val nextUp = (
        callsDue.map { r ->
            NextItem(
                key = "c${r.id}",
                at = r.dueAt,
                title = r.title,
                detail = listOfNotNull("Call back", r.note.takeIf { it.isNotBlank() } ?: r.repeatLabel)
                    .joinToString(" · "),
                isCall = true,
            )
        } + textsToday.map { r ->
            val parts = mutableListOf(if (r.code.isNotEmpty()) "Text ${r.code}" else "Text")
            if (r.isBurst) parts += "Reminder ${(r.burstIndex + 1).coerceAtMost(r.burstCount)} of ${r.burstCount}"
            else if (r.message.isNotBlank()) parts += r.message
            NextItem(
                key = "s${r.id}",
                at = r.alarmAt,
                title = r.title,
                detail = parts.joinToString(" · "),
                isCall = false,
                smsId = r.id,
            )
        }
        ).sortedBy { it.at }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { chooseNew = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New reminder")
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
                    Text(
                        today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        greeting(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            setupBanners(status, ctx, onOpenSetup, onOpenRecordingSettings)

            item(key = "tiles") {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile(
                        icon = Icons.Rounded.PhoneCallback,
                        count = callsDue.size,
                        label = "Calls due",
                        container = Color(0xFFFFE3E6),
                        content = Color(0xFF7A1020),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenCalls,
                    )
                    CountTile(
                        icon = Icons.Rounded.Sms,
                        count = textsToday.size,
                        label = "Texts today",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenTexts(0, null) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile(
                        icon = Icons.Rounded.PendingActions,
                        count = toReview,
                        label = "To review",
                        container = Color(0xFFFFF1C9),
                        content = Color(0xFF6A4A00),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (textsToReview == 0 && callsToReview > 0) {
                                CallsUi.tabRequest.value = CallsUi.TO_REVIEW
                                onOpenCalls()
                            } else {
                                onOpenTexts(1, null)
                            }
                        },
                    )
                    CountTile(
                        icon = Icons.Rounded.EditCalendar,
                        count = toSchedule.size,
                        label = "To schedule",
                        container = Color(0xFFFFE6C2),
                        content = Color(0xFF6A3B00),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenToSchedule,
                    )
                }
              }
            }

            item(key = "next-title") {
                Text(
                    "Next up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            if (nextUp.isEmpty()) {
                item(key = "next-empty") { AllClear() }
            }
            items(nextUp, key = { it.key }) { n ->
                NextRow(
                    n = n,
                    now = now,
                    onClick = {
                        if (n.isCall) onOpenCalls() else onOpenTexts(0, n.smsId)
                    }
                )
            }
        }
    }

    if (chooseNew) {
        ModalBottomSheet(onDismissRequest = { chooseNew = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text(
                    "Create new",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                CreateOption(
                    icon = Icons.Rounded.PhoneCallback,
                    title = "Call-back reminder",
                    subtitle = "Rings like a call at the time you pick",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { chooseNew = false; newCall = true },
                )
                CreateOption(
                    icon = Icons.Rounded.Sms,
                    title = "Text message",
                    subtitle = "Sends an SMS on time, once, on a repeat or as a burst",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { chooseNew = false; newText = true },
                )
            }
        }
    }

    if (newCall) {
        AddReminderDialog(
            onDismiss = { newCall = false },
            onSave = { name, number, note, dueAt, repeat ->
                ReminderStore.add(name, number, note, dueAt, repeat)
                newCall = false
            }
        )
    }

    if (newText) {
        SmsReminderDialog(
            onDismiss = { newText = false },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                SmsStore.add(name, number, message, dueAt, repeat, burst, tracked)
                newText = false
            }
        )
    }
}

private fun greeting(t: LocalTime): String = when {
    t.hour < 12 -> "Good morning"
    t.hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun CountTile(
    icon: ImageVector,
    count: Int,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun NextRow(n: NextItem, now: Long, onClick: () -> Unit) {
    val overdue = n.isCall && n.at <= now
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(
                icon = if (n.isCall) Icons.Rounded.Call else Icons.Rounded.Sms,
                container = if (n.isCall) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                content = if (n.isCall) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
                size = 36,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    n.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (overdue) Fmt.relative(n.at, now).replace("Overdue by ", "Overdue\n")
                else Fmt.timeOnly(n.at),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KindIcon(icon: ImageVector, container: Color, content: Color, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((size * 0.56f).dp))
    }
}

@Composable
private fun CreateOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindIcon(icon, container, content, size = 44)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AllClear() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.EventAvailable,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("Nothing left for today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap + to add a call-back or a text message.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The "finish setup" cards that used to sit on top of the call list. */
private fun LazyListScope.setupBanners(
    status: SetupStatus,
    ctx: android.content.Context,
    onOpenSetup: () -> Unit,
    onOpenRecordingSettings: () -> Unit,
) {
    if (!status.requiredOk) {
        item(key = "setup") {
            Banner(
                title = "Finish setup",
                body = "${status.missingRequired} permission(s) still needed for the popup after each call.",
                button = "Set up now",
                onClick = onOpenSetup
            )
        }
    }
    if (status.requiredOk && (!status.callPhone || !status.notificationAccess)) {
        item(key = "setup_new") {
            Banner(
                title = "New: call directly + WhatsApp calls",
                body = listOfNotNull(
                    "Allow \"Call directly\" so Call back dials without opening the dialer."
                        .takeIf { !status.callPhone },
                    "Turn on Notification access to get the popup after WhatsApp calls."
                        .takeIf { !status.notificationAccess },
                ).joinToString(" "),
                button = "Open setup",
                onClick = onOpenSetup
            )
        }
    }
    if (status.requiredOk && !status.recordingReady && AppSettings.get(ctx, AppSettings.REC_PHONE)) {
        item(key = "setup_recording") {
            Banner(
                title = "New: call recording",
                body = "Allow the Microphone and switch on the call-recording Accessibility service " +
                    "to record your calls.",
                button = "Set up recording",
                onClick = onOpenRecordingSettings
            )
        }
    }
}

@Composable
private fun Banner(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
