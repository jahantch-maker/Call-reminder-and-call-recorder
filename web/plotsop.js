/* Plot files SOP pages: the written SOP built live from the published Google Sheet (config/plotsopx).
 * Role SOP pages and the main SOP, English or Urdu, "Print / Save PDF", the "How to do this" window on a step, and clickable form chips.
 * Shown in Docs > SOPs (everyone with Docs access). Nothing here is typed by hand: publish a new version from the sheet and every page follows.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const P = (RB.plotSop = {});

  /** The published SOP with every {variable} replaced by its value from the sheet's Variables tab (cached per published version). */
  let fillKey = null, filled = null;
  const X = () => {
    const raw = D.plotSopX && D.plotSopX.steps ? D.plotSopX : null; if (!raw) return null;
    const k = raw.version + ":" + raw.publishedAt; if (k === fillKey) return filled;
    const vals = {}; (raw.variables || []).forEach((v) => { if (v.code) vals[v.code] = v.value; });
    const fill = (o) => (typeof o === "string" ? o.replace(/\{(\w+)\}/g, (m, c) => (vals[c] !== undefined && vals[c] !== "" ? vals[c] : m)) : Array.isArray(o) ? o.map(fill) : o && typeof o === "object" ? Object.fromEntries(Object.entries(o).map(([a, b]) => [a, a === "variables" ? b : fill(b)])) : o);
    fillKey = k; filled = fill(raw); return filled;
  };
  const lang = () => (S.sopLang === "ur" ? "ur" : "en");
  const LAB = {
    en: { starts: "Starts when", what: "What to do", inapp: "In the app", wrong: "If something goes wrong", done: "Done when", ask: "Who to ask", forms: "Forms", limit: "Time limit", job: "Your job in one line", rules: "Golden rules", need: "What you need at your desk", formsUse: "Forms you use", askAbout: "Who to ask about what", words: "Words you will see", stage: "Stage", stages: "The process in stages", steps: "Every step, in order", roles: "The roles", rulesAll: "Rules for everyone", purpose: "Purpose", no: "No.", step: "Step", role: "Role", what2: "What happens", time: "Time", form: "Form" },
    ur: { starts: "کب شروع ہوتا ہے", what: "کیا کرنا ہے", inapp: "ایپ میں", wrong: "اگر کوئی مسئلہ ہو", done: "کب مکمل ہوتا ہے", ask: "کس سے پوچھیں", forms: "فارمز", limit: "وقت کی حد", job: "آپ کا کام ایک لائن میں", rules: "سنہری اصول", need: "آپ کو کیا چاہیے", formsUse: "آپ کے فارمز", askAbout: "کس بارے میں کس سے پوچھیں", words: "وہ الفاظ جو آپ دیکھیں گے", stage: "مرحلہ", stages: "مراحل میں طریقہ کار", steps: "ہر اسٹیپ، ترتیب سے", roles: "رولز", rulesAll: "سب کے لیے اصول", purpose: "مقصد", no: "نمبر", step: "اسٹیپ", role: "رول", what2: "کیا ہوتا ہے", time: "وقت", form: "فارم" },
  };
  const pick = (o, k, l) => { const u = o[k + "Ur"]; return l === "ur" && u && (!Array.isArray(u) || u.length) ? u : o[k]; };
  const info = (key, l) => { const r = (X().info || []).find((i) => i.k === key); return r ? (l === "ur" && r.ur ? r.ur : r.en) : ""; };
  const ol = (a) => (a && a.length ? `<ol>${a.map((x) => `<li>${esc(x)}</li>`).join("")}</ol>` : "");
  const ul = (a) => (a && a.length ? `<ul>${a.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>` : "");
  const para = (t) => (t ? `<p>${esc(t).replace(/\n/g, "<br>")}</p>` : "");
  const when = () => { const t = D.plotSopX && D.plotSopX.publishedAt; return t ? new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : ""; };
  const form = (code) => (X().forms || []).find((f) => f.code === code);

  /** A form chip: opens the form's Drive file. Forms the app prints show "app prints it". A missing link is flagged for the owner only. */
  P.chip = (code, plain) => {
    const f = form(code), name = f ? code + " " + f.name : code;
    if (plain) return esc(name);
    if (f && f.link) return `<a class="tag blu" target="_blank" rel="noopener noreferrer" href="${esc(f.link)}" title="Open in Google Drive">${esc(code)} ↗</a>`;
    if (f && /Checklist|Slip/.test(f.kind || "")) return `<span class="tag blu" title="${esc(name)}: the app prints it">${esc(code)} · app prints it</span>`;
    const miss = RB.isOwner() && f && f.kind === "Fixed PDF";
    return `<span class="tag" ${miss ? 'style="color:var(--er)"' : ""} title="${esc(name)}">${esc(code)}${miss ? " · link missing" : ""}</span>`;
  };

  function stepBox(s, l, plain) {
    const L = LAB[l], row = (lab, val) => (val ? `<div class="sxr"><b>${lab}</b><div>${val}</div></div>` : "");
    const chips = (s.forms || []).map((c) => P.chip(c, plain)).join(plain ? ", " : " ");
    return `<div class="sxstep"><div class="sxh"><b>${esc(s.id)} · ${esc(pick(s, "title", l))}</b><span>${esc(pick(s, "limit", l) || "")}</span></div>
      ${row(L.starts, para(pick(s, "starts", l)))}${row(L.what, ol(pick(s, "what", l)))}${row(L.inapp, para(pick(s, "inapp", l)))}${row(L.wrong, para(pick(s, "wrong", l)))}${row(L.done, para(pick(s, "done", l)))}${row(L.ask, esc(s.ask || ""))}${row(L.forms, chips)}</div>`;
  }
  const stageName = (n, l) => { const g = (X().stages || []).find((x) => String(x.no) === String(n)); return g ? pick(g, "name", l) : ""; };

  /** The page of one role. */
  function roleHtml(name, l, plain) {
    const x = X(), r = (x.roles || []).find((y) => y.role === name), L = LAB[l]; if (!r) return "";
    const steps = x.steps.filter((s) => s.role === name), stages = Array.from(new Set(steps.map((s) => s.stage))).sort((a, b) => a - b);
    return `<h1>${esc(pick(r, "role", l))}</h1>${para(pick(r, "job", l))}
      <h2>${L.rules}</h2>${ol(pick(r, "rules", l))}<h2>${L.need}</h2>${ul(pick(r, "need", l))}<h2>${L.formsUse}</h2>${ul(pick(r, "forms", l))}
      ${stages.map((g) => `<div class="sxband"><span class="sxn">${g}</span> ${esc(stageName(g, l))}</div>${steps.filter((s) => s.stage === g).map((s) => stepBox(s, l, plain)).join("")}`).join("")}
      <h2>${L.askAbout}</h2>${ul(pick(r, "ask", l))}<h2>${L.words}</h2>${(x.words || []).map((w) => `<p><b>${esc(pick(w, "w", l))}</b>: ${esc(pick(w, "m", l))}</p>`).join("")}`;
  }
  /** The main (general) SOP: purpose, stages, roles in one line, every step in one line, forms. */
  function mainHtml(l, plain) {
    const x = X(), L = LAB[l], rules = (x.info || []).filter((i) => /^Rule for everyone/.test(i.k)).map((i) => (l === "ur" && i.ur ? i.ur : i.en));
    return `<h1>${esc(info("Title printed on the main SOP", l))}</h1>${para(info("Subtitle", l))}<h2>${L.purpose}</h2>${para(info("Purpose", l))}<h2>${L.rulesAll}</h2>${ol(rules)}
      <h2>${esc(info("Heading: the process in stages", l) || L.stages)}</h2><table class="sxt"><thead><tr><th>${L.no}</th><th>${L.stage}</th><th>${L.what2}</th><th>${L.time}</th></tr></thead><tbody>${(x.stages || []).map((g) => `<tr><td>${esc(g.no)}</td><td>${esc(pick(g, "name", l))}</td><td>${esc(pick(g, "what", l))}</td><td>${esc(pick(g, "time", l))}</td></tr>`).join("")}</tbody></table>
      <h2>${esc(info("Heading: the roles", l) || L.roles)}</h2><table class="sxt"><tbody>${(x.roles || []).map((r) => `<tr><td><b>${esc(pick(r, "role", l))}</b></td><td>${esc(pick(r, "job", l))}</td></tr>`).join("")}</tbody></table>
      <h2>${esc(info("Heading: every step in order", l) || L.steps)}</h2><table class="sxt"><thead><tr><th>${L.no}</th><th>${L.step}</th><th>${L.role}</th><th>${L.limit}</th><th>${L.form}</th></tr></thead><tbody>${x.steps.map((s) => `<tr><td>${esc(s.id)}</td><td>${esc(pick(s, "title", l))}</td><td>${esc(s.role)}</td><td>${esc(pick(s, "limit", l) || "")}</td><td>${(s.forms || []).map((c) => P.chip(c, plain)).join(plain ? ", " : " ")}</td></tr>`).join("")}</tbody></table>
      <h2>${L.forms}</h2><table class="sxt"><tbody>${(x.forms || []).map((f) => `<tr><td>${esc(f.code)}</td><td>${esc(f.name)}</td></tr>`).join("")}</tbody></table>`;
  }
  const body = (page, l, plain) => (page === "main" ? mainHtml(l, plain) : roleHtml(page, l, plain));

  // ------------------------------------------------------------------ print / save as PDF (A4, clean RB style)
  const PRINT_CSS = `@page{size:A4;margin:14mm}body{font-family:"Times New Roman",Times,serif;font-size:12px;color:#111;margin:0;line-height:1.45}body.ur{font-family:"Noto Nastaliq Urdu","Times New Roman",serif;direction:rtl;line-height:2.1;font-size:12.5px}
    .hd{display:flex;justify-content:space-between;border-bottom:2px solid #111;padding-bottom:4px;font-size:10.5px}.hd b{font-size:12px}h1{text-align:center;font-size:20px;text-decoration:underline;margin:14px 0 6px}h2{font-size:14px;margin:16px 0 4px;border-bottom:1px solid #999}p{margin:4px 0}ol,ul{margin:4px 0;padding-inline-start:22px}
    .sxband{background:#eee;border:1px solid #bbb;padding:3px 8px;font-weight:700;margin:14px 0 6px;page-break-after:avoid}.sxstep{border:1px solid #888;margin:0 0 8px;padding:5px 8px;page-break-inside:avoid}.sxh{display:flex;justify-content:space-between;gap:10px;border-bottom:1px solid #bbb;padding-bottom:3px;margin-bottom:3px;font-size:13px}
    .sxr{display:flex;gap:8px;margin:2px 0}.sxr>b{width:110px;flex:none}.sxr>div{flex:1}.sxr p{margin:0}.sxr ol{margin:0}.sxt{width:100%;border-collapse:collapse;font-size:11px}.sxt th,.sxt td{border:1px solid #888;padding:3px 5px;text-align:start;vertical-align:top}.sxt th{background:#eee}
    .tag{border:1px solid #888;padding:0 4px;font-size:10px}a{color:#111;text-decoration:none}.ft{margin-top:14px;font-size:10px;color:#555;border-top:1px solid #999;padding-top:3px}`;
  P.print = (page, l) => {
    const x = X(); if (!x) return;
    const title = page === "main" ? info("Title printed on the main SOP", "en") : "SOP · " + page, ver = "Version " + (D.plotSopX.version || "") + " · " + when();
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title><link href="https://fonts.googleapis.com/css2?family=Noto+Nastaliq+Urdu:wght@400;700&display=swap" rel="stylesheet"><style>${PRINT_CSS}</style></head><body class="${l}">
      <div class="hd"><div><b>RAFIQ BUILDERS &amp; DEVELOPERS · ROYAL GARDEN, GOJRA</b><br>${esc(info("SOP name", "en"))}</div><div>${esc(ver)}</div></div>${body(page, l, true)}<div class="ft">${esc(ver)} · printed ${esc(new Date().toLocaleDateString("en-GB"))}</div></body></html>`;
    const fr = document.createElement("iframe"); fr.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0"; document.body.appendChild(fr);
    fr.onload = () => { const go = () => { fr.contentWindow.focus(); fr.contentWindow.print(); setTimeout(() => fr.remove(), 4000); }; const f = fr.contentDocument && fr.contentDocument.fonts; if (f && f.ready) Promise.race([f.ready, new Promise((r) => setTimeout(r, 3000))]).then(go); else go(); };
    fr.srcdoc = html;
  };

  // ------------------------------------------------------------------ Docs > SOPs: the built SOP, READ ONLY (locked design: SOP builder mockup v10)
  // The Google Sheet is the master. Every tab shows the SOP exactly as published; to change anything, change the sheet and use Versions › Get changes.
  Object.assign(S, { sopOpen: null, sopTab: "steps", sopStep: null, sopRole: null, sopBands: {}, sopForm: null, sopNew: false });
  const toggle = (l) => `<span class="sxlang"><button class="sc ${l === "en" ? "on" : ""}" data-sxlang="en">English</button><button class="sc ${l === "ur" ? "on" : ""}" data-sxlang="ur">اردو</button></span>`;
  const own = () => RB.isOwner();
  const ver = () => (D.plotSopX && D.plotSopX.version) || 0;
  const sopName = () => (X() && info("SOP name", "en")) || "Plot files SOP";
  const roleChips = (a) => (a && a.length ? a.map((r) => `<span class="tag">${esc(r)}</span>`).join(" ") : '<span class="sub">none</span>');
  const val = (v) => (v === undefined || v === null || v === "" ? '<span class="sub">—</span>' : esc(String(v)));
  const sheetRow = (s) => { const x = X(), url = x.sheetUrl, gid = (x.gids || {}).Steps; return url && gid !== undefined && s.row ? `${url}#gid=${gid}&range=A${s.row}` : url || ""; };
  const RO = '<div class="hint sxro">Read only. The SOP is written in its master Google Sheet. To change anything, change the sheet, then use Versions › Get changes from Google Sheet.</div>';

  /** The built SOP's row in the Docs > SOPs list (the whole row opens it). */
  P.listRow = (section) => {
    const x = X(); if (!x) return "";
    const sec = info("Section", "en"); if (section && section !== "all" && section !== sec) return "";
    const nForms = (x.forms || []).filter((f) => /^RG-/.test(f.code)).length;
    return `<div class="docrow sxrow" data-sxopen="plot"><div class="w"><b>${esc(sopName())}</b><span class="sxtag">Built in app</span><span>${esc(sec || "")}${sec ? " · " : ""}version ${ver()} · ${x.steps.length} steps · ${(x.stages || []).length} stages · ${nForms} forms</span><span>Must read: filled from step roles · ${(x.roles || []).length} roles</span></div><span class="sxgo">›</span></div>`;
  };
  P.section = () => (X() ? info("Section", "en") : "");

  // ---- Steps tab
  const stageOf = (n) => (X().stages || []).find((g) => String(g.no) === String(n)) || { no: n, name: "" };
  function stepsTab() {
    const x = X(), groups = Array.from(new Set(x.steps.map((s) => s.stage))), allOpen = groups.every((g) => S.sopBands[g]);
    return `<div class="card"><div class="subhead"><h3>Steps</h3><div class="sp"></div><button class="btnt" data-sxall="${allOpen ? 0 : 1}">${allOpen ? "Close all" : "Open all"}</button></div><div class="hint">Tap a stage to open its steps. Tap a step to see all its details.</div>
      ${groups.map((g) => { const ss = x.steps.filter((s) => s.stage === g), o = !!S.sopBands[g];
        return `<div class="sxgrp"><div class="sxband2" data-sxband="${g}"><span class="sxn2">${g}</span><div class="w"><b>${esc(stageOf(g).name)}</b><span>Steps ${esc(ss[0].id)} to ${esc(ss[ss.length - 1].id)}</span></div><span class="sxcnt">${ss.length} steps</span><span class="sxchev">${o ? "▾" : "▸"}</span></div>
          ${o ? `<div class="sxbody">${ss.map((s) => `<div class="sxsrow" data-sxstep="${esc(s.id)}"><b>${esc(s.id)}</b><div class="w"><span class="t">${esc(s.title)}</span><span>${esc(s.role)}${s.limit ? " · " + esc(s.limit) : ""}</span></div><span class="sxgo">›</span></div>`).join("")}</div>` : ""}</div>`; }).join("")}</div>`;
  }
  /** One step with every setting (read only), in the locked editor layout: Words, People, Time, Order, Phone task, Approval, Notify (CC). */
  function stepPage(id) {
    const x = X(), s = x.steps.find((y) => y.id === id); if (!s) { S.sopStep = null; return stepsTab(); }
    const l = lang(), L = LAB[l], f = (lab, v, hint) => `<div class="sxf"><b>${lab}</b><div>${v}${hint ? `<div class="sub">${hint}</div>` : ""}</div></div>`;
    const sec = (t, body, extra) => `<div class="card sxsec"><div class="subhead"><h3>${t}</h3><div class="sp"></div>${extra || ""}</div>${body}</div>`;
    const forms = (s.forms || []).map((c) => P.chip(c)).join(" ");
    const paused = /^y/i.test(s.canPause || "");
    const link = sheetRow(s);
    return `<div class="sxstepbar"><button class="link" data-sxsteps="1">‹ All steps</button><div class="sxsel"><b>${esc(s.id)}</b> <span>${esc(s.title)}</span><select id="sx-pick">${x.steps.map((y) => `<option value="${esc(y.id)}" ${y.id === s.id ? "selected" : ""}>${esc(y.id)} ${esc(y.title)}</option>`).join("")}</select></div>${link ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(link)}">Edit in sheet ↗</a>` : ""}</div>
      ${sec("Words", `<div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""}>${f(L.step === "اسٹیپ" ? "عنوان" : "Title", esc(pick(s, "title", l)))}${f(L.starts, para(pick(s, "starts", l)) || val(""))}${f(L.what, ol(pick(s, "what", l)) || val(""))}${f(L.inapp, para(pick(s, "inapp", l)) || val(""))}${f(L.wrong, para(pick(s, "wrong", l)) || val(""))}${f(L.done, para(pick(s, "done", l)) || val(""), l === "en" ? "One short line: how the person knows this step is finished." : "")}</div>`, toggle(l))}
      ${sec("People", f("Who does it", `<span class="tag">${esc(s.role)}</span>`) + f("Who to ask", roleChips(list(s.ask))))}
      ${sec("Time", f("Time limit", val(s.limit) + (s.timer ? ` <span class="sub">(${esc(s.timer)}${s.days !== "" && s.days != null ? ", " + esc(String(s.days)) + " days" : ""})</span>` : "")) + f("Can the clock pause", val(s.canPause || "No"), "Yes adds a pause button to this step's phone task.") + (paused ? f("Pause button name", val(s.pauseName)) + f("Reminder while paused", val(s.reminder)) + f("If still paused after (days)", val(s.stillPaused)) + f("...also tell", roleChips(s.alsoTell)) : "") + f("Who may extend", roleChips(s.extend), "On the phone task and in the portal, these people can move the deadline, with a typed reason.") + f("Delay reasons", s.delays && s.delays.length ? ul(s.delays) : val(""), "When the task is late, the person picks one of these on the phone."))}
      ${s.after.length || s.alongside.length || s.onlyIf ? sec("Order exceptions", f("Comes after step", roleChips(s.after)) + f("Runs alongside step", roleChips(s.alongside)) + f("Only if", val(s.onlyIf))) : ""}
      ${sec("Phone task, forms and items", f("Makes a phone task", val(s.phoneTask) + (/^y/i.test(s.phoneTask || "") ? ` <span class="sub">goes to the person holding “${esc(s.role)}”</span>` : "")) + f("Forms used", forms || val("")) + (s.chkForm || s.chkJob ? f("Checklist job", val([s.chkForm, s.chkJob].filter(Boolean).join(": "))) : ""))}
      ${s.approval && !/^none$/i.test(s.approval) ? sec("Approval", f("Approval type", val(s.approval)) + f("Reason box", val(s.reason)) + f("Photo of signed paper", val(s.photo)) + f("If rejected, go back to step", roleChips(s.goBack)) + (s.confirms.length ? f("Who confirms (ink)", roleChips(s.confirms)) : "")) : ""}
      ${sec("Notify (CC)", f("On start", roleChips(s.notify.start)) + f("When done", roleChips(s.notify.done)) + f("When late", roleChips(s.notify.late)) + f("When rejected", roleChips(s.notify.rejected)))}
      ${own() && s.note ? sec("Notes (only you see this)", para(s.note)) : ""}`;
  }
  const list = (t) => (Array.isArray(t) ? t : String(t || "").split(/[,;\n]+/).map((y) => y.trim()).filter(Boolean));

  // ---- Forms tab
  const KIND_SUB = (f) => (/Checklist/.test(f.kind) ? "Items with tick boxes" : /Slip/.test(f.kind) ? "Filled and printed by the app" : /Fixed/.test(f.kind) ? (f.link ? "Drive link saved" : "Drive link missing: PDF not uploaded yet") : f.kind || "");
  /** Right-hand card of a form: the print preview in its locked layout. RG-03 is drawn from this SOP's items; the others show their locked sample. */
  function formPreview(f, x) {
    const P = RB.plotForms, h = P.HEIGHT[f.code] || 800;
    if (f.code === "RG-03") {
      const items = (x.formItems || []).filter((i) => /^RG-03/.test(i.form)).map((i) => ({ stage: i.group, no: i.no, name: i.item }));
      return `<div class="card"><h3>Print preview</h3><div class="sub" style="margin-bottom:6px">Blank RG-03 as the app prints it. In a plot file, Items › Print RG-03 fills the plot, ticks and dates.</div><iframe class="sxpv" title="Print preview" style="height:${h}px" srcdoc="${esc(P.rg03(null, items))}"></iframe></div>`;
    }
    if (P.SAMPLES[f.code]) return `<div class="card"><h3>Print preview (plot 245, sample)</h3><div class="sub" style="margin-bottom:6px">The exact locked ${esc(f.code)} design. The app fills it from the plot file when this form's phone task is built.</div><iframe class="sxpv" title="Print preview" style="height:${h}px" src="${esc(P.SAMPLES[f.code])}"></iframe></div>`;
    return "";
  }
  function formsTab() {
    const x = X(), fs = x.forms || [];
    if (S.sopForm) {
      const f = fs.find((y) => y.code === S.sopForm); if (!f) { S.sopForm = null; return formsTab(); }
      const items = (x.formItems || []).filter((i) => i.form.indexOf(f.code) === 0), slips = (x.slips || []).filter((i) => i.slip.indexOf(f.code) === 0), used = x.steps.filter((s) => (s.forms || []).includes(f.code)).map((s) => s.id);
      const groups = Array.from(new Set(items.map((i) => i.group)));
      const pv = formPreview(f, x);
      return `<div class="sxfsec"><div class="card"><div class="subhead"><button class="link" data-sxform="">‹ All forms</button><div class="sp"></div>${f.link ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(f.link)}">Open in Drive ↗</a>` : ""}</div>
        <h3 style="margin:6px 0">${esc(f.code)} · ${esc(f.name)}</h3><div class="sub">${esc(f.kind || "")} · ${esc(KIND_SUB(f))}</div>${f.notes ? `<div class="hint" style="margin-top:8px">${esc(f.notes)}</div>` : ""}
        ${/Fixed/.test(f.kind || "") ? `<div class="hint" style="margin-top:8px">Fixed paper. The app does not change it. To change it, upload a new PDF to Drive and paste the new link in the sheet's Forms tab, then get changes. Every step that mentions ${esc(f.code)} opens this link, on the web and on the phone task.</div>` : ""}
        <div class="mh2" style="margin-top:12px">USED IN STEPS</div><div>${used.length ? used.map((u) => `<button class="sc" data-sxstepgo="${esc(u)}">${esc(u)}</button>`).join(" ") : '<span class="sub">none</span>'}</div>
        ${items.length ? `<div class="mh2" style="margin-top:12px">ITEMS</div>${groups.map((g) => `<div class="sxband"><span>${esc(g || "")}</span></div>${items.filter((i) => i.group === g).map((i) => `<div class="sxsrow"><b>${esc(String(i.no || ""))}</b><div class="w"><span class="t">${esc(i.item)}</span><span>${[i.type, i.cols && "columns: " + i.cols, i.total && "total row: " + i.total, i.money && "money: " + i.money, i.by && "lines entered by " + i.by, i.onlyIf && (/^only if/i.test(i.onlyIf) ? i.onlyIf : "only if " + i.onlyIf)].filter(Boolean).map(esc).join(" · ")}</span></div></div>`).join("")}`).join("")}` : ""}
        ${slips.length ? `<div class="mh2" style="margin-top:12px">WORDING</div><div class="sub" style="margin-bottom:4px">Blanks in { } are filled by the app.</div>${slips.map((r) => `<div class="sxsrow"><b>${esc(r.part)}</b><div class="w"><span class="t">${esc(r.text)}</span><span>${esc(r.by || "")}${r.notes ? " · " + esc(r.notes) : ""}</span></div></div>`).join("")}` : ""}</div>${pv}</div>`;
    }
    return `<div class="card"><div class="subhead"><h3>Forms</h3></div><div class="hint">Checklist and Slip are built and filled by the app. Fixed PDF opens the Google Drive link saved in the sheet's Forms tab.</div>
      ${fs.map((f) => `<div class="sxsrow" data-sxform="${esc(f.code)}"><b>${esc(f.code)}</b><div class="w"><span class="t">${esc(f.name)}</span><span ${/missing/.test(KIND_SUB(f)) ? 'style="color:var(--er)"' : ""}>${esc(KIND_SUB(f))}</span></div><span class="tag">${esc(f.kind || "")}</span><span class="sxgo">›</span></div>`).join("")}</div>`;
  }

  // ---- Variables tab
  function varsTab() {
    const x = X(), vs = x.variables || [], groups = Array.from(new Set(vs.map((v) => v.group || "Other")));
    const text = JSON.stringify(D.plotSopX), used = (c) => (c ? (text.match(new RegExp("\\{" + c.replace(/[^\w]/g, "") + "\\}", "g")) || []).length : 0);
    return `<div class="card"><div class="subhead"><h3>Variables</h3></div><div class="hint">A value is written once in the sheet's Variables tab. Step text uses it like {sitePlanDays}. Tap a group to open it.</div>
      ${groups.map((g) => { const o = S.sopBands["v" + g] !== false, gv = vs.filter((v) => (v.group || "Other") === g);
        return `<div class="sxgrp"><div class="sxband2" data-sxband="v${esc(g)}"><div class="w"><b>${esc(g)}</b></div><span class="sxcnt">${gv.length} variables</span><span class="sxchev">${o ? "▾" : "▸"}</span></div>
          ${o ? `<div class="sxbody">${gv.map((v) => `<div class="sxsrow"><div class="w"><span class="t">${esc(v.name)}</span><span>${v.code ? "{" + esc(v.code) + "} · used in " + used(v.code) + " places" : ""}${v.notes ? " · " + esc(v.notes) : ""}</span></div><b class="sxval">${esc(v.value)}</b><span class="sub" style="width:110px">${esc(v.unit || "")}</span></div>`).join("")}</div>` : ""}</div>`; }).join("") || '<div class="muted">No variables in this version.</div>'}</div>`;
  }

  // ---- Role SOPs tab (and the main SOP), with Print / Save PDF
  function rolesTab() {
    const x = X(), l = lang(), roles = x.roles || [];
    const cur = S.sopRole === "main" || roles.some((r) => r.role === S.sopRole) ? S.sopRole : null;
    const chips = `<button class="sc ${cur === "main" ? "on" : ""}" data-sxrole="main">Main SOP</button>${roles.map((r) => `<button class="sc ${cur === r.role ? "on" : ""}" data-sxrole="${esc(r.role)}">${esc(pick(r, "role", l))}</button>`).join("")}`;
    return `<div class="card"><div class="subhead"><h3>Role SOPs</h3><div class="sp"></div>${toggle(l)}${cur ? '<button class="btnp" data-sxprint="1">🖨 Print / Save PDF</button>' : ""}</div>
      <div class="hint">Built live from the steps. Nothing to update by hand. Staff see their own one in Docs › My reading list. Form codes are links. The main SOP is for team training.</div>
      <div class="sxroles">${chips}</div>${cur ? `<div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""} style="margin-top:12px">${body(cur, l, false)}</div>` : '<div class="muted" style="margin-top:12px">Pick the main SOP or a role.</div>'}</div>`;
  }

  // ---- SOP settings tab
  function settingsTab() {
    const x = X(), url = x.sheetUrl, f = (lab, v, hint) => `<div class="sxf"><b>${lab}</b><div>${v}${hint ? `<div class="sub">${hint}</div>` : ""}</div></div>`;
    return `<div class="card"><div class="subhead"><h3>About this SOP</h3></div>
      ${f("Name", esc(sopName()))}${f("Master Google Sheet", (url ? `<a class="link" target="_blank" rel="noopener noreferrer" href="${esc(url)}">Open the sheet ↗</a>` : '<span class="sub">The sheet does not send its address yet.</span>') + '<div style="margin-top:8px"></div>' + RB.plotSync.settingsLink(), "The SOP is written in this sheet. Versions › Get changes from Google Sheet reads it through this private link.")}
      ${f("Section", esc(info("Section", "en") || "—"))}${f("Daily work shows in", "Plot files menu", "Where staff do this SOP's day-to-day work (the plot files, steps, cheques). Fixed for now.")}
      ${f("Who can edit", "Publish: only you (owner)", "The SOP is changed in the Google Sheet. Only you can publish a new version.")}</div>
`;
  }

  /** The opened built SOP: header, tabs, the draft bar, and the tab. Staff see only Role SOPs. */
  P.page = () => {
    const x = X();
    const tabs = !x ? [["versions", "Versions"]] : own() ? [["steps", "Steps"], ["forms", "Forms"], ["vars", "Variables"], ["roles", "Role SOPs"], ["settings", "SOP settings"], ["versions", "Versions"]] : [["roles", "Role SOPs"]];
    if (!tabs.some((t) => t[0] === S.sopTab)) S.sopTab = tabs[0][0];
    const d = own() && RB.plotSync ? RB.plotSync.draft() : null, n = d ? RB.plotSync.diff(d).length : 0;
    const head = `<div class="sxhead"><button class="link" data-sxback="1">‹ All SOPs</button><div class="sxtitle"><h2>${esc(x ? sopName() : "New SOP")}</h2>${ver() ? `<span class="tag grn">Live v${ver()}</span>` : '<span class="tag">Not published yet</span>'}</div>
      <div class="tabs sxtabs">${tabs.map(([k, t]) => `<button data-sxtab="${k}" class="${k === S.sopTab ? "on" : ""}">${t}</button>`).join("")}</div>
      ${d && S.sopTab !== "versions" ? `<div class="sxdraft"><b>Draft v${d.version} · ${n} change${n === 1 ? "" : "s"}${(d.problems || []).length ? " · " + d.problems.length + " problems" : ""}</b><button class="link" data-sxtab="versions"><span class="sxlong">Open Versions to review and publish ›</span><span class="sxshort">Review ›</span></button></div>` : ""}</div>`;
    let b;
    if (S.sopTab === "versions") b = RB.plotSync.versionsTab() + (x ? '<div style="height:12px"></div>' + RB.plotSync.deleteCard() : "");
    else if (S.sopTab === "forms") b = RO + formsTab();
    else if (S.sopTab === "vars") b = RO + varsTab();
    else if (S.sopTab === "roles") b = rolesTab();
    else if (S.sopTab === "settings") b = settingsTab();
    else b = RO + (S.sopStep ? stepPage(S.sopStep) : stepsTab());
    return head + b;
  };
  P.newSop = () => RB.plotSync.newSop();

  /** Tiny read-only window on a plot file step: that step's SOP, English or Urdu. */
  P.how = (stepId) => {
    const x = X(), s = x && x.steps.find((y) => y.id === stepId), m = document.getElementById("modal"); if (!s || !m) return;
    const draw = (l) => {
      m.innerHTML = `<div class="back"><div class="dlg wide"><div class="subhead"><b style="font-size:17px">How to do this</b><div class="sp"></div>${toggle(l)}</div><div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""}>${stepBox(s, l, false)}</div><div style="text-align:right;margin-top:12px"><button class="btnp" id="sx-close">Close</button></div></div></div>`;
      m.querySelectorAll("[data-sxlang]").forEach((b) => (b.onclick = (ev) => { ev.preventDefault(); S.sopLang = b.dataset.sxlang; draw(S.sopLang); }));
      m.querySelector("#sx-close").onclick = () => (m.innerHTML = "");
    };
    draw(lang());
  };
  P.has = () => !!X();
  /** From the reading list: open my role's SOP. */
  P.openRole = (role) => { S.sopOpen = "plot"; S.sopTab = "roles"; S.sopRole = role; S.sopNew = false; };
  P.bind = (root) => {
    // Heights for the fixed bars: the SOP header (tabs + draft bar), the draft bar alone (phone) and the role SOP title.
    const hd = root.querySelector(".sxhead"), dr = root.querySelector(".sxdraft"), rt = root.querySelector(".sxpage>h1"), st = document.documentElement.style;
    if (hd) st.setProperty("--sxh", (hd.offsetHeight || 0) + "px");
    st.setProperty("--sxd", (dr ? dr.offsetHeight : 0) + "px");
    st.setProperty("--sxrt", (rt ? rt.offsetHeight : 0) + "px");
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
    const go = (patch) => { Object.assign(S, patch); RB.render(); const c = document.getElementById("content"); if (c) c.scrollTop = 0; };
    on("[data-sxopen]", () => go({ sopOpen: "plot", sopNew: false, sopStep: null, sopForm: null }));
    on("[data-sxback]", () => go({ sopOpen: null, sopNew: false, sopStep: null, sopForm: null }));
    on("[data-sxtab]", (e) => go({ sopTab: e.dataset.sxtab, sopStep: null, sopForm: null }));
    on("[data-sxlang]", (e) => { S.sopLang = e.dataset.sxlang; RB.render(); });
    on("[data-sxprint]", () => P.print(S.sopRole, lang()));
    on("[data-sxrole]", (e) => { S.sopRole = e.dataset.sxrole; RB.render(); });
    on("[data-sxband]", (e) => { const k = e.dataset.sxband; S.sopBands[k] = k[0] === "v" ? S.sopBands[k] === false : !S.sopBands[k]; RB.render(); });
    on("[data-sxall]", (e) => { const o = e.dataset.sxall === "1"; (X() ? Array.from(new Set(X().steps.map((s) => s.stage))) : []).forEach((g) => (S.sopBands[g] = o)); RB.render(); });
    on("[data-sxstep]", (e) => go({ sopStep: e.dataset.sxstep }));
    on("[data-sxstepgo]", (e) => go({ sopTab: "steps", sopStep: e.dataset.sxstepgo, sopForm: null }));
    on("[data-sxsteps]", () => go({ sopStep: null }));
    on("[data-sxform]", (e) => go({ sopForm: e.dataset.sxform || null }));
    const pk = root.querySelector("#sx-pick"); if (pk) pk.onchange = () => go({ sopStep: pk.value });
    if (RB.plotSync && own()) RB.plotSync.bind(root);
  };
})();
