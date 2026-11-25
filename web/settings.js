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
  RB.sections.settings = {
    tabs: () => { const ok = RB.settingsTabs(); return TABS.filter((t) => ok.includes(t[0])).concat([["holidays", "Holidays"]]).concat(RB.isOwner() ? [["log", "Change log"]] : []).concat([["updates", "Updates"], ["look", "Appearance"]]); },
    render(tab) {
      let html;
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
        // Office holidays for the whole company. Every SOP (Plot files and any later one) counts its days from here.
        const H = D.holidays || {}, own = RB.isOwner();
        const list = (H.dates || []).slice().sort((a, b) => (a.d < b.d ? -1 : 1));
        const fmt = (d) => new Date(d + "T12:00:00").toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" });
        html = `<div class="card" style="max-width:640px"><h3>Holidays</h3><div class="hint">Public holidays and other days the office is closed. Due times in every SOP skip these dates. Weekly days off are set per timetable, in Team › Timetables.</div>
          ${list.map((h) => `<div class="rrow"><span class="n">${esc(fmt(h.d))}</span><b style="flex:1;min-width:0">${esc(h.name || "")}</b>${own ? `<button class="btn" data-hdel="${esc(h.d)}">Remove</button>` : ""}</div>`).join("") || '<div class="muted">No holidays added yet.</div>'}
          ${own ? `<div class="rrow" style="flex-wrap:wrap;gap:8px;margin-top:12px"><input type="date" id="hdate"><input id="hname" placeholder="Name, e.g. Eid ul Fitr" style="flex:1;min-width:160px"><button class="btnp" id="hadd">Add</button></div>` : '<div class="foot">Only the owner changes holidays.</div>'}</div>`;
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
      return { html, title: "Settings", sub: "Only people with access can change these" };
    },
    bind(root) {
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
      const hsave = (data, note) => RB.Store.saveConfig("holidays", Object.assign({ dates: [] }, D.holidays || {}, data)).then(() => RB.log(note)).then(() => RB.render());
      const ha = root.querySelector("#hadd");
      if (ha) ha.onclick = () => {
        const d = root.querySelector("#hdate").value, name = root.querySelector("#hname").value.trim();
        if (!d) return RB.alertBox("Pick a date first.");
        const dates = ((D.holidays && D.holidays.dates) || []).filter((h) => h.d !== d).concat([{ d, name }]);
        hsave({ dates }, "Holiday added: " + d + (name ? " (" + name + ")" : ""));
      };
      root.querySelectorAll("[data-hdel]").forEach((b) => (b.onclick = () => { const d = b.dataset.hdel; hsave({ dates: ((D.holidays && D.holidays.dates) || []).filter((h) => h.d !== d) }, "Holiday removed: " + d); }));
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
