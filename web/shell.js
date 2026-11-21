/* Shell: the dark icon rail, the light panel, page header and tabs, the right-hand person panel, routing and the phone layout. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  RB.sections = {};
  const ICONS = { app: "▦", meetings: "◷", team: "♙", plot: "▣", docs: "▤", settings: "⚙" };
  const NAMES = { app: "App view", meetings: "Meetings", team: "Team", plot: "Plot files", docs: "Docs", settings: "Settings" };
  S.sec = null; S.tab = { team: "people", plot: "files", docs: "reading", settings: "reasons" }; S.views = { app: "people", meetings: "home" }; S.q = "";

  /** Sections this person may open. Docs is open to everyone with access; staff see their reading list. */
  RB.allowed = () => ["app", "meetings", "team", "plot", "docs", "settings"].filter((k) => (RB.level(k) !== "none" || (k === "settings" && RB.anyAccess())));
  RB.go = (sec, tab) => {
    if (S.sec && S.sec !== sec && (S.sec === "app" || S.sec === "meetings")) S.views[S.sec] = S.view;
    S.sec = sec;
    if (tab) { if (sec === "app" || sec === "meetings") { S.view = tab; S.views[sec] = tab; } else S.tab[sec] = tab; }
    else if (sec === "app" || sec === "meetings") S.view = S.views[sec];
    RB.render(); const c = document.getElementById("content"); if (c) c.scrollTop = 0;
  };
  RB.ready = () => {
    const a = RB.allowed(); S.sec = a[0] || "docs"; S.view = S.views[S.sec] || "people";
    const back = restore();
    RB.render();
    // Back to the same scroll spot; data that arrives a moment later can make the page taller, so try again then.
    if (back) [0, 400, 1200].forEach((ms) => setTimeout(() => { const c = document.getElementById("content"); if (c) c.scrollTop = back.top; window.scrollTo(0, back.wy || 0); }, ms));
  };

  // A refresh lands where you were: section, tab, open item and scroll spot are kept for this browser tab only.
  const KEEP = "rb_place";
  const plain = (v) => v === null || ["string", "number", "boolean"].includes(typeof v) || ((Array.isArray(v) || (v && v.constructor === Object)) && Object.values(v).every(plain));
  window.addEventListener("pagehide", () => {
    if (!S.sec) return;
    const st = {}; Object.keys(S).forEach((k) => { if (plain(S[k])) st[k] = S[k]; });
    const c = document.getElementById("content");
    try { sessionStorage.setItem(KEEP, JSON.stringify({ S: st, top: c ? c.scrollTop : 0, wy: window.scrollY })); } catch (e) { /* remembering is optional */ }
  });
  function restore() {
    let v = null;
    try { v = JSON.parse(sessionStorage.getItem(KEEP) || "null"); sessionStorage.removeItem(KEEP); } catch (e) { v = null; }
    if (!v || !v.S || !RB.allowed().includes(v.S.sec)) return null;
    Object.keys(v.S).forEach((k) => { if (!(k in S) || plain(S[k])) S[k] = v.S[k]; });
    return v;
  }

  const waiting = () => D.members.filter((m) => m.status === "pending");
  RB.waiting = waiting;
  const badge = (k) => (k === "team" && RB.level("team") === "edit" && waiting().length ? waiting().length : 0);

  function tabsFor(sec) {
    const lvl = RB.level(sec);
    if (sec === "app") return [["people", "Overview"], ["person", "Person"], ["calendar", "Calendar"], ["reasons", "Late reasons"]];
    if (sec === "meetings") return lvl === "edit" ? [["home", "Start meeting"], ["actions", "Actions"], ["minutes", "Minutes"]] : [["actions", "Actions"], ["minutes", "Minutes"]];
    return RB.sections[sec] ? RB.sections[sec].tabs() : [];
  }
  const curTab = (sec) => (sec === "app" || sec === "meetings" ? S.view : S.tab[sec]);
  function setTab(sec, t) { if (sec === "app" || sec === "meetings") { S.view = t; S.views[sec] = t; } else S.tab[sec] = t; }

  RB.render = function () {
    const root = document.getElementById("app");
    if (!D.team || !S.sec) return;
    const allowed = RB.allowed();
    if (!allowed.includes(S.sec)) S.sec = allowed[0] || "docs";
    const sec = S.sec, meRow = RB.member(RB.uidOf()) || {};
    const tabs = tabsFor(sec);
    if (!tabs.some((t) => t[0] === curTab(sec)) && !(RB.meet.running() && sec === "meetings")) setTab(sec, tabs[0] ? tabs[0][0] : "");
    // Meetings "view" level: only minutes and actions (no start page)
    const tab = curTab(sec);
    let page;
    if (sec === "app" || sec === "meetings") page = RB.sections[sec].render(tab);
    else page = RB.sections[sec].render(tab);
    const dt = new Date();
    const ownerSeat = RB.seatOfUid(RB.uidOf());
    const hi = "Hi, " + esc(RB.first(meRow.name || (D.team.ownerUid === RB.uidOf() ? "Jahanzaib" : "there")));
    const working = D.seats.filter((s) => s.status === "filled").length;
    const rail = allowed.map((k) => `<button class="ri-w" data-sec="${k}" title="${NAMES[k]}"><span class="ri ${k === sec ? "on" : ""}">${ICONS[k]}${badge(k) ? `<b class="bd">${badge(k)}</b>` : ""}</span><span class="tip">${NAMES[k]}</span></button>`).join("");
    const running = RB.meet.running() && sec !== "meetings" ? `<div class="banner">Meeting running · <a href="#" class="link" data-sec="meetings">back to the meeting</a></div>` : "";
    // Fade in only when the page itself changes (section or tab); a click on the same page redraws without any flash.
    const sig = sec + "/" + tab, anim = sig !== RB._sig; RB._sig = sig;
    const oldC = document.getElementById("content"), oldS = document.getElementById("side");
    const keepC = !anim && oldC ? oldC.scrollTop : 0, keepS = !anim && oldS ? oldS.scrollTop : 0;
    root.innerHTML = `<div class="app${anim ? "" : " still"}"><nav class="rail"><div class="rl"><img src="logo.svg" alt="Rafiq Builders"></div>${rail}<div class="grow"></div><button class="ri-w out" id="signout" title="Sign out"><span class="ri">⇥</span><span class="tip">Sign out</span></button></nav>
      <div class="pmain"><div class="pc">
        <div class="top"><div><h1>${page.title || hi}</h1><div class="s">${page.sub || (dt.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "short" }) + " · " + working + " people in the team")}</div></div><div class="sp"></div>
          <label class="srch"><span>⌕</span><input id="gq" placeholder="Search" value="${esc(S.q)}" autocomplete="off"></label><button class="bell" id="bell" title="Notices">🔔${noticeCount() ? `<b class="bd">${noticeCount()}</b>` : ""}</button></div>
        ${running}
        <div class="tabs">${tabs.map(([k, l]) => `<button data-tab="${k}" class="${k === tab ? "on" : ""}">${l}</button>`).join("")}</div>
        <div id="content" class="content${anim ? " fade" : ""} ${RB.level(sec) === "view" ? "ro" : ""}">${page.html}</div>
      </div>${page.side ? `<aside class="pside${anim ? " fade" : ""}" id="side">${page.side}</aside>` : ""}</div></div>`;
    if (keepC) { const c = document.getElementById("content"); if (c) c.scrollTop = keepC; }
    if (keepS) { const sd = document.getElementById("side"); if (sd) sd.scrollTop = keepS; }
    root.querySelectorAll("[data-sec]").forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); RB.go(e.dataset.sec); }));
    root.querySelectorAll("[data-tab]").forEach((e) => (e.onclick = () => { setTab(sec, e.dataset.tab); RB.render(); }));
    const so = document.getElementById("signout"); if (so) so.onclick = () => (RB.DEMO ? RB.alertBox("Sign out is off in the sample preview.") : firebase.auth().signOut().then(() => location.reload()));
    const gq = document.getElementById("gq");
    gq.oninput = () => { S.q = gq.value; RB.render(); const n = document.getElementById("gq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
    document.getElementById("bell").onclick = notices;
    const content = document.getElementById("content");
    if (sec === "app" || sec === "meetings") RB.meet.bind(content);
    if (RB.sections[sec].bind) RB.sections[sec].bind(content, document.getElementById("side"));
    if (page.afterBind) page.afterBind();
  };

  // ------------------------------------------------------------------ notices (bell)
  function noticeList() {
    const out = [], now = Date.now();
    if (RB.level("team") === "edit" && waiting().length) out.push(["team", "people-waiting", waiting().length + " waiting to join", "Approve or reject in Team › Waiting"]);
    D.docs.filter((d) => d.type === "sop" && d.reviewBy && d.reviewBy < now && (RB.isOwner() || RB.canEdit("docs"))).forEach((d) => out.push(["docs", "sops", "Review due: " + d.title, "The review date has passed"]));
    return out;
  }
  const noticeCount = () => noticeList().length;
  function notices() {
    const l = noticeList(), m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">Notices</b>${l.length ? l.map((n, i) => `<div class="arow click" data-n="${i}"><span class="w">${esc(n[2])}<span>${esc(n[3])}</span></span><span class="link">Open ›</span></div>`).join("") : '<div class="muted" style="margin:12px 0">Nothing needs you right now.</div>'}<div style="text-align:right;margin-top:12px"><button class="btnp" id="ok">Close</button></div></div></div>`;
    document.getElementById("ok").onclick = () => (m.innerHTML = "");
    m.querySelectorAll("[data-n]").forEach((e) => (e.onclick = () => { const n = l[Number(e.dataset.n)]; m.innerHTML = ""; if (n[0] === "team") { S.tab.team = "waiting"; } else if (n[0] === "docs") { S.tab.docs = n[1]; } RB.go(n[0]); }));
  }

  // ------------------------------------------------------------------ dialogs for all pages
  RB.confirm = (text, yes, fn, danger) => {
    const m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg"><div style="font-size:15px;line-height:1.45">${text}</div><div style="display:flex;justify-content:flex-end;gap:16px;margin-top:16px"><button class="link" id="cn">Cancel</button><button class="${danger ? "btnd" : "btnp"}" id="cy">${esc(yes)}</button></div></div></div>`;
    document.getElementById("cn").onclick = () => (m.innerHTML = "");
    document.getElementById("cy").onclick = async () => { m.innerHTML = ""; await fn(); RB.render(); };
  };
  // Enter saves: in any dialog it presses the main button (never a red Delete); on a page it presses the card's Save.
  // Escape closes whatever popup is open (as its Cancel / Close / OK would), anywhere in the portal.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    const m = document.getElementById("modal"); if (!m || !m.innerHTML.trim()) return;
    e.preventDefault();
    const btns = Array.from(m.querySelectorAll("button"));
    const c = m.querySelector("#cn, #fc, #cpc, #ok") || btns.find((x) => /^\s*(cancel|close|ok|×|✕)\s*$/i.test(x.textContent));
    if (c) c.click(); else m.innerHTML = "";
  });
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Enter" || e.shiftKey || e.isComposing) return;
    const el = e.target; if (!el || el.tagName === "TEXTAREA" || el.tagName === "BUTTON") return;
    if (!/^(INPUT|SELECT)$/.test(el.tagName)) return;
    const dlg = el.closest(".dlg");
    if (dlg) { const b = Array.from(dlg.querySelectorAll("button.btnp")).pop(); if (b && !b.disabled) { e.preventDefault(); b.click(); } return; }
    const card = el.closest(".card"); if (!card) return;
    const b = Array.from(card.querySelectorAll("button")).find((x) => /^\s*save\b/i.test(x.textContent) && !x.disabled);
    if (b) { e.preventDefault(); b.click(); }
  });
  /** A small form dialog. fields: [{k, label, value, type, options}] → fn(values) */
  RB.form = (title, fields, yes, fn, note) => {
    const m = document.getElementById("modal");
    const f = (x) => x.type === "select" ? `<select data-k="${x.k}">${x.options.map((o) => { const v = Array.isArray(o) ? o[0] : o, l = Array.isArray(o) ? o[1] : o; return `<option value="${esc(v)}" ${String(x.value) === String(v) ? "selected" : ""}>${esc(l)}</option>`; }).join("")}</select>`
      : x.type === "textarea" ? `<textarea data-k="${x.k}" rows="4" placeholder="${esc(x.ph || "")}">${esc(x.value || "")}</textarea>`
      : `<input data-k="${x.k}" type="${x.type || "text"}" value="${esc(x.value || "")}" placeholder="${esc(x.ph || "")}">`;
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">${esc(title)}</b>${note ? `<div class="foot">${note}</div>` : ""}${fields.map((x) => `<div class="lbl">${esc(x.label)}</div>${f(x)}`).join("")}<div class="err" id="ferr"></div><div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="fc">Cancel</button><button class="btnp" id="fy">${esc(yes)}</button></div></div></div>`;
    document.getElementById("fc").onclick = () => (m.innerHTML = "");
    document.getElementById("fy").onclick = async () => {
      const v = {}; m.querySelectorAll("[data-k]").forEach((e) => (v[e.dataset.k] = e.value));
      try { const r = await fn(v); if (typeof r === "string" && r) { document.getElementById("ferr").textContent = r; return; } } catch (e) { document.getElementById("ferr").textContent = String(e.message || e); return; }
      m.innerHTML = ""; RB.render();
    };
    const first = m.querySelector("[data-k]"); if (first) first.focus();
  };
})();
