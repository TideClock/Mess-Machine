/* /join/<token> (and /j/<token>): an invite link's landing page. Calls invite_info (no sign-in).
   Names are whatever a stranger typed: they go in with textContent only. */
(function () {
  "use strict";
  var MM = window.MM;
  if (!MM || !MM.configured()) return;
  var $ = MM.$;
  var state = $("state");
  var token = MM.pathToken(["join", "j"]);

  function gone(words) {
    MM.show(state, false);
    $("gone-words").textContent = words;
    MM.show($("gone"), true);
  }
  if (!token) { gone("This link is not a whole invite. Copy it again from the message it came in."); return; }

  MM.rpc("invite_info", { p_token: token }).then(function (i) {
    i = i || {};
    $("mess-name").textContent = typeof i.mess_name === "string" && i.mess_name ? i.mess_name : "A mess with no name";
    $("owner-name").textContent = typeof i.owner_name === "string" && i.owner_name ? i.owner_name : "Someone";
    $("open").setAttribute("href", "messmachine://j/" + token);
    MM.show(state, false);
    MM.show($("found"), true);
  }, function (e) {
    if (e.code === "invite_invalid") gone("This invite has been used or has run out.");
    else { state.textContent = MM.say(e); state.classList.add("plain"); }
  });
})();
