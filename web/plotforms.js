/* Plot files forms printed by the app, in the locked layouts (sop/forms-revised).
 * RG-03 Documents Checklist is built from the imported SOP's Form items (no item list in this code) and this file's ticks.
 * The other app-printed forms (RG-05, RG-07, RG-13, RG-14, RG-15) only show their locked sample in Docs > SOPs > Forms for now:
 * their figures (RG-05 cheque lines, sale amounts, waiver rows, discount cheque trail) are entered in phone tasks, which come later. */
(function () {
  "use strict";
  const RB = window.RB, esc = RB.esc;
  const F = (RB.plotForms = {});

  /** Locked sample previews (plot 245, sample data), one static file per form. */
  F.SAMPLES = { "RG-05": "forms/rg-05-sample.html", "RG-07": "forms/rg-07-sample.html", "RG-13": "forms/rg-13-sample.html", "RG-14": "forms/rg-14-sample.html", "RG-15": "forms/rg-15-sample.html" };
  /** Page heights for the preview frame: A4 forms are taller than the half-A4 slips. */
  F.HEIGHT = { "RG-03": 1250, "RG-05": 1250, "RG-07": 1000, "RG-13": 560, "RG-14": 560, "RG-15": 560 };

  /** RG-03 v3 (# | Document | In file | Date). f = the plot file or null for a blank form; items = [{stage, no, name, status, at}]. */
  F.rg03 = (f, items, printedAt) => {
    const gs = Array.from(new Set(items.map((i) => i.stage || i.group || "")));
    const ok = (i) => i.status === "verified" || i.status === "received";
    let n = 0;
    const body = gs.map((g) => `<tr class="g"><td colspan="4">${esc(String(g).toUpperCase())}</td></tr>` + items.filter((i) => (i.stage || i.group || "") === g).map((i) => { n++; return `<tr><td class="c">${esc(String(i.no || n))}</td><td>${esc(i.name)}</td><td class="c">${ok(i) ? "☑" : "☐"}</td><td>${ok(i) && i.at ? esc(RB.fmtShort(i.at)) : ""}</td></tr>`; }).join("")).join("");
    const fld = (v) => (v ? `<b style="flex:1;border-bottom:1px solid #444;padding-left:4px">${esc(v)}</b>` : "<i></i>");
    return `<!doctype html><html><head><meta charset="utf-8"><title>Documents Checklist</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap" rel="stylesheet">
<style>@page{size:A4;margin:10mm}body{font-family:Inter,Arial,sans-serif;font-size:10.5px;color:#111;margin:0}
.hd{display:flex;justify-content:space-between;align-items:flex-end;border-bottom:2px solid #111;padding-bottom:5px}.org{font-weight:700;font-size:12px}.sub{font-size:10px;color:#555}
h1{text-align:center;font-size:17px;margin:8px 0 6px;letter-spacing:1px}
.top{display:flex;gap:20px;margin-bottom:8px;font-size:12px}.top span{flex:1;display:flex;gap:6px}.top i{flex:1;border-bottom:1px solid #444}
table{width:100%;border-collapse:collapse}th,td{border:1px solid #888;padding:4px 6px;vertical-align:top}th{background:#e9e9e9;font-size:9.5px}
.c{text-align:center}.rm{font-size:9.5px;color:#333}tr.g td{background:#f3f3f3;font-weight:700;font-size:9.5px;letter-spacing:.4px}
.note{font-size:9px;color:#444;margin-top:6px}@media screen{body{padding:14px 16px;background:#fff}}</style></head><body>
<div class="hd"><div><div class="org">RAFIQ BUILDERS &amp; DEVELOPERS · ROYAL GARDEN, GOJRA</div><div class="sub">Plot files · glue inside the front cover</div></div><div class="sub">Form RG-03 · v3${printedAt ? " · printed " + esc(RB.fmtShort(printedAt)) : ""}</div></div>
<h1>DOCUMENTS CHECKLIST</h1>
<div class="top"><span>Plot No.${fld(f && f.plot)}</span><span style="flex:2">Owner name${fld(f && f.owner)}</span></div>
<table><thead><tr><th style="width:24px">#</th><th>Document</th><th style="width:60px">In file</th><th style="width:110px">Date</th></tr></thead><tbody>${body}</tbody></table>
<div class="note">Filled only by the Document Officer: tick and date each paper the day it goes into the file. Shows at a glance which papers are in the file; full details (who, receipt numbers, approvals) are in the app.</div>
</body></html>`;
  };

  /** Prints a whole HTML page through a hidden frame (A4, the browser's print window also saves PDF). */
  F.print = (html) => {
    const fr = document.createElement("iframe"); fr.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0"; document.body.appendChild(fr);
    fr.onload = () => { setTimeout(() => { fr.contentWindow.focus(); fr.contentWindow.print(); setTimeout(() => fr.remove(), 4000); }, 300); };
    fr.srcdoc = html;
  };
})();
