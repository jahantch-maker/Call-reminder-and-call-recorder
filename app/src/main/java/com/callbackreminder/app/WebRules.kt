package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 27 Nov: the settings the website sets for each person (Team › Access): date rules, dossier and review reminder
 * times, urgent no-date reminders, notification styles and a few switches. The website is only the remote control:
 * the newest copy arrives with the member row whenever the phone is online and is saved here, so every alarm and
 * popup keeps working with no internet, after a restart and in battery saver (never "no settings = nothing fires").
 * The defaults below are the website's defaults (web/core.js DEF_SET), used until the website has sent anything.
 */
object WebRules {
    /** Full screen / Normal / Off for one kind of notification. */
    const val FULL = "full"
    const val NORMAL = "normal"
    const val OFF = "off"

    /** The kinds of notification the website sets (same keys as the website's Access › Notifications rows). */
    const val N_PLOT_DUE = "n_plotDue"
    const val N_PLOT_HEADS = "n_plotHeads"
    const val N_PLOT_WAIT = "n_plotWait"
    const val N_PLOT_FOLLOW = "n_plotFollow"
    const val N_NEW_TASK = "n_newTask"
    const val N_MENTION = "n_mention"
    const val N_NO_DATE = "n_noDate"
    const val N_DONE_CHECK = "n_doneCheck"
    const val N_EXTEND = "n_extend"
    const val N_TIMELINE = "n_timeline"
    const val N_DOSSIER = "n_dossier"

    private val DEFAULTS: Map<String, Any> = mapOf(
        // Date rules (tasks this person gives).
        "noDate" to 240, "smsMin" to 120, "locked" to true, "freeExt" to 0,
        // Urgent task with no date (tasks this person gets): after N working hours, repeated N times, in this style.
        "urgHours" to 2, "urgTimes" to 3, "urgStyle" to NORMAL,
        // Dossier and review reminders: minutes from duty end (minus = before).
        "dos1On" to true, "dos1Min" to -60, "dos2On" to true, "dos2Min" to 0,
        "rev1On" to true, "rev1Min" to -480, "rev2On" to true, "rev2Min" to -180,
        // Notifications.
        N_PLOT_DUE to FULL, "plotHeadsMin" to 60, N_PLOT_HEADS to NORMAL, N_PLOT_WAIT to NORMAL, N_PLOT_FOLLOW to NORMAL,
        N_NEW_TASK to NORMAL, N_MENTION to NORMAL, N_NO_DATE to NORMAL, N_DONE_CHECK to NORMAL, N_EXTEND to NORMAL,
        N_TIMELINE to NORMAL, N_DOSSIER to NORMAL,
    )

    private fun p(c: Context) = c.getSharedPreferences("web_rules", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Bumps when a new copy arrived from the website (screens showing these redraw). */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Saves the newest copy from the member row ([perms] = its "perms" map). [member]: a seat holder, not the owner. */
    fun save(c: Context, perms: Any?, member: Boolean) {
        val m = perms as? Map<*, *>
        fun part(k: String) = JSONObject(((m?.get(k) as? Map<*, *>).orEmpty()).entries.associate { (a, b) -> a.toString() to b })
        val json = JSONObject().put("set", part("set")).put("app", part("app")).toString()
        if (json == p(c).getString("json", null) && member == managed(c)) return
        p(c).edit().putString("json", json).putBoolean("member", member).apply()
        changed(c)
    }

    /** Signed out or left the team: the phone's own settings apply again. */
    fun clear(c: Context) {
        if (!managed(c)) return
        p(c).edit().putBoolean("member", false).apply()
        changed(c)
    }

    private fun changed(c: Context) {
        cache = null
        _version.value++
        // Times may have moved: the alarms follow at once.
        runCatching { ReviewReminder.schedule(c) }
        runCatching { TaskAlarms.scheduleNudge(c) }
        // Text messages switched back on: the paused texts start again.
        runCatching { if (smsOn(c)) SmsScheduler.rescheduleAll(c) }
        // Widgets hide or show texts with the switch.
        runCatching { WidgetSync.refresh(c) }
    }

    /** Set by the office: a team member in a seat (not the owner). These rows are then read only on the phone. */
    fun managed(c: Context): Boolean = p(c).getBoolean("member", false)

    @Volatile private var cache: JSONObject? = null
    private fun json(c: Context): JSONObject =
        cache ?: (runCatching { JSONObject(p(c).getString("json", "{}") ?: "{}") }.getOrNull() ?: JSONObject()).also { cache = it }

    private fun raw(c: Context, key: String): Any? = json(c).optJSONObject("set")?.opt(key)?.takeIf { managed(c) } ?: DEFAULTS[key]

    fun int(c: Context, key: String): Int = (raw(c, key) as? Number)?.toInt() ?: (DEFAULTS[key] as? Int) ?: 0
    fun bool(c: Context, key: String): Boolean = raw(c, key) as? Boolean ?: (DEFAULTS[key] as? Boolean) ?: false
    fun str(c: Context, key: String): String = raw(c, key) as? String ?: (DEFAULTS[key] as? String).orEmpty()

    /** 27 Nov (S6.1) The office's "Text messages" switch: off hides his own SMS follow-ups and pauses scheduled texts. */
    fun smsOn(c: Context): Boolean = app(c, "sms", true)

    /** One of the website's app switches, from the saved copy ([legacy] when the website never set it). Works offline. */
    fun app(c: Context, key: String, legacy: Boolean): Boolean =
        if (!managed(c)) legacy else json(c).optJSONObject("app")?.opt(key) as? Boolean ?: legacy

    /** "Set by the office" style of one notification kind: [FULL], [NORMAL] or [OFF]. */
    fun style(c: Context, kind: String): String = str(c, kind).takeIf { it == FULL || it == NORMAL || it == OFF } ?: NORMAL

    /**
     * Today's reminder times for dossier ("dos") or review ("rev"): each switched-on reminder at duty end plus its
     * minutes. None on the weekly off day or a holiday (the duty window says so).
     */
    fun reminderTimes(c: Context, prefix: String, date: java.time.LocalDate): List<Long> {
        val end = TeamRules.dutyEnd(c, date) ?: return emptyList()
        return (1..2).filter { bool(c, "$prefix${it}On") }.map { end + int(c, "$prefix${it}Min") * 60_000L }.distinct().sorted()
    }

    /** "1 hour before duty end", "At duty end", "30 min after duty end". */
    fun offsetLabel(min: Int): String {
        if (min == 0) return "At duty end"
        val a = kotlin.math.abs(min)
        val t = if (a % 60 == 0) "${a / 60} hour${if (a == 60) "" else "s"}" else if (a > 60) "${a / 60} h ${a % 60} min" else "$a min"
        return t + if (min < 0) " before duty end" else " after duty end"
    }
}
