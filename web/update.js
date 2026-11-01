/* One-tap update, like the phone app: every publish writes version.json; when it changes, a bar offers "Update". */
(function () {
  let mine = null, mineAt = "";
  // Returns "new" (a newer version is published), "same", or "offline". The bar stays until Update is pressed.
  async function check() {
    try {
      const r = await fetch("version.json?t=" + Date.now(), { cache: "no-store" });
      if (!r.ok) return "offline";
      const j = await r.json(), v = j.version;
      if (!v) return "offline";
      if (mine === null) { mine = v; mineAt = j.at || ""; return "same"; }
      if (v !== mine) { show(); return "new"; }
      return "same";
    } catch (e) { return "offline"; }
  }
  window.RB = window.RB || {};
  window.RB.checkUpdate = check;
  window.RB.versionInfo = () => ({ version: mine, at: mineAt });
  function show() {
    if (document.getElementById("updbar")) return;
    const b = document.createElement("div");
    b.id = "updbar";
    b.innerHTML = '<span>A new version of the website is ready</span><button type="button">Update</button>';
    b.querySelector("button").onclick = () => location.reload();
    document.body.appendChild(b);
  }
  check();
  setInterval(check, 5 * 60 * 1000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) check(); });
})();
