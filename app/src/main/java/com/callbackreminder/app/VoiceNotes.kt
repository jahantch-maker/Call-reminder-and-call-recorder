package com.callbackreminder.app

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** How many of the newest loudness bars the live wave keeps (older ones have run off the left edge). */
private const val LIVE_BARS = 80

/** The played part of a wave and its dot (WhatsApp's green). */
private val VoiceGreen = Color(0xFF3DD6B5)
private val RecRed = Color(0xFFFF5A5A)

/**
 * One voice note, WhatsApp style, the same everywhere (Timeline, task cards, Dossier, private notes): ▶ / ⏸, the
 * note's wave with a moving dot (the played part green), the current second, and the speed chip
 * (1× → 1.5× → 2×, remembered). Tap the wave or drag the dot to move the dot: it never starts the note, only ▶
 * does, from where the dot is. [onPlay] runs when the note starts playing (for the sender's 🎙 going blue). [end] sits at the end of the time line (the bubble's time and ticks).
 */
@Composable
fun VoicePlayer(
    id: String,
    bytes: ByteArray?,
    durMs: Long,
    wave: String,
    fg: Color,
    modifier: Modifier = Modifier,
    button: Dp = 36.dp,
    onPlay: () -> Unit = {},
    end: @Composable RowScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val playing by TeamMedia.playing.collectAsState()
    val paused by TeamMedia.paused.collectAsState()
    val speed by TeamMedia.speed.collectAsState()
    LaunchedEffect(Unit) { TeamMedia.loadSpeed(ctx) }
    val loaded = playing == id
    val running = loaded && !paused
    var pos by remember(id) { mutableFloatStateOf(0f) }
    var drag by remember(id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(loaded, paused) {
        if (!loaded) pos = 0f
        while (loaded) {
            pos = TeamMedia.progress(id)
            if (paused) { delay(150); pos = TeamMedia.progress(id); break }
            delay(50)
        }
    }
    val shown = drag ?: if (loaded) pos else 0f
    val bars = remember(id, wave) { TeamMedia.bars(wave, id) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (bytes != null) { TeamMedia.play(ctx, id, bytes); if (!running) onPlay() } },
                enabled = bytes != null,
                modifier = Modifier.size(button).clip(CircleShape).background(cs.primary),
            ) {
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (running) "Pause" else "Play", tint = cs.onPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).height(30.dp)
                    .pointerInput(id, bytes) {
                        detectTapGestures { o ->
                            if (bytes != null) {
                                val f = (o.x / size.width).coerceIn(0f, 1f)
                                TeamMedia.seek(ctx, id, bytes, f)
                                pos = f
                            }
                        }
                    }
                    .pointerInput(id, bytes) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                val f = drag
                                drag = null
                                if (f != null && bytes != null) { TeamMedia.seek(ctx, id, bytes, f); pos = f }
                            },
                            onDragCancel = { drag = null },
                        ) { change, dx ->
                            change.consume()
                            drag = ((drag ?: 0f) + dx / size.width).coerceIn(0f, 1f)
                        }
                    },
            ) {
                WaveBars(bars, shown, fg, dot = true, big = drag != null, modifier = Modifier.fillMaxWidth().height(30.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                speedLabel(speed),
                fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111111),
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color(0xFFEDEFF6))
                    .clickable { TeamMedia.cycleSpeed(ctx) }.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = button + 8.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            val at = drag ?: if (loaded) pos else -1f
            Text(
                if (at >= 0f) TeamMedia.duration((durMs * at).toLong()) else TeamMedia.duration(durMs),
                fontSize = if (drag != null) 12.5.sp else 11.5.sp,
                fontWeight = if (drag != null) FontWeight.Bold else FontWeight.Normal,
                color = if (drag != null) VoiceGreen else fg.copy(alpha = 0.75f),
            )
            Spacer(Modifier.weight(1f))
            end()
        }
    }
}

private fun speedLabel(s: Float): String = when (s) { 1.5f -> "1.5×"; 2f -> "2×"; else -> "1×" }

/** The bars of a wave; the part before [progress] green with the dot on it. */
@Composable
private fun WaveBars(bars: List<Float>, progress: Float, fg: Color, dot: Boolean, big: Boolean, modifier: Modifier, scrollGap: Dp? = null) {
    Canvas(modifier) {
        if (scrollGap != null) {
            // Live recording: every bar keeps its size, the newest comes in on the right and the wave runs to the left.
            val gap = scrollGap.toPx()
            val w = gap * 0.55f
            bars.forEachIndexed { i, h ->
                val x = size.width - (bars.size - i) * gap
                if (x < 0f) return@forEachIndexed
                val bh = (size.height * 0.85f * h).coerceAtLeast(w)
                drawRoundRect(fg.copy(alpha = if (h <= 1f / 9f + 0.01f) 0.45f else 1f), topLeft = Offset(x, (size.height - bh) / 2),
                    size = Size(w, bh), cornerRadius = CornerRadius(w / 2))
            }
            return@Canvas
        }
        val n = bars.size.coerceAtLeast(1)
        val gap = size.width / n
        val w = (gap * 0.55f).coerceAtLeast(2f)
        bars.forEachIndexed { i, h ->
            val x = i * gap + (gap - w) / 2
            val bh = (size.height * 0.85f * h).coerceAtLeast(w)
            drawRoundRect(
                if ((i + 0.5f) / n <= progress) VoiceGreen else fg.copy(alpha = 0.45f),
                topLeft = Offset(x, (size.height - bh) / 2), size = Size(w, bh), cornerRadius = CornerRadius(w / 2),
            )
        }
        if (dot) {
            val r = (if (big) 10.dp else 7.dp).toPx()
            val cx = (progress * size.width).coerceIn(r, size.width - r)
            drawCircle(VoiceGreen.copy(alpha = 0.25f), r + 3.dp.toPx(), Offset(cx, size.height / 2))
            drawCircle(VoiceGreen, r, Offset(cx, size.height / 2))
        }
    }
}

/** A Popup placed full width at the bottom of the window (the hands-free recording bar). */
private object BottomOfWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(0, windowSize.height - popupContentSize.height)
}

/**
 * 🎤 Voice notes, like WhatsApp. Tap once: it records hands free and a bar at the bottom shows 🔴 the time and the
 * live wave with 🗑 Delete · ⏸ Pause · ➤ Send (after ⏸: ▶ listen first, 🎙 record more). Hold: release sends,
 * slide left cancels, slide up 🔒 switches to the hands-free bar. Up to 2 minutes. Asks for the microphone the
 * first time. [onState] is true while recording (hold or bar).
 */
@Composable
internal fun TeamHoldToRecord(onState: (Boolean) -> Unit, onRecorded: (ByteArray, Long) -> Unit, size: Dp = 48.dp) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var active by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var pausedRec by remember { mutableStateOf(false) }
    var slide by remember { mutableFloatStateOf(0f) }
    var ms by remember { mutableLongStateOf(0L) }
    var levels by remember { mutableStateOf(emptyList<Int>()) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Toast.makeText(ctx, "Now tap 🎤 to record", Toast.LENGTH_SHORT).show()
    }
    fun end(send: Boolean) {
        TeamMedia.stopPlaying()
        active = false
        locked = false
        pausedRec = false
        slide = 0f
        onState(false)
        if (!send) { TeamMedia.cancelRecording(); return }
        val r = TeamMedia.stopRecording()
        if (r == null) Toast.makeText(ctx, "Too short: speak a little longer", Toast.LENGTH_SHORT).show()
        else onRecorded(r.first, r.second)
    }
    // A call's ringtone must not get into the note: the ringer is silenced while recording (Settings › Voice notes);
    // without Do Not Disturb access a ringing call pauses the note instead.
    DisposableEffect(active) {
        var stop: () -> Unit = {}
        if (active) {
            val silenced = RingerSilence.begin(ctx)
            if (!silenced && RingerSilence.enabled(ctx)) {
                stop = RingerSilence.watchRinging(ctx) {
                    if (TeamMedia.recording) {
                        TeamMedia.pauseRecording()
                        ms = TeamMedia.elapsedMs()
                        pausedRec = true
                        locked = true
                        Toast.makeText(ctx, "Paused: a call is coming in", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        onDispose { stop(); if (active) RingerSilence.end(ctx) }
    }
    // While recording: one wave bar every 100 ms, the time, and the 2-minute stop (it then waits on the bar).
    LaunchedEffect(active, pausedRec) {
        while (active && !pausedRec) {
            TeamMedia.sampleLevel()
            ms = TeamMedia.elapsedMs()
            levels = TeamMedia.liveLevels()
            if (ms >= TeamMedia.MAX_VOICE_MS) { TeamMedia.pauseRecording(); pausedRec = true; locked = true }
            delay(100)
        }
    }
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 70.dp.toPx() }
    Box(
        Modifier.size(if (active && !locked) size + 12.dp else size).clip(CircleShape)
            .background(if (active) RecRed else VoiceGreen)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (locked) return@awaitEachGesture
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (!TeamMedia.startRecording(ctx)) {
                        Toast.makeText(ctx, "The microphone is busy", Toast.LENGTH_SHORT).show()
                        return@awaitEachGesture
                    }
                    val t0 = System.currentTimeMillis()
                    active = true
                    ms = 0L
                    levels = emptyList()
                    onState(true)
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        val d = c.position - down.position
                        if (kotlin.math.abs(d.x) > 24f || kotlin.math.abs(d.y) > 24f) moved = true
                        slide = d.x.coerceAtMost(0f)
                        if (d.x < -cancelPx) { c.consume(); end(send = false); Toast.makeText(ctx, "Voice note cancelled", Toast.LENGTH_SHORT).show(); return@awaitEachGesture }
                        if (d.y < -lockPx) { c.consume(); locked = true; slide = 0f; return@awaitEachGesture }
                        if (!c.pressed) {
                            c.consume()
                            // A quick tap (no hold) means hands free.
                            if (!moved && System.currentTimeMillis() - t0 < 400L) { locked = true; slide = 0f }
                            else end(send = true)
                            return@awaitEachGesture
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = "Record a voice note", tint = if (active) Color.White else Color(0xFF053D2E))
    }
    if (active && !locked) {
        // Holding: the slide-to-cancel hint and the 🔒 above the finger.
        Popup(popupPositionProvider = BottomOfWindow, properties = PopupProperties(focusable = false)) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                    Text(TeamMedia.duration(ms), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    // The live wave: rises when you speak, flat dots when you are quiet.
                    WaveBars(levels.takeLast(LIVE_BARS).map { it.coerceAtLeast(1) / 9f }, 1f, MaterialTheme.colorScheme.primary,
                        dot = false, big = false, modifier = Modifier.weight(1f).height(26.dp), scrollGap = 5.dp)
                    Text("‹ Slide to cancel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = with(density) { (-slide / 3).toDp() }.coerceAtMost(40.dp)))
                }
            }
        }
    }
    if (locked) {
        RecordingBar(
            ms = ms, levels = levels, paused = pausedRec,
            onDelete = { end(send = false) },
            onPause = { TeamMedia.pauseRecording(); ms = TeamMedia.elapsedMs(); pausedRec = true },
            onMore = {
                TeamMedia.stopPlaying()
                if (TeamMedia.resumeRecording(ctx)) pausedRec = false
                else Toast.makeText(ctx, if (ms >= TeamMedia.MAX_VOICE_MS - 1000) "2 minutes is the most" else "The microphone is busy", Toast.LENGTH_SHORT).show()
            },
            onSend = { end(send = true) },
        )
    }
}

/** The hands-free bar: 🔴 time + live wave (or ▶ listen when paused), then 🗑 · ⏸ / 🎙 · ➤. */
@Composable
private fun RecordingBar(ms: Long, levels: List<Int>, paused: Boolean, onDelete: () -> Unit, onPause: () -> Unit, onMore: () -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Popup(popupPositionProvider = BottomOfWindow, onDismissRequest = onDelete, properties = PopupProperties(focusable = true, dismissOnClickOutside = false)) {
        Surface(color = cs.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    if (paused) {
                        val bytes = remember(ms) { TeamMedia.recordedBytes() }
                        VoicePlayer("rec-preview-$ms", bytes, ms, wave = waveOf(levels), fg = cs.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), button = 32.dp)
                    } else Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                        Spacer(Modifier.width(8.dp))
                        Text(TeamMedia.duration(ms), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.width(44.dp))
                        // The newest bars, sliding in from the right.
                        WaveBars(levels.takeLast(LIVE_BARS).map { it.coerceAtLeast(1) / 9f }, 1f, cs.primary, dot = false, big = false,
                            modifier = Modifier.weight(1f).height(26.dp), scrollGap = 5.dp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(46.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (paused) IconButton(onClick = onMore, modifier = Modifier.size(46.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Record more", tint = RecRed)
                    } else IconButton(onClick = onPause, modifier = Modifier.size(46.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = cs.onSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onSend, modifier = Modifier.size(52.dp).clip(CircleShape).background(VoiceGreen)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color(0xFF053D2E))
                    }
                }
            }
        }
    }
}

/** The live levels as a note's wave (for the ▶ listen preview). */
private fun waveOf(levels: List<Int>): String {
    if (levels.isEmpty()) return ""
    val n = TeamMedia.WAVE_BARS
    val bars = (0 until n).map { i -> levels.getOrElse(i * levels.size / n) { 0 } }
    val top = (bars.maxOrNull() ?: 0).coerceAtLeast(1)
    return bars.joinToString("") { ((it * 9 + top - 1) / top).coerceIn(1, 9).toString() }
}
