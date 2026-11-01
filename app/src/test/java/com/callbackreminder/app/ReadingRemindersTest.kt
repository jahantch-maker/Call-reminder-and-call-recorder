package com.callbackreminder.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingRemindersTest {
    private val day = 24 * 3_600_000L

    @Test fun farAwayIsQuiet() = assertEquals(0, ReadingReminders.stage(0L, 5 * day))
    @Test fun dayBefore() = assertEquals(1, ReadingReminders.stage(0L, day))
    @Test fun lastDay() = assertEquals(2, ReadingReminders.stage(0L, day / 3))
    @Test fun overdueEachDayHasItsOwnStage() {
        assertEquals(3, ReadingReminders.stage(day, day))
        assertEquals(4, ReadingReminders.stage(2 * day + 1, day))
    }
}
