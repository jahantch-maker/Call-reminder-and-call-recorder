package com.callbackreminder.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * The Settings menu: one row per section. Each row opens its own page, which slides in like
 * Quick times does.
 */
@Composable
fun SettingsScreen(
    resumeKey: Int,
    onBack: (() -> Unit)?,
    onOpenSetup: () -> Unit,
    onOpenPopup: () -> Unit,
    onOpenQuickTimes: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenTextMessages: () -> Unit,
) {
    val ctx = LocalContext.current
    val status = remember(resumeKey) { SetupStatus.read(ctx) }
    val setupItems = setupChecks(status)
    val quickCount = remember { QuickTimes.load(ctx).size }

    val popupTypes = remember {
        listOfNotNull(
            "Outgoing".takeIf { AppSettings.get(ctx, AppSettings.OUTGOING) },
            "Incoming".takeIf { AppSettings.get(ctx, AppSettings.INCOMING) },
            "Missed".takeIf { AppSettings.get(ctx, AppSettings.MISSED) },
            "WhatsApp".takeIf { AppSettings.get(ctx, AppSettings.WHATSAPP) },
        )
    }
    val recordingSummary = remember(resumeKey) {
        val phone = AppSettings.get(ctx, AppSettings.REC_PHONE)
        val whatsapp = AppSettings.get(ctx, AppSettings.REC_WHATSAPP)
        when {
            !status.recordingReady -> "Permissions needed"
            phone && whatsapp -> "Phone and WhatsApp calls"
            phone -> "Phone calls"
            whatsapp -> "WhatsApp calls"
            else -> "Off"
        }
    }
    val reminderSummary = remember { "${ReminderStyle.mode(ctx).title} · ${ReminderSound.title(ctx)}" }

    SettingsPage(title = "Settings", onBack = onBack) {
        NavRow(
            icon = Icons.Rounded.VerifiedUser,
            title = "Setup",
            summary = if (setupItems.all { it }) "All ${setupItems.size} permissions allowed"
            else "${setupItems.count { it }} of ${setupItems.size} permissions allowed",
            onClick = onOpenSetup
        )
        NavRow(
            icon = Icons.Rounded.Layers,
            title = "After-call popup",
            summary = if (popupTypes.isEmpty()) "Off for all calls" else popupTypes.joinToString(", "),
            onClick = onOpenPopup
        )
        NavRow(
            icon = Icons.Rounded.Schedule,
            title = "Quick times",
            summary = "$quickCount of ${QuickTime.MAX} set · shown on the popup and Snooze menu",
            onClick = onOpenQuickTimes
        )
        NavRow(
            icon = Icons.Rounded.Mic,
            title = "Call recording",
            summary = recordingSummary,
            onClick = onOpenRecording
        )
        NavRow(
            icon = Icons.Rounded.NotificationsActive,
            title = "Reminder style & ringtone",
            summary = reminderSummary,
            onClick = onOpenReminder
        )
        NavRow(
            icon = Icons.Rounded.Sms,
            title = "Text messages",
            summary = "${BurstTemplates.load(ctx).size} burst templates · " +
                if (BurstTemplates.stopOnReply(ctx)) "stops on a \"Done <number>\" reply" else "reply check off",
            onClick = onOpenTextMessages
        )
    }
}

/** Every permission shown on the Setup page, true when allowed. */
private fun setupChecks(s: SetupStatus): List<Boolean> = listOfNotNull(
    s.phone, s.callLog, s.notifications, s.overlay,
    s.fullScreen.takeIf { Build.VERSION.SDK_INT >= 34 },
    s.callPhone, s.notificationAccess, s.contacts, s.battery,
)

/** Asks for all the app's runtime permissions at once; Android skips the ones already allowed. */
@Composable
private fun rememberRuntimeRequest(onResult: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    return {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(list.toTypedArray())
    }
}

private fun openAppInfo(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    )
}

// ---------------------------------------------------------------- Setup

@Composable
fun SetupSettingsScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val status = remember(resumeKey, refresh) { SetupStatus.read(ctx) }
    val requestRuntime = rememberRuntimeRequest { refresh++ }

    SettingsPage(title = "Setup", onBack = onBack) {
        SectionCard(title = "Permissions", subtitle = "The popup needs these permissions to work.") {
            PermissionRow(
                icon = Icons.Rounded.Phone,
                title = "Phone state",
                description = "Detect when a call ends",
                ok = status.phone,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Call,
                title = "Call log",
                description = "Know who you just talked to",
                ok = status.callLog,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Notifications,
                title = "Notifications",
                description = "Remind you at the right time",
                ok = status.notifications,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Layers,
                title = "Display over other apps",
                description = "Show the popup right after a call",
                ok = status.overlay,
                onAllow = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            )
            if (Build.VERSION.SDK_INT >= 34) {
                HorizontalDivider()
                PermissionRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = "Full-screen alerts",
                    description = "Lets the ringing reminder open on a locked screen",
                    ok = status.fullScreen,
                    onAllow = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }
                )
            }
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Call,
                title = "Call directly",
                description = "\"Call back\" dials straight away, even on the lock screen (no dialer, no unlocking)",
                ok = status.callPhone,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Chat,
                title = "Notification access",
                description = "Needed for the popup after WhatsApp calls. Find Callback Reminder in the list and switch it on.",
                ok = status.notificationAccess,
                onAllow = {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Contacts,
                title = "Contacts",
                description = "Finds the phone number of a WhatsApp contact, so you can call back on your SIM",
                ok = status.contacts,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.BatteryFull,
                title = "Battery: Unrestricted",
                description = "Stops Samsung from putting the app to sleep. Open App info → Battery → Unrestricted.",
                ok = status.battery,
                actionLabel = "Open",
                onAllow = { openAppInfo(ctx) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "If a permission will not turn on, open App info, tap the ⋮ menu (top right) and choose " +
                    "\"Allow restricted settings\", then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openAppInfo(ctx) }) { Text("Open App info") }
        }
    }
}

// ---------------------------------------------------------------- After-call popup

@Composable
fun PopupSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var incoming by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.INCOMING)) }
    var outgoing by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.OUTGOING)) }
    var missed by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.MISSED)) }
    var whatsapp by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.WHATSAPP)) }

    SettingsPage(title = "After-call popup", onBack = onBack) {
        SectionCard(title = "Show the popup after", subtitle = "Choose which calls show the popup.") {
            SwitchRow("Outgoing calls", outgoing) {
                outgoing = it; AppSettings.set(ctx, AppSettings.OUTGOING, it)
            }
            HorizontalDivider()
            SwitchRow("Incoming calls", incoming) {
                incoming = it; AppSettings.set(ctx, AppSettings.INCOMING, it)
            }
            HorizontalDivider()
            SwitchRow("Missed calls", missed) {
                missed = it; AppSettings.set(ctx, AppSettings.MISSED, it)
            }
            HorizontalDivider()
            SwitchRow("WhatsApp calls", whatsapp) {
                whatsapp = it; AppSettings.set(ctx, AppSettings.WHATSAPP, it)
            }
            Text(
                "WhatsApp calls follow the same Outgoing / Incoming / Missed switches above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Try it out", subtitle = "See the popup without making a real call.") {
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(ctx, PostCallActivity::class.java)
                            .putExtra(PostCallActivity.EXTRA_TEST, true)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Preview the after-call popup") }
        }
    }
}

// ---------------------------------------------------------------- Call recording

@Composable
fun RecordingSettingsScreen(resumeKey: Int, onBack: () -> Unit, onOpenRecordings: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val status = remember(resumeKey, refresh) { SetupStatus.read(ctx) }
    val requestRuntime = rememberRuntimeRequest { refresh++ }

    var recPhone by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.REC_PHONE)) }
    var recWhatsApp by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.REC_WHATSAPP)) }
    var recSource by remember { mutableStateOf(RecSource.chosen(ctx)) }
    var keepDays by remember { mutableStateOf(AppSettings.getString(ctx, AppSettings.REC_KEEP_DAYS, "0")) }
    var recStatus by remember(resumeKey, refresh) {
        mutableStateOf(AppSettings.getString(ctx, AppSettings.REC_STATUS))
    }
    var sourceMenu by remember { mutableStateOf(false) }
    var keepMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val keepOptions = listOf(
        "0" to "Forever",
        "30" to "30 days",
        "60" to "60 days",
        "90" to "90 days",
        "180" to "6 months",
    )

    SettingsPage(title = "Call recording", onBack = onBack) {
        SectionCard(title = "Permissions", subtitle = "Recording needs both of these.") {
            PermissionRow(
                icon = Icons.Rounded.Mic,
                title = "Microphone",
                description = "Needed to record",
                ok = status.microphone,
                onAllow = requestRuntime
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Rounded.Accessibility,
                title = "Accessibility service",
                description = "Open Accessibility → Installed apps → Callback Reminder call recording, and switch it on. " +
                    "Without it Android silences the microphone during calls. It does not read your screen.",
                ok = status.accessibility,
                actionLabel = "Turn on",
                onAllow = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            if (!status.accessibility) {
                Text(
                    "If the switch is greyed out: open App info, tap the ⋮ menu (top right), choose " +
                        "\"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openAppInfo(ctx) }) { Text("Open App info") }
            }
        }

        SectionCard(title = "Recording", subtitle = "Records your calls and keeps them in the Recordings screen.") {
            SwitchRow("Record phone calls", recPhone) {
                recPhone = it; AppSettings.set(ctx, AppSettings.REC_PHONE, it)
            }
            HorizontalDivider()
            SwitchRow("Record WhatsApp calls", recWhatsApp) {
                recWhatsApp = it; AppSettings.set(ctx, AppSettings.REC_WHATSAPP, it)
            }
            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Recording source", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        RecSource.label(recSource),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        RecSource.options.firstOrNull { it.key == recSource }?.hint.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    FilledTonalButton(onClick = { sourceMenu = true }) { Text("Change") }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        RecSource.options.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.label) },
                                onClick = {
                                    recSource = o.key
                                    AppSettings.setString(ctx, AppSettings.REC_SOURCE, o.key)
                                    sourceMenu = false
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep recordings", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        keepOptions.firstOrNull { it.first == keepDays }?.second ?: "Forever",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    FilledTonalButton(onClick = { keepMenu = true }) { Text("Change") }
                    DropdownMenu(expanded = keepMenu, onDismissRequest = { keepMenu = false }) {
                        keepOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    keepDays = value
                                    AppSettings.setString(ctx, AppSettings.REC_KEEP_DAYS, value)
                                    keepMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: phones usually only let apps record through the microphone, so turn on the " +
                    "speaker during a call to capture the other person clearly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Test and listen", subtitle = "Check the microphone works, or play your recordings.") {
            if (recStatus.isNotBlank()) {
                Text(
                    "Last recording status: $recStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = {
                    testing = true
                    Toast.makeText(ctx, "Recording 5 seconds. Say something.", Toast.LENGTH_SHORT).show()
                    CallRecorder.testAsync(ctx, 5) { message ->
                        testing = false
                        recStatus = AppSettings.getString(ctx, AppSettings.REC_STATUS)
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !testing && status.microphone,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (testing) "Testing…" else "Test the microphone (5 s)") }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
                Text("Open recordings")
            }
        }
    }
}

// ---------------------------------------------------------------- Reminder style & ringtone

@Composable
fun ReminderSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var soundTitle by remember { mutableStateOf(ReminderSound.title(ctx)) }
    var reminderStyle by remember { mutableStateOf(ReminderStyle.mode(ctx)) }
    var popupSeconds by remember { mutableStateOf(ReminderStyle.seconds(ctx)) }
    var secondsMenu by remember { mutableStateOf(false) }

    // Needed only when the ringtone is a file from your own storage.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { soundTitle = ReminderSound.title(ctx) }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val picked: Uri? = if (data == null) null else IntentCompat.getParcelableExtra(
                data,
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java
            )
            val isPhoneDefault = picked == null ||
                picked == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            AppSettings.setString(ctx, AppSettings.RINGTONE, if (isPhoneDefault) "" else picked.toString())
            soundTitle = ReminderSound.title(ctx)

            if (!isPhoneDefault && picked.toString().contains("/external/")) {
                val perm = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(perm)
                }
            }
        }
    }

    fun pickRingtone() {
        ringtoneLauncher.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Reminder ringtone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ReminderSound.uri(ctx))
            }
        )
    }

    SettingsPage(title = "Reminder style & ringtone", onBack = onBack) {
        SectionCard(
            title = "Reminder style",
            subtitle = "What happens when it is time to call back."
        ) {
            ReminderStyle.Mode.values().forEach { mode ->
                StyleRow(
                    title = mode.title,
                    description = when (mode) {
                        ReminderStyle.Mode.FULL_SCREEN ->
                            "Rings and stays on screen until you tap Call back, Snooze or Done."
                        ReminderStyle.Mode.FULL_SCREEN_THEN_NOTIFY ->
                            "Rings on full screen for $popupSeconds seconds, then moves to your notifications so you can find it later."
                        ReminderStyle.Mode.BANNER ->
                            "A normal notification with a sound and vibration. No full-screen popup."
                    },
                    selected = reminderStyle == mode,
                    onSelect = {
                        reminderStyle = mode
                        ReminderStyle.setMode(ctx, mode)
                    }
                )
            }
            if (reminderStyle == ReminderStyle.Mode.FULL_SCREEN_THEN_NOTIFY) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Full screen for", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "$popupSeconds seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        FilledTonalButton(onClick = { secondsMenu = true }) { Text("Change") }
                        DropdownMenu(expanded = secondsMenu, onDismissRequest = { secondsMenu = false }) {
                            ReminderStyle.SECONDS_CHOICES.forEach { sec ->
                                DropdownMenuItem(
                                    text = { Text("$sec seconds") },
                                    onClick = {
                                        popupSeconds = sec
                                        ReminderStyle.setSeconds(ctx, sec)
                                        secondsMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionCard(
            title = "Reminder ringtone",
            subtitle = "How the phone rings when it is time to call back."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(soundTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Used by the full-screen reminder. Follows your ringtone volume, and stays silent when the phone is on silent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { pickRingtone() }) { Text("Change") }
            }
        }

        SectionCard(title = "Try it out", subtitle = "Hear your reminder without waiting for a real one.") {
            FilledTonalButton(
                onClick = {
                    ReminderStore.add(
                        name = "Test reminder",
                        number = "0300 1234567",
                        note = "This is a test. Mark it Done when you are finished.",
                        dueAt = System.currentTimeMillis() + 10_000L
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test a reminder in 10 seconds") }
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

/** A settings page: back arrow and title, then its cards in a scrolling column. */
@Composable
private fun SettingsPage(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // No back arrow on the Settings tab itself, only on the pages it opens.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp, start = if (onBack == null) 4.dp else 0.dp)
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A tappable card on the Settings menu that opens a section's page. */
@Composable
private fun NavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    ok: Boolean,
    actionLabel: String = "Allow",
    onAllow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (ok) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
        } else {
            FilledTonalButton(onClick = onAllow) { Text(actionLabel) }
        }
    }
}

@Composable
private fun StyleRow(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Text messages

/** Burst templates (up to 20) and the "stop when the person replies Done" switch. */
@Composable
fun TextMessageSettingsScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var templates by remember { mutableStateOf(BurstTemplates.load(ctx)) }
    var editIndex by remember { mutableStateOf<Int?>(null) } // -1 = new template
    var stopOnReply by remember { mutableStateOf(BurstTemplates.stopOnReply(ctx)) }
    var refresh by remember { mutableStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    val canReceive = remember(resumeKey, refresh) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }

    val receivePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        if (granted) {
            stopOnReply = true
            BurstTemplates.setStopOnReply(ctx, true)
        } else {
            showHelp = true
        }
    }

    fun saveTemplates(list: List<BurstTemplate>) {
        templates = list
        BurstTemplates.save(ctx, list)
    }

    SettingsPage(title = "Text messages", onBack = onBack) {
        SectionCard(
            title = "Burst templates",
            subtitle = "Up to ${BurstTemplate.MAX_TEMPLATES} templates."
        ) {
            // A short guide, so a first-time user knows what a burst is and how to use it.
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "What is a burst?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A burst sends the same message several times in a row, a few minutes apart, so it is " +
                            "hard to miss. Each text starts with its number:\n" +
                            "8:00  Reminder 1: Please fix the gate today.\n" +
                            "8:05  Reminder 2: Please fix the gate today.\n" +
                            "8:10  Reminder 3: …\n\n" +
                            "How to use it:\n" +
                            "1. Make a template here, e.g. 5 texts, 5 min apart.\n" +
                            "2. In Text messages › New message, turn on Burst and pick the template.\n" +
                            "3. Add a repeat (e.g. daily for 10 days) to send the burst every day until the work is done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (templates.isEmpty()) {
                Text(
                    "No templates yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            templates.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editIndex = i }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { saveTemplates(templates.filterIndexed { j, _ -> j != i }) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { editIndex = -1 },
                enabled = templates.size < BurstTemplate.MAX_TEMPLATES
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add template")
            }
        }

        SectionCard(
            title = "Stop when the person replies Done",
            subtitle = "Texts with Tracking number on carry a task number like 261011-01. If the person texts " +
                "back exactly Done 261011-01 (capital D, one space, the number), only that reminder stops and " +
                "moves to To review so you can check the work. A plain \"Done\" doesn't count. The app only " +
                "checks for this reply; it doesn't keep or show anyone's messages."
        ) {
            SwitchRow("Stop on a \"Done\" reply", stopOnReply && canReceive) { on ->
                if (on && !canReceive) {
                    receivePermission.launch(Manifest.permission.RECEIVE_SMS)
                } else {
                    stopOnReply = on
                    BurstTemplates.setStopOnReply(ctx, on)
                }
            }
            if (stopOnReply && !canReceive) {
                Text(
                    "Permission to receive SMS is off, so replies can't be checked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    editIndex?.let { idx ->
        BurstTemplateDialog(
            initial = templates.getOrNull(idx),
            onDismiss = { editIndex = null },
            onSave = { t ->
                saveTemplates(if (idx < 0) templates + t else templates.mapIndexed { j, old -> if (j == idx) t else old })
                editIndex = null
            }
        )
    }

    if (showHelp) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Turn on SMS for this app") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= 35) {
                        "Android locks SMS for apps installed outside Play Store. One time only:\n\n" +
                            "1. Tap Open app info below.\n" +
                            "2. Tap ⋮ (top right) › Allow restricted settings, and confirm.\n" +
                            "3. Tap Permissions › SMS › Allow.\n\n" +
                            "Then come back and turn the switch on."
                    } else {
                        "Tap Open app info below, then Permissions › SMS › Allow. Then come back and turn the switch on."
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showHelp = false
                    openAppInfo(ctx)
                }) { Text("Open app info") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showHelp = false }) { Text("Close") }
            }
        )
    }
}

/** Add or edit one burst template: how many texts, how many minutes apart. */
@Composable
private fun BurstTemplateDialog(initial: BurstTemplate?, onDismiss: () -> Unit, onSave: (BurstTemplate) -> Unit) {
    var countText by remember { mutableStateOf((initial?.count ?: 5).toString()) }
    var gapText by remember { mutableStateOf((initial?.gapMin ?: 5).toString()) }
    val count = countText.toIntOrNull()?.takeIf { it in BurstTemplate.COUNT_RANGE }
    val gap = gapText.toIntOrNull()?.takeIf { it in BurstTemplate.GAP_RANGE }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New burst template" else "Edit burst template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Number of texts") },
                    supportingText = { Text("${BurstTemplate.COUNT_RANGE.first} to ${BurstTemplate.COUNT_RANGE.last}") },
                    isError = count == null,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = gapText,
                    onValueChange = { gapText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Minutes apart") },
                    supportingText = { Text("${BurstTemplate.GAP_RANGE.first} to ${BurstTemplate.GAP_RANGE.last}") },
                    isError = gap == null,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = count != null && gap != null,
                onClick = { onSave(BurstTemplate(count ?: 5, gap ?: 5)) }
            ) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
