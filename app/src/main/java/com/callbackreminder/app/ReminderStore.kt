package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps all reminders on the phone (no server, no account).
 * Everything the UI shows comes from [items]; every change is saved straight away
 * and the matching alarm is created or cancelled.
 */
object ReminderStore {
    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
    }

    private fun prefs() = appCtx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private fun load(): List<Reminder> {
        val raw = prefs().getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    number = o.optString("number"),
                    note = o.optString("note"),
                    dueAt = o.getLong("dueAt"),
                    createdAt = o.optLong("createdAt"),
                    done = o.optBoolean("done"),
                    doneAt = o.optLong("doneAt"),
                    repeat = Repeat.fromJson(o.optJSONObject("repeat")),
                    seriesStart = o.optLong("seriesStart", o.getLong("dueAt")),
                    occurrenceAt = o.optLong("occurrenceAt", o.getLong("dueAt")),
                    occurrenceCount = o.optInt("occurrenceCount", 1),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("number", r.number)
                    .put("note", r.note)
                    .put("dueAt", r.dueAt)
                    .put("createdAt", r.createdAt)
                    .put("done", r.done)
                    .put("doneAt", r.doneAt)
                    .put("repeat", r.repeat?.toJson())
                    .put("seriesStart", r.seriesStart)
                    .put("occurrenceAt", r.occurrenceAt)
                    .put("occurrenceCount", r.occurrenceCount)
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
    }

    fun get(id: Long): Reminder? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String, number: String, note: String, dueAt: Long, repeat: Repeat? = null): Reminder {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        // A weekly repeat on chosen days starts on the first of those days.
        val first = repeat?.first(Repeat.at(dueAt))?.toInstant()?.toEpochMilli() ?: dueAt
        val r = Reminder(
            id = id,
            name = name.trim(),
            number = number.trim(),
            note = note.trim(),
            dueAt = first,
            createdAt = System.currentTimeMillis(),
            repeat = repeat,
            seriesStart = dueAt,
            occurrenceAt = first,
        )
        save(_items.value + r)
        Scheduler.schedule(appCtx, r)
        return r
    }

    @Synchronized
    fun markDone(id: Long) {
        val cur = get(id) ?: return
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        val now = System.currentTimeMillis()

        // A repeating reminder moves on to its next time that is still ahead (missed ones count too).
        if (cur.repeat != null && !cur.done) {
            var next = cur
            while (true) {
                val at = next.nextOccurrence() ?: break
                next = next.copy(dueAt = at, occurrenceAt = at, occurrenceCount = next.occurrenceCount + 1)
                if (at > now) {
                    save(_items.value.map { if (it.id == id) next else it })
                    Scheduler.schedule(appCtx, next)
                    return
                }
            }
        }
        save(_items.value.map { if (it.id == id) it.copy(done = true, doneAt = now) else it })
    }

    /**
     * The next repeat arrived while the previous one was still waiting: move on to the latest
     * repeat that is due now, so a forgotten reminder does not stop the series.
     */
    @Synchronized
    fun rollOver(id: Long): Reminder? {
        val cur = get(id)?.takeIf { !it.done && it.repeat != null } ?: return null
        val now = System.currentTimeMillis()
        var r = cur
        while (true) {
            val at = r.nextOccurrence() ?: break
            if (at > now + 1_000L) break
            r = r.copy(dueAt = at, occurrenceAt = at, occurrenceCount = r.occurrenceCount + 1)
        }
        if (r === cur) return cur
        Notifier.cancel(appCtx, id)
        save(_items.value.map { if (it.id == id) r else it })
        return r
    }

    /** Keep the reminder but stop it repeating. */
    @Synchronized
    fun stopRepeating(id: Long) {
        val cur = get(id) ?: return
        val updated = cur.copy(repeat = null)
        save(_items.value.map { if (it.id == id) updated else it })
        Scheduler.cancel(appCtx, id)
        if (!updated.done && updated.dueAt > System.currentTimeMillis()) Scheduler.schedule(appCtx, updated)
    }

    /** Move a reminder to a new time (also brings a finished one back to the to-call list). */
    @Synchronized
    fun reschedule(id: Long, newDueAt: Long) {
        val cur = get(id) ?: return
        val updated = cur.copy(dueAt = newDueAt, done = false, doneAt = 0L)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        save(_items.value.map { if (it.id == id) updated else it })
        Scheduler.schedule(appCtx, updated)
    }

    @Synchronized
    fun delete(id: Long) {
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        save(_items.value.filter { it.id != id })
    }
}
