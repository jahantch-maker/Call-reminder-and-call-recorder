/* Plot files: one file per plot, from opening it to possession and discount cheques (Phase 1 = stages 1-8 of the SOP register).
 * Own module: this file, plotsteps.js (the SOP register as defaults) and plotfiles.css. Data lives in teams/{t}/plotfiles (the file),
 * teams/{t}/plotmoney (cheques and amounts: only the owner and people with "See money details") and config/plotsteps (exceptions to
 * the register: days, who to ask). Switch the whole section off with config/plotsteps.enabled = false.
 * The register itself comes from plotsync.js: the published version of the Google Sheet, or the built-in plotsteps.js until the first publish.
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
  const cfgStep = (id) => ((D.plotCfg || {}).steps || {})[id] || {};
  const stepDef = (id) => STEPS.find((s) => s.id === id);
  const daysOf = (id) => { const c = cfgStep(id), d = stepDef(id); return c.days !== undefined && c.days !== "" ? c.days : d ? d.days : null; };
  const askOf = (id) => { const c = cfgStep(id), d = stepDef(id); return c.ask !== undefined ? c.ask : d ? d.ask : ""; };
  const holidays = () => (D.plotCfg && D.plotCfg.holidays) || [];

  // ------------------------------------------------------------------ working days: Fridays and listed holidays are skipped
  const dkey = (t) => { const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
  const isWork = (t) => new Date(t).getDay() !== 5 && !holidays().includes(dkey(t));
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
  const DEFAULT_ITEMS = [["Documents checklist (RG-03)", "Site Plan Requisition form"], ["Documents checklist (RG-03)", "CNIC copy of plot owner"], ["Documents checklist (RG-03)", "Possession form"], ["Documents checklist (RG-03)", "Registry photocopy"], ["Documents checklist (RG-03)", "100 stamp paper · Undertaking"], ["Documents checklist (RG-03)", "1200 stamp paper with discount cheque"], ["Documents checklist (RG-03)", "Director waiver / discount approval"]];

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
  const WAIT_STEPS = ["1.22", "6.01", "8.06", "4.02", "7.03"];
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
    const list = S.pfFilter === "late" ? late : S.pfFilter === "wait" ? wait : S.pfFilter === "closed" ? closed : open;
    list.sort((a, b) => sortKey(a, now) - sortKey(b, now) || b.openedAt - a.openedAt);
    const chip = (k, n, l, tone) => `<button class="pfchip ${tone} ${S.pfFilter === k ? "on" : ""}" data-pff="${k}"><b>${n}</b><span>${l}</span></button>`;
    const row = (f) => {
      const cs = curSteps(f), c = cs[0], st = c ? stepSt(f, c.id) : {}, y = c && (st.days || daysOf(c.id)), dn = c ? dayNo(st, now) : 0, nr = notReceived(f);
      const stage = STAGES[stageOf(f)] || "";
      return `<div class="pfrow" data-pf="${esc(f.id)}"><div class="pfm"><b>Plot ${esc(f.plot)} · ${esc(f.owner || "")}${f.test ? ' <em class="tag yel">TEST</em>' : ""}</b><span>${f.closed ? "Closed" : "Stage " + stageOf(f) + " · " + esc(stage)}</span>
        ${c ? `<span class="pfcur">${esc(c.title)} · ${esc(c.role)}</span>` : ""}
        <span class="pfchips">${isLate(f, now) ? '<em class="tag red">Late</em>' : ""}${isWaiting(f) ? '<em class="tag yel">Waiting for plot owner</em>' : ""}${nr ? `<em class="tag">${nr} documents not received</em>` : ""}</span></div>
        <div class="pfr">${y && !f.closed ? `<span class="pfday ${isLate(f, now) ? "late" : ""}">Day ${dn} of ${y}</span>` : ""}</div></div>`;
    };
    const html = `<div class="card pfcard"><div class="subhead"><h3>Plot files</h3><div class="sp"></div>${RB.isOwner() && D.plot.some((f) => f.test) ? '<button class="btnt edit-only" id="pf-deltest">Delete all test files</button>' : ""}${ed() ? '<button class="btnp edit-only" id="pf-new">+ Open plot file</button>' : ""}</div>
      <div class="pfchips2">${chip("open", open.length, "Open files", "blu")}${chip("late", late.length, "Late", "red")}${chip("wait", wait.length, "Waiting for plot owner", "yel")}${chip("closed", closed.length, "Closed", "")}</div>
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
        const forms = (sopOf(s.id).forms || []).map((x) => `<span class="tag blu">${esc(x)}</span>`).join(" ");
        const detail = open ? `<div class="pfdet">${esc((s.what || []).join(" "))}<div class="sub" style="margin-top:6px">${s.limit ? "Time limit: " + esc(s.limit) + ". " : ""}Who to ask: ${esc(askOf(s.id) || "—")}.${s.cc ? " Notice to: " + esc(s.cc) + "." : ""}</div>${forms ? `<div style="margin-top:6px">${forms}</div>` : ""}
          ${ed() && (status === "doing" || status === "waiting") && (!f.test || (S.pfAs && s.role === S.pfAs)) ? `<div class="pfact"><button class="btnp" data-done="${esc(s.id)}">Done</button>${status === "waiting" ? `<button class="btnt" data-back="${esc(s.id)}">Plot owner is back</button>` : WAIT_STEPS.includes(s.id) ? `<button class="btnt" data-wait="${esc(s.id)}">Waiting for plot owner</button>` : ""}</div><div class="foot">${f.test ? "Test file: acting as " + esc(S.pfAs) + "." : "Done is a backup here. Staff do it on their phone."}</div>` : ed() && f.test && (status === "doing" || status === "waiting") ? `<div class="foot">Test file: pick \"${esc(s.role)}\" in Act as to do this step.</div>` : ""}</div>` : "";
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

  // ------------------------------------------------------------------ Rules and timing (the register as defaults, his exceptions on top)
  function rulesPage() {
    const rows = STEPS.map((s) => {
      const c = cfgStep(s.id), ch = c.days !== undefined || c.ask !== undefined;
      return `<div class="pfrule"><div class="w"><b>${esc(s.id)} · ${esc(s.title)}</b><span>${esc(s.role)} · ${esc(STAGES[s.stage] || "")}</span></div><span class="pfk pfkd">${daysOf(s.id) ? daysOf(s.id) + " days" : esc(s.limit || "—")}</span><span class="pfk pfka">${esc(askOf(s.id) || "—")}</span>${ch ? '<span class="tag chg" data-rst="' + esc(s.id) + '" title="Back to the SOP default">✎ changed</span>' : '<span class="tag">default</span>'}${RB.isOwner() ? `<button class="btnt edit-only" data-rule="${esc(s.id)}">Change</button>` : ""}</div>`;
    }).join("");
    return { html: `<div class="card pfcard"><div class="subhead"><h3>Rules and timing</h3></div><div class="hint">Days and “Who to ask” come from the SOP register. Change one here only for an exception. A change applies to files that have not reached the step yet; steps already open keep their old limit. Fridays and holidays are skipped.</div>
      <div class="pfrule head"><span class="w">STEP</span><span class="pfk">DAYS</span><span class="pfk">WHO TO ASK</span><span class="pf3"></span><span class="pf4"></span></div>${rows}</div>` };
  }
  function ruleDialog(id) {
    const s = stepDef(id);
    RB.form(id + " · " + s.title, [{ k: "days", label: "DAYS (working days, empty = no day limit)", value: daysOf(id) == null ? "" : daysOf(id), type: "number" }, { k: "ask", label: "WHO TO ASK", value: askOf(id) }], "Save", async (v) => {
      const days = v.days === "" ? null : Number(v.days); if (days !== null && (!(days >= 0) || days > 60)) return "Days must be between 0 and 60.";
      const steps = Object.assign({}, (D.plotCfg || {}).steps || {}, { [id]: { days, ask: v.ask.trim() } });
      await RB.Store.put("config", "plotsteps", { steps }, true); await RB.log("Plot files rule " + id + ": " + (days === null ? "no day limit" : days + " days") + ", who to ask: " + v.ask.trim());
    }, "Applies to files that have not reached this step yet.");
  }

  // ------------------------------------------------------------------ dialogs
  /** RG-03 rows of a new file: from the published SOP (Form items tab of the sheet) when there is one, else the built-in list. */
  function startItems() {
    const sop = D.plotSop && D.plotSop.items;
    return sop && sop.length ? sop.map((x, i) => ({ id: "i" + i, group: "Documents checklist (RG-03)", stage: x.stage, no: x.no, name: x.name, status: "none" })) : DEFAULT_ITEMS.map((x, i) => ({ id: "i" + i, group: x[0], name: x[1], status: "none" }));
  }
  function newFileDialog() {
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

  /** RG-03 on A4 in the locked layout (sop/forms-revised/documents-checklist), with this file's ticks. */
  function printRG03(f) {
    const items = (f.items || []).filter((i) => /RG-03/.test(i.group || "")), gs = Array.from(new Set(items.map((i) => i.stage || i.group)));
    const ok = (i) => i.status === "verified" || i.status === "received";
    const body = gs.map((g) => `<tr class="g"><td colspan="4">${esc(String(g).toUpperCase())}</td></tr>` + items.filter((i) => (i.stage || i.group) === g).map((i, n) => `<tr><td class="c">${esc(String(i.no || n + 1))}</td><td>${esc(i.name)}</td><td class="c">${ok(i) ? "☑" : "☐"}</td><td>${ok(i) && i.at ? esc(fmtShort(i.at)) : ""}</td></tr>`).join("")).join("");
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>Documents Checklist</title><style>@page{size:A4;margin:10mm}body{font-family:Arial,sans-serif;font-size:10.5px;color:#111;margin:0}.hd{display:flex;justify-content:space-between;align-items:flex-end;border-bottom:2px solid #111;padding-bottom:5px}.org{font-weight:700;font-size:12px}.sub{font-size:10px;color:#555}h1{text-align:center;font-size:17px;margin:8px 0 6px;letter-spacing:1px}.top{display:flex;gap:20px;margin-bottom:8px;font-size:12px}.top span{flex:1;display:flex;gap:6px}.top b{flex:1;border-bottom:1px solid #444}table{width:100%;border-collapse:collapse}th,td{border:1px solid #888;padding:4px 6px;vertical-align:top}th{background:#e9e9e9;font-size:9.5px}.c{text-align:center}tr.g td{background:#f3f3f3;font-weight:700;font-size:9.5px;letter-spacing:.4px}.note{font-size:9px;color:#444;margin-top:6px}</style></head><body>
<div class="hd"><div><div class="org">RAFIQ BUILDERS &amp; DEVELOPERS · ROYAL GARDEN, GOJRA</div><div class="sub">Plot files · glue inside the front cover</div></div><div class="sub">Form RG-03 · printed ${esc(fmtShort(Date.now()))}</div></div><h1>DOCUMENTS CHECKLIST</h1>
<div class="top"><span>Plot No.<b>${esc(f.plot)}</b></span><span style="flex:2">Owner name<b>${esc(f.owner || "")}</b></span></div>
<table><thead><tr><th style="width:24px">#</th><th>Document</th><th style="width:60px">In file</th><th style="width:110px">Date</th></tr></thead><tbody>${body}</tbody></table>
<div class="note">Filled only by the Document Officer. The ticks and dates are from the app on the day of printing; full details are in the app.</div></body></html>`;
    const fr = document.createElement("iframe"); fr.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0"; document.body.appendChild(fr);
    fr.onload = () => { fr.contentWindow.focus(); fr.contentWindow.print(); setTimeout(() => fr.remove(), 4000); };
    fr.srcdoc = html;
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
    tabs() { return [["files", "Files"]].concat(RB.isOwner() ? [["rules", "Rules and timing"], ["sop", "SOP versions"]] : []); },
    render(tab) {
      if (RB.plotSync) RB.plotSync.apply();
      const f = S.pfSel && D.plot.find((x) => x.id === S.pfSel);
      let p;
      if (tab === "sop" && RB.isOwner() && RB.plotSync) p = RB.plotSync.render(); else if (tab === "rules" && RB.isOwner()) p = rulesPage(); else if (f) p = filePage(f); else { S.pfSel = null; p = filesPage(); }
      p.title = "Plot files"; p.sub = "From opening a plot file to possession · one file per plot";
      return p;
    },
    bind(root) {
      if ((S.tab || {}).plot === "sop" && RB.plotSync) return RB.plotSync.bind(root);
      const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
      const cur = () => D.plot.find((x) => x.id === S.pfSel);
      on("[data-pff]", (e) => { S.pfFilter = e.dataset.pff; RB.render(); });
      on("[data-pf]", (e) => { S.pfSel = e.dataset.pf; S.pfTab = "steps"; RB.render(); });
      on("#pf-back", () => { S.pfSel = null; RB.render(); });
      on("#pf-new", newFileDialog);
      on("#pf-deltest", deleteTestFiles);
      on("#pf-print", () => printRG03(cur()));
      const as = root.querySelector("#pf-as"); if (as) as.onchange = () => { S.pfAs = as.value; RB.render(); };
      on("#pf-edit", () => editDialog(cur()));
      on("#pf-chq", () => chequeDialog(cur()));
      on("[data-pft]", (e) => { S.pfTab = e.dataset.pft; RB.render(); });
      on("[data-open]", (e) => { const k = S.pfSel + e.dataset.open; S.pfOpen = S.pfOpen === k ? null : k; const c = document.getElementById("content"), t = c ? c.scrollTop : 0; RB.render(); const n = document.getElementById("content"); if (n) n.scrollTop = t; });
      on("[data-done]", (e) => { const f = cur(), s = stepDef(e.dataset.done); RB.confirm("Mark “" + esc(s.title) + "” done for plot " + esc(f.plot) + "? Staff normally do this on their phone.", "Done", () => doneStep(f, s.id)); });
      on("[data-wait]", (e) => waitStep(cur(), e.dataset.wait, true));
      on("[data-back]", (e) => waitStep(cur(), e.dataset.back, false));
      on("[data-rule]", (e) => ruleDialog(e.dataset.rule));
      on("[data-rst]", (e) => RB.confirm("Go back to the SOP default for " + esc(e.dataset.rst) + "?", "Go back", async () => { const steps = Object.assign({}, (D.plotCfg || {}).steps || {}); delete steps[e.dataset.rst]; await RB.Store.putField("config", "plotsteps", "steps", steps); await RB.log("Plot files rule " + e.dataset.rst + " back to the SOP default"); }));
      root.querySelectorAll("[data-item]").forEach((sel) => (sel.onchange = async () => { const f = cur(), items = (f.items || []).map((i) => (i.id === sel.dataset.item ? Object.assign({}, i, { status: sel.value, at: Date.now(), by: RB.uidOf() }) : i)); await save(f, { items }, (items.find((i) => i.id === sel.dataset.item) || {}).name + ": " + ITEM_ST[sel.value][0]); }));
      stickyBar(root);
      on("#pf-addnote", async () => { const v = document.getElementById("pf-note").value.trim(); if (!v) return; const f = cur(); await save(f, {}, v); });
    },
  };
})();
