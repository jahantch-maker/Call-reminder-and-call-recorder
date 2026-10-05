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
) {
    /** What we show as the headline: the name, else the number, else a placeholder. */
    val title: String
        get() = name.ifBlank { number }.ifBlank { "Unknown caller" }
}
