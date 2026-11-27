package com.callbackreminder.app

import android.content.Context
import org.json.JSONArray

/**
 * 28 Nov (B5) Every phone setting the website can lock. Same ids as the website (web/phoneset.js). When the office
 * sets one to "🔒 Locked", its value arrives with the member row and is written here into the phone's own setting, so
 * everything keeps working offline. "📱 His choice" leaves the phone's value alone (he changes it himself).
 */
object PhoneSettings {
    private class Spec(val id: String, val get: (Context) -> Any, val set: (Context, Any) -> Unit, var lock: String = id)

    private fun b(id: String, get: (Context) -> Boolean, set: (Context, Boolean) -> Unit) =
        Spec(id, get) { c, v -> (v as? Boolean)?.let { set(c, it) } }
    private fun i(id: String, get: (Context) -> Int, set: (Context, Int) -> Unit) =
        Spec(id, get) { c, v -> (v as? Number)?.toInt()?.let { set(c, it) } }
    private fun s(id: String, get: (Context) -> String, set: (Context, String) -> Unit) =
        Spec(id, get) { c, v -> (v as? String)?.let { set(c, it) } }
    private fun il(id: String, get: (Context) -> List<Int>, set: (Context, List<Int>) -> Unit) =
        Spec(id, { get(it) }) { c, v -> (v as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() } }?.takeIf { it.isNotEmpty() }?.let { set(c, it) } }
    private fun sl(id: String, get: (Context) -> List<String>, set: (Context, List<String>) -> Unit) =
        Spec(id, { get(it) }) { c, v -> (v as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }?.takeIf { it.isNotEmpty() }?.let { set(c, it) } }

    private val SPECS: List<Spec> = listOf(
        // General
        i("theme", Appearance::mode, Appearance::setMode),
        i("accent", Appearance::accent, Appearance::setAccent),
        b("pureBlack", Appearance::pureBlack, Appearance::setPureBlack),
        i("size", Appearance::displaySize, Appearance::setDisplaySize),
        b("gcal", GoogleCal::wanted, GoogleCal::setEnabled),
        b("doneLeave", CardColours::doneLeaves, CardColours::setDoneLeaves),
        // Task rules (the website's own keys noDate, smsMin, locked, freeExt are read straight from WebRules)
        sl("notDone", TeamRules::notDoneReasons, TeamRules::setNotDoneReasons),
        // Reminder style (remStyle, remSecs, remRepeat are read straight from WebRules)
        s("popupTone", ReminderSound.POPUP::saved, ReminderSound.POPUP::set),
        s("bannerTone", ReminderSound.BANNER::saved) { c, v -> ReminderSound.BANNER.set(c, v); Notifier.bannerChannel(c) },
        // After-call popup
        b("callerCard", CallerCard::enabled, CallerCard::setEnabled),
        b("popOut", { AppSettings.get(it, AppSettings.OUTGOING) }) { c, v -> AppSettings.set(c, AppSettings.OUTGOING, v) },
        b("popIn", { AppSettings.get(it, AppSettings.INCOMING) }) { c, v -> AppSettings.set(c, AppSettings.INCOMING, v) },
        b("popMissed", { AppSettings.get(it, AppSettings.MISSED) }) { c, v -> AppSettings.set(c, AppSettings.MISSED, v) },
        b("popWa", { AppSettings.get(it, AppSettings.WHATSAPP) }) { c, v -> AppSettings.set(c, AppSettings.WHATSAPP, v) },
        i("autoClose", SimPopup::autoClose, SimPopup::setAutoClose),
        i("hold", SimPopup::holdToCall, SimPopup::setHoldToCall),
        // Call recording
        b("recPhone", { AppSettings.get(it, AppSettings.REC_PHONE) }) { c, v -> AppSettings.set(c, AppSettings.REC_PHONE, v) },
        b("recWa", { AppSettings.get(it, AppSettings.REC_WHATSAPP) }) { c, v -> AppSettings.set(c, AppSettings.REC_WHATSAPP, v) },
        s("recSource", RecSource::chosen) { c, v -> if (RecSource.options.any { it.key == v }) AppSettings.setString(c, AppSettings.REC_SOURCE, v) },
        s("recKeep", { AppSettings.getString(it, AppSettings.REC_KEEP_DAYS, "0") }) { c, v -> AppSettings.setString(c, AppSettings.REC_KEEP_DAYS, v) },
        // Text messages
        b("sendNow", FollowUps::sendNowIfPassed, FollowUps::setSendNowIfPassed),
        b("stopReply", BurstTemplates::stopOnReply, BurstTemplates::setStopOnReply),
        // WhatsApp
        s("waApp", WhatsApp::app, WhatsApp::setApp),
        s("waMsg", WhatsApp::template, WhatsApp::setTemplate),
        // Time picker
        i("tpStart", TimeGrid::start, TimeGrid::setStart).also { it.lock = "timeGrid" },
        i("tpEnd", TimeGrid::end, TimeGrid::setEnd).also { it.lock = "timeGrid" },
        i("tpGap", TimeGrid::gap, TimeGrid::setGap).also { it.lock = "timeGrid" },
        // My day › Morning summary (Review and Dossier reminder come from WebRules when locked)
        b("msOn", MorningSummary::enabled, MorningSummary::setEnabled),
        i("msTime", MorningSummary::minute, MorningSummary::setMinute),
        i("msDays", MorningSummary::days, MorningSummary::setDays),
        b("msFull", MorningSummary::fullScreen, MorningSummary::setFullScreen),
        b("msSkip", MorningSummary::skipEmpty, MorningSummary::setSkipEmpty),
        // Daily list
        il("dlTimes", DailyList::times, DailyList::setTimes),
        i("dlDays", DailyList::days, DailyList::setDays),
        // Phone
        b("voiceNotes", RingerSilence::enabled, RingerSilence::setEnabled),
        b("vtOn", { VoiceTyping.state(it).value }, VoiceTyping::setEnabled),
        s("vtLang", { VoiceTyping.lang(it).name }) { c, v -> VoiceTyping.Lang.values().firstOrNull { it.name == v }?.let { VoiceTyping.setLang(c, it) } },
    ) + NotifKind.values().map { k ->
        s("nk_${k.name}", { Notify.kindLevel(it, k).name }) { c, v -> NotifLevel.values().firstOrNull { it.name == v }?.let { Notify.setKindLevel(c, k, it) } }
    } + NotifType.values().filter { it.locked == null }.map { t ->
        s("nt_${t.name}", { Notify.level(it, t).name }) { c, v -> NotifLevel.values().firstOrNull { it.name == v }?.let { Notify.setLevel(c, t, it) } }
    }

    /** Writes every locked setting's office value into the phone (only the ones that differ). Safe to call often. */
    fun apply(c: Context) {
        val set = WebRules.setJson(c) ?: return
        SPECS.forEach { sp ->
            if (!WebRules.locked(c, sp.lock) || !set.has(sp.id)) return@forEach
            val v = set.opt(sp.id)
            val cur = sp.get(c)
            val same = when (cur) {
                is List<*> -> (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) }.map { (it as? Number)?.toInt() ?: it } } == cur
                is Int -> (v as? Number)?.toInt() == cur
                else -> v == cur
            }
            if (!same) runCatching { sp.set(c, v) }
        }
    }
}
