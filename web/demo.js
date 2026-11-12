/* Sample data for a preview (open the page with ?demo). Nothing is read from or saved to the team. */
window.DEMO_DATA = {
  msgs: {},
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
      { name: "Asst Admin", app: {}, portal: {} }, { name: "Asst Manager", app: {}, portal: {} }, { name: "Store Keeper", app: {}, portal: {} }, { name: "Supervisor", app: {}, portal: {} } ] };
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
    // Plot files (sample): four files at different places, so every list chip has something in it.
    const wd = (n) => at(-n, 10);
    const dsteps = (cur, doneIds, extra) => { const o = {}; doneIds.forEach((id, i) => (o[id] = { status: "done", startedAt: wd(Math.max(2, 12 - i)), doneAt: wd(Math.max(1, 11 - i)), days: null })); o[cur.id] = Object.assign({ status: "doing", startedAt: cur.at, days: cur.days, role: cur.role }, extra || {}); return o; };
    const items = (st) => ["Site Plan Requisition form", "CNIC copy of plot owner", "Possession form", "Registry photocopy", "100 stamp paper · Undertaking", "1200 stamp paper with discount cheque", "Director waiver / discount approval"].map((n, i) => ({ id: "i" + i, group: "Documents checklist (RG-03)", name: n, status: st[i] || "none", at: st[i] ? wd(5) : 0 }));
    const plot = [
      { id: "pf1", plot: "245", owner: "Mr Tahir Mehmood", phone1: "0300 1234567", phone2: "0321 7654321", cnic: "35202-1234567-1", size: "10 marla", address: "House 12, Street 4, Lahore", driveInternal: "https://drive.google.com/drive/folders/demo-int", driveArchitect: "https://drive.google.com/drive/folders/demo-arch", openedAt: wd(8), stage: 1, closed: false, waiting: null,
        steps: dsteps({ id: "1.16", at: wd(6), days: 2, role: "Draftsman" }, ["1.01", "1.02", "1.03", "1.04", "1.05", "1.05c", "1.06", "1.07", "1.08", "1.09", "1.10", "1.10d", "1.11", "1.12", "1.13", "1.14", "1.15"]), items: items(["verified", "verified", "verified", "received", "none", "none", "waived"]),
        events: [{ at: wd(8), by: "m1", text: "Plot file opened" }, { at: wd(6), by: "owner", text: "1.15 · Plot Drive folder made done" }] },
      { id: "pf2", plot: "118", owner: "Mrs Saima Akhtar", phone1: "0333 5551122", phone2: "", cnic: "35202-7654321-2", size: "5 marla", address: "Block C, Lahore", openedAt: wd(20), stage: 4, closed: false, waiting: { stepId: "4.02", since: wd(2) },
        steps: dsteps({ id: "4.02", at: wd(3), days: null, role: "Draftsman" }, ["4.01"], { status: "waiting", waitingSince: wd(2), waitedMs: 0 }), items: items(["verified", "verified", "verified", "verified", "received", "none", "none"]), events: [{ at: wd(2), by: "m3", text: "Waiting for plot owner · 4.02" }] },
      { id: "pf3", plot: "302", owner: "Mr Naveed Iqbal", phone1: "0345 9988776", phone2: "", cnic: "35201-1112223-3", size: "1 kanal", address: "Phase 2, Lahore", openedAt: wd(15), stage: 2, closed: false, waiting: null,
        steps: dsteps({ id: "2.05", at: wd(7), days: 1, role: "Document Officer" }, ["2.01", "2.02", "2.02c", "2.03", "2.04"]), items: items(["verified", "received", "received", "none", "none", "none", "none"]), events: [] },
      { id: "pf4", plot: "77", owner: "Mr Rashid Ali", phone1: "0301 4443322", phone2: "", cnic: "35202-9998887-1", size: "10 marla", address: "Block A, Lahore", openedAt: wd(90), stage: 8, closed: true, waiting: null, steps: { "8.08": { status: "done", startedAt: wd(30), doneAt: wd(28) } }, items: items(["verified", "verified", "verified", "verified", "verified", "verified", "waived"]), events: [{ at: wd(28), by: "owner", text: "Plot file closed" }] },
    ];
    const plotMoney = { pf1: { cheques: [{ id: "c1", milestone: "Construction started", deadline: at(40), bank: "HBL", no: "00123456", amount: 100000, status: "held" }], pccDue: 0 }, pf2: { cheques: [], pccDue: 45000 } };
    return { plot, plotMoney, plotCfg: { enabled: true, steps: {}, holidays: [] }, team: { id: "demo", name: "Rafiq Builders (sample)", ownerUid: "owner" }, members, seats, desig, docs, reading, changelog, tasks, own, reasons: null, cfg: null, meetings };
  },
};
