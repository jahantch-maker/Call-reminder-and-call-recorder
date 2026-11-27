/* Plot files: "Get changes from Google Sheet". The master SOP is the Google Sheet "Plot files SOP · master".
 * Pull = read the sheet through its private Apps Script link, build a DRAFT, show What changed + Checks, then Publish a new version.
 * Data: config/plotsop (the published SOP, read by everyone), plotsopv/{draft | settings | v1, v2...} (owner only).
 * Nothing of the SOP is in the code: until a version is published, Plot files has no steps and cannot open files.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const SYNC = (RB.plotSync = {});
  let busy = false, msg = "";

  const pubVer = () => (D.plotSop && D.plotSop.version) || 0;
  const vdoc = (id) => (D.plotSopV || []).find((x) => x.id === id);

  // ------------------------------------------------------------------ reading the sheet (master sheet v2, header on row 4 of every tab)
  const cell = (x) => (x === undefined || x === null ? "" : String(x).trim());
  /** One tab (rows as arrays, header first) to objects keyed by the header text. __row = the sheet row number. */
  function table(rows) {
    if (!rows || !rows.length) return [];
    const heads = rows[0].map(cell);
    return rows.slice(1).map((r, i) => { const o = { __row: i + 5 }; heads.forEach((h, c) => h && (o[h] = cell(r[c]))); return o; }).filter((o) => heads.some((h) => h && o[h] !== ""));
  }
  const list = (t) => cell(t).split(/[,;\n]+/).map((x) => x.trim()).filter((x) => x && !/^(—|-|none)$/i.test(x));   // "—" or "None" = nothing
  const yes = (t) => /^yes/i.test(cell(t));
  const PLOT_OWNER = "Plot owner";   // the only name allowed in Call / WhatsApp lists that is not a role
  const TYPES = ["Tick", "Pick one", "Option", "Number box"];
  /** "6.1 · 6b" -> { step: "6.1", line: "6b" } */
  const ref = (t) => { const m = cell(t).match(/^(\d+\.\d+)\s*[·.\-]\s*(\w+)$/); return m ? { step: m[1], line: m[2] } : null; };

  SYNC.parse = (json) => {
    const T = json.tabs || {}, steps = table(T.Steps), chk = table(T.Checklist), wrong = table(T["If something goes wrong"]);
    const p = { sheet: json.sheet || "", __steps: steps, __chk: chk, __wrong: wrong, __flow: table(T.Flow), __missing: ["Steps", "Checklist", "If something goes wrong", "Flow", "Fields", "Stages", "Roles", "Forms", "RG-03 list", "Words", "SOP info"].filter((n) => !T[n]) };
    p.stages = table(T.Stages).filter((g) => g["Stage no."]).map((g) => ({ no: g["Stage no."], name: g["Name (English)"], nameUr: g["Name (Urdu)"] }));
    p.roles = table(T.Roles).filter((r) => r.Role).map((r) => ({ code: r.Code, role: r.Role, roleUr: r["Role (Urdu)"], __row: r.__row }));
    p.forms = table(T.Forms).filter((f) => f.Code).map((f) => ({ code: f.Code, name: f["Form name (English)"], nameUr: f["Form name (Urdu)"], printed: f["Printed as in steps"], link: f["Google Drive link"], __row: f.__row }));
    p.words = table(T.Words).filter((w) => w.Word).map((w) => ({ w: w.Word, m: w["What it means"], wUr: w["Word (Urdu)"], mUr: w["What it means (Urdu)"] }));
    p.info = table(T["SOP info"]).filter((r) => r.Field).map((r) => ({ k: r.Field, en: r.English, ur: r.Urdu }));
    p.fields = table(T.Fields).filter((r) => r.English).map((r) => ({ where: r.Where, kind: r.Kind, en: r.English, ur: r.Urdu }));
    p.rg03 = table(T["RG-03 list"]).filter((r) => r["Document (English)"]).map((r) => ({ no: r["No."], en: r["Document (English)"], ur: r["Document (Urdu)"], step: r["Ticked in step"] }));
    p.flow = p.__flow.filter((r) => r["From step"]).map((r) => ({ from: r["From step"], when: r.When, onlyIf: r["Only if"], to: r["Opens step"], waits: r["Also waits for"], note: r.Note, __row: r.__row }));
    p.steps = steps.filter((r) => r["Step no."]).map((r) => {
      const w = wrong.find((x) => x["Step no."] === r["Step no."]) || {};
      return {
        id: r["Step no."], stage: r.Stage, role: r["Who does it"], title: r["Title (English)"], titleUr: r["Title (Urdu)"],
        timer: r["Timer type"], days: r.Days === "" ? null : Number(r.Days), hours: r.Hours === undefined || r.Hours === "" ? null : Number(r.Hours),
        starts: r["Starts when (English)"], startsUr: r["Starts when (Urdu)"], next: Array.from(new Set(list(r["Next step(s)"]).map((x) => (x.match(/^\d+\.\d+/) || [])[0]).filter(Boolean))), side: /side step/i.test(r["Next step(s)"] || ""),   // "3.1 (if 2.2 · 3a picked)" -> 3.1; "None" -> nothing
        recv: r["Receive / Return"], returnTo: r["Return goes to"], canPause: yes(r["Can pause (Waiting for plot owner)"]), pauseStarts: r["Pause starts"], reminder: r["Reminder while paused"],
        doneWithoutAll: yes(r["Done allowed without all ticks"]), ask: list(r["Who to ask"]),
        notify: { start: list(r["Notify on start"]), done: list(r["Notify when done"]), late: list(r["Notify when late"]) },
        lines: r.Lines === "" ? null : Number(r.Lines), call: list(r["Call list"]), wa: list(r["WhatsApp list"]), waMsg: r["WhatsApp message"], forms: list(r["Forms used"]),
        wrong: w["If something goes wrong (English)"] || "", wrongUr: w["If something goes wrong (Urdu)"] || "", wrongShort: w["Short (English)"] || "", wrongShortUr: w["Short (Urdu)"] || "",
        checklist: chk.filter((c) => c["Step no."] === r["Step no."]).map((c) => ({ no: c.Line, type: c.Type, en: c["Checklist line (English)"], ur: c["Checklist line (Urdu)"], onlyIf: c["Shown only if (step · line+option)"], opens: c["Opens step"], detail: c["Detail (English)"], detailUr: c["Detail (Urdu)"], photo: /^\s*yes\s*$/i.test(c["Photo needed"] || "") })),
        row: r.__row,
      };
    });
    p.sheetUrl = /^https:\/\/docs\.google\.com\//.test(json.url || "") ? json.url : "";
    p.gids = json.gids && typeof json.gids === "object" ? json.gids : {};
    return p;
  };

  // ------------------------------------------------------------------ checks: anything wrong blocks Publish
  SYNC.checks = (p) => {
    const errs = [], add = (tab, row, id, col, text) => errs.push({ tab, row, id, col, text });
    p.__missing.forEach((n) => add(n, 4, "-", "-", "The tab '" + n + "' is missing (or the sheet script does not send it). Check its name, then update the script."));
    const nos = {}, roleSet = new Set(p.roles.map((r) => r.role)), stageSet = new Set(p.stages.map((g) => String(g.no))), formSet = new Set(p.forms.map((f) => f.code));
    const isRole = (x) => roleSet.has(x), isStep = (x) => !!nos[x];
    p.steps.forEach((s) => { if (nos[s.id]) add("Steps", s.row, s.id, "Step no.", "The step number " + s.id + " is used twice (also on row " + nos[s.id] + ")."); else nos[s.id] = s.row; });
    const urdu = (tab, row, id, col, en, ur) => { if (en && !ur) add(tab, row, id, col + " (Urdu)", "The English is filled but the Urdu is empty."); };
    p.steps.forEach((s) => {
      const A = (col, text) => add("Steps", s.row, s.id, col, text);
      if (!s.title) A("Title (English)", "The title is empty.");
      urdu("Steps", s.row, s.id, "Title", s.title, s.titleUr); urdu("Steps", s.row, s.id, "Starts when", s.starts, s.startsUr);
      if (!s.role) A("Who does it", "No role is set."); else if (!isRole(s.role)) A("Who does it", "'" + s.role + "' is not in the Roles tab.");
      if (!stageSet.has(String(s.stage))) A("Stage", "Stage '" + s.stage + "' is not in the Stages tab.");
      if (s.days !== null && !(s.days >= 0 && s.days <= 60)) A("Days", "Days must be a number from 0 to 60, or empty.");
      if (s.hours !== null && !(s.hours >= 1 && s.hours <= 10)) A("Hours", "Hours must be a number from 1 to 10, or empty.");
      if (!/^working (hours|days)$/i.test(s.timer || "")) A("Timer type", "Must be 'Working hours' or 'Working days'.");
      if (/^working days$/i.test(s.timer || "") && !(s.days >= 1)) A("Days", "Timer type is 'Working days', so write how many days in Days.");
      if (s.days !== null && /^working hours$/i.test(s.timer || "")) A("Days", "Days is only used when Timer type is 'Working days'.");
      if (/working hours/i.test(s.timer || "") && s.hours === null) A("Hours", "Timer type is 'Working hours', so write how many hours in Hours.");
      if (s.hours !== null && !/working hours/i.test(s.timer || "")) A("Hours", "Hours is only used when Timer type is 'Working hours'.");
      if (!["Receive only", "Receive / Return"].includes(s.recv)) A("Receive / Return", "Must be 'Receive only' or 'Receive / Return'.");
      if (s.recv === "Receive / Return" && !isRole(s.returnTo)) A("Return goes to", "'" + (s.returnTo || "empty") + "' is not in the Roles tab.");
      [["Who to ask", s.ask], ["Notify on start", s.notify.start], ["Notify when done", s.notify.done], ["Notify when late", s.notify.late]].forEach(([col, a]) => a.forEach((x) => { if (!isRole(x)) A(col, "'" + x + "' is not in the Roles tab."); }));
      [["Call list", s.call], ["WhatsApp list", s.wa]].forEach(([col, a]) => a.forEach((x) => { if (x !== PLOT_OWNER && !isRole(x)) A(col, "'" + x + "' is not in the Roles tab (or 'Plot owner')."); }));
      s.next.forEach((x) => { if (!isStep(x)) A("Next step(s)", "Step " + x + " does not exist."); });
      s.forms.forEach((x) => { if (!formSet.has(x)) A("Forms used", "Form " + x + " is not in the Forms tab."); });
      const nLines = s.checklist.filter((c) => c.type !== "Option").length;   // Lines counts the lines, not the options under a Pick one
      if (s.lines !== null && s.lines !== nLines) A("Lines", "Says " + s.lines + " lines but the Checklist tab has " + nLines + " (options not counted).");
      if (s.wrong && !s.wrongShort) add("If something goes wrong", (p.__wrong.find((w) => w["Step no."] === s.id) || {}).__row, s.id, "Short (English)", "The long text is filled but the short line is empty.");
      const wr = (p.__wrong.find((w) => w["Step no."] === s.id) || {}).__row;
      urdu("If something goes wrong", wr, s.id, "If something goes wrong", s.wrong, s.wrongUr); urdu("If something goes wrong", wr, s.id, "Short", s.wrongShort, s.wrongShortUr);
    });
    p.__chk.forEach((c) => {
      const id = c["Step no."], A = (col, text) => add("Checklist", c.__row, id || "?", col, text);
      if (!isStep(id)) return A("Step no.", "Step " + (id || "(empty)") + " is not in the Steps tab.");
      if (!c.Line) A("Line", "The line number is empty.");
      else if (p.__chk.some((o) => o !== c && o["Step no."] === id && o.Line === c.Line)) A("Line", "Line " + c.Line + " is used twice in step " + id + ".");
      if (!TYPES.includes(c.Type)) A("Type", "Must be one of: " + TYPES.join(", ") + ".");
      if (!c["Checklist line (English)"]) A("Checklist line (English)", "The line is empty.");
      urdu("Checklist", c.__row, id, "Checklist line", c["Checklist line (English)"], c["Checklist line (Urdu)"]); urdu("Checklist", c.__row, id, "Detail", c["Detail (English)"], c["Detail (Urdu)"]);
      const si = c["Shown only if (step · line+option)"];
      if (si) { const r = ref(si); if (!r || !p.__chk.some((o) => o["Step no."] === r.step && o.Line === r.line && o.Type === "Option")) A("Shown only if (step · line+option)", "'" + si + "' is not an Option line (write it like 6.1 · 6b)."); }
      if (c["Opens step"] && !isStep(c["Opens step"])) A("Opens step", "Step " + c["Opens step"] + " does not exist.");
    });
    p.__wrong.forEach((w) => { if (w["Step no."] && !isStep(w["Step no."])) add("If something goes wrong", w.__row, w["Step no."], "Step no.", "Step " + w["Step no."] + " is not in the Steps tab."); });
    p.flow.forEach((f) => {
      const A = (col, text) => add("Flow", f.__row, f.from, col, text);
      if (!isStep(f.from)) A("From step", "Step " + f.from + " does not exist.");
      if (!isStep(f.to)) A("Opens step", "Step " + (f.to || "(empty)") + " does not exist.");
      (f.waits.match(/\d+\.\d+/g) || []).forEach((w) => { if (!isStep(w)) A("Also waits for", "Step " + w + " does not exist."); });
      if (f.onlyIf && !ref(f.onlyIf)) A("Only if", "'" + f.onlyIf + "' must be written like 6.4 · 3b.");
    });
    p.roles.forEach((r) => urdu("Roles", r.__row, r.role, "Role", r.role, r.roleUr));
    p.forms.forEach((f) => { urdu("Forms", f.__row, f.code, "Form name", f.name, f.nameUr); if (f.link && !/^https:\/\//.test(f.link)) add("Forms", f.__row, f.code, "Google Drive link", "The link must start with https://"); });
    return errs;
  };
  /** Not blocking: roles that are not designations in Team yet (no tasks for them until added), and forms with no Drive link (their code cannot open). */
  SYNC.notice = (p) => {
    const have = new Set(RB.desigList().map((d) => d.name)), out = [];
    const roles = Array.from(new Set(p.steps.map((s) => s.role).filter(Boolean))).filter((r) => !have.has(r));
    if (roles.length) out.push("These roles are not designations in Team yet, so tasks cannot be given to them until you add them: " + roles.join(", ") + ".");
    // FLOW1 a step whose Done opens nothing and that no step waits for lets the file run ahead without it.
    const waited = new Set(p.flow.flatMap((f) => f.waits.match(/\d+\.\d+/g) || []));
    const dead = p.steps.slice(0, -1).filter((s) => !s.side && !waited.has(s.id) && !p.flow.some((f) => f.from === s.id) && !(s.checklist || []).some((c) => c.opens)).map((s) => s.id);
    if (dead.length) out.push("These steps open nothing and no step waits for them, so the file can move on without them: " + dead.join(", ") + ". Add a row in the Flow tab, or write \"None · side step\" in Next step(s) if that is on purpose.");
    const nolink = p.forms.filter((f) => !f.link).map((f) => f.code);
    if (nolink.length) out.push("These forms have no Google Drive link yet, so their code cannot open the PDF: " + nolink.join(", ") + ".");
    return out;
  };

  // ------------------------------------------------------------------ what changed (against the live version)
  const FIELDS = [["role", "Who does it"], ["title", "Title"], ["stage", "Stage"], ["timer", "Timer type"], ["days", "Days"], ["hours", "Hours"], ["next", "Next step(s)"], ["recv", "Receive / Return"], ["returnTo", "Return goes to"], ["canPause", "Can pause"], ["doneWithoutAll", "Done without all ticks"], ["ask", "Who to ask"], ["notify", "Notify"], ["call", "Call list"], ["wa", "WhatsApp list"], ["forms", "Forms used"]];
  const TEXTS = [["checklist", "Checklist"], ["waMsg", "WhatsApp message"], ["wrong", "If something goes wrong"], ["wrongShort", "Short 'If something goes wrong'"], ["starts", "Starts when"]];
  /** JSON with keys sorted: Firestore gives keys back in another order, which must not count as a change. */
  const same = (v) => JSON.stringify(v === undefined ? "" : v, (k, x) => (x && typeof x === "object" && !Array.isArray(x) ? Object.keys(x).sort().reduce((o, y) => ((o[y] = x[y]), o), {}) : x));
  const show = (v) => (Array.isArray(v) ? v.join(" · ") || "none" : v && typeof v === "object" ? Object.entries(v).filter(([, x]) => !Array.isArray(x) || x.length).map(([k, x]) => k + ": " + x).join("; ") || "none" : v === null || v === undefined || v === "" ? "none" : String(v));
  SYNC.diff = (draft) => {
    const live = (D.plotSop && D.plotSop.steps) || [], old = {}, now = {}, ch = [];
    live.forEach((s) => (old[s.id] = s)); draft.steps.forEach((s) => (now[s.id] = s));
    draft.steps.forEach((s) => {
      const o = old[s.id];
      if (!o) { ch.push({ kind: "new", id: s.id, text: "New step: " + s.title }); return; }
      const f = FIELDS.filter(([k]) => same(o[k]) !== same(s[k])).map(([k, l]) => l + ": " + show(o[k]) + " → " + show(s[k]))
        .concat(TEXTS.filter(([k]) => same(o[k] || "") !== same(s[k] || "")).map(([, l]) => l + " changed"));
      if (f.length) ch.push({ kind: "chg", id: s.id, text: s.title + " · " + f.join("; ") });
    });
    live.forEach((o) => { if (!now[o.id]) ch.push({ kind: "gone", id: o.id, text: "Step removed: " + o.title }); });
    if (D.plotSop && same(D.plotSop.flow || []) !== same(draft.flow)) ch.push({ kind: "chg", id: "Flow", text: "Flow tab changed" });
    if (D.plotSop && same(D.plotSop.stages || []) !== same(draft.stages)) ch.push({ kind: "chg", id: "Stages", text: "Stage names changed" });
    return ch;
  };

  // ------------------------------------------------------------------ pull and publish
  const strip = (a) => a.map((o) => { const x = Object.assign({}, o); delete x.__row; return x; });
  async function pull() {
    const st = vdoc("settings"), link = st && st.link;
    if (!link) return;
    busy = true; msg = "Reading the Google Sheet…"; RB.render();
    try {
      let json;
      try { const r = await fetch(link, { redirect: "follow" }); json = await r.json(); }
      catch (e) { throw new Error("Could not reach the sheet link. Check the link and that the web app is deployed with access 'Anyone'."); }
      if (!json.ok) throw new Error(json.error || "The sheet link answered with an error.");
      const p = SYNC.parse(json);
      const draft = { version: pubVer() + 1, pulledAt: Date.now(), by: RB.uidOf(), sheet: p.sheet, steps: p.steps, flow: strip(p.flow), stages: p.stages, roles: strip(p.roles), forms: strip(p.forms), words: p.words, info: p.info, fields: p.fields, rg03: p.rg03, sheetUrl: p.sheetUrl, gids: p.gids, problems: SYNC.checks(p), notice: SYNC.notice(p) };
      await RB.Store.put("plotsopv", "draft", draft);
      msg = "";
    } catch (e) { msg = String(e.message || e); }
    busy = false; RB.render();
  }
  async function publish() {
    const d = vdoc("draft"); if (!d || (d.problems || []).length) return;
    const n = pubVer() + 1, now = Date.now(), full = Object.assign({}, d, { version: n, publishedAt: now, publishedBy: RB.uidOf() });
    delete full.id; delete full.problems; delete full.notice;
    await RB.Store.put("plotsopv", "v" + n, full);
    await RB.Store.put("config", "plotsop", full);   // the one published SOP: everything the web and the phone read
    // Must read: the built SOP is one entry of the reading list, for every role in the SOP. A new version asks everyone to read again.
    const nameOf = (k) => ((d.info || []).find((i) => i.k === k) || {}).en || "";
    await RB.Store.put("docs", "plotsop", { type: "sop", built: "plot", title: nameOf("SOP name") || "Plot files SOP", section: nameOf("Section") || "", version: n, url: "", designations: (d.roles || []).map((r) => r.role), owner: "Owner", at: now });
    await RB.Store.del("plotsopv", "draft");
    await RB.log("Plot files SOP version " + n + " published from the Google Sheet");
    RB.toast("Version " + n + " published");
  }

  // ------------------------------------------------------------------ pieces of Docs > SOPs (owner only): New SOP, Versions, SOP settings
  const when = (t) => (t ? RB.fmtWhen(t) : "—");
  SYNC.draft = () => vdoc("draft");
  SYNC.link = () => (vdoc("settings") || {}).link || "";
  SYNC.sheetUrl = () => (D.plotSop && D.plotSop.sheetUrl) || (vdoc("draft") || {}).sheetUrl || "";
  const linkBox = (btn) => `<div class="pfnote"><input id="ps-link" placeholder="Paste the sheet's private link (web app URL ending in ?key=…)" value="${esc(SYNC.link())}"><button class="btnt" id="ps-save">${btn}</button></div>${msg ? `<div class="hint warn" style="margin-top:8px">${esc(msg)}</div>` : ""}`;

  /** New SOP from a Google Sheet: paste the link, the app reads every tab and makes a draft (then Versions shows What changed and Publish). */
  SYNC.newSop = () => `<div class="card"><div class="subhead"><h3>New SOP from a Google Sheet</h3></div>
    <div class="hint">Paste the private link of the master Google Sheet that Claude prepared. The app reads every tab: steps, checklist, flow, roles, stages, forms, words, English and Urdu. It becomes a draft. Staff see nothing until you publish.</div>
    ${linkBox("Save link")}<div class="pfact">${SYNC.link() ? `<button class="btnp" id="ps-pull" ${busy ? "disabled" : ""}>Read the sheet and make a draft</button>` : '<span class="sub">Save the link first. The one-time setup steps are at the top of sync-web-app.gs in the project files.</span>'}<button class="btnt" data-sxback="1">Cancel</button></div></div>`;

  /** The draft card: counts, problems (block Publish), notice, What changed, and the fixed Publish / Discard bar. */
  function draftCard(d) {
    const ch = SYNC.diff(d), bad = d.problems || [], cnt = (k) => ch.filter((c) => c.kind === k).length;
    let h = `<div class="card"><div class="subhead"><h3>Draft for version ${d.version}</h3><div class="sp"></div><span class="sub">read from the sheet ${esc(when(d.pulledAt))}</span></div>
      <div class="pfchips2"><span class="pfchip"><b>${ch.length}</b> changes</span><span class="pfchip blu"><b>${cnt("new")}</b> new</span><span class="pfchip yel"><b>${cnt("chg")}</b> changed</span><span class="pfchip"><b>${cnt("gone")}</b> removed</span><span class="pfchip ${bad.length ? "red" : ""}"><b>${bad.length}</b> problems</span></div>`;
    if (bad.length) {
      h += `<div class="mh2" style="margin-top:10px">CHECKS · PUBLISH IS BLOCKED UNTIL THESE ARE FIXED IN THE SHEET</div>${bad.slice(0, 80).map((p) => `<div class="pfev"><b>${esc(p.tab)} tab · row ${esc(String(p.row))} · step ${esc(p.id)} · column “${esc(p.col)}”</b><div>${esc(p.text)}</div></div>`).join("")}${bad.length > 80 ? `<div class="sub">…and ${bad.length - 80} more. Copy problems has all of them.</div>` : ""}
        <div class="pfact"><button class="btnt" id="ps-copy">Copy problems</button></div><div class="foot">Screenshot this or paste the copied list to Claude in the project thread. Claude fixes the sheet, then you press Get changes again.</div>`;
    } else h += `<div class="mh2" style="margin-top:10px">CHECKS</div><div class="pfev">✓ No problems found.</div>`;
    if ((d.notice || []).length) h += `<div class="hint" style="margin-top:10px"><b>Not blocking:</b> ${d.notice.map(esc).join("<br>")}</div>`;
    h += `<div class="mh2" style="margin-top:10px">WHAT CHANGED</div>${ch.slice(0, 80).map((c) => `<div class="pfev"><span class="tag ${c.kind === "new" ? "blu" : c.kind === "gone" ? "red" : "yel"}">${c.kind === "new" ? "new" : c.kind === "gone" ? "removed" : "changed"}</span> <b>${esc(c.id)}</b> · ${esc(c.text)}</div>`).join("") || '<div class="muted">Nothing is different from the live version.</div>'}${ch.length > 80 ? `<div class="sub">…and ${ch.length - 80} more.</div>` : ""}
      <div class="foot">Plot files that are already running keep the days and role of steps they have started. Steps they have not reached yet follow the new version.</div>
      </div><div class="pin"><button class="btnp" id="ps-pub" ${bad.length ? "disabled" : ""}>Publish version ${d.version}</button><button class="btnt" id="ps-drop">Discard draft</button>${bad.length ? '<span class="sub">Fix the problems in the sheet, then press Get changes again.</span>' : ""}</div>`;
    return h;
  }
  /** Versions tab: the draft on top (review and publish here only), the versions, and the master Google Sheet. */
  SYNC.versionsTab = () => {
    const d = vdoc("draft"), v = pubVer(), vs = (D.plotSopV || []).filter((x) => /^v\d+$/.test(x.id)).sort((a, b) => b.version - a.version), url = SYNC.sheetUrl();
    let h = d ? draftCard(d) + '<div style="height:12px"></div>' : "";
    h += `<div class="card"><div class="subhead"><h3>Versions</h3></div>${vs.map((x) => `<div class="pfitem"><div class="w"><b>v${x.version} ${x.version === v ? '<span class="tag grn">Live</span>' : ""}</b><span>Read from the Google Sheet ${esc(when(x.pulledAt))} · published ${esc(when(x.publishedAt))} · ${x.steps.length} steps</span></div></div>`).join("") || '<div class="muted">Nothing published yet.</div>'}</div>
      <div style="height:12px"></div><div class="card"><div class="subhead"><h3>Make the next version</h3></div>
      <div class="pfitem"><div class="w"><b>Master Google Sheet</b><span>The SOP is written in its master Google Sheet. When it is ready, read it here: the app makes a draft and shows What changed and the checks.</span></div></div>
      <div class="pfact">${url ? `<a class="btnt" target="_blank" rel="noopener noreferrer" href="${esc(url)}">Open the sheet ↗</a>` : ""}${SYNC.link() ? `<button class="btnp" id="ps-pull" ${busy ? "disabled" : ""}>Get changes from Google Sheet</button>` : '<span class="sub">The sheet link is not saved. Save it in SOP settings.</span>'}</div>${msg ? `<div class="hint warn" style="margin-top:8px">${esc(msg)}</div>` : ""}</div>`;
    return h;
  };
  /** SOP settings: the private sheet link and deleting the imported SOP. */
  SYNC.settingsLink = () => linkBox("Save link");
  SYNC.deleteCard = () => `<div class="card"><div class="subhead"><h3>Delete this SOP</h3></div><div class="hint">Deletes every imported version, any draft and its reading-list entry, so you can import again from a clean start. The sheet and its link stay. Plot files already opened are kept, but new files cannot be opened until an SOP is published again.</div>
      <div class="pfact"><button class="btnt" id="ps-del" style="color:var(--er)">Delete imported SOP (all versions)</button></div></div>`;

  SYNC.bind = (root) => {
    const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
    on("#ps-save", async () => {
      const v = document.getElementById("ps-link").value.trim();
      if (v && !/^https:\/\/script\.google(usercontent)?\.com\//.test(v) && !(RB.DEMO && /^http:\/\/localhost/.test(v))) return RB.alertBox("The link must start with https://script.google.com/ and end with ?key=…");
      await RB.Store.put("plotsopv", "settings", { link: v }); RB.toast("Link saved"); RB.render();
    });
    on("#ps-pull", async () => { await pull(); if (vdoc("draft")) { S.sopOpen = "plot"; S.sopTab = "versions"; RB.render(); } });
    on("#ps-pub", () => { const d = vdoc("draft"); RB.confirm("Publish version " + d.version + "? Steps not yet reached by running plot files will follow it.", "Publish", publish); });
    on("#ps-drop", () => RB.confirm("Discard this draft? The live version stays as it is.", "Discard", async () => { await RB.Store.del("plotsopv", "draft"); RB.render(); }, true));
    on("#ps-copy", () => {
      const d = vdoc("draft"), t = "Draft for version " + d.version + ": " + d.problems.length + " problems\n" + d.problems.map((p) => p.tab + " tab, row " + p.row + ", step " + p.id + ", column '" + p.col + "': " + p.text).join("\n");
      (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(() => RB.toast("Problems copied"), () => RB.alertBox("<pre style='white-space:pre-wrap;text-align:left'>" + esc(t) + "</pre>"));
    });
    on("#ps-del", () => RB.confirm("Delete the imported SOP, all its versions, the draft and its reading-list entry? This cannot be undone. You can import again from the Google Sheet.", "Delete", async () => {
      for (const x of (D.plotSopV || [])) if (x.id !== "settings") await RB.Store.del("plotsopv", x.id);
      await RB.Store.del("config", "plotsop"); await RB.Store.del("config", "plotsopx"); await RB.Store.del("docs", "plotsop");
      await RB.log("Plot files SOP deleted (all versions)"); D.plotSop = null; D.plotSopX = null; S.sopOpen = null; RB.toast("Imported SOP deleted"); RB.render();
    }, true));
  };
})();
