/* Team: People (one list, each Gmail is a seat) · Chart · Designations · Timetables · Access · Offboarding. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc, first = RB.first;
  const { fmtShort, fmtWhen } = RB;
  const DAY = 86400000;
  Object.assign(S, { seatSel: null, accSeat: null, accDesig: null, accMode: "person" });
  const ed = () => RB.canEdit("team");
  const gmailOk = (g) => /^[a-z0-9._%+-]+@gmail\.com$/i.test(String(g).trim());
  const initial = (s) => esc((RB.holderName(s) || "?")[0].toUpperCase());
  /** The round picture of a seat: the letter, dashed while nobody has joined. */
  /** WEB9 the person's Google photo (saved by his own phone or browser at sign-in), else his first letter. */
  const face = (s) => { const m = s.status === "filled" && s.uid && RB.member(s.uid); return m && /^https:\/\//.test(m.photo || "") ? `<img src="${esc(m.photo)}" alt="" referrerpolicy="no-referrer" data-l="${initial(s)}" onerror="this.replaceWith(this.dataset.l)">` : initial(s); };
  const avatar = (s) => `<span class="pav2 ${s.status === "filled" ? "" : "off"}" aria-hidden="true">${s.status === "filled" || s.invited ? face(s) : "＋"}</span>`;
  const tag = (name) => `<span class="ty" style="background:${RB.desigColor(name)}">${esc(name || "No designation")}</span>`;
  const selSeat = () => RB.seat(S.seatSel) || D.seats.find((s) => s.status === "filled") || D.seats[0];
  // The change is already saved; the history line and the phone rows are extras that must never show as an error.
  const done = async (text) => { try { await RB.log(text); } catch (e) { console.warn("log", e); } await RB.sync(); };
  // Access clicks show at once and are saved together half a second after the last click, so quick clicks never undo each other.
  const lazy = {};
  function lazySave(key, write, label) {
    const q = (lazy[key] = lazy[key] || { labels: [], seq: 0 });
    q.labels.push(label); q.write = write; q.seq++; q.busy = true; RB.hold = true; clearTimeout(q.t);
    const my = q.seq;
    q.t = setTimeout(async () => {
      const labels = q.labels.filter((x, i, a) => a.indexOf(x) === i); q.labels = [];
      try { await q.write(); await done(labels.join(" · ")); } catch (e) { RB.alertBox("Couldn't save the access change: " + (e.message || e)); }
      finally { if (q.seq === my) { q.busy = false; RB.hold = Object.values(lazy).some((x) => x.busy); RB.render(); } }
    }, 500);
  }
  const saveOverride = (s, label) => lazySave("seat:" + s.id, () => RB.Store.putField("seats", s.id, "override", RB.seat(s.id).override || {}), label);
  // Timetables (locked 23 Nov) live in config/designations (Team editors already write it): [{ id, name, start, end, off }],
  // start/end in minutes from midnight, off = week days off (0 Sunday … 6 Saturday). Each seat keeps one timetable id.
  RB.timetables = () => (D.desig && Array.isArray(D.desig.timetables) ? D.desig.timetables : []);
  RB.ttOf = (s) => (s && s.timetable ? RB.timetables().find((t) => t.id === s.timetable) : null);
  RB.noTimetable = () => D.seats.filter((s) => !RB.ttOf(s));
  const DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  /** A timetable's name is its hours and days off (no typed name, locked 23 Nov): "8 AM to 5 PM · Fri off". */
  const hs = (min) => { const h = Math.floor(min / 60) % 24, m = min % 60; return (h % 12 || 12) + (m ? ":" + String(m).padStart(2, "0") : "") + (h < 12 ? " AM" : " PM"); };
  RB.ttName = (t) => (t ? hs(t.start) + " to " + hs(t.end) + " · " + (t.off && t.off.length ? [1, 2, 3, 4, 5, 6, 0].filter((i) => t.off.includes(i)).map((i) => DAYS[i].slice(0, 3)).join(", ") + " off" : "No day off") : "");
  const pname = (s) => s.name || s.gmail || "";
  const onTt = (id) => D.seats.filter((s) => s.timetable === id).sort((a, b) => pname(a).localeCompare(pname(b)));
  /** Seat form options: each timetable with who else is on it. */
  const ttOpts = (seatId) => [["", "— pick a timetable —"]].concat(RB.timetables().map((t) => {
    const p = onTt(t.id).filter((s) => s.id !== seatId).map((s) => first(pname(s)));
    return [t.id, RB.ttName(t) + " · " + (p.length ? p.slice(0, 5).join(", ") + (p.length > 5 ? " +" + (p.length - 5) + " more" : "") : "nobody yet")];
  }));
  const saveDesigLazy = (list, label) => { D.desig = Object.assign({}, D.desig || {}, { list }); lazySave("desig", () => RB.Store.put("config", "designations", { list: RB.desigList(), updatedAt: Date.now() }, true), label); };

  // ------------------------------------------------------------------ people
  function people() {
    const q = (S.q || "").toLowerCase();
    // Sort by a column (locked 23 Nov, WEB8): click a column name, click again to flip. [column, 1 = A→Z / -1 = Z→A]
    if (!Array.isArray(S.psort)) S.psort = ["name", 1];
    const [col, dir] = S.psort;
    const nm = (s) => (s.status === "filled" ? RB.holderName(s) : s.name || s.gmail || "").toLowerCase();
    const key = { name: nm, dept: (s) => (s.dept || "~").toLowerCase(), tt: (s) => (RB.ttOf(s) ? RB.ttOf(s).start * 10000 + RB.ttOf(s).end : 9e9), desig: (s) => (s.designation || "~").toLowerCase() }[col] || nm;
    const by = (a, b) => { const x = key(a), y = key(b); return (x < y ? -dir : x > y ? dir : 0) || nm(a).localeCompare(nm(b)); };
    const inScope = RB.scope() === "all" ? null : new Set(RB.visibleSeats().map((x) => x.id));
    const list = D.seats.filter((s) => (!inScope || inScope.has(s.id)) && (!q || (RB.holderName(s) + " " + s.gmail + " " + (s.phone || "") + " " + s.designation + " " + (s.dept || "")).toLowerCase().includes(q))).slice().sort(by);
    const sel = selSeat();
    const th = (k, l) => k ? `<button class="plth ${col === k ? "on" : ""}" data-psort="${k}" aria-label="Sort by ${l}">${l} <span aria-hidden="true">${col === k ? (dir === 1 ? "▲" : "▼") : "⬍"}</span></button>` : `<span class="plth">${l}</span>`;
    const head = `<div class="prow phead" aria-hidden="false"><span></span>${th("name", "Name")}${th("", "Contact")}${th("dept", "Department")}${th("tt", "Timetable")}${th("desig", "Designation")}<span></span></div>`;
    const headName = (s) => { const h = RB.headOf(s); return h ? (h.status === "filled" ? h.name : h.name || first(h.gmail)) : ""; };
    const row = (s) => {
      const joined = s.status === "filled", tt = RB.ttOf(s);
      const status = joined ? '<span class="pst on">● Joined</span>' : s.invited ? '<span class="pst">Invited, not joined</span>' : '<span class="pst">Nobody invited yet</span>';
      const miss = [s.phone ? "" : '<span class="tag amb">No phone number</span>', tt ? "" : '<span class="tag amb">No timetable</span>'].join("");
      const btn = ed() ? `<button class="btnt edit-only" data-editseat="${esc(s.id)}">${joined || s.invited ? "Edit" : "Fill seat"}</button>` : "";
      return `<div class="prow ${joined ? "on" : "off"} ${sel && sel.id === s.id ? "sel" : ""}" data-seat="${esc(s.id)}" role="button" tabindex="0">
        ${avatar(s)}
        <div class="pcl"><b>${esc(joined ? s.name : s.name || "Empty seat")}${s.role === "owner" ? ' <span class="tag">App owner</span>' : ""}</b>${status}</div>
        <div class="pcl">${s.phone ? `<b class="pph">📞 ${esc(s.phone)}</b>` : '<b class="pph none">📞 —</b>'}<span class="pem">✉ ${esc(s.gmail)}</span></div>
        <div class="pcl"><b>${esc(s.dept || "—")}</b><span>${headName(s) ? "Head: <b>" + esc(headName(s)) + "</b>" : s.role === "owner" ? "Top of the chart" : "No head"}</span></div>
        <div class="pcl">${tt ? `<b>${esc(RB.ttName(tt))}</b>` : '<span>—</span>'}</div>
        <div class="pcl"><span class="tys">${tag(s.designation)}${(s.also || []).map((a) => `<span class="ty" style="background:${RB.desigColor(a)}" title="Also does">+ ${esc(a)}</span>`).join("")}</span></div>
        <div class="pend">${miss}${btn}</div></div>`;
    };
    const html = `<div class="cols"><div class="card pcard" style="flex:2"><div class="stickhead"><div class="subhead"><h3>People · ${D.seats.filter((s) => s.status === "filled").length} in seats</h3><div class="sp"></div>${ed() ? '<button class="btnp edit-only" id="addseat">+ Add seat</button>' : ""}</div>
      <div class="hint">Each official Gmail is one seat. Only Gmails added here can join Rafiq Builders. When someone leaves, the seat stays and the history stays.</div>${list.length ? head : ""}</div>
      ${list.map(row).join("") || (D.members.some((m) => m.status === "active") && ed() ? '<div class="hint warn edit-only">Your team is already using the app. Turn the people who are in it now into seats first; they keep all their tasks and their place in the chart.<br><button class="btnp" id="migrate" style="margin-top:8px">Make seats from the current team</button></div>' : '<div class="muted">No seats yet. Add the first one.</div>')}</div></div>`;
    return { html, side: seatPanel(sel) };
  }

  /** First time: turn the people who are already in the team (pilot) into seats, keeping their tasks and their place in the chart. */
  async function migrate() {
    const act = D.members.filter((m) => m.status === "active"), now = Date.now(), skipped = [];
    const idOf = (m) => RB.gkey(m.email);
    for (const m of act) {
      if (!gmailOk(idOf(m))) { skipped.push(m.name || m.uid); continue; }
      const head = act.find((x) => x.uid === m.reportsTo && gmailOk(idOf(x)));
      const owner = m.role === "owner" || (D.team && D.team.ownerUid === m.uid);
      await RB.Store.put("seats", idOf(m), { gmail: idOf(m), name: m.name || "", designation: owner ? "Owner" : "", dept: "", reportsTo: head ? idOf(head) : "", role: owner ? "owner" : "member", status: "filled", uid: m.uid, extraGivers: [], override: {}, holders: [{ uid: m.uid, name: m.name || "", from: m.joinedAt || now }] });
    }
    await done("Made seats from the current team (" + (act.length - skipped.length) + " people)");
    if (skipped.length) RB.alertBox("These people have no Gmail on file, so they got no seat yet: " + esc(skipped.join(", ")));
  }

  function seatPanel(s) {
    if (!s) return "";
    const h = RB.headOf(s), e = RB.effective(s);
    const hist = (s.holders || []).slice().reverse().map((x) => `<div class="tlr"><span class="t" style="width:80px">${fmtShort(x.from)}${x.to ? " – " + fmtShort(x.to) : ""}</span><div><b>${esc(x.name)}</b><p>${x.to ? "Left the seat" : "Now in the seat"}</p></div></div>`).join("");
    const sub = [s.designation, s.dept].filter(Boolean).join(" · ") + (h ? " · reports to " + (h.status === "filled" ? h.name : h.name || h.gmail) : "");
    return `<div class="ph"><div class="pav" style="background:${s.status === "filled" ? "linear-gradient(135deg,#F2C7A5,#E08A3C)" : "#C9CACD"}">${face(s)}</div><h2>${esc(RB.holderName(s))}</h2><div class="s">${esc(sub)}</div>${(s.also || []).length ? `<div class="s">Also does: <b>${esc(s.also.join(", "))}</b></div>` : ""}<div class="s">${esc(s.gmail)}${s.phone ? " · " + esc(s.phone) : ""}</div><div class="s">${RB.ttOf(s) ? "Timetable: " + esc(RB.ttName(RB.ttOf(s))) : '<span class="tag amb">No timetable</span>'}</div>
      ${ed() ? `<div class="pb edit-only"><button style="background:var(--yel)" data-editseat="${esc(s.id)}">Edit</button>${s.status !== "filled" && s.invited ? `<button style="background:#DDF0E3;color:#1F6B3B" data-wainv="${esc(s.id)}">🟢 Send invite on WhatsApp</button>` : ""}${s.status === "filled" && s.role !== "owner" && RB.isOwner() ? `<button style="background:#F9DAD8;color:#9E2B27" data-offboard="${esc(s.id)}">Offboard / Delete</button>` : ""}${s.status !== "filled" && s.role !== "owner" && RB.isOwner() ? `<button style="background:#F9DAD8;color:#9E2B27" data-delseat="${esc(s.id)}">Delete seat</button>` : ""}</div>` : ""}</div>
      <div class="sec"><h4>Seat history</h4>${hist || '<div class="sub">No one has held this seat yet.</div>'}</div>
      <div class="sec"><h4>Access now</h4><div class="sub">${Object.values(e.app).filter(Boolean).length} of ${RB.APP_SW.length} app switches on · Portal: ${RB.SECTIONS.filter(([k]) => e.portal[k] !== "none").map(([k, l]) => l + " " + e.portal[k]).join(", ") || "none"}</div></div>`;
  }

  /** The seat form (locked 23 Nov): Name, Official phone, Official Gmail (editable later too, his choice 23 Nov), Designation,
   *  Department (from the designation), Reports to, Timetable. */
  function seatForm(s, prefill) {
    const id = s ? s.id : "", p = s || prefill || {};
    const heads = [["", "— none (top) —"]].concat(boardOpt()).concat(D.seats.filter((x) => !id || (x.id !== id && !RB.wouldLoop(id, x.id))).map((x) => [x.id, RB.holderName(x) === "Empty seat" ? x.gmail : RB.holderName(x) + " · " + x.designation]));
    const f = [
      { k: "name", label: "NAME", value: p.name || "", ph: "Full name" },
      { k: "phone", label: "OFFICIAL PHONE", type: "phone", value: p.phone || "" },
      { k: "gmail", label: "OFFICIAL GMAIL", value: p.gmail || "", ph: "name@gmail.com" },
    ].concat([
      { k: "designation", label: "DESIGNATION", type: "select", value: p.designation || RB.desigList()[0].name, options: RB.desigList().map((d) => d.name) },
      // WEB21 Other jobs this person covers: plot steps, the SOP warning and his role SOP count them; the chart shows only the designation.
      { k: "also", label: "ALSO DOES · OTHER JOBS THIS PERSON COVERS (OPTIONAL)", type: "chips", value: p.also || [], options: alsoOpts(p), hint: "Plot file steps for these jobs also come to this person. The main designation above still shows everywhere." },
      deptField("DEPARTMENT", p.dept),
      { k: "reportsTo", label: "REPORTS TO", type: "select", value: p.reportsTo || "", options: heads },
      { k: "timetable", label: "TIMETABLE", type: "select", value: (s && RB.ttOf(s) ? s.timetable : ""), options: ttOpts(id) },
    ]);
    if (s && RB.isOwner()) f.push({ k: "role", label: "ROLE", type: "select", value: s.role, options: [["member", "Member"], ["owner", "App owner (sees everyone, gives tasks to anyone)"]] });
    const check = (v) => {
      v.name = v.name.trim().replace(/\s+/g, " "); v.phone = RB.digitsFmt(v.phone, "phone");
      if (!v.name) return "Type the name.";
      if (!RB.phoneOk(v.phone)) return "The official phone must be a mobile number: 11 digits starting 03, e.g. 0300 1234567.";
      if (!v.timetable && (!s || s.timetable)) return RB.timetables().length ? "Pick a timetable." : "Make a timetable first, in Team › Timetables.";
      return "";
    };
    if (!s) {
      RB.form("Add seat", f, "Add seat", async (v) => {
        const g = RB.gkey(v.gmail), bad = check(v);
        if (bad) return bad;
        if (!gmailOk(g)) return "Type a plain Gmail address (name@gmail.com).";
        if (RB.seat(g)) return "That Gmail already has a seat.";
        await RB.Store.put("seats", g, { gmail: g, name: v.name, phone: v.phone, invited: true, designation: v.designation, also: v.also.filter((a) => a !== v.designation), dept: v.dept === "__new" ? "" : v.dept, reportsTo: v.reportsTo, timetable: v.timetable, role: "member", status: "empty", uid: "", extraGivers: [], override: {}, holders: [], addedAt: Date.now() });
        S.seatSel = g; await done("Added seat " + g + " for " + v.name + " (" + v.designation + ")");
        if (v.dept === "__new") newDeptFor(g);
      }, "The person signs in to the phone app with this Gmail and lands in this seat.");
    } else {
      RB.form("Edit seat", f, "Save", async (v) => {
        const bad = check(v); if (bad) return bad;
        const g = RB.gkey(v.gmail), moved = g !== id;
        if (moved) {
          if (!gmailOk(g)) return "Type a plain Gmail address (name@gmail.com).";
          if (RB.seat(g)) return "That Gmail already has a seat.";
          if (s.role === "owner" || (s.uid && s.uid === RB.uidOf())) return "This Gmail can't be changed here: it would sign out the app owner or you.";
        }
        const patch = { name: v.name, phone: v.phone, designation: v.designation, also: v.also.filter((a) => a !== v.designation), dept: v.dept === "__new" ? s.dept || "" : v.dept, role: v.role || s.role };
        if (v.timetable) patch.timetable = v.timetable;
        if (s.status !== "filled") patch.invited = true;
        // The one name shows everywhere, the person now in the seat included.
        else patch.holders = (s.holders || []).map((h, i, a) => (i === a.length - 1 && !h.to ? Object.assign({}, h, { name: v.name }) : h));
        if (v.dept === "__new") newDeptFor(id);
        await RB.Store.put("seats", id, patch, true);
        const hd = v.reportsTo !== (s.reportsTo || "");
        if (hd) { await RB.Store.put("seats", id, { reportsTo: v.reportsTo }, true); }
        const ch = [v.name !== (s.name || "") ? "name " + v.name : "", v.phone !== (s.phone || "") ? "phone " + (v.phone || "none") : "", v.timetable && v.timetable !== s.timetable ? "timetable " + RB.ttName(RB.ttOf({ timetable: v.timetable })) : "", hd ? "now reports to " + (v.reportsTo || "nobody") : "", v.role && v.role !== s.role ? "role " + v.role : ""].filter(Boolean);
        if (moved) { await moveGmail(id, g, Object.assign({}, patch, { reportsTo: v.reportsTo })); ch.push("Gmail now " + g); }
        await done("Edited seat " + id + (ch.length ? " · " + ch.join(" · ") : ""));
      }, s.status === "filled" ? "If you change the Gmail, " + esc(s.name) + " signs in again with the new one. Open tasks wait in the seat and go back when they sign in." : "");
    }
    wireDesigDept(p.dept || "");
  }
  /** Gmail changed (23 Nov, his choice): the seat moves to the new Gmail, with its place in the chart and history. Whoever is in
   *  it signs in again with the new Gmail; until then their open tasks wait with the seat (like caretaker tasks) and go back
   *  to them when they sign in. */
  async function moveGmail(oldId, g, extra) {
    const s = RB.seat(oldId), now = Date.now(), filled = s.status === "filled" && s.uid;
    const data = Object.assign({}, s, extra, { gmail: g }); delete data.id; delete data.perms;
    if (filled) Object.assign(data, { status: "empty", uid: "", invited: true, holders: (s.holders || []).map((h, i, a) => (i === a.length - 1 && !h.to ? Object.assign({}, h, { to: now }) : h)) });
    await RB.Store.put("seats", g, data);
    for (const x of D.seats.filter((y) => y.id !== oldId && (y.reportsTo === oldId || (y.extraGivers || []).includes(oldId))))
      await RB.Store.put("seats", x.id, { reportsTo: x.reportsTo === oldId ? g : x.reportsTo || "", extraGivers: (x.extraGivers || []).map((e) => (e === oldId ? g : e)) }, true);
    const open = (t) => !["done", "checked", "cancelled", "returned"].includes(t.status);
    for (const t of D.tasks.filter(open)) {
      let c = t.caretaker && t.caretaker.seatId === oldId ? Object.assign({}, t.caretaker, { seatId: g }) : null;
      if (!c && filled && t.toUid === s.uid) c = { seatId: g, forName: s.name, role: "to", uid: s.uid, name: s.name, keep: false, at: now };
      else if (!c && filled && t.fromUid === s.uid && t.toUid !== s.uid) c = { seatId: g, forName: s.name, role: "by", uid: s.uid, name: s.name, keep: false, at: now };
      if (c) await RB.Store.put("tasks", t.id, { caretaker: c }, true);
    }
    if (filled) await RB.Store.put("members", s.uid, { status: "removed", perms: { app: {}, portal: { app: "none", meetings: "none", team: "none", docs: "none", settings: "none" } }, removedAt: now }, true);
    await RB.Store.del("seats", oldId);
    if (S.seatSel === oldId) S.seatSel = g;
  }
  const addSeat = (prefill) => seatForm(null, prefill);
  const editSeat = (id) => seatForm(RB.seat(id));

  // ------------------------------------------------------------------ chart
  const branchNames = (s) => RB.descendants(s.id).map((x) => RB.holderName(x));
  const boardOpt = () => (RB.boardOwners().length ? [[RB.OWNERS, "All owners (" + RB.boardOwners().map((o) => RB.holderName(o)).join(", ") + ")"]] : []);
  function impact(s, newHeadId) {
    const nh = newHeadId ? RB.seat(newHeadId) : null, old = RB.headOf(s);
    const desc = RB.descendants(s.id), branchUids = [s.uid].concat(desc.map((x) => x.uid)).filter(Boolean);
    const open = D.tasks.filter((t) => branchUids.includes(t.toUid) && t.status !== "done" && t.status !== "checked" && t.status !== "cancelled").length;
    const oldChain = RB.chainOf(s).map((x) => x.id), newChain = nh ? (nh.virtual ? RB.boardOwners() : [nh].concat(RB.chainOf(nh))).map((x) => x.id) : [];
    const sees = newChain.filter((i) => !oldChain.includes(i)).map((i) => RB.holderName(RB.seat(i)));
    const stops = oldChain.filter((i) => !newChain.includes(i)).map((i) => RB.holderName(RB.seat(i)));
    const side = D.seats.filter((x) => x.id !== s.id && x.role !== "owner" && (x.reportsTo || "") === (newHeadId || "") && x.status === "filled").map((x) => first(x.name));
    return { nh, old, desc, open, sees, stops, side };
  }
  function moveSeat(id, newHeadId) {
    const s = RB.seat(id); if (!s || (s.reportsTo || "") === (newHeadId || "")) return;
    if (RB.wouldLoop(id, newHeadId)) return RB.alertBox("A person can't report to someone in his own team.");
    const im = impact(s, newHeadId), nm = RB.holderName(s);
    const list = (a) => (a.length ? a.map(esc).join(", ") : "nobody");
    const m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">Check before saving</b><div class="impact" style="margin-top:10px">
      <b>${esc(nm)}</b> will report to <b>${im.nh ? esc(RB.holderName(im.nh)) : "nobody (top)"}</b> (now ${im.old ? esc(RB.holderName(im.old)) : "nobody (top)"}).<br>
      • Reports under ${esc(first(nm))} move with ${s.role === "owner" ? "them" : "him"}: ${list(im.desc.map((x) => RB.holderName(x)))}<br>
      • Now also see ${esc(first(nm))}'s branch: ${list(im.sees)}<br>
      • Stop seeing it: ${list(im.stops)}<br>
      • Open tasks stay as they are: ${im.open}<br>
      • New sideways colleagues (can give tasks to ${esc(first(nm))}): ${list(im.side)}</div>
      <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:16px"><button class="link" id="cn">Cancel</button><button class="btnp" id="cy">Confirm</button></div></div></div>`;
    document.getElementById("cn").onclick = () => (m.innerHTML = "");
    document.getElementById("cy").onclick = async () => { m.innerHTML = ""; await RB.Store.put("seats", id, { reportsTo: newHeadId || "" }, true); await done("Chart: " + nm + " now reports to " + (im.nh ? RB.holderName(im.nh) : "nobody (top)") + " (was " + (im.old ? RB.holderName(im.old) : "nobody (top)") + ")"); RB.render(); };
  }

  function node(s) {
    const g = (s.extraGivers || []).length;
    const cls = s.status !== "filled" ? "emp" : s.role === "owner" ? "own" : "";
    return `<div class="node ${cls} ${S.seatSel === s.id ? "sel" : ""}" draggable="${ed()}" data-node="${esc(s.id)}"><b>${s.status === "filled" ? esc(first(s.name)) : s.invited && s.name ? esc(first(s.name)) + ' <span class="inv">invited</span>' : "Empty seat"}</b><span class="ty" style="background:${s.role === "owner" ? "#fff" : RB.desigColor(s.designation)}">${esc(s.designation || "")}${s.role === "owner" ? (s.designation ? " · " : "") + "App owner" : ""}</span>${g ? `<span class="gv">● ${g} extra giver${g > 1 ? "s" : ""}</span>` : ""}${RB.isBoardOwner(RB.seat(s.reportsTo)) ? `<span class="gv only">→ ${esc(RB.holderName(RB.seat(s.reportsTo)))} only</span>` : ""}</div>`;
  }
  // People under one head sit together by department; two or more of the same department share one labelled outline.
  const kidsHtml = (arr) => {
    const nm = (x) => RB.holderName(x).toLowerCase(), dp = (x) => (x.dept || "").trim();
    const sorted = arr.slice().sort((a, b) => (dp(a) || "\uffff").localeCompare(dp(b) || "\uffff") || nm(a).localeCompare(nm(b)));
    let out = "", i = 0;
    while (i < sorted.length) {
      const d = dp(sorted[i]); let j = i; while (j < sorted.length && dp(sorted[j]) === d) j++;
      const grp = sorted.slice(i, j);
      out += d && grp.length > 1 ? groupHtml(d, grp) : grp.map(subtree).join("");
      i = j;
    }
    return out;
  };
  // Each department has its own soft colour. When at most one person in the group has a team, the group's boxes sit
  // side by side in one row (that person in the middle) and his team hangs below, so the boxes don't drift apart.
  const DTINT = [["#EEF3FC", "#C9D8F2", "#3D5F9E"], ["#FDF3E7", "#F0D6B4", "#9A6420"], ["#ECF8F1", "#BFE3CE", "#2F7A4E"], ["#F4EEFC", "#D9CBF2", "#6A4BA3"], ["#FDEFF2", "#F2C9D3", "#A3405A"], ["#EAF6F8", "#BEE0E6", "#2C6F7C"], ["#F8F6E6", "#E4DDAE", "#7A6C1E"], ["#EEF0FB", "#CACFF0", "#4651A0"], ["#FBEFEA", "#EDCBBE", "#9C4A2C"], ["#EDF6EA", "#C6E0BC", "#477A35"], ["#F9EEF8", "#E8C6E4", "#8E3F86"], ["#EEF2F4", "#CBD5DB", "#4A5D69"]];
  const deptTint = (d) => { const i = Math.max(0, RB.deptList().indexOf(d)); const c = DTINT[i % DTINT.length]; return `--dbg:${c[0]};--dln:${c[1]};--dtx:${c[2]}`; };
  const groupHtml = (d, grp) => {
    const lab = `<div class="dglab">${esc(d)}</div>`, withKids = grp.filter((x) => RB.children(x.id).length);
    // Several of them have teams: only a band behind their own boxes is coloured (placed by placeBands), never their teams.
    if (withKids.length > 1) return `<li class="dg"><div class="dgroup multi" style="${deptTint(d)}"><div class="dband">${lab}</div><ul>${grp.map(subtree).join("")}</ul></div></li>`;
    const lead = withKids[0], rest = grp.filter((x) => x !== lead), h = Math.floor(rest.length / 2);
    const k = lead ? RB.children(lead.id) : [];
    const side = (a) => `<div class="dside">${a.map(node).join("")}</div>`;
    const inner = lead ? side(rest.slice(0, h)) + node(lead) + side(rest.slice(h)) : rest.map(node).join("");
    return `<li class="dg"><div class="dgroup" style="${deptTint(d)}"><div class="dband">${lab}</div><div class="drow">${inner}</div></div>${k.length ? "<ul>" + kidsHtml(k) + "</ul>" : ""}</li>`;
  };
  const subtree = (s) => { const k = RB.children(s.id); return `<li>${node(s)}${k.length ? "<ul>" + kidsHtml(k) + "</ul>" : ""}</li>`; };
  function chart() {
    const board = RB.boardOwners(), bIds = board.map((o) => o.id);
    const tops = D.seats.filter((s) => !bIds.includes(s.id) && (!s.reportsTo || !RB.seat(s.reportsTo) || s.reportsTo === s.id));
    const bKids = D.seats.filter((s) => s.reportsTo === RB.OWNERS || bIds.includes(s.reportsTo));
    const boardLi = board.length ? `<li><div class="board" data-node="${RB.OWNERS}" title="Drop a box here to report to all owners"><div class="dglab">OWNERS${board.length > 1 ? " · drop here to report to all" : ""}</div><div class="brow">${board.map(node).join("")}</div></div>${bKids.length ? "<ul>" + kidsHtml(bKids) + "</ul>" : ""}</li>` : "";
    const z = S.chartZoom || 1;
    const html = `<div class="card"><div class="subhead"><h3>Chart</h3><span class="sub">${ed() ? "Drag a box onto its new head. Click a box for details." : "Click a box for details."}</span><div class="sp"></div>
      <div class="zoom"><button class="sc" data-zoom="out" title="Zoom out">−</button><span class="sub" id="zpct">${Math.round(z * 100)}%</span><button class="sc" data-zoom="in" title="Zoom in">+</button><button class="sc" data-zoom="fit">Fit</button><button class="sc" data-zoom="100">100%</button></div></div>
      <div class="chart" id="chartbox"><div class="tree" id="tree" style="zoom:${z}">${ed() ? '<div class="node toprow" data-node="" title="Drop a box here: nobody above him">TOP · drop here for no head</div>' : ""}<ul>${boardLi}${kidsHtml(tops)}</ul></div></div></div>`;
    return { html };
  }
  /** The details of one box (was the right-hand panel): opens as a small popup next to the box. */
  function chartPop(id, anchor) {
    const sel = RB.seat(id); if (!sel) return;
    S.seatSel = id;
    document.querySelectorAll(".node.sel").forEach((x) => x.classList.remove("sel")); if (anchor) anchor.classList.add("sel");
    const heads = [["", "— nobody (top) —"]].concat(sel.id && RB.isBoardOwner(sel) ? [] : boardOpt()).concat(D.seats.filter((x) => x.id !== sel.id && !RB.wouldLoop(sel.id, x.id)).map((x) => [x.id, RB.holderName(x) === "Empty seat" ? x.gmail : RB.holderName(x)]));
    const gv = (sel.extraGivers || []).map((i) => RB.seat(i)).filter(Boolean);
    const m = document.getElementById("modal");
    m.innerHTML = `<div class="back pop"><div class="dlg popc"><div style="display:flex;align-items:flex-start;gap:10px"><div style="flex:1"><b style="font-size:17px">${esc(RB.holderName(sel))}</b><div class="sub">${esc(sel.designation || "")}${sel.dept ? " · " + esc(sel.dept) : ""}${sel.role === "owner" ? " · App owner" : ""}</div><div class="sub">${esc(sel.gmail || "")}</div></div><button class="link" id="fc">Close</button></div>
      <div class="lbl">REPORTS TO</div><select id="rt" ${ed() ? "" : "disabled"} style="width:100%">${heads.map(([v, l]) => `<option value="${esc(v)}" ${v === (sel.reportsTo || "") ? "selected" : ""}>${esc(l)}</option>`).join("")}</select>
      <div class="foot">One head per person. A change shows what it affects before it is saved.</div>
      <div class="lbl">CAN GET TASKS FROM</div><div class="sub">By default: his head, his own branch's heads and colleagues with the same head.</div><div style="margin-top:6px">${gv.map((g) => `<span class="chip">${esc(RB.holderName(g))}${ed() ? `<button data-rmgiver="${esc(g.id)}" title="Remove">✕</button>` : ""}</span>`).join("") || '<span class="sub">No extra givers.</span>'}${ed() ? ' <button class="btnt edit-only" id="addgiver">+ Add</button>' : ""}</div></div></div>`;
    const back = m.querySelector(".back"), card = m.querySelector(".popc");
    back.onclick = (e) => { if (e.target === back) m.innerHTML = ""; };
    m.querySelector("#fc").onclick = () => (m.innerHTML = "");
    // Place it next to the box, inside the window.
    if (anchor) {
      const r = anchor.getBoundingClientRect(), w = card.offsetWidth, h = card.offsetHeight;
      let left = r.right + 12; if (left + w > innerWidth - 8) left = r.left - w - 12; if (left < 8) left = Math.max(8, Math.min(innerWidth - w - 8, r.left));
      let top = Math.min(Math.max(8, r.top - 10), innerHeight - h - 8);
      Object.assign(card.style, { left: left + "px", top: top + "px" });
    }
    const rt = m.querySelector("#rt"); if (rt) rt.onchange = () => { const v = rt.value; m.innerHTML = ""; moveSeat(id, v); };
    m.querySelectorAll("[data-rmgiver]").forEach((b) => (b.onclick = () => { const g = RB.seat(b.dataset.rmgiver); m.innerHTML = ""; RB.Store.put("seats", sel.id, { extraGivers: (sel.extraGivers || []).filter((x) => x !== g.id) }, true).then(() => done("Removed extra giver " + RB.holderName(g) + " for " + RB.holderName(sel))).then(RB.render); }));
    const ag = m.querySelector("#addgiver"); if (ag) ag.onclick = () => {
      const s = sel, cand = D.seats.filter((x) => x.id !== s.id && x.status === "filled" && x.role !== "owner" && !(s.extraGivers || []).includes(x.id) && !RB.canGiveTo(x).some((y) => y.id === s.id));
      if (!cand.length) return RB.alertBox("Everyone who can be added already gives tasks to " + esc(RB.holderName(s)) + ".");
      RB.form("Add a giver for " + RB.holderName(s), [{ k: "g", label: "WHO CAN GIVE HIM TASKS", type: "select", value: cand[0].id, options: cand.map((x) => [x.id, RB.holderName(x) + " · " + x.designation]) }], "Add", async (v) => { await RB.Store.put("seats", s.id, { extraGivers: (s.extraGivers || []).concat(v.g) }, true); await done(RB.holderName(RB.seat(v.g)) + " can now give tasks to " + RB.holderName(s)); });
    };
  }

  // ------------------------------------------------------------------ departments
  function depts() {
    const all = RB.deptList(), seatsOf = (n) => D.seats.filter((s) => (s.dept || "").trim() === n).length;
    S.deptSort = S.deptSort || "az";
    const list = S.deptSort === "seats" ? all.slice().sort((a, b) => seatsOf(b) - seatsOf(a) || a.localeCompare(b)) : all;
    const chips = [["az", "A–Z"], ["seats", "Most seats"]].map(([k, l]) => `<button class="sc ${S.deptSort === k ? "on" : ""}" data-deptsort="${k}">${l}</button>`).join("");
    const stray = Array.from(new Set(D.seats.map((s) => (s.dept || "").trim()).filter((d) => d && !all.includes(d))));
    const html = `<div class="card" style="max-width:760px"><div class="subhead"><h3>Departments · ${all.length}</h3><div class="sp"></div>${chips}${ed() ? '<button class="btnp edit-only" id="adddept">+ New department</button>' : ""}</div>
      <div class="hint">Seats pick their department from this list, so names are always spelled the same. Rename fixes it on every seat at once.</div>
      ${stray.length && ed() ? `<div class="hint warn edit-only">Typed on seats but not in the list: ${stray.map(esc).join(", ")}. <button class="link" id="deptimport">Add them to the list</button></div>` : ""}
      ${list.map((d) => { const n = seatsOf(d); return `<div class="docrow"><span class="ty" style="background:#EEF0F3">${esc(d)}</span><div class="w"><span>${n} seat${n === 1 ? "" : "s"}</span></div>${ed() ? `<button class="btnt edit-only" data-deptren="${esc(d)}">Rename</button>${n ? `<button class="btnt edit-only off" disabled title="Used by ${n} seat${n === 1 ? "" : "s"}. Rename it, or move those seats first.">Delete</button>` : `<button class="btnt edit-only" data-deptdel="${esc(d)}">Delete</button>`}` : ""}</div>`; }).join("") || '<div class="muted">No departments yet.</div>'}</div>`;
    return { html };
  }
  function renDept(old) {
    RB.form("Rename department", [{ k: "name", label: "NAME", value: old }], "Save", async (v) => {
      const n = v.name.trim().replace(/\s+/g, " "); if (!n) return "Type a name.";
      const hit = RB.deptList().find((d) => d.toLowerCase() === n.toLowerCase() && d !== old);
      const name = hit || n; // renaming onto an existing name merges the two
      for (const s of D.seats.filter((x) => (x.dept || "").trim() === old)) await RB.Store.put("seats", s.id, { dept: name }, true);
      const dl = RB.desigList().map((d) => (dDepts(d).includes(old) ? Object.assign({}, d, { depts: Array.from(new Set(dDepts(d).map((x) => (x === old ? name : x)))) }) : d));
      await RB.Store.put("config", "designations", { list: dl }, true);
      await saveDepts(Array.from(new Set(RB.deptList().map((d) => (d === old ? name : d)))), hit ? "Merged department " + old + " into " + name : "Renamed department " + old + " to " + name);
    }, "Type an existing name to merge the two.");
  }

  // ------------------------------------------------------------------ designations
  /** v0.49.4 One tab: departments, each with its designations under it. A designation can sit under several departments. */
  const dDepts = (d) => (d && Array.isArray(d.depts) ? d.depts : []);
  function desigs() {
    const all = RB.desigList(), depts = RB.deptList(), seatsOf = (n, dp) => D.seats.filter((s) => s.designation === n && (dp === undefined || (s.dept || "") === dp)).length;
    S.dsort = S.dsort || "az";
    if (S.dsort === "unchecked") S.dsort = "az";
    const unchecked = [];
    const order = (arr) => S.dsort === "seats" ? arr.slice().sort((a, b) => seatsOf(b.name) - seatsOf(a.name) || a.name.localeCompare(b.name)) : S.dsort === "new" ? arr.slice().reverse() : arr.slice().sort((a, b) => a.name.localeCompare(b.name));
    const shown = (arr) => order(arr);
    const chips = [["az", "A–Z"], ["seats", "Most seats"], ["new", "Newest"]].concat(unchecked.length ? [["unchecked", "Unchecked · " + unchecked.length]] : []).map(([k, l]) => `<button class="sc ${S.dsort === k ? "on" : ""}" data-dsort="${k}">${l}</button>`).join("");
    const row = (d, dp) => { const n = seatsOf(d.name), here = dp ? seatsOf(d.name, dp) : n; const other = dDepts(d).filter((x) => x !== dp);
      return `<div class="docrow"><span class="ty" style="background:${RB.desigColor(d.name)}">${esc(d.name)}</span><div class="w"><span>${here} seat${here === 1 ? "" : "s"}${dp && other.length ? " · also in " + other.map(esc).join(", ") : ""} · ${RB.countOn(d.name)} of ${RB.APP_SW.length} app switches on</span></div>${ed() ? `<button class="btnt edit-only" data-dren="${esc(d.name)}">Rename</button>${dp ? (here ? `<button class="btnt edit-only off" disabled title="${here} seat${here === 1 ? "" : "s"} here. Move them first.">Remove</button>` : `<button class="btnt edit-only" data-dunlink="${esc(d.name)}|${esc(dp)}" title="Take it out of ${esc(dp)}">Remove</button>`) : n ? `<button class="btnt edit-only off" disabled title="Used by ${n} seat${n === 1 ? "" : "s"}">Delete</button>` : `<button class="btnt edit-only" data-ddel="${esc(d.name)}">Delete</button>`}` : ""}</div>`; };
    const section = (dp) => {
      const ds = shown(all.filter((d) => dDepts(d).includes(dp))), people = D.seats.filter((x) => (x.dept || "") === dp).length;
      if (S.dsort === "unchecked" && !ds.length) return "";
      return `<div class="dsec"><div class="dsech"><b>${esc(dp)}</b><span class="sub">${people} seat${people === 1 ? "" : "s"} · ${all.filter((d) => dDepts(d).includes(dp)).length} designation${all.filter((d) => dDepts(d).includes(dp)).length === 1 ? "" : "s"}</span><div class="sp"></div>${ed() ? `<button class="btnt edit-only" data-dadd="${esc(dp)}">+ Designation</button><button class="link edit-only" data-deptren="${esc(dp)}">Rename</button>${people ? `<button class="link edit-only off" disabled title="${people} seat${people === 1 ? "" : "s"} still here. Move them to another department first.">Delete</button>` : `<button class="link edit-only" data-deptdel="${esc(dp)}">Delete</button>`}` : ""}</div>
        ${ds.map((d) => row(d, dp)).join("") || '<div class="muted" style="padding:6px 0">No designations yet.</div>'}</div>`;
    };
    const loose = shown(all.filter((d) => !dDepts(d).length));
    const canFill = all.some((d) => !dDepts(d).length && D.seats.some((s) => s.designation === d.name && (s.dept || "").trim()));
    const html = `<div class="card" style="max-width:860px"><div class="subhead"><h3>Departments & designations</h3><div class="sp"></div>${chips}${ed() ? '<button class="btnp edit-only" id="adddept">+ New department</button>' : ""}</div>
      <div class="hint">Add departments, then the designations under each one. A designation can sit under more than one department. When a seat gets a designation, its department fills in by itself (or you pick from that designation's departments).</div>
      ${depts.map(section).join("")}
      ${loose.length ? `<div class="dsec"><div class="dsech"><b>No department yet</b><span class="sub">${loose.length}</span><div class="sp"></div>${ed() && canFill ? '<button class="btnt edit-only" id="dfill" title="Put each designation under the departments its seats already have">Place them from current seats</button>' : ""}${ed() ? '<button class="btnt edit-only" id="adddes">+ Designation</button>' : ""}</div>${loose.map((d) => row(d, null)).join("")}</div>` : ed() ? '<div style="margin-top:10px"><button class="link edit-only" id="adddes">+ Designation without a department</button></div>' : ""}</div>`;
    return { html };
  }
  /** Add a designation under a department: an existing one, or a new name. */
  function addDesigTo(dp) {
    const others = RB.desigList().filter((d) => !dDepts(d).includes(dp)).map((d) => d.name).sort((a, b) => a.localeCompare(b));
    RB.form("Designation in " + dp, [{ k: "pick", label: "EXISTING DESIGNATION", type: "select", value: "", options: [["", "— none, type a new one below —"]].concat(others.map((n) => [n, n])) }, { k: "name", label: "OR A NEW ONE", ph: "Site Engineer" }], "Add", async (v) => {
      const n = (v.pick || v.name.trim()).replace(/\s+/g, " "); if (!n) return "Pick one or type a name.";
      const hit = RB.desigList().find((d) => d.name.toLowerCase() === n.toLowerCase());
      const list = hit ? RB.desigList().map((d) => (d === hit ? Object.assign({}, d, { depts: Array.from(new Set(dDepts(d).concat(dp))) }) : d)) : RB.desigList().concat({ name: n, app: {}, portal: {}, depts: [dp] });
      await saveDesig(list, (hit ? "Put designation " + hit.name : "Added designation " + n) + " under " + dp);
    });
  }
  const saveDesig = async (list, text) => { await RB.Store.put("config", "designations", { list, updatedAt: Date.now() }, true); await done(text); };
  const saveDepts = async (depts, text) => { D.desig = Object.assign({}, D.desig || {}, { depts }); await RB.Store.put("config", "designations", { depts, updatedAt: Date.now() }, true); await done(text); };
  /** Department options for a designation: its own departments when it has any, else every department. */
  const deptOpts = (desig, cur) => { const own = dDepts(RB.desigOf(desig)); const l = own.length ? own : RB.deptList(); return (own.length === 1 ? [] : [["", "— none —"]]).concat(l.map((d) => [d, d])).concat(cur && !l.includes(cur) ? [[cur, cur + (own.length ? " (not in this designation)" : " (not in list)")]] : []); };
  /** In an open seat form: when the designation changes, the department list follows it (and fills in when there is only one). */
  function wireDesigDept(keepCur) {
    const m = document.getElementById("modal"), ds = m.querySelector('[data-k="designation"]'), dp = m.querySelector('[data-k="dept"]'); if (!ds || !dp) return;
    const fill = (first) => { const cur = first ? keepCur : dp.value; const opts = deptOpts(ds.value, first ? cur : ""); dp.innerHTML = opts.map(([v, l]) => `<option value="${esc(v)}">${esc(l)}</option>`).join(""); const own = dDepts(RB.desigOf(ds.value)); dp.value = first && cur && opts.some((o) => o[0] === cur) ? cur : own.length === 1 ? own[0] : opts.some((o) => o[0] === cur) ? cur : opts[0][0]; };
    // One department: filled in and locked. Several: only that designation's departments (never typed).
    const lab = dp.previousElementSibling, lock = () => { dp.disabled = dDepts(RB.desigOf(ds.value)).length === 1; if (lab) lab.textContent = dp.disabled ? "DEPARTMENT · 🔒 FROM THE DESIGNATION" : "DEPARTMENT"; };
    ds.addEventListener("change", () => { fill(false); lock(); }); fill(true); lock();
  }
  /** WEB21 "Also does" choices: the designations the published plot SOP uses (all designations if none), plus any already ticked. */
  const alsoOpts = (p) => {
    const x = RB.plotSop && RB.plotSop.x ? RB.plotSop.x() : null, all = RB.desigList().map((d) => d.name).filter((n) => n !== "Owner");
    const used = x ? new Set(x.steps.flatMap((s) => [s.role].concat(...Object.values(s.notify || {})))) : null;
    return all.filter((n) => !used || used.has(n) || (p.also || []).includes(n));
  };
  const deptField = (label, value) => ({ k: "dept", label, type: "select", value: value || "", options: [["", "— none —"]].concat(RB.deptList().map((d) => [d, d])).concat(value && !RB.deptList().includes(value) ? [[value, value + " (not in list)"]] : []).concat([["__new", "+ New department…"]]) });
  /** "+ New department…" picked in a seat form: ask the name once the form has closed, add it to the list, then put it on the seat. */
  function newDeptFor(seatId) {
    setTimeout(() => RB.form("New department", [{ k: "name", label: "NAME", ph: "Electrical" }], "Add", async (v) => {
      const n = v.name.trim().replace(/\s+/g, " "); if (!n) return "Type a name.";
      const hit = RB.deptList().find((d) => d.toLowerCase() === n.toLowerCase());
      const name = hit || n;
      if (!hit) await saveDepts(RB.deptList().concat(name), "Added department " + name);
      if (seatId && RB.seat(seatId)) { await RB.Store.put("seats", seatId, { dept: name }, true); await done("Department of " + seatId + ": " + name); }
    }), 60);
  }
  function addDesig() {
    RB.form("New designation", [{ k: "name", label: "NAME", ph: "Site Engineer" }], "Add", async (v) => {
      const n = v.name.trim(); if (!n) return "Type a name."; if (RB.desigOf(n)) return "That designation exists.";
      await saveDesig(RB.desigList().concat({ name: n, app: {}, portal: {} }).map((x) => Object.assign({}, x)), "Added designation " + n);
    });
  }
  function renDesig(old) {
    RB.form("Rename designation", [{ k: "name", label: "NAME", value: old }], "Save", async (v) => {
      const n = v.name.trim(); if (!n) return "Type a name."; if (n !== old && RB.desigOf(n)) return "That designation exists.";
      for (const s of D.seats.filter((x) => x.designation === old)) await RB.Store.put("seats", s.id, { designation: n }, true);
      await saveDesig(RB.desigList().map((x) => (x.name === old ? Object.assign({}, x, { name: n }) : x)), "Renamed designation " + old + " to " + n);
    });
  }

  // ------------------------------------------------------------------ access
  function access() {
    const seats = D.seats.filter((s) => s.status === "filled"), desigs = RB.desigList().slice().sort((a, b) => a.name.localeCompare(b.name));
    if (!S.accSeat || !RB.seat(S.accSeat)) S.accSeat = (seats.find((s) => s.role !== "owner") || seats[0] || {}).id;
    if (!S.accDesig || !RB.desigOf(S.accDesig)) S.accDesig = (desigs.find((d) => !RB.isChecked(d)) || desigs[0] || {}).name;
    const s = RB.seat(S.accSeat), mode = S.accMode === "person" && s ? "person" : "desig";
    const e = mode === "desig" ? { app: RB.defaultsOf(S.accDesig).app, portal: RB.defaultsOf(S.accDesig).portal, changed: {} } : RB.effective(s);
    const can = ed() && !(mode === "person" && s && s.role === "owner");
    const sw = (k) => `<div class="sw ${e.app[k] ? "on" : ""} ${can ? "" : "dis"}" data-sw="${k}" role="switch" aria-checked="${!!e.app[k]}"></div>`;
    const stag = (...keys) => { const key = keys.find((k) => e.changed[k]); return mode === "desig" || !key ? '<span class="tag">default</span>' : `<span class="tag chg" data-resetone="${key}" title="Tap to go back to default">✎ changed</span>`; };
    const who = mode === "desig" ? `<span class="ty" style="background:${RB.desigColor(S.accDesig)}">${esc(S.accDesig)}</span><div class="nm"><b>${esc(S.accDesig)} · defaults</b><span>Everyone with this designation, unless changed for one person</span></div>`
      : `<span class="avs" style="background:${RB.desigColor(s.designation)}">${face(s)}</span><div class="nm"><b>${esc(RB.holderName(s))}</b><span>${esc(s.designation)}${s.dept ? " · " + esc(s.dept) : ""}${RB.headOf(s) ? " · head: " + esc(first(RB.holderName(RB.headOf(s)))) : ""}</span></div>`;
    const cur = S.accDesig && RB.desigOf(S.accDesig);
    const left = `<div class="card" style="flex:1.1"><div class="subhead" style="flex-wrap:nowrap"><div class="seg"><button data-accmode="desig" class="${mode === "desig" ? "on" : ""}">Designations</button><button data-accmode="person" class="${mode === "person" ? "on" : ""}">People</button></div><div class="sp"></div>${mode === "desig" && ed() ? `${cur && RB.isChecked(cur) ? "" : '<button class="btnt edit-only" id="accok" title="Nothing to change here: take it off the Unchecked list">No change needed</button>'}<button class="btnp edit-only" id="acccopy">Copy to…</button>` : ""}${mode === "person" && can ? '<button class="btnt edit-only" id="accreset">↻ Reset to default</button>' : ""}</div>
      <div class="subhead">${mode === "person" ? `<select id="accp" style="flex:1">${seats.map((x) => `<option value="${esc(x.id)}" ${x.id === s.id ? "selected" : ""}>${esc(x.name)} · ${esc(x.designation)}</option>`).join("")}</select>` : `<select id="accd" style="flex:1">${desigs.map((d) => `<option value="${esc(d.name)}" ${d.name === S.accDesig ? "selected" : ""}>${esc(d.name)}${RB.isChecked(d) ? "" : " · unchecked"}</option>`).join("")}</select>`}</div>
      <div class="seatcard" style="cursor:default">${who}</div>${s && s.role === "owner" && mode === "person" ? '<div class="hint">The app owner can do everything, whatever the designation. Call recording stays off unless the person turns it on in his own phone.</div>' : ""}
      <div class="mh2" style="margin-top:12px">IN THE APP</div>
      ${RB.APP_SW.map(([k, t, sub]) => `<div class="swrow"><div class="w"><b>${t}</b>${sub ? `<span>${sub}</span>` : ""}</div>${stag("app." + k)}${sw(k)}</div>`).join("")}</div>`;
    const dis = can ? "" : "pointer-events:none;opacity:.6";
    const lv = (k, opts) => `<div class="lvl" style="${dis}">${opts.map(([v, l]) => `<button data-lvl="${k}:${v}" class="${(k === "app" ? (e.portal.app !== "none" ? "view" : "none") : e.portal[k]) === v ? "on" : ""}">${l}</button>`).join("")}</div>`;
    const NVE = [["none", "None"], ["view", "View"], ["edit", "Edit"]];
    const chip = (key, v, l, on) => `<button class="sc ${on ? "on" : ""}" style="${dis}" data-tick="${key}:${v}">${on ? "✓ " : ""}${l}</button>`;
    const lock = (t) => `<button class="sc" style="opacity:.55;cursor:default" disabled>🔒 ${t}</button>`;
    const extra = (label, html) => `<div style="flex-basis:100%;display:flex;gap:6px;flex-wrap:wrap;align-items:center;padding-top:8px"><span class="mh2" style="margin:0 4px 0 0">${label}</span>${html}</div>`;
    const row = (name, help, tags, ctl, more) => `<div class="swrow" style="flex-wrap:wrap;align-items:flex-start"><div class="w" style="min-width:200px"><b>${name}</b><span>${help}</span></div>${tags}${ctl}${more || ""}</div>`;
    const tabsOn = (key, all) => (Array.isArray(e.portal[key]) ? e.portal[key] : []).filter((t) => all.some((a) => a[0] === t));
    const teamOn = tabsOn("teamTabs", RB.TEAM_TABS), setOn = tabsOn("settingsTabs", RB.SET_TABS);
    const mid = `<div class="card" style="flex:1.25"><div class="mh2">IN THE PORTAL</div>
      ${row("App view", 'See tasks, calendar and late reasons from phones. Read only. “How far” decides whose.', stag("portal.app", "portal.appScope"), lv("app", [["none", "Off"], ["view", "On"]]),
        e.portal.app !== "none" ? extra("HOW FAR", RB.SCOPES.map(([v, l]) => `<button class="sc ${e.portal.appScope === v ? "on" : ""}" style="${dis}" data-lvl="appScope:${v}">${e.portal.appScope === v ? "✓ " : ""}${l}</button>`).join("")) : "")}
      ${row("Meetings", "View: minutes of meetings they were in, and their own actions. Edit: runs meetings, adds decisions, notes, actions, closes minutes.", stag("portal.meetings"), lv("meetings", NVE))}
      ${row("Team", "View: sees the ticked tabs, changes nothing. Edit: changes things in the ticked tabs (seats, chart, departments, approving joins). The People list follows “How far”.", stag("portal.team", "portal.teamTabs"), lv("team", NVE),
        e.portal.team !== "none" ? extra("TABS", RB.TEAM_TABS.map(([v, l]) => chip("teamTabs", v, l, teamOn.includes(v))).join("") + lock("Access · Offboard (owners only)")) : "")}
      ${row("Plot files", "On: sees every plot file and its steps, writes notes in a step and edits the plot owner details. Steps are done only on the phone.", stag("portal.plot"), lv("plot", [["none", "Off"], ["edit", "On"]]))}
      ${row("Documents", "View: own reading list, work documents, policies shared with them. Edit: adds SOPs, job descriptions, policies; sees everyone's reading progress.", stag("portal.docs"), lv("docs", NVE))}
      ${row("Settings", "Off for everyone until you turn it on. View: reads the ticked tabs. Edit: changes them.", stag("portal.settings", "portal.settingsTabs"), lv("settings", NVE),
        e.portal.settings !== "none" ? extra("TABS", RB.SET_TABS.map(([v, l]) => chip("settingsTabs", v, l, setOn.includes(v))).join("") + lock("Check for updates · Appearance (always on)")) : "")}
      <div class="hint">Portal “None” for every section = he can't sign in to the portal at all; the app still works. Check for updates and Appearance are always there for everyone who signs in.</div></div>`;
    const showUn = S.accUn && desigs.some((d) => !RB.isChecked(d)), unN = desigs.filter((d) => !RB.isChecked(d)).length;
    const dl = desigs.filter((d) => !showUn || !RB.isChecked(d)).map((d) => `<div class="swrow ${mode === "desig" && S.accDesig === d.name ? "sel" : ""}"><div class="w"><b>${esc(d.name)}</b></div>${RB.isChecked(d) ? "" : '<span class="tag yel">unchecked</span>'}<span class="sub">${RB.countOn(d.name)} of 9</span><button class="link" data-dacc="${esc(d.name)}">open ›</button></div>`).join("");
    const side = `<div class="card"><div class="subhead" style="margin-bottom:6px"><h3 style="margin:0">Designation defaults</h3><div class="sp"></div>${unN ? `<button class="sc ${showUn ? "on" : ""}" data-accun="1">Unchecked · ${unN}</button>` : ""}</div>${dl}${ed() ? '<button class="link" id="adddes2" style="margin-top:8px">+ New designation</button>' : ""}</div>
      <div class="card" style="margin-top:12px"><h3>Always on for everyone</h3><div class="sub" style="line-height:1.8">📷 Photo proof on tasks he gives<br>🔒 Private tasks<br>⏰ Own reminders, tasks, SMS, widgets, calendar<br>Only owners remove someone from RB. Staff can only sign out.</div></div>`;
    return { html: `<div class="cols">${left}${mid}</div>`, side, sideCards: true };
  }
  async function setSwitch(k, val) {
    if (S.accMode === "desig" && S.accDesig) {
      const list = RB.desigList().map((d) => (d.name === S.accDesig ? Object.assign({}, d, { app: Object.assign({}, d.app) }) : d)); const d = list.find((x) => x.name === S.accDesig); d.app[k] = val; d.checkedAt = Date.now();
      saveDesigLazy(list, "Default for " + S.accDesig + ": " + RB.APP_SW.find((x) => x[0] === k)[1] + " " + (val ? "ON" : "OFF"));
    } else {
      const s = RB.seat(S.accSeat), ov = JSON.parse(JSON.stringify(s.override || {})); ov.app = ov.app || {}; ov.app[k] = val;
      if (RB.defaultsOf(s.designation).app[k] === val) delete ov.app[k];
      s.override = ov; saveOverride(s, RB.holderName(s) + ": " + RB.APP_SW.find((x) => x[0] === k)[1] + " " + (val ? "ON" : "OFF"));
    }
  }
  async function setLevel(k, v) {
    if (S.accMode === "desig" && S.accDesig) {
      const list = RB.desigList().map((d) => (d.name === S.accDesig ? Object.assign({}, d, { portal: Object.assign({}, d.portal) }) : d)); list.find((x) => x.name === S.accDesig).portal[k] = v; list.find((x) => x.name === S.accDesig).checkedAt = Date.now();
      saveDesigLazy(list, "Default for " + S.accDesig + ": portal " + k + " " + (Array.isArray(v) ? v.join(", ") || "none" : v));
    } else {
      const s = RB.seat(S.accSeat), ov = JSON.parse(JSON.stringify(s.override || {})); ov.portal = ov.portal || {}; ov.portal[k] = v;
      if (JSON.stringify(RB.defaultsOf(s.designation).portal[k]) === JSON.stringify(v)) delete ov.portal[k];
      s.override = ov; saveOverride(s, RB.holderName(s) + ": portal " + k + " " + (Array.isArray(v) ? v.join(", ") || "none" : v));
    }
  }

  /** Copy one designation's access (app switches and portal levels) to several others at once. */
  function copyAccess(from) {
    const src = RB.desigOf(from); if (!src) return;
    const m = document.getElementById("modal"), pick = new Set();
    const others = RB.desigList().filter((d) => d.name !== from).sort((a, b) => a.name.localeCompare(b.name));
    const draw = (q) => {
      const vis = others.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase()));
      m.querySelector("#cplist").innerHTML = vis.map((d) => `<label class="swrow" style="cursor:pointer"><input type="checkbox" data-cp="${esc(d.name)}" ${pick.has(d.name) ? "checked" : ""} style="width:18px;height:18px;margin-right:10px"><div class="w"><b>${esc(d.name)}</b></div><span class="sub">${RB.countOn(d.name)} of 9${d.checkedAt ? "" : " · unchecked"}</span></label>`).join("") || '<div class="muted">No designation matches.</div>';
      m.querySelectorAll("[data-cp]").forEach((c) => (c.onchange = () => { c.checked ? pick.add(c.dataset.cp) : pick.delete(c.dataset.cp); count(); }));
      m.querySelector("#cpall").onclick = () => { const all = vis.every((d) => pick.has(d.name)); vis.forEach((d) => (all ? pick.delete(d.name) : pick.add(d.name))); draw(m.querySelector("#cpq").value); count(); };
    };
    const count = () => { const b = m.querySelector("#cpgo"); b.textContent = pick.size ? "Copy to " + pick.size : "Copy"; b.disabled = !pick.size; };
    m.innerHTML = `<div class="back"><div class="dlg wide"><b style="font-size:19px">Copy ${esc(from)}'s access to…</b><div class="foot">App switches and portal levels are copied together. People with their own changes keep them.</div>
      <div style="display:flex;gap:10px;margin:10px 0"><input id="cpq" placeholder="Search designations" style="flex:1"><button class="btnt" id="cpall">Select all</button></div>
      <div id="cplist" style="max-height:52vh;overflow:auto"></div>
      <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="cpc">Cancel</button><button class="btnp" id="cpgo" disabled>Copy</button></div></div></div>`;
    m.querySelector("#cpc").onclick = () => (m.innerHTML = "");
    m.querySelector("#cpq").oninput = (e) => draw(e.target.value);
    m.querySelector("#cpgo").onclick = () => {
      const names = Array.from(pick); if (!names.length) return;
      RB.confirm(`Copy ${esc(from)}'s access to ${names.length} designation${names.length === 1 ? "" : "s"}?<div class="foot" style="margin-top:6px">${names.map(esc).join(", ")}</div>`, "Copy", async () => {
        const now = Date.now();
        const list = RB.desigList().map((d) => (pick.has(d.name) ? Object.assign({}, d, { app: Object.assign({}, src.app || {}), portal: Object.assign({}, src.portal || {}), checkedAt: now }) : d.name === from ? Object.assign({}, d, { checkedAt: now }) : d));
        await saveDesig(list, "Copied " + from + "'s access to " + names.join(", "));
      });
    };
    draw(""); count(); m.querySelector("#cpq").focus();
  }

  /** A member whose Gmail has a seat takes the seat; caretaker tasks that belong to the seat go back to him (unless kept). */
  async function fillFrom(m, s) {
    if (!m || !s || s.status === "filled") return;
    const name = s.name || m.name || s.gmail;   // the seat name wins (locked 23 Nov)
    const holders = (s.holders || []).concat({ uid: m.uid, name, from: Date.now() });
    await RB.Store.put("seats", s.id, { status: "filled", uid: m.uid, name, invited: false, holders }, true);
    const seat = RB.seat(s.id);
    for (const t of D.tasks.filter((x) => x.caretaker && x.caretaker.seatId === s.id && !x.caretaker.keep && x.status !== "done" && x.status !== "checked")) {
      const back = {}; const isTo = t.caretaker.role === "to";
      if (isTo) Object.assign(back, { toUid: m.uid, toName: name, viewers: Array.from(new Set((t.viewers || []).concat(m.uid))) }); else Object.assign(back, { followUpBy: m.uid, followUpName: name });
      back.caretaker = null; back.events = (t.events || []).concat({ at: Date.now(), text: "Back with " + first(name) + " (caretaker " + first(t.caretaker.name || "") + " handed over)" });
      await RB.Store.put("tasks", t.id, back, true);
    }
    return seat;
  }
  let recBusy = false;
  /** Looks for members who signed in with a seat's Gmail and fills the seat. Runs for people who can edit the team. */
  RB.reconcile = async function () {
    if (recBusy || RB.DEMO || !ed()) return;
    const todo = D.members.filter((m) => m.status === "active" && m.seatId && RB.seat(m.seatId) && RB.seat(m.seatId).status === "empty");
    if (!todo.length) { if (D.seats.some((s) => s.role !== "owner" && s.gmail && !s.perms)) RB.sync(); return; }
    recBusy = true;
    try { for (const m of todo) { await fillFrom(m, RB.seat(m.seatId)); await RB.log((m.name || m.seatId) + " joined seat " + m.seatId); } await RB.sync(); } finally { recBusy = false; }
  };

  // ------------------------------------------------------------------ offboard
  function offboard(id) {
    const s = RB.seat(id), now = Date.now(), nm = s.name;
    const open = (t) => t.status !== "done" && t.status !== "checked" && t.status !== "cancelled" && t.status !== "returned";
    const toHim = D.tasks.filter((t) => t.toUid === s.uid && open(t)), byHim = D.tasks.filter((t) => t.fromUid === s.uid && t.toUid !== s.uid && open(t));
    const others = D.seats.filter((x) => x.status === "filled" && x.id !== id);
    const opt = (sel) => others.map((x) => `<option value="${esc(x.id)}" ${x.id === sel ? "selected" : ""}>${esc(x.name)} · ${esc(x.designation)}</option>`).join("");
    const rows = (arr, kind) => arr.map((t) => `<div class="docrow"><div class="w"><b>${esc(t.title)}</b><span>${kind === "to" ? "Task for " + esc(first(nm)) : "Given by " + esc(first(nm)) + " to " + esc(first(t.toName)) + " · follow-up"}</span></div><select data-ct="${esc(t.id)}:${kind}">${opt(others[0] && others[0].id)}</select><label class="sub"><input type="checkbox" data-keep="${esc(t.id)}"> Keep with caretaker</label></div>`).join("");
    const checks = ["Gmail password changed", "Recovery phone moved to HR", "Google: signed out of all devices", "Company phone / SIM taken back"];
    const m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg wide"><b style="font-size:19px">Offboard ${esc(nm)}</b><div class="foot">His open tasks get a temporary caretaker. They return to this seat when a new person signs in with the same Gmail, unless you tick Keep.</div>
      <div class="lbl">GIVE ALL TO</div><select id="allto"><option value="">— choose per task —</option>${others.map((x) => `<option value="${esc(x.id)}">${esc(x.name)} · ${esc(x.designation)}</option>`).join("")}</select>
      <div class="lbl">TASKS FOR HIM · ${toHim.length}</div>${rows(toHim, "to") || '<div class="sub">None open.</div>'}
      <div class="lbl">TASKS HE GAVE · ${byHim.length} (follow-up by)</div>${rows(byHim, "by") || '<div class="sub">None open.</div>'}
      <div class="lbl">HR CHECKLIST</div>${checks.map((c, i) => `<label class="swrow" style="cursor:pointer"><input type="checkbox" class="hrc" data-i="${i}"><span class="w"><b style="font-size:14px">${c}</b></span></label>`).join("")}
      <label class="swrow" style="cursor:pointer"><input type="checkbox" id="gone"><span class="w"><b style="font-size:14px">Remove this seat for good</b><span>Nobody will take it. The seat and its history disappear, his tasks stay with the caretakers, and the people under him move up to his head.</span></span></label>
      <div class="hint warn">His own private tasks stay on his phone and with his Gmail. After this he is signed out of Rafiq Builders in the app and cannot open the portal. His history stays under his name.</div>
      <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="cn">Cancel</button><button class="btnd" id="go" disabled>Offboard</button></div></div></div>`;
    const go = document.getElementById("go"), all = () => [...m.querySelectorAll(".hrc")].every((c) => c.checked);
    m.querySelectorAll(".hrc").forEach((c) => (c.onchange = () => (go.disabled = !all())));
    document.getElementById("allto").onchange = (e) => m.querySelectorAll("[data-ct]").forEach((x) => { if (e.target.value) x.value = e.target.value; });
    document.getElementById("cn").onclick = () => (m.innerHTML = "");
    go.onclick = async () => {
      go.disabled = true;
      const gone = document.getElementById("gone").checked;
      const keep = {}; m.querySelectorAll("[data-keep]").forEach((c) => (keep[c.dataset.keep] = gone || c.checked));
      let n = 0;
      for (const sel of m.querySelectorAll("[data-ct]")) {
        const [tid, kind] = sel.dataset.ct.split(":"), t = D.tasks.find((x) => x.id === tid), cs = RB.seat(sel.value); if (!t || !cs || !cs.uid) continue;
        const patch = { caretaker: { seatId: id, forName: nm, role: kind, uid: cs.uid, name: cs.name, keep: !!keep[tid], at: now }, viewers: Array.from(new Set((t.viewers || []).concat(cs.uid, cs.uid ? RB.chainOf(cs).map((h) => h.uid).filter(Boolean) : []))) };
        if (kind === "to") Object.assign(patch, { toUid: cs.uid, toName: cs.name }); else Object.assign(patch, { followUpBy: cs.uid, followUpName: cs.name });
        patch.events = (t.events || []).concat({ at: now, text: "🔁 Caretaker for " + first(nm) + ": " + first(cs.name) });
        await RB.Store.put("tasks", tid, patch, true); n++;
      }
      const holders = (s.holders || []).map((h, i, a) => (i === a.length - 1 && !h.to ? Object.assign({}, h, { to: now }) : h));
      await RB.Store.put("seats", id, { status: "empty", uid: "", holders, invited: false }, true);
      await RB.Store.put("members", s.uid, { status: "removed", perms: { app: {}, portal: { app: "none", meetings: "none", team: "none", docs: "none", settings: "none" } }, removedAt: now }, true);
      if (gone) {
        for (const x of D.seats.filter((y) => y.reportsTo === id)) await RB.Store.put("seats", x.id, { reportsTo: s.reportsTo || "" }, true);
        await RB.Store.del("seats", id);
        await done("Offboarded " + nm + " · seat " + id + " removed for good · " + n + " tasks to caretakers");
        m.innerHTML = ""; S.seatSel = null; RB.render(); return;
      }
      await done("Offboarded " + nm + " · seat " + id + " is empty · " + n + " tasks to caretakers");
      m.innerHTML = ""; S.seatSel = id; RB.render();
    };
  }

  // An empty or invited seat (a test, a mistake, or nobody there any more) is deleted completely, with its history.
  function deleteSeat(id) {
    const s = RB.seat(id); if (!s || s.status === "filled" || s.role === "owner") return;
    const under = D.seats.filter((x) => x.reportsTo === id);
    if (under.length) return RB.alertBox("Move the people who report to this seat first (Chart, or Edit their “Reports to”): " + esc(under.map((x) => RB.holderName(x)).join(", ")) + ".");
    RB.confirm("Delete the seat " + esc(s.gmail) + "? Its history goes too. Use this for tests and mistakes. When a real person leaves, keep the empty seat.", "Delete seat", async () => {
      await RB.Store.del("seats", id); await done("Deleted seat " + s.gmail); S.seatSel = null;
    }, true);
  }

  // ------------------------------------------------------------------ timetables
  function timetables() {
    const tts = RB.timetables(), miss = RB.noTimetable(), E = S.ttEdit;
    const chip = (s, pick) => `<button class="ttp" ${pick && ed() ? `data-ttpick="${esc(s.id)}" title="Pick a timetable"` : `data-ttseat="${esc(s.id)}" title="Open seat"`}><span class="av" style="background:${RB.desigColor(s.designation)}">${esc((pname(s)[0] || "?").toUpperCase())}</span>${esc(pname(s))}</button>`;
    const card = (t) => { const p = onTt(t.id); return `<div class="tt ${E && E.id === t.id ? "sel" : ""}"><div class="tth"><h4>${esc(RB.ttName(t))}</h4><div class="sp"></div>${ed() ? `<button class="btnt edit-only" data-ttedit="${esc(t.id)}">Edit</button>` : ""}</div>${p.length ? `<div class="ttppl">${p.map((x) => chip(x)).join("")}</div>` : ""}<div class="sub ttn">${p.length ? p.length + (p.length === 1 ? " person" : " people") : "Nobody yet"}</div></div>`; };
    const html = `<div class="card"><div class="subhead"><h3>Timetables · ${tts.length}</h3><div class="sp"></div>${ed() ? '<button class="btnp edit-only" id="ttnew">+ New timetable</button>' : ""}</div>
      <div class="hint">Each person works on one timetable. Change a timetable here and everyone on it gets the new time. Steps already started keep their due time. Holidays are in Settings › Holidays.</div>
      ${miss.length ? `<div class="tt miss"><div class="tth"><h4>⚠ No timetable · ${miss.length}</h4><div class="sp"></div><span class="sub">${ed() ? "Tap a name to pick. " : ""}Until then: 11:00 AM – 9:00 PM, Friday off</span></div><div class="ttppl">${miss.map((x) => chip(x, true)).join("")}</div></div>` : ""}
      ${tts.map(card).join("") || '<div class="muted">No timetables yet.</div>'}</div>`;
    return { html, side: E ? ttPanel(E) : "" };
  }
  const t2m = (v) => { const m = String(v || "").match(/^(\d{1,2}):(\d{2})/); return m ? Number(m[1]) * 60 + Number(m[2]) : NaN; };
  const m2t = (n) => String(Math.floor(n / 60)).padStart(2, "0") + ":" + String(n % 60).padStart(2, "0");
  const moves = (E) => E.people.filter((id) => RB.seat(id) && RB.seat(id).timetable !== E.id).length;
  const saveLbl = (E) => { const n = moves(E); return "Save" + (n ? " · moves " + n + (n === 1 ? " person" : " people") : ""); };
  /** Edit panel (right side). The draft lives in S.ttEdit so a redraw from a live update keeps what was typed. */
  function ttPanel(E) {
    const t = RB.timetables().find((x) => x.id === E.id), now = t ? onTt(t.id) : [];
    const list = D.seats.slice().sort((a, b) => Number(RB.ttOf(b) && RB.ttOf(b).id === E.id) - Number(RB.ttOf(a) && RB.ttOf(a).id === E.id) || pname(a).localeCompare(pname(b)));
    const row = (s) => { const cur = RB.ttOf(s), mine = cur && cur.id === E.id, on = E.people.includes(s.id);
      return `<label class="ttck ${on ? "on" : ""}" ${mine ? 'title="Already on this timetable. To move, tick them on another timetable or edit their seat."' : ""}><input type="checkbox" data-ttck="${esc(s.id)}" ${on ? "checked" : ""} ${mine ? "disabled" : ""}><span class="w"><b>${esc(pname(s))}</b><small>${esc(s.designation || "")}</small></span><span class="${cur ? "sub" : "amb"}">${cur ? esc(RB.ttName(cur)) : "now: none"}</span></label>`; };
    return `<div class="ttpanel"><h3>${t ? esc(RB.ttName(t)) : "New timetable"}</h3><div class="sub">${t ? now.length + (now.length === 1 ? " person" : " people") + " on this timetable" : "Set the hours and days off, then add people. Its name is made from them."}</div>
      <div class="ttrow"><div><div class="lbl">STARTS</div><input type="time" id="ttstart" value="${m2t(E.start)}"></div><div><div class="lbl">CLOSES</div><input type="time" id="ttend" value="${m2t(E.end)}"></div></div>
      <div class="lbl">DAYS OFF</div><div class="ttdays">${[1, 2, 3, 4, 5, 6, 0].map((i) => `<button class="ttd ${E.off.includes(i) ? "on" : ""}" data-ttoff="${i}" aria-pressed="${E.off.includes(i)}" title="${DAYS[i]}">${DAYS[i].slice(0, 3)}</button>`).join("")}</div>
      <div class="lbl">PEOPLE · ADD PEOPLE</div>${list.map(row).join("") || '<div class="muted">No seats yet.</div>'}
      <div class="err" id="tterr"></div>
      <div class="ttfoot">${t ? (now.length ? `<button class="link off" disabled title="${now.length} on it. Move them to another timetable first.">Delete</button>` : '<button class="link" id="ttdel">Delete</button>') : ""}<div class="sp"></div><button class="link" id="ttcancel">Cancel</button><button class="btnp" id="ttsave">${saveLbl(E)}</button></div></div>`;
  }
  async function ttSave(btn) {
    const E = S.ttEdit, err = (t) => { const e = document.getElementById("tterr"); if (e) e.textContent = t; };
    if (!(E.start >= 0 && E.end > E.start)) return err("Closing time must be after the starting time.");
    const name = RB.ttName(E);
    if (RB.timetables().some((x) => x.id !== E.id && RB.ttName(x) === name)) return err("A timetable with these hours and days off already exists: " + name + ".");
    if (E.off.length > 6) return err("Leave at least one working day.");
    const id = E.id || "tt" + Date.now().toString(36), row = { id, name, start: E.start, end: E.end, off: E.off.slice().sort() };
    const old = RB.timetables().find((x) => x.id === id);
    const list = old ? RB.timetables().map((x) => (x.id === id ? row : x)) : RB.timetables().concat(row);
    const mv = E.people.filter((sid) => RB.seat(sid) && RB.seat(sid).timetable !== id);
    btn.disabled = true; RB.hold = true;
    try {
      await RB.Store.put("config", "designations", { timetables: list, updatedAt: Date.now() }, true);
      for (const sid of mv) await RB.Store.put("seats", sid, { timetable: id }, true);
      S.ttEdit = null;
      await done((old ? "Edited" : "Added") + " timetable " + name + (mv.length ? " · moved " + mv.map((x) => pname(RB.seat(x))).join(", ") : ""));
    } catch (e) { btn.disabled = false; err("Couldn't save: " + (e.message || e)); }
    finally { RB.hold = false; RB.render(); }
  }
  function ttPick(seatId) {
    const s = RB.seat(seatId); if (!s) return;
    if (!RB.timetables().length) return RB.alertBox("Make a timetable first: + New timetable.");
    RB.form("Timetable for " + pname(s), [{ k: "tt", label: "TIMETABLE", type: "select", value: "", options: ttOpts(seatId) }], "Save", async (v) => {
      if (!v.tt) return "Pick a timetable.";
      await RB.Store.put("seats", seatId, { timetable: v.tt }, true);
      await done(pname(s) + " is now on timetable " + RB.ttName(RB.ttOf({ timetable: v.tt })));
    });
  }
  function ttBind(root, side) {
    const nw = root.querySelector("#ttnew"); if (nw) nw.onclick = () => { S.ttEdit = { id: "", name: "", start: 11 * 60, end: 21 * 60, off: [5], people: [] }; RB.render(); };
    root.querySelectorAll("[data-ttedit]").forEach((b) => (b.onclick = () => { const t = RB.timetables().find((x) => x.id === b.dataset.ttedit); if (t) { S.ttEdit = { id: t.id, name: t.name, start: t.start, end: t.end, off: t.off.slice(), people: onTt(t.id).map((s) => s.id) }; RB.render(); } }));
    root.querySelectorAll("[data-ttpick]").forEach((b) => (b.onclick = () => ttPick(b.dataset.ttpick)));
    root.querySelectorAll("[data-ttseat]").forEach((b) => (b.onclick = () => { S.seatSel = b.dataset.ttseat; S.tab.team = "people"; RB.render(); }));
    const E = S.ttEdit, P = side && side.querySelector(".ttpanel"); if (!E || !P) return;
    const sv = P.querySelector("#ttsave"), lbl = () => (sv.textContent = saveLbl(E));
    P.querySelector("#ttstart").oninput = (e) => (E.start = t2m(e.target.value));
    P.querySelector("#ttend").oninput = (e) => (E.end = t2m(e.target.value));
    P.querySelectorAll("[data-ttoff]").forEach((b) => (b.onclick = () => { const i = Number(b.dataset.ttoff); E.off = E.off.includes(i) ? E.off.filter((x) => x !== i) : E.off.concat(i); b.classList.toggle("on"); b.setAttribute("aria-pressed", E.off.includes(i)); }));
    P.querySelectorAll("[data-ttck]").forEach((c) => (c.onchange = () => { const id = c.dataset.ttck; E.people = c.checked ? E.people.concat(id) : E.people.filter((x) => x !== id); c.closest(".ttck").classList.toggle("on", c.checked); lbl(); }));
    P.querySelector("#ttcancel").onclick = () => { S.ttEdit = null; RB.render(); };
    sv.onclick = () => ttSave(sv);
    const dl = P.querySelector("#ttdel"); if (dl) dl.onclick = () => RB.confirm("Delete the timetable “" + esc(RB.ttName(E)) + "”? Nobody is on it.", "Delete", async () => {
      if (onTt(E.id).length) return RB.alertBox("Someone was just put on it. Move them first.");
      const n = RB.ttName(E); await RB.Store.put("config", "designations", { timetables: RB.timetables().filter((x) => x.id !== E.id), updatedAt: Date.now() }, true);
      S.ttEdit = null; await done("Deleted timetable " + n);
    }, true);
  }

  // ------------------------------------------------------------------ section object
  RB.sections.team = {
    tabs: () => { const ok = RB.teamTabs(); return RB.TEAM_TABS.concat([["access", "Access"]]).filter((t) => ok.includes(t[0])); },
    render(tab) {
      RB.reconcile();
      const p = tab === "chart" ? chart() : tab === "desig" ? desigs() : tab === "dept" ? depts() : tab === "access" ? access() : tab === "timetables" ? timetables() : people();
      p.title = "Team"; p.sub = tab === "access" ? "Changes reach the phone in a few seconds · off buttons are hidden" : "Seats, chart, designations and access";
      return p;
    },
    bind(root, side) {
      const on = (r, sel, fn) => r && r.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.stopPropagation(); fn(e, ev); }));
      const both = (sel, fn) => { on(root, sel, fn); on(side, sel, fn); };
      on(root, "[data-seat]", (e) => {
        S.seatSel = e.dataset.seat; RB.render();
        // On a phone the person's panel (Edit, Access, Offboard) sits below the whole list: bring it into view.
        if (window.innerWidth < 900) { const sd = document.getElementById("side"); if (sd) setTimeout(() => sd.scrollIntoView({ behavior: "smooth", block: "start" }), 30); }
      });
      root.querySelectorAll(".prow[data-seat]").forEach((e) => (e.onkeydown = (ev) => { if (ev.key === "Enter" && ev.target === e) e.click(); }));
      const ad = root.querySelector("#addseat"); if (ad) ad.onclick = () => addSeat();
      const mg = root.querySelector("#migrate"); if (mg) mg.onclick = () => { mg.disabled = true; migrate().then(RB.render); };
      // WhatsApp invite: opens WhatsApp with a short ready message; he picks the person and sends it himself.
      both("[data-wainv]", (e) => {
        const st = RB.seat(e.dataset.wainv); if (!st) return;
        const txt = `Rafiq Builders: your seat is ready${st.designation ? " (" + st.designation + ")" : ""}.\nOpen https://rafiqbuilders.web.app or the Callback Reminder app, sign in with ${st.gmail} and tap Join Rafiq Builders.`;
        window.open("https://wa.me/?text=" + encodeURIComponent(txt), "_blank", "noopener");
      });
      both("[data-editseat]", (e) => editSeat(e.dataset.editseat));
      both("[data-access]", (e) => { S.accSeat = e.dataset.access; S.accMode = "person"; S.tab.team = "access"; RB.render(); });
      both("[data-offboard]", (e) => offboard(e.dataset.offboard));
      both("[data-delseat]", (e) => deleteSeat(e.dataset.delseat));
      // chart
      const cb = root.querySelector("#chartbox"), tree = root.querySelector("#tree");
      if (cb) {
        // Keep the chart where it was scrolled to (a click or a save redraws the page).
        if (S.chartScroll) { cb.scrollLeft = S.chartScroll[0]; cb.scrollTop = S.chartScroll[1]; }
        cb.onscroll = () => (S.chartScroll = [cb.scrollLeft, cb.scrollTop]);
        // Dragging near an edge scrolls the chart that way.
        cb.ondragover = (ev) => {
          const r = cb.getBoundingClientRect(), edge = 70, step = 24;
          if (ev.clientX < r.left + edge) cb.scrollLeft -= step; else if (ev.clientX > r.right - edge) cb.scrollLeft += step;
          if (ev.clientY < r.top + 40) cb.scrollTop -= step; else if (ev.clientY > r.bottom - 40) cb.scrollTop += step;
          const ce = document.getElementById("content"); if (ce) { if (ev.clientY < 90) ce.scrollTop -= step; else if (ev.clientY > innerHeight - 60) ce.scrollTop += step; }
        };
      }
      // Department bands: sized to cover only the group's own boxes (their teams hang below, outside the band).
      if (tree) tree.querySelectorAll(".dgroup").forEach((g) => {
        const band = g.querySelector(":scope>.dband"), gr = g.getBoundingClientRect(), k = gr.width / (g.offsetWidth || 1);
        const ns = [...g.querySelectorAll(":scope>ul>li>.node, :scope>.drow .node")].map((n) => n.getBoundingClientRect());
        if (!band || !ns.length) return;
        const L = Math.min(...ns.map((r) => r.left)), R = Math.max(...ns.map((r) => r.right)), T = Math.min(...ns.map((r) => r.top)), B = Math.max(...ns.map((r) => r.bottom));
        Object.assign(band.style, { left: (L - gr.left) / k - 14 + "px", width: (R - L) / k + 28 + "px", top: (T - gr.top) / k - 14 + "px", height: (B - T) / k + 28 + "px" });
      });
      const setZoom = (z) => { S.chartZoom = Math.max(0.3, Math.min(1.6, Math.round(z * 100) / 100)); if (tree) tree.style.zoom = S.chartZoom; const p = root.querySelector("#zpct"); if (p) p.textContent = Math.round(S.chartZoom * 100) + "%"; };
      on(root, "[data-zoom]", (e) => {
        const k = e.dataset.zoom, z = S.chartZoom || 1;
        if (k === "in") setZoom(z + 0.1); else if (k === "out") setZoom(z - 0.1); else if (k === "100") setZoom(1);
        else if (cb && tree) { tree.style.zoom = 1; const w = tree.scrollWidth, h = tree.scrollHeight; const room = Math.min((cb.clientWidth - 12) / w, (innerHeight - cb.getBoundingClientRect().top - 30) / h); setZoom(Math.min(1, room)); cb.scrollLeft = 0; }
      });
      root.querySelectorAll("[data-node]").forEach((n) => {
        n.onclick = (ev) => { ev.stopPropagation(); if (!n.dataset.node || n.dataset.node === RB.OWNERS) return; chartPop(n.dataset.node, n); };
        n.ondragstart = (ev) => { ev.stopPropagation(); ev.dataTransfer.setData("text/plain", n.dataset.node); n.classList.add("drag"); if (tree) tree.classList.add("dragging"); };
        n.ondragend = () => { n.classList.remove("drag"); if (tree) tree.classList.remove("dragging"); };
        n.ondragover = (ev) => { ev.preventDefault(); ev.stopPropagation(); n.classList.add("over"); if (cb) cb.ondragover(ev); };
        n.ondragleave = () => n.classList.remove("over");
        n.ondrop = (ev) => {
          ev.preventDefault(); ev.stopPropagation(); n.classList.remove("over"); if (tree) tree.classList.remove("dragging");
          const id = ev.dataTransfer.getData("text/plain"); if (!id || !ed() || id === n.dataset.node) return;
          const s = RB.seat(id);
          if (!n.dataset.node && s && (!s.reportsTo || !RB.seat(s.reportsTo))) return RB.toast(RB.holderName(s) + " is already at the top");
          if (n.dataset.node === RB.OWNERS && RB.isBoardOwner(s)) return RB.toast(RB.holderName(s) + " is already on the owners board");
          moveSeat(id, n.dataset.node);
        };
      });
      // designations
      const dd = root.querySelector("#adddes"); if (dd) dd.onclick = addDesig;
      const dd2 = side && side.querySelector("#adddes2"); if (dd2) dd2.onclick = addDesig;
      both("[data-dren]", (e) => renDesig(e.dataset.dren));
      const cpb = root.querySelector("#acccopy"); if (cpb) cpb.onclick = () => copyAccess(S.accDesig);
      const okb = root.querySelector("#accok"); if (okb) okb.onclick = () => saveDesig(RB.desigList().map((d) => (d.name === S.accDesig ? Object.assign({}, d, { checkedAt: Date.now() }) : d)), "Access for " + S.accDesig + " looked over, no change").then(() => RB.render());
      both("[data-dsort]", (e) => { S.dsort = e.dataset.dsort; RB.render(); });
      const adp = root.querySelector("#adddept"); if (adp) adp.onclick = () => newDeptFor(null);
      const dim = root.querySelector("#deptimport"); if (dim) dim.onclick = () => { const add = D.seats.map((s) => (s.dept || "").trim()).filter(Boolean); saveDepts(Array.from(new Set(RB.deptList().concat(add))), "Added typed departments to the list").then(RB.render); };
      on(root, "[data-deptren]", (e) => renDept(e.dataset.deptren));
      on(root, "[data-deptdel]", (e) => {
        const dp = e.dataset.deptdel, under = RB.desigList().filter((d) => dDepts(d).includes(dp));
        RB.confirm("Delete the department “" + esc(dp) + "”?" + (under.length ? (under.length === 1 ? " Its designation stays" : " Its " + under.length + " designations stay") + " and moves to “No department yet” unless under another department." : ""), "Delete", async () => {
          if (under.length) await RB.Store.put("config", "designations", { list: RB.desigList().map((d) => (dDepts(d).includes(dp) ? Object.assign({}, d, { depts: dDepts(d).filter((x) => x !== dp) }) : d)), updatedAt: Date.now() }, true);
          await saveDepts(RB.deptList().filter((d) => d !== dp), "Deleted department " + dp);
        }, true);
      });
      on(root, "[data-deptsort]", (e) => { S.deptSort = e.dataset.deptsort; RB.render(); });
      on(root, "[data-dadd]", (e) => addDesigTo(e.dataset.dadd));
      on(root, "[data-dunlink]", (e) => { const [n, dp] = e.dataset.dunlink.split("|"); saveDesig(RB.desigList().map((d) => (d.name === n ? Object.assign({}, d, { depts: dDepts(d).filter((x) => x !== dp) }) : d)), "Took designation " + n + " out of " + dp).then(RB.render); });
      const dfl = root.querySelector("#dfill"); if (dfl) dfl.onclick = () => {
        const list = RB.desigList().map((d) => { if (dDepts(d).length) return d; const ds = Array.from(new Set(D.seats.filter((s) => s.designation === d.name).map((s) => (s.dept || "").trim()).filter(Boolean))); return ds.length ? Object.assign({}, d, { depts: ds }) : d; });
        const add = Array.from(new Set(D.seats.map((s) => (s.dept || "").trim()).filter(Boolean))).filter((x) => !RB.deptList().includes(x));
        (add.length ? saveDepts(RB.deptList().concat(add), "Added typed departments") : Promise.resolve()).then(() => saveDesig(list, "Placed designations under their seats' departments")).then(RB.render);
      };
      both("[data-psort]", (e) => { const k = e.dataset.psort; S.psort = [k, S.psort[0] === k ? -S.psort[1] : 1]; RB.render(); });
      both("[data-ddel]", (e) => RB.confirm("Delete the designation “" + esc(e.dataset.ddel) + "”?", "Delete", () => saveDesig(RB.desigList().filter((x) => x.name !== e.dataset.ddel), "Deleted designation " + e.dataset.ddel), true));
      both("[data-dacc]", (e) => { S.accDesig = e.dataset.dacc; S.accMode = "desig"; S.tab.team = "access"; RB.render(); });
      // access
      const accp = root.querySelector("#accp"); if (accp) accp.onchange = () => { S.accSeat = accp.value; RB.render(); };
      const accd = root.querySelector("#accd"); if (accd) accd.onchange = () => { S.accDesig = accd.value; RB.render(); };
      on(root, "[data-accmode]", (e) => { S.accMode = e.dataset.accmode; RB.render(); });
      both("[data-accun]", () => { S.accUn = !S.accUn; RB.render(); });
      on(root, "[data-tick]", (e) => {
        if (!ed()) return; const [k, v] = e.dataset.tick.split(":");
        const eff = S.accMode === "desig" && S.accDesig ? RB.defaultsOf(S.accDesig).portal : RB.effective(RB.seat(S.accSeat)).portal;
        const cur = Array.isArray(eff[k]) ? eff[k] : [], next = cur.includes(v) ? cur.filter((x) => x !== v) : cur.concat(v);
        setLevel(k, next).then(RB.render);
      });
      on(root, "[data-sw]", (e) => { if (!ed() || e.classList.contains("dis")) return; setSwitch(e.dataset.sw, !e.classList.contains("on")).then(RB.render); });
      on(root, "[data-lvl]", (e) => { if (!ed()) return; const [k, v] = e.dataset.lvl.split(":"); setLevel(k, v).then(RB.render); });
      on(root, "[data-resetone]", (e) => { const [grp, k] = e.dataset.resetone.split("."); const s = RB.seat(S.accSeat), ov = JSON.parse(JSON.stringify(s.override || {})); if (ov[grp]) delete ov[grp][k]; s.override = ov; saveOverride(s, RB.holderName(s) + ": " + k + " back to default"); RB.render(); });
      const rs = root.querySelector("#accreset"); if (rs) rs.onclick = () => RB.confirm("Reset all of " + esc(RB.holderName(RB.seat(S.accSeat))) + "'s access to the " + esc(RB.seat(S.accSeat).designation) + " defaults?", "Reset", async () => { const s = RB.seat(S.accSeat); s.override = {}; saveOverride(s, RB.holderName(s) + ": access reset to default"); });
      // timetables
      ttBind(root, side);
    },
  };
  RB.team = { addSeat, editSeat, offboard, moveSeat, migrate };
})();
