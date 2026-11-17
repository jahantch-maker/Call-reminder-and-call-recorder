/* Plot files: the daily work on plot files (Files list, file page Steps / Items / Cheques / Timeline).
 * Own module: this file and plotfiles.css. Data: teams/{t}/plotfiles (the file), teams/{t}/plotmoney (cheques and amounts: only the owner
 * and people with "See money details"), config/plotsteps (only the on/off switch; holidays are in Settings > Holidays).
 * Every rule (steps, roles, days, who to ask, pause, extend, forms, RG-03 items) comes ONLY from the SOP imported in Docs > SOPs (plotsync.js).
 * Nothing of the SOP is in the code: with no SOP imported there are no steps and no new file can be opened.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc, first = RB.first;
  const { fmtShort, fmtDay, fmtWhen } = RB;
  const DAY = 86400000;
  const STEPS = window.PLOT_STEPS || [], STAGES = window.PLOT_STAGES || {};
  Object.assign(S, { pfSel: null, pfFilter: "open", pfTab: "steps", pfFiles: "files", pfAs: "" });
  D.plot = D.plot || []; D.plotMoney = D.plotMoney || {}; D.plotCfg = D.plotCfg || null;

  const ed = () => RB.canEdit("plot");
  RB.plotOn = () => !D.plotCfg || D.plotCfg.enabled !== false;
  /** "See money details": owners always; everyone else only when the owner ticked it for that person (Team > Access). */
  RB.canMoney = () => {
    if (RB.isOwner()) return true;
    const s = RB.seatOfUid(RB.uidOf());
    return !!(s ? RB.effective(s).app.seeMoney : D.me && D.me.perms && D.me.perms.app && D.me.perms.app.seeMoney);
  };
  const stepDef = (id) => STEPS.find((s) => s.id === id);
  const daysOf = (id) => { const d = stepDef(id); return d ? d.days : null; };   // rules come only from the imported SOP
  const askOf = (id) => { const d = stepDef(id); return d ? d.ask : ""; };
  // Holidays live in Settings > Holidays (config/holidays) and every SOP reads them. Old dates in config/plotsteps still count.
  const holidays = () => ((D.holidays && D.holidays.dates) || []).map((h) => h.d).concat((D.plotCfg && D.plotCfg.holidays) || []);
  const offDays = () => (D.holidays && Array.isArray(D.holidays.offDays) ? D.holidays.offDays : [5]);

  // ------------------------------------------------------------------ working days: the weekly off day (Friday) and listed holidays are skipped
  const dkey = (t) => { const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
  const isWork = (t) => !offDays().includes(new Date(t).getDay()) && !holidays().includes(dkey(t));
  /** Whole working days from a to b (not counting the day it started). Time spent Waiting for plot owner is taken off first. */
  function workDays(a, b) {
    let n = 0; const d = new Date(a); d.setHours(12, 0, 0, 0);
    const end = new Date(b); end.setHours(12, 0, 0, 0);
    while (d < end) { d.setDate(d.getDate() + 1); if (isWork(d.getTime())) n++; }
    return n;
  }
  const dayNo = (st, now) => { if (!st || !st.startedAt) return 0; const upto = st.waitingSince || now; return workDays(st.startedAt, upto - (st.waitedMs || 0)) + 1; };

  // ------------------------------------------------------------------ file helpers
  const orderIdx = (id) => STEPS.findIndex((s) => s.id === id);
  const stepSt = (f, id) => (f.steps || {})[id] || {};
  function curSteps(f) { return STEPS.filter((s) => stepSt(f, s.id).status === "doing" || stepSt(f, s.id).status === "waiting"); }
  const isWaiting = (f) => !!(f.waiting && f.waiting.stepId);
  function isLate(f, now) { return !f.closed && curSteps(f).some((s) => { const y = daysOf(s.id), st = stepSt(f, s.id); return y && st.status === "doing" && dayNo(st, now) > (st.days || y); }); }
  const notReceived = (f) => (f.items || []).filter((i) => i.status === "none" || i.status === "rejected").length;
  const stageOf = (f) => { const c = curSteps(f)[0]; return c ? c.stage : f.stage || 1; };
  const sortKey = (f, now) => (isLate(f, now) ? 0 : isWaiting(f) ? 1 : 2);
  const ITEM_ST = { none: ["Not received", ""], received: ["Received", "blu"], verified: ["Verified", "grn"], rejected: ["Rejected", "red"], waived: ["Waived", "yel"] };

  async function save(f, patch, line) {
    const data = Object.assign({}, patch);
    if (line) data.events = (f.events || []).concat({ at: Date.now(), by: RB.uidOf(), text: line });
    await RB.Store.put("plotfiles", f.id, data, true);
    if (line) await RB.log("Plot " + f.plot + ": " + line);
    RB.render();
  }
  function startStep(f, id, now) { const d = stepDef(id); return { status: "doing", startedAt: now, days: daysOf(id) || null, role: d.role }; }
  /** Done on web (backup for managers and the owner; staff do it on the phone). Starts the next step; a letter step (1.05c) runs alongside its number. */
  async function doneStep(f, id) {
    const now = Date.now(), steps = Object.assign({}, f.steps || {}), d = stepDef(id);
    steps[id] = Object.assign({}, steps[id], { status: "done", doneAt: now, doneBy: RB.uidOf() });
    const i = orderIdx(id), nx = STEPS[i + 1];
    let line = d.id + " · " + d.title + " done" + (f.test && S.pfAs ? " (acting as " + S.pfAs + ")" : "");
    if (nx && !(steps[nx.id] || {}).status) { steps[nx.id] = startStep(f, nx.id, now); line += " · next: " + nx.title; }
    const more = STEPS[i + 2];
    if (nx && more && more.id.replace(/[a-z]$/, "") === nx.id.replace(/[a-z]$/, "") && /[a-z]$/.test(more.id) && !(steps[more.id] || {}).status) steps[more.id] = startStep(f, more.id, now);
    const anyOpen = STEPS.some((s) => (steps[s.id] || {}).status === "doing" || (steps[s.id] || {}).status === "waiting");
    const patch = { steps, stage: (nx || d).stage }; if (!anyOpen) patch.closed = true;
    if (f.waiting && f.waiting.stepId === id) patch.waiting = null;
    await save(f, patch, line);
  }
  const WAIT_STEPS = ["1.22", "6.01", "8.06", "4.02", "7.03"];   // used only until a version with the sheet's "Can the clock pause" column is published
  const canWait = (id) => { const s = STEPS.find((x) => x.id === id); return s && s.canPause ? /^y/i.test(s.canPause) : WAIT_STEPS.includes(id); };
  const myDes = () => (RB.D.me && RB.D.me.designation) || ((RB.seatOfUid && RB.seatOfUid(RB.uidOf())) || {}).designation || "";
  const mayExtend = (s) => RB.isOwner() || (s.extend || []).includes(myDes());
  /** Extend the time limit of the running step: only who the sheet names, a typed reason is required, open steps keep the new limit only for this file. */
  function extendStep(f, id) {
    const s = STEPS.find((x) => x.id === id), st0 = stepSt(f, id);
    RB.form("Extend time · " + id, [{ k: "days", label: "EXTRA WORKING DAYS", ph: "1" }, { k: "reason", label: "REASON (required)" }], "Extend", async (v) => {
      const n = Math.floor(Number(v.days)); if (!(n >= 1 && n <= 30)) return "Type 1 to 30 days.";
      if (!(v.reason || "").trim()) return "A reason is required.";
      const steps = Object.assign({}, f.steps || {}), st = Object.assign({}, st0); st.days = (st.days || daysOf(id) || 0) + n; steps[id] = st;
      await save(f, { steps }, "Time extended by " + n + " working day" + (n > 1 ? "s" : "") + " · " + id + " · " + v.reason.trim());
    });
  }
  async function waitStep(f, id, on) {
    const now = Date.now(), steps = Object.assign({}, f.steps || {}), st = Object.assign({}, steps[id]);
    if (on) { st.status = "waiting"; st.waitingSince = now; steps[id] = st; await save(f, { steps, waiting: { stepId: id, since: now } }, "Waiting for plot owner · " + id); }
    else { st.status = "doing"; st.waitedMs = (st.waitedMs || 0) + (now - (st.waitingSince || now)); st.waitingSince = 0; steps[id] = st; await save(f, { steps, waiting: null }, "Plot owner is back · " + id); }
  }

  // ------------------------------------------------------------------ Files list
  function filesPage() {
    const now = Date.now(), q = (S.q || "").toLowerCase();
    const all = D.plot.filter((f) => (!f.test || RB.isOwner()) && (!q || (f.plot + " " + (f.owner || "")).toLowerCase().includes(q)));
    const open = all.filter((f) => !f.closed), late = open.filter((f) => isLate(f, now)), wait = open.filter(isWaiting), closed = all.filter((f) => f.closed);
    const list = S.pfFilter === "late" ? late : S.pfFilter === "wait" ? wait.slice().sort((a, b) => a.waiting.since - b.waiting.since) : S.pfFilter === "closed" ? closed : open;
    list.sort((a, b) => sortKey(a, now) - sortKey(b, now) || b.openedAt - a.openedAt);
    const chip = (k, n, l, tone) => `<button class="pfchip ${tone} ${S.pfFilter === k ? "on" : ""}" data-pff="${k}"><b>${n}</b><span>${l}</span></button>`;
    const row = (f) => {
      const cs = curSteps(f), c = cs[0], st = c ? stepSt(f, c.id) : {}, y = c && (st.days || daysOf(c.id)), dn = c ? dayNo(st, now) : 0, nr = notReceived(f);
      const stage = STAGES[stageOf(f)] || "";
      return `<div class="pfrow" data-pf="${esc(f.id)}"><div class="pfm"><b>Plot ${esc(f.plot)} · ${esc(f.owner || "")}${f.test ? ' <em class="tag yel">TEST</em>' : ""}</b><span>${f.closed ? "Closed" : "Stage " + stageOf(f) + " · " + esc(stage)}</span>
        ${c ? `<span class="pfcur">${esc(c.title)} · ${esc(c.role)}</span>` : ""}
        <span class="pfchips">${isLate(f, now) ? '<em class="tag red">Late</em>' : ""}${isWaiting(f) ? '<em class="tag yel">Waiting for plot owner</em>' : ""}${nr ? `<em class="tag">${nr} documents not received</em>` : ""}</span></div>
        <div class="pfr">${S.pfFilter === "wait" && isWaiting(f) ? `<span class="pfday">Waiting ${workDays(f.waiting.since, now)} working days</span><span class="pfwb">${f.phone1 ? `<a class="btnt" href="tel:${esc(f.phone1.replace(/[^\d+]/g, ""))}" data-nogo="1">Call plot owner</a>` : ""}${ed() && !f.test ? `<button class="btnt" data-pfback="${esc(f.id)}">Plot owner is back</button>` : ""}</span>` : ""}${y && !f.closed && !(S.pfFilter === "wait" && isWaiting(f)) ? `<span class="pfday ${isLate(f, now) ? "late" : ""}">Day ${dn} of ${y}</span>` : ""}</div></div>`;
    };
    const html = `<div class="card pfcard"><div class="subhead"><h3>Plot files</h3><div class="sp"></div>${RB.isOwner() && D.plot.some((f) => f.test) ? '<button class="btnt edit-only" id="pf-deltest">Delete all test files</button>' : ""}${ed() ? '<button class="btnp edit-only" id="pf-new">+ Open plot file</button>' : ""}</div>
      ${sopReady() ? "" : '<div class="hint warn" style="margin-bottom:10px">The SOP is not imported yet. Import it in Docs &gt; SOPs. New plot files can only be opened after that.</div>'}<div class="pfchips2">${chip("open", open.length, "Open files", "blu")}${chip("late", late.length, "Late", "red")}${chip("wait", wait.length, "Waiting for plot owner", "yel")}${chip("closed", closed.length, "Closed", "")}</div>
      ${list.map(row).join("") || '<div class="muted" style="padding:14px 0">No plot files here.</div>'}</div>`;
    return { html };
  }

  // ------------------------------------------------------------------ one file
  function filePage(f) {
    const now = Date.now(), money = RB.canMoney();
    if (!money && S.pfTab === "cheques") S.pfTab = "steps";
    const tabs = [["steps", "Steps"], ["items", "Items"]].concat(money ? [["cheques", "Cheques"]] : []).concat([["timeline", "Timeline"]]);
    const hd = `<div class="pfback"><button class="link" id="pf-back">← All files</button></div>
      <div class="card pfhead"><h2>Plot ${esc(f.plot)} · ${esc(f.owner || "")}${f.test ? ' <em class="tag yel" style="vertical-align:middle">TEST</em>' : ""}</h2><div class="sub">${f.closed ? "Closed" : "Stage " + stageOf(f) + " · " + esc(STAGES[stageOf(f)] || "")} · opened ${fmtShort(f.openedAt)}</div>
        <div class="pfflds"><div><span>Phone 1</span><b>${esc(f.phone1 || "—")}</b></div><div><span>Phone 2</span><b>${esc(f.phone2 || "—")}</b></div><div><span>CNIC</span><b>${esc(f.cnic || "—")}</b></div><div><span>Size</span><b>${esc(f.size || "—")}</b></div><div class="wide"><span>Address</span><b>${esc(f.address || "—")}</b></div></div>
        ${f.test && RB.isOwner() ? `<div class="hint" style="margin:0 0 10px"><b>Test file.</b> Pick a role to act as. Then the Done and Waiting buttons work for that role's steps, as if that person did it. <select id="pf-as"><option value="">— pick a role —</option>${Array.from(new Set(STEPS.map((x) => x.role))).map((r) => `<option ${r === S.pfAs ? "selected" : ""}>${esc(r)}</option>`).join("")}</select></div>` : ""}
        <div class="pfdrive">${f.driveInternal ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(f.driveInternal)}">Google Drive: Internal</a>` : ""}${f.driveArchitect ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(f.driveArchitect)}">Google Drive: For architect</a>` : ""}${ed() ? '<button class="btnt edit-only" id="pf-edit">Edit details</button>' : ""}</div></div>
      <div class="pfbar"><div class="pfbt"><b>Plot ${esc(f.plot)} · ${esc(f.owner || "")}</b><span>${f.closed ? "Closed" : "Stage " + stageOf(f) + " · " + esc(STAGES[stageOf(f)] || "")}</span></div>
        <div class="tabs pftabs">${tabs.map(([k, l]) => `<button data-pft="${k}" class="${S.pfTab === k ? "on" : ""}">${l}</button>`).join("")}</div></div>`;
    let body = "";
    if (S.pfTab === "steps") body = stepsTab(f, now); else if (S.pfTab === "items") body = itemsTab(f); else if (S.pfTab === "cheques") body = chequesTab(f); else body = timelineTab(f);
    return { html: hd + `<div class="card pfcard">${body}</div>` };
  }
  function stepsTab(f, now) {
    const sopOf = (id) => stepDef(id);
    let out = "";
    for (let g = 1; g <= 8; g++) {
      const ss = STEPS.filter((s) => s.stage === g);
      const done = ss.filter((s) => stepSt(f, s.id).status === "done").length, started = ss.some((s) => stepSt(f, s.id).status);
      if (!started) { out += `<div class="pfstage later"><span class="pfn">${g}</span><b>${esc(STAGES[g])}</b><span class="sub">${ss.length} steps</span></div>`; continue; }
      out += `<div class="pfgrp"><div class="mh2 pfsh">STAGE ${g} · ${esc((STAGES[g] || "").toUpperCase())} · ${done} of ${ss.length} done</div>`;
      out += ss.map((s) => {
        const st = stepSt(f, s.id), y = st.days || daysOf(s.id), open = S.pfOpen === f.id + s.id, status = st.status || "next";
        const ic = status === "done" ? '<span class="pfic done">✓</span>' : status === "doing" ? '<span class="pfic cur">▶</span>' : status === "waiting" ? '<span class="pfic wait">⏳</span>' : '<span class="pfic">·</span>';
        const right = status === "done" ? fmtShort(st.doneAt) : status === "doing" && y ? `<span class="pfday ${dayNo(st, now) > y ? "late" : ""}">Day ${dayNo(st, now)} of ${y}</span>` : status === "waiting" ? '<span class="pfday">Paused</span>' : status === "next" ? "Next" : "";
        const fl = sopOf(s.id).forms || [], forms = fl.map((x) => (RB.plotSop && RB.plotSop.has() ? RB.plotSop.chip(x) : `<span class="tag blu">${esc(x)}</span>`)).join(" ");
        const detail = open ? `<div class="pfdet">${RB.plotSop && RB.plotSop.has() ? `<button class="btnt" data-how="${esc(s.id)}">How to do this</button>` : esc((s.what || []).join(" "))}<div class="sub" style="margin-top:6px">${s.limit ? "Time limit: " + esc(s.limit) + ". " : ""}Who to ask: ${esc(askOf(s.id) || "—")}.${s.cc ? " Notice to: " + esc(s.cc) + "." : ""}</div>${forms ? `<div style="margin-top:6px">${forms}</div>` : ""}
          ${ed() && (status === "doing" || status === "waiting") && (!f.test || (S.pfAs && s.role === S.pfAs)) ? `<div class="pfact"><button class="btnp" data-done="${esc(s.id)}">Done</button>${status === "doing" && y && mayExtend(s) ? `<button class="btnt" data-ext="${esc(s.id)}">Extend time</button>` : ""}${status === "waiting" ? `<button class="btnt" data-back="${esc(s.id)}">Plot owner is back</button>` : canWait(s.id) ? `<button class="btnt" data-wait="${esc(s.id)}">Waiting for plot owner</button>` : ""}</div><div class="foot">${f.test ? "Test file: acting as " + esc(S.pfAs) + "." : "Done is a backup here. Staff do it on their phone."}</div>` : ed() && f.test && (status === "doing" || status === "waiting") ? `<div class="foot">Test file: pick \"${esc(s.role)}\" in Act as to do this step.</div>` : ""}</div>` : "";
        return `<div class="pfstep ${status}"><div class="pfsr" data-open="${esc(s.id)}">${ic}<div class="w"><b>${esc(s.title)}</b><span>${esc(s.id)} · ${esc(s.role)}${status === "done" ? " · done " + fmtShort(st.doneAt) : st.startedAt ? " · started " + fmtShort(st.startedAt) : ""}</span></div><span class="pfrt">${right}</span></div>${detail}</div>`;
      }).join("");
      out += "</div>";
    }
    return out || '<div class="muted">No steps yet.</div>';
  }
  function itemsTab(f) {
    const items = f.items || [], pr = `<div class="subhead"><h3>Items</h3><div class="sp"></div><button class="btnt" id="pf-print">Print RG-03</button></div>`;
    if (!items.length) return pr + '<div class="muted">No items yet.</div>';
    return pr + itemsList(f, items);
  }
  function itemsList(f, items) {
    const groups = Array.from(new Set(items.map((i) => i.stage || i.group || "Items")));
    return groups.map((g) => `<div class="mh2">${esc(g.toUpperCase())}</div>${items.filter((i) => (i.stage || i.group || "Items") === g).map((i) => {
      const t = ITEM_ST[i.status || "none"];
      return `<div class="pfitem"><div class="w"><b>${esc(i.name)}</b><span>${esc(i.note || (i.at ? fmtShort(i.at) : "Plot owner to bring"))}</span></div>${ed() ? `<select data-item="${esc(i.id)}">${Object.keys(ITEM_ST).map((k) => `<option value="${k}" ${k === (i.status || "none") ? "selected" : ""}>${ITEM_ST[k][0]}</option>`).join("")}</select>` : `<span class="tag ${t[1]}">${t[0]}</span>`}</div>`;
    }).join("")}`).join("") || '<div class="muted">No items yet.</div>';
  }
  function chequesTab(f) {
    const m = D.plotMoney[f.id] || {}, ch = m.cheques || [];
    return `<div class="subhead"><h3>Cheques</h3><div class="sp"></div>${ed() ? '<button class="btnp edit-only" id="pf-chq">+ Add cheque</button>' : ""}</div>
      ${ch.map((c) => `<div class="pfitem"><div class="w"><b>${esc(c.milestone)}</b><span>${esc(c.bank || "")} ${esc(c.no || "")}${c.deadline ? " · deadline " + fmtShort(c.deadline) : ""}</span></div><b>Rs ${Number(c.amount || 0).toLocaleString("en-US")}</b><span class="tag ${c.status === "returned" ? "grn" : c.status === "deposited" ? "red" : "blu"}">${esc(c.status === "returned" ? "Returned" : c.status === "deposited" ? "Deposited" : "Held")}</span></div>`).join("") || '<div class="muted">No cheques yet.</div>'}
      ${m.pccDue ? `<div class="hint">Payment Clearance Certificate dues: Rs ${Number(m.pccDue).toLocaleString("en-US")}</div>` : ""}`;
  }
  function timelineTab(f) {
    const ev = (f.events || []).slice().sort((a, b) => b.at - a.at);
    return `${ed() ? '<div class="pfnote"><input id="pf-note" placeholder="Add a note to this file"><button class="btnp" id="pf-addnote">Add</button></div>' : ""}
      ${ev.map((e) => `<div class="pfev"><span class="sub">${fmtWhen(e.at)}${e.by && RB.member(e.by) ? " · " + esc(first(RB.member(e.by).name)) : ""}</span><div>${esc(e.text)}</div></div>`).join("") || '<div class="muted">Nothing yet.</div>'}`;
  }

  // ------------------------------------------------------------------ dialogs
  /** RG-03 rows of a new file: only from the imported SOP (Form items tab of the sheet). */
  function startItems() {
    return ((D.plotSop && D.plotSop.items) || []).map((x, i) => ({ id: "i" + i, group: "Documents checklist (RG-03)", stage: x.stage, no: x.no, name: x.name, status: "none" }));
  }
  const sopReady = () => !!(D.plotSop && D.plotSop.version && D.plotSop.steps && D.plotSop.steps.length);
  function newFileDialog() {
    if (!sopReady()) return RB.alertBox("The SOP has not been imported yet. Import it in Docs > SOPs first. Every new plot file follows the rules of that SOP.");
    RB.form("Open plot file", [{ k: "plot", label: "PLOT NUMBER" }, { k: "owner", label: "PLOT OWNER NAME" }, { k: "phone1", label: "PHONE 1" }, { k: "phone2", label: "PHONE 2 (optional)" }, { k: "cnic", label: "CNIC" }, { k: "size", label: "SIZE", ph: "10 marla" }, { k: "address", label: "ADDRESS" }, { k: "driveInternal", label: "GOOGLE DRIVE: INTERNAL (optional)", ph: "https://drive.google.com/…" }, { k: "driveArchitect", label: "GOOGLE DRIVE: FOR ARCHITECT (optional)", ph: "https://drive.google.com/…" }].concat(RB.isOwner() ? [{ k: "test", label: "TEST FILE?", type: "select", options: [["no", "No, a real plot file"], ["yes", "Yes, a test file (only you see it; delete it when done)"]], value: "no" }] : []), "Open file", async (v) => {
      if (!v.plot.trim() || !v.owner.trim()) return "Type the plot number and the plot owner's name.";
      if (D.plot.some((f) => !f.closed && f.plot.trim().toLowerCase() === v.plot.trim().toLowerCase())) return "An open file for plot " + v.plot.trim() + " already exists. Do not open a second one.";
      for (const k of ["driveInternal", "driveArchitect"]) if (v[k] && !/^https:\/\//.test(v[k].trim())) return "A Drive link must start with https://";
      const now = Date.now(), id = RB.id(), first1 = STEPS[0];
      const f = { plot: v.plot.trim(), owner: v.owner.trim(), phone1: v.phone1.trim(), phone2: v.phone2.trim(), cnic: v.cnic.trim(), size: v.size.trim(), address: v.address.trim(), driveInternal: v.driveInternal.trim(), driveArchitect: v.driveArchitect.trim(),
        openedAt: now, openedBy: RB.uidOf(), stage: 1, closed: false, waiting: null, steps: { [first1.id]: { status: "doing", startedAt: now, days: daysOf(first1.id) || null, role: first1.role } },
        items: startItems(), test: v.test === "yes", events: [{ at: now, by: RB.uidOf(), text: "Plot file opened" }] };
      await RB.Store.put("plotfiles", id, f); await RB.log("Opened plot file " + f.plot + " · " + f.owner); S.pfSel = id; S.pfTab = "steps";
    });
  }
  function editDialog(f) {
    RB.form("Edit details · plot " + f.plot, [{ k: "owner", label: "PLOT OWNER NAME", value: f.owner }, { k: "phone1", label: "PHONE 1", value: f.phone1 }, { k: "phone2", label: "PHONE 2", value: f.phone2 }, { k: "cnic", label: "CNIC", value: f.cnic }, { k: "size", label: "SIZE", value: f.size }, { k: "address", label: "ADDRESS", value: f.address }, { k: "driveInternal", label: "GOOGLE DRIVE: INTERNAL", value: f.driveInternal }, { k: "driveArchitect", label: "GOOGLE DRIVE: FOR ARCHITECT", value: f.driveArchitect }], "Save", async (v) => {
      for (const k of ["driveInternal", "driveArchitect"]) if (v[k] && !/^https:\/\//.test(v[k].trim())) return "A Drive link must start with https://";
      const patch = {}; Object.keys(v).forEach((k) => (patch[k] = v[k].trim()));
      await RB.Store.put("plotfiles", f.id, patch, true); await RB.log("Edited details of plot file " + f.plot);
    });
  }
  function chequeDialog(f) {
    RB.form("Add cheque · plot " + f.plot, [{ k: "milestone", label: "MILESTONE", ph: "Construction started" }, { k: "deadline", label: "DEADLINE", type: "date" }, { k: "bank", label: "BANK" }, { k: "no", label: "CHEQUE NUMBER" }, { k: "amount", label: "AMOUNT (Rs)", type: "number" }], "Add", async (v) => {
      if (!v.milestone.trim() || !(Number(v.amount) > 0)) return "Type the milestone and the amount.";
      const m = D.plotMoney[f.id] || {}, list = (m.cheques || []).concat({ id: RB.id(), milestone: v.milestone.trim(), deadline: v.deadline ? new Date(v.deadline + "T09:00").getTime() : 0, bank: v.bank.trim(), no: v.no.trim(), amount: Number(v.amount), status: "held" });
      await RB.Store.put("plotmoney", f.id, { cheques: list }, true); await RB.log("Cheque added to plot file " + f.plot);
    });
  }

  /** RG-03 on A4 in the locked v3 layout (plotforms.js), with this file's ticks. */
  function printRG03(f) {
    RB.plotForms.print(RB.plotForms.rg03(f, (f.items || []).filter((i) => /RG-03/.test(i.group || "")), Date.now()));
  }
  async function deleteTestFiles() {
    const t = D.plot.filter((f) => f.test);
    RB.confirm("Delete all " + t.length + " test file" + (t.length === 1 ? "" : "s") + "? This cannot be undone. Real plot files are not touched.", "Delete", async () => {
      for (const f of t) { await RB.Store.del("plotfiles", f.id); if (D.plotMoney[f.id]) await RB.Store.del("plotmoney", f.id); }
      await RB.log("Deleted " + t.length + " Plot files test file(s)"); S.pfSel = null; RB.render();
    }, true);
  }

  // ------------------------------------------------------------------ fixed bars
  /** File page: the slim "Plot · owner · stage" line joins the tabs bar only once the big header has scrolled away.
   * --pfbar tells the stage headings how far below the bar they stop. */
  let pfObs = null;
  function stickyBar(root) {
    if (pfObs) { pfObs.disconnect(); pfObs = null; }
    const bar = root.querySelector(".pfbar"), head = root.querySelector(".pfhead");
    if (!bar) return;
    const fit = () => root.style.setProperty("--pfbar", bar.offsetHeight + "px");
    fit();
    if (!head || !window.IntersectionObserver) return;
    pfObs = new IntersectionObserver(([e]) => { bar.classList.toggle("stuck", !e.isIntersecting); fit(); });
    pfObs.observe(head);
  }

  // ------------------------------------------------------------------ section
  RB.sections.plot = {
    tabs() { return [["files", "Files"]]; },
    render(tab) {
      if (RB.plotSync) RB.plotSync.apply();
      const f = S.pfSel && D.plot.find((x) => x.id === S.pfSel);
      let p;
      if (f) p = filePage(f); else { S.pfSel = null; p = filesPage(); }
      p.title = "Plot files"; p.sub = "From opening a plot file to possession · one file per plot";
      return p;
    },
    bind(root) {
      const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
      const cur = () => D.plot.find((x) => x.id === S.pfSel);
      root.querySelectorAll("[data-nogo]").forEach((e) => (e.onclick = (ev) => ev.stopPropagation()));
      root.querySelectorAll("[data-pfback]").forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); ev.stopPropagation(); const f = D.plot.find((x) => x.id === e.dataset.pfback); if (f && f.waiting) waitStep(f, f.waiting.stepId, false); }));
      on("[data-pff]", (e) => { S.pfFilter = e.dataset.pff; RB.render(); });
      on("[data-pf]", (e) => { S.pfSel = e.dataset.pf; S.pfTab = "steps"; RB.render(); });
      on("#pf-back", () => { S.pfSel = null; RB.render(); });
      on("#pf-new", newFileDialog);
      on("#pf-deltest", deleteTestFiles);
      on("#pf-print", () => printRG03(cur()));
      on("[data-ext]", (e) => extendStep(cur(), e.dataset.ext));
      on("[data-how]", (e) => RB.plotSop.how(e.dataset.how));
      const as = root.querySelector("#pf-as"); if (as) as.onchange = () => { S.pfAs = as.value; RB.render(); };
      on("#pf-edit", () => editDialog(cur()));
      on("#pf-chq", () => chequeDialog(cur()));
      on("[data-pft]", (e) => { S.pfTab = e.dataset.pft; RB.render(); });
      on("[data-open]", (e) => { const k = S.pfSel + e.dataset.open; S.pfOpen = S.pfOpen === k ? null : k; const c = document.getElementById("content"), t = c ? c.scrollTop : 0; RB.render(); const n = document.getElementById("content"); if (n) n.scrollTop = t; });
      on("[data-done]", (e) => { const f = cur(), s = stepDef(e.dataset.done); RB.confirm("Mark “" + esc(s.title) + "” done for plot " + esc(f.plot) + "? Staff normally do this on their phone.", "Done", () => doneStep(f, s.id)); });
      on("[data-wait]", (e) => waitStep(cur(), e.dataset.wait, true));
      on("[data-back]", (e) => waitStep(cur(), e.dataset.back, false));
      root.querySelectorAll("[data-item]").forEach((sel) => (sel.onchange = async () => { const f = cur(), items = (f.items || []).map((i) => (i.id === sel.dataset.item ? Object.assign({}, i, { status: sel.value, at: Date.now(), by: RB.uidOf() }) : i)); await save(f, { items }, (items.find((i) => i.id === sel.dataset.item) || {}).name + ": " + ITEM_ST[sel.value][0]); }));
      stickyBar(root);
      on("#pf-addnote", async () => { const v = document.getElementById("pf-note").value.trim(); if (!v) return; const f = cur(); await save(f, {}, v); });
    },
  };
})();
