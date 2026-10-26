package com.callbackreminder.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The page that opens when you drop a widget on the home screen, and again when you long-press it
 * (Android 12+) or tap the chip in its header: what it shows, its look, see-through and text size.
 */
class WidgetSetupActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out of a new widget's setup leaves it off the home screen.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        WidgetData.ensure(this)
        val kind = WidgetKind.of(this, widgetId)
        val editing = WidgetPrefs.exists(this, widgetId)
        val start = WidgetPrefs.get(this, widgetId)
        setContent {
            CallbackTheme {
                SetupScreen(kind, editing, start, onCancel = { finish() }, onSave = { save(it) })
            }
        }
    }

    private fun save(cfg: WidgetConfig) {
        WidgetPrefs.set(this, widgetId, cfg)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        WidgetSync.start(application)
        WidgetSync.refresh(this)
        finish()
    }
}

@Composable
private fun SetupScreen(
    kind: WidgetKind?,
    editing: Boolean,
    start: WidgetConfig,
    onCancel: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
) {
    var cfg by remember { mutableStateOf(start) }
    val lists by MyTaskStore.lists.collectAsState()
    val cs = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    (if (editing) "Change " else "Add ") + (kind?.title ?: "widget"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Preview(kind, cfg, if (kind == WidgetKind.MY_TASKS) listLabel(cfg.listId) else WidgetPrefs.filterLabel(cfg.filter))

                when (kind) {
                    WidgetKind.FOLLOW_UPS -> Section("Show") {
                        WidgetPrefs.FILTERS.forEach { (f, label) ->
                            Pick(label, cfg.filter == f) { cfg = cfg.copy(filter = f) }
                        }
                    }
                    WidgetKind.MY_TASKS -> Section("List") {
                        Pick("All lists", cfg.listId == WidgetPrefs.ALL_LISTS) { cfg = cfg.copy(listId = WidgetPrefs.ALL_LISTS) }
                        if (lists.none { it.id == MyTaskStore.UNSORTED }) {
                            Pick("Unsorted", cfg.listId == MyTaskStore.UNSORTED) { cfg = cfg.copy(listId = MyTaskStore.UNSORTED) }
                        }
                        lists.forEach { l -> Pick(l.name, cfg.listId == l.id) { cfg = cfg.copy(listId = l.id) } }
                    }
                    else -> Unit
                }

                Section("Look") {
                    WidgetPrefs.LOOKS
                        .filter { it.first != WidgetPrefs.LOOK_WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                        .forEach { (l, label) -> Pick(label, cfg.look == l) { cfg = cfg.copy(look = l) } }
                }

                Column {
                    Text("See-through · ${cfg.seeThrough}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Slider(
                        value = cfg.seeThrough.toFloat(),
                        onValueChange = { cfg = cfg.copy(seeThrough = (it / 5f).toInt() * 5) },
                        valueRange = 0f..80f,
                    )
                }

                Section("Text size") {
                    WidgetPrefs.TEXT_SIZES.forEach { (t, label) -> Pick(label, cfg.textSize == t) { cfg = cfg.copy(textSize = t) } }
                }

                Text(
                    "You can change these later: long-press the widget, or tap the chip at its top.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                Button(onClick = { onSave(cfg) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (editing) "Save" else "Add widget")
                }
            }
        }
    }
}

private fun listLabel(id: Long) = if (id == WidgetPrefs.ALL_LISTS) "All lists" else MyTaskStore.listName(id)

@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun Pick(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** A small picture of the widget with the chosen look, over a wallpaper-like background. */
@Composable
private fun Preview(kind: WidgetKind?, cfg: WidgetConfig, chip: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val col = remember(cfg.look) { WidgetLook.colors(ctx, cfg) }
    val bg by animateColorAsState(col.bg.copy(alpha = 1f - cfg.seeThrough / 100f), label = "bg")
    val on by animateColorAsState(col.on, label = "on")
    val onVar by animateColorAsState(col.onVar, label = "onVar")
    val row by animateColorAsState(col.row, label = "row")
    val scale by animateFloatAsState(cfg.textScale, label = "scale")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B5BA9), Color(0xFF8E4FA8), Color(0xFFE08A5B))))
            .padding(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind?.title ?: "Widget", color = on, fontWeight = FontWeight.Bold, fontSize = (15 * scale).sp, modifier = Modifier.weight(1f))
                if (kind == WidgetKind.FOLLOW_UPS || kind == WidgetKind.MY_TASKS) {
                    Box(Modifier.clip(CircleShape).background(col.primaryC).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text("$chip ▾", color = col.onPrimaryC, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            listOf("Send the site photos" to "Ali · 3:00 PM", "Confirm cement order" to "Bilal · 4:30 PM").forEach { (task, sub) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(row).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task, color = on, fontSize = (13 * scale).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = onVar, fontSize = (11 * scale).sp, maxLines = 1)
                    }
                    Spacer(Modifier.size(6.dp))
                    Box(Modifier.size(26.dp).clip(CircleShape).background(col.secondaryC), contentAlignment = Alignment.Center) {
                        Text("✓", color = col.onSecondaryC, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
