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
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
    }

    fun get(id: Long): Reminder? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String, number: String, note: String, dueAt: Long): Reminder {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val r = Reminder(
            id = id,
            name = name.trim(),
            number = number.trim(),
            note = note.trim(),
            dueAt = dueAt,
            createdAt = System.currentTimeMillis(),
        )
        save(_items.value + r)
        Scheduler.schedule(appCtx, r)
        return r
    }

    @Synchronized
    fun markDone(id: Long) {
        get(id) ?: return
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        val now = System.currentTimeMillis()
        save(_items.value.map { if (it.id == id) it.copy(done = true, doneAt = now) else it })
    }

    /** Move a reminder to a new time (also brings a finished one back to the to-call list). */
    @Synchronized
    fun reschedule(id: Long, newDueAt: Long) {
        val cur = get(id) ?: return
        val updated = cur.copy(dueAt = newDueAt, done = false, doneAt = 0L)
        Notifier.cancel(appCtx, id)
        save(_items.value.map { if (it.id == id) updated else it })
        Scheduler.schedule(appCtx, updated)
    }

    @Synchronized
    fun delete(id: Long) {
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        save(_items.value.filter { it.id != id })
    }
}
