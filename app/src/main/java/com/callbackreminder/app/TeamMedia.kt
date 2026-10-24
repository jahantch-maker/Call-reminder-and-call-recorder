package com.callbackreminder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
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

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L

    /** Starts a voice note. False when the microphone couldn't start (e.g. a call is being recorded). */
    fun startRecording(c: Context): Boolean {
        cancelRecording()
        return try {
            val f = File(dir(c), "voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_VOICE_MS)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            recStart = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            cancelRecording()
            false
        }
    }

    val recording: Boolean get() = recorder != null
    fun elapsedMs(): Long = if (recorder != null) System.currentTimeMillis() - recStart else 0L

    /** Stops and returns the note and its length, or null when it was too short (under a second) or failed. */
    fun stopRecording(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val f = recFile
        val dur = (System.currentTimeMillis() - recStart).coerceAtMost(MAX_VOICE_MS.toLong())
        recorder = null
        recFile = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || dur < 1000L) {
            f?.delete()
            return null
        }
        val bytes = runCatching { f.readBytes() }.getOrNull()
        f.delete()
        return bytes?.let { it to dur }
    }

    fun cancelRecording() {
        recorder?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        recorder = null
        recFile?.delete()
        recFile = null
    }

    // ------------------------------------------------------------ playing

    /** The message whose voice note is playing now (null when none). */
    val playing = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null

    fun play(c: Context, id: String, bytes: ByteArray) {
        val was = playing.value
        stopPlaying()
        if (was == id) return
        try {
            val f = File(dir(c), "play.m4a")
            f.writeBytes(bytes)
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            p.start()
            player = p
            playing.value = id
        } catch (e: Exception) {
            stopPlaying()
        }
    }

    fun stopPlaying() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        playing.value = null
    }

    // ------------------------------------------------------------ photos

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
