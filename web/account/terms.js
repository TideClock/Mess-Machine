/* /terms/: the page carries its own copy of the Vault grant. When a server is set up, the server's
   copy (vault_grant) is the one people agree to in the app, so it is shown here instead, as plain text,
   and the page says whether the two versions match. */
(function () {
  "use strict";
  var MM = window.MM;
  if (!MM || !MM.configured()) return;
  var $ = MM.$;
  var mine = $("grant-version").textContent.trim();
  var state = $("grant-state");
  MM.rpc("vault_grant", {}).then(function (g) {
    if (!g || typeof g.version !== "string" || typeof g.body !== "string" || !g.body) return;
    $("grant-version").textContent = g.version;
    $("grant-body").textContent = g.body;
    state.hidden = false;
    if (g.version === mine) MM.note(state, "This is the text the app shows, version " + g.version + ".", "ok");
    else MM.note(state, "The server has version " + g.version + " and this page was written for " + mine + ". The server's text is shown. Update this page.", "bad");
  }, function () {
    // The page's own copy stays.
  });
})();
