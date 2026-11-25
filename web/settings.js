/* Settings: late-reason list, red lines, meeting steps, ranking, reading deadline, office holidays and the change log (owners). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  /** The five tabs the owner can tick per person (RB.SET_TABS); Change log is for owners, Updates and Appearance for everyone. */
  const TABS = RB.SET_TABS;
  /** Team Performance settings (config/meeting.perf, read by tpcalc.js). */
  function perfHtml() {
    const c = RB.tp.cfg(), num = (id, val, lbl, unit, hint) => `<div class="rrow"><span class="n" style="flex:1;min-width:200px">${lbl}${hint ? `<br><span class="sub">${hint}</span>` : ""}</span><input class="num" type="number" id="${id}" value="${val}"><span style="width:52px;flex:none">${unit || ""}</span></div>`;
    const SHOW = [["oldOver", "Oldest overdue tasks"], ["oldUns", "Not scheduled list"], ["moved", "Moved " + c.movedN + "+ list"], ["needs", "Needs help box"], ["trend", "Score line (weeks)"], ["dosBars", "Dossier day bars"], ["byType", "By task type"]];
    const tick = (k, i) => (c.show[k][i] === null ? '<span class="sub">–</span>' : `<input type="checkbox" data-tpshow="${k}:${i}" ${c.show[k][i] ? "checked" : ""} aria-label="${k}">`);
    const ppl = RB.tp.people(true);
    return `<div class="card" style="max-width:720px"><h3>Team Performance</h3><div class="hint">These numbers decide how Team Performance counts and colours. Changes show for everyone at once.</div>
      ${num("tpwon", c.wOn, "Score weight: tasks on time", "%")}${num("tpwdos", c.wDos, "Score weight: dossier", "%", "Score = on time % × this + dossier % × this, divided by both added")}
      ${num("tpgrace", c.grace, "Not scheduled counts after", "days", "a task with no date for longer than this")}${num("tpolder", c.older, "Oldest overdue list shows tasks over", "days")}
      ${num("tpmoved", c.movedN, "Moved list: date moved at least", "times")}${num("tpred", c.redBelow, "Red row when the score is under", "%")}${num("tpweak", c.weakDay, "Red dossier day when under", "%")}
      ${num("tpweeks", c.weeks, "Score line shows the last", "weeks")}${num("tpoldn", c.oldOverN, "Oldest overdue: rows before Show all", "rows")}${num("tpneedn", c.needsN, "Needs help: people shown", "people")}
      <div class="rrow"><span class="n" style="flex:1">Period when the page opens</span><select id="tpperiod">${RB.tp.PERIODS.map(([k, l]) => `<option value="${k}" ${c.period === k ? "selected" : ""}>${l}</option>`).join("")}</select></div>
      <h3 style="margin-top:16px">Show or hide</h3><table style="width:100%"><tr><th style="text-align:left">Part</th><th>Main screen</th><th>Person report</th></tr>${SHOW.map(([k, l]) => `<tr><td>${l}</td><td style="text-align:center">${tick(k, 0)}</td><td style="text-align:center">${tick(k, 1)}</td></tr>`).join("")}</table>
      <h3 style="margin-top:16px">Hide people</h3><div class="hint">Ticked people are left out of Team Performance (for example the owner or a driver).</div>
      <div style="display:flex;flex-wrap:wrap;gap:6px 16px;margin-top:6px">${ppl.map((m) => `<label style="display:flex;gap:6px;align-items:center"><input type="checkbox" data-tphide="${esc(m.uid)}" ${c.hidden.includes(m.uid) ? "checked" : ""}>${esc(RB.first(m.name))}</label>`).join("")}</div>
      <button class="btnp edit-only" id="tpsave" style="margin-top:14px">Save</button>
      <div class="tp-box" style="margin-top:14px"><b>How the numbers work</b><div class="hint" style="line-height:1.6;margin-top:4px">· <b>On time %</b> = done on time ÷ (on time + late + overdue + not scheduled). The first due date counts; moving a date later doesn't reset it. Late with an outside reason (material, payment, client) counts nowhere.<br>· Tasks given by a manager use their current deadline, because only the giver or a manager can change it.<br>· <b>Dossier %</b> = ticked ÷ due over the days. Today never pulls it down until the day ends. No dossier counts as 0.<br>· <b>Score</b> = both together by the weights above; the rank always follows the score.<br>· The week starts on Saturday. Weekly off days come from each person's timetable, holidays from Settings › Holidays.</div></div></div>`;
  }

  // ------------------------------------------------------------------ Holidays (office closed days; locked 25 Nov: Import Pakistan holidays,
  // 🌙 moon dates confirmed 3 days before, a "next year is ready" notice every 1 December). config/holidays { dates: [{ d, name, moon, ok }] }.
  // The import asks the cloud (functions/ pkHolidays) through config/holimport, because a browser can't read Google's calendar itself.
  const pad = (n) => String(n).padStart(2, "0");
  const isoOf = (dt) => dt.getFullYear() + "-" + pad(dt.getMonth() + 1) + "-" + pad(dt.getDate());
  const plus = (d, n) => { const dt = new Date(d + "T12:00:00"); dt.setDate(dt.getDate() + n); return isoOf(dt); };
  const hfmt = (d) => new Date(d + "T12:00:00").toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
  const hdates = () => ((D.holidays && D.holidays.dates) || []).slice().sort((a, b) => (a.d < b.d ? -1 : a.d > b.d ? 1 : 0));
  /** Week days everyone has off (from all timetables; Friday until there are none). */
  const commonOff = () => { const tts = RB.timetables ? RB.timetables() : []; return tts.length ? [0, 1, 2, 3, 4, 5, 6].filter((d) => tts.every((t) => (t.off || []).includes(d))) : [5]; };
  const DN = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  const offTag = (d) => { const w = new Date(d + "T12:00:00").getDay(); return commonOff().includes(w) ? `<span class="hz-tag fri">${DN[w]}, already off</span>` : ""; };
  const base = (n) => String(n || "").replace(/\(.*?\)|·.*$|\bholidays?\b|\bday\s*\d+\b/gi, "").replace(/\s+/g, " ").trim() || n;
  /** 🌙 Moon holidays not confirmed yet that start within 3 days, grouped (Eid day 1 + day 2 = one question). */
  RB.moonDue = () => {
    const today = isoOf(new Date()), limit = plus(today, 3), out = [];
    hdates().filter((h) => h.moon && !h.ok && h.d >= today && h.d <= limit).forEach((h) => {
      const g = out.find((x) => x.base === base(h.name) && plus(x.dates[x.dates.length - 1].d, 1) >= h.d);
      if (g) g.dates.push(h); else out.push({ base: base(h.name), dates: [h] });
    });
    return out;
  };
  /** From 1 December: next year's holidays wait to be imported until any date of that year is in the list. */
  RB.holReady = () => { const n = new Date(); return n.getMonth() === 11 && !hdates().some((h) => h.d.startsWith(String(n.getFullYear() + 1))) ? n.getFullYear() + 1 : 0; };
  RB.holNotices = () => {
    if (!RB.isOwner()) return [];
    const out = RB.moonDue().map((g) => ["settings", "holidays", "🌙 " + g.base + " is set for " + g.dates.map((h) => hfmt(h.d)).join(" and "), "Moon date: confirm it once the moon is announced"]);
    const y = RB.holReady(); if (y) out.unshift(["settings", "holidays", "🗓 " + y + " Pakistan holidays are ready", "Tick the days the office is closed, then Add", () => openImport(y)]);
    return out;
  };
  const hsave = (dates, note) => { RB.hold = true; return RB.Store.saveConfig("holidays", Object.assign({}, D.holidays || {}, { dates })).then(() => RB.log(note)).catch((e) => console.warn("log", e)).finally(() => { RB.hold = false; RB.render(); }); };
  function openImport(year) {
    S.hImp = { year, askedAt: Date.now(), picks: null, moved: {}, editing: "" };
    S.tab.settings = "holidays";
    RB.Store.put("config", "holimport", { year, askedAt: S.hImp.askedAt }, true, true);
    RB.go("settings", "holidays");
  }
  function holidays() {
    const own = RB.isOwner(), list = hdates(), y = own && RB.holReady();
    const tag = (h) => (h.moon ? (h.ok ? '<span class="hz-tag ok">✓ Moon date confirmed</span>' : '<span class="hz-tag moon">🌙 Moon date, confirm</span>') : offTag(h.d));
    let yr = "";
    const rows = list.map((h) => { const head = h.d.slice(0, 4) !== yr ? ((yr = h.d.slice(0, 4)), `<div class="hz-yh">${yr}</div>`) : "";
      return head + `<div class="hz-row"><span class="d">${esc(hfmt(h.d))}</span><b>${esc(h.name || "")}</b><span>${tag(h)}</span><span style="display:flex;gap:6px">${own ? `<button class="btnt" data-hedit="${esc(h.d)}">Edit</button><button class="btnt" data-hdel="${esc(h.d)}">Remove</button>` : ""}</span></div>`; }).join("");
    const moon = own ? RB.moonDue().map((g, i) => `<div class="hz-bar">🌙<span class="t"><b>${esc(g.base)} is set for ${g.dates.map((h) => esc(hfmt(h.d))).join(" and ")}.</b> Moon date: confirm once Ruet-e-Hilal announces.</span><button class="btnt" data-hmoon="${i}:same">Same</button><button class="btnt" data-hmoon="${i}:1">1 day later</button><button class="btnt" data-hmoon="${i}:-1">1 day earlier</button><button class="btnt" data-hmoon="${i}:add">+ Add a day</button></div>`).join("") : "";
    const ready = y ? `<div class="hz-bar">🗓<span class="t"><b>${y} Pakistan holidays are ready.</b> Tick the days the office is closed, then Add.</span><button class="btnp" data-himp="${y}">Review and add ›</button></div>` : "";
    const html = `<div class="card" style="max-width:${S.hImp ? 640 : 820}px"><div class="stickhead"><div class="subhead"><h3>Holidays · ${list.length}</h3><div class="sp"></div>${own ? `<button class="btnp" data-himp="${new Date().getFullYear()}">⬇ Import Pakistan holidays</button>` : ""}</div><div class="hint">Public holidays and other days the office is closed. Due times in every SOP and dossier days skip these dates. Weekly days off are set per timetable, in Team › Timetables.</div></div>
      ${ready}${moon}${rows || '<div class="muted">No holidays added yet.</div>'}
      ${own ? `<div class="rrow" style="flex-wrap:wrap;gap:8px;margin-top:12px"><input type="date" id="hdate"><input id="hname" placeholder="Name, e.g. Eid ul Fitr" style="flex:1;min-width:160px"><button class="btnp" id="hadd">Add</button></div>` : '<div class="foot">Only the owner changes holidays.</div>'}</div>`;
    return { html, side: own && S.hImp ? impPanel() : "" };
  }
  function impPanel() {
    const I = S.hImp, R = D.holimport || {}, now = new Date().getFullYear();
    const yrs = `<div class="hz-yr">${[now, now + 1].map((y) => `<button class="dz-chip ${I.year === y ? "on" : ""}" data-himpy="${y}">${y}</button>`).join("")}</div>`;
    const head = `<div class="ttpanel hzpanel"><h3>Import Pakistan holidays</h3><div class="sub">From Google's public Pakistan holidays calendar. Tick the days the office is closed.</div>${yrs}`;
    const foot = (btn) => `<div class="ttfoot"><div class="sp"></div><button class="link" id="hicancel">Cancel</button>${btn || ""}</div></div>`;
    const ready = R.year === I.year && (R.doneAt || 0) >= I.askedAt;
    if (!ready) return head + `<div class="muted" style="margin:14px 0">Reading Google's Pakistan holidays for ${I.year}…</div>` + foot();
    if (R.error) return head + `<div class="err" style="margin:12px 0">Couldn't read Google's calendar: ${esc(R.error)}</div><button class="btnt" data-himpy="${I.year}">Try again</button>` + foot();
    const have = new Set(hdates().map((h) => h.d)), found = (R.found || []).filter((h) => h.d && h.name);
    if (!found.length) return head + `<div class="muted" style="margin:14px 0">Google has no Pakistan holidays for ${I.year} yet. Try again later, or add them by hand.</div>` + foot();
    if (!I.picks) { I.picks = {}; found.forEach((h) => (I.picks[h.d + h.name] = !h.optional && !have.has(h.d))); }
    const n = found.filter((h) => I.picks[h.d + h.name] && !have.has(h.d)).length;
    const row = (h) => { const k = h.d + h.name, d = I.moved[k] || h.d, isIn = have.has(h.d) && !I.moved[k];
      return `<label class="hz-p ${I.picks[k] && !isIn ? "" : "off"}"><input type="checkbox" data-hpick="${esc(k)}" ${I.picks[k] && !isIn ? "checked" : ""} ${isIn ? "disabled" : ""} aria-label="${esc(h.name)}"><b>${esc(hfmt(d))}</b><span class="n"><b>${esc(h.name)}</b>${isIn ? '<span class="hz-tag ok">Already added</span>' : ""}${h.moon ? '<span class="hz-tag moon">🌙 Moon date</span>' : ""}${h.optional ? '<span class="hz-tag opt">Optional</span>' : ""}${offTag(d)}</span>${isIn ? "<span></span>" : `<button class="ed" data-hmove="${esc(k)}" title="Change date" aria-label="Change date of ${esc(h.name)}">✎</button>`}${I.editing === k ? `<input type="date" data-hdate="${esc(k)}" value="${esc(d)}">` : ""}</label>`; };
    return head + (found.some((h) => h.moon) ? '<div class="hz-moonnote">🌙 Eid, Ashura and Eid Milad follow the moon. Google\'s date is a best guess, so you\'ll be asked to confirm 3 days before.</div>' : "")
      + `<div class="hz-tk"><button data-hall="1">Tick all</button><button data-hall="0">Untick all</button><span>${n} of ${found.length} ticked</span></div>${found.map(row).join("")}<div class="sub" style="margin-top:6px">✎ changes a date before adding. You can also change it later with Edit.</div>`
      + foot(`<button class="btnp" id="hiadd" ${n ? "" : "disabled"}>Add ${n} holiday${n === 1 ? "" : "s"}</button>`);
  }
  function holBind(root, side) {
    const own = RB.isOwner(); if (!own) return;
    root.querySelectorAll("[data-himp]").forEach((b) => (b.onclick = () => openImport(Number(b.dataset.himp))));
    const ha = root.querySelector("#hadd");
    if (ha) ha.onclick = () => {
      const d = root.querySelector("#hdate").value, name = root.querySelector("#hname").value.trim();
      if (!d) return RB.alertBox("Pick a date first.");
      hsave(hdates().filter((h) => h.d !== d).concat([{ d, name }]), "Holiday added: " + d + (name ? " (" + name + ")" : ""));
    };
    root.querySelectorAll("[data-hdel]").forEach((b) => (b.onclick = () => { const d = b.dataset.hdel; hsave(hdates().filter((h) => h.d !== d), "Holiday removed: " + d); }));
    root.querySelectorAll("[data-hedit]").forEach((b) => (b.onclick = () => {
      const h = hdates().find((x) => x.d === b.dataset.hedit); if (!h) return;
      RB.form("Edit holiday", [{ k: "d", label: "DATE", type: "date", value: h.d }, { k: "name", label: "NAME", value: h.name || "" }], "Save", async (v) => {
        if (!v.d) return "Pick a date.";
        if (v.d !== h.d && hdates().some((x) => x.d === v.d)) return "That date is already a holiday.";
        // A moon date you set yourself counts as confirmed.
        await hsave(hdates().filter((x) => x.d !== h.d).concat([Object.assign({}, h, { d: v.d, name: v.name.trim() }, h.moon && v.d !== h.d ? { ok: true } : {})]), "Holiday changed: " + h.d + " " + (h.name || "") + " → " + v.d + " " + v.name.trim());
      });
    }));
    root.querySelectorAll("[data-hmoon]").forEach((b) => (b.onclick = () => {
      const [i, act] = b.dataset.hmoon.split(":"), g = RB.moonDue()[Number(i)]; if (!g) return;
      const ds = g.dates.map((h) => h.d), rest = hdates().filter((h) => !ds.includes(h.d));
      let mine = g.dates.map((h) => Object.assign({}, h, { ok: true }));
      if (act === "1" || act === "-1") mine = mine.map((h) => Object.assign(h, { d: plus(h.d, Number(act)) }));
      if (act === "add") { const last = ds[ds.length - 1]; mine.push({ d: plus(last, 1), name: g.base + " (extra day)", moon: true, ok: true }); }
      const clash = mine.find((h) => rest.some((x) => x.d === h.d));
      if (clash) return RB.alertBox(esc(hfmt(clash.d)) + " is already a holiday. Use Edit on the row instead.");
      hsave(rest.concat(mine), "Moon date confirmed: " + g.base + " · " + mine.map((h) => h.d).join(", "));
    }));
    const P = side && side.querySelector(".hzpanel"), I = S.hImp; if (!P || !I) return;
    P.querySelector("#hicancel").onclick = () => { S.hImp = null; RB.render(); };
    P.querySelectorAll("[data-himpy]").forEach((b) => (b.onclick = () => openImport(Number(b.dataset.himpy))));
    P.querySelectorAll("[data-hpick]").forEach((c) => (c.onchange = () => { I.picks[c.dataset.hpick] = c.checked; RB.render(); }));
    P.querySelectorAll("[data-hall]").forEach((b) => (b.onclick = () => { Object.keys(I.picks).forEach((k) => (I.picks[k] = b.dataset.hall === "1")); RB.render(); }));
    P.querySelectorAll("[data-hmove]").forEach((b) => (b.onclick = (e) => { e.preventDefault(); I.editing = I.editing === b.dataset.hmove ? "" : b.dataset.hmove; RB.render(); }));
    P.querySelectorAll("[data-hdate]").forEach((c) => (c.onchange = () => { if (c.value) I.moved[c.dataset.hdate] = c.value; I.editing = ""; RB.render(); }));
    const add = P.querySelector("#hiadd");
    if (add) add.onclick = () => {
      const have = new Set(hdates().map((h) => h.d)), cur = hdates(), pick = [];
      ((D.holimport || {}).found || []).forEach((h) => { const k = h.d + h.name, d = I.moved[k] || h.d; if (I.picks[k] && !(have.has(h.d) && !I.moved[k]) && !cur.some((x) => x.d === d) && !pick.some((x) => x.d === d)) pick.push(Object.assign({ d, name: h.name }, h.moon ? { moon: true } : {})); });
      if (!pick.length) return;
      S.hImp = null;
      hsave(cur.concat(pick), "Holidays imported for " + I.year + ": " + pick.map((h) => h.d + " " + h.name).join(", "));
    };
  }
  if (RB.DEMO) RB.demoHolImport = (q) => setTimeout(() => {
    const y = q.year, f = [["02-05", "Kashmir Solidarity Day"], ["03-10", "Eid al-Fitr"], ["03-11", "Eid al-Fitr Holiday"], ["03-12", "Eid al-Fitr Holiday"], ["03-23", "Pakistan Day"], ["03-28", "Easter Sunday", 1], ["05-01", "Labour Day"], ["05-17", "Eid al-Adha"], ["05-18", "Eid al-Adha Holiday"], ["05-28", "Youm-e-Takbeer"], ["06-14", "Ashura (9 Muharram)"], ["06-15", "Ashura (10 Muharram)"], ["08-14", "Independence Day"], ["08-15", "Eid Milad un-Nabi"], ["10-29", "Diwali", 1], ["11-09", "Iqbal Day"], ["12-25", "Quaid-e-Azam Day / Christmas"]];
    D.holimport = Object.assign({}, q, { found: f.map(([md, name, opt]) => ({ d: y + "-" + md, name, moon: /eid|ashura|milad/i.test(name), optional: !!opt })), error: "", doneAt: Date.now() });
    RB.render();
  }, 700);
  RB.sections.settings = {
    tabs: () => { const ok = RB.settingsTabs(); return TABS.filter((t) => ok.includes(t[0])).concat([["holidays", "Holidays"]]).concat(RB.isOwner() ? [["log", "Change log"]] : []).concat([["updates", "Updates"], ["look", "Appearance"]]); },
    render(tab) {
      let html, side = "";
      if (tab === "look") {
        const cur = RB.theme.get(), card = (k, n, bg, cd) => `<button class="thm ${cur === k ? "on" : ""}" data-theme-pick="${k}"><div class="thp" style="background:${bg}"><i style="background:${cd}"></i><i style="background:${cd};width:85%"></i></div><b>${n}${cur === k ? " ✓" : ""}</b></button>`;
        html = `<div class="card" style="max-width:640px"><h3>Appearance</h3><div class="hint">Your own choice. It follows you to any laptop you sign in on. “Same as device” follows your computer's light or dark setting.</div>
          <div class="thms">${card("light", "Light", "#F4F4F2", "#fff")}${card("dark", "Dark", "#17181B", "#2E3035")}${card("auto", "Same as device", "linear-gradient(90deg,#F4F4F2 50%,#17181B 50%)", "#8f9198")}</div></div>`;
      } else if (tab === "updates") {
        const vi = RB.versionInfo ? RB.versionInfo() : {};
        html = `<div class="card" style="max-width:560px"><h3>Website updates</h3><div class="hint">A new version is published after every change. When one is ready, a bar at the bottom of the screen shows <b>Update</b> and stays until you press it.</div>
          <div class="rrow"><span class="n">This page is running</span><b>${esc(vi.version || "—")}</b>${vi.at ? `<span class="sub" style="margin-left:8px">published ${esc(RB.fmtWhen(Date.parse(vi.at)))}</span>` : ""}</div>
          <div style="display:flex;align-items:center;gap:14px;margin-top:12px"><button class="btnp" id="updchk">Check for updates</button><span class="sub" id="updres"></span></div></div>`;
      } else if (tab === "holidays") {
        const r = holidays();
        html = r.html; side = r.side;
      } else if (tab === "log") {
        const q = (S.q || "").toLowerCase();
        const list = D.changelog.filter((c) => !q || c.text.toLowerCase().includes(q) || (c.byName || "").toLowerCase().includes(q));
        html = `<div class="card" style="max-width:820px"><div class="subhead"><h3>Change log</h3></div><div class="hint">Every access, chart, seat and offboarding change. Only owners see it and nobody can edit or delete a line.</div>
          ${list.map((c) => `<div class="tlr"><span class="t" style="width:120px">${esc(RB.fmtWhen(c.at))}</span><div><b>${esc(c.text)}</b><p>by ${esc(c.byName || "")}</p></div></div>`).join("") || '<div class="muted">Nothing logged yet.</div>'}</div>`;
      } else if (tab === "docs") {
        const days = (D.cfg && D.cfg.readingDays) || 7;
        html = `<div class="card" style="max-width:560px"><h3>Reading list deadline</h3><div class="rrow"><span class="n">New people finish their reading in</span><input class="num" type="number" min="1" max="60" id="rdays" value="${days}"> days</div><button class="btnp edit-only" id="rsave" style="margin-top:12px">Save</button><div class="foot">Counted from the day he starts in the seat. The phone reminds him before it.</div></div>`;
      } else if (tab === "perf") html = perfHtml();
      else { S.settingsTab = tab; html = RB.meet.html("settings"); }
      return { html, side, title: "Settings", sub: "Only people with access can change these" };
    },
    bind(root, side) {
      root.querySelectorAll("[data-theme-pick]").forEach((b) => (b.onclick = () => { RB.theme.set(b.dataset.themePick); RB.render(); }));
      const sv = root.querySelector("#rsave");
      if (sv) sv.onclick = async () => { const n = Math.max(1, Math.min(60, Number(root.querySelector("#rdays").value) || 7)); await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { readingDays: n })); await RB.log("Reading deadline set to " + n + " days"); RB.alertBox("Saved."); };
      const uc = root.querySelector("#updchk");
      if (uc) uc.onclick = async () => {
        const out = root.querySelector("#updres"); uc.disabled = true; out.textContent = "Checking…";
        const r = RB.checkUpdate ? await RB.checkUpdate() : "offline";
        uc.disabled = false;
        out.textContent = r === "new" ? "A new version is ready. Press Update in the bar at the bottom." : r === "same" ? "You have the latest version." : "Couldn't check. Are you online?";
      };
      if (S.tab.settings === "holidays") holBind(root, side);
      const ps = root.querySelector("#tpsave");
      if (ps) ps.onclick = async () => {
        const v = (id, lo, hi) => Math.max(lo, Math.min(hi, Math.round(Number(root.querySelector(id).value)) || 0));
        const show = {}; root.querySelectorAll("[data-tpshow]").forEach((b) => { const [k, i] = b.dataset.tpshow.split(":"); (show[k] = show[k] || [])[Number(i)] = b.checked; });
        const perf = { wOn: v("#tpwon", 0, 100), wDos: v("#tpwdos", 0, 100), grace: v("#tpgrace", 1, 60), older: v("#tpolder", 1, 60), movedN: v("#tpmoved", 1, 20), redBelow: v("#tpred", 0, 100), weakDay: v("#tpweak", 0, 100),
          weeks: v("#tpweeks", 4, 26), oldOverN: v("#tpoldn", 1, 20), needsN: v("#tpneedn", 1, 20), period: root.querySelector("#tpperiod").value, show,
          hidden: [...root.querySelectorAll("[data-tphide]")].filter((b) => b.checked).map((b) => b.dataset.tphide) };
        if (perf.wOn + perf.wDos === 0) return RB.alertBox("At least one weight must be more than 0.");
        await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { perf }));
        await RB.log("Team Performance settings changed");
        RB.alertBox("Saved. Team Performance uses the new numbers now.");
      };
      if (!["log", "docs", "updates", "look", "holidays", "perf"].includes(S.tab.settings)) RB.meet.bind(root);
    },
  };
})();
