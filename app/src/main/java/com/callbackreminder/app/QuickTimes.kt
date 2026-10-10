package com.callbackreminder.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One quick-time choice the user picked in Settings. It is turned into a real time
 * (with [toOption]) each time the popup or the Snooze menu opens.
 */
sealed class QuickTime {
    /** "After a while": now + amount of minutes, hours or days. */
    data class After(val amount: Int, val unit: Span) : QuickTime()

    /** "On a day": [daysAhead] days from today (0 = today) at [hour]:[minute], or at the current time when [hour] is null. */
    data class OnDay(val daysAhead: Int, val hour: Int?, val minute: Int = 0) : QuickTime()

    enum class Span(val code: String, val minutes: Long, val max: Int) {
        MINUTES("m", 1, 999),
        HOURS("h", 60, 99),
        DAYS("d", 24 * 60, 30),
    }

    /** Short name shown on the tile, like "15 min", "2 hours" or "Tomorrow". */
    val title: String
        get() = when (this) {
            is After -> when (unit) {
                Span.MINUTES -> "$amount min"
                Span.HOURS -> if (amount == 1) "1 hour" else "$amount hours"
                Span.DAYS -> if (amount == 1) "1 day" else "$amount days"
            }
            is OnDay -> dayName(daysAhead)
        }

    /** Longer text for the Settings list, like "Tomorrow at 10:00 AM". */
    val description: String
        get() = when (this) {
            is After -> "In $title"
            is OnDay -> if (hour == null) "${dayName(daysAhead)}, same time as now"
            else "${dayName(daysAhead)} at ${clockText(hour, minute)}"
        }

    fun toOption(now: Long = System.currentTimeMillis()): QuickOption {
        val zone = ZoneId.systemDefault()
        val nowZ = Instant.ofEpochMilli(now).atZone(zone).withSecond(0).withNano(0)
        val today = nowZ.toLocalDate()
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val dayClock = DateTimeFormatter.ofPattern("EEE, h:mm a", Locale.getDefault())

        return when (this) {
            is After -> {
                val ms = now + amount * unit.minutes * 60_000L
                val dt = Instant.ofEpochMilli(ms).atZone(zone)
                val text = when (dt.toLocalDate()) {
                    today -> dt.format(clock)
                    today.plusDays(1) -> "Tmrw, ${dt.format(clock)}"
                    else -> dt.format(dayClock)
                }
                QuickOption(title, ms, text)
            }
            is OnDay -> {
                var dt = if (hour == null) nowZ.plusDays(daysAhead.toLong())
                else today.plusDays(daysAhead.toLong()).atTime(hour, minute).atZone(zone)
                // "Today at 9 AM" when it's already past 9 AM moves to tomorrow.
                if (!dt.isAfter(nowZ)) dt = dt.plusDays(1)
                val date = dt.toLocalDate()
                val text = when {
                    daysAhead <= 1 && date == today.plusDays(daysAhead.toLong()) -> dt.format(clock)
                    date == today.plusDays(1) -> "Tmrw, ${dt.format(clock)}"
                    else -> dt.format(dayClock)
                }
                QuickOption(title, dt.toInstant().toEpochMilli(), text)
            }
        }
    }

    fun encode(): String = when (this) {
        is After -> "A:$amount:${unit.code}"
        is OnDay -> "D:$daysAhead:${hour ?: "now"}:$minute"
    }

    companion object {
        const val MAX = 12
        const val MAX_DAYS_AHEAD = 7

        fun dayName(daysAhead: Int) = when (daysAhead) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> "In $daysAhead days"
        }

        fun clockText(hour: Int, minute: Int): String =
            java.time.LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

        fun decode(s: String): QuickTime? {
            val p = s.split(":")
            return try {
                when (p.getOrNull(0)) {
                    "A" -> {
                        val unit = Span.entries.first { it.code == p[2] }
                        val amount = p[1].toInt()
                        if (amount in 1..unit.max) After(amount, unit) else null
                    }
                    "D" -> {
                        val days = p[1].toInt()
                        val hour = if (p[2] == "now") null else p[2].toInt()
                        val minute = p[3].toInt()
                        if (days in 0..MAX_DAYS_AHEAD && (hour == null || hour in 0..23) && minute in 0..59) {
                            OnDay(days, hour, minute)
                        } else null
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        /** The 12 choices the app has always shipped with. */
        val DEFAULTS: List<QuickTime> = listOf(
            After(15, Span.MINUTES),
            After(30, Span.MINUTES),
            After(45, Span.MINUTES),
            After(1, Span.HOURS),
            After(2, Span.HOURS),
            After(3, Span.HOURS),
            OnDay(1, 10),
            OnDay(1, 16),
            OnDay(1, null),
            OnDay(2, 10),
            OnDay(2, 16),
            OnDay(2, null),
        )
    }
}

/** The user's quick times, saved on the phone. Used by the after-call popup and the Snooze menu. */
object QuickTimes {
    private const val KEY = "quick_times"

    fun load(ctx: Context): List<QuickTime> {
        val saved = AppSettings.getString(ctx, KEY)
        if (saved.isBlank()) return QuickTime.DEFAULTS
        val list = saved.split("|").mapNotNull { QuickTime.decode(it) }.distinct().take(QuickTime.MAX)
        return list.ifEmpty { QuickTime.DEFAULTS }
    }

    fun save(ctx: Context, list: List<QuickTime>) {
        AppSettings.setString(ctx, KEY, list.take(QuickTime.MAX).joinToString("|") { it.encode() })
    }

    fun reset(ctx: Context) = AppSettings.setString(ctx, KEY, "")

    fun options(ctx: Context, now: Long = System.currentTimeMillis()): List<QuickOption> =
        load(ctx).map { it.toOption(now) }
}
