package com.callbackreminder.app

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Weekly off day: up to 2 days (or none) when the routine items pause: the review
 * reminder, the morning summary, the Dossier (an off day is never Missed) and the daily lists.
 * Call-back reminders and texts you set for that day still go out. Friday to start with.
 */
object OffDays {
    const val MAX = 2
    private val FRIDAY = 1 shl (DayOfWeek.FRIDAY.value - 1)
    private const val MON_SAT = 0b0111111

    private val _mask = MutableStateFlow(FRIDAY)
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val mask: StateFlow<Int> = _mask.asStateFlow()
    @Volatile private var loaded = false

    private fun p(c: Context) = c.getSharedPreferences("off_days", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        val ctx = c.applicationContext
        val prefs = p(ctx)
        if (!prefs.getBoolean("migrated", false)) {
            // Sunday used to be the built-in day off (Monday to Saturday). The off day is Friday now,
            // so those lists run every day and Friday pauses them instead.
            listOf("daily_list", "morning_summary").forEach { name ->
                val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (sp.getInt("days", MON_SAT) == MON_SAT) sp.edit().putInt("days", Dossier.ALL_DAYS).apply()
            }
            DossierStore.init(ctx)
            DossierStore.sundayToEveryDay()
            prefs.edit().putInt("mask", FRIDAY).putBoolean("migrated", true).apply()
        }
        _mask.value = prefs.getInt("mask", FRIDAY)
        loaded = true
    }

    fun set(c: Context, mask: Int) {
        init(c)
        p(c).edit().putInt("mask", mask).apply()
        _mask.value = mask
        // Move the routine alarms off (or back onto) the changed days.
        ReviewReminder.schedule(c)
        MorningSummary.schedule(c)
        DailyList.schedule(c)
        DossierStore.init(c)
        DossierStore.tasks.value.forEach { TaskAlarms.scheduleDossier(c, it) }
    }

    fun isOff(day: DayOfWeek): Boolean = _mask.value and (1 shl (day.value - 1)) != 0
    fun isOff(date: LocalDate): Boolean = isOff(date.dayOfWeek)

    fun label(mask: Int = _mask.value): String {
        val days = DayOfWeek.values().filter { mask and (1 shl (it.value - 1)) != 0 }
        return if (days.isEmpty()) "No off day"
        else days.joinToString(" + ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
