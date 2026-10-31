/* Sample data for a preview (open the page with ?demo). Nothing is read from or saved to the team. */
window.DEMO_DATA = {
  msgs: {},
  make() {
    const DAY = 86400000, now = Date.now();
    const sod = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
    const at = (days, h) => sod(now) + days * DAY + (h || 16) * 3600000;
    const names = ["Adeel", "Bilal", "Faisal", "Hamza", "Imran", "Kamran", "Shahid", "Usman", "Zubair"];
    const members = [{ uid: "owner", name: "Owner", status: "active", role: "owner", managers: [] }]
      .concat(names.map((n, i) => ({ uid: "m" + i, name: n, status: "active", role: "member", managers: ["owner"], phone: "" })));
    const own = [], reasons = ["Forgot", "Too much work", "Waiting on staff", "Date was too tight", "Material late", "Payment held", "Client / vendor delay"];
    const outside = new Set(["Material late", "Payment held", "Client / vendor delay", "Weather"]);
    const jobs = ["Get steel rates from 3 vendors", "Submit plumber bill for approval", "Confirm tile delivery date", "Site photos of slab work", "Call electrician about DB box", "Send cement order", "Check generator service", "Collect payment cheque", "Update site drawing", "Book crane for Thursday", "Visit Block C plaster", "Follow up municipal NOC"];
    let seed = 7; const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
    const tasks = [];
    names.forEach((n, i) => {
      const uid = "m" + i, skill = [0.95, 0.9, 0.8, 0.85, 0.45, 0.65, 0.8, 0.93, 0.7][i];
      own[uid] = [];
      for (let k = 0; k < 26 + Math.floor(rnd() * 10); k++) {
        const promise = at(-Math.floor(rnd() * 56), 11 + Math.floor(rnd() * 8));
        const past = promise < now;
        const done = past ? rnd() < 0.92 : rnd() < 0.2;
        const lateBy = rnd() < skill ? 0 : (1 + Math.floor(rnd() * 5)) * DAY;
        const doneAt = done ? Math.min(now, promise - 3600000 + lateBy) : 0;
        const moves = rnd() < skill ? 0 : 1 + Math.floor(rnd() * 3);
        const rs = [];
        if (lateBy || moves) { const r = reasons[Math.floor(rnd() * reasons.length)]; rs.push({ at: doneAt || promise, r, k: done ? "late" : "move", o: outside.has(r), n: "" }); }
        const t = { id: uid + "-" + k, title: jobs[Math.floor(rnd() * jobs.length)], person: "", createdAt: promise - 3 * DAY, dueAt: promise + moves * DAY, firstDueAt: promise, moves, done, doneAt, reasons: rs,
          events: [{ at: promise - 3 * DAY, text: "Created · due " + new Date(promise).toDateString() }].concat(moves ? [{ at: promise, text: "Time moved · later" }] : []) };
        if (k % 3 === 0) tasks.push({ id: "t-" + uid + "-" + k, title: t.title, fromUid: "owner", fromName: "Owner", toUid: uid, toName: n, viewers: ["owner", uid], createdAt: t.createdAt, due: t.dueAt, firstDue: promise, extCount: moves, status: done ? "checked" : "dated", doneAt, lateReasons: rs, events: t.events });
        else own[uid].push(t);
      }
      if (i % 3 === 1) own[uid].push({ id: uid + "-nd", title: "Arrange site visit", createdAt: now - 2 * DAY, dueAt: 0, firstDueAt: 0, moves: 0, done: false, doneAt: 0, reasons: [], events: [] });
    });
    const m1 = "meet1";
    const a1 = { id: "a1", title: "Weekly material order list every Monday", fromUid: "owner", fromName: "Owner", toUid: "m2", toName: "Faisal", meeting: true, meetingFrom: "From reason: Material late", due: at(-3), firstDue: at(-3), status: "checked", doneAt: at(-4), createdAt: at(-6), events: [] };
    const a2 = { id: "a2", title: "1 PM check-in calls with site staff", fromUid: "owner", fromName: "Owner", toUid: "m5", toName: "Kamran", meeting: true, meetingFrom: "From person: Kamran", due: at(-2), firstDue: at(-2), status: "dated", doneAt: 0, createdAt: at(-6), events: [] };
    const a3 = { id: "a3", title: "Get 3 quotes for the generator", fromUid: "owner", fromName: "Owner", toUid: "m3", toName: "Hamza", meeting: true, meetingFrom: "Not from the report", due: at(3), firstDue: at(3), status: "dated", doneAt: 0, createdAt: at(-6), events: [] };
    tasks.push(a1, a2, a3);
    const meetings = [{ id: m1, type: "weekly", startedAt: at(-6, 11), closedAt: at(-6, 11.7), plannedMin: 40, attendance: { m0: "present", m1: "present", m2: "present", m3: "late", m4: "present", m5: "absent" }, decisions: [{ at: at(-6, 11), text: "Site visits start at 10 AM from Monday" }], notes: [{ at: at(-6, 11), text: "Cement rate may rise; order early" }], actions: ["a1", "a2", "a3"], agreed: {}, sharedAt: at(-6, 12) }];
    return { team: { id: "demo", name: "Rafiq Builders (sample)", ownerUid: "owner" }, members, tasks, own, reasons: null, cfg: null, meetings };
  },
};
