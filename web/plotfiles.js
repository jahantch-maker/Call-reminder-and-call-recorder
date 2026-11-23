/* Plot files: watch every plot file (locked mockups v4 A, B1-B4 and pending-list section I). Staff do the steps on the phone;
 * the web only reads, writes notes (with @) in an open step, and edits the plot owner details (every edit is logged).
 *
 * Data (the phone writes the same shape):
 *   plotfiles/{id}: plotNo, size (marla, number), name (plot owner), cnic (13 digits), wa (11 digits), sim2 (11 digits or ""),
 *                   address, attorney, test (true = owner's test file), status ("open" | "closed"), openedAt, openedByName,
 *                   edits: [{ at, uid, name, field, from, to }]
 *   tasks/{id} (one per step, a normal team task): plot: true, plotId, plotNo, step ("1.8"), plus the usual toUid, toName, due,
 *                   seenAt (received), doneAt, events [{ at, text }], viewers; ticks { lineNo: { at, name, value } };
 *                   waitAt (when "Waiting for plot owner" started; 0 = not waiting). Its Timeline talk is tasks/{id}/msgs.
 * Nothing of the SOP is in this code: stages, steps, roles, checklists and time allowed come from config/plotsop.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc, DAY = RB.DAY;
  Object.assign(S, { pfFile: null, pfTab: "steps", pfChip: "open", pfSort: ["due", 1], pfq: "", pfOpen: null, pfStages: {} });
  const X = () => (RB.plotSop && RB.plotSop.x ? RB.plotSop.x() : null);
  const M = {};   // task id -> its Timeline messages (loaded when needed)
  const MA = {};  // task id -> its lastMsgAt when those messages were loaded

  // ------------------------------------------------------------------ small helpers
  const sod = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const days = (a, b) => Math.round((sod(b) - sod(a)) / DAY);
  const pl = (n, w) => n + " " + w + (n === 1 ? "" : "s");
  const time = (t) => new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }).toLowerCase();
  const digits = (v) => String(v || "").replace(/\D/g, "");
  const phone = (v) => { const d = digits(v); return d.length === 11 ? d.slice(0, 4) + " " + d.slice(4) : esc(v || "—"); };
  const cnic = (v) => { const d = digits(v); return d.length === 13 ? d.slice(0, 5) + "-" + d.slice(5, 12) + "-" + d.slice(12) : esc(v || "—"); };
  const intl = (v) => { const d = digits(v); return d.length === 11 && d[0] === "0" ? "92" + d.slice(1) : d; };
  const first = (n) => RB.first(n).split(" ")[0];
  const step = (id) => (X() ? X().steps.find((s) => s.id === id) : null);
  const stageOf = (n) => ((X() && X().stages) || []).find((g) => String(g.no) === String(n)) || { no: n, name: "" };
  const allowed = (s) => (RB.stepTime(s) ? RB.stepTime(s) + " allowed" : "");
  const b64 = (b) => { if (!b) return ""; if (typeof b === "string") return b; try { if (typeof b.toBase64 === "function") return b.toBase64(); if (b._byteString && b._byteString.toBase64) return b._byteString.toBase64(); } catch (e) {} return ""; };

  // ------------------------------------------------------------------ where each file stands
  const tasksOf = (fid) => (D.plotTasks || []).filter((t) => t.plotId === fid);
  /** The latest task of one step in one file (a returned step gets a new task). */
  const taskOf = (fid, sid) => tasksOf(fid).filter((t) => t.step === sid).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0];
  const isLate = (t, now) => !t.doneAt && !t.waitAt && t.due && now > t.due;
  /** The open step the file is waiting on: the most late one when several run at once. */
  function current(f) {
    const open = tasksOf(f.id).filter((t) => !t.doneAt);
    return open.sort((a, b) => (a.waitAt ? 1 : 0) - (b.waitAt ? 1 : 0) || (a.due || 9e15) - (b.due || 9e15))[0] || null;
  }
  /** open | late | today | wait | ontime | build (construction started) | closed */
  function stateOf(f, now) {
    if (f.status === "closed") return "closed";
    const t = current(f);
    if (!t) { const x = X(); return x && x.steps.length && x.steps.every((s) => { const k = taskOf(f.id, s.id); return k && k.doneAt; }) ? "build" : "ontime"; }
    if (t.waitAt) return "wait";
    if (isLate(t, now)) return "late";
    if (t.due && sod(t.due) === sod(now)) return "today";
    return "ontime";
  }
  const BADGE = { late: ["red", "Late"], wait: ["yel", "Waiting for plot owner"], today: ["org", "Due today"], ontime: ["grn", "On time"], build: ["grn", "Construction started"], closed: ["", "Closed"] };
  const badge = (st) => `<span class="tag ${BADGE[st][0]}">${BADGE[st][1]}</span>`;
  /** The "Due" text: days late in red, waiting days, today's time or the date. */
  function dueText(t, now) {
    if (!t) return "";
    if (t.waitAt) return `<span class="plgrey">Waiting ${pl(Math.max(0, days(t.waitAt, now)), "day")}</span>`;
    if (!t.due) return "";
    if (isLate(t, now)) { const n = days(t.due, now); return `<span class="pllate">${n ? pl(n, "day") + " late" : "Late today"}</span>`; }
    return sod(t.due) === sod(now) ? "Today, " + time(t.due) : RB.fmtDay(t.due);
  }
  const ownerUid = () => (D.team && D.team.ownerUid) || "";
  const withName = (t) => (t.test && t.toUid === ownerUid() ? "on your phone" : esc(first(t.toName)));

  // ------------------------------------------------------------------ list (mockup A)
  const CHIPS = [["open", "Open", "blu"], ["today", "Due today", "peach"], ["late", "Late", "red"], ["wait", "Waiting for plot owner", "yel"], ["build", "Construction started", "grn"], ["closed", "Closed", ""]];
  const inChip = (c, st) => (c === "open" ? st !== "build" && st !== "closed" : c === st);
  const COLS = [["plot", "Plot"], ["size", "Size"], ["name", "Plot owner"], ["step", "Stage and step"], ["with", "Now with"], ["due", "Due"], ["status", "Status"]];
  const ORDER = { late: 0, wait: 1, today: 2, ontime: 3, build: 4, closed: 5 };
  function list() {
    const now = Date.now(), x = X(), own = RB.isOwner();
    const files = (D.plot || []).filter((f) => own || !f.test).map((f) => { const t = current(f), s = t && step(t.step); return { f, t, s, st: stateOf(f, now) }; });
    const counted = files.filter((r) => !r.f.test);
    const chips = CHIPS.map(([k, l, c]) => `<button class="plchip ${c} ${S.pfChip === k ? "on" : ""}" data-pfchip="${k}" aria-pressed="${S.pfChip === k}"><b>${counted.filter((r) => inChip(k, r.st)).length}</b>${l}</button>`).join("");
    const q = (S.pfq + " " + S.q).trim().toLowerCase(), qd = digits(q);
    let rows = files.filter((r) => inChip(S.pfChip, r.st) && (!q || [r.f.plotNo, r.f.name].some((v) => String(v || "").toLowerCase().includes(q)) || (qd.length >= 3 && [r.f.cnic, r.f.wa, r.f.sim2].some((v) => digits(v).includes(qd)))));
    const [col, dir] = S.pfSort, key = {
      plot: (r) => [Number(digits(r.f.plotNo)) || 0, String(r.f.plotNo)], size: (r) => Number(r.f.size) || 0, name: (r) => String(r.f.name || "").toLowerCase(),
      step: (r) => (r.s ? [Number(r.s.stage), r.s.id.split(".").map(Number)[1]] : [99, 0]), with: (r) => (r.s ? r.s.role : "~"),
      due: (r) => (r.t ? (r.t.waitAt ? now : r.t.due || 9e15) : 9e16), status: (r) => ORDER[r.st],
    }[col];
    const cmp = (a, b) => { const p = [].concat(key(a)), q2 = [].concat(key(b)); for (let i = 0; i < p.length; i++) { if (p[i] < q2[i]) return -dir; if (p[i] > q2[i]) return dir; } return 0; };
    rows = rows.sort(cmp);
    const head = COLS.map(([k, l]) => `<button class="plth ${col === k ? "on" : ""}" data-pfsort="${k}" aria-label="Sort by ${l}">${l} <span aria-hidden="true">${col === k ? (dir === 1 ? "▲" : "▼") : "⬍"}</span></button>`).join("");
    const body = rows.map(({ f, t, s, st }) => `<div class="plrow" data-pffile="${esc(f.id)}" role="button" tabindex="0">
      <div class="plc plot" data-l="Plot"><b>${esc(f.plotNo)}</b></div><div class="plc" data-l="Size">${f.size ? esc(f.size) + " marla" : "—"}</div>
      <div class="plc name"><b>${esc(f.name || "—")}</b>${f.test ? ' <span class="tag yel">TEST</span>' : ""}</div>
      <div class="plc">${s ? `${esc("Stage " + s.stage + " · " + stageOf(s.stage).name)}<span>${esc(s.id + " " + s.title)}</span>` : st === "build" ? "All steps done" : '<span>No step open</span>'}</div>
      <div class="plc">${s ? `${esc(s.role)}<span>${withName(t)}</span>` : ""}</div><div class="plc due">${dueText(t, now)}</div><div class="plc">${badge(st)}</div></div>`).join("");
    const empty = !x ? "Plot files opens once the Plot files SOP is published (Docs › SOPs)." : !files.length ? "No plot files yet. New files are opened on the Front Desk phone." : "No file matches.";
    return `<div class="card pllist"><div class="subhead"><h3>Plot files</h3><div class="sp"></div><span class="sub">New files are opened on the Front Desk phone</span></div>
      <div class="plchips">${chips}</div>
      <label class="plsearch"><span aria-hidden="true">⌕</span><input id="pfq" placeholder="Search plot number, plot owner, CNIC or phone" value="${esc(S.pfq)}" autocomplete="off" aria-label="Search plot files"></label>
      <div class="pltable"><div class="plhead">${head}</div>${body || `<div class="muted" style="padding:16px 8px">${empty}</div>`}</div>
      <div class="sub" style="padding:10px 8px 0">Showing ${pl(rows.length, (CHIPS.find((c) => c[0] === S.pfChip) || CHIPS[0])[1].toLowerCase() === "open" ? "open file" : "file")} · click any column name to sort it, click again to flip the order${own ? " · test files are only seen by you and never counted in the chips" : ""}</div></div>`;
  }

  // ------------------------------------------------------------------ one file (mockups B1-B4)
  function header(f) {
    const now = Date.now(), t = current(f), s = t && step(t.step), st = stateOf(f, now), x = X();
    const curStage = s ? String(s.stage) : null;
    const inStage = (g) => x.steps.filter((y) => String(y.stage) === String(g.no));
    const nDone = (g) => inStage(g).filter((y) => { const k = taskOf(f.id, y.id); return k && k.doneAt; }).length;
    const opened = f.openedAt ? "File opened " + RB.fmtDay(f.openedAt) + " · " + pl(days(f.openedAt, now), "day") + " ago" : "";
    const facts = [phone(f.wa) !== "—" ? "WhatsApp " + phone(f.wa) : "", f.sim2 ? "Second SIM " + phone(f.sim2) : "", f.cnic ? "CNIC " + cnic(f.cnic) : "", esc(f.address || ""), f.attorney ? "Attorney " + esc(f.attorney) : "", opened].filter(Boolean);
    const ib = (attr, icon, label) => `<button class="plib" ${attr}><i aria-hidden="true">${icon}</i>${label}</button>`;
    // Stepper: done = ✓, current = outlined with "N of M steps done"; the line to the next stage fills by that share.
    const stages = (x.stages || []).map((g, i, all) => {
      const n = inStage(g).length, d = nDone(g), done = n && d === n, cur = String(g.no) === curStage;
      const prev = all[i - 1], pn = prev ? inStage(prev).length : 0, fill = prev ? Math.round((pn ? nDone(prev) / pn : 0) * 100) : 0;
      return `<div class="${done ? "done" : cur ? "cur" : ""}" style="--f:${fill}%"><i>${done ? "✓" : esc(g.no)}</i><span>${esc(g.name)}${cur ? `<small>${d} of ${n} steps done</small>` : ""}</span></div>`;
    }).join("");
    return `<button class="link plback" data-pfback="1">← All files</button>
      <div class="card plhd"><div class="plcc"><div class="plav" aria-hidden="true">${esc((f.name || "?").trim().charAt(0).toUpperCase())}</div>
        <div class="w"><div class="plttl"><h2>Plot ${esc(f.plotNo)}</h2>${f.size ? `<span class="tag">${esc(f.size)} marla</span>` : ""}${badge(st)}${f.test ? '<span class="tag yel">TEST</span>' : ""}<b class="plnm">${esc(f.name || "—")}</b></div>
          <div class="plfacts">Plot owner · ${facts.join(" · ")}</div></div>
        <div class="plibs">${ib('data-pfcall="1"', "📞", "Call")}<a class="plib" target="_blank" rel="noopener noreferrer" href="https://wa.me/${intl(f.wa)}"><i aria-hidden="true">💬</i>WhatsApp</a>${RB.canEdit("plot") ? ib('data-pfedit="1"', "✏️", "Edit") : ""}${f.test && RB.isOwner() ? ib('data-pfdel="1"', "🗑", "Delete test") : ""}</div></div>
        ${t ? `<div class="plnow">Now with <b>${esc(s ? s.role : "")} · ${withName(t)}</b><span>Due <b>${t.due ? RB.fmtDay(t.due) : "—"}</b></span> ${dueText(t, now)}</div>` : ""}</div>
      <div class="card plstcard"><div class="plstages">${stages}</div></div>`;
  }

  /** Owner only, test files only: removes the file and all its step tasks (they leave every phone too). */
  function delTest(f) {
    if (!f.test || !RB.isOwner()) return;
    RB.confirm("Delete test file Plot " + esc(f.plotNo) + " and all its tasks? They also disappear from every phone. This cannot be undone.", "Delete", async () => {
      const ts = tasksOf(f.id);
      try {
        for (const t of ts) await RB.Store.del("tasks", t.id);
        await RB.Store.del("plotfiles", f.id);
        if (RB.DEMO) D.plotTasks = D.plotTasks.filter((t) => t.plotId !== f.id);
        await RB.log("Plot files: test file " + f.plotNo + " deleted");
        S.pfFile = null; RB.toast("Test file deleted");
      } catch (e) { RB.toast("Could not delete: " + (e && e.message ? e.message : e)); }
    }, true);
  }

  /** One step row (and its checklist + Timeline when opened in place). */
  function stepRow(f, s, nextIds) {
    const now = Date.now(), t = taskOf(f.id, s.id), done = t && t.doneAt, open = S.pfOpen === s.id;
    const state = done ? "done" : t ? "cur" : "later";
    const who = `<span class="plwho">👤 ${esc(s.role)}${t ? " · " + withName(t) : ""}</span>`;
    const when = done ? "Done " + RB.fmtDay(t.doneAt) : t ? (t.waitAt ? "Waiting for plot owner since " + RB.fmtDay(t.waitAt) : t.seenAt ? "Received " + RB.fmtWhen(t.seenAt) : "Sent " + RB.fmtWhen(t.createdAt || now) + ", not received yet") : "Not started";
    let right = "";
    if (done) { const n = t.due && t.doneAt > t.due ? days(t.due, t.doneAt) : -1; right = n < 0 ? '<span class="plok">On time</span>' : `<span class="pllate">${n ? pl(n, "day") + " late" : "Late"}</span>`; }
    else if (t) right = dueText(t, now) || '<span class="plgrey">No due time</span>';
    else if (nextIds.includes(s.id)) right = '<span class="plgrey">Next</span>';
    return `<div class="plstep ${state} ${open ? "open" : ""}"><div class="plsr" data-pfstep="${esc(s.id)}" role="button" tabindex="0" aria-expanded="${open}">
        <span class="plic">${done ? "✓" : ""}</span><div class="w"><div class="plst"><b>${esc(s.id)}</b> ${esc(s.title)}</div><div class="plmeta">${who}<b>${when}</b><span>⏱ ${esc(allowed(s) || "—")}</span></div></div>
        <div class="plright">${right}</div><span class="plchev" aria-hidden="true">${open ? "⌄" : "›"}</span></div>${open ? inPlace(f, s, t) : ""}</div>`;
  }

  function inPlace(f, s, t) {
    const ticks = (t && t.ticks) || {}, cl = s.checklist || [], counted = cl.filter((c) => c.type !== "Option");
    const lines = cl.map((c) => { const k = ticks[c.no]; return `<div class="plcl ${c.type === "Option" ? "opt" : ""} ${k ? "on" : ""}"><span class="plbox" aria-hidden="true">${k ? "✓" : ""}</span><div>${esc(c.en)}${k && k.value ? ` <b>${esc(k.value)}</b>` : ""}${k ? `<span>${esc(first(k.name))} · ${esc(RB.fmtDay(k.at).split(" ")[0])} ${time(k.at)}</span>` : ""}</div></div>`; }).join("");
    const canNote = t && !t.doneAt && RB.canEdit("plot");
    return `<div class="plpanel"><div class="card plchk"><div class="subhead"><h3>Checklist</h3><span class="sub">${counted.filter((c) => ticks[c.no]).length} of ${counted.length} ticked</span><div class="sp"></div><span class="tag">View only · ticked on the phone</span></div>
        ${lines || '<div class="muted">This step has no checklist.</div>'}
        <button class="btnt" data-pfhow="${esc(s.id)}" style="margin-top:12px">📖 How to do this</button><div class="sub" style="margin-top:4px">Opens the ${esc(s.role)} SOP for this step, English | اردو</div></div>
      <div class="card pltl"><div class="subhead"><h3>Timeline of step ${esc(s.id)}</h3><span class="sub">only this step</span></div>
        ${t ? timeline([t], false) : '<div class="muted">Not started yet.</div>'}
        ${canNote ? noteBox(s) : t && t.doneAt ? '<div class="sub" style="margin-top:10px">This step is done, so its Timeline is closed. Write a remark in the current step and mention this one (for example "about ' + esc(s.id) + '").</div>' : ""}</div></div>`;
  }

  const noteBox = (s) => `<div class="plnote"><div class="plnin"><textarea id="pfnote" rows="2" placeholder="Write a note… type @ to ask someone" aria-label="Note for step ${esc(s.id)}">${esc(S.pfDraft || "")}</textarea>
      <label class="plclip" title="Send a photo from this computer"><input type="file" id="pfphoto" accept="image/*" hidden>📎</label><button class="plsend" id="pfsend" aria-label="Send">➤</button></div>
      <div id="pfat" class="plat" hidden></div><div class="sub">Your note goes into step ${esc(s.id)} on the phone. To ask someone, type @ and pick a name (names cannot be typed).</div><div class="err" id="pferr"></div></div>`;

  /** Timeline lines for these tasks (+ the file's detail edits when all), newest first, grouped by day. */
  function timeline(tasks, all, f) {
    const L = [];
    tasks.forEach((t) => {
      (t.events || []).forEach((e) => L.push({ at: e.at, s: t.step, h: esc(e.text) }));
      (M[t.id] || []).forEach((m) => L.push({ at: m.at, s: t.step, h: msgHtml(m) }));
    });
    if (all && f) (f.edits || []).forEach((e) => L.push({ at: e.at, s: "Details", h: `<b>${esc(first(e.name))}</b> edited <b>${esc(e.field)}</b>: ${esc(e.from || "—")} → ${esc(e.to || "—")}` }));
    if (!L.length) return '<div class="muted">Nothing on the Timeline yet.</div>';
    const today = sod(Date.now());
    let lastDay = null;
    return L.sort((a, b) => b.at - a.at).map((l) => { const d = sod(l.at), pill = all && d !== lastDay ? `<div class="plday"><span>${d === today ? "Today · " : ""}${RB.fmtDay(l.at)}</span></div>` : ""; lastDay = d;
      return `${pill}<div class="plev"><span class="pltime">${all ? time(l.at) : (d === today ? "" : RB.fmtShort(l.at) + " ") + time(l.at)}</span><div><span class="tag blu">${esc(l.s)}</span> ${l.h}</div></div>`; }).join("");
  }
  const mention = (t) => esc(t).replace(/(^|\s)@([A-Za-z][\w.]*(?: [A-Z][\w.]*)?)/g, '$1<b class="plmen">@$2</b>');
  function msgHtml(m) {
    const who = `<b>${esc(first(m.fromName))}</b>${m.web ? " (note from web)" : ""}`;
    if (m.kind === "photo") { const d = b64(m.data); return `${who} added a photo${m.text ? ": " + mention(m.text) : ""}${d ? `<button class="plimg" data-pfimg="${esc(m.id)}" aria-label="Open the photo"><img alt="" src="data:image/jpeg;base64,${d}"><span>🔍 Click to open</span></button>` : ' <i class="sub">(photo cleared)</i>'}`; }
    if (m.kind === "voice") { const d = b64(m.data), sec = Math.round((m.durMs || 0) / 1000); return `${who} sent a voice note${d ? `<div class="plvoice" data-pfvoice="${esc(m.id)}"><button class="plplay" aria-label="Play">▶</button><span class="plwave" aria-hidden="true"></span><span class="pldur">${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}</span><button class="plspeed" aria-label="Speed">1x</button></div>` : ' <i class="sub">(voice note cleared)</i>'}`; }
    return m.line ? esc(m.text) : `${who}: ${mention(m.text)}`;
  }

  function filePage(f) {
    const x = X(), t = current(f), cur = t && step(t.step), nextIds = cur ? cur.next || [] : [];
    const tabs = `<div class="tabs pltabs"><button data-pftab="steps" class="${S.pfTab === "steps" ? "on" : ""}">Steps</button><button data-pftab="timeline" class="${S.pfTab === "timeline" ? "on" : ""}">Timeline</button></div>`;
    if (S.pfTab === "timeline") return header(f) + tabs + `<div class="card"><div class="hint">This is the whole file, all steps, newest first. To write a note, open the step in the <b>Steps</b> tab.</div>${timeline(tasksOf(f.id), true, f)}</div>`;
    const groups = Array.from(new Set(x.steps.map((s) => String(s.stage))));
    const body = groups.map((g) => {
      const ss = x.steps.filter((s) => String(s.stage) === g), started = ss.some((s) => taskOf(f.id, s.id)), nd = ss.filter((s) => { const k = taskOf(f.id, s.id); return k && k.doneAt; }).length;
      const openG = S.pfStages[g] !== undefined ? S.pfStages[g] : started;
      if (!openG) return `<div class="plgrp" data-pfstage="${esc(g)}" role="button" tabindex="0" aria-expanded="false"><span class="pfn">${esc(g)}</span><b>${esc(stageOf(g).name)}</b><span class="sub">${pl(ss.length, "step")} · ${nd === ss.length ? "done" : nd ? nd + " done" : "not started"}</span></div>`;
      return `<div class="plgh" data-pfstage="${esc(g)}" role="button" tabindex="0" aria-expanded="true">STAGE ${esc(g)} · ${esc(String(stageOf(g).name).toUpperCase())} · ${nd} OF ${ss.length} DONE</div>${ss.map((s) => stepRow(f, s, nextIds)).join("")}`;
    }).join("");
    return header(f) + tabs + `<div class="card plsteps">${body}</div>`;
  }

  // ------------------------------------------------------------------ actions
  // Live: a new message (phone or web) moves the task's lastMsgAt; the task list is watched, so the Timeline reloads then.
  async function loadMsgs(tasks) {
    const need = tasks.filter((t) => !M[t.id] || (t.lastMsgAt || 0) > (MA[t.id] || 0));
    if (!need.length) return;
    need.forEach((t) => (MA[t.id] = t.lastMsgAt || 0));
    await Promise.all(need.map((t) => RB.Store.msgs(t.id).then((m) => (M[t.id] = m)).catch(() => (M[t.id] = M[t.id] || []))));
    RB.render();
  }
  const fileNow = () => (D.plot || []).find((f) => f.id === S.pfFile);

  function editDetails(f) {
    const F = [["plotNo", "Plot number"], ["size", "Plot size (marla)"], ["name", "Plot owner name"], ["cnic", "CNIC number (13 digits)"], ["wa", "WhatsApp number (11 digits)"], ["sim2", "Second SIM (11 digits, optional)"], ["address", "Address"], ["attorney", "Attorney name (optional)"]];
    RB.form("Edit details · Plot " + f.plotNo, F.map(([k, label]) => ({ k, label, value: f[k] === undefined || f[k] === null ? "" : String(f[k]), type: k === "size" ? "number" : k === "cnic" ? "cnic" : k === "wa" || k === "sim2" ? "phone" : "text" })), "Save", async (v) => {
      Object.keys(v).forEach((k) => (v[k] = String(v[k]).trim()));
      ["cnic", "wa", "sim2"].forEach((k) => (v[k] = digits(v[k])));
      if (!v.plotNo) return "Plot number is needed.";
      if (!(Number(v.size) > 0)) return "Plot size must be a number of marla.";
      if (!v.name) return "Plot owner name is needed.";
      if (v.cnic.length !== 13) return "CNIC must be exactly 13 digits.";
      if (!/^03\d{9}$/.test(v.wa)) return "WhatsApp number must be a mobile number: 11 digits starting 03.";
      if (v.sim2 && !/^03\d{9}$/.test(v.sim2)) return "Second SIM must be a mobile number (11 digits starting 03), or empty.";
      if (!v.address) return "Address is needed.";
      const same = (D.plot || []).find((o) => o.id !== f.id && !!o.test === !!f.test && String(o.plotNo).trim().toLowerCase() === v.plotNo.toLowerCase());
      if (same) return "Plot " + v.plotNo + " already has a file" + (same.name ? " (" + same.name + ")" : "") + ". A plot number can be opened only once.";
      v.size = Number(v.size);
      const me = (D.me && D.me.name) || "Owner", at = Date.now();
      const log = F.filter(([k]) => String(f[k] === undefined || f[k] === null ? "" : f[k]) !== String(v[k])).map(([k, label]) => ({ at, uid: RB.uidOf() || "", name: me, field: label.replace(/ \(.*\)$/, ""), from: String(f[k] === undefined || f[k] === null ? "" : f[k]), to: String(v[k]) }));
      if (!log.length) return;
      await RB.Store.put("plotfiles", f.id, Object.assign(v, { edits: (f.edits || []).concat(log), updatedAt: at }), true);
    }, "Every change is written on the file's Timeline (old → new, who, when).");
  }

  function callPick(f) {
    const nums = [["WhatsApp number", f.wa], ["Second SIM", f.sim2]].filter((n) => digits(n[1]).length === 11);
    if (nums.length === 1) { location.href = "tel:+" + intl(nums[0][1]); return; }
    const m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">Call ${esc(f.name || "the plot owner")}</b>${nums.map((n) => `<a class="arow click" style="text-decoration:none;color:inherit" href="tel:+${intl(n[1])}"><span class="w">${phone(n[1])}<span>${n[0]}</span></span><span class="link">Call ›</span></a>`).join("") || '<div class="muted" style="margin:12px 0">No number saved.</div>'}<div style="text-align:right;margin-top:12px"><button class="link" id="cn">Cancel</button></div></div></div>`;
    document.getElementById("cn").onclick = () => (m.innerHTML = "");
  }

  function howTo(id, l) {
    const s = step(id), m = document.getElementById("modal"); if (!s) return;
    m.innerHTML = `<div class="back"><div class="dlg plhow"><div class="subhead"><b style="font-size:19px">How to do this · ${esc(s.id)}</b><div class="sp"></div><span class="sxlang"><button class="sc ${l === "en" ? "on" : ""}" data-l="en">English</button><button class="sc ${l === "ur" ? "on" : ""}" data-l="ur">اردو</button></span></div>
      <div class="sub">${esc(s.role)} SOP</div><div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""}>${RB.plotSop.stepBox(s.id, l)}</div><div style="text-align:right;margin-top:12px"><button class="btnp" id="ok">Close</button></div></div></div>`;
    document.getElementById("ok").onclick = () => (m.innerHTML = "");
    m.querySelectorAll("[data-l]").forEach((b) => (b.onclick = () => howTo(id, b.dataset.l)));
  }

  /** Photo viewer (mockup B4): zoom - / 100% / +, mouse wheel, Download. */
  function viewer(src) {
    let z = 1; const m = document.getElementById("modal");
    m.innerHTML = `<div class="back plview"><div class="plvbar"><button class="sc" data-z="-1" aria-label="Zoom out">−</button><button class="sc" data-z="0" id="pfz">100%</button><button class="sc" data-z="1" aria-label="Zoom in">+</button><a class="sc" download="photo.jpg" href="${src}">Download</a><button class="sc" id="cn">✕ Close</button></div><div class="plvimg"><img id="pfvi" alt="Photo" src="${src}"></div></div>`;
    const img = document.getElementById("pfvi"), set = (v) => { z = Math.min(5, Math.max(0.25, v)); img.style.transform = `scale(${z})`; document.getElementById("pfz").textContent = Math.round(z * 100) + "%"; };
    m.querySelectorAll("[data-z]").forEach((b) => (b.onclick = () => set(b.dataset.z === "0" ? 1 : z * (b.dataset.z === "1" ? 1.25 : 0.8))));
    m.querySelector(".plvimg").onwheel = (e) => { e.preventDefault(); set(z * (e.deltaY < 0 ? 1.1 : 0.9)); };
    document.getElementById("cn").onclick = () => (m.innerHTML = "");
  }

  /** A photo from the computer, made small enough for one Timeline message (as the phone does). */
  const shrink = (file) => new Promise((ok, bad) => {
    const r = new FileReader(); r.onerror = () => bad(new Error("Couldn't read the photo."));
    r.onload = () => { const im = new Image(); im.onerror = () => bad(new Error("This file is not a photo.")); im.onload = () => {
      const k = Math.min(1, 1280 / Math.max(im.width, im.height)), c = document.createElement("canvas"); c.width = Math.round(im.width * k); c.height = Math.round(im.height * k);
      c.getContext("2d").drawImage(im, 0, 0, c.width, c.height); ok(c.toDataURL("image/jpeg", 0.7).split(",")[1]); }; im.src = r.result; };
    r.readAsDataURL(file);
  });

  /** Note (text or photo) into this step's Timeline; @ names also get a help request, like the phone. */
  async function send(t, text, photo, picked) {
    const me = D.me || { name: "Owner" }, uid = RB.uidOf(), now = Date.now();
    const who = picked.filter((p) => text.includes("@" + p.name));
    const msg = { at: now, fromUid: uid, fromName: me.name, kind: photo ? "photo" : "text", tag: "", text, durMs: 0, viewers: t.viewers || [], web: true };
    const preview = (photo ? "📷 Photo" + (text ? ": " + text : "") : text).slice(0, 120);
    if (RB.DEMO) { (M[t.id] = M[t.id] || []).push(Object.assign({ id: "w" + now }, msg, photo ? { data: photo } : {})); return; }
    const fs = firebase.firestore, ref = RB.tref().collection("tasks").doc(t.id), b = fs().batch();
    if (photo) msg.data = fs.Blob.fromBase64String(photo);
    b.set(ref.collection("msgs").doc(), msg);
    b.update(ref, Object.assign({ lastMsgAt: now, lastMsgBy: uid, lastMsgText: preview, ["nBy." + uid]: fs.FieldValue.increment(1) }, photo ? { nPhoto: fs.FieldValue.increment(1) } : {}));
    if (who.length) {
      const hid = uid + "_t-" + t.id, href = RB.tref().collection("helps").doc(hid);
      let cur = null; try { const g = await href.get(); cur = g.exists ? g.data() : null; } catch (e) { cur = null; }
      const helpers = ((cur && cur.helpers) || []).filter((h) => !who.some((w) => w.uid === h.uid)).concat(who.map((w) => ({ uid: w.uid, name: w.name, solvedAt: 0 })));
      const line = "🤝 " + who.map((w) => first(w.name)).join(", ") + (who.length > 1 ? " were" : " was") + " asked to help";
      b.set(href, { ownerUid: uid, ownerName: me.name, key: "t:" + t.id, title: "Plot " + t.plotNo + " · " + t.step + " " + ((step(t.step) || {}).title || ""), helpers, viewers: Array.from(new Set([uid].concat(helpers.map((h) => h.uid)))),
        history: (t.events || []).slice(-60).map((e) => ({ at: e.at, text: String(e.text).slice(0, 300) })), createdAt: (cur && cur.createdAt) || now, updatedAt: now, ownerDone: false, lastMsgAt: now + 1, lastMsgBy: uid, lastMsgText: text.slice(0, 120) }, { merge: true });
      b.set(href.collection("msgs").doc(), { at: now, fromUid: uid, fromName: me.name, text: line, line: true });
      b.set(href.collection("msgs").doc(), { at: now + 1, fromUid: uid, fromName: me.name, text, line: false });
    }
    await b.commit();
    delete M[t.id];
  }

  function bindNote(root, f) {
    const ta = root.querySelector("#pfnote"); if (!ta) return;
    const t = taskOf(f.id, S.pfOpen), at = root.querySelector("#pfat"), err = root.querySelector("#pferr");
    S.pfPicked = S.pfPicked || [];
    const people = () => D.members.filter((m) => m.status === "active" && m.uid !== RB.uidOf());
    const desig = (u) => ((RB.seatOfUid(u) || {}).designation || "");
    const word = () => { const m = ta.value.slice(0, ta.selectionStart).match(/(^|\s)@([^\s@]*)$/); return m ? m[2] : null; };
    const show = () => {
      const w = word(); if (w === null) { at.hidden = true; return; }
      const l = people().filter((m) => m.name.toLowerCase().includes(w.toLowerCase())).slice(0, 8);
      at.hidden = !l.length;
      at.innerHTML = `<div class="mh2">Pick a team member</div>` + l.map((m) => `<button class="platp" data-u="${esc(m.uid)}"><span class="plav">${esc(m.name.split(" ").map((x) => x[0]).join("").slice(0, 2).toUpperCase())}</span><b>${esc(m.name)}</b><span>${esc(desig(m.uid))}</span></button>`).join("");
      at.querySelectorAll("[data-u]").forEach((b) => (b.onmousedown = (e) => { e.preventDefault(); const p = people().find((m) => m.uid === b.dataset.u), pos = ta.selectionStart, before = ta.value.slice(0, pos).replace(/@[^\s@]*$/, "@" + p.name + " ");
        ta.value = before + ta.value.slice(pos); S.pfDraft = ta.value; if (!S.pfPicked.some((x) => x.uid === p.uid)) S.pfPicked.push({ uid: p.uid, name: p.name }); ta.focus(); ta.setSelectionRange(before.length, before.length); at.hidden = true; }));
    };
    ta.oninput = () => { S.pfDraft = ta.value; show(); };
    ta.onkeyup = (e) => { if (e.key === "ArrowLeft" || e.key === "ArrowRight") show(); };
    ta.onblur = () => setTimeout(() => (at.hidden = true), 150);
    const go = async (photo) => {
      const text = ta.value.trim(); if (!text && !photo) return;
      err.textContent = "";
      try { await send(t, text, photo, S.pfPicked); S.pfDraft = ""; S.pfPicked = []; await loadMsgs([t]); RB.toast("Sent"); RB.render(); }
      catch (e) { err.textContent = "Not sent: " + (e.message || e); }
    };
    root.querySelector("#pfsend").onclick = () => go(null);
    ta.onkeydown = (e) => { if (e.key === "Enter" && !e.shiftKey && !e.isComposing && at.hidden) { e.preventDefault(); go(null); } };
    root.querySelector("#pfphoto").onchange = async (e) => { const file = e.target.files[0]; if (!file) return; try { go(await shrink(file)); } catch (x) { err.textContent = x.message; } };
  }

  function bindVoice(root) {
    root.querySelectorAll("[data-pfvoice]").forEach((v) => {
      const m = Object.values(M).flat().find((x) => x.id === v.dataset.pfvoice); if (!m) return;
      const a = new Audio("data:audio/mp4;base64," + b64(m.data)), play = v.querySelector(".plplay"), sp = v.querySelector(".plspeed"), R = [1, 1.5, 2];
      play.onclick = () => (a.paused ? a.play() : a.pause());
      a.onplay = () => (play.textContent = "❚❚"); a.onpause = a.onended = () => (play.textContent = "▶");
      sp.onclick = () => { a.playbackRate = R[(R.indexOf(a.playbackRate) + 1) % R.length]; sp.textContent = a.playbackRate + "x"; };
    });
  }

  // ------------------------------------------------------------------ section
  RB.sections.plot = {
    tabs: () => [["files", "Files"]],
    render() {
      const f = fileNow(); if (!f) S.pfFile = null;
      const page = { title: "Plot files", sub: "Watch every plot file · notes and @ only, staff work on their phone" };
      page.html = f && X() ? filePage(f) : list();
      return page;
    },
    bind(root) {
      const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => { e.onclick = (ev) => { if (ev.target.closest("a,input,textarea,.plpanel")) return; ev.preventDefault(); fn(e); }; if (e.getAttribute("role") === "button") e.onkeydown = (ev) => { if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); fn(e); } }; });
      const top = () => { const c = document.getElementById("content"); if (c) c.scrollTop = 0; };
      on("[data-pfchip]", (e) => { S.pfChip = e.dataset.pfchip; RB.render(); });
      on("[data-pfsort]", (e) => { const k = e.dataset.pfsort; S.pfSort = [k, S.pfSort[0] === k ? -S.pfSort[1] : 1]; RB.render(); });
      on("[data-pffile]", (e) => { S.pfFile = e.dataset.pffile; S.pfTab = "steps"; S.pfOpen = null; S.pfStages = {}; S.pfDraft = ""; S.pfPicked = []; RB.render(); top(); });
      on("[data-pfback]", () => { S.pfFile = null; RB.render(); top(); });
      on("[data-pftab]", (e) => { S.pfTab = e.dataset.pftab; RB.render(); });
      on("[data-pfstage]", (e) => { const g = e.dataset.pfstage; S.pfStages[g] = e.getAttribute("aria-expanded") !== "true"; RB.render(); });
      on("[data-pfstep]", (e) => { S.pfOpen = S.pfOpen === e.dataset.pfstep ? null : e.dataset.pfstep; S.pfDraft = ""; S.pfPicked = []; RB.render(); });
      root.querySelectorAll("[data-pfhow]").forEach((b) => (b.onclick = () => howTo(b.dataset.pfhow, "en")));
      root.querySelectorAll("[data-pfimg]").forEach((b) => (b.onclick = () => viewer(b.querySelector("img").src)));
      const q = root.querySelector("#pfq");
      if (q) q.oninput = () => { S.pfq = q.value; RB.render(); const n = document.getElementById("pfq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
      const f = fileNow(); if (!f || !X()) return;
      const c = root.querySelector("[data-pfcall]"); if (c) c.onclick = () => callPick(f);
      const ed = root.querySelector("[data-pfedit]"); if (ed) ed.onclick = () => editDetails(f);
      const dl = root.querySelector("[data-pfdel]"); if (dl) dl.onclick = () => delTest(f);
      bindNote(root, f); bindVoice(root);
      const need = S.pfTab === "timeline" ? tasksOf(f.id) : S.pfOpen && taskOf(f.id, S.pfOpen) ? [taskOf(f.id, S.pfOpen)] : [];
      loadMsgs(need);
    },
  };
})();
