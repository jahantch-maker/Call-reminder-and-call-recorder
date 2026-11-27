/* Settings › Rules (27 Nov, R1-R3): every rule of the app and the portal in simple English, with an example, grouped
 * under headings, with a search bar. Read only. Who sees it: owners, and people with Access › "See Rules".
 * EVERY build updates this list when a rule is added, changed or removed (the "v" says in which version). */
(function () {
  "use strict";
  const RB = window.RB, esc = RB.esc;
  // [heading, [[rule, what it does, example, version changed]]]
  RB.RULES = [
    ["Duty hours and quiet time", [
      ["Duty hours", "Each person's duty hours come from their timetable (Team › Timetables). No timetable = 11 AM to 9 PM. The weekly off day and Settings › Holidays have no duty hours.", "Timetable 10 AM to 7 PM, Friday off: Friday has no duty hours at all.", "0.61.0"],
      ["Notification window", "Automatic team notifications, reminders and texts to staff only come from 30 minutes before duty starts until 1 hour after it ends.", "Duty 11 AM to 9 PM: automatic ones come 10:30 AM to 10 PM.", "0.61.0"],
      ["Night bundle", "Automatic notifications outside the window wait. When the window opens they come as ONE notification: “8 updates while you were off”.", "Three “Done, check it” at 11 PM arrive at 10:30 AM as “3 updates while you were off”.", "0.61.0"],
      ["Text info waits too", "For team members, info about texts (sent, failed, to review, a reply, daily list sent) outside the window also waits for the bundle.", "“Sent to Ali ✓” at 11:40 PM comes in the morning bundle.", "0.61.1"],
      ["Night Timeline messages", "Outside the window, Timeline messages still show, but silently. An @mention of you still rings.", "At midnight “@Ali please check” rings on Ali's phone; a plain message is silent.", "0.61.0"],
      ["Set times always ring", "Anything with a set time (call-back reminder, My task, follow-up, plot step due) always comes at its time, day or night.", "A call-back set for 11:30 PM rings at 11:30 PM.", "0.61.0"],
      ["Quiet hours switch", "Phone Settings › Notifications › Quiet hours. On (default): automatic notifications keep to the window above. Off: they come at any time, also at night, on the off day and on holidays. The office can lock it on (Access › Notifications › “Can change quiet hours”); then the phone shows “Set by your manager”. The owner can always change it.", "Testing on Friday (your off day): turn Quiet hours off and every notification comes at once.", "0.62.0"],
      ["Working time counts", "Every “after N hours” rule counts only working hours: nights, the off day and holidays don't count.", "Task given 8 PM, rule “after 2 h”, duty ends 9 PM, starts 11 AM: it fires at 12 noon next day.", "0.61.0"],
    ]],
    ["Tasks you give", [
      ["No deadline picked", "If the person hasn't picked a deadline after the set working hours (Access › Date rules, default 4 h), the giver gets a reminder.", "Task given 10 AM, no deadline by 2 PM: “Pick your deadline” reminder.", "0.55"],
      ["Not seen · SMS", "If the task isn't opened after the set working hours (default 2 h), the giver's phone sends one SMS; a second one at the start of the next working day; then the giver is told to call.", "Not opened by 1 PM: SMS “New task from Ali…”.", "0.42"],
      ["Deadline locked", "On (default): once the person sets a deadline, they can only press 📅 Extend deadline and wait for approval.", "Deadline Friday 5 PM: to move it, they ask; the giver approves or keeps it.", "0.55"],
      ["Extend without asking", "How many times the person may move their own deadline without asking (default Never).", "Set to 1×: the first move goes through, the second one asks.", "0.55"],
      ["Not done yet", "↩ Not done yet sends the task back with a reason; their own reminder is cleared so they set it again.", "“Photo missing”: the task comes back to them with that reason.", ""],
    ]],
    ["Urgent task with no deadline", [
      ["First reminder", "An urgent task with no deadline reminds the person after the set working hours (Access › Date rules, default 2 h).", "Given 11 AM, default: reminder at 1 PM.", "0.61.0"],
      ["Repeats", "It repeats with the same gap, the set number of times (default 3), and stops as soon as a deadline is set.", "Reminders at 1 PM, 3 PM and 5 PM, then no more.", "0.61.0"],
      ["Giver is told", "Each time, the giver gets “🔴 Name hasn't set a date” (inside duty hours only). Several at once come as one notification.", "Two urgent tasks waiting: “🔴 2 urgent tasks still have no date”.", "0.61.0"],
      ["How it shows", "The person's style is set on the website: Full screen (ringing popup), Normal (notification) or Off.", "Full screen: the phone rings like a call-back reminder.", "0.61.0"],
      ["Backup SMS", "Only if the task never reached the person's phone, the giver's phone sends one SMS (from the giver's SIM).", "Their phone is off all day: one SMS goes so they know.", "0.61.0"],
    ]],
    ["Timeline buttons", [
      ["⚠ Stuck pauses the task", "Stuck needs a reason (typed or voice). The task's deadline and late time pause until the giver answers. The pause shows as “⏸ Paused 2h 10m”.", "Stuck at 2 PM, giver answers 4:10 PM: deadline moves 2 h 10 m later.", "0.61.0"],
      ["Stuck count", "Team Performance shows how many times each person pressed Stuck. The score doesn't change.", "“⚠ Stuck 3× · paused 5h”.", "0.61.0"],
      ["Ask removed", "The ❓ Ask button is gone: a normal message (or @someone) does the same. Old Ask messages show as normal messages.", "", "0.61.0"],
      ["📅 Extend deadline", "Asks the giver for a new deadline with a reason; the giver approves or keeps the old one.", "", "0.55"],
    ]],
    ["Timeline attachments", [
      ["📎 Attach", "The 📎 in a Timeline has Camera, Gallery, Files (on the phone) and From WhatsApp. Photos are always made small (about 150 KB). Voice notes, documents and videos can be up to 15 MB.", "A 20 MB video: “Too big, share a smaller file”.", "0.62.0"],
      ["From WhatsApp", "Tap From WhatsApp in the task, then in WhatsApp hold the photo, voice note or file › Share › RB360. It lands in the same task, marked “↪ from WhatsApp”. Sharing to RB360 from any other app asks which open task it is for.", "A plot owner sends his CNIC photo on WhatsApp: share it to RB360 and it goes into his plot step.", "0.62.0"],
      ["No internet", "Attaching works with no internet. The file shows 🕓 and goes up by itself when data is on.", "", "0.62.0"],
      ["Opening files", "Tap a document to open it, a video to play it, a voice note's ▶ to play it. The website shows them in the task's Timeline too.", "", "0.62.0"],
      ["Kept 90 days", "Like photos and voice notes, attached files are removed 90 days after the task closes. The message text stays.", "", "0.62.0"],
    ]],
    ["Plot files", [
      ["Waiting for plot owner: reason", "Pressing Waiting for plot owner opens a window. A reason is needed (type it or use 🎤). You can also pick the day he said he will come, and a time if he gave one. It shows on the step, the Timeline and the website.", "“Out of city · comes Thu 3 Dec”.", "0.62.0"],
      ["Come-day reminder", "Day only: a reminder at the start of your duty that day. Day and time: a reminder exactly at that time. It works with no internet. Until that day the daily “still waiting” reminder stops. If the day passes and you haven't pressed Plot owner came, the daily reminder starts again the next working day.", "He says he will come Thursday: reminder Thursday at the start of your duty, “⏳ Plot 214 · Ali Raza said he would come today”.", "0.62.0"],
      ["Waiting for plot owner: how the time pause works", "When you press Waiting for plot owner, the task's clock stops. When you press Plot owner came, the clock starts again, and the due time moves later by exactly the duty hours you waited. Nights, your weekly off day and holidays are not counted.", "A step gets 2 working days. Duty is 9 AM to 6 PM, Friday off. The task comes Monday 10 AM, so it is due Wednesday 10 AM.\n• You press Waiting on Monday 3 PM. The plot owner comes Tuesday 11 AM. You waited 3 duty hours on Monday (3 to 6 PM) and 2 on Tuesday (9 to 11 AM) = 5 hours. New due time: Wednesday 3 PM.\n• You press Waiting on Monday 11 AM. He comes the same day at 5 PM = 6 hours. New due time: Wednesday 4 PM.\n• You press Waiting on Thursday 4 PM. He comes Saturday 10 AM. Thursday 2 hours + Friday 0 (off day) + Saturday 1 hour = 3 hours. The due time moves 3 duty hours later.", "0.62.0"],
      ["List of waits", "Each step keeps every wait (from, to, duty hours, reason, the day he said). Plot files shows “⏸ 2 waits · 7h paused” on the step; open the step to see each wait. Team Performance › Person shows “⏸ Plot owner waits 4× · paused 12h”. The score doesn't change.", "", "0.62.0"],
      ["Photo needed lines", "Checklist lines marked “Photo needed” in the SOP sheet show 📷. They can't be ticked until at least one photo is added (Camera or Gallery, then the photo editor). Several photos can be added. A small × on a photo removes it (only the person who added it, and only until Done). Removing the last photo unticks the line. The photos also go on the Timeline and show on the website.", "“RB contractor built 4 corner pillars”: add 3 photos of the pillars, then tick the line.", "0.62.0"],
    ]],
    ["Reminders set by the office", [
      ["Dossier reminder", "Up to 2 per person, each “duty end minus or plus N minutes” (default 1 hour before and at duty end). Full-screen popup with the unticked tasks, Open dossier and Skip. Only when something due is unticked.", "Duty ends 9 PM: popups at 8 PM and 9 PM if a task is unticked.", "0.61.0"],
      ["Dossier Skip", "Each Skip is quietly noted and shown in that person's Team Performance. Score not changed.", "“📋 Dossier reminder skipped 2× this week”.", "0.61.0"],
      ["Review reminder", "Up to 2 per person the same way (default 8 h and 3 h before duty end). Only when something is waiting. Snooze and Skip.", "Duty ends 9 PM: 1 PM and 6 PM.", "0.61.0"],
      ["Never on off days", "Office reminders never come on the weekly off day or a holiday.", "Friday off: no dossier or review popup on Friday.", "0.61.0"],
      ["Works offline", "The phone keeps the last settings from the website and sets its own alarms, so reminders work with no internet, after a restart and in battery saver.", "Mobile data off all day: the 8 PM popup still comes.", "0.61.0"],
    ]],
    ["Full-screen popups", [
      ["🔇 Silence", "Every full-screen popup has a yellow Silence button at the bottom. It stops the sound and vibration; the popup stays.", "", "0.61.0"],
      ["Side buttons", "Volume up/down or the power button stop the sound and vibration at once; the popup stays on screen.", "", "0.61.0"],
      ["Plot step due", "A plot file step rings full screen at its due time (office can set Normal or Off), with a heads-up before (default 1 hour).", "Step due 3 PM: notification at 2 PM, ringing popup at 3 PM.", "0.61.0"],
      ["Notification styles", "The office sets each kind of team notification per person: Full screen, Normal or Off. Read only on the phone.", "", "0.61.0"],
    ]],
    ["After-call popup", [
      ["Only in duty hours", "On by default: outside the notification window calls don't show the popup, unless the number has an open task with you. The office can lock this switch.", "A family call at 11 PM: no popup.", "0.61.0"],
      ["Close by itself", "Off by default. When on (5 to 60 s, 15 s when turned on), a live countdown closes it like Skip. Any touch keeps it open.", "15 s: “⏱ Closes in 12s · touch to keep it open”.", "0.61.0"],
      ["Hold to call", "Off by default. When on, “Call again” calls only after holding it 1 or 2 seconds. A quick tap shows a hint.", "", "0.61.0"],
      ["Per SIM", "A SIM can be switched off; its calls stay quiet unless the number has an open task.", "", "0.44"],
    ]],
    ["Texts and WhatsApp lists", [
      ["Text messages switch", "Off (Access › Calls and texts): every SMS part disappears from the phone, including home lists, widgets, the calendar, reviews and the morning summary. Scheduled texts pause and nothing is deleted; they come back when it is on. Team SMS are not affected.", "Switched off: texts vanish from Home, the widget and the calendar. Switched on again: they all come back.", "0.61.1"],
      ["Task list message", "Lists go in groups: 🔴 Late, ⏳ No time yet, 📅 Today, 🗓 Later, numbered, with a blank line after each task. WhatsApp shows the headings in bold.", "*Ali, your open tasks (27 Nov)* … 1. Send the map / Due 25 Nov, 5:00 pm · T-12", "0.61.0"],
      ["Send which tasks?", "Before sending, you tick the groups. Late, No time yet and Today are ticked; Later isn't. The daily list sends the first three.", "", "0.61.0"],
      ["Pending list from the website", "Team Performance › Person › Send pending list on WhatsApp: all open tasks from everyone (not dossier); “Last sent” shows who sent it last.", "", "0.61.0"],
    ]],
    ["Phone and backup", [
      ["Cloud backup", "Each phone backs up every night (7 nights kept). Normally only while charging; if 2 days pass without one, it uploads anyway.", "", "0.61.0"],
      ["Last backup", "Team › People shows each phone's last backup; red after 2 nights.", "", "0.61.0"],
      ["Web settings received", "Every time the website changes someone's settings it notes the time, and their phone writes back which change it has. Team › People › Phone health shows “✓ Web settings: up to date”, or in red “✗ Web settings: old” with the red dot next to the name. Phones older than 0.62 show nothing new.", "You change Ali's date rules at 5:09 PM while his phone is off: it shows “old” until he opens the app with internet on.", "0.62.0"],
      ["Settings search", "Phone Settings has a search bar at the top. Type a word and tap the page it is on.", "Type “quiet” → Notifications. Type “theme” → Appearance.", "0.62.0"],
      ["Voice typing", "The 🎤 in message and note boxes is on for everyone (existing phones were switched on once; anyone can turn it off in Settings › Voice typing). Languages: Urdu (default) and English.", "", "0.62.0"],
      ["Phone health", "Each phone reports its version and permissions when online. People with “See phone health” see a card and a red dot when something is off.", "", "0.61.0"],
      ["Sign-in screen", "New installs start with Sign in with Google or Use without an account (guest, no team parts). After a phone joins the team, Guest never comes back.", "", "0.61.0"],
      ["Look", "New installs start in Light with Small text; existing phones were switched once. Anyone can change it in Settings › Appearance.", "", "0.61.0"],
      ["Google Calendar", "On by default. Shows only the calendars of the Gmail signed in to RB360 (main one on, others off). Holidays and birthdays are hidden.", "", "0.61.0"],
    ]],
    ["Access", [
      ["Designation then person", "Each designation has defaults; a person's own changes win (“✎ changed”, tap it to go back to default).", "", ""],
      ["Copy to", "“Copy all…” copies every section; each section's “Copy this section to…” copies only that section. Works for designations and people.", "", "0.61.0"],
      ["Owners", "The app owner can do everything; call recording still needs them to turn it on in their phone.", "", ""],
      ["One section at a time", "Team › Access shows one section at a time: pick it in the left menu (on a phone, the “Section ▾” picker). The number next to each name is how many settings it holds. On a phone the grey “default” tag is hidden; only rows that differ show “✎ changed”.", "Open “In the portal” to set which website pages he can open.", ""],
    ]],
    ["Website settings", [
      ["Switches and ticks save at once", "In Settings, a switch or a tick box saves the moment you click it, and “✓ Saved” shows at the bottom.", "Turning off “Needs help box” in Team Performance hides it for everyone straight away.", ""],
      ["Numbers wait for Save", "A typed number or a picked choice is not live yet: the changed row turns darker blue and a bar at the bottom shows “1 change not saved yet” with Undo and Save. Typed numbers stay even if you open another section and come back.", "Change “Red row when the score is under” from 60 to 55: nothing changes for the team until you press Save.", ""],
    ]],
  ];

  const LATEST = "0.62.0";   // bump with each build: rules changed in it get the green tag
  RB.rulesHtml = () => {
    const n = RB.RULES.reduce((t, [, rs]) => t + rs.length, 0);
    // The search sits in the card's frozen top, so it stays in view while the rules scroll (Settings redesign J4).
    return `<div class="card rules st-card"><div class="subhead"><input id="rulesq" class="rulesq" placeholder="🔍 Search rules, e.g. urgent, SMS, dossier" aria-label="Search rules" style="flex:1;min-width:0;margin:0"><span class="sub" id="rulesn">${n} rules</span></div>
      ${RB.RULES.map(([h, rs]) => `<div class="rulesg"><div class="mh2 rulesh">${esc(h.toUpperCase())}</div>${rs.map((r) => { const [t, w, ex, v] = r; return `<div class="rule" data-t="${esc((h + " " + r.join(" ")).toLowerCase())}"><div class="ruleh"><b>${esc(t)}</b>${v ? `<span class="tag ${v === LATEST ? "grn" : ""}">Changed in version ${esc(v)}</span>` : ""}</div><div>${esc(w)}</div>${ex ? `<div class="rulex">Example: ${esc(ex)}</div>` : ""}</div>`; }).join("")}</div>`).join("")}
      <div class="muted" id="rulesnone" style="margin-top:12px;display:none">No rule matches.</div></div>`;
  };
  // Filters in place (no redraw), so typing never makes the page jump.
  RB.rulesBind = (root) => {
    const i = root.querySelector("#rulesq"); if (!i) return;
    i.oninput = () => {
      const q = i.value.toLowerCase().trim(); let n = 0;
      root.querySelectorAll(".rulesg").forEach((g) => { let k = 0; g.querySelectorAll(".rule").forEach((r) => { const on = !q || r.dataset.t.includes(q); r.style.display = on ? "" : "none"; k += on; }); g.style.display = k ? "" : "none"; n += k; });
      root.querySelector("#rulesn").textContent = n + (n === 1 ? " rule" : " rules");
      root.querySelector("#rulesnone").style.display = n ? "none" : "";
    };
  };
})();
