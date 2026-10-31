/* Rafiq Builders portal · core: shared data, sign-in, permissions, seats and the one place that writes to Firebase.
 * Every page reads window.RB. ?demo shows sample data (nothing is read or saved); ?demo&as=m3 previews a staff view.
 */
(function () {
  "use strict";
  const RB = (window.RB = { D: { me: null, team: null, members: [], seats: [], docs: [], reading: {}, changelog: [], tasks: [], own: {}, reasons: null, cfg: null, meetings: [], desig: null, dossier: {}, days: {} }, S: {}, DEMO: /[?&]demo\b/.test(location.search) });
  const D = RB.D;
  const DAY = 86400000;
  RB.DAY = DAY;
  let db = null, teamRef = null, uid = null;

  // ------------------------------------------------------------------ small helpers
  RB.esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  RB.first = (n) => String(n || "").trim().split(/\s+/)[0] || "Member";
  RB.fmtDay = (t) => new Date(t).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
  RB.fmtShort = (t) => new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  RB.fmtWhen = (t) => RB.fmtDay(t) + ", " + new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  RB.gkey = (g) => String(g || "").trim().toLowerCase();
  RB.uidOf = () => uid;
  RB.id = () => "x" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

  // ------------------------------------------------------------------ switches and access
  RB.APP_SW = [
    ["approveJoins", "Approve people waiting to join", "Join requests", false],
    ["setDossier", "Set Dossier for own people", "Fixed daily time blocks", false],
    ["editReasons", "Edit late-reason list", "Reasons shown on late tasks", false],
    ["approveExt", "Approve extension requests", "From own people", true],
    ["seeNumbers", "See team numbers on phone", "Only people he gave tasks to", false],
    ["seeDay", "See own people's day", "People below him in the chart", true],
    ["giveDown", "Give tasks down his branch", "", true],
    ["giveSide", "Give tasks sideways", "Colleagues with the same head", true],
    ["callRec", "Call recording", "Stays on his phone", false],
  ];
  RB.SECTIONS = [["app", "App view"], ["meetings", "Meetings"], ["team", "Team"], ["docs", "Documents"], ["settings", "Settings"]];
  const DEF_APP = {}; RB.APP_SW.forEach((s) => (DEF_APP[s[0]] = s[3]));
  const DEF_PORTAL = { app: "none", meetings: "none", team: "none", docs: "view", settings: "none" };
  const COLORS = ["lil", "blu", "yel", "peach"];
  RB.colorOf = (i) => "var(--" + COLORS[i % 4] + ")";

  const SEED_DESIG = [
    { name: "GM", app: { approveJoins: true, setDossier: true, editReasons: true, seeNumbers: true }, portal: { app: "view", meetings: "view", team: "view", docs: "view", settings: "none" } },
    { name: "Site Manager", app: { setDossier: true }, portal: { app: "view" } },
    { name: "Admin", app: { seeNumbers: true }, portal: {} },
    { name: "Surveyor", app: {}, portal: {} },
    { name: "Store Keeper", app: {}, portal: {} },
  ];
  RB.desigList = () => (D.desig && D.desig.list && D.desig.list.length ? D.desig.list : SEED_DESIG);
  RB.desigOf = (name) => RB.desigList().find((d) => d.name === name);
  RB.desigColor = (name) => { const i = RB.desigList().findIndex((d) => d.name === name); return RB.colorOf(i < 0 ? 0 : i); };

  /** What a seat can do: designation defaults, then the owner's changes for that person. Owners can do everything. */
  RB.effective = (seat) => {
    if (!seat) return { app: DEF_APP, portal: DEF_PORTAL, changed: {}, owner: false };
    const owner = seat.role === "owner";
    const ds = RB.desigOf(seat.designation) || { app: {}, portal: {} };
    const ov = seat.override || {};
    const app = Object.assign({}, DEF_APP, ds.app || {}, ov.app || {});
    const portal = Object.assign({}, DEF_PORTAL, ds.portal || {}, ov.portal || {});
    if (owner) { Object.keys(app).forEach((k) => (app[k] = k === "callRec" ? false : true)); Object.keys(portal).forEach((k) => (portal[k] = "edit")); }
    const changed = {};
    Object.keys(ov.app || {}).forEach((k) => { if ((ov.app[k]) !== (Object.assign({}, DEF_APP, ds.app || {})[k])) changed["app." + k] = 1; });
    Object.keys(ov.portal || {}).forEach((k) => { if (ov.portal[k] !== Object.assign({}, DEF_PORTAL, ds.portal || {})[k]) changed["portal." + k] = 1; });
    return { app, portal, changed, owner };
  };
  RB.defaultsOf = (name) => { const ds = RB.desigOf(name) || { app: {}, portal: {} }; return { app: Object.assign({}, DEF_APP, ds.app || {}), portal: Object.assign({}, DEF_PORTAL, ds.portal || {}) }; };
  RB.countOn = (name) => Object.values(RB.defaultsOf(name).app).filter(Boolean).length;

  const seatOfUid = (u) => D.seats.find((s) => s.uid === u && s.status === "filled");
  RB.seatOfUid = seatOfUid;
  RB.isOwner = () => !!(D.team && D.team.ownerUid === uid) || (D.me && D.me.role === "owner");
  /** none | view | edit for one portal section, for the person using the page. */
  RB.level = (sec) => {
    if (RB.isOwner()) return "edit";
    const s = seatOfUid(uid);
    if (s) return RB.effective(s).portal[sec] || "none";
    return (D.me && D.me.perms && D.me.perms.portal && D.me.perms.portal[sec]) || "none";
  };
  RB.canEdit = (sec) => RB.level(sec) === "edit";
  RB.anyAccess = () => RB.SECTIONS.some(([k]) => RB.level(k) !== "none");

  // ------------------------------------------------------------------ the chart
  RB.seat = (id) => D.seats.find((s) => s.id === id);
  RB.holderName = (s) => (s && s.status === "filled" ? s.name || RB.first(s.gmail) : "Empty seat");
  RB.headOf = (s) => (s && s.reportsTo ? RB.seat(s.reportsTo) : null);
  RB.children = (id) => D.seats.filter((s) => s.reportsTo === id && s.role !== "owner");
  RB.descendants = (id) => { const out = []; const walk = (x) => RB.children(x).forEach((c) => { out.push(c); walk(c.id); }); walk(id); return out; };
  RB.chainOf = (s) => { const out = []; let h = RB.headOf(s), n = 0; while (h && n++ < 20) { out.push(h); h = RB.headOf(h); } return out; };
  RB.wouldLoop = (seatId, newHead) => { if (!newHead) return false; if (newHead === seatId) return true; return RB.descendants(seatId).some((d) => d.id === newHead); };
  RB.owners = () => D.seats.filter((s) => s.role === "owner");
  /** Whom a person may give tasks to (their own branch, colleagues with the same head, and the extras the owner added). */
  RB.canGiveTo = (s) => {
    if (!s) return [];
    if (s.role === "owner") return D.seats.filter((x) => x.id !== s.id);
    const e = RB.effective(s).app, set = new Map();
    if (e.giveDown) RB.descendants(s.id).forEach((x) => set.set(x.id, x));
    if (e.giveSide) D.seats.filter((x) => x.id !== s.id && x.role !== "owner" && (x.reportsTo || "") === (s.reportsTo || "")).forEach((x) => set.set(x.id, x));
    D.seats.forEach((x) => { if ((x.extraGivers || []).includes(s.id) && x.id !== s.id) set.set(x.id, x); });
    return Array.from(set.values());
  };
  /** Who can see a person's App view: owners, his heads, and anyone the chart lets give him tasks. */
  RB.visibleSeats = () => {
    if (RB.isOwner()) return D.seats.filter((s) => s.status === "filled");
    const me = seatOfUid(uid); if (!me) return [];
    const out = [me].concat(RB.descendants(me.id));
    return out.filter((s) => s.status === "filled");
  };

  // ------------------------------------------------------------------ store (Firebase or demo)
  const COLL = { members: "members", seats: "seats", docs: "docs", changelog: "changelog", tasks: "tasks", meetings: "meetings" };
  RB.Store = {
    async put(coll, id, data, merge) {
      if (coll === "docs" && !RB.DEMO) { const ex = D.docs.find((x) => x.id === id); coll = (data.type || (ex && ex.type)) === "policy" ? "policies" : "docs"; }
      if (RB.DEMO) {
        if (coll === "config") { if (id === "reasons") D.reasons = data; else if (id === "meeting") D.cfg = merge ? Object.assign({}, D.cfg || {}, data) : data; else if (id === "designations") D.desig = merge ? Object.assign({}, D.desig || {}, data) : data; return id; }
        if (coll === "reading") { D.reading[id] = merge ? Object.assign({}, D.reading[id] || {}, data) : data; return id; }
        if (coll === "dossier") { D.dossier[id] = data; return id; }
        const arr = D[COLL[coll]]; const i = arr.findIndex((x) => x.id === id || x.uid === id && coll === "members");
        const key = coll === "members" ? "uid" : "id";
        if (i >= 0) arr[i] = merge ? Object.assign({}, arr[i], data) : Object.assign({ [key]: id }, data);
        else arr.unshift(Object.assign({ [key]: id }, data));
        return id;
      }
      const ref = teamRef.collection(coll).doc(id);
      if (merge) await ref.set(data, { merge: true }); else await ref.set(data);
      if (coll === "policies") await reindex(id, Object.assign({}, D.docs.find((x) => x.id === id) || {}, data).shareWith || []);
      return id;
    },
    async add(coll, data) { const id = RB.id(); await RB.Store.put(coll, id, data); return id; },
    async del(coll, id) {
      if (coll === "docs" && !RB.DEMO) { const ex = D.docs.find((x) => x.id === id); if (ex && ex.type === "policy") coll = "policies"; }
      if (RB.DEMO) { const arr = D[COLL[coll]]; const key = coll === "members" ? "uid" : "id"; const i = arr.findIndex((x) => x[key] === id); if (i >= 0) arr.splice(i, 1); return; }
      await teamRef.collection(coll).doc(id).delete();
      if (coll === "policies") await reindex(id, null);
    },
    /** A given task, in the same shape the phone app writes. */
    async task(fromUid, toUid, title, due, extra) {
      const to = RB.member(toUid), me = RB.member(fromUid) || { name: "Owner", managers: [] };
      const now = Date.now();
      const viewers = Array.from(new Set([fromUid, toUid].concat(to.managers || [], me.managers || [])));
      const doc = Object.assign({
        title: title.trim(), fromUid, fromName: me.name, toUid, toName: to.name, toPhone: to.phone || "", viewers,
        createdAt: now, updatedAt: now, deadline: due, urgent: false, dateBy: "giver", due, firstDue: due, status: "dated",
        seenAt: 0, doneAt: 0, checkedAt: 0, moreDue: 0, moreReason: "", moreAt: 0, reason: "", passedTo: "", askAt: 0, stuckAt: 0, stuckText: "",
        lastMsgAt: 0, lastMsgBy: "", lastMsgText: "", photoProof: false, proofAt: 0, deliveredAt: 0, locked: true, freeExt: 0, extCount: 0,
        reminderAt: 0, nPhoto: 0, nVoice: 0, nBy: {}, meeting: false, meetingFrom: "",
        events: [{ at: now, text: "Given by " + RB.first(me.name) + " · 📅 Deadline " + RB.fmtWhen(due) }],
      }, extra || {});
      if (RB.DEMO) { const id = "d" + now + Math.random().toString(36).slice(2, 5); D.tasks.push(Object.assign({ id }, doc)); return id; }
      const ref = await teamRef.collection("tasks").add(doc);
      return ref.id;
    },
    async addAction(toUid, title, due, fromReason) {
      const me = RB.member(uid) || { name: "Owner" };
      return RB.Store.task(uid, toUid, title, due, { meeting: true, meetingFrom: fromReason || "", events: [{ at: Date.now(), text: "Given by " + RB.first(me.name) + " · 🤝 Meeting · 📅 Deadline " + RB.fmtWhen(due) }] });
    },
    async saveMeeting(m) {
      if (RB.DEMO) { const i = D.meetings.findIndex((x) => x.id === m.id); if (i >= 0) D.meetings[i] = m; else { m.id = m.id || "mt" + Date.now(); D.meetings.unshift(m); } return m.id; }
      const data = Object.assign({}, m); delete data.id;
      if (m.id) { await teamRef.collection("meetings").doc(m.id).set(data); return m.id; }
      const ref = await teamRef.collection("meetings").add(data); m.id = ref.id; return ref.id;
    },
    async saveConfig(doc, data) { return RB.Store.put("config", doc, data); },
    async msgs(taskId) {
      if (RB.DEMO) return (window.DEMO_DATA && window.DEMO_DATA.msgs[taskId]) || [];
      const s = await teamRef.collection("tasks").doc(taskId).collection("msgs").orderBy("at").get();
      return s.docs.map((d) => Object.assign({ id: d.id }, d.data()));
    },
  };
  /** Change log: owners read it; nobody can edit or delete a line. */
  RB.log = (text) => RB.Store.add("changelog", { at: Date.now(), by: uid || "", byName: (D.me && D.me.name) || "Owner", text });

  RB.member = (u) => D.members.find((m) => m.uid === u);

  /** Staff cannot list policies (owners only): config/policyIndex tells each designation which policy ids it may read. */
  async function reindex(id, shareWith) {
    const by = {};
    D.docs.filter((p) => p.type === "policy" && p.id !== id).forEach((p) => (p.shareWith || []).forEach((x) => (by[x] = (by[x] || []).concat(p.id))));
    (shareWith || []).forEach((x) => (by[x] = (by[x] || []).concat(id)));
    await teamRef.collection("config").doc("policyIndex").set({ byDesig: by, updatedAt: Date.now() });
  }

  /** After any seat, chart or access change, rewrite each holder's member row so the phones follow (managers, give-to list, switches). */
  RB.sync = async function () {
    const writes = [];
    D.seats.forEach((s) => {
      if (s.status !== "filled" || !s.uid) return;
      const e = RB.effective(s), head = RB.headOf(s);
      const chain = RB.chainOf(s).filter((h) => h.status === "filled" && h.uid).map((h) => h.uid);
      const data = {
        seatId: s.id, designation: s.designation || "", dept: s.dept || "", reportsTo: head && head.uid ? head.uid : "", managers: chain,
        perms: { app: e.app, portal: e.portal }, setDossier: !!e.app.setDossier, canGiveTo: RB.canGiveTo(s).filter((x) => x.uid && x.status === "filled").map((x) => x.uid),
        role: s.role === "owner" ? "owner" : "member",
        docDesigs: Array.from(new Set([s.designation].concat(RB.descendants(s.id).map((x) => x.designation)).filter(Boolean))),
      };
      const cur = RB.member(s.uid);
      if (cur && JSON.stringify([cur.seatId, cur.designation, cur.dept, cur.reportsTo, cur.managers, cur.perms, cur.canGiveTo, cur.role, cur.docDesigs]) === JSON.stringify([data.seatId, data.designation, data.dept, data.reportsTo, data.managers, data.perms, data.canGiveTo, data.role, data.docDesigs])) return;
      writes.push(RB.Store.put("members", s.uid, data, true));
    });
    await Promise.all(writes);
  };

  // ------------------------------------------------------------------ start
  const $app = () => document.getElementById("app");
  RB.message = (a, b, out) => {
    $app().innerHTML = `<div class="center"><h2>${a}</h2><div class="muted">${b}</div>${out ? '<button class="btnt" id="so">Sign out</button>' : ""}</div>`;
    const so = document.getElementById("so"); if (so) so.onclick = () => firebase.auth().signOut().then(() => location.reload());
  };
  function signInPage() {
    $app().innerHTML = `<div class="center login"><div class="logo">RB</div><h2>Rafiq Builders</h2><div class="muted">Sign in with your official Gmail.</div>
      <button class="btnp" id="gs">Sign in with Google</button></div>`;
    document.getElementById("gs").onclick = () => firebase.auth().signInWithPopup(new firebase.auth.GoogleAuthProvider()).catch((e) => RB.alertBox(RB.esc(e.message)));
  }
  RB.alertBox = (t) => { const m = document.getElementById("modal"); m.innerHTML = `<div class="back"><div class="dlg"><div>${t}</div><div style="text-align:right;margin-top:12px"><button class="btnp" id="ok">OK</button></div></div></div>`; document.getElementById("ok").onclick = () => (m.innerHTML = ""); };

  RB.start = function () {
    if (RB.DEMO) {
      const asM = (location.search.match(/[?&]as=([\w-]+)/) || [])[1];
      Object.assign(D, window.DEMO_DATA.make());
      uid = asM || D.team.ownerUid; D.me = RB.member(uid);
      RB.ready(); return;
    }
    firebase.initializeApp(window.FIREBASE_CONFIG);
    db = firebase.firestore();
    firebase.auth().onAuthStateChanged(async (u) => {
      if (!u) return signInPage();
      uid = u.uid;
      try {
        const us = await db.collection("users").doc(uid).get();
        const t = us.exists && us.data().teamId;
        if (!t) return RB.message("This Google account isn't in Rafiq Builders yet.", "Ask an owner to add your Gmail in the portal, then join from the phone app.<br>Signed in as " + RB.esc(u.email) + ".", true);
        teamRef = db.collection("teams").doc(t);
        const ts = await teamRef.get();
        D.team = Object.assign({ id: t }, ts.data());
        const ms = await teamRef.collection("members").doc(uid).get();
        D.me = ms.exists ? Object.assign({ uid }, ms.data()) : null;
        if (!RB.isOwner() && !(D.me && D.me.status === "active" && D.me.perms && D.me.perms.portal && Object.values(D.me.perms.portal).some((v) => v !== "none"))) {
          return RB.message("You don't have access to the portal.", "Ask an owner. The phone app still works. Signed in as " + RB.esc(u.email) + ".", true);
        }
        listen(); RB.ready();
      } catch (e) { RB.message("Couldn't open the portal.", RB.esc(e.message || e) + "<br>If this is the first time, the new Firestore rules may not be pasted yet.", true); }
    });
  };

  let softT = null;
  RB.soft = () => { if (document.getElementById("modal").innerHTML) return; clearTimeout(softT); softT = setTimeout(() => RB.render && RB.render(), 250); };
  function listen() {
    const onErr = () => {};
    const snap = (name, fn) => teamRef.collection(name).onSnapshot((s) => { fn(s); RB.soft(); }, onErr);
    teamRef.collection("members").onSnapshot((s) => {
      D.members = s.docs.map((d) => Object.assign({ uid: d.id }, d.data()));
      D.me = RB.member(uid) || D.me;
      D.members.forEach((m) => {
        if (D.own[m.uid] !== undefined || m.status !== "active") return;
        D.own[m.uid] = [];
        teamRef.collection("own").doc(m.uid).collection("tasks").onSnapshot((q) => { D.own[m.uid] = q.docs.map((d) => Object.assign({ id: d.id }, d.data())); RB.soft(); }, onErr);
        if (!(RB.isOwner() || RB.canEdit("docs")) && (m.uid === uid || (m.managers || []).includes(uid))) teamRef.collection("reading").doc(m.uid).onSnapshot((q) => { if (q.exists) D.reading[m.uid] = q.data(); RB.soft(); }, onErr);
        teamRef.collection("days").doc(m.uid).onSnapshot((q) => { if (q.exists) D.days[m.uid] = q.data(); RB.soft(); }, onErr);
        teamRef.collection("dossier").doc(m.uid).onSnapshot((q) => { if (q.exists) D.dossier[m.uid] = q.data(); RB.soft(); }, onErr);
      });
      RB.soft();
    }, (e) => RB.message("Couldn't read the team.", RB.esc(e.message), true));
    snap("seats", (s) => (D.seats = s.docs.map((d) => Object.assign({ id: d.id }, d.data()))));
    let plain = [], pols = [];
    const merge = () => (D.docs = plain.concat(pols));
    snap("docs", (s) => { plain = s.docs.map((d) => Object.assign({ id: d.id }, d.data())); merge(); });
    const seeAllPol = RB.isOwner() || RB.canEdit("docs");
    const myDes = () => ((D.me && D.me.designation) || (RB.seatOfUid(uid) || {}).designation || "");
    const polMap = {}, watched = new Set();
    const setPols = () => { pols = Object.values(polMap); merge(); RB.soft(); };
    if (seeAllPol) teamRef.collection("policies").onSnapshot((s) => { s.docs.forEach((d) => (polMap[d.id] = Object.assign({ id: d.id, type: "policy" }, d.data()))); Object.keys(polMap).forEach((k) => { if (!s.docs.some((d) => d.id === k)) delete polMap[k]; }); setPols(); }, onErr);
    RB.watchPolicies = (byDesig) => {
      if (seeAllPol) return;
      ((byDesig || {})[myDes()] || []).forEach((pid) => { if (watched.has(pid)) return; watched.add(pid); teamRef.collection("policies").doc(pid).onSnapshot((q) => { if (q.exists) polMap[pid] = Object.assign({ id: pid, type: "policy" }, q.data()); else delete polMap[pid]; setPols(); }, () => {}); });
    };
    if (seeAllPol) snap("reading", (s) => s.docs.forEach((d) => (D.reading[d.id] = d.data())));
    teamRef.collection("changelog").orderBy("at", "desc").limit(200).onSnapshot((s) => { D.changelog = s.docs.map((d) => Object.assign({ id: d.id }, d.data())); RB.soft(); }, onErr);
    const tq = RB.isOwner() ? teamRef.collection("tasks") : teamRef.collection("tasks").where("viewers", "array-contains", uid);
    tq.onSnapshot((s) => { D.tasks = s.docs.map((d) => Object.assign({ id: d.id }, d.data())); RB.soft(); }, onErr);
    teamRef.collection("config").onSnapshot((s) => { s.docs.forEach((d) => { if (d.id === "reasons") D.reasons = d.data(); if (d.id === "meeting") D.cfg = d.data(); if (d.id === "designations") D.desig = d.data(); if (d.id === "policyIndex") RB.watchPolicies(d.data().byDesig); }); RB.soft(); }, onErr);
    if (RB.level("meetings") !== "none") teamRef.collection("meetings").orderBy("startedAt", "desc").limit(100).onSnapshot((s) => { D.meetings = s.docs.map((d) => Object.assign({ id: d.id }, d.data())); RB.soft(); }, onErr);
  }
})();
