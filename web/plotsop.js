/* Plot files SOP pages in Docs > SOPs, built live from the published Google Sheet (config/plotsop). READ ONLY.
 * Owner: Steps (List | Flow chart), Forms, Role SOPs, SOP settings, Versions. Staff: their own role SOP, the forms of their own steps, the Flow chart.
 * Locked design: mockups v5-sops S1-S7. Nothing of the SOP is in this code: publish a new version from the sheet and every page follows.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const P = (RB.plotSop = {});

  const X = () => (D.plotSop && Array.isArray(D.plotSop.steps) && D.plotSop.steps.length ? D.plotSop : null);
  const own = () => RB.isOwner();
  const lang = () => (S.sopLang === "ur" ? "ur" : "en");
  const ur = (o, k, l) => (l === "ur" && o[k + "Ur"] ? o[k + "Ur"] : o[k] || "");
  const info = (key, l) => { const r = (X().info || []).find((i) => i.k === key); return r ? (l === "ur" && r.ur ? r.ur : r.en) : ""; };
  const ver = () => (D.plotSop && D.plotSop.version) || 0;
  const sopName = () => (X() && info("SOP name", "en")) || "Plot files SOP";
  const when = () => { const t = D.plotSop && D.plotSop.publishedAt; return t ? new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : ""; };
  const form = (code) => (X().forms || []).find((f) => f.code === code);
  const stage = (n) => (X().stages || []).find((g) => String(g.no) === String(n)) || { no: n, name: "" };
  const stagesOf = (steps) => Array.from(new Set(steps.map((s) => String(s.stage))));
  /** The seat's own role(s). Staff see only these; the owner sees every role. */
  const myRoles = () => { const d = (D.me && D.me.designation) || (RB.seatOfUid ? (RB.seatOfUid(RB.uidOf()) || {}).designation : ""); return (X().roles || []).filter((r) => r.role === d).map((r) => r.role); };
  const mySteps = () => (own() ? X().steps : X().steps.filter((s) => myRoles().includes(s.role)));

  /** Every RG-xx code in a text opens its Drive PDF (locked 21 Nov: every form code anywhere is clickable). plain = print: no links. */
  const fl = (t, plain) => esc(t).replace(/RG-\d\d/g, (c) => { const f = form(c); return !plain && f && f.link ? `<a class="sxfl" target="_blank" rel="noopener noreferrer" href="${esc(f.link)}" title="Open ${c} in Google Drive">${c} ↗</a>` : `<b>${c}</b>`; });
  const paras = (t, plain) => (t ? String(t).split("\n").filter((x) => x.trim()).map((x) => `<p>${fl(x, plain)}</p>`).join("") : "");
  const chips = (a) => (a && a.length ? a.map((r) => `<span class="tag">${esc(r)}</span>`).join(" ") : '<span class="sub">—</span>');
  const val = (v) => (v === undefined || v === null || v === "" ? '<span class="sub">—</span>' : esc(String(v)));
  const f = (lab, v, hint) => `<div class="sxf"><b>${lab}</b><div>${v}${hint ? `<div class="sub">${hint}</div>` : ""}</div></div>`;
  const sec = (t, body, extra) => `<div class="card sxsec"><div class="subhead"><h3>${t}</h3><div class="sp"></div>${extra || ""}</div>${body}</div>`;
  const toggle = (l, a, b) => `<span class="sxlang"><button class="sc ${l === "en" ? "on" : ""}" data-sxlang="en">${a || "English"}</button><button class="sc ${l === "ur" ? "on" : ""}" data-sxlang="ur">${b || "اردو"}</button></span>`;
  const RO = '<div class="hint sxro">Read only. The SOP is written in its master Google Sheet. To change anything, change the sheet, then use Versions › Get changes from Google Sheet.</div>';
  const W = {
    en: { title: "Title", stage: "Stage", starts: "Starts when", chk: "Checklist", wrong: "If something goes wrong", forms: "Forms used", what: "What the task says", how: "How to do this", click: "Click any line to see how to do it, in full detail.", prev: "Previous step(s)", next: "Next step(s)", rr: "Receive / Return", ask: "Who to ask", pause: "Pause", rules: "Rules for everyone", steps: "Your steps, in full detail", first: "None (first step)", onlyIf: "shown only if", opens: "opens step" },
    ur: { title: "عنوان", stage: "مرحلہ", starts: "کب شروع ہوتا ہے", chk: "چیک لسٹ", wrong: "اگر کوئی مسئلہ ہو", forms: "اس اسٹیپ کے فارم", what: "ٹاسک پر کیا لکھا ہے", how: "یہ کیسے کریں", click: "تفصیل دیکھنے کے لیے کسی بھی لائن پر کلک کریں", prev: "پچھلا اسٹیپ", next: "اگلا اسٹیپ", rr: "وصول / واپس", ask: "کس سے پوچھیں", pause: "وقفہ", rules: "سب کے لیے اصول", steps: "آپ کے اسٹیپس، پوری تفصیل کے ساتھ", first: "کوئی نہیں (پہلا اسٹیپ)", onlyIf: "shown only if", opens: "opens step" },
  };

  /** Steps that open this step: Flow tab rows, plus checklist options with "Opens step". */
  const prev = (id) => { const x = X(), a = (x.flow || []).filter((r) => String(r.to) === id).map((r) => String(r.from)); x.steps.forEach((s) => (s.checklist || []).forEach((c) => { if (String(c.opens) === id) a.push(s.id + " · " + c.no); })); return Array.from(new Set(a)); };
  const formsUsed = (s, l, plain) => ((s.forms || []).length ? s.forms.map((c) => { const fm = form(c) || {}; return fl(c, plain) + " " + esc(ur(fm, "name", l)); }).join("<br>") : '<span class="sub">—</span>');

  // ------------------------------------------------------------------ checklist lines: tap a line to open its full detail (all open when printed)
  const TYPE = { Tick: "☐", "Pick one": "◉", Option: "○", "Number box": "#" };
  const detail = (c, l, plain) => { const t = l === "ur" ? c.detailUr || c.detail : c.detail; if (!t) return ""; const L = W[l];
    return `<div class="sxdet"><div class="sxdh">${L.how}</div>${String(t).split("\n").filter((x) => x.trim()).map((x) => (/^(If something goes wrong|اگر کوئی مسئلہ ہو|You need|آپ کو چاہیے)/.test(x) ? `<p><b>${fl(x, plain)}</b></p>` : `<p>${fl(x, plain)}</p>`)).join("")}</div>`; };
  function lines(s, l, plain) {
    const L = W[l], cl = s.checklist || []; if (!cl.length) return '<span class="sub">—</span>';
    return `<div class="sxl">${cl.map((c) => { const k = s.id + "|" + c.no, open = plain || !!(S.sopLines || {})[k];
      return `<div class="sxline ${c.type === "Option" ? "opt" : ""}" ${plain ? "" : `data-sxline="${esc(k)}" role="button" tabindex="0" aria-expanded="${open}"`}><span class="sxno">${esc(c.no)}</span><span class="sxic">${TYPE[c.type] || "☐"}</span><span class="sxlt">${fl(l === "ur" ? c.ur || c.en : c.en, plain)}${c.opens ? ` <span class="tag blu" dir="ltr">${L.opens} ${esc(c.opens)}</span>` : ""}${c.onlyIf ? ` <span class="tag" dir="ltr">${L.onlyIf} ${esc(c.onlyIf)}</span>` : ""}</span><span class="sxty" dir="ltr">${esc(c.type)}</span>${plain ? "" : `<span class="sxchev">${open ? "▾" : "›"}</span>`}</div>${open ? detail(c, l, plain) : ""}`; }).join("")}</div>`;
  }

  // ------------------------------------------------------------------ Steps tab (S1) and one step (S2/S3)
  function stepsList() {
    const x = X(), groups = stagesOf(x.steps), allOpen = groups.every((g) => S.sopBands[g]);
    return `<div class="card"><div class="subhead"><h3>Steps</h3><div class="sp"></div>${toggle(S.sopFlow ? "ur" : "en", "List", "Flow chart").replace(/data-sxlang/g, "data-sxflow")}${S.sopFlow ? '<button class="btnp" data-sxprint="flow">🖨 Print / Save PDF</button>' : `<button class="btnt" data-sxall="${allOpen ? 0 : 1}">${allOpen ? "Close all" : "Open all"}</button>`}</div>
      ${S.sopFlow ? flowChart() : `<div class="hint">Tap a stage to open its steps. Tap a step to see all its details.</div>${groups.map((g) => { const ss = x.steps.filter((s) => String(s.stage) === g), o = !!S.sopBands[g];
        return `<div class="sxgrp"><div class="sxband2" data-sxband="${esc(g)}"><span class="sxn2">${esc(g)}</span><div class="w"><b>${esc(stage(g).name)}</b><span>Steps ${esc(ss[0].id)} to ${esc(ss[ss.length - 1].id)}</span></div><span class="sxcnt">${ss.length} steps</span><span class="sxchev">${o ? "▾" : "▸"}</span></div>
          ${o ? `<div class="sxbody">${ss.map((s) => `<div class="sxsrow" data-sxstep="${esc(s.id)}"><b>${esc(s.id)}</b><div class="w"><span class="t">${esc(s.title)}</span><span><span class="tag">${esc(s.role)}</span> ${esc(s.limit || "")}</span></div><span class="sxgo">›</span></div>`).join("")}</div>` : ""}</div>`; }).join("")}`}</div>`;
  }
  const sheetRow = (s) => { const x = X(), url = x.sheetUrl, gid = (x.gids || {}).Steps; return url && gid !== undefined && s.row ? `${url}#gid=${gid}&range=A${s.row}` : url || ""; };
  function stepPage(id) {
    const x = X(), s = x.steps.find((y) => y.id === id); if (!s) { S.sopStep = null; return stepsList(); }
    const l = lang(), L = W[l], link = sheetRow(s), pv = prev(s.id);
    return `<div class="sxstepbar"><button class="link" data-sxsteps="1">‹ All steps</button><div class="sxsel"><b>${esc(s.id)}</b> <span>${esc(s.title)}</span><select id="sx-pick" aria-label="Pick a step">${x.steps.map((y) => `<option value="${esc(y.id)}" ${y.id === s.id ? "selected" : ""}>${esc(y.id)} ${esc(y.title)}</option>`).join("")}</select></div>${link ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(link)}">Edit in sheet ↗</a>` : ""}</div>
      ${sec(L.what, `<div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""}>${f(L.title, esc(ur(s, "title", l)))}${f(L.stage, esc(s.stage + " · " + ur(stage(s.stage), "name", l)))}${f(L.starts, val(ur(s, "starts", l)))}${f(L.chk, lines(s, l) + `<div class="sub" style="margin-top:6px">${L.click}</div>`)}${f(L.wrong, paras(ur(s, "wrong", l)) || val(""))}${f(L.forms, formsUsed(s, l))}</div>`, toggle(l))}
      ${sec("People", f("Who does it", chips([s.role])) + f("Who to ask", chips(s.ask)) + f("Call list", chips(s.call), "Names shown in the phone picker when Call is tapped.") + f("WhatsApp list", chips(s.wa)) + f("WhatsApp message", s.waMsg ? `<p>${esc(s.waMsg)}</p>` : val(""), "Typed in for the plot owner only. Staff can edit before sending."))}
      <div class="sx2"><div>${sec("Time", f("Time allowed", val(s.limit) + (s.timer ? ` <span class="sub">(${esc(s.timer)}${s.days !== null && s.days !== undefined ? ", " + s.days + (s.days === 1 ? " day" : " days") : ""}${s.hours ? ", " + s.hours + (s.hours === 1 ? " hour" : " hours") : ""})</span>` : "")) + f("Can pause (Waiting for plot owner)", s.canPause ? "Yes" : "No") + (s.canPause ? f("Pause starts", val(s.pauseStarts)) + f("Reminder while paused", val(s.reminder)) : ""))}
        ${sec("Task buttons", f("Receive / Return", val(s.recv)) + f("Return goes to", s.returnTo ? chips([s.returnTo]) : val("")) + f("Done allowed without all ticks", s.doneWithoutAll ? "Yes" : "No"))}</div>
      <div>${sec("Flow", f("Previous step(s)", esc(pv.join(", ") || "None (first step)")) + f("Next step(s)", val((s.next || []).join(", "))))}${sec("Notify (CC)", f("On start", chips(s.notify.start)) + f("When done", chips(s.notify.done)) + f("When late", chips(s.notify.late)))}</div></div>`;
  }

  // ------------------------------------------------------------------ Flow chart (S7): drawn from the Flow tab and checklist "Opens step"
  const STC = ["#E9EEF8", "#E9EEF8", "#F3ECDD", "#E6F2EA", "#F1E8F4", "#FBEBC8", "#E3F0F5", "#F4F1EA", "#EAF1F1"];
  function flowChart(plain) {
    const x = X(), steps = x.steps, idx = {}; steps.forEach((s, i) => (idx[s.id] = i));
    const E = (x.flow || []).filter((r) => idx[r.from] !== undefined && idx[r.to] !== undefined).map((r) => { const m = String(r.onlyIf || "").split("·")[1]; return { a: String(r.from), b: String(r.to), opt: r.onlyIf ? "if " + (m ? m.trim() : r.onlyIf) + " picked" : "" }; });
    steps.forEach((s) => (s.checklist || []).forEach((c) => { if (c.opens && idx[c.opens] !== undefined && !E.some((e) => e.a === s.id && e.b === String(c.opens))) E.push({ a: s.id, b: String(c.opens), opt: "if " + c.no + " picked" }); }));
    const lv = {}; steps.forEach((s) => (lv[s.id] = 0));
    for (let k = 0; k < steps.length; k++) E.forEach((e) => { if (idx[e.b] > idx[e.a] && lv[e.b] < lv[e.a] + 1) lv[e.b] = lv[e.a] + 1; });   // only forward edges set the rows, so a loop back cannot run away
    const rows = {}; steps.forEach((s) => (rows[lv[s.id]] = (rows[lv[s.id]] || []).concat(s)));
    const BW = 236, BH = 74, RH = 118, Wd = Math.max(1080, ...Object.values(rows).map((r) => r.length * (BW + 40))), pos = {};
    Object.entries(rows).forEach(([r, xs]) => xs.forEach((s, i) => (pos[s.id] = { x: Wd / 2 + (i - (xs.length - 1) / 2) * (BW + 40) - BW / 2, y: 20 + Number(r) * RH })));
    const H = 20 + (Math.max(...Object.keys(rows).map(Number)) + 1) * RH;
    const outs = (id) => E.filter((e) => e.a === id && !e.opt).length;
    const joins = (id) => Array.from(new Set((x.flow || []).filter((r) => String(r.to) === id && r.waits).map((r) => String(r.from)))).join(" + ");
    const paths = E.map((e) => { const p = pos[e.a], q = pos[e.b], x1 = p.x + BW / 2, y1 = p.y + BH, x2 = q.x + BW / 2, y2 = q.y, my = (y1 + y2) / 2;
      return `<path d="M${x1},${y1} C${x1},${my} ${x2},${my} ${x2},${y2 - 6}" fill="none" stroke="${e.opt ? "#8A5A00" : "#1F3A93"}" stroke-width="2" ${e.opt ? 'stroke-dasharray="6 5"' : ""} marker-end="url(#sxar${e.opt ? "o" : ""})"/>${e.opt ? `<text x="${(x1 + x2) / 2 + 6}" y="${my - 4}" font-size="12" fill="#8A5A00" font-weight="700">${esc(e.opt)}</text>` : ""}`; }).join("");
    const boxes = steps.map((s) => { const p = pos[s.id], n = outs(s.id), j = joins(s.id);
      return `<div class="sxfb" ${plain || !own() ? "" : `data-sxstep="${esc(s.id)}"`} style="left:${p.x}px;top:${p.y}px;width:${BW}px;height:${BH}px;background:${STC[Number(s.stage)] || STC[0]}"><b style="color:#1F3A93">${esc(s.id)}</b> <b>${esc(s.title)}</b><div style="margin-top:3px"><span class="tag">${esc(s.role)}</span></div>
        ${n > 1 ? `<span class="sxbadge" style="background:#E8590C;right:-8px;bottom:-11px">opens ${n} at once</span>` : ""}${j ? `<span class="sxbadge" style="background:#6B3FA0;right:-8px;top:-12px">waits for ALL: ${esc(j)}</span>` : ""}</div>`; }).join("");
    const legend = `<div class="sxleg"><span><b style="color:#1F3A93">——▶</b> Done opens the next step</span><span><b style="color:#8A5A00">- - ▶</b> opens only if that option is ticked</span><span><span class="sxbadge" style="background:#E8590C;position:static">opens 3 at once</span> several people get a task at the same time</span><span><span class="sxbadge" style="background:#6B3FA0;position:static">waits for ALL</span> starts only when every step named is Done</span></div>`;
    return `${legend}<div class="sxfc" data-w="${Wd}" data-h="${H}"><div class="sxfci" style="width:${Wd}px;height:${H}px"><svg width="${Wd}" height="${H}" aria-hidden="true"><defs><marker id="sxar" markerWidth="10" markerHeight="10" refX="5" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#1F3A93"/></marker><marker id="sxaro" markerWidth="10" markerHeight="10" refX="5" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#8A5A00"/></marker></defs>${paths}</svg>${boxes}</div></div>`;
  }

  // ------------------------------------------------------------------ Forms tab (S4): the code opens the Drive PDF
  function formsTab() {
    const x = X(), mine = mySteps(), used = (c) => mine.filter((s) => (s.forms || []).includes(c)).map((s) => s.id);
    const fs = (x.forms || []).filter((fm) => own() || used(fm.code).length);
    return `<div class="card"><div class="subhead"><h3>Paper forms</h3></div><div class="hint">The app prints nothing. Tap a form code to open its PDF in Google Drive.${own() ? "" : " Only the forms used in your own steps are shown."}</div>
      ${fs.length ? `<table class="sxt sxforms"><thead><tr><th>Code</th><th>Form</th><th class="sxur">اردو</th><th>Used in steps</th></tr></thead><tbody>${fs.map((fm) => `<tr><td>${fm.link ? fl(fm.code) : `<b>${esc(fm.code)}</b>${own() ? ' <span class="sub" style="color:var(--er)">link missing</span>' : ""}`}</td><td>${esc(fm.name)}<div class="sxur2" dir="rtl">${esc(fm.nameUr || "")}</div></td><td class="sxur" dir="rtl">${esc(fm.nameUr || "")}</td><td>${used(fm.code).map((u) => (own() ? `<button class="tag blu" data-sxstepgo="${esc(u)}">${esc(u)}</button>` : `<span class="tag">${esc(u)}</span>`)).join(" ") || '<span class="sub">—</span>'}</td></tr>`).join("")}</tbody></table>` : '<div class="muted">No forms in your steps.</div>'}</div>`;
  }

  // ------------------------------------------------------------------ Role SOPs (S5) and the main SOP, with Print / Save PDF
  function stepBox(s, l, plain) {
    const L = W[l], r = (k, v) => (v ? `<div class="sxr"><b>${k}</b><div>${v}</div></div>` : ""), pv = prev(s.id);
    return `<div class="sxstep"><div class="sxh"><b>${esc(s.id)} · ${esc(ur(s, "title", l))}</b><span>${esc(s.limit || "")}</span></div>${r(L.starts, esc(ur(s, "starts", l)))}${r(L.prev, esc(pv.join(", ") || L.first))}${r(L.next, esc((s.next || []).join(", ")))}${r(L.rr, esc(s.recv || "") + (s.returnTo ? " · Return goes to " + esc(s.returnTo) : ""))}${s.canPause ? r(L.pause, esc(s.pauseStarts || "")) : ""}${r(L.ask, esc((s.ask || []).join(", ")))}${r(L.chk, lines(s, l, plain))}${r(L.wrong, paras(ur(s, "wrong", l), plain))}${r(L.forms, formsUsed(s, l, plain))}</div>`;
  }
  const rules = (l) => (X().info || []).filter((i) => /^Rule for everyone/.test(i.k)).map((i) => (l === "ur" && i.ur ? i.ur : i.en));
  const ol = (a) => (a.length ? `<ol>${a.map((t) => `<li>${esc(t)}</li>`).join("")}</ol>` : "");
  function roleHtml(name, l, plain) {
    const x = X(), r = (x.roles || []).find((y) => y.role === name), L = W[l]; if (!r) return "";
    const steps = x.steps.filter((s) => s.role === name);
    return `<h1>${esc(ur(r, "role", l))} SOP</h1><h2>${L.rules}</h2>${ol(rules(l))}<h2>${L.steps}</h2>
      ${stagesOf(steps).map((g) => `<div class="sxband"><span class="sxn">${esc(g)}</span> ${esc(ur(stage(g), "name", l))}</div>${steps.filter((s) => String(s.stage) === g).map((s) => stepBox(s, l, plain)).join("")}`).join("") || '<p class="sub">No steps for this role.</p>'}`;
  }
  function mainHtml(l, plain) {
    const x = X();
    return `<h1>${esc(info("Title printed on the main SOP", l) || sopName())}</h1><h2>${W[l].rules}</h2>${ol(rules(l))}
      <h2>${esc(info("Heading: the process in stages", l))}</h2><table class="sxt"><tbody>${(x.stages || []).map((g) => `<tr><td>${esc(g.no)}</td><td>${esc(ur(g, "name", l))}</td></tr>`).join("")}</tbody></table>
      <h2>${esc(info("Heading: the roles", l))}</h2><table class="sxt"><tbody>${(x.roles || []).map((r) => `<tr><td><b>${esc(ur(r, "role", l))}</b></td><td>${esc(x.steps.filter((s) => s.role === r.role).map((s) => s.id).join(", "))}</td></tr>`).join("")}</tbody></table>
      <h2>${esc(info("Heading: every step in order", l))}</h2><table class="sxt"><tbody>${x.steps.map((s) => `<tr><td>${esc(s.id)}</td><td>${esc(ur(s, "title", l))}</td><td>${esc(s.role)}</td><td>${esc(s.limit || "")}</td><td>${(s.forms || []).map((c) => fl(c, plain)).join(" ")}</td></tr>`).join("")}</tbody></table>`;
  }
  const body = (page, l, plain) => (page === "main" ? mainHtml(l, plain) : roleHtml(page, l, plain));
  function rolesTab() {
    const x = X(), l = lang(), roles = own() ? (x.roles || []).map((r) => r.role) : myRoles();
    const cur = (S.sopRole === "main" && own()) || roles.includes(S.sopRole) ? S.sopRole : roles.length === 1 && !own() ? roles[0] : null;
    if (!roles.length && !own()) return '<div class="card"><div class="muted">Your designation has no steps in this SOP.</div></div>';
    const pick = `<div class="sxroles">${own() ? `<button class="sc ${cur === "main" ? "on" : ""}" data-sxrole="main">Main SOP</button>` : ""}${roles.map((r) => `<button class="sc ${cur === r ? "on" : ""}" data-sxrole="${esc(r)}">${esc(r)}</button>`).join("")}</div>`;
    return `<div class="card"><div class="subhead"><h3>${own() ? "Role SOPs" : "My SOP"}</h3><div class="sp"></div>${toggle(l)}${cur ? '<button class="btnp" data-sxprint="role">🖨 Print / Save PDF</button>' : ""}</div>
      <div class="hint">Built live from the steps. Nothing to update by hand. Tap a checklist line to see how to do it.${own() ? " Staff see their own one in Docs › My reading list." : ""}</div>
      ${roles.length > 1 || own() ? pick : ""}${cur ? `<div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""} style="margin-top:12px">${body(cur, l, false)}</div>` : '<div class="muted" style="margin-top:12px">Pick the main SOP or a role.</div>'}</div>`;
  }

  // ------------------------------------------------------------------ SOP settings (S6, owner only)
  function settingsTab() {
    const url = X().sheetUrl;
    return `<div class="card"><div class="subhead"><h3>About this SOP</h3></div>
      ${f("Name", esc(sopName()))}${f("Master Google Sheet", url ? `<a class="link" target="_blank" rel="noopener noreferrer" href="${esc(url)}">Open the sheet ↗</a>` : '<span class="sub">The sheet does not send its address yet.</span>', "The SOP is written in this sheet. Versions › Get changes from Google Sheet reads it through the private link below.")}
      ${f("Private link (Apps Script)", RB.plotSync.settingsLink())}${f("Section", esc(info("Section", "en") || "—"))}${f("Daily work shows in", "Plot files menu (watch only; staff work on the phone)")}${f("Who can publish", "Only you (owner)")}</div>`;
  }

  // ------------------------------------------------------------------ print / save as PDF (A4, clean RB style)
  const PRINT_CSS = `@page{size:A4;margin:14mm}body{font-family:"Times New Roman",Times,serif;font-size:12px;color:#111;margin:0;line-height:1.45}body.ur{font-family:"Noto Nastaliq Urdu","Times New Roman",serif;direction:rtl;line-height:2.1;font-size:12.5px}
    .hd{display:flex;justify-content:space-between;border-bottom:2px solid #111;padding-bottom:4px;font-size:10.5px}.hd b{font-size:12px}h1{text-align:center;font-size:20px;text-decoration:underline;margin:14px 0 6px}h2{font-size:14px;margin:16px 0 4px;border-bottom:1px solid #999}p{margin:2px 0}ol{margin:4px 0;padding-inline-start:22px}
    .sxband{background:#eee;border:1px solid #bbb;padding:3px 8px;font-weight:700;margin:14px 0 6px;page-break-after:avoid}.sxn{margin-inline-end:6px}.sxstep{border:1px solid #888;margin:0 0 8px;padding:5px 8px}.sxh{display:flex;justify-content:space-between;gap:10px;border-bottom:1px solid #bbb;padding-bottom:3px;margin-bottom:3px;font-size:13px}
    .sxr{display:flex;gap:8px;margin:2px 0}.sxr>b{width:110px;flex:none}.sxr>div{flex:1}.sxt{width:100%;border-collapse:collapse;font-size:11px}.sxt td{border:1px solid #888;padding:3px 5px;text-align:start;vertical-align:top}
    .sxline{display:flex;gap:6px;border-top:1px solid #ddd;padding:2px 0}.sxline.opt{padding-inline-start:20px}.sxno{width:22px;flex:none;font-weight:700}.sxty{display:none}.sxlt{flex:1}.sxdet{padding:2px 0 4px 28px;font-size:11px}body.ur .sxdet{padding:2px 28px 4px 0}.sxdh{font-weight:700}
    .tag{border:1px solid #888;padding:0 4px;font-size:10px;font-family:Arial,sans-serif}.ft{margin-top:14px;font-size:10px;color:#555;border-top:1px solid #999;padding-top:3px}
    .sxleg{display:flex;gap:14px;flex-wrap:wrap;font-size:11px;margin:6px 0}.sxfc{position:relative}.sxfci{position:relative}.sxfci svg{position:absolute;left:0;top:0}.sxfb{position:absolute;border:1px solid #9aa;border-radius:10px;padding:6px 9px;box-sizing:border-box;font:12px/1.3 Arial,sans-serif}.sxbadge{position:absolute;color:#fff;border-radius:10px;padding:1px 7px;font:700 11px Arial,sans-serif;-webkit-print-color-adjust:exact;print-color-adjust:exact}.sxfb{-webkit-print-color-adjust:exact;print-color-adjust:exact}`;
  P.print = (what, l) => {
    if (!X()) return;
    const flow = what === "flow", page = S.sopRole, title = flow ? sopName() + " · Flow chart" : page === "main" ? info("Title printed on the main SOP", "en") : page + " SOP";
    const v = "Version " + ver() + " · " + when();
    let inner = flow ? flowChart(true) : body(page, l, true);
    if (flow) { const m = inner.match(/data-w="(\d+)"/), w = m ? Number(m[1]) : 1080; inner = `<div style="zoom:${Math.min(1, 1000 / w)}">${inner}</div>`; }
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title><link href="https://fonts.googleapis.com/css2?family=Noto+Nastaliq+Urdu:wght@400;700&display=swap" rel="stylesheet"><style>${PRINT_CSS}${flow ? "@page{size:A4 landscape}" : ""}</style></head><body class="${flow ? "en" : l}">
      <div class="hd"><div><b>RAFIQ BUILDERS &amp; DEVELOPERS · ROYAL GARDEN, GOJRA</b><br>${esc(sopName())}</div><div>${esc(v)}</div></div>${inner}<div class="ft">${esc(v)} · printed ${esc(new Date().toLocaleDateString("en-GB"))}</div></body></html>`;
    const fr = document.createElement("iframe"); fr.title = "Print"; fr.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0"; document.body.appendChild(fr);
    fr.onload = () => { const go = () => { fr.contentWindow.focus(); fr.contentWindow.print(); setTimeout(() => fr.remove(), 4000); }; const fo = fr.contentDocument && fr.contentDocument.fonts; if (fo && fo.ready) Promise.race([fo.ready, new Promise((r) => setTimeout(r, 3000))]).then(go); else go(); };
    fr.srcdoc = html;
  };

  // ------------------------------------------------------------------ Docs > SOPs: list row, page, binding
  Object.assign(S, { sopOpen: null, sopTab: "steps", sopStep: null, sopRole: null, sopBands: {}, sopLines: {}, sopFlow: false, sopNew: false });

  /** The built SOP's row in the Docs > SOPs list (the whole row opens it). */
  P.listRow = (section) => {
    const x = X(); if (!x) return "";
    const sec2 = info("Section", "en"); if (section && section !== "all" && section !== sec2) return "";
    return `<div class="docrow sxrow" data-sxopen="plot"><div class="w"><b>${esc(sopName())}</b><span class="sxtag">Built in app</span><span>${esc(sec2 || "")}${sec2 ? " · " : ""}version ${ver()} · ${x.steps.length} steps · ${(x.stages || []).length} stages · ${(x.forms || []).length} forms</span><span>Must read: filled from step roles · ${(x.roles || []).length} roles</span></div><span class="sxgo">›</span></div>`;
  };
  P.section = () => (X() ? info("Section", "en") : "");

  /** The opened built SOP: header, tabs, the draft bar, and the tab. */
  P.page = () => {
    const x = X();
    const tabs = !x ? [["versions", "Versions"]] : own() ? [["steps", "Steps"], ["forms", "Forms"], ["roles", "Role SOPs"], ["settings", "SOP settings"], ["versions", "Versions"]] : [["roles", "My SOP"], ["forms", "Forms"], ["flow", "Flow chart"]];
    if (!tabs.some((t) => t[0] === S.sopTab)) S.sopTab = tabs[0][0];
    const d = own() && RB.plotSync ? RB.plotSync.draft() : null, n = d ? RB.plotSync.diff(d).length : 0;
    const head = `<div class="sxhead"><button class="link" data-sxback="1">‹ All SOPs</button><div class="sxtitle"><h2>${esc(x ? sopName() : "New SOP")}</h2>${ver() ? `<span class="tag grn">Live v${ver()}</span>` : '<span class="tag">Not published yet</span>'}</div>
      <div class="tabs sxtabs">${tabs.map(([k, t]) => `<button data-sxtab="${k}" class="${k === S.sopTab ? "on" : ""}">${t}</button>`).join("")}</div>
      ${d && S.sopTab !== "versions" ? `<div class="sxdraft"><b>Draft v${d.version} · ${n} change${n === 1 ? "" : "s"}${(d.problems || []).length ? " · " + d.problems.length + " problems" : ""}</b><button class="link" data-sxtab="versions"><span class="sxlong">Open Versions to review and publish ›</span><span class="sxshort">Review ›</span></button></div>` : ""}</div>`;
    const t = S.sopTab, ro = own() && t !== "versions" && t !== "roles" ? RO : "";
    const b = t === "versions" ? RB.plotSync.versionsTab() + (x ? '<div style="height:12px"></div>' + RB.plotSync.deleteCard() : "")
      : t === "settings" ? settingsTab() : t === "forms" ? formsTab() : t === "roles" ? rolesTab()
      : t === "flow" ? `<div class="card"><div class="subhead"><h3>Flow chart</h3><div class="sp"></div><button class="btnp" data-sxprint="flow">🖨 Print / Save PDF</button></div>${flowChart()}</div>`
      : S.sopStep ? stepPage(S.sopStep) : stepsList();
    return head + ro + b;
  };
  P.newSop = () => RB.plotSync.newSop();
  P.has = () => !!X();
  P.x = () => X();
  /** One step's part of its role SOP, all lines open (Plot files › How to do this). */
  P.stepBox = (id, l) => { const s = X() && X().steps.find((y) => y.id === id); return s ? stepBox(s, l, true) : ""; };
  /** From the reading list: open my role's SOP. */
  P.openRole = (role) => { S.sopOpen = "plot"; S.sopTab = "roles"; S.sopRole = role; S.sopNew = false; };
  /** Fit the flow chart to the card width (never wider than the screen). */
  const fit = (root) => root.querySelectorAll(".sxfc").forEach((c) => { const w = Number(c.dataset.w), h = Number(c.dataset.h), k = Math.min(1, c.clientWidth / w); c.firstElementChild.style.transform = `scale(${k})`; c.style.height = Math.ceil(h * k) + "px"; });
  P.bind = (root) => {
    // Heights for the fixed bars: the SOP header (tabs + draft bar), the draft bar alone (phone) and the role SOP title.
    const hd = root.querySelector(".sxhead"), dr = root.querySelector(".sxdraft"), rt = root.querySelector(".sxpage>h1"), st = document.documentElement.style;
    if (hd) st.setProperty("--sxh", (hd.offsetHeight || 0) + "px");
    st.setProperty("--sxd", (dr ? dr.offsetHeight : 0) + "px");
    st.setProperty("--sxrt", (rt ? rt.offsetHeight : 0) + "px");
    fit(root);
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { if (ev.target.closest("a")) return; ev.preventDefault(); fn(e); }));
    const go = (patch) => { Object.assign(S, patch); RB.render(); const c = document.getElementById("content"); if (c) c.scrollTop = 0; };
    on("[data-sxopen]", () => go({ sopOpen: "plot", sopNew: false, sopStep: null }));
    on("[data-sxback]", () => go({ sopOpen: null, sopNew: false, sopStep: null }));
    on("[data-sxtab]", (e) => go({ sopTab: e.dataset.sxtab, sopStep: null }));
    on("[data-sxlang]", (e) => { S.sopLang = e.dataset.sxlang; RB.render(); });
    on("[data-sxflow]", (e) => { S.sopFlow = e.dataset.sxflow === "ur"; RB.render(); });
    on("[data-sxprint]", (e) => P.print(e.dataset.sxprint, lang()));
    on("[data-sxrole]", (e) => { S.sopRole = e.dataset.sxrole; RB.render(); });
    on("[data-sxband]", (e) => { S.sopBands[e.dataset.sxband] = !S.sopBands[e.dataset.sxband]; RB.render(); });
    on("[data-sxall]", (e) => { const o = e.dataset.sxall === "1"; stagesOf(X() ? X().steps : []).forEach((g) => (S.sopBands[g] = o)); RB.render(); });
    on("[data-sxstep]", (e) => go({ sopTab: "steps", sopFlow: false, sopStep: e.dataset.sxstep }));
    on("[data-sxstepgo]", (e) => go({ sopTab: "steps", sopFlow: false, sopStep: e.dataset.sxstepgo }));
    on("[data-sxsteps]", () => go({ sopStep: null }));
    const line = (e) => { const k = e.dataset.sxline; S.sopLines[k] = !S.sopLines[k]; RB.render(); };
    root.querySelectorAll("[data-sxline]").forEach((e) => { e.onclick = (ev) => { if (!ev.target.closest("a")) line(e); }; e.onkeydown = (ev) => { if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); line(e); } }; });
    const pk = root.querySelector("#sx-pick"); if (pk) pk.onchange = () => go({ sopStep: pk.value });
    if (RB.plotSync && own()) RB.plotSync.bind(root);
  };
})();
