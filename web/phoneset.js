/*
 * 28 Nov (B5, locked mockups/all-phone-settings/2-subnav-and-tags.png) Every page of the phone app's Settings, with the
 * same names, order and icons, so the office can set each one per designation or person.
 * Row: { id, t: title, c: control, o: options, d: ⓘ text, k: kind, g: group, l: lock id }.
 *   k "lock" (default): 🔒 Locked (set here, read only on his phone) / 📱 His choice (he sets it on his phone).
 *   k "web": always set here (no phone control).  k "inv": "Can change" app switch shown as a lock (on = his choice).
 *   k "tt": comes from his timetable (Team › Timetables).  k "po": only on the phone (v = what it is).
 * Control c: sw, seg, sel, time (minutes of the day), days (Mon..Sun bits), times (list of minutes), text, lines, rem.
 * The phone reads the same ids (PhoneSettings.kt, WebRules.kt).
 */
(function () {
  const H = (m) => (m % 60 ? (m / 60).toFixed(1).replace(/\.0$/, "") : m / 60) + " h";
  const MINS = (l) => l.map((m) => [m, m === 0 ? "Off" : m >= 1440 ? "1 day" : H(m)]);
  const LVL = [["OFF", "Off"], ["SILENT", "Silent"], ["SOUND", "Sound"]];
  const STY = [["full", "Full screen"], ["normal", "Normal"], ["off", "Off"]];
  const TONES = [["rb_soft_chime", "RB Soft Chime"], ["ding_dong", "Ding Dong"], ["electronic", "Electronic Ping"], ["new_notification", "New Notification"], ["bright", "Bright"], ["message", "Message Ping"], ["sleek_chime", "Sleek Chime"], ["ui_ding", "UI Ding"], ["metallic_echo", "Metallic Echo"], ["classic_ding", "Classic Ding"], ["quick_ding", "Quick Ding"]];
  const tones = (phone) => [["", phone]].concat(TONES.map(([k, l]) => ["app:" + k, l]));
  const po = (t, v) => ({ id: "po_" + t, t, k: "po", v: v || "Only on his phone" });
  const KINDS = [["STUCK", "⚠ Stuck"], ["ASKED", "❓ Questions"], ["DONE_CHECK", "✅ Done, check it"], ["MY_TASKS", "☑ My tasks"], ["DOSSIER", "📋 Dossier"], ["FOLLOW_UPS", "📞 Follow-ups"], ["MESSAGES", "💬 Team messages"]];
  // NotifType on the phone: [name, group, title, default, locked]
  const TYPES = [["CALLBACK", "Calls", "Call-back reminder", "SOUND", 1], ["MISSED", "Calls", "Unanswered reminder", "SILENT"], ["TEXT_REPLIED", "Calls", "Reply to a moved text", "SOUND"],
    ["TEXT_SENT", "Texts", "Each text sent", "SILENT"], ["TEXT_REVIEW", "Texts", "Text to review", "SOUND"], ["TEXT_FAILED", "Texts", "Text failed or not sent", "SOUND", 1], ["TAP_TO_SEND", "Texts", "Tap to send", "SOUND", 1], ["DAILY_LIST", "Texts", "Daily list sent", "SILENT"], ["DAILY_LIST_WA", "Texts", "Daily lists for WhatsApp", "SOUND"],
    ["MY_TASK", "My tasks", "One-time task due", "SOUND"], ["DOSSIER", "My tasks", "Dossier task due", "SOUND"], ["DOSSIER_BEFORE", "My tasks", "Dossier early reminder", "SOUND"], ["DOSSIER_NUDGE", "My tasks", "Dossier reminder", "SOUND"],
    ["REVIEW", "Review", "Review reminder", "SOUND"], ["MORNING", "Review", "Morning summary", "SOUND"],
    ["RECORDING", "Recording and backup", "Recording in progress", "SILENT", 1], ["BANKING", "Recording and backup", "Recording is off", "SOUND"], ["TEAM", "Team", "Team tasks", "SOUND"], ["BACKUP_FAILED", "Recording and backup", "Backup failed", "SOUND", 1]];
  const typeCards = Array.from(new Set(TYPES.map((t) => t[1]))).map((g) => [g, TYPES.filter((t) => t[1] === g).map(([n, , t, , lk]) => (lk ? po(t, "Always on") : { id: "nt_" + n, t, c: "seg", o: LVL }))]);
  const off = (n) => ({ id: n, t: n === "n_plotHeads" ? "Heads-up before due" : n, c: "seg", o: STY, k: "web" });
  const N = (id, t) => Object.assign(off(id), { t });

  RB.PHONE = [
    { id: "newplot", title: "New plot file", grp: "PLOT FILES", ic: "add", tone: "#B08A5A", desc: "Open a file for a new plot owner.", cards: [
      ["New plot file", [{ id: "openPlot", g: "app", t: "New plot file button on his phone", c: "sw", k: "web", d: "On: his phone shows the “New plot file” button in Settings, so he can open a file for a new plot owner. Off: it is hidden (until set, the owner and whoever does the first step have it). Seeing the Plot files page on this website is a different switch: Settings › Permissions › In the portal › “Plot files page on the website”." }]] ] },
    { id: "appearance", title: "Appearance", grp: "GENERAL", ic: "palette", tone: "#8E7CFF", desc: "Theme, accent colour and size of his phone app.", cards: [
      ["Theme", [{ id: "theme", t: "Theme", c: "seg", o: [[0, "System"], [1, "Light"], [2, "Dark"]] }]],
      ["Accent colour", [{ id: "accent", t: "Accent colour", c: "seg", o: ["Indigo", "Ocean", "Teal", "Violet", "Rose", "Amber"].map((n, i) => [i, n]) }]],
      ["Dark mode", [{ id: "pureBlack", t: "Pure black", c: "sw" }]],
      ["Display size", [{ id: "size", t: "Display size", c: "seg", o: [[0, "Extra small"], [1, "Small"], [2, "Default"], [3, "Large"]] }]] ] },
    { id: "calendar", title: "Calendar", grp: "GENERAL", ic: "calendar_month", tone: "#4F8DFF", desc: "Working hours, Google Calendar and done tasks on his phone.", cards: [
      ["Working hours (required)", [{ id: "tt_hours", t: "Hours", k: "tt" }]],
      ["Google Calendar", [{ id: "gcal", t: "Show Google Calendar", c: "sw" }]],
      ["Google calendars", [po("Google calendars", "His own Gmail's calendars")]],
      ["Done tasks", [{ id: "doneLeave", t: "Done tasks", c: "seg", o: [[false, "Stay greyed"], [true, "Leave the calendar"]] }]],
      ["Card colours", [po("Card colours")]] ] },
    { id: "offday", title: "Weekly off day", grp: "GENERAL", ic: "beach_access", tone: "#2BB3A3", desc: "His weekly off days. Company holidays are off too.", cards: [
      ["Your off days", [{ id: "tt_off", t: "Your off days", k: "tt" }]] ] },
    { id: "dates", title: "Task rules", grp: "TEAM TASKS", ic: "assignment", tone: "#E5566A", desc: "Deadlines, reminders and texts. Times count working hours only (nights, his off day and holidays don't count).", cards: [
      ["Tasks you give", [
        { id: "noDate", t: "No deadline picked · remind me", c: "sel", o: MINS([60, 120, 240, 480, 1440]), d: "When he gives a task and the other person hasn't picked a deadline after this many working hours, he gets a reminder. Example: 4 h, task given at 10 AM, reminder at 2 PM." },
        { id: "smsMin", t: "Not seen · text him", c: "sel", o: MINS([0, 60, 120, 240]), d: "If the person hasn't opened his task after this many working hours, his phone sends them an SMS. Off: no SMS." },
        { id: "locked", t: "Deadline locked", c: "sw", d: "On: once the person picks a deadline on a task he gave, they can only ask him to extend it. Off: they can change it themselves." },
        { id: "freeExt", t: "Extend without asking", c: "sel", o: [[0, "Never"], [1, "1×"], [2, "2×"], [3, "3×"]], d: "How many times the person may move their own deadline without asking him. Never = always asks." },
        // 28 Nov (T7) Late reasons moved here from Settings › Late reasons: one list for the whole office, edited below the cards.
        { id: "lateReasons", t: "Late reasons", k: "office", d: "Picked by the person DOING a task, when he moves a deadline, asks for more time or finishes late. One list for the whole office (no Locked / His choice). “Their own” reasons count in his score; “Outside” ones (material, payment, client) don't. They feed the Meetings “Top late reasons” chart. Not the same as Not done reasons." },
        { id: "notDone", t: "Not done reasons", c: "lines", d: "Picked by the person who GAVE a task, when he sends it back with ↩ Not done yet (for example “Photo missing”). Can differ per designation or person. One per line. Not the same as Late reasons." }]],
      ["Tasks you get", [
        { id: "urgHours", t: "Urgent, no deadline · remind me", c: "sel", o: [1, 2, 3, 4, 6, 8].map((h) => [h, h + " h"]), k: "web", d: "An urgent task he gets with no deadline: the first reminder comes after this many working hours. The task giver is told each time too. Stops as soon as a deadline is set." },
        { id: "urgTimes", t: "Urgent, no deadline · times", c: "sel", o: [1, 2, 3, 4, 5, 6].map((n) => [n, n + "×"]), k: "web", d: "How many reminders in all (same gap each time). If the task never reached his phone, the giver's phone sends him one SMS instead." },
        { id: "urgStyle", t: "Urgent, no deadline · how it shows", c: "seg", o: STY, k: "web", d: "Full screen: a ringing popup like a call-back reminder. Normal: a notification. Off: no reminders to him (the giver is still told)." }]] ] },
    { id: "style", title: "Reminder style", grp: "CALLS & TEXTS", ic: "notifications_active", tone: "#F2A93B", desc: "How a call-back reminder shows and rings on his phone.", cards: [
      ["Reminder style", [
        { id: "remStyle", t: "Reminder style", c: "seg", o: [["full", "Full screen until I react"], ["timed", "Full screen, then notification"], ["banner", "Banner notification only"]], d: "Full screen until I react: rings and stays on screen until he taps Call back, Snooze or Done. Full screen, then notification: rings on full screen for the seconds below, then moves to his notifications. Banner notification only: a normal notification, no popup." },
        { id: "remSecs", t: "Full screen for", c: "sel", o: [10, 15, 30, 60].map((n) => [n, n + " seconds"]), d: "Only with “Full screen, then notification”." }]],
      ["Popup ringtone", [
        { id: "popupTone", t: "Popup ringtone", c: "sel", o: tones("Phone ringtone"), d: "For the large full-screen reminder. Follows his ringtone volume, silent when his phone is on silent." },
        { id: "remRepeat", t: "Repeat until I react / Play once", c: "seg", o: [[true, "Repeat until I react"], [false, "Play once"]], d: "Repeat until I react: keeps playing for up to 1 minute. Play once: plays one time and vibrates 3 times. The reminder stays on screen either way." }]],
      ["Notification ringtone", [{ id: "bannerTone", t: "Notification ringtone", c: "sel", o: tones("Phone notification sound"), d: "For banner notifications: call-back banners and text-message alerts." }]],
      ["Try it out", [po("Test a reminder in 10 seconds")]] ] },
    { id: "popup", title: "After-call popup", grp: "CALLS & TEXTS", ic: "layers", tone: "#34B36B", desc: "Which calls show the popup on his phone.", cards: [
      ["Caller card", [{ id: "callerCard", t: "Show the caller card", c: "sw", d: "When someone with pending tasks calls, or he calls them, their tasks show over the call screen." }]],
      ["Show the popup after", [{ id: "popOut", t: "Outgoing calls", c: "sw" }, { id: "popIn", t: "Incoming calls", c: "sw" }, { id: "popMissed", t: "Missed calls", c: "sw" }, { id: "popWa", t: "WhatsApp calls", c: "sw" }]],
      ["Each SIM", [po("Each SIM", "His own SIMs")]],
      ["Only in duty hours", [{ id: "dutyOnly", t: "After-call popup only in duty hours", k: "inv", inv: "popupHours", d: "🔒 Locked: always on. 📱 His choice: he turns it on or off himself. A number that still has an open task with him always asks." }]],
      ["Close by itself", [{ id: "autoClose", t: "Close by itself", c: "sel", o: [[0, "Off"], [5, "5s"], [10, "10s"], [15, "15s"], [30, "30s"], [60, "60s"]], d: "The popup closes after a few seconds if he doesn't touch it, like Skip (the call stays in Calls)." }]],
      ["Hold to call", [{ id: "hold", t: "Hold to call", c: "sel", o: [[0, "Off"], [1, "1s"], [2, "2s"]], d: "“Call again” on the popups calls only when he holds it." }]],
      ["Try it out", [po("See the popup")]] ] },
    { id: "rec", title: "Call recording", grp: "CALLS & TEXTS", ic: "mic", tone: "#E5566A", desc: "Recording his calls. Recordings never leave his phone.", cards: [
      ["Allowed", [{ id: "callRec", g: "app", t: "Call recording allowed", c: "sw", k: "web", d: "On: call recording is allowed; he still has to turn it on in his own phone. Off: no call recording." }]],
      ["Permissions", [po("Microphone"), po("Accessibility service")]],
      ["Using a bank app?", [po("Switch off for banking")]],
      ["Recording", [{ id: "recPhone", t: "Record phone calls", c: "sw" }, { id: "recWa", t: "Record WhatsApp calls", c: "sw" },
        { id: "recSource", t: "Recording source", c: "sel", o: [["auto", "Automatic (recommended)"], ["recognition", "Voice recognition"], ["communication", "Voice communication"], ["mic", "Microphone"], ["call", "Call audio"], ["unprocessed", "Unprocessed"]] },
        { id: "recKeep", t: "Keep recordings", c: "sel", o: [["0", "Forever"], ["30", "30 days"], ["60", "60 days"], ["90", "90 days"], ["180", "6 months"]] }]],
      ["Test and listen", [po("Test and listen")]] ] },
    { id: "texts", title: "Text messages", grp: "CALLS & TEXTS", ic: "sms", tone: "#6C6FF5", desc: "His own SMS follow-ups.", cards: [
      ["Allowed", [{ id: "sms", g: "app", t: "Text messages allowed", c: "sw", k: "web", d: "On: he can send SMS follow-ups from his phone. Off: every SMS part disappears from his phone. Texts already scheduled pause and start again when it is back on." }]],
      ["When a follow-up gets a new time", [{ id: "sendNow", t: "Send one text right away", c: "sw", d: "If every one of the texts' times has already passed on the new day." }]],
      ["Burst templates", [po("Burst templates")]],
      ["Stop when the person texts back the task number", [{ id: "stopReply", t: "Stop on a task number reply", c: "sw" }]] ] },
    { id: "wa", title: "WhatsApp", grp: "CALLS & TEXTS", ic: "chat", tone: "#34B36B", desc: "Which WhatsApp the WA buttons open, and the message.", cards: [
      ["Open in", [{ id: "waApp", t: "Open in", c: "seg", o: [["com.whatsapp", "WhatsApp"], ["com.whatsapp.w4b", "WhatsApp Business"]] }]],
      ["Message", [{ id: "waMsg", t: "Message", c: "text", d: "Words his phone fills in: {number} {task} {due} {name}" }]] ] },
    { id: "tpick", title: "Time picker", grp: "CALLS & TEXTS", ic: "schedule", tone: "#4F8DFF", desc: "The time chips he sees when setting a time.", cards: [
      ["Time grid", [{ id: "tpStart", t: "First time", c: "time", l: "timeGrid" }, { id: "tpEnd", t: "Last time", c: "time", l: "timeGrid" }, { id: "tpGap", t: "Gap", c: "seg", o: [[15, "15m"], [30, "30m"], [60, "60m"]], l: "timeGrid" }]],
      ["How long chips", [po("How long chips")]],
      ["Preview", [po("Preview")]] ] },
    { id: "myday", title: "My day", grp: "YOUR DAY", ic: "wb_sunny", tone: "#F2A93B", desc: "Morning summary, Review and Dossier reminder.", cards: [
      ["Morning summary", [{ id: "msOn", t: "Send me a morning summary", c: "sw" }, { id: "msTime", t: "Time", c: "time" }, { id: "msDays", t: "Days", c: "days" }, { id: "msFull", t: "Full screen", c: "sw" }, { id: "msSkip", t: "Skip when the day is empty", c: "sw" }]],
      ["Remind me to review", [
        { id: "rev1", t: "Review reminder 1", c: "rem", l: "review", d: "🔒 Locked: a full-screen popup at these times from his duty end, only when something is waiting, never on his off day or a holiday. 📱 His choice: he sets his own times on his phone." },
        { id: "rev2", t: "Review reminder 2", c: "rem", l: "review" }]],
      ["Dossier reminder", [
        { id: "dos1", t: "Dossier reminder 1", c: "rem", l: "dossier", d: "🔒 Locked: a full-screen popup listing his unticked dossier tasks at these times from his duty end. 📱 His choice: he sets his own times on his phone." },
        { id: "dos2", t: "Dossier reminder 2", c: "rem", l: "dossier" }]] ] },
    { id: "daily", title: "Daily list", grp: "YOUR DAY", ic: "assignment", tone: "#2BB3A3", desc: "When his phone sends each person their open tasks.", cards: [
      ["Who gets it", [po("Who gets it", "Picked on his phone, per person")]],
      ["Send times", [{ id: "dlTimes", t: "Send times", c: "times" }]],
      ["Days", [{ id: "dlDays", t: "Days", c: "days" }]] ] },
    { id: "notif", title: "Notifications", grp: "YOUR DAY", ic: "notifications", tone: "#E5566A", desc: "How each kind of notification shows on his phone.", cards: [
      ["Quiet hours", [{ id: "quiet", t: "Quiet hours", k: "inv", inv: "quietHours", d: "🔒 Locked: always on (automatic ones come only from 30 min before his duty to 1 hour after). 📱 His choice: he can turn it off." }]],
      ["Plot files", [N("n_plotNew", "New plot step"), N("n_plotDue", "Step due"), off("n_plotHeads"), { id: "plotHeadsMin", t: "Heads-up · how long before", c: "sel", k: "web", o: [15, 30, 60, 120, 240, 1440].map((m) => [m, (m >= 1440 ? "1 day" : m >= 60 ? m / 60 + " hour" + (m > 60 ? "s" : "") : m + " min") + " before"]) }, N("n_plotWait", "Waiting for plot owner"), N("n_plotFollow", "Updates on plots you follow")]],
      ["Team tasks", [N("n_newTask", "New task for you"), N("n_doneCheck", "Done, check it"), N("n_extend", "Asks to extend"), N("n_noDate", "No date / not seen nudges"), N("n_timeline", "Timeline messages"), N("n_mention", "@mentions")]],
      ["Dossier", [N("n_dossier", "New dossier task")]],
      ["Each category", KINDS.map(([n, t]) => ({ id: "nk_" + n, t, c: "seg", o: LVL }))],
    ].concat(typeCards) },
    { id: "backup", title: "Backup", grp: "PHONE", ic: "backup", tone: "#4F8DFF", desc: "Backups of his phone's data.", cards: [
      ["Nightly backup", [po("Nightly backup", "His phone's own folder")]], ["Keep it off the phone", [po("Share the latest backup")]], ["Restore", [po("Restore")]], ["Cloud backup", [po("Cloud backup", "Automatic, his own space")]] ] },
    { id: "vnotes", title: "Voice notes", grp: "PHONE", ic: "mic", tone: "#2BB3A3", desc: "Calls are silenced while he records a voice note.", cards: [
      ["Voice notes", [{ id: "voiceNotes", t: "Voice notes", c: "sw", d: "On: calls are silenced while he records a voice note." }]] ] },
    { id: "vtype", title: "Voice typing", grp: "PHONE", ic: "keyboard_voice", tone: "#8E7CFF", desc: "The 🎤 button in every note and message box.", cards: [
      ["Voice typing button", [{ id: "vtOn", t: "Show the 🎤 button", c: "sw" }]],
      ["Language", [{ id: "vtLang", t: "Language", c: "seg", o: [["URDU", "Urdu"], ["ENGLISH", "English"]] }]] ] },
    { id: "setup", title: "Setup", grp: "PHONE", ic: "verified_user", tone: "#34B36B", desc: "The phone permissions the app needs.", cards: [
      ["Permissions", ["Phone state", "Call log", "Notifications", "Display over other apps", "Full-screen alerts", "Call directly", "Notification access", "Contacts", "Battery: Unrestricted"].map((t) => po(t, "Allowed on his phone"))] ] },
  ];
  // Fix the Notifications cards (built from lists above): typeCards came in as [group, rows] pairs.
  RB.PHONE.forEach((p) => p.cards.forEach((c) => c[1].forEach((r) => (r.k = r.k || "lock"))));

  /** The phone's own defaults (same as PhoneSettings.kt / each setting's default), used until the office sets one. */
  RB.PHONE_DEF = {
    theme: 1, accent: 0, pureBlack: false, size: 1, gcal: true, doneLeave: false,
    notDone: ["Photo missing", "Incomplete", "Wrong site", "Not as asked", "Quality"],
    popupTone: "app:rb_soft_chime", bannerTone: "app:message",
    callerCard: true, popOut: true, popIn: true, popMissed: true, popWa: true, autoClose: 0, hold: 0,
    recPhone: true, recWa: true, recSource: "auto", recKeep: "0", sendNow: true, stopReply: false,
    waApp: "com.whatsapp", waMsg: "Task {number}: {task}\nDue: {due}\nPlease update.",
    tpStart: 480, tpEnd: 1260, tpGap: 30, msOn: true, msTime: 480, msDays: 63, msFull: false, msSkip: true,
    dlTimes: [540, 780, 1080], dlDays: 63, voiceNotes: true, vtOn: true, vtLang: "URDU",
  };
  KINDS.forEach(([n]) => (RB.PHONE_DEF["nk_" + n] = "SOUND"));
  TYPES.forEach(([n, , , d, lk]) => { if (!lk) RB.PHONE_DEF["nt_" + n] = d; });
  Object.assign(RB.DEF_SET, RB.PHONE_DEF);
  /** Locked before 28 Nov, so they stay locked until the office says otherwise (same list as WebRules.kt). */
  RB.DEF_LOCK = { noDate: true, smsMin: true, locked: true, freeExt: true, remStyle: true, remSecs: true, remRepeat: true, review: true, dossier: true };
})();
