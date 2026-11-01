/* Team: People (one list, each Gmail is a seat) · Chart · Designations · Access · Waiting to join · Offboarding. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc, first = RB.first;
  const { fmtShort, fmtWhen } = RB;
  const DAY = 86400000;
  Object.assign(S, { seatSel: null, accSeat: null, accDesig: null, accMode: "person" });
  const ed = () => RB.canEdit("team");
  const gmailOk = (g) => /^[a-z0-9._%+-]+@gmail\.com$/i.test(String(g).trim());
  const initial = (s) => esc((RB.holderName(s) || "?")[0].toUpperCase());
  const tag = (name) => `<span class="ty" style="background:${RB.desigColor(name)}">${esc(name || "No designation")}</span>`;
  const seatLine = (s) => {
    const h = RB.headOf(s);
    return [s.dept, h ? "head: " + (h.status === "filled" ? first(h.name) : first(h.gmail)) : s.role === "owner" ? "App owner" : ""].filter(Boolean).join(" · ");
  };
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
  const saveDesigLazy = (list, label) => { D.desig = Object.assign({}, D.desig || {}, { list }); lazySave("desig", () => RB.Store.put("config", "designations", { list: RB.desigList(), updatedAt: Date.now() }), label); };

  // ------------------------------------------------------------------ people
  function people() {
    const q = (S.q || "").toLowerCase();
    S.psort = S.psort || "az";
    const nm = (s) => (s.status === "filled" ? RB.holderName(s) : s.name || s.gmail || "").toLowerCase();
    const by = { az: (a, b) => nm(a).localeCompare(nm(b)), desig: (a, b) => (a.designation || "~").localeCompare(b.designation || "~") || nm(a).localeCompare(nm(b)), dept: (a, b) => (a.dept || "~").localeCompare(b.dept || "~") || nm(a).localeCompare(nm(b)) }[S.psort];
    const list = D.seats.filter((s) => !q || (RB.holderName(s) + " " + s.gmail + " " + s.designation + " " + (s.dept || "")).toLowerCase().includes(q)).slice().sort(by);
    const pchips = [["az", "A–Z"], ["desig", "Designation"], ["dept", "Department"]].map(([k, l]) => `<button class="sc ${S.psort === k ? "on" : ""}" data-psort="${k}">${l}</button>`).join("");
    const sel = selSeat();
    const row = (s) => s.status === "filled"
      ? `<div class="seatcard ${sel && sel.id === s.id ? "sel" : ""}" data-seat="${esc(s.id)}"><span class="avs" style="background:${RB.desigColor(s.designation)}">${initial(s)}</span><div class="nm"><b>${esc(s.name)}${s.role === "owner" ? ' <span class="tag">App owner</span>' : ""}</b><span>${esc(s.gmail)} · ${esc(seatLine(s))}</span></div>${tag(s.designation)}</div>`
      : `<div class="seatcard empty" data-seat="${esc(s.id)}"><span class="avs" style="background:#ECECEA">＋</span><div class="nm"><b>${s.invited ? esc(s.name) + " · invited" : "Empty seat"}</b><span>${esc(s.gmail)} · ${esc(seatLine(s))}</span></div>${tag(s.designation)}${ed() && s.invited ? `<button class="btnt edit-only" data-wainv="${esc(s.id)}" title="Send the invite on WhatsApp">🟢 Invite</button>` : ""}${ed() ? `<button class="btnt edit-only" data-fill="${esc(s.id)}">${s.invited ? "Edit" : "Fill seat"}</button>` : ""}</div>`;
    const html = `<div class="cols"><div class="card" style="flex:2"><div class="subhead"><h3>People · ${D.seats.filter((s) => s.status === "filled").length} in seats</h3><div class="sp"></div>${pchips}${ed() ? '<button class="btnp edit-only" id="addseat">+ Add seat</button>' : ""}</div>
      <div class="hint">Each official Gmail is one seat. Only Gmails added here can join Rafiq Builders. When someone leaves, the seat stays and the history stays.</div>
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
    const sub = [s.designation, s.dept].filter(Boolean).join(" · ") + (h ? " · reports to " + (h.status === "filled" ? h.name : h.gmail) : "");
    return `<div class="ph"><div class="pav" style="background:${s.status === "filled" ? "linear-gradient(135deg,#F2C7A5,#E08A3C)" : "#C9CACD"}">${initial(s)}</div><h2>${esc(RB.holderName(s))}</h2><div class="s">${esc(sub)}</div><div class="s">${esc(s.gmail)}</div>
      ${ed() ? `<div class="pb edit-only"><button style="background:var(--yel)" data-editseat="${esc(s.id)}">Edit</button><button style="background:var(--blu)" data-access="${esc(s.id)}">Access</button>${s.status === "filled" && s.role !== "owner" && RB.isOwner() ? `<button style="background:#F9DAD8;color:#9E2B27" data-offboard="${esc(s.id)}">Offboard / Delete</button>` : ""}${s.status !== "filled" && s.role !== "owner" && RB.isOwner() ? `<button style="background:#F9DAD8;color:#9E2B27" data-delseat="${esc(s.id)}">Delete seat</button>` : ""}</div>` : ""}</div>
      <div class="sec"><h4>Seat history</h4>${hist || '<div class="sub">No one has held this seat yet.</div>'}</div>
      <div class="sec"><h4>Access now</h4><div class="sub">${Object.values(e.app).filter(Boolean).length} of 9 app switches on · Portal: ${RB.SECTIONS.filter(([k]) => e.portal[k] !== "none").map(([k, l]) => l + " " + e.portal[k]).join(", ") || "none"}</div></div>`;
  }

  function addSeat(prefill) {
    const heads = [["", "— none (top) —"]].concat(D.seats.map((s) => [s.id, RB.holderName(s) === "Empty seat" ? s.gmail : RB.holderName(s) + " · " + s.designation]));
    RB.form("Add seat", [
      { k: "gmail", label: "OFFICIAL GMAIL", value: (prefill && prefill.gmail) || "", ph: "name@gmail.com" },
      { k: "designation", label: "DESIGNATION", type: "select", value: (prefill && prefill.designation) || RB.desigList()[0].name, options: RB.desigList().map((d) => d.name) },
      { k: "dept", label: "DEPARTMENT / SITE", value: (prefill && prefill.dept) || "", ph: "Site A" },
      { k: "reportsTo", label: "REPORTS TO", type: "select", value: (prefill && prefill.reportsTo) || "", options: heads },
    ], "Add seat", async (v) => {
      const g = RB.gkey(v.gmail);
      if (!gmailOk(g)) return "Type a plain Gmail address (name@gmail.com).";
      if (RB.seat(g)) return "That Gmail already has a seat.";
      await RB.Store.put("seats", g, { gmail: g, designation: v.designation, dept: v.dept.trim(), reportsTo: v.reportsTo, role: "member", status: "empty", uid: "", name: "", extraGivers: [], override: {}, holders: [], addedAt: Date.now() });
      S.seatSel = g; await done("Added seat " + g + " (" + v.designation + ")");
    }, "The person joins by signing in to the phone app with this Gmail.");
  }
  function fillSeat(id) {
    RB.form("Fill seat", [{ k: "name", label: "WHO WILL SIT HERE", value: (RB.seat(id) || {}).name || "", ph: "Name" }], "Save", async (v) => {
      if (!v.name.trim()) return "Type a name.";
      await RB.Store.put("seats", id, { name: v.name.trim(), invited: true }, true); await done("Invited " + v.name.trim() + " to seat " + id);
    }, "He joins from the phone app with this Gmail and lands in this seat.");
  }
  function editSeat(id) {
    const s = RB.seat(id), heads = [["", "— none (top) —"]].concat(D.seats.filter((x) => x.id !== id && !RB.wouldLoop(id, x.id)).map((x) => [x.id, RB.holderName(x) === "Empty seat" ? x.gmail : RB.holderName(x) + " · " + x.designation]));
    const f = [{ k: "designation", label: "DESIGNATION", type: "select", value: s.designation, options: RB.desigList().map((d) => d.name) }, { k: "dept", label: "DEPARTMENT / SITE", value: s.dept || "" }, { k: "reportsTo", label: "REPORTS TO", type: "select", value: s.reportsTo || "", options: heads }];
    if (RB.isOwner() && s.id !== "") f.push({ k: "role", label: "ROLE", type: "select", value: s.role, options: [["member", "Member"], ["owner", "App owner (sees everyone, gives tasks to anyone)"]] });
    RB.form("Edit seat", f, "Save", async (v) => {
      const patch = { designation: v.designation, dept: v.dept.trim(), role: v.role || s.role };
      await RB.Store.put("seats", id, patch, true);
      const hd = v.reportsTo !== (s.reportsTo || "");
      if (hd) { await RB.Store.put("seats", id, { reportsTo: v.reportsTo }, true); }
      await done("Edited seat " + id + (hd ? " · now reports to " + (v.reportsTo || "nobody") : "") + (v.role && v.role !== s.role ? " · role " + v.role : ""));
    });
  }

  // ------------------------------------------------------------------ chart
  const branchNames = (s) => RB.descendants(s.id).map((x) => RB.holderName(x));
  function impact(s, newHeadId) {
    const nh = newHeadId ? RB.seat(newHeadId) : null, old = RB.headOf(s);
    const desc = RB.descendants(s.id), branchUids = [s.uid].concat(desc.map((x) => x.uid)).filter(Boolean);
    const open = D.tasks.filter((t) => branchUids.includes(t.toUid) && t.status !== "done" && t.status !== "checked" && t.status !== "cancelled").length;
    const oldChain = RB.chainOf(s).map((x) => x.id), newChain = nh ? [nh].concat(RB.chainOf(nh)).map((x) => x.id) : [];
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
    return `<div class="node ${cls} ${S.seatSel === s.id ? "sel" : ""}" draggable="${ed()}" data-node="${esc(s.id)}"><b>${s.status === "filled" ? esc(first(s.name)) : s.invited && s.name ? esc(first(s.name)) + ' <span class="inv">invited</span>' : "Empty seat"}</b><span class="ty" style="background:${s.role === "owner" ? "#fff" : RB.desigColor(s.designation)}">${esc(s.designation || "")}${s.role === "owner" ? (s.designation ? " · " : "") + "App owner" : ""}</span>${g ? `<span class="gv">● ${g} extra giver${g > 1 ? "s" : ""}</span>` : ""}</div>`;
  }
  const subtree = (s) => { const k = RB.children(s.id); return `<li>${node(s)}${k.length ? "<ul>" + k.map(subtree).join("") + "</ul>" : ""}</li>`; };
  function chart() {
    const tops = D.seats.filter((s) => !s.reportsTo || !RB.seat(s.reportsTo) || s.reportsTo === s.id);
    const z = S.chartZoom || 1;
    const html = `<div class="card"><div class="subhead"><h3>Chart</h3><span class="sub">${ed() ? "Drag a box onto its new head. Click a box for details." : "Click a box for details."}</span><div class="sp"></div>
      <div class="zoom"><button class="sc" data-zoom="out" title="Zoom out">−</button><span class="sub" id="zpct">${Math.round(z * 100)}%</span><button class="sc" data-zoom="in" title="Zoom in">+</button><button class="sc" data-zoom="fit">Fit</button><button class="sc" data-zoom="100">100%</button></div></div>
      <div class="chart" id="chartbox"><div class="tree" id="tree" style="zoom:${z}">${ed() ? '<div class="node toprow" data-node="" title="Drop a box here: nobody above him">TOP · drop here for no head</div>' : ""}<ul>${tops.map(subtree).join("")}</ul></div></div></div>`;
    return { html };
  }
  /** The details of one box (was the right-hand panel): opens as a small popup next to the box. */
  function chartPop(id, anchor) {
    const sel = RB.seat(id); if (!sel) return;
    S.seatSel = id;
    document.querySelectorAll(".node.sel").forEach((x) => x.classList.remove("sel")); if (anchor) anchor.classList.add("sel");
    const heads = [["", "— nobody (top) —"]].concat(D.seats.filter((x) => x.id !== sel.id && !RB.wouldLoop(sel.id, x.id)).map((x) => [x.id, RB.holderName(x) === "Empty seat" ? x.gmail : RB.holderName(x)]));
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

  // ------------------------------------------------------------------ designations
  function desigs() {
    const all = RB.desigList(), seatsOf = (n) => D.seats.filter((s) => s.designation === n).length;
    S.dsort = S.dsort || "az";
    const unchecked = all.filter((d) => !d.checkedAt);
    if (S.dsort === "unchecked" && !unchecked.length) S.dsort = "az";
    const list = S.dsort === "unchecked" ? unchecked.slice().sort((a, b) => a.name.localeCompare(b.name)) : S.dsort === "az" ? all.slice().sort((a, b) => a.name.localeCompare(b.name)) : S.dsort === "seats" ? all.slice().sort((a, b) => seatsOf(b.name) - seatsOf(a.name) || a.name.localeCompare(b.name)) : all.slice().reverse();
    const chips = [["az", "A–Z"], ["seats", "Most seats"], ["new", "Newest"]].concat(unchecked.length ? [["unchecked", "Unchecked · " + unchecked.length]] : []).map(([k, l]) => `<button class="sc ${S.dsort === k ? "on" : ""}" data-dsort="${k}">${l}</button>`).join("");
    const html = `<div class="card" style="max-width:760px"><div class="subhead"><h3>Designations · ${all.length}</h3><div class="sp"></div>${chips}${ed() ? '<button class="btnp edit-only" id="adddes">+ New designation</button>' : ""}</div>
      <div class="hint">A designation is only a label. It carries the default access, which you can still change for any one person.</div>
      ${list.map((d) => { const n = seatsOf(d.name); return `<div class="docrow"><span class="ty" style="background:${RB.desigColor(d.name)}">${esc(d.name)}</span><div class="w"><span>${n} seat${n === 1 ? "" : "s"} · ${RB.countOn(d.name)} of 9 app switches on</span></div>${ed() ? `<button class="btnt edit-only" data-dacc="${esc(d.name)}">Edit access</button><button class="btnt edit-only" data-dren="${esc(d.name)}">Rename</button>${n ? `<button class="btnt edit-only off" disabled title="Used by ${n} seat${n === 1 ? "" : "s"}. Give those seats another designation first.">Delete</button>` : `<button class="btnt edit-only" data-ddel="${esc(d.name)}">Delete</button>`}` : ""}</div>`; }).join("")}</div>`;
    return { html };
  }
  const saveDesig = async (list, text) => { await RB.Store.put("config", "designations", { list, updatedAt: Date.now() }); await done(text); };
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
    const seats = D.seats.filter((s) => s.status === "filled");
    if (!S.accSeat || !RB.seat(S.accSeat)) S.accSeat = (seats.find((s) => s.role !== "owner") || seats[0] || {}).id;
    const s = RB.seat(S.accSeat), mode = S.accMode === "desig" && S.accDesig ? "desig" : "person";
    const e = mode === "desig" ? { app: RB.defaultsOf(S.accDesig).app, portal: RB.defaultsOf(S.accDesig).portal, changed: {} } : RB.effective(s);
    const can = ed() && !(mode === "person" && s && s.role === "owner");
    const def = mode === "person" && s ? RB.defaultsOf(s.designation) : null;
    const sw = (k) => `<div class="sw ${e.app[k] ? "on" : ""} ${can ? "" : "dis"}" data-sw="${k}" role="switch" aria-checked="${!!e.app[k]}"></div>`;
    const stag = (key) => mode === "desig" || !e.changed[key] ? '<span class="tag">default</span>' : `<span class="tag chg" data-resetone="${key}" title="Tap to go back to default">✎ changed</span>`;
    const who = mode === "desig" ? `<span class="ty" style="background:${RB.desigColor(S.accDesig)}">${esc(S.accDesig)}</span><div class="nm"><b>${esc(S.accDesig)} · defaults</b><span>Everyone with this designation, unless changed for one person</span></div>`
      : `<span class="avs" style="background:${RB.desigColor(s.designation)}">${initial(s)}</span><div class="nm"><b>${esc(RB.holderName(s))}</b><span>${esc(s.designation)}${s.dept ? " · " + esc(s.dept) : ""}${RB.headOf(s) ? " · head: " + esc(first(RB.holderName(RB.headOf(s)))) : ""}</span></div>`;
    const left = `<div class="card" style="flex:1.1"><div class="subhead" style="flex-wrap:nowrap">${mode === "person" ? `<select id="accp">${seats.map((x) => `<option value="${esc(x.id)}" ${x.id === s.id ? "selected" : ""}>${esc(x.name)} · ${esc(x.designation)}</option>`).join("")}</select>` : '<button class="btnt" id="accback">‹ Back to people</button>'}<div class="sp"></div>${mode === "desig" && ed() ? `${(RB.desigOf(S.accDesig) || {}).checkedAt ? "" : '<button class="btnt edit-only" id="accok" title="Nothing to change here: take it off the Unchecked list">No change needed</button>'}<button class="btnp edit-only" id="acccopy">Copy to…</button>` : ""}${mode === "person" && can ? '<button class="btnt edit-only" id="accreset">↻ Reset to default</button>' : ""}</div>
      <div class="seatcard" style="cursor:default">${who}</div>${s && s.role === "owner" && mode === "person" ? '<div class="hint">The app owner can do everything, whatever the designation. Call recording stays off unless the person turns it on in his own phone.</div>' : ""}
      <div class="mh2" style="margin-top:12px">IN THE APP</div>
      ${RB.APP_SW.map(([k, t, sub]) => `<div class="swrow"><div class="w"><b>${t}</b>${sub ? `<span>${sub}</span>` : ""}</div>${stag("app." + k)}${sw(k)}</div>`).join("")}</div>`;
    const lv = (k) => ["none", "view", "edit"].map((v) => `<button data-lvl="${k}:${v}" class="${e.portal[k] === v ? "on" : ""}">${v[0].toUpperCase() + v.slice(1)}</button>`).join("");
    const mid = `<div class="card" style="flex:1"><div class="mh2">IN THE PORTAL</div>${RB.SECTIONS.map(([k, l]) => `<div class="swrow"><div class="w"><b>${l}</b></div>${stag("portal." + k)}<div class="lvl ${can ? "" : "dis"}" style="${can ? "" : "pointer-events:none;opacity:.6"}">${lv(k)}</div></div>`).join("")}
      <div class="hint">Portal “None” for every section = he can't sign in to the portal at all; the app still works. Meetings “View” shows only the minutes he was in and the actions on him.</div></div>`;
    const dl = RB.desigList().slice().sort((a, b) => a.name.localeCompare(b.name)).map((d) => `<div class="swrow ${mode === "desig" && S.accDesig === d.name ? "sel" : ""}"><div class="w"><b>${esc(d.name)}</b></div><span class="sub">${RB.countOn(d.name)} of 9</span>${ed() ? `<button class="link" data-dacc="${esc(d.name)}">edit ›</button>` : ""}</div>`).join("");
    const side = `<div class="card"><h3>Designation defaults</h3>${dl}${ed() ? '<button class="link" id="adddes2" style="margin-top:8px">+ New designation</button>' : ""}</div>
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
      saveDesigLazy(list, "Default for " + S.accDesig + ": portal " + k + " " + v);
    } else {
      const s = RB.seat(S.accSeat), ov = JSON.parse(JSON.stringify(s.override || {})); ov.portal = ov.portal || {}; ov.portal[k] = v;
      if (RB.defaultsOf(s.designation).portal[k] === v) delete ov.portal[k];
      s.override = ov; saveOverride(s, RB.holderName(s) + ": portal " + k + " " + v);
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
        const list = RB.desigList().map((d) => (pick.has(d.name) ? Object.assign({}, d, { app: Object.assign({}, src.app || {}), portal: Object.assign({}, src.portal || {}), checkedAt: now }) : d.name === from ? Object.assign({}, d, { checkedAt: d.checkedAt || now }) : d));
        await saveDesig(list, "Copied " + from + "'s access to " + names.join(", "));
      });
    };
    draw(""); count(); m.querySelector("#cpq").focus();
  }

  // ------------------------------------------------------------------ waiting
  function waiting() {
    const w = RB.waiting();
    const html = `<div class="card" style="max-width:760px"><h3>Waiting to join</h3><div class="hint">These Gmails signed in to the phone app and tapped “Join Rafiq Builders” but have no seat yet.</div>
      ${w.map((m) => `<div class="docrow"><span class="avs">${esc(first(m.name)[0] || "?")}</span><div class="w"><b>${esc(m.name || m.email || "Unknown")}</b><span>${esc(m.email || "")}</span></div>${ed() ? `<button class="btnp edit-only" data-approve="${esc(m.uid)}">Approve</button><button class="btnt edit-only" data-reject="${esc(m.uid)}">Reject</button>` : ""}</div>`).join("") || '<div class="muted" style="padding:10px 0">Nobody is waiting.</div>'}</div>`;
    return { html };
  }
  function approve(uid) {
    const m = RB.member(uid), empties = D.seats.filter((s) => s.status === "empty");
    const heads = [["", "— none (top) —"]].concat(D.seats.map((s) => [s.id, RB.holderName(s) === "Empty seat" ? s.gmail : RB.holderName(s)]));
    RB.form("Approve " + (m.name || "request"), [
      { k: "seat", label: "SEAT", type: "select", value: "new", options: [["new", "New seat for " + (m.email || "this Gmail")]].concat(empties.map((s) => [s.id, s.gmail + " · " + s.designation])) },
      { k: "designation", label: "DESIGNATION (new seat)", type: "select", value: RB.desigList()[0].name, options: RB.desigList().map((d) => d.name) },
      { k: "dept", label: "DEPARTMENT / SITE (new seat)", value: "" },
      { k: "reportsTo", label: "REPORTS TO (new seat)", type: "select", value: "", options: heads },
    ], "Approve", async (v) => {
      let id = v.seat;
      if (id === "new") {
        id = RB.gkey(m.email || ""); if (!gmailOk(id)) return "This request has no Gmail, so a new seat cannot be made.";
        if (RB.seat(id)) return "That Gmail already has a seat. Choose it from the list.";
        await RB.Store.put("seats", id, { gmail: id, designation: v.designation, dept: v.dept.trim(), reportsTo: v.reportsTo, role: "member", status: "empty", uid: "", name: "", extraGivers: [], override: {}, holders: [] });
      }
      await RB.Store.put("members", uid, { status: "active", seatId: id }, true);
      await fillFrom(RB.member(uid), RB.seat(id));
      await done("Approved " + (m.name || m.email) + " into seat " + id);
    });
  }

  /** A member whose Gmail has a seat takes the seat; caretaker tasks that belong to the seat go back to him (unless kept). */
  async function fillFrom(m, s) {
    if (!m || !s || s.status === "filled") return;
    const name = m.name || s.name || s.gmail;
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
    if (!todo.length) return;
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

  // ------------------------------------------------------------------ section object
  RB.sections.team = {
    tabs: () => [["people", "People"], ["chart", "Chart"], ["desig", "Designations"], ["access", "Access"], ["waiting", "Waiting" + (RB.waiting().length ? " (" + RB.waiting().length + ")" : "")]],
    render(tab) {
      RB.reconcile();
      const p = tab === "chart" ? chart() : tab === "desig" ? desigs() : tab === "access" ? access() : tab === "waiting" ? waiting() : people();
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
      const ad = root.querySelector("#addseat"); if (ad) ad.onclick = () => addSeat();
      const mg = root.querySelector("#migrate"); if (mg) mg.onclick = () => { mg.disabled = true; migrate().then(RB.render); };
      both("[data-fill]", (e) => fillSeat(e.dataset.fill));
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
      const setZoom = (z) => { S.chartZoom = Math.max(0.3, Math.min(1.6, Math.round(z * 100) / 100)); if (tree) tree.style.zoom = S.chartZoom; const p = root.querySelector("#zpct"); if (p) p.textContent = Math.round(S.chartZoom * 100) + "%"; };
      on(root, "[data-zoom]", (e) => {
        const k = e.dataset.zoom, z = S.chartZoom || 1;
        if (k === "in") setZoom(z + 0.1); else if (k === "out") setZoom(z - 0.1); else if (k === "100") setZoom(1);
        else if (cb && tree) { tree.style.zoom = 1; const w = tree.scrollWidth, h = tree.scrollHeight; const room = Math.min((cb.clientWidth - 12) / w, (innerHeight - cb.getBoundingClientRect().top - 30) / h); setZoom(Math.min(1, room)); cb.scrollLeft = 0; }
      });
      root.querySelectorAll("[data-node]").forEach((n) => {
        n.onclick = () => { if (!n.dataset.node) return; chartPop(n.dataset.node, n); };
        n.ondragstart = (ev) => { ev.dataTransfer.setData("text/plain", n.dataset.node); n.classList.add("drag"); if (tree) tree.classList.add("dragging"); };
        n.ondragend = () => { n.classList.remove("drag"); if (tree) tree.classList.remove("dragging"); };
        n.ondragover = (ev) => { ev.preventDefault(); n.classList.add("over"); };
        n.ondragleave = () => n.classList.remove("over");
        n.ondrop = (ev) => {
          ev.preventDefault(); n.classList.remove("over"); if (tree) tree.classList.remove("dragging");
          const id = ev.dataTransfer.getData("text/plain"); if (!id || !ed() || id === n.dataset.node) return;
          const s = RB.seat(id);
          if (!n.dataset.node && s && (!s.reportsTo || !RB.seat(s.reportsTo))) return RB.toast(RB.holderName(s) + " is already at the top");
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
      both("[data-psort]", (e) => { S.psort = e.dataset.psort; RB.render(); });
      both("[data-ddel]", (e) => RB.confirm("Delete the designation “" + esc(e.dataset.ddel) + "”?", "Delete", () => saveDesig(RB.desigList().filter((x) => x.name !== e.dataset.ddel), "Deleted designation " + e.dataset.ddel), true));
      both("[data-dacc]", (e) => { S.accDesig = e.dataset.dacc; S.accMode = "desig"; S.tab.team = "access"; RB.render(); });
      // access
      const accp = root.querySelector("#accp"); if (accp) accp.onchange = () => { S.accSeat = accp.value; RB.render(); };
      const ab = root.querySelector("#accback"); if (ab) ab.onclick = () => { S.accMode = "person"; RB.render(); };
      on(root, "[data-sw]", (e) => { if (!ed() || e.classList.contains("dis")) return; setSwitch(e.dataset.sw, !e.classList.contains("on")).then(RB.render); });
      on(root, "[data-lvl]", (e) => { if (!ed()) return; const [k, v] = e.dataset.lvl.split(":"); setLevel(k, v).then(RB.render); });
      on(root, "[data-resetone]", (e) => { const [grp, k] = e.dataset.resetone.split("."); const s = RB.seat(S.accSeat), ov = JSON.parse(JSON.stringify(s.override || {})); if (ov[grp]) delete ov[grp][k]; s.override = ov; saveOverride(s, RB.holderName(s) + ": " + k + " back to default"); RB.render(); });
      const rs = root.querySelector("#accreset"); if (rs) rs.onclick = () => RB.confirm("Reset all of " + esc(RB.holderName(RB.seat(S.accSeat))) + "'s access to the " + esc(RB.seat(S.accSeat).designation) + " defaults?", "Reset", async () => { const s = RB.seat(S.accSeat); s.override = {}; saveOverride(s, RB.holderName(s) + ": access reset to default"); });
      // waiting
      on(root, "[data-approve]", (e) => approve(e.dataset.approve));
      on(root, "[data-reject]", (e) => RB.confirm("Reject this request? The person can ask again.", "Reject", async () => { const m = RB.member(e.dataset.reject); await RB.Store.del("members", e.dataset.reject); await RB.log("Rejected join request from " + (m.name || m.email)); }, true));
    },
  };
  RB.team = { addSeat, offboard, moveSeat, migrate };
})();
