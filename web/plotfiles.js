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
        const detail = open ? `<div class="pfdet">${RB.plotSop && RB.plotSop.has() ? `<button class="btnt" data-how="${esc(s.id)}">How to do this</button>` : esc((s.what || []).join(" "))}<div class="sub" style="margin-top:6px">${s.limit ? "Time limit: " + esc(s.limit) + ". " : ""}Who to ask: ${esc(askOf(s.id) || "—")}.${s.cc ? " Notice to: " + esc(s.cc) + "." : ""}</div>${forms ? `<div style="margin-top:6px">${forms}</div>` : ""}${fl.includes("RG-14") ? `<div class="pfact"><button class="btnt" data-rg14="${esc(s.id)}">Print Still pending slip</button></div>` : ""}
          ${ed() && (status === "doing" || status === "waiting") && (!f.test || (S.pfAs && s.role === S.pfAs)) ? `<div class="pfact"><button class="btnp" data-done="${esc(s.id)}">Done</button>${status === "doing" && y && mayExtend(s) ? `<button class="btnt" data-ext="${esc(s.id)}">Extend time</button>` : ""}${status === "waiting" ? `<button class="btnt" data-back="${esc(s.id)}">Plot owner is back</button>` : canWait(s.id) ? `<button class="btnt" data-wait="${esc(s.id)}">Waiting for plot owner</button>` : ""}</div><div class="foot">${f.test ? "Test file: acting as " + esc(S.pfAs) + "." : "Done is a backup here. Staff do it on their phone."}</div>` : ed() && f.test && (status === "doing" || status === "waiting") ? `<div class="foot">Test file: pick \"${esc(s.role)}\" in Act as to do this step.</div>` : ""}</div>` : "";
        return `<div class="pfstep ${status}"><div class="pfsr" data-open="${esc(s.id)}">${ic}<div class="w"><b>${esc(s.title)}</b><span>${esc(s.id)} · ${esc(s.role)}${status === "done" ? " · done " + fmtShort(st.doneAt) : st.startedAt ? " · started " + fmtShort(st.startedAt) : ""}</span></div><span class="pfrt">${right}</span></div>${detail}</div>`;
      }).join("");
      out += "</div>";
    }
    return out || '<div class="muted">No steps yet.</div>';
  }
  function itemsTab(f) {
    const items = f.items || [], pr = `<div class="subhead"><h3>Items</h3><div class="sp"></div><button class="btnt" id="pf-print">Print RG-03</button></div>`;
    const r3 = items.filter((i) => i.group !== R5), r5 = items.filter((i) => i.group === R5), ok = approved(f) || r5.length;
    const p5 = RB.canMoney() ? `<button class="btnt" id="pf-print5" ${approved(f) ? "" : 'disabled title="Print opens after the Accounts Head approves" style="opacity:.45"'}>Print RG-05</button>` : "";
    const pend = r5.filter((i) => !["verified", "waived"].includes(i.status || "none")).length;
    const g5 = rg05Items().length ? `<div class="subhead" style="margin-top:16px"><div class="mh2" style="margin:0">${esc(R5.toUpperCase())}</div><div class="sp"></div>${p5}</div>
      ${ok ? itemsList(f, r5, true) + (pend ? `<div class="hint">Items the plot owner has not brought yet: ${pend}. Tap “Still pending slip” in step 2.07 to print it.</div>` : "") : '<div class="hint">The RG-05 rows appear here after the Accounts Officer adds the cheque lines (Cheques tab) and the Accounts Head approves them.</div>'}` : "";
    if (!r3.length && !g5) return pr + '<div class="muted">No items yet.</div>';
    return pr + itemsList(f, r3) + g5;
  }
  function itemsList(f, items, noHead) {
    const groups = Array.from(new Set(items.map((i) => i.stage || i.group || "Items")));
    return groups.map((g) => `${noHead ? "" : `<div class="mh2">${esc(g.toUpperCase())}</div>`}${items.filter((i) => (i.stage || i.group || "Items") === g).map((i) => {
      const t = ITEM_ST[i.status || "none"], l = i.lineId ? linesOf(f).find((y) => y.id === i.lineId) : null;
      const sub = (l && RB.canMoney() ? money(lineAmt(l)) + " · see Cheques tab · " : "") + (i.note || (i.at ? fmtShort(i.at) : "Plot owner to bring"));
      return `<div class="pfitem"><div class="w"><b>${esc(i.name)}</b><span>${esc(sub)}</span></div>${ed() ? `<select data-item="${esc(i.id)}">${Object.keys(ITEM_ST).map((k) => `<option value="${k}" ${k === (i.status || "none") ? "selected" : ""}>${ITEM_ST[k][0]}</option>`).join("")}</select>` : `<span class="tag ${t[1]}">${t[0]}</span>`}</div>`;
    }).join("")}`).join("") || '<div class="muted">No items yet.</div>';
  }
  // ------------------------------------------------------------------ RG-05 cheque lines (locked 17 Nov, option A of the cheque-lines mockup)
  // The Accounts Officer adds this plot's cheque lines on the Cheques tab. The kinds of lines and their columns come ONLY from the SOP's
  // RG-05 "Repeating" form items (sheet Form items tab). The Accounts Head approves (phone task later; owner/manager backup on web now).
  // Approved lines become RG-05 rows on the Items tab; Front Desk records each cheque against its line. Amounts live in plotmoney only.
  const R5 = "Required attachments (RG-05)";
  const sopX = () => (RB.plotSop && RB.plotSop.x ? RB.plotSop.x() : null);
  const rg05Items = () => { const x = sopX(); return ((x && x.formItems) || []).filter((i) => /^RG-05/.test(i.form || "")); };
  const kinds = () => rg05Items().filter((i) => /^repeat/i.test(i.type || ""));
  /** "Cheque date (date) · Discount condition (text) · Amount (amount)" -> [{name, kind}] */
  const colsOf = (k) => String((k && k.cols) || "").split("·").map((c) => c.trim()).filter(Boolean).map((c) => { const m = c.match(/^(.*?)\s*\((\w+)\)\s*$/); return m ? { name: m[1], kind: m[2].toLowerCase() } : { name: c, kind: "text" }; });
  const money = (n) => "Rs " + Number(n || 0).toLocaleString("en-US");
  const isManager = () => RB.isOwner() || /\b(GM|manager)\b/i.test((D.me && D.me.designation) || "") || /accounts head/i.test((D.me && D.me.designation) || "");
  const linesOf = (f) => ((D.plotMoney[f.id] || {}).lines || []);
  const approved = (f) => !!(D.plotMoney[f.id] || {}).linesOk;
  const lineDate = (l) => { const k = colsOf(kinds()[l.kind]).find((c) => c.kind === "date"); return k ? l.v[k.name] : ""; };
  const lineAmt = (l) => { const k = colsOf(kinds()[l.kind]).find((c) => c.kind === "amount"); return k ? Number(l.v[k.name] || 0) : 0; };
  const lineText = (l) => { const k = colsOf(kinds()[l.kind]).find((c) => c.kind === "text"); return k ? l.v[k.name] : ""; };
  const dshow = (d) => (d ? new Date(d + "T12:00:00").toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "");
  const lineTitle = (l, n) => lineText(l) || "Cheque " + n + (lineDate(l) ? " · dated " + dshow(lineDate(l)) : "");
  function chequesTab(f) {
    const m = D.plotMoney[f.id] || {}, ks = kinds(), ls = linesOf(f), ok = approved(f), canAdd = ed() && !ok;
    if (!ks.length) return `<div class="subhead"><h3>Cheques</h3></div><div class="muted">The SOP has no repeating RG-05 items, so there are no cheque lines to add.</div>`;
    const strip = ok ? `<div class="hint" style="background:#E3F3E6;color:#1E6B34"><b>Approved</b> by ${esc(m.linesOk.byName || "")}${m.linesOk.backup ? " (from portal)" : ""} · ${esc(fmtWhen(m.linesOk.at))}. Lines are locked; a change needs a new approval.</div>`
      : ls.length ? `<div class="hint" style="background:#FBEBC8;color:#8A5A00;display:flex;gap:10px;align-items:center;flex-wrap:wrap"><span style="flex:1;min-width:200px"><b>Waiting for the Accounts Head's approval.</b> RG-05 can be printed after approval.</span>${isManager() ? '<button class="btnt" id="pf-lok">Approve (backup)</button>' : ""}</div>` : "";
    const groups = ks.map((k, ki) => {
      const mine = ls.filter((l) => l.kind === ki); let n = 0;
      const rows = mine.map((l) => { n++; const r = l.rec, chk = r && r.check;
        const tagH = r ? `<span class="tag ${chk ? "red" : "blu"}">${chk ? "Check" : "Received"}</span>` : '<span class="tag">Expected</span>';
        const sub = r ? `${esc(r.bank)} · no. ${esc(r.no)} · received ${esc(fmtShort(r.at))}${chk ? ' · <b style="color:var(--er)">date or amount differs from the line</b>' : ""}` : lineText(l) ? "Expected · dated " + esc(dshow(lineDate(l))) : "Expected";
        const act = canAdd ? ` data-lnedit="${esc(l.id)}" style="cursor:pointer"` : "";
        return `<div class="pfitem"${act}><div class="w"><b>${esc(lineTitle(l, n))}</b><span>${sub}</span></div><b>${money(lineAmt(l))}</b>${tagH}${ok && !r && ed() ? `<button class="btnt" data-rec="${esc(l.id)}">Record cheque</button>` : ""}</div>`; }).join("");
      const tot = /^y/i.test(k.total || "") && mine.length ? `<div class="pfitem" style="border-bottom:0"><div class="w"><b style="color:var(--onv)">Total</b></div><b>${money(mine.reduce((a, l) => a + lineAmt(l), 0))}</b></div>` : "";
      return `<div class="mh2" style="margin-top:12px">${esc(String(k.item || "").toUpperCase())}</div>${rows || '<div class="muted" style="padding:6px 4px">No lines.</div>'}${tot}`;
    }).join("");
    const old = (m.cheques || []).length ? `<div class="mh2" style="margin-top:12px">OTHER CHEQUES (ADDED BEFORE CHEQUE LINES)</div>${m.cheques.map((c) => `<div class="pfitem"><div class="w"><b>${esc(c.milestone)}</b><span>${esc(c.bank || "")} ${esc(c.no || "")}${c.deadline ? " · deadline " + fmtShort(c.deadline) : ""}</span></div><b>${money(c.amount)}</b></div>`).join("")}` : "";
    return `<div class="subhead"><h3>Cheques</h3><div class="sp"></div>${canAdd ? '<button class="btnp edit-only" id="pf-lnadd">+ Add cheque line</button>' : ""}</div>${strip}${groups}${old}
      ${canAdd && ls.length ? '<div class="hint">Tap a line to change or remove it. After approval the lines are locked.</div>' : ""}
      ${m.pccDue ? `<div class="hint">Payment Clearance Certificate dues: ${money(m.pccDue)}</div>` : ""}`;
  }
  /** Add or change one cheque line. The type list and the boxes come from the SOP's RG-05 repeating items. */
  function lineDialog(f, line) {
    const ks = kinds(), md = document.getElementById("modal");
    let ki = line ? line.kind : 0;
    const draw = () => {
      const cols = colsOf(ks[ki]), v = (line && line.kind === ki && line.v) || {};
      md.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">${line ? "Change cheque line" : "Add cheque line"} · plot ${esc(f.plot)}</b><div class="foot">Only the lines this plot needs. They print on RG-05 as tables${ks[ki] && /^y/i.test(ks[ki].total || "") ? " with a total" : ""}, after the Accounts Head approves.</div>
        <div class="lbl">TYPE</div><select id="ln-k">${ks.map((k, i) => `<option value="${i}" ${i === ki ? "selected" : ""}>${esc(k.item)}</option>`).join("")}</select>
        ${cols.map((c, i) => `<div class="lbl">${esc(c.name.toUpperCase())}${c.kind === "amount" ? " (Rs)" : ""}</div><input data-ln="${i}" type="${c.kind === "date" ? "date" : c.kind === "amount" || c.kind === "number" ? "number" : "text"}" value="${esc(v[c.name] || "")}">`).join("")}
        <div class="err" id="ln-err"></div><div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px">${line ? '<button class="link" id="ln-del" style="color:var(--er);margin-right:auto">Remove line</button>' : ""}<button class="link" id="ln-x">Cancel</button><button class="btnp" id="ln-ok">${line ? "Save" : "Add"}</button></div></div></div>`;
      md.querySelector("#ln-k").onchange = (e) => { ki = Number(e.target.value); draw(); };
      md.querySelector("#ln-x").onclick = () => (md.innerHTML = "");
      const dl = md.querySelector("#ln-del");
      if (dl) dl.onclick = async () => { const ls = linesOf(f).filter((l) => l.id !== line.id); await RB.Store.put("plotmoney", f.id, { lines: ls }, true); await RB.log("Plot " + f.plot + ": cheque line removed"); md.innerHTML = ""; RB.render(); };
      md.querySelector("#ln-ok").onclick = async () => {
        const vals = {}; let bad = "";
        cols.forEach((c, i) => { const x = md.querySelector(`[data-ln="${i}"]`).value.trim(); vals[c.name] = x; if (!x) bad = "Fill in " + c.name.toLowerCase() + "."; else if (c.kind === "amount" && !(Number(x) > 0)) bad = c.name + " must be more than 0."; });
        if (bad) { md.querySelector("#ln-err").textContent = bad; return; }
        const nl = { id: line ? line.id : RB.id(), kind: ki, v: vals };
        const ls = line ? linesOf(f).map((l) => (l.id === line.id ? nl : l)) : linesOf(f).concat(nl);
        await RB.Store.put("plotmoney", f.id, { lines: ls, linesBy: { by: RB.uidOf(), byName: (D.me && D.me.name) || "", at: Date.now() } }, true);
        await RB.log("Plot " + f.plot + ": cheque line " + (line ? "changed" : "added") + " (" + ks[ki].item + ")"); md.innerHTML = ""; RB.render();
      };
    };
    draw();
  }
  /** Approve the cheque lines. Normally the Accounts Head on the phone; until then the owner or a manager here, recorded as from portal.
   *  Approval locks the lines and adds the plot's RG-05 rows to Items: the SOP's normal RG-05 items plus one row per cheque line. */
  function approveLines(f) {
    const ls = linesOf(f); if (!ls.length) return;
    RB.confirm("Approve the " + ls.length + " cheque lines of plot " + esc(f.plot) + "? The Accounts Head normally does this on the phone. It will show as approved from portal by you.", "Approve", async () => {
      const now = Date.now(), ks = kinds(), have = new Set((f.items || []).map((i) => i.id)), add = [];
      let lineN = {};
      rg05Items().forEach((it, n) => {
        if (/^repeat/i.test(it.type || "")) { const ki = ks.indexOf(it); ls.filter((l) => l.kind === ki).forEach((l) => { lineN[ki] = (lineN[ki] || 0) + 1; const id = "r5" + l.id; if (!have.has(id)) add.push({ id, group: R5, name: it.item.replace(/s$/, "") + " · " + (lineText(l) || "cheque " + lineN[ki]), lineId: l.id, status: "none" }); }); }
        else { const id = "r5n" + n; if (!have.has(id)) add.push({ id, group: R5, name: it.item, status: "none" }); }
      });
      await RB.Store.put("plotmoney", f.id, { linesOk: { by: RB.uidOf(), byName: (D.me && D.me.name) || "Owner", at: now, backup: true } }, true);
      await save(f, { items: (f.items || []).concat(add) }, "Cheque lines approved from portal by " + ((D.me && D.me.name) || "Owner") + " · " + add.length + " RG-05 rows added");
    });
  }
  /** Front Desk records a cheque that arrived against its expected line; a date or amount that differs is flagged "Check". */
  function recordDialog(f, lineId) {
    const l = linesOf(f).find((x) => x.id === lineId); if (!l) return;
    RB.form("Record cheque · plot " + f.plot, [{ k: "bank", label: "BANK" }, { k: "no", label: "CHEQUE NUMBER" }, { k: "date", label: "DATE ON THE CHEQUE", type: "date", value: lineDate(l) }, { k: "amount", label: "AMOUNT ON THE CHEQUE (Rs)", type: "number", value: String(lineAmt(l) || "") }], "Save", async (v) => {
      if (!v.bank.trim() || !v.no.trim()) return "Type the bank and the cheque number.";
      const check = (lineDate(l) && v.date !== lineDate(l)) || Number(v.amount) !== lineAmt(l);
      const ls = linesOf(f).map((x) => (x.id === lineId ? Object.assign({}, x, { rec: { bank: v.bank.trim(), no: v.no.trim(), date: v.date, amount: Number(v.amount), at: Date.now(), by: RB.uidOf(), check: !!check } }) : x));
      await RB.Store.put("plotmoney", f.id, { lines: ls }, true);
      const items = (f.items || []).map((i) => (i.lineId === lineId && (i.status || "none") === "none" ? Object.assign({}, i, { status: "received", at: Date.now(), by: RB.uidOf() }) : i));
      await save(f, { items }, "Cheque recorded: " + v.bank.trim() + " no. " + v.no.trim() + (check ? " (date or amount differs from the line)" : ""));
    }, "Front Desk, at the counter. If the date or amount differs from the expected line, the line shows “Check”.");
  }
  /** RG-05 print (locked layout, plotforms.js): only after approval, only for people with money details. */
  function printRG05(f) {
    const m = D.plotMoney[f.id] || {}, ks = kinds(), x = sopX();
    const form = ((x && x.forms) || []).find((y) => y.code === "RG-05");
    const prep = m.linesBy ? RB.member(m.linesBy.by) : null;
    RB.plotForms.print(RB.plotForms.rg05({ plot: f.plot, owner: f.owner, title: form ? form.name : "Required Attachments with Floor Plan submission",
      items: rg05Items().map((it) => (/^repeat/i.test(it.type || "") ? { name: it.item, cols: colsOf(it), total: /^y/i.test(it.total || ""), rows: linesOf(f).filter((l) => l.kind === ks.indexOf(it)).map((l) => l.v) } : { name: it.item })),
      prepared: m.linesBy ? { name: m.linesBy.byName, at: m.linesBy.at, desig: prep && prep.designation } : null, approved: m.linesOk ? { name: m.linesOk.byName, at: m.linesOk.at } : null, phone: prep && prep.phone }));
  }
  /** RG-14 Still pending slip (locked layout): the plot's RG-05 rows not yet Verified or Waived; wording from the Slips tab. */
  function printRG14(f, stepId) {
    const x = sopX(), sl = ((x && x.slips) || []).filter((r) => /^RG-14/.test(r.slip || "")), part = (p) => (sl.find((r) => r.part === p) || {}).text || "";
    const pend = (f.items || []).filter((i) => i.group === R5 && !["verified", "waived"].includes(i.status || "none"));
    const lines = pend.map((i) => { const l = i.lineId ? linesOf(f).find((y) => y.id === i.lineId) : null; return l && RB.canMoney() ? i.name + " · " + money(lineAmt(l)) + (lineDate(l) ? " · dated " + dshow(lineDate(l)) : "") : i.name; });
    const rec = (f.items || []).filter((i) => i.group === R5 && i.at && i.status !== "none").sort((a, b) => a.at - b.at)[0];
    const st = stepDef(stepId);
    RB.plotForms.print(RB.plotForms.rg14({ plot: f.plot, owner: f.owner, stage: st ? STAGES[st.stage] || "" : "", receivedOn: rec ? rec.at : 0, frontDesk: rec && RB.member(rec.by) ? RB.member(rec.by).name : "", submittedBy: f.submittedBy || "", message: part("Message"), closing: part("Closing"), items: lines }));
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
      on("#pf-lnadd", () => lineDialog(cur()));
      on("[data-lnedit]", (e) => { const f = cur(); lineDialog(f, linesOf(f).find((l) => l.id === e.dataset.lnedit)); });
      on("#pf-lok", () => approveLines(cur()));
      on("[data-rec]", (e) => recordDialog(cur(), e.dataset.rec));
      on("#pf-print5", () => { if (approved(cur())) printRG05(cur()); });
      on("[data-rg14]", (e) => printRG14(cur(), e.dataset.rg14));
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
