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

        /** The follow-up got a new due time; [detail] says where the texts went and the new due time. */
        MOVED("moved", "Moved with the task"),

        /** A line carried over from the call reminder this text replaced; [detail] says what happened. */
        CALL_EVENT("call", "Call reminder"),
        FROM_CALL("fromcall", "Changed from a call reminder to a text"),

        /** You called about this text (Call now in To review); [detail] is the length or "no answer". */
        CALLED("called", "Called"),
    }

    /** Carried over from the call reminder, not something this text did. */
    val fromCall: Boolean
        get() = status == Status.CALL_EVENT || status == Status.FROM_CALL
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
    /**
     * Task number like "26101101" (the date, then that day's serial). Given when the first
     * text goes out and never changed after that; empty until then.
     */
    val code: String = "",
    /** Put the task number (and "Reminder N") in the texts, so a "Done <number>" reply can stop it. */
    val tracked: Boolean = true,
    /** The follow-up this text belongs to, shared with its call (0 = a text on its own). */
    val linkId: Long = 0L,
    /**
     * Texts at set times (a follow-up's "Text him" times): minutes after [dueAt] for each text, first 0.
     * Empty for a single text or a template burst. When set, [burstCount] is its size.
     */
    val slots: List<Int> = emptyList(),
    /** The task's due time, written at the end of each text and updated on every New time; 0 = none. */
    val taskDue: Long = 0L,
    /** "Review later" in To review: hidden there until this time. */
    val reviewLaterUntil: Long = 0L,
    /**
     * The text times as you set them, minutes after [dueAt]. With a burst sent at each time, [slots] holds every
     * text and this keeps the times you picked. Empty on texts made before bursts at each time existed.
     */
    val baseSlots: List<Int> = emptyList(),
    /** A burst sent at each text time: texts per time (1 = no burst) and minutes apart. */
    val groupCount: Int = 1,
    val groupGapMin: Int = 0,
) {
    enum class Review(val code: String) {
        /** The person texted back "Done". */
        REPLIED("replied"),

        /** Every text went out (one text, a burst or the last of a repeat) without a "Done" reply. */
        NO_REPLY("noreply"),

        /** A one-time text never went out: it failed after every retry, or its time passed while the phone was off. */
        NOT_SENT("notsent"),
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

    /** The burst sent at each text time, or null for one text per time. */
    val eachBurst: BurstTemplate?
        get() = when {
            groupCount > 1 -> BurstTemplate(groupCount, groupGapMin)
            slots.isEmpty() && isBurst -> BurstTemplate(burstCount, burstGapMin)
            else -> null
        }

    /** The text times as you set them (without the extra texts of each burst). */
    val baseTimes: List<Long>
        get() = when {
            baseSlots.isNotEmpty() -> baseSlots.map { dueAt + it * 60_000L }
            slots.isNotEmpty() && groupCount <= 1 -> slots.indices.map { textAt(it) }
            else -> listOf(dueAt)
        }

    /** Like "5 texts, 5 min apart" (or "2 texts: 9:00 AM, 2:00 PM"), or null for a single text. */
    val burstLabel: String?
        get() = when {
            !isBurst -> null
            groupCount > 1 && baseSlots.size > 1 ->
                "Burst at each time: ${BurstTemplate(groupCount, groupGapMin).label} · " + baseTimes.joinToString(", ") { Fmt.timeOnly(it) }
            groupCount > 1 -> BurstTemplate(groupCount, groupGapMin).label
            slots.isNotEmpty() -> "$burstCount texts: " + slots.indices.joinToString(", ") { Fmt.timeOnly(textAt(it)) }
            else -> BurstTemplate(burstCount, burstGapMin).label
        }

    /** When text [index] of the current message or burst goes out. */
    fun textAt(index: Int): Long =
        if (slots.isNotEmpty()) dueAt + slots[index.coerceIn(0, slots.size - 1)] * 60_000L
        else dueAt + index * burstGapMin * 60_000L

    /** When the next text of the current burst goes out. */
    val nextTextAt: Long
        get() = textAt(burstIndex)

    /** When the alarm should fire: the waiting retry, else the next text. */
    val alarmAt: Long
        get() = if (retryAt > 0L) retryAt else nextTextAt

    /**
     * The text that actually goes out. Tracked: "Reminder 2 (26101101): …" for a burst, "(26101101) …"
     * for a single text. Not tracked: just the message, as typed.
     */
    fun textFor(index: Int): String {
        val body = if (taskDue > 0L) "$message (Due: ${Fmt.fullDate(taskDue)})" else message
        return when {
            !tracked || code.isEmpty() -> body
            isBurst -> "Reminder ${index + 1} ($code): $body"
            else -> "($code) $body"
        }
    }

    /** The exact reply that stops this reminder: just the task number, e.g. "26101101". */
    val doneReply: String
        get() = code

    val lastLog: SmsLogEntry?
        get() = log.lastOrNull { !it.fromCall }
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
