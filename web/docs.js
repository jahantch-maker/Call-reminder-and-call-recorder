/* Documents: reading list, work documents, policies (staff) · SOPs, job descriptions, reading progress (owner). All files are Google Drive links. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc, first = RB.first;
  const { fmtShort, fmtDay } = RB;
  const DAY = 86400000;
  Object.assign(S, { policySel: null, sopSection: "all" });
  const SECTIONS = () => Array.from(new Set(["HR", "Site", "Purchase", "Accounts", "Safety"].concat(D.docs.filter((d) => d.section).map((d) => d.section))));
  const me = () => RB.seatOfUid(RB.uidOf());
  const myDesig = () => (me() || {}).designation || (RB.member(RB.uidOf()) || {}).designation || "";
  const isEd = () => RB.isOwner() || RB.canEdit("docs");
  const link = (u, t) => (u ? `<a class="link" target="_blank" rel="noopener noreferrer" href="${esc(u)}">${t || "Open ›"}</a>` : '<span class="sub">No link yet</span>');
  const ofType = (t) => D.docs.filter((d) => d.type === t);
  const forDesig = (d, name) => (d.designations || []).includes(name);
  const readDone = (uid) => (D.reading[uid] || {}).done || {};
  const driveHint = '<div class="hint">Share each Drive file only with Rafiq Builders Gmails, never “anyone with the link”.</div>';

  /** Everything a person must read: his job description first, then his SOPs. */
  function listFor(desig) { return ofType("jd").filter((d) => forDesig(d, desig)).concat(ofType("sop").filter((d) => forDesig(d, desig))); }
  function itemState(uid, d) {
    const r = readDone(uid)[d.id], v = d.version || 1;
    if (r && (r.version || 1) >= v) return { done: true, by: r.trainedBy || "", at: r.at };
    const t = D.tasks.find((x) => x.docRef && x.docRef.id === d.id && x.fromUid === uid && (x.docRef.version || 1) >= v);
    if (t && (t.status === "done" || t.status === "checked")) return { done: true, by: first(t.toName), at: t.doneAt, trained: true };
    return { done: false, again: !!r, training: !!t };
  }
  const deadlineOf = (uid) => { const s = RB.seatOfUid(uid), h = s && (s.holders || []).slice().reverse().find((x) => x.uid === uid); return (h ? h.from : Date.now()) + ((D.cfg && D.cfg.readingDays) || 7) * DAY; };

  // ------------------------------------------------------------------ staff pages
  function reading() {
    const uid = RB.uidOf(), desig = myDesig(), items = listFor(desig), now = Date.now();
    const st = items.map((d) => [d, itemState(uid, d)]), n = st.filter((x) => x[1].done).length, dl = deadlineOf(uid);
    const row = ([d, s]) => `<div class="docrow"><span class="ty" style="background:${d.type === "jd" ? "var(--lil)" : "var(--blu)"}">${d.type === "jd" ? "Job description" : "SOP"}</span><div class="w"><b>${esc(d.title)}</b><span>${d.section ? esc(d.section) + " · " : ""}Version ${d.version || 1}${s.again ? " · <b style='color:var(--org)'>new version, read again</b>" : ""}</span>
      ${(d.parts || {})[desig] ? `<span class="part">Your part: ${esc(d.parts[desig])}</span>` : ""}</div>${link(d.url, "Open ›")}
      ${s.done ? `<span class="tag grn">✓ ${s.trained ? "trained by " + esc(s.by) : "read"}</span>` : `<button class="btnt" data-train="${esc(d.id)}">${s.training ? "Training asked" : "Need training"}</button><button class="btnp" data-read="${esc(d.id)}">Read ✓</button>`}</div>`;
    const html = `<div class="cols"><div class="card" style="flex:2"><div class="subhead"><h3>My reading list</h3><div class="sp"></div><span class="tag ${n === st.length ? "grn" : dl < now ? "red" : "yel"}">${n < st.length ? (dl < now ? "Overdue since " + fmtDay(dl) : "Finish by " + fmtDay(dl)) : "All done"}</span></div>
      <div style="display:flex;align-items:center;gap:12px"><b>${n} of ${st.length}</b><div class="prog" style="flex:1"><i style="width:${st.length ? Math.round((n / st.length) * 100) : 0}%"></i></div></div>
      ${st.map(row).join("") || `<div class="muted" style="padding:12px 0">${desig ? "Nothing to read for " + esc(desig) + " yet." : "Your seat has no designation yet."}</div>`}</div>
      ${teamBlock()}</div>`;
    return { html };
  }
  function teamBlock() {
    const s = me(); if (!s) return "";
    const under = RB.descendants(s.id); if (!under.length) return "";
    const ds = Array.from(new Set(under.map((x) => x.designation)));
    const sops = ofType("sop").filter((d) => ds.some((x) => forDesig(d, x)));
    return `<div class="card" style="flex:1"><h3>Your people's SOPs</h3>${sops.map((d) => `<div class="docrow"><div class="w"><b>${esc(d.title)}</b><span>${esc((d.designations || []).filter((x) => ds.includes(x)).join(", "))}</span></div>${link(d.url)}</div>`).join("") || '<div class="muted">None.</div>'}</div>`;
  }
  function work() {
    const desig = myDesig(), owner = isEd();
    const list = ofType("work").filter((d) => owner || forDesig(d, desig)).filter((d) => !S.q || d.title.toLowerCase().includes(S.q.toLowerCase()));
    const html = `<div class="card" style="max-width:820px"><div class="subhead"><h3>Work documents</h3><div class="sp"></div>${owner ? '<button class="btnp edit-only" data-add="work">+ Add document</button>' : ""}</div><div class="hint">Tools, guides and sheets for your job. Nothing to tick here.</div>
      ${list.map((d) => `<div class="docrow"><div class="w"><b>${esc(d.title)}</b><span>${(d.designations || []).map(esc).join(", ") || "Everyone"}</span></div>${link(d.url)}${owner ? `<button class="btnt edit-only" data-edit="${esc(d.id)}">Edit</button><button class="btnt edit-only" data-del="${esc(d.id)}">Delete</button>` : ""}</div>`).join("") || '<div class="muted">Nothing here for your designation.</div>'}</div>`;
    return { html };
  }

  // ------------------------------------------------------------------ policies
  const visiblePolicies = () => ofType("policy").filter((d) => RB.isOwner() || (d.shareWith || []).includes(myDesig()));
  const fill = (s, ctx) => String(s || "").replace(/\{name\}|\[name\]/gi, ctx.name).replace(/\{designation\}|\[designation\]/gi, ctx.designation).replace(/\{date\}|\[date\]/gi, ctx.date);
  function gmailLink(t) {
    const ctx = { name: (me() && me().name) || (RB.member(RB.uidOf()) || {}).name || "", designation: myDesig(), date: new Date().toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) };
    return "https://mail.google.com/mail/?view=cm&fs=1&to=" + encodeURIComponent(t.to || "") + "&cc=" + encodeURIComponent(t.cc || "") + "&su=" + encodeURIComponent(fill(t.subject, ctx)) + "&body=" + encodeURIComponent(fill(t.body, ctx));
  }
  const prettyBody = (t) => esc(fill(t.body, { name: "<name>", designation: "<designation>", date: "<date>" })).replace(/\\n|\n/g, "<br>");
  function policies() {
    const list = visiblePolicies().filter((d) => !S.q || d.title.toLowerCase().includes(S.q.toLowerCase()));
    if (!S.policySel || !list.some((d) => d.id === S.policySel)) S.policySel = list[0] && list[0].id;
    const p = list.find((d) => d.id === S.policySel), owner = RB.isOwner();
    const left = `<div class="card" style="width:280px;flex:none;min-width:240px"><div class="subhead"><h3>Policies</h3><div class="sp"></div>${owner ? '<button class="btnp edit-only" data-add="policy">+ Add</button>' : ""}</div>${list.map((d) => `<div class="seatcard ${d.id === S.policySel ? "sel" : ""}" data-pol="${esc(d.id)}"><div class="nm"><b>${esc(d.title)}</b><span>${owner ? ((d.shareWith || []).length ? "Shared with " + esc((d.shareWith || []).join(", ")) : "Owners only") : ""}</span></div></div>`).join("") || '<div class="muted">No policies shared with you.</div>'}</div>`;
    let right = '<div class="card" style="flex:1"><div class="muted">Choose a policy.</div></div>';
    if (p) {
      right = `<div class="card" style="flex:1"><div class="subhead"><h3>${esc(p.title)}</h3><div class="sp"></div>${owner ? `<button class="btnt edit-only" data-edit="${esc(p.id)}">Edit</button><button class="btnt edit-only" data-del="${esc(p.id)}">Delete</button>` : ""}</div>
        ${p.summary ? `<div class="mh2">IN SHORT</div><div style="line-height:1.5">${esc(p.summary)}</div>` : ""}
        <div style="margin:12px 0">${p.url ? `<a class="btnp" style="display:inline-block;text-decoration:none" target="_blank" rel="noopener noreferrer" href="${esc(p.url)}">Open full policy</a>` : ""}</div>
        ${(p.how || []).length ? `<div class="mh2">HOW TO</div>${p.how.map((h, i) => `<div class="docrow" style="padding:8px 0"><span class="tag">${i + 1}</span><div class="w"><b style="font-size:14px">${esc(h)}</b></div></div>`).join("")}` : ""}
        ${(p.forms || []).length ? `<div class="mh2" style="margin-top:12px">FORMS</div>${p.forms.map((f) => `<div class="docrow" style="padding:8px 0"><div class="w"><b style="font-size:14px">${esc(f.name)}</b></div>${link(f.url)}</div>`).join("")}` : ""}
        ${(p.templates || []).length ? `<div class="mh2" style="margin-top:12px">EMAIL TEMPLATES</div>${p.templates.map((t) => `<div class="card" style="background:#F7F7F5;margin-bottom:8px;padding:12px 14px"><b>${esc(t.name)}</b><div class="sub">To ${esc(t.to || "—")}${t.cc ? " · CC " + esc(t.cc) : ""}</div><div class="sub" style="margin-top:4px"><b>${esc(fill(t.subject, { name: "<name>", designation: "<designation>", date: "<date>" }))}</b></div><div class="sub" style="margin:4px 0 8px">${prettyBody(t)}</div><div class="foot">Fill the [brackets] in Gmail before you send.</div><a class="btnp" style="display:inline-block;text-decoration:none;margin-top:6px" target="_blank" rel="noopener noreferrer" href="${esc(gmailLink(t))}">Open in Gmail</a>${owner ? ` <button class="btnt edit-only" data-tpl="${esc(p.id)}:${esc(t.id)}">Edit template</button>` : ""}</div>`).join("")}` : ""}
        ${owner ? `<button class="btnt edit-only" data-tpl="${esc(p.id)}:new" style="margin-top:8px">+ Email template</button>` : ""}</div>`;
    }
    return { html: `<div class="cols">${left}${right}</div>` };
  }

  // ------------------------------------------------------------------ owner pages
  function sops() {
    const ps = RB.plotSop ? RB.plotSop.docs() : "";   // the built Plot files SOP (written live from the Google Sheet)
    if (!isEd() || S.sopPage) return { html: ps || '<div class="card"><div class="muted">No SOPs here yet.</div></div>' };
    const secs = SECTIONS(), now = Date.now();
    const list = ofType("sop").filter((d) => (S.sopSection === "all" || d.section === S.sopSection) && (!S.q || d.title.toLowerCase().includes(S.q.toLowerCase())));
    const chips = ["all"].concat(secs).map((x) => `<button class="sc ${S.sopSection === x ? "on" : ""}" data-sec2="${esc(x)}">${x === "all" ? "All" : esc(x)}</button>`).join("");
    const html = ps + `<div class="card"><div class="subhead"><h3>SOPs</h3><div class="sp"></div>${chips}<button class="btnp edit-only" data-add="sop">+ Add SOP</button></div>${driveHint}
      ${list.map((d) => `<div class="docrow"><div class="w"><b>${esc(d.title)}</b><span>${esc(d.section || "")} · owner ${esc(d.owner || "—")} · version ${d.version || 1}${d.reviewBy ? " · review by " + fmtShort(d.reviewBy) : ""}${d.reviewBy && d.reviewBy < now ? ' <b style="color:var(--er)">review overdue</b>' : ""}</span><span>Must read: ${(d.designations || []).map((x) => `<span class="ty" style="background:${RB.desigColor(x)};font-size:11px;padding:2px 8px;margin-right:4px">${esc(x)}</span>`).join("") || "nobody yet"}</span></div>${link(d.url)}<button class="btnt edit-only" data-newver="${esc(d.id)}">Upload new version</button><button class="btnt edit-only" data-edit="${esc(d.id)}">Edit</button><button class="btnt edit-only" data-del="${esc(d.id)}">Delete</button></div>`).join("") || '<div class="muted" style="padding:12px 0">No SOPs here yet.</div>'}</div>`;
    return { html };
  }
  function jds() {
    const html = `<div class="card" style="max-width:820px"><div class="subhead"><h3>Job descriptions</h3><div class="sp"></div><button class="btnp edit-only" data-add="jd">+ Add JD</button></div>${driveHint}
      ${RB.desigList().map((x) => { const j = ofType("jd").find((d) => forDesig(d, x.name)); return `<div class="docrow"><span class="ty" style="background:${RB.desigColor(x.name)}">${esc(x.name)}</span><div class="w">${j ? `<b>${esc(j.title)}</b><span>Version ${j.version || 1}</span>` : '<span>No job description yet</span>'}</div>${j ? link(j.url) : ""}${j ? `<button class="btnt edit-only" data-edit="${esc(j.id)}">Edit</button>` : `<button class="btnt edit-only" data-add="jd:${esc(x.name)}">Add</button>`}</div>`; }).join("")}</div>`;
    return { html };
  }
  function progress() {
    const now = Date.now(), seats = D.seats.filter((s) => s.status === "filled" && s.role !== "owner");
    const html = `<div class="card"><div class="subhead"><h3>Reading progress</h3></div><div class="tablewrap"><table><tr><th>Person</th><th>Seat</th><th>Read</th><th style="width:180px">Progress</th><th>Deadline</th></tr>
      ${seats.map((s) => { const items = listFor(s.designation), st = items.map((d) => itemState(s.uid, d)), n = st.filter((x) => x.done).length, dl = deadlineOf(s.uid); const late = n < items.length && dl < now; return `<tr><td><b>${esc(first(s.name))}</b></td><td><span class="ty" style="background:${RB.desigColor(s.designation)}">${esc(s.designation)}</span></td><td>${n} of ${items.length}</td><td><div class="prog"><i style="width:${items.length ? Math.round((n / items.length) * 100) : 100}%"></i></div></td><td class="st" style="color:var(--${late ? "red" : n === items.length ? "grn" : "org"})">${n === items.length ? "Done" : (late ? "Overdue · " : "") + fmtDay(dl)}</td></tr>`; }).join("")}</table></div></div>`;
    return { html };
  }

  // ------------------------------------------------------------------ dialogs
  function docDialog(kind, existing, presetDesig) {
    const e = existing || {}, ds = RB.desigList().map((d) => d.name), t = kind;
    const m = document.getElementById("modal");
    const extra = t === "sop"
      ? `<div class="lbl">SECTION</div><select data-k="section">${SECTIONS().concat("＋ New section").map((x) => `<option ${e.section === x ? "selected" : ""}>${esc(x)}</option>`).join("")}</select><input data-k="newsec" placeholder="New section name" style="display:none;margin-top:6px">
         <div class="lbl">OWNER (who keeps it up to date)</div><input data-k="owner" value="${esc(e.owner || "")}">
         <div class="lbl">REVIEW BY</div><input data-k="reviewBy" type="date" value="${e.reviewBy ? new Date(e.reviewBy).toISOString().slice(0, 10) : ""}">` : "";
    const pol = t === "policy" ? `<div class="lbl">IN SHORT</div><textarea data-k="summary" rows="3">${esc(e.summary || "")}</textarea><div class="lbl">HOW TO (one step per line)</div><textarea data-k="how" rows="3">${esc((e.how || []).join("\n"))}</textarea><div class="lbl">FORMS (name | link, one per line)</div><textarea data-k="forms" rows="2">${esc((e.forms || []).map((f) => f.name + " | " + f.url).join("\n"))}</textarea>` : "";
    const des = t === "policy"
      ? `<div class="lbl">SHARE WITH (owners always see it)</div>${ds.map((d) => `<label class="sub" style="display:block"><input type="checkbox" data-d="${esc(d)}" ${(e.shareWith || []).includes(d) ? "checked" : ""}> ${esc(d)}</label>`).join("")}`
      : `<div class="lbl">${t === "work" ? "FOR DESIGNATIONS" : "MUST READ"}</div>${ds.map((d) => `<div class="swrow" style="padding:6px 0"><label class="sub" style="flex:none;width:150px"><input type="checkbox" data-d="${esc(d)}" ${((e.designations || []).includes(d) || presetDesig === d) ? "checked" : ""}> ${esc(d)}</label>${t === "sop" ? `<input data-part="${esc(d)}" placeholder="Your part (optional)" value="${esc((e.parts || {})[d] || "")}" style="flex:1">` : ""}</div>`).join("")}`;
    m.innerHTML = `<div class="back"><div class="dlg wide"><b style="font-size:19px">${e.id ? "Edit" : "Add"} ${{ sop: "SOP", jd: "job description", work: "work document", policy: "policy" }[t]}</b>
      <div class="lbl">TITLE</div><input data-k="title" value="${esc(e.title || "")}" style="width:100%"><div class="lbl">GOOGLE DRIVE LINK</div><input data-k="url" value="${esc(e.url || "")}" placeholder="https://drive.google.com/…" style="width:100%">${driveHint}${extra}${pol}${des}
      <div class="err" id="ferr"></div><div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="fc">Cancel</button><button class="btnp" id="fy">Save</button></div></div></div>`;
    const sel = m.querySelector('[data-k="section"]'); if (sel) sel.onchange = () => (m.querySelector('[data-k="newsec"]').style.display = sel.value.startsWith("＋") ? "block" : "none");
    document.getElementById("fc").onclick = () => (m.innerHTML = "");
    document.getElementById("fy").onclick = async () => {
      const v = {}; m.querySelectorAll("[data-k]").forEach((x) => (v[x.dataset.k] = x.value));
      const chosen = [...m.querySelectorAll("[data-d]")].filter((c) => c.checked).map((c) => c.dataset.d);
      const err = (s) => (document.getElementById("ferr").textContent = s);
      if (!v.title.trim()) return err("Type a title.");
      if (v.url && !/^https:\/\//.test(v.url.trim())) return err("The link must start with https://");
      if (t === "jd" && !chosen.length) return err("Pick the designation this job description is for.");
      const doc = { type: t, title: v.title.trim(), url: (v.url || "").trim(), version: e.version || 1, updatedAt: Date.now() };
      if (t === "policy") {
        doc.shareWith = chosen; doc.summary = (v.summary || "").trim(); doc.how = (v.how || "").split("\n").map((x) => x.trim()).filter(Boolean);
        doc.forms = (v.forms || "").split("\n").map((x) => x.split("|")).filter((x) => x[0].trim()).map((x) => ({ name: x[0].trim(), url: (x[1] || "").trim() })); doc.templates = e.templates || [];
      } else {
        doc.designations = chosen;
        if (t === "sop") {
          doc.section = v.section.startsWith("＋") ? (v.newsec || "").trim() || "General" : v.section; doc.owner = (v.owner || "").trim();
          doc.reviewBy = v.reviewBy ? new Date(v.reviewBy + "T09:00").getTime() : 0;
          doc.parts = {}; m.querySelectorAll("[data-part]").forEach((x) => { if (x.value.trim() && chosen.includes(x.dataset.part)) doc.parts[x.dataset.part] = x.value.trim(); });
        }
      }
      m.innerHTML = ""; await RB.Store.put("docs", e.id || RB.id(), doc); await RB.log((e.id ? "Edited " : "Added ") + t + " “" + doc.title + "”"); RB.render();
    };
  }
  function tplDialog(pid, tid) {
    const p = D.docs.find((d) => d.id === pid), t = (p.templates || []).find((x) => x.id === tid) || { id: RB.id() };
    RB.form(tid === "new" ? "New email template" : "Edit template", [
      { k: "name", label: "TEMPLATE NAME", value: t.name || "" }, { k: "to", label: "TO (fixed)", value: t.to || "" }, { k: "cc", label: "CC (fixed)", value: t.cc || "" },
      { k: "subject", label: "SUBJECT", value: t.subject || "", ph: "Leave request · [name] · [date]" }, { k: "body", label: "BODY", type: "textarea", value: t.body || "", ph: "Use [name], [designation], [date]. Other [brackets] are filled by staff." },
    ], "Save", async (v) => {
      if (!v.name.trim()) return "Type a name.";
      const list = (p.templates || []).filter((x) => x.id !== t.id).concat(Object.assign({}, t, { name: v.name.trim(), to: v.to.trim(), cc: v.cc.trim(), subject: v.subject, body: v.body }));
      await RB.Store.put("docs", pid, { templates: list }, true); await RB.log("Edited email template “" + v.name.trim() + "” of " + p.title);
    }, "[name], [designation] and [date] fill themselves. To and CC cannot be changed by staff.");
  }

  // ------------------------------------------------------------------ section
  RB.sections.docs = {
    tabs() {
      const t = [["reading", "My reading list"], ["work", "Work documents"]];
      if (visiblePolicies().length || RB.isOwner()) t.push(["policies", "Policies"]);
      if (isEd()) t.push(["sops", "SOPs"], ["jds", "Job descriptions"], ["progress", "Reading progress"]);
      else if (RB.plotSop && RB.plotSop.has()) t.push(["sops", "SOPs"]);
      return t;
    },
    render(tab) {
      const p = { reading, work, policies, sops, jds, progress }[tab] ? { reading, work, policies, sops, jds, progress }[tab]() : reading();
      p.title = "Documents"; p.sub = "Job descriptions, SOPs, tools and policies · all open in Google Drive";
      return p;
    },
    bind(root) {
      const on = (sel, fn) => root.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e); }));
      on("[data-read]", async (e) => { const d = D.docs.find((x) => x.id === e.dataset.read); await RB.Store.put("reading", RB.uidOf(), { done: Object.assign({}, readDone(RB.uidOf()), { [d.id]: { at: Date.now(), version: d.version || 1 } }) }, true); RB.render(); });
      on("[data-train]", async (e) => {
        const d = D.docs.find((x) => x.id === e.dataset.train), s = me(), h = RB.headOf(s), hm = h && h.status === "filled" && RB.member(h.uid);
        if (!hm) return RB.alertBox("You have no head in the chart to ask. Tell an owner.");
        if (D.tasks.some((x) => x.docRef && x.docRef.id === d.id && x.fromUid === RB.uidOf() && x.status !== "done" && x.status !== "checked")) return RB.alertBox("Training is already asked. Your head has a task for it.");
        await RB.Store.task(RB.uidOf(), hm.uid, "Train " + first(s.name) + " on " + d.title, Date.now() + 3 * DAY, { docRef: { id: d.id, version: d.version || 1 } });
        await RB.log(s.name + " asked " + hm.name + " for training on " + d.title); RB.render();
      });
      on("[data-add]", (e) => { const [k, pre] = e.dataset.add.split(":"); docDialog(k, null, pre); });
      on("[data-edit]", (e) => { const d = D.docs.find((x) => x.id === e.dataset.edit); docDialog(d.type, d); });
      on("[data-del]", (e) => { const d = D.docs.find((x) => x.id === e.dataset.del); RB.confirm("Delete “" + esc(d.title) + "”?", "Delete", async () => { await RB.Store.del("docs", d.id); await RB.log("Deleted " + d.type + " “" + d.title + "”"); }, true); });
      on("[data-newver]", (e) => { const d = D.docs.find((x) => x.id === e.dataset.newver); RB.form("New version of " + d.title, [{ k: "url", label: "GOOGLE DRIVE LINK (leave as is if the same file)", value: d.url || "" }], "Publish version " + ((d.version || 1) + 1), async (v) => { if (v.url && !/^https:\/\//.test(v.url)) return "The link must start with https://"; await RB.Store.put("docs", d.id, { version: (d.version || 1) + 1, url: v.url.trim(), updatedAt: Date.now() }, true); await RB.log("New version " + ((d.version || 1) + 1) + " of " + d.title + ": " + (d.designations || []).join(", ") + " read again"); }, "Only the designations that must read it will read it again."); });
      if (RB.plotSop) RB.plotSop.bind(root);
      on("[data-sec2]", (e) => { S.sopSection = e.dataset.sec2; RB.render(); });
      on("[data-pol]", (e) => { S.policySel = e.dataset.pol; RB.render(); });
      on("[data-tpl]", (e) => { const [p, t] = e.dataset.tpl.split(":"); tplDialog(p, t); });
    },
  };
})();
