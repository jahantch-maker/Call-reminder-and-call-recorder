/* Settings: late-reason list, red lines, meeting steps, ranking, reading deadline and the change log (owners). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  /** The five tabs the owner can tick per person (RB.SET_TABS); Change log is for owners, Updates and Appearance for everyone. */
  const TABS = RB.SET_TABS;
  RB.sections.settings = {
    tabs: () => { const ok = RB.settingsTabs(); return TABS.filter((t) => ok.includes(t[0])).concat(RB.isOwner() ? [["log", "Change log"]] : []).concat([["updates", "Updates"], ["look", "Appearance"]]); },
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
      } else if (tab === "log") {
        const q = (S.q || "").toLowerCase();
        const list = D.changelog.filter((c) => !q || c.text.toLowerCase().includes(q) || (c.byName || "").toLowerCase().includes(q));
        html = `<div class="card" style="max-width:820px"><div class="subhead"><h3>Change log</h3></div><div class="hint">Every access, chart, seat and offboarding change. Only owners see it and nobody can edit or delete a line.</div>
          ${list.map((c) => `<div class="tlr"><span class="t" style="width:120px">${esc(RB.fmtWhen(c.at))}</span><div><b>${esc(c.text)}</b><p>by ${esc(c.byName || "")}</p></div></div>`).join("") || '<div class="muted">Nothing logged yet.</div>'}</div>`;
      } else if (tab === "docs") {
        const days = (D.cfg && D.cfg.readingDays) || 7;
        html = `<div class="card" style="max-width:560px"><h3>Reading list deadline</h3><div class="rrow"><span class="n">New people finish their reading in</span><input class="num" type="number" min="1" max="60" id="rdays" value="${days}"> days</div><button class="btnp edit-only" id="rsave" style="margin-top:12px">Save</button><div class="foot">Counted from the day he starts in the seat. The phone reminds him before it.</div></div>`;
      } else { S.settingsTab = tab; html = RB.meet.html("settings"); }
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
      if (!["log", "docs", "updates", "look"].includes(S.tab.settings)) RB.meet.bind(root);
    },
  };
})();
