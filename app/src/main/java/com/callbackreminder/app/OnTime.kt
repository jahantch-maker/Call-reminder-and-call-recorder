package com.callbackreminder.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The on-time mark (v0.47): every finished team task and every own My task (not 🔒 Private) is measured against the
 * FIRST date that was promised, from the moment it was ticked Done. Every later move is counted, so an approved
 * extension or a task pushed every evening still shows as a slip: "✅ On time", "⏱ 2 days late · extended 1×",
 * "⏱ 3 h late · moved 2×". A task with no date at all says "No date", so leaving tasks undated is no way out.
 */
object OnTime {
    /** A minute's grace: ticking Done at the deadline itself is on time. */
    private const val GRACE_MS = 60_000L

    /**
     * The mark's text, or null when there is nothing to say (an open task with a date). [promise] is the first date
     * (0 = none), [word] "extended" for a given task's deadline, "moved" for an own task's time.
     */
    fun label(promise: Long, done: Boolean, doneAt: Long, moves: Int, word: String): String? {
        val tail = if (moves > 0) " · $word $moves×" else ""
        return when {
            promise <= 0L -> "No date"
            !done || doneAt <= 0L -> null
            doneAt - promise <= GRACE_MS -> "✅ On time$tail"
            else -> "⏱ ${late(promise, doneAt)} late$tail"
        }
    }

    fun chip(label: String?): RowChip? = label?.let {
        RowChip(it, when {
            it.startsWith("✅") -> ChipTone.GREEN
            it.startsWith("⏱") -> ChipTone.RED
            else -> ChipTone.MUTED
        })
    }

    /** "2 days" once it ran into another day, else "3 h" / "20 min". */
    private fun late(promise: Long, doneAt: Long): String {
        val z = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(promise).atZone(z).toLocalDate(), Instant.ofEpochMilli(doneAt).atZone(z).toLocalDate()
        )
        val min = (doneAt - promise) / 60_000L
        return when {
            days >= 1L -> if (days == 1L) "1 day" else "$days days"
            min >= 60L -> "${min / 60} h"
            else -> "${maxOf(1L, min)} min"
        }
    }

    // ---- Team tasks ----

    /** The deadline it is measured against: the first one he set (older tasks only know the current one). */
    fun promise(t: TeamTask): Long = t.firstDue.takeIf { it > 0L } ?: t.due

    fun finished(t: TeamTask): Boolean = t.status == Team.DONE || t.status == Team.CHECKED

    /** The mark if he ticks it Done at [doneAt]. */
    fun label(t: TeamTask, doneAt: Long): String? = label(promise(t), true, doneAt, t.extCount, "extended")

    fun chip(t: TeamTask): RowChip? = if (finished(t)) chip(label(t, t.doneAt)) else null

    // ---- Own My tasks ----

    fun promise(t: MyTask): Long = t.firstDueAt.takeIf { it > 0L } ?: t.dueAt

    fun label(t: MyTask): String? = label(promise(t), t.done, t.doneAt, t.moves, "moved")

    /** The mark of a finished My task as text (search): its team mark when it was given to him, else his own. */
    fun doneText(c: Context, t: MyTask): String? {
        if (!t.done || !TeamRepo.activeNow) return null
        TeamUi.taskOfMyTask(c, t.id, TeamRepo.tasks.value)?.let { return chip(it)?.text }
        return if (TeamLinks.isPrivate(c, t.id)) null else label(t)
    }
}
