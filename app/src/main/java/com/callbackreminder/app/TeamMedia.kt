package com.callbackreminder.app

import androidx.compose.material.icons.rounded.Close
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Voice notes and photos for team tasks. On the free Firebase plan they live inside the message itself, so they
 * are kept small: voice is mono AAC at 24 kbps (2 min ≈ 360 KB), photos are shrunk to about 150 KB.
 */
object TeamMedia {
    const val MAX_VOICE_MS = 120_000
    private const val PHOTO_BYTES = 150_000
    private const val PHOTO_SIDE = 1280

    private fun dir(c: Context) = File(c.cacheDir, "team").apply { mkdirs() }

    // ------------------------------------------------------------ recording

    // A note can be paused and continued, so it is recorded in parts. ADTS AAC parts simply join end to end.
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private val parts = mutableListOf<ByteArray>()
    private var partsMs = 0L
    /** Loudness every 100 ms while recording (0-9), for the note's wave. */
    private val levels = mutableListOf<Int>()
    /** The waves of the last few notes, by length, read when the note is sent ([waveFor]). */
    private val waves = LinkedHashMap<Long, String>()

    /** Starts a new voice note. False when the microphone couldn't start (e.g. a call is being recorded). */
    fun startRecording(c: Context): Boolean {
        // Recording a note stops any voice note or call recording that is playing.
        stopPlaying()
        RecordingPlayer.stop()
        cancelRecording()
        return startPart(c)
    }

    /** 🎙 Record more on the end of a paused note. */
    fun resumeRecording(c: Context): Boolean = if (recorder != null) true else startPart(c)

    private fun startPart(c: Context): Boolean {
        val left = MAX_VOICE_MS - partsMs
        if (left < 1000L) return false
        return try {
            val f = File(dir(c), "voice_${System.currentTimeMillis()}.aac")
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(left.toInt())
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            recStart = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            recorder?.let { runCatching { it.release() } }
            recorder = null
            recFile?.delete()
            recFile = null
            false
        }
    }

    val recording: Boolean get() = recorder != null
    /** Recorded so far, all parts. */
    fun elapsedMs(): Long = (partsMs + if (recorder != null) System.currentTimeMillis() - recStart else 0L).coerceAtMost(MAX_VOICE_MS.toLong())
    fun hasRecorded(): Boolean = parts.isNotEmpty() || recorder != null

    /** Called every 100 ms while recording: one bar of the wave. */
    fun sampleLevel() {
        val a = runCatching { recorder?.maxAmplitude ?: return }.getOrNull() ?: return
        // maxAmplitude is 0..32767; a square root makes quiet speech still visible.
        levels += (kotlin.math.sqrt(a / 32767.0) * 9.0 + 0.5).toInt().coerceIn(0, 9)
    }

    /** The bars recorded so far, oldest first (for the live wave). */
    fun liveLevels(): List<Int> = levels.toList()

    /** ⏸ Pause: closes the current part (it can be played or continued). */
    fun pauseRecording() {
        val r = recorder ?: return
        val f = recFile
        val ms = System.currentTimeMillis() - recStart
        recorder = null
        recFile = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val bytes = if (ok) f?.let { runCatching { it.readBytes() }.getOrNull() } else null
        f?.delete()
        if (bytes != null && bytes.isNotEmpty()) {
            parts += bytes
            partsMs = (partsMs + ms).coerceAtMost(MAX_VOICE_MS.toLong())
        }
    }

    /** What is recorded so far (paused), to ▶ listen before sending. */
    fun recordedBytes(): ByteArray? = if (parts.isEmpty()) null else parts.fold(ByteArray(0)) { acc, b -> acc + b }

    /** Stops and returns the note and its length, or null when it was too short (under a second) or failed. */
    fun stopRecording(): Pair<ByteArray, Long>? {
        pauseRecording()
        val bytes = recordedBytes()
        val dur = partsMs
        val wave = wave(levels)
        clear()
        if (bytes == null || dur < 1000L) return null
        waves[dur] = wave
        while (waves.size > 10) waves.remove(waves.keys.first())
        return bytes to dur
    }

    fun cancelRecording() {
        recorder?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        recorder = null
        recFile?.delete()
        recFile = null
        clear()
    }

    private fun clear() {
        parts.clear()
        partsMs = 0L
        levels.clear()
    }

    /** The wave of a note just recorded with this length ("" when unknown). */
    fun waveFor(durMs: Long): String = waves[durMs].orEmpty()

    /** [WAVE_BARS] bars, each 1-9, scaled so the loudest bar is full height. */
    private fun wave(l: List<Int>): String {
        if (l.isEmpty()) return ""
        val bars = (0 until WAVE_BARS).map { i ->
            val from = i * l.size / WAVE_BARS
            val to = maxOf(from + 1, (i + 1) * l.size / WAVE_BARS).coerceAtMost(l.size)
            l.subList(from.coerceAtMost(l.size - 1), to).maxOrNull() ?: 0
        }
        val top = (bars.maxOrNull() ?: 0).coerceAtLeast(1)
        return bars.joinToString("") { ((it * 9 + top - 1) / top).coerceIn(1, 9).toString() }
    }

    const val WAVE_BARS = 32

    /** Bar heights (0.1-1) for drawing: the note's own wave, else an even, gentle one (older notes). */
    fun bars(wave: String, id: String): List<Float> {
        if (wave.length >= 4) return wave.map { ((it - '0').coerceIn(1, 9)) / 9f }
        var x = id.hashCode()
        return (0 until WAVE_BARS).map { x = x * 1103515245 + 12345; 0.35f + ((x ushr 16) % 30) / 100f }
    }

    // ------------------------------------------------------------ playing

    /** The voice note loaded now, playing or paused (null when none). */
    val playing = MutableStateFlow<String?>(null)
    val paused = MutableStateFlow(false)
    private var player: MediaPlayer? = null

    /** 1× → 1.5× → 2×, remembered. */
    val SPEEDS = listOf(1f, 1.5f, 2f)
    val speed = MutableStateFlow(1f)
    private fun prefs(c: Context) = c.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)
    fun loadSpeed(c: Context) { speed.value = prefs(c).getFloat("speed", 1f).takeIf { it in SPEEDS } ?: 1f }

    fun cycleSpeed(c: Context) {
        val next = SPEEDS[(SPEEDS.indexOf(speed.value) + 1) % SPEEDS.size]
        speed.value = next
        prefs(c).edit().putFloat("speed", next).apply()
        player?.takeIf { !paused.value }?.let { applySpeed(it) }
    }

    private fun applySpeed(p: MediaPlayer) {
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed.value) }
    }

    /** ▶ plays [id]; tapping again pauses, then goes on from there. */
    fun play(c: Context, id: String, bytes: ByteArray) {
        val p = player
        if (p != null && playing.value == id) {
            if (paused.value) { runCatching { p.start(); applySpeed(p) }; paused.value = false }
            else { runCatching { p.pause() }; paused.value = true }
            return
        }
        start(c, id, bytes, 0f)
    }

    /**
     * Moves the dot to [frac] (0-1) of [id]. It never starts a note that isn't playing: another note (or none)
     * loaded means this one is loaded paused at that spot, and ▶ goes on from there.
     */
    fun seek(c: Context, id: String, bytes: ByteArray, frac: Float) {
        val p = player
        if (p != null && playing.value == id) {
            runCatching { p.seekTo((p.duration * frac.coerceIn(0f, 1f)).toInt()) }
            return
        }
        start(c, id, bytes, frac, autoplay = false)
    }

    private fun start(c: Context, id: String, bytes: ByteArray, frac: Float, autoplay: Boolean = true) {
        stopPlaying()
        loadSpeed(c)
        try {
            // Notes from v0.42 on are ADTS AAC (they can be paused and continued), older ones MP4.
            val adts = bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xF0) == 0xF0
            val f = File(dir(c), if (adts) "play.aac" else "play.m4a")
            f.writeBytes(bytes)
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            if (frac > 0f) p.seekTo((p.duration * frac.coerceIn(0f, 1f)).toInt())
            player = p
            if (autoplay) { p.start(); applySpeed(p) }
            paused.value = !autoplay
            playing.value = id
        } catch (e: Exception) {
            stopPlaying()
        }
    }

    /** Where [id] is now, 0-1 (0 when it isn't loaded). */
    fun progress(id: String): Float {
        val p = player ?: return 0f
        if (playing.value != id) return 0f
        return runCatching { if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f }.getOrDefault(0f)
    }

    fun stopPlaying() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        paused.value = false
        playing.value = null
    }

    // ------------------------------------------------------------ photos

    /** A file to hand to Share: the bytes written to the cache, as a content link other apps may read. */
    fun shareUri(c: Context, bytes: ByteArray, ext: String): Uri {
        val f = File(dir(c), "share.$ext")
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    }

    /** Where the camera app saves the next photo. */
    fun cameraUri(c: Context): Uri {
        val f = File(dir(c), "camera.jpg")
        return FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    }

    /** A photo from the camera or gallery, turned around the right way and shrunk to about 150 KB. */
    fun shrink(c: Context, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("No photo")
        val big = maxOf(bmp.width, bmp.height)
        if (big > PHOTO_SIDE) {
            val k = PHOTO_SIDE.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val turn = runCatching {
            c.contentResolver.openInputStream(uri)?.use {
                when (androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (turn != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(turn) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        var q = 80
        var out: ByteArray
        do {
            val s = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, s)
            out = s.toByteArray()
            q -= 10
        } while (out.size > PHOTO_BYTES && q >= 30)
        out
    } catch (e: Exception) {
        null
    }

    fun bitmap(bytes: ByteArray): android.graphics.Bitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** Like "0:24". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Every photo, full screen: pinch or double-tap to zoom, drag when zoomed, swipe for the next one. [onShown] says
 * which one is on screen (it counts as opened for the sender's blue ticks).
 */
@androidx.compose.runtime.Composable
fun PhotoViewer(photos: List<ByteArray>, start: Int = 0, onDismiss: () -> Unit, onShown: (Int) -> Unit = {}) {
    if (photos.isEmpty()) return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
        androidx.compose.runtime.LaunchedEffect(pager.currentPage) { onShown(pager.currentPage) }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
        ) {
            androidx.compose.foundation.pager.HorizontalPager(pager, modifier = androidx.compose.ui.Modifier.fillMaxSize()) { i ->
                ZoomablePhoto(photos[i])
            }
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .statusBarsPadding().padding(8.dp),
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Close",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            if (photos.size > 1) androidx.compose.material3.Text(
                "${pager.currentPage + 1} of ${photos.size} · swipe",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ZoomablePhoto(bytes: ByteArray) {
    val bmp = androidx.compose.runtime.remember(bytes) { TeamMedia.bitmap(bytes)?.asImageBitmap() } ?: return
    var scale by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var offset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.foundation.Image(
        bmp, contentDescription = "Photo", contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1.1f) 1f else 2.5f; if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero })
            }
            .pointerInput(Unit) {
                // Pinch and drag only take the touch while zoomed in, so a plain swipe still turns the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}
