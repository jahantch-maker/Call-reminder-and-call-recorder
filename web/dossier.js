/* Team › Dossier (locked 25 Nov): only the owner adds, changes and deletes each person's dossier tasks, here on the web.
 * Phones only see and tick them. Data: teams/{t}/dossier/{uid} { blocks: [TeamBlock], viewers, updatedAt, by,
 * seen: { blockId: ms } (written by his phone when he taps OK), old / oldOwn: the dossier deleted on update day }.
 * A block is the phone's TeamBlock plus changedAt (ms of the last add or change), so his phone knows what is new. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const ALL = 127, MONSAT = 63, DAYN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]; // bit i = DAYN[i] (phone order)
  const FALLBACK = { start: 11 * 60, end: 21 * 60, off: [5] };
  const hm = (m) => { m = ((m % 1440) + 1440) % 1440; const h = Math.floor(m / 60); return (h % 12 || 12) + ":" + String(m % 60).padStart(2, "0") + (h < 12 ? " AM" : " PM"); };
  const range = (b) => hm(b.minute) + " – " + hm(b.endMinute);
  const ord = (n) => n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : ["th", "st", "nd", "rd"][n % 10] || "th");
  const t2m = (v) => { const m = String(v || "").match(/^(\d{1,2}):(\d{2})/); return m ? Number(m[1]) * 60 + Number(m[2]) : NaN; };
  const m2t = (n) => String(Math.floor(n / 60) % 24).padStart(2, "0") + ":" + String(n % 60).padStart(2, "0");
  const bits = (days) => [0, 1, 2, 3, 4, 5, 6].filter((i) => days & (1 << i));
  function freq(b) {
    if (b.monthDay > 0) return "Monthly · on the " + ord(b.monthDay);
    const on = bits(b.days), every = (b.everyWeeks || 1) > 1 ? " · every " + b.everyWeeks + " weeks" : "";
    if (on.length === 7) return "Daily";
    if ((b.days & ALL) === MONSAT) return "Mon–Sat" + every;
    return "Weekly · " + on.map((i) => DAYN[i]).join(", ") + every;
  }
  const extras = (b) => [b.proof && "📷 Photo proof", b.remindBefore > 0 && "🔔 " + (b.remindBefore >= 60 ? b.remindBefore / 60 + " hour" : b.remindBefore + " min") + " before", b.afterReview && "📋 After evening review", b.onOffDay && "Also on off day"].filter(Boolean);
  /** "9:12 AM" today, else "24 Nov". */
  const seenAt = (t) => (new Date(t).toDateString() === new Date().toDateString() ? new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }) : RB.fmtShort(t));
  const say = (b) => b.title + " · " + range(b) + " · " + freq(b);

  /** Everyone whose dossier the owner sets: all active members, the owner too (J19, 25 Nov). */
  RB.dosPeople = () => D.members.filter((m) => m.status === "active").sort((a, b) => RB.first(a.name).localeCompare(RB.first(b.name)));
  const doc = (uid) => D.dossier[uid] || {};
  const blocks = (uid) => (doc(uid).blocks || []).slice().sort((a, b) => a.minute - b.minute || a.endMinute - b.endMinute);
  const unseen = (uid, b) => !!b.changedAt && !(((doc(uid).seen || {})[b.id] || 0) >= b.changedAt);
  const ttOf = (uid) => RB.ttOf(RB.seatOfUid(uid)) || FALLBACK;
  /** Same rule as the phone (TeamBlock.clashes): same days and the times overlap. */
  const clashes = (a, b) => (a.monthDay > 0 || b.monthDay > 0 || (a.days & b.days) !== 0) && a.minute < b.endMinute && b.minute < a.endMinute;
  const viewers = (uid) => { const m = RB.member(uid) || {}; return [uid, D.team.ownerUid].concat(m.managers || []).filter((x, i, a) => x && a.indexOf(x) === i); };

  async function saveBlocks(uid, list, note) {
    RB.hold = true;
    try {
      await RB.Store.put("dossier", uid, Object.assign({}, RB.DEMO ? doc(uid) : {}, { blocks: list.slice().sort((a, b) => a.minute - b.minute), viewers: viewers(uid), updatedAt: Date.now(), by: RB.uidOf() || "" }), true);
      try { await RB.log(note); } catch (e) { console.warn("log", e); }
    } finally { RB.hold = false; RB.render(); }
  }

  /** Update day (locked 25 Nov): blocks set on the phone (no changedAt) are moved to "old" and logged, once, by the owner's web. */
  RB.dosLegacy = (uid) => {
    if (!RB.isOwner() || RB.DEMO) return;
    const d = doc(uid), legacy = (d.blocks || []).filter((b) => !b.changedAt), nm = RB.first((RB.member(uid) || {}).name);
    // The tasks he made himself were deleted on his phone on update day; his phone sent the list. Logged once.
    if ((d.oldOwn || []).length && !d.oldOwnLogged) {
      RB.Store.put("dossier", uid, { oldOwnLogged: true }, true, true);
      RB.log("Dossier cleared on update day · " + nm + " · " + d.oldOwn.length + " tasks he made himself: " + d.oldOwn.map(say).join(" | ")).catch(() => {});
    }
    if (!legacy.length) return;
    const m = RB.member(uid) || {}, keep = (d.blocks || []).filter((b) => b.changedAt);
    RB.Store.put("dossier", uid, { blocks: keep, old: (d.old || []).concat(legacy.map((b) => Object.assign({}, b, { removedAt: Date.now() }))), viewers: viewers(uid), updatedAt: Date.now(), by: RB.uidOf() || "" }, true, true);
    RB.log("Dossier cleared on update day · " + RB.first(m.name) + " · " + legacy.length + " old tasks: " + legacy.map(say).join(" | ")).catch(() => {});
  };

  // ------------------------------------------------------------------ the page
  function page() {
    const ppl = RB.dosPeople();
    if (!ppl.length) return { html: '<div class="card"><div class="muted">Nobody has joined the team yet.</div></div>' };
    if (!ppl.some((m) => m.uid === S.dosSel)) S.dosSel = ppl[0].uid;
    const uid = S.dosSel, m = RB.member(uid), seat = RB.seatOfUid(uid) || {}, tt = RB.ttOf(seat), E = S.dosEdit && S.dosEdit.uid === uid ? S.dosEdit : null;
    const list = ppl.map((p) => { const n = blocks(p.uid).length, u = blocks(p.uid).filter((b) => unseen(p.uid, b)).length;
      return `<button class="tp-pl ${p.uid === uid ? "on" : ""}" data-dossel="${esc(p.uid)}"><span class="tp-av">${esc(RB.first(p.name)[0] || "?")}</span><span class="tp-name">${esc(RB.first(p.name))}</span><b class="${n ? "" : "low"}">${n || "none"}</b><span class="tp-rk dz-ns" ${u ? `title="${u} not seen on his phone yet"` : 'style="visibility:hidden"'}>●${u}</span></button>`; }).join("");
    const bl = blocks(uid), old = (doc(uid).old || []).concat(doc(uid).oldOwn || []).sort((a, b) => a.minute - b.minute);
    const seen = (b) => { const at = (doc(uid).seen || {})[b.id] || 0; return !b.changedAt ? "" : unseen(uid, b) ? '<span class="dz-st wait">● Not seen yet</span>' : `<span class="dz-st ok">✓ Seen ${esc(seenAt(at))}</span>`; };
    const tags = (b) => [freq(b)].concat(extras(b)).map((t) => `<span class="dz-tag">${esc(t)}</span>`).join("");
    const row = (b) => `<div class="dz-row ${E && E.id === b.id ? "sel" : ""}"><span class="tm">${esc(range(b))}</span><span class="ti"><b>${esc(b.title)}</b>${b.note ? `<small>${esc(b.note)}</small>` : ""}<span class="dz-tags">${tags(b)}</span></span><span class="dz-tags dz-wide">${tags(b)}</span><span>${seen(b)}</span><span class="dz-act"><button class="btnt" data-dosedit="${esc(b.id)}">Edit</button><button class="btnt dz-del" data-dosdel="${esc(b.id)}">Delete</button></span></div>`;
    const oldRow = (b) => `<div class="dz-orow"><span class="tm">${esc(range(b))}</span><span><b>${esc(b.title)}</b> <span class="sub">· ${esc(freq(b))}${b.byName ? " · added by " + esc(RB.first(b.byName)) : b.own ? " · made by himself" : ""}</span></span><button class="btnt" data-dosreadd="${esc(b.id)}">Re-add ›</button></div>`;
    const booked = bl.reduce((s, b) => s + Math.max(0, b.endMinute - b.minute), 0);
    const html = `<div class="tp-person dz ${E ? "narrow" : ""}"><div class="card tp-plist tp-side"><div class="tp-hint" style="margin:0 4px 4px">Team · dossier tasks · <span class="dz-nsl">● not seen</span></div>${list}</div>
      <div class="card tp-main"><div class="dz-hd"><span class="tp-av big">${esc(RB.first(m.name)[0] || "?")}</span><div><h3>${esc(RB.first(m.name))}</h3><div class="sub">${esc(seat.designation || "")}${tt ? " · " + esc(RB.ttName(tt)) : " · no timetable yet (11 AM to 9 PM · Fri off until then)"}</div></div><div class="sp"></div><button class="btnp" id="dosadd">+ Add dossier task</button></div>
      <div class="hint" style="margin-top:10px">${esc(RB.first(m.name))} sees and ticks these on his phone. Only you can add, change or delete them. Every save reaches his phone right away, with a notification, and goes into the Change log.</div>
      ${old.length ? `<div class="dz-old">📋 <span class="t"><b>${esc(RB.first(m.name))}'s old dossier</b> · ${old.length} task${old.length === 1 ? "" : "s"} deleted on update day, kept here so you can re-add the real ones</span><button class="btnt" id="dosold">${S.dosOld ? "Hide ▴" : "Show ▾"}</button></div>${S.dosOld ? `<div class="dz-olist">${old.map(oldRow).join("")}</div>` : ""}` : ""}
      ${bl.length ? `<div class="dz-colh"><span>TIME</span><span>DOSSIER TASK</span><span class="dz-wide">REPEATS · EXTRAS</span><span>ON HIS PHONE</span><span></span></div>${bl.map(row).join("")}<div class="sub" style="margin-top:12px">${bl.length} task${bl.length === 1 ? "" : "s"} · sorted by time · ${Math.floor(booked / 60)} h ${booked % 60} min of his day booked</div>` : '<div class="muted" style="margin-top:14px">No dossier tasks yet. Press + Add dossier task.</div>'}</div></div>`;
    return { html, side: E ? panel(E) : "" };
  }

  // ------------------------------------------------------------------ add / edit panel (the draft lives in S.dosEdit, so a live redraw keeps it)
  const draftOf = (uid, b) => b ? { uid, id: b.id, title: b.title, note: b.note || "", minute: b.minute, endMinute: b.endMinute, days: b.days || ALL, monthDay: b.monthDay || 0, everyWeeks: b.everyWeeks || 1, onOffDay: !!b.onOffDay, remind: b.remindBefore > 0, remindBefore: b.remindBefore || 15, afterReview: !!b.afterReview, proof: !!b.proof, rep: b.monthDay > 0 ? "monthly" : (b.days & ALL) === ALL ? "daily" : (b.days & ALL) === MONSAT ? "monsat" : "weekly" }
    : (() => { const g = gaps({ uid, id: "", days: ALL, monthDay: 0 })[0], s = g ? g[0] : ttOf(uid).start; return { uid, id: "", title: "", note: "", minute: s, endMinute: Math.min(s + 30, g ? g[1] : s + 30), days: ALL, monthDay: 0, everyWeeks: 1, onOffDay: true, remind: true, remindBefore: 15, afterReview: true, proof: false, rep: "daily" }; })();
  /** Free stretches (30 min or more) inside his working hours on the days this task comes up. */
  function gaps(E) {
    const tt = ttOf(E.uid), busy = blocks(E.uid).filter((b) => b.id !== E.id && clashes(b, { days: E.days, monthDay: E.monthDay, minute: 0, endMinute: 1440 })).sort((a, b) => a.minute - b.minute);
    const out = []; let t = tt.start;
    busy.forEach((b) => { if (b.minute - t >= 30) out.push([t, Math.min(b.minute, tt.end)]); t = Math.max(t, b.endMinute); });
    if (tt.end - t >= 30) out.push([t, tt.end]);
    return out.filter((g) => g[1] - g[0] >= 30).slice(0, 4);
  }
  const chip = (on, attr, label) => `<button class="dz-chip ${on ? "on" : ""}" ${attr} aria-pressed="${on}">${label}</button>`;
  const sw = (k, on, title, sub) => `<label class="dz-sw"><span class="w"><b>${title}</b><small>${sub}</small></span><input type="checkbox" class="dz-tg" data-dossw="${k}" ${on ? "checked" : ""} aria-label="${esc(title.replace(/<[^>]+>/g, ""))}"></label>`;
  function panel(E) {
    const m = RB.member(E.uid) || {}, nm = esc(RB.first(m.name)), off = ttOf(E.uid).off || [], offN = off.map((d) => DAYN[(d + 6) % 7]).join(", ");
    const g = gaps(E), rb = E.remindBefore >= 60 ? E.remindBefore / 60 + " hour" : E.remindBefore + " min";
    return `<div class="ttpanel dzpanel"><h3>${E.id ? "Change dossier task" : "Add dossier task"} for ${nm}</h3><div class="sub">Goes to his phone as soon as you save</div>
      <div class="lbl">DOSSIER TASK</div><input id="dostitle" value="${esc(E.title)}" placeholder="e.g. Site photos to WhatsApp group" maxlength="80">
      <div class="lbl">NOTE (OPTIONAL)</div><input id="dosnote" value="${esc(E.note)}" placeholder="Anything he should know" maxlength="200">
      <div class="lbl">REPEATS</div><div class="dz-chips">${chip(E.rep === "daily", 'data-dosrep="daily"', "Daily")}${chip(E.rep === "monsat", 'data-dosrep="monsat"', "Mon-Sat")}${chip(E.rep === "weekly", 'data-dosrep="weekly"', "Weekly")}${chip(E.rep === "monthly", 'data-dosrep="monthly"', "Monthly")}</div>
      ${E.rep === "weekly" ? `<div class="ttdays">${[5, 6, 0, 1, 2, 3, 4].map((i) => `<button class="ttd ${E.days & (1 << i) ? "on" : ""}" data-dosday="${i}" aria-pressed="${!!(E.days & (1 << i))}">${DAYN[i]}</button>`).join("")}</div>
        <div class="lbl">EVERY</div><div class="dz-chips">${[1, 2, 3, 4].map((n) => chip(E.everyWeeks === n, `data-dosevery="${n}"`, n === 1 ? "Every week" : n + " weeks")).join("")}</div>` : ""}
      ${E.rep === "monthly" ? `<div class="dz-step"><button class="btnt" data-dosmd="-1" aria-label="Earlier day">−</button><b>On the ${ord(E.monthDay)}</b><button class="btnt" data-dosmd="1" aria-label="Later day">+</button></div><div class="sub">In shorter months it comes on the last day.</div>`
        : `<div class="sub">${esc(freq(E))}.${offN ? " " + offN + " is his weekly off day." : ""}</div>`}
      <div class="ttrow"><div><div class="lbl">START</div><input type="time" id="dosstart" value="${m2t(E.minute)}"></div><div><div class="lbl">END</div><input type="time" id="dosend" value="${m2t(E.endMinute)}"></div></div>
      <div class="sub">The Done notification comes at Start. His calendar keeps Start to End booked.</div>
      ${g.length ? `<div class="lbl">FREE TIMES IN HIS DAY</div><div class="dz-chips">${g.map((x) => `<button class="dz-chip dz-free" data-dosgap="${x[0]}-${x[1]}">${hm(x[0])} – ${hm(x[1])}</button>`).join("")}</div>` : ""}
      ${sw("onOffDay", E.onOffDay, "Also on his off day", E.onOffDay ? "Comes up on his off day too, when it's one of its days" : "Pauses on his weekly off day")}
      ${sw("remind", E.remind, "Remind him before", E.remind ? "An extra notification, " + rb + " before" : "Off")}
      ${E.remind ? `<div class="dz-chips" style="margin-left:4px">${[5, 15, 30, 60].map((n) => chip(E.remindBefore === n, `data-dosrem="${n}"`, n === 60 ? "1 hour" : n + " min")).join("")}</div>` : ""}
      ${sw("afterReview", E.afterReview, "📋 Tick after evening review", "Shows with today's numbers when the evening review ends")}
      ${sw("proof", E.proof, "📷 Photo proof on tick", "His tick opens the camera first. No photo, no tick.")}
      <div class="dz-note">On Save: ${nm} gets a notification and a card at the top of My tasks. Saved in the Change log.</div>
      <div class="err" id="doserr"></div>
      <div class="ttfoot"><div class="sp"></div><button class="link" id="doscancel">Cancel</button><button class="btnp" id="dossave">Save</button></div></div>`;
  }

  async function save(btn) {
    const E = S.dosEdit, err = (t) => { const e = document.getElementById("doserr"); if (e) e.textContent = t; };
    const title = E.title.trim();
    if (!title) return err("Write the dossier task first.");
    if (!(E.minute >= 0) || !(E.endMinute > E.minute)) return err("End must be after Start.");
    if (E.rep !== "monthly" && !(E.days & ALL)) return err("Pick at least one day.");
    const b = { id: E.id || Math.random().toString(36).slice(2, 14), title, note: E.note.trim(), minute: E.minute, endMinute: E.endMinute, days: E.rep === "monthly" ? ALL : E.days & ALL, monthDay: E.rep === "monthly" ? E.monthDay : 0,
      everyWeeks: E.rep === "weekly" ? E.everyWeeks : 1, onOffDay: E.onOffDay, remindBefore: E.remind ? E.remindBefore : 0, proof: E.proof, afterReview: E.afterReview };
    const cur = blocks(E.uid), old = cur.find((x) => x.id === E.id);
    const clash = cur.find((x) => x.id !== b.id && clashes(x, b));
    if (clash) return err("Clashes with " + clash.title + " (" + range(clash) + "). Pick another time.");
    const same = old && ["title", "note", "minute", "endMinute", "days", "monthDay", "everyWeeks", "onOffDay", "remindBefore", "proof", "afterReview"].every((k) => old[k] === b[k]);
    if (same) { S.dosEdit = null; return RB.render(); }
    Object.assign(b, { by: (old && old.by) || RB.uidOf() || "", byName: (old && old.byName) || (D.me && D.me.name) || "Owner", changedAt: Date.now() });
    btn.disabled = true;
    const who = RB.first((RB.member(E.uid) || {}).name);
    S.dosEdit = null;
    await saveBlocks(E.uid, cur.filter((x) => x.id !== b.id).concat(b), old ? "Dossier · " + who + " · changed: " + say(old) + " → " + say(b) : "Dossier · " + who + " · added: " + say(b));
  }

  function bind(root, side) {
    root.querySelectorAll("[data-dossel]").forEach((e) => (e.onclick = () => { S.dosSel = e.dataset.dossel; S.dosEdit = null; S.dosOld = false; RB.render(); }));
    const uid = S.dosSel;
    const add = root.querySelector("#dosadd"); if (add) add.onclick = () => { S.dosEdit = draftOf(uid); RB.render(); };
    const ob = root.querySelector("#dosold"); if (ob) ob.onclick = () => { S.dosOld = !S.dosOld; RB.render(); };
    root.querySelectorAll("[data-dosedit]").forEach((e) => (e.onclick = () => { const b = blocks(uid).find((x) => x.id === e.dataset.dosedit); if (b) { S.dosEdit = draftOf(uid, b); RB.render(); } }));
    root.querySelectorAll("[data-dosreadd]").forEach((e) => (e.onclick = () => {
      const d = doc(uid), b = (d.old || []).concat(d.oldOwn || []).find((x) => x.id === e.dataset.dosreadd); if (!b) return;
      S.dosEdit = Object.assign(draftOf(uid, b), { id: "" }); RB.render();
    }));
    root.querySelectorAll("[data-dosdel]").forEach((e) => (e.onclick = () => {
      const b = blocks(uid).find((x) => x.id === e.dataset.dosdel); if (!b) return;
      const who = RB.first((RB.member(uid) || {}).name);
      RB.confirm("Delete “" + esc(b.title) + "” (" + esc(range(b)) + ") from " + esc(who) + "'s dossier? It goes from his phone at once.", "Delete", async () => {
        if (S.dosEdit && S.dosEdit.id === b.id) S.dosEdit = null;
        await saveBlocks(uid, blocks(uid).filter((x) => x.id !== b.id), "Dossier · " + who + " · deleted: " + say(b));
      }, true);
    }));
    const E = S.dosEdit, P = side && side.querySelector(".dzpanel"); if (!E || !P) return;
    const redraw = () => RB.render();
    P.querySelector("#dostitle").oninput = (e) => (E.title = e.target.value);
    P.querySelector("#dosnote").oninput = (e) => (E.note = e.target.value);
    P.querySelector("#dosstart").onchange = (e) => { const v = t2m(e.target.value); if (v >= 0) { const len = E.endMinute - E.minute; E.minute = v; if (!(E.endMinute > v)) E.endMinute = Math.min(v + Math.max(len, 30), 1439); redraw(); } };
    P.querySelector("#dosend").onchange = (e) => { const v = t2m(e.target.value); if (v >= 0) { E.endMinute = v; redraw(); } };
    P.querySelectorAll("[data-dosrep]").forEach((b) => (b.onclick = () => {
      E.rep = b.dataset.dosrep;
      if (E.rep === "daily") E.days = ALL; else if (E.rep === "monsat") E.days = MONSAT;
      else if (E.rep === "weekly" && ((E.days & ALL) === ALL || (E.days & ALL) === MONSAT)) E.days = 1 << ((new Date().getDay() + 6) % 7);
      else if (E.rep === "monthly" && !E.monthDay) E.monthDay = 1;
      redraw();
    }));
    P.querySelectorAll("[data-dosday]").forEach((b) => (b.onclick = () => { E.days ^= 1 << Number(b.dataset.dosday); redraw(); }));
    P.querySelectorAll("[data-dosevery]").forEach((b) => (b.onclick = () => { E.everyWeeks = Number(b.dataset.dosevery); redraw(); }));
    P.querySelectorAll("[data-dosmd]").forEach((b) => (b.onclick = () => { E.monthDay = Math.max(1, Math.min(31, E.monthDay + Number(b.dataset.dosmd))); redraw(); }));
    P.querySelectorAll("[data-dosrem]").forEach((b) => (b.onclick = () => { E.remindBefore = Number(b.dataset.dosrem); redraw(); }));
    P.querySelectorAll("[data-dossw]").forEach((c) => (c.onchange = () => { E[c.dataset.dossw] = c.checked; redraw(); }));
    P.querySelectorAll("[data-dosgap]").forEach((b) => (b.onclick = () => { const [a, z] = b.dataset.dosgap.split("-").map(Number), len = Math.max(30, E.endMinute - E.minute); E.minute = a; E.endMinute = Math.min(a + len, z); redraw(); }));
    P.querySelector("#doscancel").onclick = () => { S.dosEdit = null; redraw(); };
    const sv = P.querySelector("#dossave"); sv.onclick = () => save(sv);
  }

  RB.dossierEd = { page, bind };
})();
