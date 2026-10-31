package com.callbackreminder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The on-time mark: measured against the first date, moves counted, No date when there was none (v0.47.0). */
class OnTimeTest {
    private fun ms(d: LocalDate, hour: Int, min: Int = 0) = d.atTime(hour, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val thu = LocalDate.of(2026, 10, 29)

    @Test fun doneBeforeTheFirstDeadlineIsOnTime() {
        assertEquals("✅ On time", OnTime.label(ms(thu, 16), true, ms(thu, 15), 0, "extended"))
        assertEquals("✅ On time · extended 1×", OnTime.label(ms(thu, 16), true, ms(thu, 16), 1, "extended"))
    }

    @Test fun anExtensionStillCountsAsLate() {
        // First deadline Thu 4 pm, extended to Sat, done Sat 2:30 pm.
        assertEquals("⏱ 2 days late · extended 1×", OnTime.label(ms(thu, 16), true, ms(thu.plusDays(2), 14, 30), 1, "extended"))
    }

    @Test fun sameDayLatenessIsInHoursOrMinutes() {
        assertEquals("⏱ 3 h late", OnTime.label(ms(thu, 12), true, ms(thu, 15, 10), 0, "moved"))
        assertEquals("⏱ 20 min late · moved 2×", OnTime.label(ms(thu, 12), true, ms(thu, 12, 20), 2, "moved"))
    }

    @Test fun noDateAndOpenTasks() {
        assertEquals("No date", OnTime.label(0L, false, 0L, 0, "moved"))
        assertEquals("No date", OnTime.label(0L, true, ms(thu, 9), 0, "moved"))
        assertNull(OnTime.label(ms(thu, 16), false, 0L, 0, "moved"))
    }
}
