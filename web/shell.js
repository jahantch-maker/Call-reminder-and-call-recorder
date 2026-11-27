/* Shell: the dark icon rail, the light panel, page header and tabs, the right-hand person panel, routing and the phone layout. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  RB.sections = {};
  const ICONS = { app: "▦", meetings: "◷", team: "♙", plot: "▣", docs: "▤", settings: "⚙" };
  const NAMES = { app: "Team Performance", meetings: "Meetings", team: "Team", plot: "Plot files", docs: "Docs", settings: "Settings" };
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

  // Amber count on Team: people who have no timetable yet (their due times use 11 AM - 9 PM, Friday off until set).
  const badge = (k) => (k === "team" && RB.level("team") === "edit" && RB.noTimetable ? RB.noTimetable().length : 0);

  function tabsFor(sec) {
    const lvl = RB.level(sec);
    if (sec === "app") return [["people", "Overview"], ["person", "Person"]];
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
    // WEB10 Until the first data arrives, say so (never "No … yet", which looks like everything is gone).
    const wait = RB.loading && RB.loading();
    if (wait) page = { title: page.title, sub: page.sub, html: '<div class="card"><div class="muted loadmsg">Loading…</div></div>' };
    // Grouped left menu instead of the row of tabs (Settings redesign, locked 27 Nov): [group, [[key, label, icon, one line]]].
    const grp = RB.sections[sec].groups ? RB.sections[sec].groups(tabs) : null;
    const dt = new Date();
    const ownerSeat = RB.seatOfUid(RB.uidOf());
    const hi = "Hi, " + esc(RB.first(meRow.name || (D.team.ownerUid === RB.uidOf() ? "Jahanzaib" : "there")));
    const working = D.seats.filter((s) => s.status === "filled").length;
    const rail = allowed.map((k) => `<button class="ri-w" data-sec="${k}" title="${NAMES[k]}"><span class="ri ${k === sec ? "on" : ""}">${ICONS[k]}${badge(k) ? `<b class="bd amb" title="No timetable">${badge(k)}</b>` : ""}</span><span class="tip">${NAMES[k]}</span></button>`).join("");
    const running = RB.meet.running() && sec !== "meetings" ? `<div class="banner">Meeting running · <a href="#" class="link" data-sec="meetings">back to the meeting</a></div>` : "";
    // Fade in only when the page itself changes (section or tab); a click on the same page redraws without any flash.
    const sig = sec + "/" + tab, anim = sig !== RB._sig; RB._sig = sig;
    // WEB20 A redraw (cloud data, a click) keeps every scroll spot: the list, the side panel, and on narrow screens
    // (under 900px) the whole page, which scrolls .pmain instead of the list.
    const oldC = document.getElementById("content"), oldS = document.getElementById("side"), oldM = root.querySelector(".pmain");
    // The top keeps its folded (slim) state through the redraw; else the scroll spot lands on the taller unfolded page.
    const oldPc = !anim && root.querySelector(".pc"), pcCls = oldPc ? oldPc.className : "";
    const keepC = !anim && oldC ? oldC.scrollTop : 0, keepS = !anim && oldS ? oldS.scrollTop : 0, keepM = !anim && oldM ? oldM.scrollTop : 0;
    root.innerHTML = `<div class="app${anim ? "" : " still"}"><nav class="rail"><div class="rl"><img src="logo.svg" alt="Rafiq Builders"></div>${rail}<div class="grow"></div><button class="ri-w out" id="signout" title="Sign out"><span class="ri">⇥</span><span class="tip">Sign out</span></button></nav>
      <div class="pmain"><div class="pc">
        <div class="top"><div><h1>${page.title || hi}</h1><div class="s">${page.sub || (dt.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "short" }) + " · " + working + " people in the team")}</div></div><div class="sp"></div>
          <label class="srch"><span>⌕</span><input id="gq" placeholder="Search" value="${esc(S.q)}" autocomplete="off"></label><button class="bell" id="bell" title="Notices">🔔${noticeCount() ? `<b class="bd">${noticeCount()}</b>` : ""}</button></div>
        ${running}
        ${grp ? `<div class="gwrap">${gnav(grp, tab)}` : `<div class="tabs">${tabs.map(([k, l]) => `<button data-tab="${k}" class="${k === tab ? "on" : ""}">${l}</button>`).join("")}${page.tabsExtra ? `<span class="tabx">${page.tabsExtra}</span>` : ""}</div>`}
        <div id="content" class="content${anim ? " fade" : ""} ${RB.level(sec) === "view" ? "ro" : ""}">${grp && !wait ? ghead(grp, tab) : ""}${page.html}</div>${grp ? "</div>" : ""}
      </div>${page.side ? `<aside class="pside${anim ? " fade" : ""}" id="side">${page.side}</aside>` : ""}</div></div>`;
    if (pcCls) root.querySelector(".pc").className = pcCls + " notr";
    RB.stickHeads(document.getElementById("content"));   // wrap heads before the scroll spot is put back, else every card above it changes height and the page jumps
    if (keepC) { const c = document.getElementById("content"); if (c) c.scrollTop = keepC; }
    if (keepS) { const sd = document.getElementById("side"); if (sd) sd.scrollTop = keepS; }
    if (keepM) { const pm = root.querySelector(".pmain"); if (pm) pm.scrollTop = keepM; }
    root.querySelectorAll("[data-sec]").forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); RB.go(e.dataset.sec); }));
    root.querySelectorAll("[data-tab]").forEach((e) => (e.onclick = () => { setTab(sec, e.dataset.tab); RB.render(); }));
    const gp = document.getElementById("gpick"); if (gp) gp.onchange = () => { setTab(sec, gp.value); RB.render(); };
    const so = document.getElementById("signout"); if (so) so.onclick = () => (RB.DEMO ? RB.alertBox("Sign out is off in the sample preview.") : firebase.auth().signOut().then(() => location.reload()));
    const gq = document.getElementById("gq");
    gq.oninput = () => { S.q = gq.value; RB.render(); const n = document.getElementById("gq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
    document.getElementById("bell").onclick = notices;
    const content = document.getElementById("content");
    slimTop(root.querySelector(".pc"), content);
    if (wait) return;
    if (sec === "app" || sec === "meetings") RB.meet.bind(content);
    if (RB.sections[sec].bind) RB.sections[sec].bind(content, document.getElementById("side"));
    if (page.afterBind) page.afterBind();
    RB.stickHeads(content);
  };
  /** The left menu (computer) and, on phones and narrow windows, one "Section ▾" picker in its place. */
  function gnav(grp, tab) {
    const all = grp.flatMap((g) => g[1]), cur = all.find((x) => x[0] === tab) || all[0] || ["", "", ""];
    return `<nav class="gnav" aria-label="Sections">${grp.map(([g, it]) => `<h6>${esc(g)}</h6>` + it.map(([k, l, ic]) => `<button data-tab="${k}" class="${k === tab ? "on" : ""}" ${k === tab ? 'aria-current="page"' : ""}>${ic || ""}<span>${esc(l)}</span></button>`).join("")).join("")}</nav>
      <label class="gpick">${cur[2] || ""}<b>${esc(cur[1])}</b><span>Section ▾</span><select id="gpick" aria-label="Section">${grp.map(([g, it]) => `<optgroup label="${esc(g)}">${it.map(([k, l]) => `<option value="${k}" ${k === tab ? "selected" : ""}>${esc(l)}</option>`).join("")}</optgroup>`).join("")}</select></label>`;
  }
  /** The open section's big name and one line on what it does (scrolls away with the page). */
  function ghead(grp, tab) {
    const x = grp.flatMap((g) => g[1]).find((i) => i[0] === tab); if (!x) return "";
    return `<div class="ghead"><h2>${esc(x[1])}</h2>${x[3] ? `<p>${x[3]}</p>` : ""}</div>`;
  }
  /** Thin line icons (RB navy) for the left menus: RB.lineIcon("name"). */
  const LI = {
    late: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    red: '<path d="M12 3.5 21 19.5H3z"/><path d="M12 10v4.5M12 17.2v.1"/>',
    steps: '<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r="1.2"/><circle cx="4.5" cy="12" r="1.2"/><circle cx="4.5" cy="18" r="1.2"/>',
    rank: '<path d="M8 4h8v5a4 4 0 0 1-8 0z"/><path d="M8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4M12 13v4M8.5 20h7"/>',
    read: '<path d="M4 5.5A2 2 0 0 1 6 4h5v15H6a2 2 0 0 0-2 1.5z"/><path d="M20 5.5A2 2 0 0 0 18 4h-5v15h5a2 2 0 0 1 2 1.5z"/>',
    chart: '<path d="M4 20V10M10 20V4M16 20v-7M21 20H3"/>',
    cal: '<rect x="3.5" y="5" width="17" height="15" rx="2"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    rules: '<path d="M6 3.5h9l3.5 3.5v13.5H6z"/><path d="M9 11h6.5M9 14.5h6.5M9 18h4"/>',
    log: '<path d="M3.5 12a8.5 8.5 0 1 0 2.5-6"/><path d="M3.5 4v4h4M12 8v4.5l3 1.5"/>',
    upd: '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19.5h14"/>',
    look: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
    people: '<circle cx="9" cy="8" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 5.5a3 3 0 0 1 0 5.5M17.5 14a5 5 0 0 1 3 5"/>',
    phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a1 1 0 0 1-1 1A16 16 0 0 1 4 5a1 1 0 0 1 1-1z"/>',
    folder: '<path d="M3.5 7a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
    alarm: '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 1.5M5 4 3 6M19 4l2 2"/>',
    screen: '<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M8.5 20h7M12 16.5V20"/>',
    bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
    user: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
    pen: '<path d="M15.5 4.5 19.5 8.5 8.5 19.5H4.5v-4z"/>',
    bin: '<path d="M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13"/>',
    undo: '<path d="M9 7 4.5 11.5 9 16"/><path d="M4.5 11.5H15a4.5 4.5 0 0 1 0 9h-2"/>',
  };
  RB.lineIcon = (k) => `<svg class="li" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${LI[k] || ""}</svg>`;

  /** WEB16 Slim top (locked 24 Nov): the moment the page is scrolled its top folds (a plot file: into its slim line), and it
   *  opens again back at the very top. It folds only when the page is long enough to stay scrolled once folded, so it never
   *  flickers. A redraw keeps the state with no animation. */
  function slimTop(pc, content) {
    if (!pc || !content) return;
    const set = () => {
      const y = content.scrollTop, fix = content.querySelector(".plfix"), on = pc.classList.contains("slim");
      if (fix && !on) pc.style.setProperty("--pfh", fix.scrollHeight + "px");
      const room = content.scrollHeight - content.clientHeight - (fix && !on ? fix.offsetHeight : 0) - 160;
      pc.classList.toggle("slim", on ? y > 4 : y > 4 && room > 40);
      pc.classList.toggle("pfpage", !!fix);
    };
    pc.classList.add("notr"); set();
    requestAnimationFrame(() => requestAnimationFrame(() => pc.classList.remove("notr")));
    content.addEventListener("scroll", set, { passive: true });
  }

  /** Frozen top bars (locked 23 Nov, WEB7): in every page card, the title bar (count, buttons, filters), the help note
   *  under it and the column names stay in place while the list scrolls. Done once here for every section. */
  RB.stickHeads = (content) => {
    if (!content) return;
    content.querySelectorAll(".card").forEach((card) => {
      if (card.parentElement.closest(".card")) return;   // only page cards, not cards inside cards
      let head = card.firstElementChild;
      if (!head) return;
      if (!head.classList.contains("stickhead")) {
        if (!head.classList.contains("subhead") && head.tagName !== "H3") return;   // WEB12 a plain heading counts too
        const w = document.createElement("div"); w.className = "stickhead";
        card.insertBefore(w, head);
        while (w.nextElementSibling && (w.childElementCount === 0 || /\b(hint|plseg|plsts|plsearch)\b/.test(w.nextElementSibling.className))) w.appendChild(w.nextElementSibling);
      }
      const ph = card.querySelector(":scope > .pltable > .plhead");
      if (ph) card.firstElementChild.appendChild(ph);
    });
  };

  // ------------------------------------------------------------------ notices (bell)
  function noticeList() {
    const out = [], now = Date.now();
    D.docs.filter((d) => d.type === "sop" && d.reviewBy && d.reviewBy < now && (RB.isOwner() || RB.canEdit("docs"))).forEach((d) => out.push(["docs", "sops", "Review due: " + d.title, "The review date has passed"]));
    return (RB.holNotices ? RB.holNotices() : []).concat(out);
  }
  const noticeCount = () => noticeList().length;
  function notices() {
    const l = noticeList(), m = document.getElementById("modal");
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">Notices</b>${l.length ? l.map((n, i) => `<div class="arow click" data-n="${i}"><span class="w">${esc(n[2])}<span>${esc(n[3])}</span></span><span class="link">${n[4] ? "Review and add ›" : "Open ›"}</span></div>`).join("") : '<div class="muted" style="margin:12px 0">Nothing needs you right now.</div>'}<div style="text-align:right;margin-top:12px"><button class="btnp" id="ok">Close</button></div></div></div>`;
    document.getElementById("ok").onclick = () => (m.innerHTML = "");
    m.querySelectorAll("[data-n]").forEach((e) => (e.onclick = () => { const n = l[Number(e.dataset.n)]; m.innerHTML = ""; if (n[4]) return n[4](); RB.go(n[0], n[1]); }));
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
  // Phone (11 digits, Pakistani mobile) and CNIC (13 digits) boxes: digits only, no more than that, spaced as you type
  // (0300 1234567 · 35202-1234567-1), the same as the phone app (locked 23 Nov). +92 3xx becomes 03xx.
  RB.digitsFmt = (v, kind) => {
    let d = String(v || "").replace(/\D/g, "");
    if (kind === "phone" && /^923/.test(d)) d = "0" + d.slice(2);
    if (kind === "phone") { d = d.slice(0, 11); return d.length > 4 ? d.slice(0, 4) + " " + d.slice(4) : d; }
    d = d.slice(0, 13); return d.length > 12 ? d.slice(0, 5) + "-" + d.slice(5, 12) + "-" + d.slice(12) : d.length > 5 ? d.slice(0, 5) + "-" + d.slice(5) : d;
  };
  /** A Pakistani mobile: empty, or 11 digits starting 03. */
  RB.phoneOk = (v) => { const d = String(v || "").replace(/\D/g, ""); return !d || /^03\d{9}$/.test(d); };
  document.addEventListener("input", (e) => {
    const el = e.target; if (!el || !el.dataset || !el.dataset.dig) return;
    const f = RB.digitsFmt(el.value, el.dataset.dig); if (f !== el.value) el.value = f;
  });
  /** A small form dialog. fields: [{k, label, value, type, options}] → fn(values) */
  RB.form = (title, fields, yes, fn, note) => {
    const m = document.getElementById("modal");
    const f = (x) => x.type === "select" ? `<select data-k="${x.k}">${x.options.map((o) => { const v = Array.isArray(o) ? o[0] : o, l = Array.isArray(o) ? o[1] : o; return `<option value="${esc(v)}" ${String(x.value) === String(v) ? "selected" : ""}>${esc(l)}</option>`; }).join("")}</select>`
      : x.type === "chips" ? `<div class="chk" data-k="${x.k}" data-multi="1">${x.options.map((o) => `<label><input type="checkbox" value="${esc(o)}" ${(x.value || []).includes(o) ? "checked" : ""}> ${esc(o)}</label>`).join("")}</div>${x.hint ? `<div class="sub">${esc(x.hint)}</div>` : ""}`
      : x.type === "textarea" ? `<textarea data-k="${x.k}" rows="4" placeholder="${esc(x.ph || "")}">${esc(x.value || "")}</textarea>`
      : x.type === "phone" || x.type === "cnic" ? `<input data-k="${x.k}" data-dig="${x.type}" type="text" inputmode="numeric" autocomplete="off" value="${esc(RB.digitsFmt(x.value, x.type))}" placeholder="${esc(x.ph || (x.type === "cnic" ? "35202-1234567-1" : "0300 1234567"))}">`
      : `<input data-k="${x.k}" type="${x.type || "text"}" value="${esc(x.value || "")}" placeholder="${esc(x.ph || "")}">`;
    m.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">${esc(title)}</b>${note ? `<div class="foot">${note}</div>` : ""}${fields.map((x) => `<div class="lbl">${esc(x.label)}</div>${f(x)}`).join("")}<div class="err" id="ferr"></div><div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="fc">Cancel</button><button class="btnp" id="fy">${esc(yes)}</button></div></div></div>`;
    document.getElementById("fc").onclick = () => (m.innerHTML = "");
    document.getElementById("fy").onclick = async () => {
      const v = {}; m.querySelectorAll("[data-k]").forEach((e) => (v[e.dataset.k] = e.dataset.multi ? Array.from(e.querySelectorAll("input:checked")).map((i) => i.value) : e.value));
      try { const r = await fn(v); if (typeof r === "string" && r) { document.getElementById("ferr").textContent = r; return; } } catch (e) { document.getElementById("ferr").textContent = String(e.message || e); return; }
      m.innerHTML = ""; RB.render();
    };
    const first = m.querySelector("[data-k]"); if (first) first.focus();
  };
})();
