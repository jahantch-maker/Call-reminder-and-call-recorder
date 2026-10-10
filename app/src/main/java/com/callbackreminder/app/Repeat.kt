package com.callbackreminder.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a reminder repeats, modelled on Google Tasks:
 * "Every [interval] [day/week/month/year]", on chosen weekdays for weekly repeats,
 * ending never, on a date, or after a number of times.
 */
data class Repeat(
    val unit: Every,
    val interval: Int = 1,
    /** Only for weekly repeats. Empty means "the same weekday as the first reminder". */
    val weekDays: Set<DayOfWeek> = emptySet(),
    val end: End = End.Never,
) {
    enum class Every(val code: String, val one: String, val many: String) {
        DAY("d", "day", "days"),
        WEEK("w", "week", "weeks"),
        MONTH("m", "month", "months"),
        YEAR("y", "year", "years"),
    }

    sealed class End {
        object Never : End()

        /** The last day a reminder may fall on (inclusive). */
        data class OnDate(val date: LocalDate) : End()

        /** Stop after this many reminders in total. */
        data class AfterCount(val count: Int) : End()
    }

    /**
     * The first reminder time strictly after [after], for a series that started at [start],
     * or null when the series has ended. [count] is how many reminders came before [after]
     * (including it), so "after N times" can stop.
     */
    fun next(start: ZonedDateTime, after: ZonedDateTime, count: Int): ZonedDateTime? {
        val end = end
        if (end is End.AfterCount && count >= end.count) return null
        val candidate = firstAfter(start, after) ?: return null
        if (end is End.OnDate && candidate.toLocalDate().isAfter(end.date)) return null
        return candidate
    }

    /** The first reminder of a series that starts at [start] (a weekly repeat may move it to a chosen weekday). */
    fun first(start: ZonedDateTime): ZonedDateTime =
        firstAfter(start, start.minusNanos(1)) ?: start

    private fun firstAfter(start: ZonedDateTime, after: ZonedDateTime): ZonedDateTime? {
        val step = interval.coerceAtLeast(1).toLong()
        when (unit) {
            Every.DAY -> {
                val days = ChronoUnit.DAYS.between(start.toLocalDate(), after.toLocalDate()).coerceAtLeast(0)
                var k = days / step * step
                while (true) {
                    val t = start.plusDays(k)
                    if (t.isAfter(after)) return t
                    k += step
                }
            }
            Every.MONTH, Every.YEAR -> {
                val months = if (unit == Every.MONTH) step else step * 12
                // plusMonths keeps the original day where it can (31 Jan → 28 Feb → 31 Mar).
                val gap = ChronoUnit.MONTHS.between(start.toLocalDate(), after.toLocalDate()).coerceAtLeast(0)
                var k = (gap / months - 1).coerceAtLeast(0)
                while (true) {
                    val t = start.plusMonths(k * months)
                    if (t.isAfter(after)) return t
                    k++
                }
            }
            Every.WEEK -> {
                val days = weekDays.ifEmpty { setOf(start.dayOfWeek) }.sortedBy { it.value }
                val firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weeks = ChronoUnit.WEEKS.between(firstMonday.toLocalDate(), after.toLocalDate()).coerceAtLeast(0)
                var w = (weeks / step - 1).coerceAtLeast(0) * step
                // Two full cycles always contain an answer; the cap is only a safety net.
                for (guard in 0 until 1000) {
                    val monday = firstMonday.plusWeeks(w)
                    for (d in days) {
                        val t = monday.plusDays((d.value - 1).toLong())
                        if (!t.isBefore(start) && t.isAfter(after)) return t
                    }
                    w += step
                }
                return null
            }
        }
    }

    /** Short text for the reminder list and the Repeat button, like "Every 2 weeks on Mon, Thu". */
    fun label(start: ZonedDateTime): String {
        val base = when {
            interval == 1 && unit == Every.DAY -> "Daily"
            interval == 1 && unit == Every.WEEK -> "Weekly"
            interval == 1 && unit == Every.MONTH -> "Monthly"
            interval == 1 && unit == Every.YEAR -> "Yearly"
            else -> "Every $interval ${unit.many}"
        }
        val detail = when (unit) {
            Every.DAY -> ""
            Every.WEEK -> " on " + weekDays.ifEmpty { setOf(start.dayOfWeek) }
                .sortedBy { it.value }
                .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            Every.MONTH -> " on day ${start.dayOfMonth}"
            Every.YEAR -> " on " + start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
        val ends = when (val e = end) {
            End.Never -> ""
            is End.OnDate -> ", until " + e.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
            is End.AfterCount -> if (e.count == 1) ", once" else ", ${e.count} times"
        }
        return base + detail + ends
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("unit", unit.code)
            .put("interval", interval)
            .put("days", JSONArray(weekDays.map { it.value }))
        when (val e = end) {
            End.Never -> o.put("end", "never")
            is End.OnDate -> o.put("end", "date").put("endDate", e.date.toString())
            is End.AfterCount -> o.put("end", "count").put("endCount", e.count)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): Repeat? {
            if (o == null) return null
            return try {
                val unit = Every.values().first { it.code == o.getString("unit") }
                val arr = o.optJSONArray("days")
                val days = if (arr == null) emptySet() else
                    (0 until arr.length()).map { DayOfWeek.of(arr.getInt(it)) }.toSet()
                val end = when (o.optString("end")) {
                    "date" -> End.OnDate(LocalDate.parse(o.getString("endDate")))
                    "count" -> End.AfterCount(o.getInt("endCount").coerceAtLeast(1))
                    else -> End.Never
                }
                Repeat(unit, o.optInt("interval", 1).coerceAtLeast(1), days, end)
            } catch (e: Exception) {
                null
            }
        }

        fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    }
}
