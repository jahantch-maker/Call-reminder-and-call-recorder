/* Team Performance numbers (locked 25 Nov, pending-list J1-J33). Built from zero, apart from the Meetings numbers in calc.js.
 *
 * Every task of a person comes in one shape: his own My tasks (own/{uid}/tasks), tasks given to him and plot file steps
 * (tasks), and his follow-ups (days/{uid}.fuAll, sent by the phone from v0.59).
 *  · First due date ("promise"): own tasks and follow-ups keep their first date; a given task's deadline only changes when
 *    the giver or a manager approves it, so its current deadline is the promise. Moving a date later never changes it.
 *  · On time % = on time ÷ (on time + late + overdue + not scheduled). Late with an outside reason counts nowhere.
 *  · Not scheduled = open with no date for more than the grace days. Moved N+ = tasks moved N or more times.
 *  · Dossier % = ticked ÷ due over the days (days/{uid}.dosDays). A day still open (today) never pulls it down.
 *  · Score = On time % × weight + Dossier % × weight; no dossier counts as 0, so nobody tops the list without one.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, DAY = RB.DAY, GRACE = 60000;

  // ------------------------------------------------------------------ settings (config/meeting.perf)
  const DEF = {
    wOn: 50, wDos: 50, grace: 5, older: 5, movedN: 3, redBelow: 60, weakDay: 70, period: "week", weeks: 8,
    // [main screen, person report]; null = that place has no such part
    show: { oldOver: [true, true], oldUns: [null, true], moved: [null, true], needs: [false, null], trend: [true, true], dosBars: [true, true], byType: [null, true] },
    oldOverN: 3, needsN: 3, hidden: [],
  };
  function cfg() {
    const p = (D.cfg && D.cfg.perf) || {};
    const show = {}; Object.keys(DEF.show).forEach((k) => (show[k] = DEF.show[k].map((v, i) => (v === null ? null : p.show && p.show[k] && typeof p.show[k][i] === "boolean" ? p.show[k][i] : v))));
    return Object.assign({}, DEF, p, { show, hidden: Array.isArray(p.hidden) ? p.hidden : [] });
  }

  // ------------------------------------------------------------------ days and periods
  const sod = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const addDays = (t, n) => { const d = new Date(t); d.setDate(d.getDate() + n); return d.getTime(); };   // safe across clock changes
  /** RB's week starts on Saturday (locked). */
  const weekStart = (t) => { const d = sod(t); return addDays(d, -((new Date(d).getDay() + 1) % 7)); };
  const PERIODS = [["today", "Today"], ["week", "This week"], ["2w", "2 weeks"], ["month", "This month"]];
  function range(p, now) {
    const t = sod(now), ws = weekStart(now);
    if (p === "today") return [t, addDays(t, 1)];
    if (p === "2w") return [addDays(ws, -7), addDays(ws, 7)];
    if (p === "month") { const d = new Date(now); return [new Date(d.getFullYear(), d.getMonth(), 1).getTime(), new Date(d.getFullYear(), d.getMonth() + 1, 1).getTime()]; }
    return [ws, addDays(ws, 7)];
  }
  /** The same length of time just before, for the ▲▼ change on the cards. */
  function prevRange(p, now) {
    const [a, b] = range(p, now);
    if (p === "month") { const d = new Date(a); return [new Date(d.getFullYear(), d.getMonth() - 1, 1).getTime(), a]; }
    const n = Math.round((b - a) / DAY);
    return [addDays(a, -n), a];
  }
  const dayList = (a, b) => { const out = []; for (let t = a; t < b; t = addDays(t, 1)) out.push(t); return out; };
  const isoDay = (t) => { const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
  const parseDay = (s) => { const [y, m, d] = String(s).split("-").map(Number); return new Date(y, m - 1, d).getTime(); };

  /** A person's timetable (Team › Timetables): working minutes and week days off. Until one is set: 11 AM-9 PM, Friday off. */
  function tt(uid) {
    const s = RB.seatOfUid(uid), t = s && RB.ttOf ? RB.ttOf(s) : null;
    return { start: t ? t.start : 660, end: t ? t.end : 1260, off: t && Array.isArray(t.off) ? t.off : [5] };
  }
  const holidaySet = () => new Set(((D.holidays && D.holidays.dates) || []).map((h) => h.d));
  const isOff = (uid, t) => tt(uid).off.includes(new Date(t).getDay()) || holidaySet().has(isoDay(t));

  // ------------------------------------------------------------------ people
  /** Everyone this person may see (Access levels › Team Performance › how far), minus the ones hidden in Settings. */
  function people(withHidden) {
    const vis = RB.visibleSeats(), ids = new Set(vis.map((s) => s.uid)), hid = new Set(cfg().hidden);
    return D.members.filter((m) => m.status === "active" && ids.has(m.uid) && (withHidden || !hid.has(m.uid)))
      .sort((a, b) => RB.first(a.name).localeCompare(RB.first(b.name)));
  }

  // ------------------------------------------------------------------ tasks in one shape
  const outsideNames = () => new Set(((RB.calc && RB.calc.reasonsCfg && RB.calc.reasonsCfg().outside) || []).map((x) => x.name));
  let memo = null;
  /** Every task of uid. Cached until the data changes (one render reads it many times). */
  function items(uid) {
    const key = [D.tasks, D.own[uid], D.days[uid]];
    if (memo && memo.uid === uid && memo.key.every((k, i) => k === key[i])) return memo.list;
    const out = [], out2 = outsideNames();
    const outside = (rs) => { const r = rs && rs.length ? rs[rs.length - 1] : null; return !!(r && (r.o || out2.has(r.r))); };
    (D.own[uid] || []).forEach((t) => out.push({
      kind: "own", id: "o" + t.id, raw: t, title: t.title || "", person: t.person || "", promise: t.firstDueAt || t.dueAt || 0, due: t.dueAt || 0,
      done: !!t.done, doneAt: t.doneAt || 0, moves: t.moves || 0, created: t.createdAt || 0, dur: t.durationMin || 30,
      reasons: t.reasons || [], outside: outside(t.reasons), events: t.events || [],
    }));
    D.tasks.forEach((t) => {
      if (t.toUid !== uid || t.status === "cancelled" || t.status === "returned") return;
      const plot = !!t.plot;
      out.push({
        kind: plot ? "plot" : "given", id: "t" + t.id, taskId: t.id, raw: t, title: plot ? "Plot " + (t.plotNo || "") + " · " + (t.title || "") : t.title || "",
        // N7 A plot step waiting for the plot owner is never late meanwhile; "Plot owner came" moves its due by the duty hours waited.
        promise: plot && t.waitAt && t.due ? t.due + Math.max(0, Date.now() - t.waitAt) : t.due || 0, due: t.due || 0, done: t.status === "done" || t.status === "checked", doneAt: t.doneAt || 0, moves: 0,
        created: t.createdAt || 0, dur: 30, from: t.fromName || "", chain: t.passedTo ? [RB.first(t.fromName || ""), RB.first(t.toName || ""), t.passedTo] : null, urgent: !!t.urgent, stage: plot && t.step ? String(t.step).split(".")[0] : "",
        reasons: t.lateReasons || [], outside: outside(t.lateReasons), events: t.events || [],
      });
    });
    const day = D.days[uid] || {};
    // The team task behind a follow-up (same title, he gave it or got it), newest open first: "Basharat → Husnain (→ Ilyas)".
    const taskOf = (f) => { const k = (f.title || "").trim(); return k ? D.tasks.filter((t) => (t.title || "").trim() === k && (t.fromUid === uid || t.toUid === uid) && t.status !== "cancelled")
      .sort((a, b) => (b.status !== "done" && b.status !== "checked") - (a.status !== "done" && a.status !== "checked") || (b.createdAt || 0) - (a.createdAt || 0))[0] : null; };
    (day.fuAll || []).forEach((f) => { const t = taskOf(f), chain = t ? [RB.first(t.fromName || ""), RB.first(t.toName || "")].concat(t.passedTo ? [t.passedTo] : []) : null; out.push({
      kind: "fu", id: "f" + f.id, raw: f, chain, title: (f.title || "Call back") + (f.name && !chain ? " · " + f.name : ""), promise: f.first || f.due || 0, due: f.due || 0,
      done: !!f.done, doneAt: f.doneAt || 0, moves: (f.moves || []).length, created: f.created || 0, dur: 15, star: !!f.star,
      reasons: (f.moves || []).map((m) => ({ at: m.at, r: m.r })), outside: false, events: f.events || [],
    }); });
    memo = { uid, key, list: out };
    return out;
  }
  const KIND = { own: "My task", fu: "Follow-up", given: "Assigned", plot: "Plot file" };

  // ------------------------------------------------------------------ the facts
  /** Numbers for one person over [a, b), as things stood at T (now, or the end of a past week). */
  function facts(uid, a, b, T) {
    const c = cfg(), list = items(uid);
    const f = { onTime: 0, late: 0, lateOut: 0, overdue: 0, uns: 0, moved: 0, done: 0, kinds: {} };
    const end = Math.min(b, T);
    const k = (x) => (f.kinds[x.kind] = f.kinds[x.kind] || { onTime: 0, late: 0, overdue: 0, uns: 0 });
    list.forEach((x) => {
      if (x.created > T) return;
      const doneBy = x.done && x.doneAt <= T;
      if (doneBy && x.promise >= a && x.promise < end) {
        f.done++;
        if (x.doneAt - x.promise <= GRACE) { f.onTime++; k(x).onTime++; }
        else if (x.outside) f.lateOut++;
        else { f.late++; k(x).late++; }
      }
      if (!doneBy && x.promise > 0 && x.promise < T) { f.overdue++; k(x).overdue++; }
      if (!doneBy && !x.promise && x.created && x.created < T - c.grace * DAY) { f.uns++; k(x).uns++; }
      if (x.moves >= c.movedN && (!doneBy || (x.doneAt >= a && x.doneAt < b))) f.moved++;
    });
    const n = f.onTime + f.late + f.overdue + f.uns;
    f.counted = n;
    f.on = n ? Math.round((f.onTime / n) * 100) : null;
    return f;
  }

  /** Dossier days of uid in [a, b): [{ t, due, done, open, items }]; a day still open never counts against him. */
  function dosDays(uid, a, b, now) {
    const map = {};
    ((D.days[uid] || {}).dosDays || []).forEach((d) => (map[d.d] = d.i || []));
    return dayList(a, b).filter((t) => !isOff(uid, t) || (map[isoDay(t)] || []).length).map((t) => {
      const its = map[isoDay(t)];
      if (!its) return { t, due: 0, done: 0, open: t >= sod(now), items: [], none: true };
      const done = its.filter((i) => i.s === "D").length, missed = its.filter((i) => i.s === "M").length;
      return { t, due: done + missed, done, all: its.length, open: its.some((i) => i.s === "O"), items: its, future: t > now };
    });
  }
  function dosPct(days) {
    const due = days.reduce((s, d) => s + d.due, 0), done = days.reduce((s, d) => s + d.done, 0);
    return { due, done, pct: due ? Math.round((done / due) * 100) : null };
  }
  const hasDossier = (uid) => ((D.days[uid] || {}).dosDays || []).length > 0;

  function score(on, dos) {
    const c = cfg(), w = (Number(c.wOn) || 0) + (Number(c.wDos) || 0);
    if (on == null && dos == null) return null;
    return w ? Math.round(((on || 0) * c.wOn + (dos || 0) * c.wDos) / w) : 0;
  }

  /** Everything the screens need for one person and one period. */
  function person(uid, p, now) {
    const [a, b] = range(p, now), f = facts(uid, a, b, now), days = dosDays(uid, a, b, now), d = dosPct(days);
    return { uid, f, days, dos: d.pct, dosDone: d.done, dosDue: d.due, score: score(f.on, d.pct) };
  }
  /** Weekly scores, oldest first (the 8-week line). */
  function trend(uid, now, weeks) {
    const out = [];
    for (let i = weeks - 1; i >= 0; i--) {
      const a = addDays(weekStart(now), -7 * i), b = addDays(a, 7), T = Math.min(b, now);
      const f = facts(uid, a, b, T), d = dosPct(dosDays(uid, a, b, T));
      out.push(score(f.on, d.pct));
    }
    return out;
  }
  /** All rows of a period, ranked by Score (rank always follows Score). */
  function board(p, now) {
    const rows = people().map((m) => Object.assign({ m }, person(m.uid, p, now)));
    rows.slice().sort((x, y) => (y.score == null ? -1 : y.score) - (x.score == null ? -1 : x.score) || RB.first(x.m.name).localeCompare(RB.first(y.m.name)))
      .forEach((r, i) => (r.rank = i + 1));
    return rows;
  }
  const avg = (arr) => { const v = arr.filter((x) => x != null); return v.length ? Math.round(v.reduce((s, x) => s + x, 0) / v.length) : null; };

  RB.tp = { DEF, cfg, sod, addDays, weekStart, PERIODS, range, prevRange, dayList, isoDay, parseDay, tt, isOff, people, items, KIND, facts, dosDays, dosPct, hasDossier, score, person, trend, board, avg, GRACE };
})();
