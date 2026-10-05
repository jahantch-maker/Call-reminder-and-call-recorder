package com.callbackreminder.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

@Composable
fun SettingsScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val status = remember(resumeKey, refresh) { SetupStatus.read(ctx) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    fun requestRuntime() {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
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
            }

            // ---- Test tools ----
            SectionCard(title = "Try it out", subtitle = "Test without making a real call.") {
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(ctx, PostCallActivity::class.java)
                                .putExtra(PostCallActivity.EXTRA_TEST, true)
                                .putExtra(PostCallActivity.EXTRA_KIND, "outgoing")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Preview the after-call popup") }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        ReminderStore.add(
                            name = "Test reminder",
                            number = "",
                            note = "This is a test. You can delete it.",
                            dueAt = System.currentTimeMillis() + 10_000L
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send a test reminder in 10 seconds") }
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
