/* Plot files: "Get changes from Google Sheet". The master SOP is the Google Sheet "Plot files SOP · master".
 * Pull = read the sheet through its private Apps Script link, build a DRAFT, show What changed + Checks, then Publish a new version.
 * Own module (loads before plotfiles.js). Data: config/plotsop (the published SOP, read by everyone), plotsopv/{draft | settings | v1, v2...} (owner only).
 * Until the first publish the built-in register in plotsteps.js is used (version 0).
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const STEPS = (window.PLOT_STEPS = window.PLOT_STEPS || []), STAGES = (window.PLOT_STAGES = window.PLOT_STAGES || {});
  const BUILTIN = { steps: JSON.parse(JSON.stringify(STEPS)), stages: Object.assign({}, STAGES) };
  const SYNC = (RB.plotSync = {});
  let busy = false, msg = "", shownVer = null;

  const pubVer = () => (D.plotSop && D.plotSop.version) || 0;
  const vdoc = (id) => (D.plotSopV || []).find((x) => x.id === id);

  /** Make the live register match the published version (in place, so every module that holds the arrays sees it). */
  SYNC.apply = () => {
    const v = pubVer();
    if (shownVer === v + ":" + ((D.plotSop || {}).publishedAt || 0)) return;
    shownVer = v + ":" + ((D.plotSop || {}).publishedAt || 0);
    const src = v && D.plotSop.steps ? D.plotSop : BUILTIN;
    STEPS.length = 0; src.steps.forEach((s) => STEPS.push(s));
    Object.keys(STAGES).forEach((k) => delete STAGES[k]); Object.assign(STAGES, src.stages);
  };

  // ------------------------------------------------------------------ reading the sheet
  const cell = (x) => (x === undefined || x === null ? "" : String(x).trim());
  /** One tab (rows as arrays, header first) to objects. A header that appears twice (English, then Urdu) gets "·ur" the second time. */
  function table(rows) {
    if (!rows || !rows.length) return [];
    const seen = {}, heads = rows[0].map((h) => { h = cell(h); const n = (seen[h] = (seen[h] || 0) + 1); return n > 1 ? h + "·ur" : h; });
    return rows.slice(1).map((r, i) => { const o = { __row: i + 5 }; heads.forEach((h, c) => (o[h] = cell(r[c]))); return o; }).filter((o) => heads.some((h) => h && o[h] !== ""));
  }
  const list = (t) => cell(t).split(/[,;\n]+/).map((x) => x.replace(/\(.*?\)/g, "").trim()).filter(Boolean);
  const lines = (t) => cell(t).split(/\n+/).map((x) => x.replace(/^\s*\d+\)\s*/, "").trim()).filter(Boolean);

  SYNC.parse = (json) => {
    const T = json.tabs || {}, steps = table(T.Steps), stages = table(T.Stages), roles = table(T.Roles), forms = table(T.Forms), fitems = table(T["Form items"]);
    const out = { sheet: json.sheet || "", stages: {}, roles: roles.map((r) => r.Role).filter(Boolean), formCodes: forms.map((f) => f.Code).filter(Boolean), steps: [], items: [], __steps: steps, __stages: stages };
    stages.forEach((g) => { if (g["Stage no."]) out.stages[g["Stage no."]] = g.Name; });
    steps.forEach((r) => {
      out.steps.push({
        sid: r.ID, id: r["Step no."], stage: Number(r.Stage) || 0, role: r["Who does it"], title: r.Title, titleUr: r["Title·ur"],
        limit: r["Time limit as shown"], days: r.Days === "" ? null : Number(r.Days), ask: r["Who to ask"],
        cc: list(r["Notify when done"]).join(", "), notify: { start: list(r["Notify on start"]), done: list(r["Notify when done"]), late: list(r["Notify when late"]), rejected: list(r["Notify when rejected"]) },
        forms: ["Form 1", "Form 2", "Form 3", "Form 4"].map((k) => (cell(r[k]).match(/^(RG-\d+|W\d+|T\d+)/) || [])[1]).filter(Boolean),
        what: lines(r["What to do"]), whatUr: lines(r["What to do·ur"]), starts: r["Starts when"], startsUr: r["Starts when·ur"], done: r["Done when"], doneUr: r["Done when·ur"],
        timer: r["Timer type"], phoneTask: r["Makes a phone task"], approval: r.Approval, after: list(r["Comes after step"]), alongside: list(r["Runs alongside step"]), goBack: list(r["If rejected, go back to step"]),
        canPause: r["Can the clock pause"], pauseName: r["Pause button name"], extend: list(r["Who may extend"]), confirms: list(r["Who confirms (ink approvals)"]), __row: r.__row,
      });
    });
    // the written sections (shown on the SOP pages and in the print)
    const info = table(T["SOP info"]).filter((r) => r.Field).map((r) => ({ k: r.Field, en: r.English, ur: r.Urdu }));
    out.x = {
      info: info,
      stages: stages.filter((g) => g["Stage no."]).map((g) => ({ no: g["Stage no."], name: g.Name, nameUr: g["Name·ur"], what: g["What happens"], whatUr: g["What happens·ur"], time: g.Time, timeUr: g["Time·ur"] })),
      roles: roles.filter((r) => r.Role).map((r) => ({ role: r.Role, roleUr: r["Role·ur"], job: r["Your job in one line"], jobUr: r["Your job in one line·ur"], rules: lines(r["Golden rules"]), rulesUr: lines(r["Golden rules·ur"]), need: lines(r["What you need"]), needUr: lines(r["What you need·ur"]), forms: lines(r["Forms you use (as printed)"]), formsUr: lines(r["Forms you use (as printed)·ur"]), ask: lines(r["Who to ask about what"]), askUr: lines(r["Who to ask about what·ur"]) })),
      words: table(T.Words).filter((w) => w.Word).map((w) => ({ w: w.Word, m: w["What it means"], wUr: w["Word·ur"], mUr: w["What it means·ur"] })),
      forms: forms.filter((f) => f.Code).map((f) => ({ code: f.Code, name: f["Form name"], kind: f.Kind, link: /^https:\/\//.test(f["Google Drive link"]) ? f["Google Drive link"] : "" })),
      steps: steps.filter((r) => r["Step no."]).map((r) => ({ id: r["Step no."], stage: Number(r.Stage) || 0, role: r["Who does it"], title: r.Title, titleUr: r["Title·ur"], limit: r["Time limit as shown"], limitUr: r["Time limit as shown·ur"], ask: r["Who to ask"], starts: r["Starts when"], startsUr: r["Starts when·ur"], what: lines(r["What to do"]), whatUr: lines(r["What to do·ur"]), inapp: r["In the app"], inappUr: r["In the app·ur"], wrong: r["If something goes wrong"], wrongUr: r["If something goes wrong·ur"], done: r["Done when"], doneUr: r["Done when·ur"], forms: ["Form 1", "Form 2", "Form 3", "Form 4"].map((k) => (cell(r[k]).match(/^(RG-\d+|W\d+|T\d+)/) || [])[1]).filter(Boolean) })),
    };
    fitems.filter((r) => /^RG-03/.test(r.Form)).forEach((r) => out.items.push({ stage: r.Group, no: r["No."], name: r.Item }));
    return out;
  };

  // ------------------------------------------------------------------ checks: anything wrong blocks Publish
  SYNC.checks = (p) => {
    const errs = [], add = (tab, row, id, col, text) => errs.push({ tab, row, id, col, text });
    const nos = {}, sids = {}, roleSet = new Set(p.roles), stageSet = new Set(Object.keys(p.stages)), formSet = new Set(p.formCodes);
    p.steps.forEach((s) => {
      if (!s.sid) add("Steps", s.__row, s.id || "?", "ID", "The ID is empty. Every step needs its ID (the locked first column).");
      else if (sids[s.sid]) add("Steps", s.__row, s.id, "ID", "The ID " + s.sid + " is used twice (also on row " + sids[s.sid] + ").");
      else sids[s.sid] = s.__row;
      if (!s.id) add("Steps", s.__row, s.sid || "?", "Step no.", "The step number is empty.");
      else if (nos[s.id]) add("Steps", s.__row, s.id, "Step no.", "The step number " + s.id + " is used twice (also on row " + nos[s.id] + ").");
      else nos[s.id] = s.__row;
    });
    p.steps.forEach((s) => {
      const id = s.id || s.sid || "?";
      if (!s.title) add("Steps", s.__row, id, "Title", "The title is empty.");
      if (!s.role) add("Steps", s.__row, id, "Who does it", "No role is set.");
      else if (!roleSet.has(s.role)) add("Steps", s.__row, id, "Who does it", "'" + s.role + "' is not in the Roles tab.");
      if (!stageSet.has(String(s.stage))) add("Steps", s.__row, id, "Stage", "Stage '" + s.stage + "' is not in the Stages tab.");
      if (s.days !== null && !(s.days >= 0 && s.days <= 60)) add("Steps", s.__row, id, "Days", "Days must be a number from 0 to 60.");
      list(s.ask).forEach((x) => { if (!roleSet.has(x)) add("Steps", s.__row, id, "Who to ask", "'" + x + "' is not in the Roles tab."); });
      [["Notify on start", s.notify.start], ["Notify when done", s.notify.done], ["Notify when late", s.notify.late], ["Notify when rejected", s.notify.rejected], ["Who may extend", s.extend], ["Who confirms (ink approvals)", s.confirms]].forEach(([col, arr]) =>
        arr.forEach((x) => { if (!roleSet.has(x)) add("Steps", s.__row, id, col, "'" + x + "' is not in the Roles tab."); }));
      [["Comes after step", s.after], ["Runs alongside step", s.alongside], ["If rejected, go back to step", s.goBack]].forEach(([col, arr]) =>
        arr.forEach((x) => { if (!nos[x]) add("Steps", s.__row, id, col, "Step " + x + " does not exist."); }));
      s.forms.forEach((x) => { if (!formSet.has(x)) add("Steps", s.__row, id, "Form", "Form " + x + " is not in the Forms tab."); });
      [["Title", s.title, s.titleUr], ["What to do", s.what.length, s.whatUr.length], ["Starts when", s.starts, s.startsUr], ["Done when", s.done, s.doneUr]].forEach(([col, en, ur]) => {
        if (en && !ur) add("Steps", s.__row, id, col + " (Urdu)", "The English is filled but the Urdu is empty.");
      });
    });
    return errs;
  };
  /** Roles the steps use that are not designations in Team yet: a notice only (tasks cannot be assigned to them until they are added). */
  SYNC.teamNotice = (p) => { const have = new Set(RB.desigList().map((d) => d.name)); return Array.from(new Set(p.steps.map((s) => s.role).filter(Boolean))).filter((r) => !have.has(r)); };

  // ------------------------------------------------------------------ what changed (against the live register)
  const FIELDS = [["role", "Who does it"], ["title", "Title"], ["stage", "Stage"], ["limit", "Time limit"], ["days", "Days"], ["ask", "Who to ask"], ["cc", "Notice to"], ["forms", "Forms"], ["what", "What to do"]];
  const codes = (v) => (Array.isArray(v) ? v : []).map((x) => (String(x).match(/(RG-\d+|W\d+|T\d+)/) || [x])[0]);
  const show = (v) => (Array.isArray(v) ? v.join(" · ") : v === null || v === undefined || v === "" ? "none" : String(v));
  SYNC.diff = (draft) => {
    const old = {}, ch = []; STEPS.forEach((s) => (old[s.id] = s));
    const now = {}; draft.steps.forEach((s) => (now[s.id] = s));
    draft.steps.forEach((s) => {
      const o = old[s.id];
      if (!o) { ch.push({ kind: "new", id: s.id, text: "New step: " + s.title }); return; }
      const f = FIELDS.filter(([k]) => (k === "forms" ? show(codes(o[k])) !== show(codes(s[k])) : show(o[k]) !== show(s[k]))).map(([k, l]) => k === "what" ? l + " changed" : l + ": " + show(o[k]) + " → " + show(s[k]));
      if (f.length) ch.push({ kind: "chg", id: s.id, text: s.title + " · " + f.join("; ") });
    });
    STEPS.forEach((o) => { if (!now[o.id]) ch.push({ kind: "gone", id: o.id, text: "Step removed: " + o.title }); });
    const so = JSON.stringify(STAGES), sn = JSON.stringify(draft.stages);
    if (so !== sn) ch.push({ kind: "chg", id: "stages", text: "Stage names changed" });
    return ch;
  };

  // ------------------------------------------------------------------ pull and publish
  const compact = (s) => ({ id: s.id, sid: s.sid, stage: s.stage, role: s.role, title: s.title, limit: s.limit, days: s.days, ask: s.ask, cc: s.cc, forms: s.forms, what: s.what });
  async function pull() {
    const st = vdoc("settings"), link = st && st.link;
    if (!link) return;
    busy = true; msg = "Reading the Google Sheet…"; RB.render();
    try {
      let json;
      try { const r = await fetch(link, { redirect: "follow" }); json = await r.json(); }
      catch (e) { throw new Error("Could not reach the sheet link. Check the link and that the web app is deployed with access 'Anyone'."); }
      if (!json.ok) throw new Error(json.error || "The sheet link answered with an error.");
      const p = SYNC.parse(json), draft = { version: pubVer() + 1, pulledAt: Date.now(), by: RB.uidOf(), sheet: p.sheet, steps: p.steps.map((s) => { const x = Object.assign({}, s); delete x.__row; x.row = s.__row; return x; }), stages: p.stages, items: p.items, roles: p.roles, x: p.x, problems: SYNC.checks(p), notice: SYNC.teamNotice(p) };
      await RB.Store.put("plotsopv", "draft", draft);
      msg = "";
    } catch (e) { msg = String(e.message || e); }
    busy = false; RB.render();
  }
  async function publish() {
    const d = vdoc("draft"); if (!d || (d.problems || []).length) return;
    const n = pubVer() + 1, now = Date.now(), full = Object.assign({}, d, { id: undefined, version: n, publishedAt: now, publishedBy: RB.uidOf() }); delete full.id;
    await RB.Store.put("plotsopv", "v" + n, full);
    await RB.Store.put("config", "plotsop", { version: n, publishedAt: now, publishedBy: RB.uidOf(), sheet: d.sheet || "", steps: d.steps.map(compact), stages: d.stages, items: d.items || [] });
    if (d.x) await RB.Store.put("config", "plotsopx", Object.assign({ version: n, publishedAt: now }, d.x));
    await RB.Store.del("plotsopv", "draft");
    await RB.log("Plot files SOP version " + n + " published from the Google Sheet");
    shownVer = null; SYNC.apply(); RB.toast("Version " + n + " published");
  }

  // ------------------------------------------------------------------ the page
  const when = (t) => (t ? RB.fmtWhen(t) : "—");
  SYNC.render = () => {
    const st = vdoc("settings") || {}, d = vdoc("draft"), v = pubVer();
    let h = `<div class="card pfcard"><div class="subhead"><h3>Master Google Sheet</h3></div>
      <div class="hint">The SOP lives in the Google Sheet "Plot files SOP · master". Claude edits it there. The app only reads it: Get changes makes a draft, you read What changed, and Publish makes a new version. Live plot files are never touched by a draft.</div>
      <div class="pfnote"><input id="ps-link" placeholder="Paste the sheet link (web app URL with ?key=…)" value="${esc(st.link || "")}"><button class="btnt" id="ps-save">Save link</button></div>
      <div class="pfact">${st.link ? `<button class="btnp" id="ps-pull" ${busy ? "disabled" : ""}>Get changes from Google Sheet</button>` : '<span class="sub">Save the link first. One-time setup steps are at the top of sync-web-app.gs in the project files.</span>'}</div>
      ${msg ? `<div class="hint warn" style="margin-top:8px">${esc(msg)}</div>` : ""}</div>`;
    if (d) {
      const ch = SYNC.diff(d), bad = d.problems || [], cnt = (k) => ch.filter((c) => c.kind === k).length;
      h += `<div class="card pfcard" style="margin-top:12px"><div class="subhead"><h3>Draft for version ${d.version}</h3><div class="sp"></div><span class="sub">pulled ${esc(when(d.pulledAt))}</span></div>
        <div class="pfchips2"><span class="pfchip"><b>${ch.length}</b> changes</span><span class="pfchip blu"><b>${cnt("new")}</b> new</span><span class="pfchip yel"><b>${cnt("chg")}</b> changed</span><span class="pfchip"><b>${cnt("gone")}</b> removed</span><span class="pfchip ${bad.length ? "red" : ""}"><b>${bad.length}</b> problems</span></div>`;
      if (bad.length) {
        h += `<div class="mh2" style="margin-top:10px">PROBLEMS · PUBLISH IS BLOCKED UNTIL THESE ARE FIXED IN THE SHEET</div>${bad.slice(0, 80).map((p) => `<div class="pfev"><b>${esc(p.tab)} tab · row ${esc(String(p.row))} · step ${esc(p.id)} · column “${esc(p.col)}”</b><div>${esc(p.text)}</div></div>`).join("")}${bad.length > 80 ? `<div class="sub">…and ${bad.length - 80} more. Copy problems has all of them.</div>` : ""}
          <div class="pfact"><button class="btnt" id="ps-copy">Copy problems</button></div><div class="foot">Screenshot this or paste the copied list to Claude in the project thread. Claude fixes the sheet, then you press Get changes again.</div>`;
      }
      if ((d.notice || []).length) h += `<div class="hint" style="margin-top:10px"><b>Not blocking:</b> these roles are not designations in Team yet, so tasks cannot be given to them until you add them: ${esc(d.notice.join(", "))}.</div>`;
      h += `<div class="mh2" style="margin-top:10px">WHAT CHANGED</div>${ch.slice(0, 60).map((c) => `<div class="pfev"><span class="tag ${c.kind === "new" ? "blu" : c.kind === "gone" ? "red" : "yel"}">${c.kind === "new" ? "new" : c.kind === "gone" ? "removed" : "changed"}</span> <b>${esc(c.id)}</b> · ${esc(c.text)}</div>`).join("") || '<div class="muted">Nothing is different from the live version.</div>'}${ch.length > 60 ? `<div class="sub">…and ${ch.length - 60} more.</div>` : ""}
        <div class="foot">Plot files that are already running keep the days and role of steps they have started. Steps they have not reached yet follow the new version.</div>
        </div><div class="pin"><button class="btnp" id="ps-pub" ${bad.length ? "disabled" : ""}>Publish version ${d.version}</button><button class="btnt" id="ps-drop">Discard draft</button>${bad.length ? '<span class="sub">Fix the problems in the sheet, then press Get changes again.</span>' : ""}</div>`;
    }
    const vs = (D.plotSopV || []).filter((x) => /^v\d+$/.test(x.id)).sort((a, b) => b.version - a.version);
    h += `<div class="card pfcard" style="margin-top:12px"><div class="subhead"><h3>Versions</h3></div>
      <div class="pfitem"><div class="w"><b>${v ? "Version " + v + " · live" : "Built-in rules · live"}</b><span>${v ? "published " + esc(when((D.plotSop || {}).publishedAt)) : "Until the first import from the Google Sheet. Version 1 will replace these."}</span></div></div>
      ${vs.filter((x) => x.version !== v).map((x) => `<div class="pfitem"><div class="w"><b>Version ${x.version}</b><span>published ${esc(when(x.publishedAt))} · ${x.steps.length} steps</span></div></div>`).join("")}</div>`;
    return { html: h };
  };
  SYNC.bind = (root) => {
    const on = (sel, fn) => { const e = root.querySelector(sel); if (e) e.onclick = (ev) => { ev.preventDefault(); fn(e); }; };
    on("#ps-save", async () => {
      const v = document.getElementById("ps-link").value.trim();
      if (v && !/^https:\/\/script\.google(usercontent)?\.com\//.test(v) && !(RB.DEMO && /^http:\/\/localhost/.test(v))) return RB.alertBox("The link must start with https://script.google.com/ and end with ?key=…");
      await RB.Store.put("plotsopv", "settings", { link: v }); RB.toast("Link saved"); RB.render();
    });
    on("#ps-pull", pull);
    on("#ps-pub", () => { const d = vdoc("draft"); RB.confirm("Publish version " + d.version + "? Steps not yet reached by running plot files will follow it.", "Publish", publish); });
    on("#ps-drop", () => RB.confirm("Discard this draft? The live version stays as it is.", "Discard", async () => { await RB.Store.del("plotsopv", "draft"); RB.render(); }, true));
    on("#ps-copy", () => {
      const d = vdoc("draft"), t = "Draft for version " + d.version + ": " + d.problems.length + " problems\n" + d.problems.map((p) => p.tab + " tab, row " + p.row + ", step " + p.id + ", column '" + p.col + "': " + p.text).join("\n");
      (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(() => RB.toast("Problems copied"), () => RB.alertBox("<pre style='white-space:pre-wrap;text-align:left'>" + esc(t) + "</pre>"));
    });
  };
})();
