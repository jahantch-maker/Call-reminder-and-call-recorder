package com.callbackreminder.app

/** One follow-up call the user wants to be reminded about. */
data class Reminder(
    val id: Long,
    val name: String,
    val number: String,
    val note: String,
    val dueAt: Long,
    val createdAt: Long,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    /** How it repeats, or null for a one-off reminder. */
    val repeat: Repeat? = null,
    /** When the repeating series began (the rule counts weeks and month days from here). */
    val seriesStart: Long = dueAt,
    /** The scheduled time of the current repeat. [dueAt] can differ from it after a snooze. */
    val occurrenceAt: Long = dueAt,
    /** Which repeat this is, counting from 1 (for "end after N times"). */
    val occurrenceCount: Int = 1,
    /** Done, but waiting for you to check it (the To review chip on Calls). */
    val review: Boolean = false,
    /** Every time this task was moved to a new time after a follow-up call, oldest first. */
    val delays: List<Delay> = emptyList(),
    /** What happened to this task (created, rang, snoozed, called, done...), oldest first. */
    val events: List<CallEvent> = emptyList(),
    /** Starred as important (★): shown on the card and found with the Starred filter. */
    val starred: Boolean = false,
) {
    /** What we show as the headline: the name, else the number, else a placeholder. */
    val title: String
        get() = name.ifBlank { number }.ifBlank { "Unknown caller" }

    /** The time of the repeat after the current one, or null for one-offs and finished series. */
    fun nextOccurrence(): Long? = repeat
        ?.next(Repeat.at(seriesStart), Repeat.at(occurrenceAt), occurrenceCount)
        ?.toInstant()?.toEpochMilli()

    /** Like "Daily" or "Every 2 weeks on Mon, Thu", or null when it does not repeat. */
    val repeatLabel: String?
        get() = repeat?.label(Repeat.at(seriesStart))
}

/** One "Why not done?" entry: the task was due at [wasDue], and on [at] it was moved to [newDue]. */
data class Delay(val at: Long, val wasDue: Long, val newDue: Long, val reason: String)

/**
 * One line in a call task's History. [what] is like "Snoozed to" or "Rang"; [due], when set, is the time it
 * points at and is shown after it ("Snoozed to Today, 4:00 PM"). [note] is shown under it in quotes.
 */
data class CallEvent(val at: Long, val what: String, val due: Long = 0L, val note: String = "") {
    val label: String
        get() = if (due > 0L) "$what ${Fmt.dueLabel(due)}" else what
}

/** Everything in a task's History, newest first: its events plus the "Why not done?" moves. */
fun Reminder.historyLines(): List<CallEvent> =
    (events + delays.map { CallEvent(it.at, "Moved after a call to", it.newDue, it.reason) }).sortedByDescending { it.at }

/**
 * This task's History as lines for the text that replaces it (oldest first), ending with the change itself.
 * Dates are written out in full, since "Today" would be wrong tomorrow.
 */
fun Reminder.historyForText(): List<SmsLogEntry> {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", java.util.Locale.getDefault())
    fun full(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(fmt)
    val lines = historyLines().reversed().map { e ->
        val text = buildString {
            append(e.what)
            if (e.due > 0L) append(' ').append(full(e.due))
            if (e.note.isNotBlank()) append(" · “").append(e.note).append('”')
        }
        SmsLogEntry(e.at, SmsLogEntry.Status.CALL_EVENT, text)
    }
    return lines + SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.FROM_CALL)
}
