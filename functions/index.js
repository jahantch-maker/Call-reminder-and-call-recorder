/* Plot files: the cloud part (Firebase Cloud Functions, Mumbai region, close to Pakistan).
 *
 * A plot step is a normal team task with plot: true (see web/plotfiles.js for the shape). The phone only marks its own
 * step Done or Returned. This function then opens what comes next, from the published SOP (teams/{t}/config/plotsop):
 *   - Done: every Flow row "from this step, when Done" (and every picked option's "Opens step"), when its "Only if" pick
 *     matches and every step it "Also waits for" is Done. A step that was given back after a Return reopens only the
 *     step that returned it.
 *   - Returned: the step before it, for the role it is returned to, goes back to the person who did it, with the reason.
 * New task ids are fixed by what caused them, so a retry or two steps finishing at once never make the same task twice.
 * Nothing of the SOP is in this code.
 */
const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

initializeApp();
const db = getFirestore();

const DONE = ["checked", "done"];
const isDone = (t) => t && t.doneAt > 0 && DONE.includes(t.status);
const stepOf = (txt) => ((String(txt || "").match(/^\d+\.\d+/) || [])[0]) || "";
/** "6.4 · 3b" -> { step: "6.4", line: "3", opt: "3b" } */
const ref = (txt) => {
  const m = String(txt || "").trim().match(/^(\d+\.\d+)\s*[·.\-]\s*(\d+)(\w*)$/);
  return m ? { step: m[1], line: m[2], opt: m[2] + m[3] } : null;
};

// ------------------------------------------------------------------ due time (a first guess; the doer's phone sets it again).
// The doer's own timetable (web Team › Timetables, locked 23 Nov) and the company holidays (web Settings › Holidays) decide
// working hours and days off. Nobody on a timetable yet: 11 AM - 9 PM Pakistan time, Friday off. Same rules as the phone.
const PKT = 5 * 3600000, DAY = 86400000, FALLBACK = { start: 11 * 60, end: 21 * 60, off: [5] };
const dayStart = (ms) => Math.floor((ms + PKT) / DAY) * DAY - PKT;   // midnight PKT of that day, as UTC ms
const at = (day, min) => day + min * 60000;
/** cal = { start, end, off: [week days], hol: Set of "YYYY-MM-DD" } */
const isOff = (cal, day) => cal.off.includes(new Date(day + PKT).getUTCDay()) || cal.hol.has(new Date(day + PKT).toISOString().slice(0, 10));
const nextWork = (cal, day) => { let d = day + DAY, g = 0; while (isOff(cal, d) && g++ < 400) d += DAY; return d; };
function dueOf(timer, days, hours, from, cal) {
  const START = cal.start, END = cal.end, off = (d) => isOff(cal, d), next = (d) => nextWork(cal, d);
  let day = dayStart(from), start = from;
  if (off(day) || from >= at(day, END)) { day = next(day); start = at(day, START); }
  else if (from < at(day, START)) start = at(day, START);
  const end = at(day, END), n = Number(days) || 0, h = Number(hours) || 0, HR = 3600000;
  // The sheet's "Time rules" tab says the same in simple English (locked 23 Nov).
  if (/working hours/i.test(timer || "") && h > 0) return end - start >= h * HR ? start + h * HR : Math.min(at(next(day), START) + h * HR, at(next(day), END));
  if (n > 0) { const clock = start - day; for (let i = 0; i < n; i++) day = next(day); return day + clock; }
  return end;
}

// ------------------------------------------------------------------ the trigger
exports.plotNext = onDocumentUpdated({ document: "teams/{team}/tasks/{task}", region: "asia-south1", retry: true }, async (event) => {
  const before = event.data.before.data() || {}, after = event.data.after.data() || {};
  if (!after.plot) return;
  const doneNow = isDone(after) && !isDone(before);
  const returnedNow = after.status === "returned" && before.status !== "returned";
  if (!doneNow && !returnedNow) return;

  const teamRef = db.doc(`teams/${event.params.team}`);
  const [sopSnap, fileSnap, teamSnap, tasksSnap, membersSnap, desigSnap, holSnap, seatsSnap] = await Promise.all([
    teamRef.collection("config").doc("plotsop").get(),
    teamRef.collection("plotfiles").doc(after.plotId).get(),
    teamRef.get(),
    teamRef.collection("tasks").where("plotId", "==", after.plotId).get(),
    teamRef.collection("members").get(),
    teamRef.collection("config").doc("designations").get(),
    teamRef.collection("config").doc("holidays").get(),
    teamRef.collection("seats").get(),
  ]);
  const sop = sopSnap.data();
  if (!sop || !fileSnap.exists) return;
  const file = fileSnap.data(), ownerUid = (teamSnap.data() || {}).ownerUid || "";
  const steps = {}; (sop.steps || []).forEach((s) => (steps[s.id] = s));
  const tasks = tasksSnap.docs.map((d) => ({ id: d.id, ...d.data() })).filter((t) => t.plot);
  tasks.forEach((t) => { if (t.id === event.params.task) Object.assign(t, after); });
  const members = membersSnap.docs.map((d) => ({ uid: d.id, ...d.data() })).filter((m) => m.status === "active");
  const latest = (sid) => tasks.filter((t) => t.step === sid).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
  const now = Date.now();
  const timetables = (desigSnap.data() || {}).timetables || [], seats = {};
  seatsSnap.docs.forEach((d) => (seats[d.id] = d.data()));
  const hol = new Set(((holSnap.data() || {}).dates || []).map((h) => h.d));
  const calOf = (m) => { const t = timetables.find((x) => x.id === ((seats[m.seatId] || {}).timetable)) || FALLBACK; return { start: t.start, end: t.end, off: t.off || [], hol }; };

  // Picks made in the file so far ("2.2·3" -> "3b"), for "Only if" here and "Shown only if" lines on the new tasks.
  const picks = {};
  tasks.forEach((t) => Object.entries(t.ticks || {}).forEach(([line, v]) => { if (v && /^\d+[a-z]+$/i.test(v.value || "")) picks[`${t.step}·${line}`] = v.value; }));
  const picked = (r) => r && picks[`${r.step}·${r.line}`] === r.opt;

  const from = members.find((m) => m.uid === after.toUid) || { uid: after.toUid, name: after.toName || "", managers: [] };
  const doerOf = (role) => {
    if (file.test && !(file.testRoles || []).includes(role)) return members.find((m) => m.uid === ownerUid);
    return members.find((m) => m.designation === role && m.uid === from.uid) || members.find((m) => m.designation === role);
  };

  /** Creates one step task with a fixed id; an id that already exists means it was made already. */
  async function give(id, s, to, extra) {
    // "Notify on start / done / late" of the SOP: those people see the step in their Follow-ups (cc = { uid: "start,done,late" }).
    const cc = {};
    Object.entries(s.notify || {}).forEach(([when, roles]) => (roles || []).forEach((role) => {
      const m = doerOf(role);
      if (m && m.uid !== to.uid) cc[m.uid] = Array.from(new Set([...(cc[m.uid] ? cc[m.uid].split(",") : []), when])).join(",");
    }));
    const viewers = Array.from(new Set([from.uid, to.uid, ...(to.managers || []), ...(from.managers || []), ...Object.keys(cc)].filter(Boolean)));
    const paused = s.canPause && /created/i.test(s.pauseStarts || "");
    const due = dueOf(s.timer, s.days, s.hours, now, calOf(to)), seat = seats[to.seatId] || {};
    const doc = {
      title: s.title, fromUid: from.uid, fromName: from.name || "", toUid: to.uid, toName: seat.name || to.name || "", toPhone: seat.phone || to.phone || "",
      viewers, createdAt: now, updatedAt: now, deadline: due, urgent: false, dateBy: "giver", due, firstDue: due, status: "dated",
      seenAt: 0, doneAt: 0, checkedAt: 0, moreDue: 0, moreReason: "", moreAt: 0, reason: "", passedTo: "",
      askAt: 0, stuckAt: 0, stuckText: "", lastMsgAt: 0, lastMsgBy: "", lastMsgText: "", photoProof: false, proofAt: 0, deliveredAt: 0,
      locked: true, freeExt: 0, extCount: 0, reminderAt: 0, nPhoto: 0, nVoice: 0, nBy: {},
      events: [{ at: now, text: extra.event }],
      plot: true, plotId: after.plotId, plotNo: after.plotNo || file.plotNo || "", step: s.id, role: s.role, stage: s.stage || "",
      test: !!file.test, ticks: {}, waitAt: paused ? now : 0, timer: s.timer || "", days: s.days === undefined ? null : s.days, hours: s.hours === undefined ? null : s.hours, dueSet: false,
      cc, fromRole: after.role || "", ownerName: file.name || "", ownerWa: file.wa || "", ownerSim2: file.sim2 || "", picks,
      ...(extra.fields || {}),
    };
    try { await teamRef.collection("tasks").doc(id).create(doc); }
    catch (e) { if (e.code !== 6) throw e; }   // 6 = ALREADY_EXISTS: made by an earlier try or by the other step
  }
  const note = (text) => event.data.after.ref.update({ events: FieldValue.arrayUnion({ at: now, text }) });

  if (returnedNow) {
    // The step before this one that the role it is returned to did.
    const back = (sop.flow || []).filter((f) => f.to === after.step).map((f) => latest(f.from))
      .filter((t) => t && (steps[t.step] || {}).role === after.returnTo).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
    if (!back) return note(`⚠ Could not find the ${after.returnTo || "earlier"} step to give back. Tell the Document Officer.`);
    const to = members.find((m) => m.uid === back.toUid) || doerOf(steps[back.step].role);
    if (!to) return note(`⚠ Nobody has the designation ${steps[back.step].role}. Add it on the web in Team.`);
    return give(`pf_${event.params.task}_back`, steps[back.step], to, {
      event: `↩ Returned by ${after.toName || after.role} · “${after.reason || ""}”`,
      fields: { reopen: [after.step], ticks: back.ticks || {}, fromRole: after.role || "" },
    });
  }

  // Done: what it opens.
  const s = steps[after.step] || {};
  let targets = (sop.flow || []).filter((f) => f.from === after.step && /done/i.test(f.when || "Done") && (!f.onlyIf || picked(ref(f.onlyIf))))
    .map((f) => ({ to: f.to, waits: f.waits }));
  (s.checklist || []).forEach((c) => { if (c.type === "Option" && c.opens && picks[`${after.step}·${String(c.no).match(/^\d+/)[0]}`] === c.no) targets.push({ to: c.opens, waits: "" }); });
  if (Array.isArray(after.reopen) && after.reopen.length) targets = after.reopen.map((to) => ({ to, waits: "" }));
  const seen = new Set();
  for (const tg of targets) {
    const ns = steps[tg.to];
    if (!ns || seen.has(tg.to)) continue;
    seen.add(tg.to);
    // "Also waits for": every one of those steps must be Done ("only if X was created": only when X exists;
    // "only if 6.1 · 6b": only when that option was picked).
    const need = [event.params.task];
    let ready = true;
    String(tg.waits || "").split(/[,;]/).map((w) => w.trim()).filter(Boolean).forEach((w) => {
      const sid = stepOf(w), t = latest(sid);
      if (!sid) return;
      // FLOW5 "6.8 (only if 6.1 · 6b)": waits for 6.8 only when that option was picked, even before 6.8 exists.
      const cond = ref((w.match(/only if\s+([^)]+)/i) || [])[1]);
      if (cond && !picked(cond)) return;
      if (!t) { if (!/only if/i.test(w) || cond) ready = false; return; }
      if (!isDone(t)) ready = false; else need.push(t.id);
    });
    if (!ready) continue;
    const to = doerOf(ns.role);
    if (!to) { await note(`⚠ Step ${ns.id} not given: nobody has the designation ${ns.role}. Add it on the web in Team.`); continue; }
    await give(`pf_${ns.id}_${need.sort().join("_")}`.slice(0, 1400), ns, to, { event: `Opened when ${after.step} was Done by ${after.toName || after.role}` });
  }
});
