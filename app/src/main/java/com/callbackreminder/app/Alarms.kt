package com.callbackreminder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/**
 * Sets an exact "alarm clock" alarm when Android allows it, and a slightly less exact one when it
 * doesn't. Since Android 12 an exact alarm without the "Alarms & reminders" permission throws an
 * error, which closed the app the moment it opened on phones where that permission was off.
 */
fun AlarmManager.setClockSafely(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()) {
        try {
            setAlarmClock(info, operation)
            return
        } catch (e: SecurityException) {
            // Permission was taken away between the check and the call: fall through.
        }
    }
    setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, info.triggerTime, operation)
}
