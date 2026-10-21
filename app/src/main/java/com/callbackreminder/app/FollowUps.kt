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

    /**
     * What "Text <first name>" will send: one text (or a [burst]) at each of [times], the message, the task number
     * switch, and an optional [repeat] for the whole set.
     */
    data class TextPlan(
        val times: List<Long>,
        val message: String,
        val tracked: Boolean,
        val burst: BurstTemplate? = null,
        val repeat: Repeat? = null,
    ) {
        val sorted: List<Long> get() = times.distinct().sorted()

        /** Every text: each time, plus the rest of its burst. */
        fun expanded(): List<Long> {
            val n = burst?.count ?: 1
            val gap = (burst?.gapMin ?: 0) * 60_000L
            return sorted.flatMap { t -> (0 until n).map { t + it * gap } }.distinct().sorted()
        }
    }

    /** The plan of texts a follow-up already has, to edit or keep while it has no time. */
    fun planOf(t: SmsReminder): TextPlan =
        TextPlan(t.baseTimes, t.message, t.tracked, t.eachBurst, t.repeat)

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

    /**
     * The first text time for a task called at [due]: 9:00 AM that day; if that has passed or isn't before the call,
     * the next free half hour from now that is still before the call; else a minute from now.
     */
    fun firstTextTime(due: Long, now: Long): Long? {
        val zone = ZoneId.systemDefault()
        val nine = Instant.ofEpochMilli(due).atZone(zone).toLocalDate().atTime(java.time.LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val half = nextHalfHour(now)
        return when {
            nine in (now + 60_000L) until due -> nine
            half < due -> half
            due > now + 120_000L -> now + 60_000L
            else -> null
        }
    }

    /** First text time with no call: 9:00 AM today if still ahead, else the next half hour. */
    fun firstTextTimeNoCall(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val nine = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(java.time.LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
        return if (nine > now + 60_000L) nine else nextHalfHour(now)
    }

    /** The next :00 or :30 at least a minute from now. */
    fun nextHalfHour(now: Long): Long {
        val step = 30 * 60_000L
        return ((now + 60_000L) / step + 1) * step
    }

    /**
     * Text times for a follow-up that gets [newDue] after waiting with no time: the same clock times, as many
     * days later as the due time moved ([oldDue], 0 = none). Times already passed drop; if none is left, one
     * sensible time is picked.
     */
    fun movedTimes(times: List<Long>, oldDue: Long, newDue: Long, now: Long = System.currentTimeMillis()): List<Long> {
        val zone = ZoneId.systemDefault()
        val days = if (oldDue > 0L) ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(oldDue).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(newDue).atZone(zone).toLocalDate(),
        ) else 0L
        val moved = times.map { Instant.ofEpochMilli(it).atZone(zone).plusDays(days).toInstant().toEpochMilli() }
            .filter { it > now + 30_000L }
        return moved.ifEmpty { listOf(firstTextTime(newDue, now) ?: (now + 60_000L)) }
    }

    private fun addTexts(name: String, number: String, plan: TextPlan, link: Long, due: Long, history: List<SmsLogEntry> = emptyList(), code: String = "") {
        val all = plan.expanded()
        if (all.isEmpty()) return
        val first = all.first()
        SmsStore.add(
            name, number, plan.message, first, plan.repeat, null, plan.tracked,
            history = history,
            slots = slotsFor(all), linkId = link, taskDue = due,
            baseSlots = plan.sorted.map { ((it - first) / 60_000L).toInt() },
            group = plan.burst?.takeIf { it.count > 1 },
            code = code,
        )
    }

    /**
     * Saves a new follow-up. With no due time, or with neither a call nor texts (a quick save of just the task),
     * it waits in To schedule and under No time. [from] is the To schedule item it came from: its history, task
     * number and link carry over, and it leaves To schedule once it has a time.
     */
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
        from: ToScheduleItem? = null,
    ): Boolean {
        val textOn = text != null && text.times.isNotEmpty()
        // A number only when tracked texts will carry it (a daily list gives one later if it lists the task).
        val code = from?.code?.takeIf { it.isNotEmpty() } ?: if (textOn && text!!.tracked) SmsStore.newCode() else ""
        if (due == null || (!callOn && !textOn)) {
            ToScheduleStore.put(
                ToScheduleItem(
                    id = from?.id ?: 0L,
                    name = name,
                    number = number,
                    note = task,
                    createdAt = from?.createdAt ?: 0L,
                    callOn = callOn,
                    textOn = textOn,
                    message = text?.message ?: from?.message.orEmpty(),
                    tracked = text?.tracked ?: from?.tracked ?: true,
                    textTimes = text?.sorted ?: from?.textTimes.orEmpty(),
                    burstCount = text?.burst?.count ?: 1,
                    burstGapMin = text?.burst?.gapMin ?: 0,
                    repeat = text?.repeat,
                    oldDue = due ?: from?.oldDue ?: 0L,
                    autopilot = autopilot,
                    starred = starred,
                    linkId = from?.linkId ?: 0L,
                    code = code,
                    callEvents = from?.callEvents.orEmpty(),
                    textLog = from?.textLog.orEmpty(),
                )
            )
            return false
        }
        val link = from?.linkId?.takeIf { it != 0L } ?: newLink(c)
        if (callOn) {
            val r = ReminderStore.add(
                name, number, task, due,
                createdAs = if (from != null) "Time set" else "Follow-up created",
                earlier = from?.callEvents.orEmpty(),
                starred = starred,
                code = code,
            )
            ReminderStore.setLink(r.id, link, autopilot)
        }
        if (text != null && textOn) addTexts(name, number, text, link, due, from?.textLog.orEmpty(), code)
        from?.let { ToScheduleStore.remove(it.id) }
        return true
    }

    /**
     * Saves changes to a follow-up opened with Edit: the call and texts are changed, added or removed. With no
     * due time (Remove time), or nothing left to remind, it moves to To schedule with all its settings and history.
     */
    fun update(
        c: Context,
        linkId: Long,
        name: String,
        number: String,
        task: String,
        due: Long?,
        callOn: Boolean,
        autopilot: Boolean,
        text: TextPlan?,
        starred: Boolean,
    ): Boolean {
        val call = call(linkId)
        val sms = text(linkId)
        val textOn = text != null && text.times.isNotEmpty()
        val code = call?.code?.takeIf { it.isNotEmpty() } ?: sms?.code?.takeIf { it.isNotEmpty() }
            ?: if (textOn && text!!.tracked) SmsStore.newCode() else ""
        if (due == null || (!callOn && !textOn)) {
            park(c, linkId, name, number, task, callOn, autopilot, text, starred, oldDue = due ?: call?.takeIf { !it.done }?.dueAt ?: sms?.taskDue ?: 0L)
            return false
        }
        if (callOn) {
            if (call != null && !call.done) {
                ReminderStore.edit(call.id, name, number, task, due, call.repeat, starred)
                ReminderStore.setLink(call.id, linkId, autopilot)
                if (call.code != code) ReminderStore.setCode(call.id, code)
            } else {
                val r = ReminderStore.add(name, number, task, due, createdAs = "Follow-up call added", starred = starred, code = code)
                ReminderStore.setLink(r.id, linkId, autopilot)
            }
        } else if (call != null && !call.done) {
            ReminderStore.delete(call.id)
        }
        if (text != null && textOn) {
            val all = text.expanded()
            val first = all.first()
            if (sms != null && !sms.done) {
                SmsStore.edit(
                    sms.id, name, number, text.message, first, text.repeat, null, text.tracked,
                    slots = slotsFor(all), taskDue = due,
                    baseSlots = text.sorted.map { ((it - first) / 60_000L).toInt() },
                    group = text.burst?.takeIf { it.count > 1 },
                )
                if (sms.code != code) SmsStore.setCode(sms.id, code)
            } else {
                addTexts(name, number, text, linkId, due, code = code)
            }
        } else if (sms != null && !sms.done) {
            SmsStore.delete(sms.id)
        }
        return true
    }

    /**
     * Takes the follow-up's time away: its call and texts stop (nothing rings or sends) and it waits in To schedule
     * with everything kept. Returns the To schedule item.
     */
    private fun park(
        c: Context,
        linkId: Long,
        name: String,
        number: String,
        task: String,
        callOn: Boolean,
        autopilot: Boolean,
        text: TextPlan?,
        starred: Boolean,
        oldDue: Long,
    ): ToScheduleItem {
        val calls = ReminderStore.items.value.filter { it.linkId == linkId }
        val texts = SmsStore.items.value.filter { it.linkId == linkId }
        val pendingCall = calls.firstOrNull { !it.done }
        val pendingText = texts.firstOrNull { !it.done }
        val now = System.currentTimeMillis()
        val events = (pendingCall?.events ?: calls.maxByOrNull { it.id }?.events).orEmpty() +
            CallEvent(now, "Time removed, waiting in To schedule")
        val log = (pendingText?.log ?: texts.maxByOrNull { it.id }?.log).orEmpty() +
            SmsLogEntry(now, SmsLogEntry.Status.MOVED, "Time removed: texts paused")
        calls.filter { !it.done }.forEach { ReminderStore.delete(it.id) }
        texts.filter { !it.done }.forEach { SmsStore.delete(it.id) }
        val textOn = text != null && text.times.isNotEmpty()
        return ToScheduleStore.put(
            ToScheduleItem(
                id = 0L,
                name = name,
                number = number,
                note = task,
                createdAt = 0L,
                callOn = callOn,
                textOn = textOn,
                message = text?.message ?: pendingText?.message.orEmpty(),
                tracked = text?.tracked ?: pendingText?.tracked ?: true,
                textTimes = text?.sorted.orEmpty(),
                burstCount = text?.burst?.count ?: 1,
                burstGapMin = text?.burst?.gapMin ?: 0,
                repeat = text?.repeat,
                oldDue = oldDue,
                autopilot = autopilot,
                starred = starred,
                linkId = linkId,
                code = (pendingText ?: texts.maxByOrNull { it.id })?.code?.takeIf { it.isNotEmpty() }
                    ?: (pendingCall ?: calls.maxByOrNull { it.id })?.code?.takeIf { it.isNotEmpty() }
                    ?: SmsStore.newCode(),
                callEvents = events,
                textLog = log,
            )
        )
    }

    /** Remove time on a follow-up row: like Edit › Remove time, keeping its call and texts as they are. */
    fun removeTime(c: Context, call: Reminder?, text: SmsReminder?): ToScheduleItem {
        val link = ensureLink(c, call, text)
        val pendingCall = call?.takeIf { !it.done }
        val pendingText = text?.takeIf { !it.done }
        return park(
            c, link,
            name = call?.name ?: text?.name.orEmpty(),
            number = call?.number ?: text?.number.orEmpty(),
            task = taskOf(call, text),
            callOn = pendingCall != null,
            autopilot = call?.autopilot ?: false,
            text = pendingText?.let { planOf(it) },
            starred = call?.starred ?: false,
            oldDue = pendingCall?.dueAt ?: text?.taskDue?.takeIf { it > 0L } ?: pendingText?.dueAt ?: 0L,
        )
    }

    /** Set time on a To schedule follow-up: its call and texts come back, the texts moved like New time. */
    fun setTime(c: Context, item: ToScheduleItem, due: Long) {
        val plan = if (item.textOn && item.number.isNotBlank()) TextPlan(
            movedTimes(item.textTimes, item.oldDue, due), item.message.ifBlank { item.note }, item.tracked, item.burst, item.repeat
        ) else null
        create(
            c, item.name, item.number, item.note, due,
            callOn = item.callOn && item.number.isNotBlank(),
            autopilot = item.autopilot, text = plan, starred = item.starred, from = item,
        )
    }

    /** The task line of a follow-up: what was written, else the text message. */
    fun taskOf(call: Reminder?, text: SmsReminder?): String =
        call?.note?.takeIf { it.isNotBlank() } ?: text?.message.orEmpty()

    /** An older call or text with no follow-up link gets one (with each other's part, if any), so Edit can add the other part. */
    fun ensureLink(c: Context, call: Reminder?, text: SmsReminder?): Long {
        val existing = call?.linkId?.takeIf { it != 0L } ?: text?.linkId?.takeIf { it != 0L }
        if (existing != null) return existing
        val link = newLink(c)
        call?.let { ReminderStore.setLink(it.id, link, it.autopilot) }
        text?.let { SmsStore.setLink(it.id, link) }
        return link
    }

    /**
     * Move to My tasks: the next step is yours. A One-time task with the same text, person and time is made; the
     * call and texts still waiting stop and go to Done marked "Moved to My tasks". Returns the new task.
     */
    fun moveToMyTasks(c: Context, call: Reminder?, text: SmsReminder?): MyTask {
        MyTaskStore.init(c)
        val pendingCall = call?.takeIf { !it.done }
        val due = pendingCall?.dueAt ?: text?.taskDue?.takeIf { it > 0L } ?: 0L
        val task = MyTaskStore.add(
            title = taskOf(call, text).ifBlank { "Follow up with ${call?.title ?: text?.title.orEmpty()}" },
            note = "Moved from Follow-ups",
            dueAt = due,
            name = call?.name ?: text?.name.orEmpty(),
            number = call?.number ?: text?.number.orEmpty(),
            starred = call?.starred ?: false,
        )
        val link = call?.linkId?.takeIf { it != 0L } ?: text?.linkId?.takeIf { it != 0L }
        val calls = if (link != null) ReminderStore.items.value.filter { it.linkId == link && !it.done } else listOfNotNull(pendingCall)
        val texts = if (link != null) SmsStore.items.value.filter { it.linkId == link && !it.done } else listOfNotNull(text?.takeIf { !it.done })
        calls.forEach {
            if (it.repeat != null) ReminderStore.stopRepeating(it.id)
            ReminderStore.markDone(it.id, linked = false)
            ReminderStore.logEvent(it.id, MOVED_TO_MY_TASKS)
        }
        texts.forEach {
            if (it.repeat != null) SmsStore.stopRepeating(it.id)
            SmsStore.markDone(it.id, linked = false)
            SmsStore.addLog(it.id, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.MOVED, MOVED_TO_MY_TASKS))
        }
        return task
    }

    /** A To schedule follow-up moved to My tasks, with no time. */
    fun moveToMyTasks(c: Context, item: ToScheduleItem): MyTask {
        MyTaskStore.init(c)
        val task = MyTaskStore.add(
            title = item.headline,
            note = "Moved from Follow-ups",
            dueAt = 0L,
            name = item.name,
            number = item.number,
            starred = item.starred,
        )
        ToScheduleStore.remove(item.id)
        return task
    }

    const val MOVED_TO_MY_TASKS = "Moved to My tasks"

    /** Was this call or text finished by Move to My tasks? */
    fun wasMoved(call: Reminder?, text: SmsReminder?): Boolean =
        call?.events?.lastOrNull()?.what == MOVED_TO_MY_TASKS || text?.log?.lastOrNull()?.detail == MOVED_TO_MY_TASKS

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
            ReminderStore.logEvent(it.id, "Call cancelled by the “${text.doneReply}” reply (Autopilot)")
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
