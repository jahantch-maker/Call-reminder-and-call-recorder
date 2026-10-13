package com.callbackreminder.app

import android.content.Context
import android.provider.CallLog

/**
 * Remembers which task a call belongs to when you tap Call back on a due reminder (the full-screen
 * popup or its notification), or Call now on a call or text in To review, so the after-call popup
 * can ask how that task went.
 */
object FollowUp {
    // The call starts a moment after the tap; anything older belongs to some other call.
    private const val MAX_GAP_MS = 2 * 60_000L

    /** The task a call was about: a call-back reminder or a text. */
    data class Marked(val reminder: Reminder?, val text: SmsReminder?)

    private fun p(c: Context) = c.getSharedPreferences("followup", Context.MODE_PRIVATE)

    fun mark(ctx: Context, r: Reminder) {
        p(ctx).edit()
            .clear()
            .putLong("id", r.id)
            .putString("number", r.number)
            .putLong("at", System.currentTimeMillis())
            .commit()
    }

    /** Call now on a text in To review. */
    fun markText(ctx: Context, r: SmsReminder) {
        p(ctx).edit()
            .clear()
            .putLong("sms", r.id)
            .putString("number", r.number)
            .putLong("at", System.currentTimeMillis())
            .commit()
    }

    /**
     * The task behind the call session that began at [sessionStart], or null. Each mark is used once.
     * A call task may be waiting or in To review; a text task must not be done yet.
     */
    fun take(ctx: Context, sessionStart: Long): Marked? {
        val prefs = p(ctx)
        val id = prefs.getLong("id", -1L)
        val sms = prefs.getLong("sms", -1L)
        val at = prefs.getLong("at", 0L)
        if (id < 0 && sms < 0) return null
        prefs.edit().clear().commit()
        if (at > sessionStart + 30_000L || sessionStart - at > MAX_GAP_MS) return null
        if (id >= 0) {
            val r = ReminderStore.get(id)?.takeIf { !it.done || it.review } ?: return null
            return Marked(r, null)
        }
        SmsStore.init(ctx)
        val t = SmsStore.get(sms)?.takeIf { !it.done } ?: return null
        return Marked(null, t)
    }

    /**
     * Puts the follow-up call first in the popup queue, tagged with its task. If the phone did not log
     * the call (yet), it is added with the task's own name and number.
     */
    fun attach(marked: Marked, entries: List<CallInfo>, queue: List<CallInfo>): List<CallInfo> {
        val name = marked.reminder?.name ?: marked.text?.name.orEmpty()
        val number = marked.reminder?.number ?: marked.text?.number.orEmpty()
        return putFirst(name, number, entries, queue) {
            it.copy(followUpId = marked.reminder?.id ?: -1L, textTaskId = marked.text?.id ?: -1L)
        }
    }

    /** The outgoing call to [number] in this session (or a stand-in), changed by [tag], first in the queue. */
    internal fun putFirst(
        name: String,
        number: String,
        entries: List<CallInfo>,
        queue: List<CallInfo>,
        tag: (CallInfo) -> CallInfo,
    ): List<CallInfo> {
        val k = RecentPeople.key(number)
        val call = entries.lastOrNull {
            it.type == CallLog.Calls.OUTGOING_TYPE && k.isNotEmpty() && RecentPeople.key(it.number) == k
        }
        val tagged = (call ?: CallInfo(-1L, number, name, CallLog.Calls.OUTGOING_TYPE, 0L, System.currentTimeMillis()))
            .let { tag(it.copy(name = it.name.ifBlank { name })) }
        val rest = queue.filter { it.id != tagged.id && !(it.id < 0 && it.type == CallLog.Calls.OUTGOING_TYPE) }
        return listOf(tagged) + rest
    }
}

/**
 * "Call again" on the after-call popup: keeps what was typed and which task it was about, so the popup
 * comes back after the next call to the same person with everything still there.
 */
object CallAgain {
    private const val MAX_GAP_MS = 2 * 60_000L

    private fun p(c: Context) = c.getSharedPreferences("call_again", Context.MODE_PRIVATE)

    fun mark(ctx: Context, call: CallInfo, name: String, note: String) {
        val earlier = call.roundDurations.joinToString(",")
        p(ctx).edit()
            .clear()
            .putString("number", call.number)
            .putString("name", name)
            .putString("note", note)
            .putString("earlier", earlier)
            .putLong("followUp", call.followUpId)
            .putLong("textTask", call.textTaskId)
            .putLong("at", System.currentTimeMillis())
            .commit()
    }

    /** Calls the person again; the popup comes back after the call with the same note and task. */
    fun dialAgain(ctx: Context, call: CallInfo, name: String, note: String) {
        mark(ctx, call, name, note)
        dial(ctx, call.number)
    }

    /**
     * If this session is the "Call again" call, puts it first in [queue] with the kept note, task and
     * earlier call lengths. Otherwise returns null. Each mark is used once.
     */
    fun take(ctx: Context, sessionStart: Long, entries: List<CallInfo>, queue: List<CallInfo>): List<CallInfo>? {
        val prefs = p(ctx)
        val number = prefs.getString("number", null) ?: return null
        val at = prefs.getLong("at", 0L)
        val name = prefs.getString("name", "").orEmpty()
        val note = prefs.getString("note", "").orEmpty()
        val earlier = prefs.getString("earlier", "").orEmpty()
        val followUp = prefs.getLong("followUp", -1L)
        val textTask = prefs.getLong("textTask", -1L)
        prefs.edit().clear().commit()
        if (at > sessionStart + 30_000L || sessionStart - at > MAX_GAP_MS) return null
        return FollowUp.putFirst(name, number, entries, queue) {
            it.copy(prefillNote = note, earlier = earlier, followUpId = followUp, textTaskId = textTask)
        }
    }

    /** Like "called twice · 4 min + 1 min", or null after a single call. */
    fun roundLabel(call: CallInfo): String? {
        val all = call.roundDurations
        if (all.size < 2) return null
        val times = if (all.size == 2) "twice" else "${all.size} times"
        return "called $times · " + all.joinToString(" + ") { if (it > 0) Fmt.duration(it) else "no answer" }
    }
}
