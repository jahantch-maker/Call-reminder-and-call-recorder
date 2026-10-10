package com.callbackreminder.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

@Composable
fun SettingsScreen(
    resumeKey: Int,
    onBack: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenQuickTimes: () -> Unit,
) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val status = remember(resumeKey, refresh) { SetupStatus.read(ctx) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    var soundTitle by remember { mutableStateOf(ReminderSound.title(ctx)) }

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

    fun requestRuntime() {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(list.toTypedArray())
    }

    fun openAppInfo() {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // ---- Setup checklist ----
            SectionCard(title = "Setup", subtitle = "The popup needs these permissions to work.") {
                PermissionRow(
                    icon = Icons.Rounded.Phone,
                    title = "Phone state",
                    description = "Detect when a call ends",
                    ok = status.phone,
                    onAllow = { requestRuntime() }
                )
                HorizontalDivider()
                PermissionRow(
                    icon = Icons.Rounded.Call,
                    title = "Call log",
                    description = "Know who you just talked to",
                    ok = status.callLog,
                    onAllow = { requestRuntime() }
                )
                HorizontalDivider()
                PermissionRow(
                    icon = Icons.Rounded.Notifications,
                    title = "Notifications",
                    description = "Remind you at the right time",
                    ok = status.notifications,
                    onAllow = { requestRuntime() }
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
                    onAllow = { requestRuntime() }
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
                    onAllow = { requestRuntime() }
                )
                HorizontalDivider()
                PermissionRow(
                    icon = Icons.Rounded.BatteryFull,
                    title = "Battery: Unrestricted",
                    description = "Stops Samsung from putting the app to sleep. Open App info → Battery → Unrestricted.",
                    ok = status.battery,
                    actionLabel = "Open",
                    onAllow = { openAppInfo() }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If a permission will not turn on, open App info, tap the ⋮ menu (top right) and choose " +
                        "\"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openAppInfo() }) { Text("Open App info") }
            }

            // ---- Popup switches ----
            var incoming by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.INCOMING)) }
            var outgoing by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.OUTGOING)) }
            var missed by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.MISSED)) }
            var whatsapp by remember { mutableStateOf(AppSettings.get(ctx, AppSettings.WHATSAPP)) }

            SectionCard(title = "After-call popup", subtitle = "Choose which calls show the popup.") {
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

            // ---- Quick times ----
            val quickCount = remember { QuickTimes.load(ctx).size }
            Card(
                onClick = onOpenQuickTimes,
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Quick times", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "$quickCount of ${QuickTime.MAX} set · shown on the popup and Snooze menu",
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

            // ---- Call recording ----
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

            SectionCard(
                title = "Call recording",
                subtitle = "Records your calls and keeps them in the Recordings screen."
            ) {
                PermissionRow(
                    icon = Icons.Rounded.Mic,
                    title = "Microphone",
                    description = "Needed to record",
                    ok = status.microphone,
                    onAllow = { requestRuntime() }
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
                    OutlinedButton(onClick = { openAppInfo() }) { Text("Open App info") }
                }
                HorizontalDivider()
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
                if (recStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last recording status: $recStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
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

            // ---- Reminder sound ----
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
                            "Rings until you act. Follows your ringtone volume, and stays silent when the phone is on silent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { pickRingtone() }) { Text("Change") }
                }
            }

            // ---- Test tools ----
            SectionCard(title = "Try it out", subtitle = "Test without making a real call.") {
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(ctx, PostCallActivity::class.java)
                                .putExtra(PostCallActivity.EXTRA_TEST, true)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Preview the after-call popup") }
                Spacer(Modifier.height(8.dp))
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
                ) { Text("Ring a test reminder in 10 seconds") }
            }

            Spacer(Modifier.height(24.dp))
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
