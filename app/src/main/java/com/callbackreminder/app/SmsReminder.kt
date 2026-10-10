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
        REPLIED_DONE("replied", "Replied Done"),
        NO_REPLY("noreply", "No reply, moved to review"),
        MARKED_DONE("done", "You marked it done"),
        MARKED_NOT_DONE("notdone", "You marked it not done"),
    }
}

/** A text message the app sends for you at a set time, once or on a repeat, as one text or a burst. */
data class SmsReminder(
    val id: Long,
    val name: String,
    val number: String,
    val message: String,
    /** When the current message (or the first text of the current burst) goes out. */
    val dueAt: Long,
    val createdAt: Long,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    val repeat: Repeat? = null,
    val seriesStart: Long = dueAt,
    val occurrenceAt: Long = dueAt,
    val occurrenceCount: Int = 1,
    /** Failed tries for the current single text (0 when it has not failed). Bursts don't retry. */
    val attempts: Int = 0,
    /** When the next retry is due, or 0 when no retry is waiting. */
    val retryAt: Long = 0L,
    /** Newest last, capped at [SmsStore.LOG_LIMIT]. */
    val log: List<SmsLogEntry> = emptyList(),
    /** Texts per burst: 1 means a single text each time. */
    val burstCount: Int = 1,
    /** Minutes between the texts of a burst. */
    val burstGapMin: Int = 0,
    /** How many texts of the current burst are already handled (sent, failed or skipped). */
    val burstIndex: Int = 0,
    /** Waiting in the To review tab, and why; null while scheduled or done. */
    val review: Review? = null,
    val reviewAt: Long = 0L,
) {
    enum class Review(val code: String) {
        /** The person texted back "Done". */
        REPLIED("replied"),

        /** Every text went out and the repeat ended without a "Done" reply. */
        NO_REPLY("noreply"),
    }

    val title: String
        get() = name.ifBlank { number }.ifBlank { "Unknown" }

    val isBurst: Boolean
        get() = burstCount > 1

    /** Still sending: not done and not waiting for review. */
    val active: Boolean
        get() = !done && review == null

    fun nextOccurrence(): Long? = repeat
        ?.next(Repeat.at(seriesStart), Repeat.at(occurrenceAt), occurrenceCount)
        ?.toInstant()?.toEpochMilli()

    val repeatLabel: String?
        get() = repeat?.label(Repeat.at(seriesStart))

    /** Like "5 texts, 5 min apart", or null for a single text. */
    val burstLabel: String?
        get() = if (isBurst) BurstTemplate(burstCount, burstGapMin).label else null

    /** When the next text of the current burst goes out. */
    val nextTextAt: Long
        get() = dueAt + burstIndex * burstGapMin * 60_000L

    /** When the alarm should fire: the waiting retry, else the next text. */
    val alarmAt: Long
        get() = if (retryAt > 0L) retryAt else nextTextAt

    /** The text that actually goes out: bursts start with "Reminder N: ". */
    fun textFor(index: Int): String = if (isBurst) "Reminder ${index + 1}: $message" else message

    val lastLog: SmsLogEntry?
        get() = log.lastOrNull()
}

/** A saved burst: how many texts, how many minutes apart. */
data class BurstTemplate(val count: Int, val gapMin: Int) {
    val label: String
        get() = "$count texts, $gapMin min apart"

    companion object {
        const val MAX_TEMPLATES = 20
        val COUNT_RANGE = 2..20
        val GAP_RANGE = 5..120
    }
}
