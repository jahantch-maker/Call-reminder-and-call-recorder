package com.callbackreminder.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A follow-up is one task for one person: a call-back reminder for you and/or texts to them, saved
 * together. The call and its texts share a [Reminder.linkId] / [SmsReminder.linkId]; calls and texts made
 * before follow-ups existed have none and work as before.
 */
object FollowUps {
    private fun prefs(c: Context) = c.getSharedPreferences("follow_ups", Context.MODE_PRIVATE)

    /** What "Text him" will send: one text per time, the message, and the task number switch. */
    data class TextPlan(val times: List<Long>, val message: String, val tracked: Boolean)

    /** A new id that ties a call and its texts together. */
    fun newLink(c: Context): Long {
        val id = prefs(c).getLong("nextLink", 1L)
        prefs(c).edit().putLong("nextLink", id + 1).apply()
        return id
    }

    /** "If every text time has passed, send one text right away" (Settings › Text messages). On by default. */
    fun sendNowIfPassed(c: Context): Boolean =
        c.getSharedPreferences("sms_settings", Context.MODE_PRIVATE).getBoolean("send_now_if_passed", true)

    fun setSendNowIfPassed(c: Context, on: Boolean) =
        c.getSharedPreferences("sms_settings", Context.MODE_PRIVATE).edit().putBoolean("send_now_if_passed", on).apply()

    /** The one-time "What moved where" card after this update. */
    fun introSeen(c: Context): Boolean = prefs(c).getBoolean("intro_seen", false)

    fun setIntroSeen(c: Context) = prefs(c).edit().putBoolean("intro_seen", true).apply()

    fun call(linkId: Long): Reminder? =
        if (linkId == 0L) null
        else ReminderStore.items.value.filter { it.linkId == linkId }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } }

    fun text(linkId: Long): SmsReminder? =
        if (linkId == 0L) null
        else SmsStore.items.value.filter { it.linkId == linkId }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } }

    /** Minutes after the first time, for each time (the first is 0). */
    fun slotsFor(times: List<Long>): List<Int> {
        val sorted = times.sorted()
        return sorted.map { ((it - sorted.first()) / 60_000L).toInt() }
    }

    /** Saves a new follow-up. With no due time it waits in To schedule. */
    fun create(
        c: Context,
        name: String,
        number: String,
        task: String,
        due: Long?,
        callOn: Boolean,
        autopilot: Boolean,
        text: TextPlan?,
        starred: Boolean,
    ) {
        if (due == null) {
            ToScheduleStore.add(name, number, task)
            return
        }
        val link = newLink(c)
        if (callOn) {
            val r = ReminderStore.add(name, number, task, due, createdAs = "Follow-up created", starred = starred)
            ReminderStore.setLink(r.id, link, autopilot)
        }
        if (text != null && text.times.isNotEmpty()) {
            val sorted = text.times.sorted()
            SmsStore.add(
                name, number, text.message, sorted.first(), null, null, text.tracked,
                slots = slotsFor(sorted), linkId = link, taskDue = due,
            )
        }
    }

    /** Saves changes to a follow-up opened with Edit: the call and texts are changed, added or removed. */
    fun update(
        c: Context,
        linkId: Long,
        name: String,
        number: String,
        task: String,
        due: Long,
        callOn: Boolean,
        autopilot: Boolean,
        text: TextPlan?,
        starred: Boolean,
    ) {
        val call = call(linkId)
        val sms = text(linkId)
        if (callOn) {
            if (call != null) {
                ReminderStore.edit(call.id, name, number, task, due, call.repeat, starred)
                ReminderStore.setLink(call.id, linkId, autopilot)
            } else {
                val r = ReminderStore.add(name, number, task, due, createdAs = "Follow-up call added", starred = starred)
                ReminderStore.setLink(r.id, linkId, autopilot)
            }
        } else if (call != null && !call.done) {
            ReminderStore.delete(call.id)
        }
        if (text != null && text.times.isNotEmpty()) {
            val sorted = text.times.sorted()
            if (sms != null) {
                SmsStore.edit(
                    sms.id, name, number, text.message, sorted.first(), sms.repeat, null, text.tracked,
                    slots = slotsFor(sorted), taskDue = due,
                )
            } else {
                SmsStore.add(
                    name, number, text.message, sorted.first(), null, null, text.tracked,
                    slots = slotsFor(sorted), linkId = linkId, taskDue = due,
                )
            }
        } else if (sms != null && !sms.done) {
            SmsStore.delete(sms.id)
        }
    }

    /** The call was marked done: its texts stop and go to Done too. */
    fun stopTexts(linkId: Long) {
        SmsStore.items.value.filter { it.linkId == linkId && !it.done }.forEach { SmsStore.markDone(it.id, linked = false) }
    }

    /** A text was marked done: its call reminder is done too. */
    fun finishCall(linkId: Long) {
        ReminderStore.items.value.filter { it.linkId == linkId && !it.done }.forEach { ReminderStore.markDone(it.id, linked = false) }
    }

    /** The person replied "Done <number>". With Autopilot on, the follow-up's call reminder is cancelled. */
    fun onReplied(c: Context, text: SmsReminder) {
        if (text.linkId == 0L) return
        ReminderStore.init(c)
        ReminderStore.items.value.filter { it.linkId == text.linkId && !it.done && it.autopilot }.forEach {
            ReminderStore.logEvent(it.id, "Call cancelled by his “${text.doneReply}” reply (Autopilot)")
            ReminderStore.markDone(it.id, linked = false)
        }
    }

    /**
     * The task got a new due time ([call] is how it was before). Its texts move with it ([moveTexts]): the same
     * clock times, the same number of days later. Times that already passed are skipped; if all passed, one
     * text goes now (when that setting is on). Either way the due time written in the message is updated.
     */
    fun onNewTime(c: Context, call: Reminder, newDue: Long, moveTexts: Boolean) {
        if (call.linkId == 0L) return
        SmsStore.init(c)
        val texts = SmsStore.items.value.filter { it.linkId == call.linkId && !it.done }
        if (texts.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(call.dueAt).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(newDue).atZone(zone).toLocalDate(),
        )
        val now = System.currentTimeMillis()
        val dueText = "due ${Fmt.fullDate(newDue)}"
        texts.forEach { t ->
            if (!moveTexts) {
                SmsStore.setTaskDue(t.id, newDue, "Message updated: $dueText")
                return@forEach
            }
            val start = Instant.ofEpochMilli(t.dueAt).atZone(zone).plusDays(days).toInstant().toEpochMilli()
            val moved = t.copy(dueAt = start)
            val times = (0 until t.burstCount).map { moved.textAt(it) }
            val first = times.indexOfFirst { it > now + 30_000L }
            when {
                first >= 0 -> SmsStore.moveWithTask(
                    t.id, start, first, newDue,
                    "Texts moved to " + times.drop(first).joinToString(", ") { Fmt.dueLabel(it) } + " · message updated: $dueText"
                )
                sendNowIfPassed(c) -> {
                    // Every time has passed: the last text goes out in a few seconds.
                    val last = t.burstCount - 1
                    val offset = times[last] - start
                    SmsStore.moveWithTask(t.id, now + 10_000L - offset, last, newDue, "Text times had passed, so one text goes now · message updated: $dueText")
                }
                else -> SmsStore.setTaskDue(t.id, newDue, "Text times had passed, no text sent · message updated: $dueText")
            }
        }
        ReminderStore.logEvent(call.id, if (moveTexts) "Texts moved with it, message updated: due" else "Message updated: due", newDue)
    }

    /** Everything of this follow-up as it is now, and a way to put it back (for Undo). */
    fun snapshot(linkId: Long): () -> Unit {
        if (linkId == 0L) return {}
        val calls = ReminderStore.items.value.filter { it.linkId == linkId }
        val texts = SmsStore.items.value.filter { it.linkId == linkId }
        return {
            calls.forEach { ReminderStore.restore(it) }
            texts.forEach { SmsStore.restore(it) }
        }
    }
}
