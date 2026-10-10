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
