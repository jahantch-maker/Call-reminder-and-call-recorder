package com.callbackreminder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Timeline's day chips, real dates in old lines, the 1-hour Undo and the speed list (v0.45.0). */
class TimelineRulesTest {
    private fun ms(d: LocalDate, hour: Int = 12) = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun chipsSayTodayYesterdayThenTheDate() {
        val today = LocalDate.now()
        assertEquals("Today", TimelineDates.chip(ms(today)))
        assertEquals("Yesterday", TimelineDates.chip(ms(today.minusDays(1))))
        val older = TimelineDates.chip(ms(today.minusDays(5)))
        assertFalse(older == "Today" || older == "Yesterday")
    }

    @Test fun aFrozenTodayBecomesTheDayItWasWritten() {
        val written = ms(LocalDate.now().minusDays(9))
        val out = TimelineDates.unfreeze("Time moved · Today, 4:00 pm → Tomorrow, 11:15 am", written)
        assertFalse(out.contains("Today") || out.contains("Tomorrow"))
        assertTrue(out.contains("4:00 pm") && out.contains("11:15 am"))
    }

    @Test fun textWithoutRelativeWordsIsKept() {
        assertEquals("Created", TimelineDates.unfreeze("Created", 1_000L))
    }

    @Test fun oneChipBeforeEachDay() {
        val d = LocalDate.of(2026, 10, 28)
        val times = listOf(ms(d, 9), ms(d, 15), ms(d.plusDays(1), 8))
        val rows = withDayChips(times) { it }
        assertEquals(2, rows.count { it is DayChip })
        assertTrue(rows.first() is DayChip)
    }

    @Test fun undoIsForYourOwnMessageWithinAnHour() {
        fun msg(from: String, ago: Long, text: String = "hi") =
            TeamMsg("1", System.currentTimeMillis() - ago, from, "", Team.TEXT, "", text, null, 0L)
        assertTrue(Team.canUndo(msg("me", 5 * 60_000L), "me"))
        assertFalse(Team.canUndo(msg("me", 2 * 3_600_000L), "me"))
        assertFalse(Team.canUndo(msg("him", 60_000L), "me"))
        assertFalse(Team.canUndo(msg("me", 60_000L, Team.DELETED), "me"))
    }

    @Test fun voiceSpeedsAreOneOnePointFiveAndTwo() {
        assertEquals(listOf(1f, 1.5f, 2f), TeamMedia.SPEEDS)
    }
}
