package com.callbackreminder.app

import android.content.Context

/** How a due reminder gets your attention. Chosen once in Settings and used for every reminder. */
object ReminderStyle {

    enum class Mode(val code: String, val title: String) {
        /** Rings and shows the full-screen reminder until you tap Call back, Snooze or Done. */
        FULL_SCREEN("full", "Full screen until I react"),

        /** Rings and shows the full-screen reminder for a few seconds, then leaves a notification. */
        FULL_SCREEN_THEN_NOTIFY("timed", "Full screen, then notification"),

        /** A normal heads-up notification with sound. No full-screen reminder. */
        BANNER("banner", "Banner notification only"),
    }

    val SECONDS_CHOICES = listOf(10, 15, 30, 60)
    private const val DEFAULT_SECONDS = 15

    fun mode(ctx: Context): Mode {
        // 28 Nov A team member's style is set by the office (web Settings › Team rules and access › Notifications); the owner picks his own.
        val saved = if (WebRules.managed(ctx)) WebRules.str(ctx, "remStyle") else AppSettings.getString(ctx, AppSettings.REMINDER_STYLE)
        return Mode.values().firstOrNull { it.code == saved } ?: Mode.FULL_SCREEN
    }

    fun setMode(ctx: Context, mode: Mode) =
        AppSettings.setString(ctx, AppSettings.REMINDER_STYLE, mode.code)

    /** How long the full-screen reminder stays before it moves to the notifications. */
    fun seconds(ctx: Context): Int =
        (if (WebRules.managed(ctx)) WebRules.int(ctx, "remSecs") else AppSettings.getString(ctx, AppSettings.POPUP_SECONDS).toIntOrNull())
            ?.takeIf { it in SECONDS_CHOICES } ?: DEFAULT_SECONDS

    fun setSeconds(ctx: Context, seconds: Int) =
        AppSettings.setString(ctx, AppSettings.POPUP_SECONDS, seconds.toString())

    /** It is time to call back: ring with the full-screen reminder, or show a banner. */
    fun alert(ctx: Context, r: Reminder) {
        when (mode(ctx)) {
            // Without permission to post notifications a banner would never show, so ring instead.
            Mode.BANNER -> if (Notifier.canNotify(ctx)) Notifier.showReminder(ctx, r) else RingController.start(ctx, r)
            Mode.FULL_SCREEN -> RingController.start(ctx, r)
            Mode.FULL_SCREEN_THEN_NOTIFY -> RingController.start(ctx, r, timeoutMs = seconds(ctx) * 1_000L)
        }
    }
}
