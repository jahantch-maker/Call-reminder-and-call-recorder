package com.callbackreminder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The "all tasks" WhatsApp/SMS message (27 Nov): groups, bold, blank line after each task, numbering runs on. */
class TaskListTextTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private fun ms(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 11, 27)
    private val now = ms(day, 10)
    private val tasks = listOf(
        TaskListText.Task(ms(day.minusDays(2), 17), "Send the map", "T-12"),
        TaskListText.Task(0L, "Visit plot 4", "T-14", order = 5),
        TaskListText.Task(ms(day, 15), "Call the bank", "T-15"),
        TaskListText.Task(ms(day.plusDays(3), 11), "Pay the bill", "T-16"),
    )

    @Test fun whatsAppListIsBoldAndSpaced() {
        val text = TaskListText.format("Ali", tasks, now, TaskListText.DEFAULT, bold = true, zone = zone)
        assertEquals(
            """
            *Ali, your open tasks (27 Nov)*

            *🔴 Late (1)*
            1. Send the map
               Due 25 Nov, 5:00 pm · T-12

            *⏳ No time yet (1)*
            2. Visit plot 4
               T-14

            *📅 Today (1)*
            3. Call the bank
               Due 27 Nov, 3:00 pm · T-15
            """.trimIndent(),
            text
        )
    }

    @Test fun smsHasNoStarsAndLaterCanBePicked() {
        val text = TaskListText.format(null, tasks, now, setOf(TaskListText.Group.LATER), bold = false, zone = zone)
        assertEquals("Your open tasks (27 Nov)\n\n🗓 Later (1)\n1. Pay the bill\n   Due 30 Nov, 11:00 am · T-16", text)
    }

    @Test fun nothingPickedIsNull() {
        assertNull(TaskListText.format("Ali", tasks.take(1), now, setOf(TaskListText.Group.TODAY), bold = true, zone = zone))
    }
}
