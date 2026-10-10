package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the scheduled text messages on the phone, next to (but separate from) the call-backs.
 * Every change is saved straight away and the matching alarm is set or cancelled.
 */
object SmsStore {
    const val LOG_LIMIT = 30

    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<SmsReminder>>(emptyList())
    val items: StateFlow<List<SmsReminder>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
    }

    private fun prefs() = appCtx.getSharedPreferences("sms_reminders", Context.MODE_PRIVATE)

    private fun load(): List<SmsReminder> {
        val raw = prefs().getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val logArr = o.optJSONArray("log") ?: JSONArray()
                val log = (0 until logArr.length()).mapNotNull { j ->
                    val l = logArr.getJSONObject(j)
                    val status = SmsLogEntry.Status.values().firstOrNull { it.code == l.optString("s") }
                        ?: return@mapNotNull null
                    SmsLogEntry(l.getLong("at"), status, l.optString("d"))
                }
                SmsReminder(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    number = o.optString("number"),
                    message = o.optString("message"),
                    dueAt = o.getLong("dueAt"),
                    createdAt = o.optLong("createdAt"),
                    done = o.optBoolean("done"),
                    doneAt = o.optLong("doneAt"),
                    repeat = Repeat.fromJson(o.optJSONObject("repeat")),
                    seriesStart = o.optLong("seriesStart", o.getLong("dueAt")),
                    occurrenceAt = o.optLong("occurrenceAt", o.getLong("dueAt")),
                    occurrenceCount = o.optInt("occurrenceCount", 1),
                    attempts = o.optInt("attempts"),
                    retryAt = o.optLong("retryAt"),
                    log = log,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<SmsReminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            val log = JSONArray()
            r.log.forEach { l ->
                log.put(JSONObject().put("at", l.at).put("s", l.status.code).put("d", l.detail))
            }
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("number", r.number)
                    .put("message", r.message)
                    .put("dueAt", r.dueAt)
                    .put("createdAt", r.createdAt)
                    .put("done", r.done)
                    .put("doneAt", r.doneAt)
                    .put("repeat", r.repeat?.toJson())
                    .put("seriesStart", r.seriesStart)
                    .put("occurrenceAt", r.occurrenceAt)
                    .put("occurrenceCount", r.occurrenceCount)
                    .put("attempts", r.attempts)
                    .put("retryAt", r.retryAt)
                    .put("log", log)
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
    }

    fun get(id: Long): SmsReminder? = _items.value.firstOrNull { it.id == id }

    private fun replace(updated: SmsReminder) {
        save(_items.value.map { if (it.id == updated.id) updated else it })
    }

    private fun withLog(r: SmsReminder, entry: SmsLogEntry) = r.copy(log = (r.log + entry).takeLast(LOG_LIMIT))

    /** A fresh series starting at [dueAt] (a weekly repeat may move it to its first chosen weekday). */
    private fun series(base: SmsReminder, dueAt: Long, repeat: Repeat?): SmsReminder {
        val first = repeat?.first(Repeat.at(dueAt))?.toInstant()?.toEpochMilli() ?: dueAt
        return base.copy(
            dueAt = first,
            repeat = repeat,
            seriesStart = dueAt,
            occurrenceAt = first,
            occurrenceCount = 1,
            done = false,
            doneAt = 0L,
            attempts = 0,
            retryAt = 0L,
        )
    }

    @Synchronized
    fun add(name: String, number: String, message: String, dueAt: Long, repeat: Repeat?): SmsReminder {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val r = series(
            SmsReminder(
                id = id,
                name = name.trim(),
                number = number.trim(),
                message = message.trim(),
                dueAt = dueAt,
                createdAt = System.currentTimeMillis(),
            ),
            dueAt,
            repeat
        )
        save(_items.value + r)
        SmsScheduler.schedule(appCtx, r)
        return r
    }

    /** Edit who, what, when and the repeat. The history is kept. */
    @Synchronized
    fun edit(id: Long, name: String, number: String, message: String, dueAt: Long, repeat: Repeat?) {
        val cur = get(id) ?: return
        val r = series(
            cur.copy(name = name.trim(), number = number.trim(), message = message.trim()),
            dueAt,
            repeat
        )
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    /** Move the next message to a new time (also brings a finished one back). */
    @Synchronized
    fun reschedule(id: Long, newDueAt: Long) {
        val cur = get(id) ?: return
        val r = cur.copy(dueAt = newDueAt, done = false, doneAt = 0L, attempts = 0, retryAt = 0L)
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    @Synchronized
    fun stopRepeating(id: Long) {
        val cur = get(id) ?: return
        val r = cur.copy(repeat = null)
        replace(r)
        if (!r.done) SmsScheduler.schedule(appCtx, r)
    }

    @Synchronized
    fun delete(id: Long) {
        SmsScheduler.cancel(appCtx, id)
        SmsNotifier.cancel(appCtx, id)
        save(_items.value.filter { it.id != id })
    }

    @Synchronized
    fun addLog(id: Long, entry: SmsLogEntry) {
        val cur = get(id) ?: return
        replace(withLog(cur, entry))
    }

    /**
     * The current message is finished (sent, given up on, skipped or handed to you to send):
     * note it in the history, then move a repeat on to its next time still ahead, or mark a
     * one-off as done.
     */
    @Synchronized
    fun finishOccurrence(id: Long, entry: SmsLogEntry) {
        val cur = get(id) ?: return
        val now = System.currentTimeMillis()
        var r = withLog(cur, entry).copy(attempts = 0, retryAt = 0L)
        if (r.repeat != null) {
            while (true) {
                val at = r.nextOccurrence() ?: break
                r = r.copy(dueAt = at, occurrenceAt = at, occurrenceCount = r.occurrenceCount + 1)
                if (at > now) {
                    replace(r)
                    SmsScheduler.schedule(appCtx, r)
                    return
                }
            }
        }
        SmsScheduler.cancel(appCtx, id)
        replace(r.copy(done = true, doneAt = now))
    }

    /** Sending failed: try again at [retryAt]. */
    @Synchronized
    fun setRetry(id: Long, attempts: Int, retryAt: Long, entry: SmsLogEntry) {
        val cur = get(id) ?: return
        val r = withLog(cur, entry).copy(attempts = attempts, retryAt = retryAt)
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }
}
