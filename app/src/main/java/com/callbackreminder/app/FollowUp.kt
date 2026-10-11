package com.callbackreminder.app

import android.content.Context
import android.provider.CallLog

/**
 * Remembers which task a call belongs to when you tap Call back on a due reminder (the full-screen
 * popup or its notification), so the after-call popup can ask how that task went.
 */
object FollowUp {
    // The call starts a moment after the tap; anything older belongs to some other call.
    private const val MAX_GAP_MS = 2 * 60_000L

    private fun p(c: Context) = c.getSharedPreferences("followup", Context.MODE_PRIVATE)

    fun mark(ctx: Context, r: Reminder) {
        p(ctx).edit()
            .putLong("id", r.id)
            .putString("number", r.number)
            .putLong("at", System.currentTimeMillis())
            .commit()
    }

    /** The task behind the call session that began at [sessionStart], or null. Each mark is used once. */
    fun take(ctx: Context, sessionStart: Long): Reminder? {
        val prefs = p(ctx)
        val id = prefs.getLong("id", -1L)
        val at = prefs.getLong("at", 0L)
        if (id < 0) return null
        prefs.edit().clear().commit()
        if (at > sessionStart + 30_000L || sessionStart - at > MAX_GAP_MS) return null
        return ReminderStore.get(id)?.takeIf { !it.done }
    }

    /**
     * Puts the follow-up call first in the popup queue, tagged with its task. If the phone did not log
     * the call (yet), it is added with the task's own name and number.
     */
    fun attach(task: Reminder, entries: List<CallInfo>, queue: List<CallInfo>): List<CallInfo> {
        val k = RecentPeople.key(task.number)
        val call = entries.lastOrNull {
            it.type == CallLog.Calls.OUTGOING_TYPE && k.isNotEmpty() && RecentPeople.key(it.number) == k
        }
        val tagged = (call ?: CallInfo(-1L, task.number, task.name, CallLog.Calls.OUTGOING_TYPE, 0L, System.currentTimeMillis()))
            .let { it.copy(name = it.name.ifBlank { task.name }, followUpId = task.id) }
        val rest = queue.filter { it.id != tagged.id && !(it.id < 0 && it.type == CallLog.Calls.OUTGOING_TYPE) }
        return listOf(tagged) + rest
    }
}
