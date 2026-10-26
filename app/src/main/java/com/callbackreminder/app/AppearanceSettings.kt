package com.callbackreminder.app

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppearanceUi {
    fun summary(c: Context): String {
        val mode = when (Appearance.mode(c)) {
            Appearance.LIGHT -> "Light"
            Appearance.DARK -> if (Appearance.pureBlack(c)) "Dark (pure black)" else "Dark"
            else -> "System"
        }
        val a = Appearance.accent(c)
        val accent = if (a == Appearance.WALLPAPER) "wallpaper colours" else ACCENTS[a].name
        return "$mode · $accent"
    }
}

/** Settings › Appearance: theme with small previews, accent colour, Pure black. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val v by Appearance.version.collectAsState()
    val mode = v.let { Appearance.mode(ctx) }
    val accent = v.let { Appearance.accent(ctx) }
    val black = v.let { Appearance.pureBlack(ctx) }

    SettingsPage(title = "Appearance", onBack = onBack) {
        SectionCard(title = "Theme", subtitle = "System follows your phone. The change fades in smoothly.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice(Icons.Rounded.Contrast, "System", mode == Appearance.SYSTEM, Modifier.weight(1f), split = true, Color(0xFFF3F4FA), Color.White) {
                    Appearance.setMode(ctx, Appearance.SYSTEM)
                }
                ThemeChoice(Icons.Rounded.LightMode, "Light", mode == Appearance.LIGHT, Modifier.weight(1f), split = false, Color(0xFFF3F4FA), Color.White) {
                    Appearance.setMode(ctx, Appearance.LIGHT)
                }
                ThemeChoice(Icons.Rounded.DarkMode, "Dark", mode == Appearance.DARK, Modifier.weight(1f), split = false, Color(0xFF0C0E15), Color(0xFF171A24)) {
                    Appearance.setMode(ctx, Appearance.DARK)
                }
            }
        }
        SectionCard(title = "Accent colour", subtitle = "Buttons, the selected tab and highlights use it.") {
            val items = ACCENTS.indices.toList() + if (Appearance.wallpaperSupported()) listOf(Appearance.WALLPAPER) else emptyList()
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { i ->
                        AccentDot(i, selected = i == accent, modifier = Modifier.weight(1f)) { Appearance.setAccent(ctx, i) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SectionCard(title = "Dark mode", subtitle = "Pure black saves battery on OLED screens.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pure black", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = black, onCheckedChange = { Appearance.setPureBlack(ctx, it) })
            }
        }
        val size = v.let { Appearance.displaySize(ctx) }
        SectionCard(title = "Display size", subtitle = "This app only. Smaller fits more tasks on each screen.") {
            BoxTabs(Appearance.SIZES.map { it.first.replace(" ", "\n") }, size, { Appearance.setDisplaySize(ctx, it) }, compact = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SizePreview("Home", Modifier.weight(1f)) { PreviewHome() }
                SizePreview("Follow-ups", Modifier.weight(1f)) { PreviewFollowUps() }
            }
        }
    }
}

@Composable
private fun ThemeChoice(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    split: Boolean,
    bg: Color,
    card: Color,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val edge by animateColorAsState(if (selected) cs.primary else cs.outlineVariant, tween(250), label = "edge")
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, edge, RoundedCornerShape(14.dp))
                .background(
                    if (split) Brush.horizontalGradient(0.5f to bg, 0.5f to Color(0xFF0C0E15))
                    else Brush.linearGradient(listOf(bg, bg))
                )
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(36.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary))
                repeat(2) {
                    Box(
                        Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(6.dp)).background(
                            if (split) Brush.horizontalGradient(0.5f to card, 0.5f to Color(0xFF171A24))
                            else Brush.linearGradient(listOf(card, card))
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) cs.primary else cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccentDot(index: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val wall = index == Appearance.WALLPAPER
    val a = if (wall) null else ACCENTS[index]
    val brush = if (a != null) Brush.linearGradient(listOf(Color(a.light), Color(a.light2)))
    else Brush.sweepGradient(listOf(Color(0xFF3E7D32), Color(0xFF66A35A), Color(0xFFE8C468), Color(0xFF3E7D32)))
    val grow by animateFloatAsState(if (selected) 1f else 0.88f, tween(220), label = "grow")
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .scale(grow)
                .border(if (selected) 2.5.dp else 0.dp, if (selected) cs.onSurface else Color.Transparent, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
            else if (wall) Icon(Icons.Rounded.Wallpaper, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            if (wall) "Wallpaper" else a!!.name,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A live mini phone at the chosen display size: the real cards, drawn at half size inside the frame, so a bigger
 * size shows fewer of them (just like the app).
 */
@Composable
private fun SizePreview(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val d = androidx.compose.ui.platform.LocalDensity.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(cs.background)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * 0.5f, d.fontScale)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
        Text(label, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PreviewHome() {
    Text("Good morning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("3" to Tones.done, "6" to Tones.late, "4" to Tones.amber).forEach { (n, t) ->
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(t.bg), contentAlignment = Alignment.Center) {
                Text(n, color = t.fg, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
        }
    }
    Text("NEXT UP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PreviewCard("Tile order confirm", "Today 2:00 PM", "Ali", TimeIcons.CALL)
    PreviewCard("Send delivery photos", "Today 4:10 PM", "Bilal", TimeIcons.TEXT)
    PreviewCard("Cement stock count", "Tomorrow 11 AM", "Basharat", TimeIcons.CALL)
}

@Composable
private fun PreviewFollowUps() {
    Text("Follow-ups", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    BoxTabs(listOf("Open · 12", "Done · 40"), 0, {})
    PreviewCard("Steel bar cutting list", "Mon 5 PM", "Hasnain", TimeIcons.CALL)
    PreviewCard("Plaster 2nd floor", "Today 6 PM", "Basharat", TimeIcons.CALL)
    PreviewCard("Send rate list", "Today 9 AM", "Ilyas", TimeIcons.TEXT)
    PreviewCard("Door frames advance", "Sun 11 AM", "Bilal", TimeIcons.CALL)
}

@Composable
private fun PreviewCard(title: String, time: String, person: String, icons: TimeIcons) =
    TaskCard(title = title, done = false, onTick = {}, time = time, onClick = {}, timeIcons = icons, person = person, pos = RowPos.SINGLE)
