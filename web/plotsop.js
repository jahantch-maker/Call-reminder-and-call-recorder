/* Plot files SOP pages: the written SOP built live from the published Google Sheet (config/plotsopx).
 * Role SOP pages and the main SOP, English or Urdu, "Print / Save PDF", the "How to do this" window on a step, and clickable form chips.
 * Shown in Docs > SOPs (everyone with Docs access). Nothing here is typed by hand: publish a new version from the sheet and every page follows.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const P = (RB.plotSop = {});

  const X = () => (D.plotSopX && D.plotSopX.steps ? D.plotSopX : null);
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

  // ------------------------------------------------------------------ Docs > SOPs
  const toggle = (l) => `<span class="sxlang"><button class="sc ${l === "en" ? "on" : ""}" data-sxlang="en">English</button><button class="sc ${l === "ur" ? "on" : ""}" data-sxlang="ur">اردو</button></span>`;
  /** The built SOP in Docs > SOPs: a landing card (main SOP and a button for each role) or the page that was opened. */
  P.docs = () => {
    const x = X();
    if (!x) return RB.isOwner() ? `<div class="card"><h3>Plot files SOP</h3><div class="muted">The written SOP pages appear here after you publish a version from the Google Sheet (Plot files > SOP versions > Get changes from Google Sheet).</div></div>` : "";
    const l = lang(), meta = `Version ${esc(String(D.plotSopX.version || ""))} · published ${esc(when())}`;
    if (S.sopPage && (S.sopPage === "main" || (x.roles || []).some((r) => r.role === S.sopPage))) {
      return `<div class="card"><div class="subhead sxbar"><button class="btnt" data-sxback="1">‹ All SOPs</button><div class="sp"></div>${toggle(l)}<button class="btnp" data-sxprint="1">Print / Save PDF</button></div><div class="muted" style="margin:6px 0">${meta}</div><div class="sxpage ${l}" ${l === "ur" ? 'dir="rtl"' : ""}>${body(S.sopPage, l, false)}</div></div>`;
    }
    return `<div class="card"><div class="subhead"><h3>Plot files SOP</h3><div class="sp"></div>${toggle(l)}</div><div class="muted" style="margin:0 0 8px">${meta}. Opens in English or Urdu, and every page can be printed or saved as a PDF.</div>
      <div class="docrow"><div class="w"><b>Main SOP (for team training)</b><span>Purpose, the 8 stages, every role in one line, every step in one line, forms</span></div><button class="btnp" data-sxopen="main">Open</button></div>
      <div class="lbl" style="margin-top:10px">ROLE SOPs · TAP YOUR ROLE</div><div class="sxroles">${(x.roles || []).map((r) => `<button class="sc" data-sxopen="${esc(r.role)}">${esc(pick(r, "role", l))}</button>`).join("")}</div></div>`;
  };
  /** Tiny read-only window: one step's SOP, English or Urdu. */
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
  P.bind = (root) => {
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
    on("[data-sxopen]", (e) => { S.sopPage = e.dataset.sxopen; RB.render(); });
    on("[data-sxback]", () => { S.sopPage = null; RB.render(); });
    on("[data-sxlang]", (e) => { S.sopLang = e.dataset.sxlang; RB.render(); });
    on("[data-sxprint]", () => P.print(S.sopPage, lang()));
  };
})();
