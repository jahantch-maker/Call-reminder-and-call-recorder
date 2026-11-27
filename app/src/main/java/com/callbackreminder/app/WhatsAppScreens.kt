package com.callbackreminder.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings › WhatsApp: which app the WA buttons open (set once) and the reminder message. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhatsAppSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var app by remember { mutableStateOf(WhatsApp.app(ctx)) }
    var text by remember { mutableStateOf(WhatsApp.template(ctx)) }
    val apps = remember {
        listOf(WhatsApp.PKG, WhatsApp.PKG_BUSINESS).filter { WhatsApp.installed(ctx, it) }
            .ifEmpty { listOf(WhatsApp.PKG, WhatsApp.PKG_BUSINESS) }
    }

    fun save(t: String) {
        text = t
        WhatsApp.setTemplate(ctx, t)
    }

    SettingsPage(title = "WhatsApp", onBack = onBack) {
        SectionCard(
            title = "Open in",
            subtitle = "The WA buttons open the person's chat in this app with the message typed. You press Send yourself.",
            lock = "waApp"
        ) {
            apps.forEach { pkg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { app = pkg; WhatsApp.setApp(ctx, pkg) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = app == pkg, onClick = { app = pkg; WhatsApp.setApp(ctx, pkg) })
                    Text(WhatsApp.appLabel(pkg), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        SectionCard(title = "Message", subtitle = "Write it your way. Tap a word below to add it.", lock = "waMsg") {
            OutlinedTextField(
                value = text,
                onValueChange = { save(it) },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WhatsApp.PLACEHOLDERS.forEach { ph ->
                    Surface(
                        onClick = { save(text + ph) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(ph, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ChipHeading("PREVIEW")
            Spacer(Modifier.height(6.dp))
            val sample = remember(text) {
                WhatsApp.message(ctx, "26101804", "Veranda bed remove, workout machine fix", System.currentTimeMillis(), "Hasnain")
            }
            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF005C4B), modifier = Modifier.padding(start = 32.dp)) {
                Text(sample, color = Color(0xFFE9EDEF), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            TextButton(onClick = { save(WhatsApp.DEFAULT_MESSAGE) }) { Text("↺ Reset to default") }
        }
    }
}

/**
 * The daily lists to send on WhatsApp this round: one row per person. WA (or "Send next") opens his chat with
 * the list typed; coming back ticks the row.
 */
@Composable
fun DailyWhatsAppScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // The chat that was opened: ticked once you're back in the app.
    var opened by remember { mutableStateOf<String?>(null) }
    DisposableEffect(resumeKey) {
        opened?.let { DailyList.markSent(ctx, it) }
        opened = null
        tick++
        onDispose { }
    }
    val round = remember(tick) { DailyList.round(ctx) }
    val names = remember(round) { round.keys.associateWith { DailyList.nameFor(ctx, it) } }

    fun send(key: String) {
        if (DailyList.openWhatsApp(ctx, key)) opened = key
        else {
            // Nothing open for him any more: nothing to send.
            DailyList.markSent(ctx, key)
            tick++
        }
    }

    val time = if (round.at > 0L) Instant.ofEpochMilli(round.at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())) else ""
    SettingsPage(title = "Daily lists" + if (time.isNotEmpty()) " · $time" else "", onBack = onBack) {
        if (round.keys.isEmpty()) {
            Text("Nothing to send on WhatsApp right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingsPage
        }
        Row(Modifier.fillMaxWidth()) {
            Text("${round.sent.size} of ${round.keys.size} sent", modifier = Modifier.weight(1f))
            Text("WhatsApp", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { round.sent.size.toFloat() / round.keys.size }, modifier = Modifier.fillMaxWidth())
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                round.keys.forEachIndexed { i, key ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    val sent = key in round.sent
                    val count = remember(tick, key) { DailyList.count(ctx, key) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(text = names[key].orEmpty(), size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(names[key].orEmpty(), fontWeight = FontWeight.SemiBold, color = if (sent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text("$count ${if (count == 1) "task" else "tasks"}", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sent) Text("✓ Sent", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        else WhatsAppAction { send(key) }
                    }
                }
            }
        }
        val next = round.left.firstOrNull()
        if (next != null) {
            Button(
                onClick = { send(next) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = WhatsAppInk)
            ) { Text("Send next: ${names[next].orEmpty()} ›", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Text(
                "Opens the chat with the list typed. Press Send, then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("🎉 All sent.", fontWeight = FontWeight.Bold)
        }
        if (round.sms.isNotEmpty()) {
            ChipHeading("SENT BY SMS")
            Text(round.sms.joinToString(", ") { DailyList.nameFor(ctx, it) })
        }
    }
}
