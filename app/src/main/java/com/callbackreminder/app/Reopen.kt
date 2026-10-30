package com.callbackreminder.app

import android.content.Context

/**
 * v0.44 ↩ Reopen: a task ticked done by mistake comes back, also long after its Undo bar has gone. When its old time
 * has already passed the caller asks for a new time first ([needsTime]), so it doesn't come back overdue at once.
 * A team task gets a "↩ Reopened" line on its Timeline, so the other person sees it is open again.
 */
object Reopen {
    fun needsTime(at: Long) = at > 0L && at <= System.currentTimeMillis()

    /** A follow-up's call: back to the to-call list, at [newAt] when given. */
    fun call(c: Context, id: Long, newAt: Long?) {
        val r = ReminderStore.get(id) ?: return
        if (newAt != null) ReminderStore.reschedule(id, newAt, "Reopened, new time") else ReminderStore.reopen(id)
        if (r.linkId != 0L) tellTeam(c, TeamLinks.map(c, "given").entries.firstOrNull { it.value == r.linkId.toString() }?.key)
    }

    /** A done text goes back to To review (it has no time of its own to ask for). */
    fun text(id: Long) = SmsStore.reopen(id)

    /** A My task: open again, at [newAt] when given. */
    fun myTask(c: Context, id: Long, newAt: Long?) {
        MyTaskStore.setDone(id, false)
        if (newAt != null) MyTaskStore.setDue(id, newAt)
        tellTeam(c, TeamLinks.map(c, "recv").entries.firstOrNull { it.value == id.toString() }?.key)
    }

    private fun tellTeam(c: Context, teamTaskId: String?) {
        teamTaskId ?: return
        runCatching {
            if (!TeamRepo.activeNow) return
            TeamRepo.tasks.value.firstOrNull { it.id == teamTaskId }?.let { TeamRepo.note(it, "↩ Reopened") }
        }.onFailure { CrashLog.record(c, it, "Reopen · team line") }
    }
}
