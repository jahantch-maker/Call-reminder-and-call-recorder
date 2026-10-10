package com.callbackreminder.app

/** One line in a text message's history: what happened when it was due. */
data class SmsLogEntry(
    val at: Long,
    val status: Status,
    val detail: String = "",
) {
    enum class Status(val code: String, val label: String) {
        SENT("sent", "Sent"),
        DELIVERED("delivered", "Delivered"),
        FAILED("failed", "Failed"),
        RETRYING("retry", "Not sent, will retry"),
        SKIPPED("skipped", "Skipped (phone was off)"),
        TAP_TO_SEND("tap", "Waiting for you to tap Send"),
    }
}

/** A text message the app sends for you at a set time, once or on a repeat. */
data class SmsReminder(
    val id: Long,
    val name: String,
    val number: String,
    val message: String,
    /** When the current message goes out. */
    val dueAt: Long,
    val createdAt: Long,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    val repeat: Repeat? = null,
    val seriesStart: Long = dueAt,
    val occurrenceAt: Long = dueAt,
    val occurrenceCount: Int = 1,
    /** Failed tries for the current message (0 when it has not failed). */
    val attempts: Int = 0,
    /** When the next retry is due, or 0 when no retry is waiting. */
    val retryAt: Long = 0L,
    /** Newest last, capped at [SmsStore.LOG_LIMIT]. */
    val log: List<SmsLogEntry> = emptyList(),
) {
    val title: String
        get() = name.ifBlank { number }.ifBlank { "Unknown" }

    fun nextOccurrence(): Long? = repeat
        ?.next(Repeat.at(seriesStart), Repeat.at(occurrenceAt), occurrenceCount)
        ?.toInstant()?.toEpochMilli()

    val repeatLabel: String?
        get() = repeat?.label(Repeat.at(seriesStart))

    /** When the alarm should fire: the waiting retry, else the due time. */
    val alarmAt: Long
        get() = if (retryAt > 0L) retryAt else dueAt

    val lastLog: SmsLogEntry?
        get() = log.lastOrNull()
}
