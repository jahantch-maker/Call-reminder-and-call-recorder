/* Shared numbers: every task of every person in the same shape, and the plain facts (due, on time, late, moved...). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, esc = RB.esc, first = RB.first, member = RB.member;
  const DAY = 86400000, GRACE = 60000;
  const OWN_DEFAULT = ["Forgot", "Too much work", "Waiting on staff", "Date was too tight", "Priority changed"];
  const OUT_DEFAULT = ["Material late", "Payment held", "Client / vendor delay", "Weather"];
  const RED_DEFAULT = { overdue: 2, late: 3, moved: 5, noDate: 3, avgLate: 3 };
  const STEP_NAMES = { attendance: "Attendance", lastActions: "Last actions", trend: "Team trend", reasons: "Top late reasons", people: "People → Person", readback: "Read-back", minutes: "Minutes" };
  const STEPS_DEFAULT = {
    daily: [["attendance", 1, true], ["lastActions", 1, true], ["people", 6, true], ["trend", 3, false], ["reasons", 5, false], ["readback", 1, true], ["minutes", 1, true]],
    weekly: [["attendance", 2, true], ["lastActions", 5, true], ["trend", 3, true], ["reasons", 12, true], ["people", 10, true], ["readback", 3, true], ["minutes", 5, true]],
  };

  // ------------------------------------------------------------------ state

  const reasonsCfg = () => {
    const r = D.reasons || {};
    const norm = (a, d) => (Array.isArray(a) && a.length ? a : d.map((n) => ({ name: n }))).map((x) => (typeof x === "string" ? { name: x } : x));
    return { own: norm(r.own, OWN_DEFAULT), outside: norm(r.outside, OUT_DEFAULT) };
  };
  const outsideSet = () => new Set(reasonsCfg().outside.map((x) => x.name));
  const red = () => Object.assign({}, RED_DEFAULT, (D.cfg && D.cfg.red) || {});
  const stepsFor = (type) => ((D.cfg && D.cfg.steps && D.cfg.steps[type]) || STEPS_DEFAULT[type]).map((x) => x.slice());

  // ------------------------------------------------------------------ data model
  function people() {
    const vis = RB.visibleSeats(), ownerUid = D.team && D.team.ownerUid;
    const ids = new Set(vis.map((s) => s.uid));
    let list = D.members.filter((m) => m.status === "active" && (ids.has(m.uid) || (RB.isOwner() && !RB.seatOfUid(m.uid))));
    const own = list.filter((m) => m.uid === ownerUid || (RB.seatOfUid(m.uid) || {}).role === "owner");
    const rest = list.filter((m) => !own.includes(m)).sort((a, b) => first(a.name).localeCompare(first(b.name)));
    return own.sort((a, b) => first(a.name).localeCompare(first(b.name))).concat(rest);
  }
  const team = people;

  /** Every task of every person, the same shape: given (team) tasks and own tasks. */
  function items() {
    const out = [];
    D.tasks.forEach((t) => {
      if (!t.toUid || t.status === "cancelled" || t.status === "returned") return;
      out.push({
        src: "team", id: t.id, uid: t.toUid, title: t.title, promise: t.firstDue || t.due || 0, due: t.due || 0,
        done: t.status === "done" || t.status === "checked", doneAt: t.doneAt || 0, moves: t.extCount || 0,
        reasons: t.lateReasons || [], events: t.events || [], meeting: !!t.meeting, from: t.fromName, createdAt: t.createdAt || 0, raw: t,
      });
    });
    Object.keys(D.own).forEach((uid) => (D.own[uid] || []).forEach((t) => {
      out.push({
        src: "own", id: t.id, uid, title: t.title, promise: t.firstDueAt || t.dueAt || 0, due: t.dueAt || 0, done: !!t.done,
        doneAt: t.doneAt || 0, moves: t.moves || 0, reasons: t.reasons || [], events: t.events || [], person: t.person, createdAt: t.createdAt || 0, raw: t,
      });
    }));
    return out;
  }
  const isLate = (x) => x.done && x.promise > 0 && x.doneAt - x.promise > GRACE;
  const isOverdue = (x, now) => !x.done && x.promise > 0 && x.promise < now;
  const lastReason = (x) => (x.reasons && x.reasons.length ? x.reasons[x.reasons.length - 1] : null);
  const isOutside = (x) => { const r = lastReason(x); return !!(r && (r.o || outsideSet().has(r.r))); };
  const daysLate = (x) => Math.max(1, Math.round((startOfDay(x.doneAt) - startOfDay(x.promise)) / DAY));

  function startOfDay(t) { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); }
  /** RB's week runs Saturday to Thursday; Friday is the weekly off day. */
  function weekStart(t) { const d = new Date(startOfDay(t)); const wd = (d.getDay() + 1) % 7; return d.getTime() - wd * DAY; }
  const isOff = (t) => new Date(t).getDay() === 5;
  /** Start of the rolling window: the last 7 work days (Fridays skipped) or the last 30 days, today included. */
  function rollStart(period, now) {
    const sod = startOfDay(now);
    if (period === "month") return sod - 29 * DAY;
    let d = sod, n = 0;
    for (;;) { if (!isOff(d)) n++; if (n === 7) return d; d -= DAY; }
  }
  function range(period, now) {
    // v0.50 rolling: the numbers never go empty when a new week or month starts.
    return [rollStart(period, now), startOfDay(now) + DAY];
  }

  /** The plain facts for one person (or everyone, uid null) in [a, b). */
  function facts(all, uid, a, b, now) {
    const mine = all.filter((x) => uid == null ? team().some((p) => p.uid === x.uid) : x.uid === uid);
    const due = mine.filter((x) => x.promise >= a && x.promise < b && x.promise <= now);
    const f = { due: due.length, onTime: 0, late: 0, lateOut: 0, overdue: 0, moved: 0, noDate: 0, avgLate: 0, done: 0 };
    let lateDays = 0, lateN = 0;
    due.forEach((x) => {
      f.moved += x.moves;
      if (x.done) f.done++;
      if (x.done && !isLate(x)) f.onTime++;
      else if (isLate(x)) { if (isOutside(x)) f.lateOut++; else f.late++; lateDays += daysLate(x); lateN++; }
    });
    // Still overdue: every open task past its deadline, whatever day it fell due.
    f.overdue = mine.filter((x) => isOverdue(x, now)).length;
    f.noDate = mine.filter((x) => !x.done && !x.promise).length;
    f.avgLate = lateN ? Math.round(lateDays / lateN) : 0;
    f.pct = f.due ? Math.round((f.onTime / f.due) * 100) : null;
    return f;
  }
  function weeklyPct(all, uid, now, weeks) {
    const out = [];
    for (let i = weeks - 1; i >= 0; i--) { const a = weekStart(now) - i * 7 * DAY; out.push(facts(all, uid, a, a + 7 * DAY, now).pct); }
    return out;
  }
  function reasonCounts(all, uid, a, b) {
    const c = {};
    all.forEach((x) => {
      if (uid != null && x.uid !== uid) return;
      if (uid == null && !team().some((p) => p.uid === x.uid)) return;
      (x.reasons || []).forEach((r) => { if (r.at >= a && r.at < b) { const k = r.r || "Other"; c[k] = c[k] || { n: 0, out: !!r.o || outsideSet().has(k), items: [] }; c[k].n++; c[k].items.push(x); } });
    });
    return Object.entries(c).sort((p, q) => q[1].n - p[1].n);
  }
  RB.calc = { OWN_DEFAULT, OUT_DEFAULT, RED_DEFAULT, STEP_NAMES, STEPS_DEFAULT, reasonsCfg, outsideSet, red, stepsFor, people, team, items, isLate, isOverdue, lastReason, isOutside, daysLate, startOfDay, weekStart, isOff, range, facts, weeklyPct, reasonCounts, GRACE };
})();
