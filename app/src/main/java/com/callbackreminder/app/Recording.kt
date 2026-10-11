package com.callbackreminder.app

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One recorded call. The audio itself lives in the phone's Recordings folder. */
data class Recording(
    val id: Long,
    val uri: String,
    val startedAt: Long,
    /** A name the user typed when renaming. Wins over the contact name. */
    val title: String = "",
    val name: String = "",
    val number: String = "",
    /** A CallLog.Calls type (incoming / outgoing), or 0 when not known. */
    val type: Int = 0,
    val durationSec: Long = 0L,
    val whatsApp: Boolean = false,
    /** True when the recording finished but contains (almost) no sound. */
    val silent: Boolean = false,
    /** False while the call is still being recorded. */
    val finished: Boolean = false,
    /** Ties the recording to the phone-call session it belongs to (see CallEndReceiver). */
    val sessionKey: Long = 0L,
    val source: String = "",
    val sizeBytes: Long = 0L,
    /** Starred recordings are kept when deleting several at once, and by auto-delete. */
    val starred: Boolean = false,
    /** Pinned recordings stay at the top of the list. */
    val pinned: Boolean = false,
    /** What the call was about, typed by the user. */
    val note: String = "",
) {
    val displayName: String
        get() = title.ifBlank { name.ifBlank { number.ifBlank { "Unknown caller" } } }

    val typeLabel: String
        get() {
            val base = when (type) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                else -> "Call"
            }
            return if (whatsApp) "WhatsApp · ${base.lowercase()}" else base
        }
}

/** Keeps the list of recordings on the phone and deletes the audio files when asked. */
object RecordingStore {
    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        // A recording still marked "recording" at start-up was cut short (the phone killed the app).
        _items.value = load().map { if (it.finished) it else it.copy(finished = true) }
    }

    private fun prefs() = appCtx.getSharedPreferences("recordings", Context.MODE_PRIVATE)

    private fun load(): List<Recording> {
        val raw = prefs().getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recording(
                    id = o.getLong("id"),
                    uri = o.getString("uri"),
                    startedAt = o.getLong("startedAt"),
                    title = o.optString("title"),
                    name = o.optString("name"),
                    number = o.optString("number"),
                    type = o.optInt("type"),
                    durationSec = o.optLong("durationSec"),
                    whatsApp = o.optBoolean("whatsApp"),
                    silent = o.optBoolean("silent"),
                    finished = o.optBoolean("finished"),
                    sessionKey = o.optLong("sessionKey"),
                    source = o.optString("source"),
                    sizeBytes = o.optLong("sizeBytes"),
                    starred = o.optBoolean("starred"),
                    pinned = o.optBoolean("pinned"),
                    note = o.optString("note"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("uri", r.uri)
                    .put("startedAt", r.startedAt)
                    .put("title", r.title)
                    .put("name", r.name)
                    .put("number", r.number)
                    .put("type", r.type)
                    .put("durationSec", r.durationSec)
                    .put("whatsApp", r.whatsApp)
                    .put("silent", r.silent)
                    .put("finished", r.finished)
                    .put("sessionKey", r.sessionKey)
                    .put("source", r.source)
                    .put("sizeBytes", r.sizeBytes)
                    .put("starred", r.starred)
                    .put("pinned", r.pinned)
                    .put("note", r.note)
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
    }

    @Synchronized
    private fun update(id: Long, change: (Recording) -> Recording) {
        save(_items.value.map { if (it.id == id) change(it) else it })
    }

    /** Called the moment a recording starts. */
    @Synchronized
    fun begin(
        uri: String,
        sessionKey: Long,
        whatsApp: Boolean,
        type: Int,
        name: String,
        number: String,
        source: String,
    ): Recording {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val r = Recording(
            id = id,
            uri = uri,
            startedAt = System.currentTimeMillis(),
            name = name,
            number = number,
            type = type,
            whatsApp = whatsApp,
            sessionKey = sessionKey,
            source = source,
        )
        save(_items.value + r)
        return r
    }

    /** Called when recording stops. */
    @Synchronized
    fun finish(id: Long, durationSec: Long, silent: Boolean, sizeBytes: Long, finalName: String?) {
        update(id) {
            it.copy(
                durationSec = durationSec,
                silent = silent,
                sizeBytes = sizeBytes,
                finished = true,
                name = if (!finalName.isNullOrBlank()) finalName else it.name,
            )
        }
    }

    /** Forget a recording (the caller deals with the file). */
    @Synchronized
    fun remove(id: Long) {
        save(_items.value.filter { it.id != id })
    }

    /**
     * After a phone call, the call log tells us who we really talked to.
     * Put that name and number on the recording made during that session.
     */
    @Synchronized
    fun attachCalls(sessionKey: Long, entries: List<CallInfo>) {
        val talked = entries.filter {
            it.type == CallLog.Calls.INCOMING_TYPE || it.type == CallLog.Calls.OUTGOING_TYPE
        }
        if (talked.isEmpty()) return
        val names = talked.map { it.name.ifBlank { it.number } }.filter { it.isNotBlank() }.distinct()
        val joined = names.joinToString(" & ")
        val firstNumber = talked.firstOrNull { it.number.isNotBlank() }?.number.orEmpty()
        val firstType = talked.first().type
        save(
            _items.value.map {
                if (it.sessionKey == sessionKey && !it.whatsApp) {
                    it.copy(
                        name = if (joined.isNotBlank()) joined else it.name,
                        number = if (firstNumber.isNotBlank()) firstNumber else it.number,
                        type = firstType,
                    )
                } else {
                    it
                }
            }
        )
    }

    @Synchronized
    fun rename(id: Long, title: String) {
        update(id) { it.copy(title = title.trim()) }
    }

    @Synchronized
    fun setNote(id: Long, note: String) {
        update(id) { it.copy(note = note.trim()) }
    }

    @Synchronized
    fun setStarred(ids: Set<Long>, starred: Boolean) {
        save(_items.value.map { if (it.id in ids) it.copy(starred = starred) else it })
    }

    @Synchronized
    fun setPinned(ids: Set<Long>, pinned: Boolean) {
        save(_items.value.map { if (it.id in ids) it.copy(pinned = pinned) else it })
    }

    /**
     * A phone-call recording turned out to be a WhatsApp call (some phones report WhatsApp calls
     * as phone calls too). Mark it as WhatsApp so the call log does not overwrite it.
     */
    @Synchronized
    fun markWhatsApp(id: Long, type: Int, name: String) {
        update(id) {
            it.copy(
                whatsApp = true,
                type = if (type != 0) type else it.type,
                name = name.ifBlank { it.name },
            )
        }
    }

    /** Puts the caller's name and number on a WhatsApp recording once the call is over. */
    @Synchronized
    fun setCaller(id: Long, name: String, number: String) {
        update(id) {
            it.copy(name = name.ifBlank { it.name }, number = number.ifBlank { it.number })
        }
    }

    /** The ringing was cut from the start of the file: point at the new file. */
    @Synchronized
    fun replaceAudio(id: Long, uri: String, durationSec: Long, sizeBytes: Long) {
        update(id) { it.copy(uri = uri, durationSec = durationSec, sizeBytes = sizeBytes) }
    }

    fun get(id: Long): Recording? = _items.value.firstOrNull { it.id == id }

    /** Deletes several recordings, skipping starred ones. Returns how many starred were kept. */
    fun deleteUnstarred(ctx: Context, ids: Set<Long>): Int {
        val chosen = _items.value.filter { it.id in ids }
        chosen.filter { !it.starred }.forEach { delete(ctx, it.id) }
        return chosen.count { it.starred }
    }

    /** Deletes the audio file and the list entry. */
    fun delete(ctx: Context, id: Long) {
        val r = _items.value.firstOrNull { it.id == id } ?: return
        RecordingPlayer.stopIf(id)
        RecordingFiles.delete(ctx, r.uri)
        remove(id)
    }

    /** Deletes recordings older than the "keep for" setting (0 = keep forever). Starred ones stay. */
    fun purgeOld(ctx: Context) {
        val days = AppSettings.getString(ctx, AppSettings.REC_KEEP_DAYS, "0").toIntOrNull() ?: 0
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        _items.value
            .filter { it.finished && !it.starred && it.startedAt < cutoff }
            .forEach { delete(ctx, it.id) }
    }
}

/** Creating and deleting the audio files. */
object RecordingFiles {
    private const val FOLDER = "Call Recorder"

    class Output(val uri: Uri, val pfd: android.os.ParcelFileDescriptor?, val file: File?)

    /** A new, empty .m4a file in the phone's Recordings folder (visible in the Files app). */
    fun create(ctx: Context, baseName: String): Output? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$baseName.m4a")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        if (Build.VERSION.SDK_INT >= 31) "Recordings/$FOLDER" else "Music/$FOLDER"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(collection, values) ?: return null
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                if (pfd == null) {
                    ctx.contentResolver.delete(uri, null, null)
                    return null
                }
                Output(uri, pfd, null)
            } else {
                val dir = File(ctx.getExternalFilesDir(null), FOLDER).apply { mkdirs() }
                val f = File(dir, "$baseName.m4a")
                Output(Uri.fromFile(f), null, f)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Makes a finished file visible to other apps (Files, Samsung Voice Recorder, ...). */
    fun publish(ctx: Context, out: Output) {
        if (out.file != null) return
        try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(out.uri, values, null, null)
        } catch (e: Exception) {
            // The file is still saved; it just stays hidden from other apps.
        }
    }

    /** The file's name, e.g. "Call_2026-10-11_10-30-00.m4a". */
    fun displayName(ctx: Context, uri: String): String? {
        return try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") {
                u.path?.let { File(it).name }
            } else {
                ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gives a finished file the name [displayName] (used once the original with that name is gone).
     * Returns where the file now is; if renaming fails it keeps its current name and place.
     */
    fun rename(ctx: Context, out: Output, displayName: String): Uri {
        return try {
            val f = out.file
            if (f != null) {
                val target = File(f.parentFile, displayName)
                if (f.renameTo(target)) Uri.fromFile(target) else out.uri
            } else {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }
                ctx.contentResolver.update(out.uri, values, null, null)
                out.uri
            }
        } catch (e: Exception) {
            out.uri
        }
    }

    fun size(ctx: Context, out: Output): Long {
        return try {
            out.file?.length()
                ?: ctx.contentResolver.openAssetFileDescriptor(out.uri, "r")?.use { it.length }
                ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun delete(ctx: Context, uri: String) {
        try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") {
                u.path?.let { File(it).delete() }
            } else {
                ctx.contentResolver.delete(u, null, null)
            }
        } catch (e: Exception) {
            // Already gone, or the phone would not let us: nothing more to do.
        }
    }
}

/** Plays one recording at a time inside the app. */
object RecordingPlayer {
    data class State(
        val id: Long = -1L,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Playback speed (1.0, 1.25 or 1.5). Remembered between visits. */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun loadSpeed(ctx: Context) {
        _speed.value = AppSettings.getString(ctx, AppSettings.REC_SPEED, "1.0").toFloatOrNull() ?: 1f
    }

    @Synchronized
    fun setSpeed(ctx: Context, value: Float) {
        _speed.value = value
        AppSettings.setString(ctx, AppSettings.REC_SPEED, value.toString())
        val p = player ?: return
        try {
            // Only change the speed while playing: on some phones, setting it while paused starts playback.
            if (p.isPlaying) applySpeed(p)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = p.playbackParams.setSpeed(_speed.value)
        } catch (e: Exception) {
            // This phone cannot change speed for this file: it keeps playing at normal speed.
        }
    }

    /** Play, or pause if this recording is already playing. Returns an error text, or null. */
    @Synchronized
    fun toggle(ctx: Context, r: Recording): String? {
        val p = player
        if (p != null && _state.value.id == r.id) {
            return try {
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.start()
                    applySpeed(p)
                }
                _state.value = _state.value.copy(playing = p.isPlaying, positionMs = p.currentPosition)
                null
            } catch (e: Exception) {
                stop()
                "Could not play this recording."
            }
        }
        stop()
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(ctx.applicationContext, Uri.parse(r.uri))
            mp.prepare()
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                try {
                    it.seekTo(0)
                } catch (e: Exception) {
                    // ignore
                }
            }
            mp.start()
            applySpeed(mp)
            player = mp
            _state.value = State(id = r.id, playing = true, positionMs = 0, durationMs = mp.duration)
            null
        } catch (e: Exception) {
            stop()
            "Could not play this recording. Was the file deleted?"
        }
    }

    @Synchronized
    fun seek(ms: Int) {
        try {
            player?.seekTo(ms)
            _state.value = _state.value.copy(positionMs = ms)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Synchronized
    fun tick() {
        val p = player ?: return
        try {
            _state.value = _state.value.copy(playing = p.isPlaying, positionMs = p.currentPosition)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Synchronized
    fun stopIf(id: Long) {
        if (_state.value.id == id) stop()
    }

    @Synchronized
    fun stop() {
        try {
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        _state.value = State()
    }
}
