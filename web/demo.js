/* Sample data for a preview (open the page with ?demo). Nothing is read from or saved to the team. */
window.DEMO_DATA = {
  msgs: { "t-urg": [
    { id: "dm1", at: Date.now() - 7200000, fromName: "Imran", kind: "photo", text: "Leak point", data: "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCABQAHgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDZooor2DywooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigD/9k=" },
    { id: "dm2", at: Date.now() - 3600000, fromName: "Jahanzaib", kind: "voice", durMs: 14000, data: "AAAAHGZ0eXBNNEEgAAAAAE00QSBpc29tbXA0MgAAAAhmcmVl" },
    { id: "dm3", at: Date.now() - 1800000, fromName: "Imran", kind: "text", text: "Plumber coming at 2" },
  ] },
  make() {
    const DAY = 86400000, now = Date.now();
    const sod = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
    const at = (days, h) => sod(now) + days * DAY + (h || 16) * 3600000;
    const names = ["Adeel", "Bilal", "Faisal", "Hamza", "Imran", "Kamran", "Shahid", "Usman", "Zubair"];
    const SEATS = { // name: [designation, head, dept]
      Adeel: ["Surveyor", "Kamran", "Site A"], Bilal: ["Admin", "Shahid", "Office"], Faisal: ["Asst Admin", "Bilal", "Office"], Hamza: ["Surveyor", "Kamran", "Site A"],
      Imran: ["Asst Manager", "Kamran", "Site A"], Kamran: ["Site Manager", "Shahid", "Site A"], Shahid: ["GM", "owner", "Head office"], Usman: ["Store Keeper", "Shahid", "Store"], Zubair: ["Supervisor", "Kamran", "Site A"] };
    const uidOfName = (n) => (n === "owner" ? "owner" : "m" + names.indexOf(n));
    const chainOf = (n) => { const o = []; let h = n === "owner" ? null : SEATS[n][1]; while (h) { o.push(uidOfName(h)); h = h === "owner" ? null : SEATS[h][1]; } return o; };
    const gm = (n) => n.toLowerCase() + "@gmail.com";
    const members = [{ uid: "owner", name: "Jahanzaib", status: "active", role: "owner", managers: [] }]
      .concat(names.map((n, i) => ({ uid: "m" + i, name: n, status: "active", role: "member", managers: chainOf(n), phone: "" })))
      .concat([{ uid: "p1", name: "Tariq", status: "pending", role: "member", managers: [], email: "tariq.site@gmail.com" }]);
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
        if (k % 3 === 0) tasks.push({ id: "t-" + uid + "-" + k, title: t.title, fromUid: "owner", fromName: "Jahanzaib", toUid: uid, toName: n, viewers: ["owner", uid], createdAt: t.createdAt, due: t.dueAt, firstDue: promise, extCount: moves, status: done ? "checked" : "dated", doneAt, lateReasons: rs, events: t.events });
        else own[uid].push(t);
      }
      if (i % 3 === 1) own[uid].push({ id: uid + "-nd", title: "Arrange site visit", createdAt: now - 2 * DAY, dueAt: 0, firstDueAt: 0, moves: 0, done: false, doneAt: 0, reasons: [], events: [] });
    });
    // Team Performance (phone 0.59+ day doc): follow-ups, dossier days and Google events for each person.
    const days = {}, DOS = [["Site photos to group", 600, 615], ["Attendance sheet", 660, 670], ["Material stock count", 900, 930], ["Daily report to head", 1080, 1095]];
    const iso = (t) => { const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
    const callers = ["Mr Asif", "Nadeem", "Plot 14 buyer", "Rana sahib", "Tile vendor", "Mrs Saima"];
    names.forEach((n, i) => {
      const uid = "m" + i, skill = [0.95, 0.9, 0.8, 0.85, 0.45, 0.65, 0.8, 0.93, 0.7][i], fuAll = [], dosDays = [];
      for (let k = 0; k < 14; k++) {
        const due = at(Math.floor(rnd() * 10) - 6, 11 + Math.floor(rnd() * 8) + (rnd() < 0.5 ? 0.5 : 0)), done = due < now - 3600000 && rnd() < skill + 0.05;
        const moved = rnd() > skill ? [{ at: due - DAY, from: due - DAY, to: due, r: reasons[Math.floor(rnd() * 3)] }] : [];
        fuAll.push({ id: uid + "f" + k, title: "Call back", name: callers[Math.floor(rnd() * callers.length)], created: due - 2 * DAY, due, first: moved.length ? due - DAY : due, done, doneAt: done ? due - 300000 : 0, star: false, moves: moved, events: [{ at: due - 2 * DAY, text: "Follow-up added" }] });
      }
      if (i === 4) for (let k = 0; k < 3; k++) fuAll.push({ id: uid + "fx" + k, title: "Call back", name: callers[k], created: at(0, 9), due: at(0, 15) + k * 900000, first: at(0, 15) + k * 900000, done: false, doneAt: 0, moves: [], events: [] });
      for (let d = 20; d >= 0; d--) {
        const t = at(-d, 0), wd = new Date(t).getDay(); if (wd === 5) continue;
        dosDays.push({ d: iso(t), i: DOS.map(([title, m, e]) => { const due = sod(t) + m * 60000; const s = d === 0 && due > now ? "O" : rnd() < skill ? "D" : "M"; return { t: title, m, e, s, at: s === "D" ? due + 300000 : 0, r: s === "M" ? (rnd() < 0.5 ? "Was on site" : "") : "", p: s === "D" && title.startsWith("Site") }; }) });
      }
      days[uid] = { fuAll, dosDays: i === 8 ? [] : dosDays, googleWeek: [{ title: "Client meeting", start: at(0, 14), end: at(0, 15) }, { title: "Bank visit", start: at(2, 12), end: at(2, 13) }] };
    });
    tasks.push({ id: "t-urg", title: "Fix water leak at Block B", fromUid: "owner", fromName: "Jahanzaib", toUid: "m4", toName: "Imran", viewers: ["owner", "m4"], createdAt: at(-2), due: at(0, 14.5), firstDue: at(0, 14.5), urgent: true, status: "dated", doneAt: 0, lateReasons: [], events: [{ at: at(-2), text: "Given by Jahanzaib" }] });
    tasks.push({ id: "t-plot", title: "Possession letter signed", plot: "p14", plotNo: "14", step: "1.8", fromUid: "owner", fromName: "Jahanzaib", toUid: "m4", toName: "Imran", viewers: ["owner", "m4"], createdAt: at(-5), due: at(-2, 12), firstDue: at(-2, 12), status: "dated", doneAt: 0, lateReasons: [], events: [{ at: at(-5), text: "Given by Jahanzaib" }] });
    const m1 = "meet1";
    const a1 = { id: "a1", title: "Weekly material order list every Monday", fromUid: "owner", fromName: "Jahanzaib", toUid: "m2", toName: "Faisal", meeting: true, meetingFrom: "From reason: Material late", due: at(-3), firstDue: at(-3), status: "checked", doneAt: at(-4), createdAt: at(-6), events: [] };
    const a2 = { id: "a2", title: "1 PM check-in calls with site staff", fromUid: "owner", fromName: "Jahanzaib", toUid: "m5", toName: "Kamran", meeting: true, meetingFrom: "From person: Kamran", due: at(-2), firstDue: at(-2), status: "dated", doneAt: 0, createdAt: at(-6), events: [] };
    const a3 = { id: "a3", title: "Get 3 quotes for the generator", fromUid: "owner", fromName: "Jahanzaib", toUid: "m3", toName: "Hamza", meeting: true, meetingFrom: "Not from the report", due: at(3), firstDue: at(3), status: "dated", doneAt: 0, createdAt: at(-6), events: [] };
    tasks.push(a1, a2, a3);
    const meetings = [{ id: m1, type: "weekly", startedAt: at(-6, 11), closedAt: at(-6, 11.7), plannedMin: 40, attendance: { m0: "present", m1: "present", m2: "present", m3: "late", m4: "present", m5: "absent" }, decisions: [{ at: at(-6, 11), text: "Site visits start at 10 AM from Monday" }], notes: [{ at: at(-6, 11), text: "Cement rate may rise; order early" }], actions: ["a1", "a2", "a3"], agreed: {}, sharedAt: at(-6, 12) }];
    const seats = [{ id: "jahanzaib@gmail.com", gmail: "jahanzaib@gmail.com", name: "Jahanzaib", designation: "Owner", role: "owner", status: "filled", uid: "owner", reportsTo: "", dept: "Head office", extraGivers: [], override: {}, holders: [{ uid: "owner", name: "Jahanzaib", from: at(-300) }] }]
      .concat(names.map((n) => ({ id: gm(n), gmail: gm(n), name: n, designation: SEATS[n][0], role: "member", status: "filled", uid: uidOfName(n), reportsTo: SEATS[n][1] === "owner" ? "jahanzaib@gmail.com" : gm(SEATS[n][1]), dept: SEATS[n][2], extraGivers: [], override: {}, holders: [{ uid: uidOfName(n), name: n, from: at(-120) }] })));
    seats.find((x) => x.id === gm("Kamran")).override = { app: { setDossier: true }, portal: { app: "view" } };
    seats.find((x) => x.id === gm("Bilal")).extraGivers = [gm("Usman")];
    seats.push({ id: "surveyor3@gmail.com", gmail: "surveyor3@gmail.com", name: "", designation: "Surveyor", role: "member", status: "empty", uid: "", reportsTo: gm("Kamran"), dept: "Site A", extraGivers: [], override: {}, holders: [{ uid: "old1", name: "Rashid", from: at(-200), to: at(-20) }] });
    const desig = { list: [
      { name: "GM", app: { approveJoins: true, setDossier: true, editReasons: true, seeNumbers: true }, portal: { app: "view", meetings: "view", team: "view", docs: "view" } },
      { name: "Site Manager", app: { setDossier: true }, portal: { app: "view" } }, { name: "Admin", app: { seeNumbers: true }, portal: {} }, { name: "Surveyor", app: {}, portal: {} },
      { name: "Asst Admin", app: {}, portal: {} }, { name: "Asst Manager", app: {}, portal: {} }, { name: "Store Keeper", app: {}, portal: {} }, { name: "Supervisor", app: {}, portal: {} } ],
      timetables: [{ id: "office", name: "Office", start: 660, end: 1260, off: [5] }, { id: "site", name: "Site", start: 540, end: 1080, off: [5] }] };
    seats.forEach((s, i) => { if (i < 4) s.timetable = i % 2 ? "site" : "office"; });
    const docs = [
      { id: "jd-sur", type: "jd", title: "Job description · Surveyor", url: "https://drive.google.com/file/d/demo-jd-surveyor/view", designations: ["Surveyor"], version: 2 },
      { id: "jd-sm", type: "jd", title: "Job description · Site Manager", url: "https://drive.google.com/file/d/demo-jd-sm/view", designations: ["Site Manager"], version: 1 },
      { id: "sop-site", type: "sop", title: "Site daily report", section: "Site", owner: "Shahid", version: 3, reviewBy: at(20), url: "https://drive.google.com/file/d/demo-sop1/view", designations: ["Surveyor", "Site Manager"], parts: { Surveyor: "You fill the photo form every evening", "Site Manager": "You check all forms before 6 PM" } },
      { id: "sop-pur", type: "sop", title: "Purchase approval", section: "Purchase", owner: "Bilal", version: 1, reviewBy: at(-3), url: "https://drive.google.com/file/d/demo-sop2/view", designations: ["Admin", "Site Manager"], parts: { Admin: "You approve under 50,000", "Site Manager": "You raise the request" } },
      { id: "sop-saf", type: "sop", title: "Safety on site", section: "Safety", owner: "Kamran", version: 1, reviewBy: at(60), url: "https://drive.google.com/file/d/demo-sop3/view", designations: ["Surveyor", "Supervisor", "Site Manager"], parts: { Surveyor: "Helmet and vest always" } },
      { id: "wd-1", type: "work", title: "Total station quick guide", url: "https://drive.google.com/file/d/demo-w1/view", designations: ["Surveyor"] },
      { id: "wd-2", type: "work", title: "Material rate sheet", url: "https://drive.google.com/file/d/demo-w2/view", designations: ["Admin", "Site Manager"] },
      { id: "pol-leave", type: "policy", title: "Leave policy", url: "https://drive.google.com/file/d/demo-p1/view", shareWith: ["GM"], summary: "30 days notice for long leave. Short leave: tell your head the day before.",
        how: ["Tell your head", "Send the leave email", "Hand over your open tasks"], forms: [{ name: "Leave form", url: "https://drive.google.com/file/d/demo-form/view" }],
        templates: [{ id: "tpl1", name: "Leave request", to: "hr@gmail.com", cc: "head@gmail.com", subject: "Leave request · [name] · [designation] · [date]", body: "Dear HR,\n\nI request leave from [from] to [to] because [reason].\n\nRegards,\n[name]" }] },
    ];
    const reading = { m0: { done: { "jd-sur": { at: at(-3), version: 2 }, "sop-site": { at: at(-1), version: 3 } } }, m1: {} };
    const changelog = [{ id: "c1", at: at(-1, 15), by: "owner", byName: "Jahanzaib", text: "Moved Imran under Kamran" }, { id: "c2", at: at(-2, 10), by: "owner", byName: "Jahanzaib", text: "Site Manager default: Set Dossier ON" }];
    // Team › Dossier (owner sets it on the web): Imran with a few tasks, two not seen on his phone yet, and his old dossier.
    const blk = (id, title, a, b, extra) => Object.assign({ id, title, note: "", minute: a, endMinute: b, days: 127, monthDay: 0, everyWeeks: 1, onOffDay: true, remindBefore: 15, proof: false, afterReview: true, by: "owner", byName: "Jahanzaib", changedAt: at(-1, 9) }, extra || {});
    const dossier = { m4: { blocks: [blk("b1", "Site photos to WhatsApp group", 660, 690, { note: "All 4 sites, before and after", proof: true }), blk("b2", "Check material register", 720, 750, { days: 63, remindBefore: 0 }), blk("b3", "Visit Block C site", 960, 990, { days: 34, changedAt: now - 600000 }), blk("b4", "Monthly stock count", 1080, 1140, { monthDay: 1, changedAt: now - 300000 })],
      seen: { b1: at(0, 9), b2: at(0, 9) }, old: [blk("o1", "Morning site round", 600, 660, { byName: "Kamran", changedAt: 0 })], oldOwn: [blk("o2", "Call supplier", 840, 870, { own: true, byName: "", changedAt: 0 })] },
      m1: { blocks: [blk("b5", "Office opening check", 660, 680)], seen: { b5: at(-1, 10) } } };
    return { dossier, plot: [], team: { id: "demo", name: "Rafiq Builders (sample)", ownerUid: "owner" }, members, seats, desig, docs, reading, changelog, tasks, own, days, reasons: null, cfg: null, meetings };
  },
};
