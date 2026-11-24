/* /m/<token>: a share link's landing page. Calls open_shared (no sign-in), then report_mess.
   The mess name and the maker's name are whatever a stranger typed: they go in with textContent only. */
(function () {
  "use strict";
  var MM = window.MM;
  if (!MM || !MM.configured()) return;
  var $ = MM.$, h = MM.h;
  var state = $("state");
  var token = MM.pathToken(["m"]);

  function gone(words) {
    MM.show(state, false);
    $("gone-words").textContent = words;
    MM.show($("gone"), true);
  }
  if (!token) { gone("This link is not a whole share link. Copy it again from the message it came in."); return; }

  MM.rpc("open_shared", { p_token: token }).then(function (m) {
    m = m || {};
    $("mess-name").textContent = typeof m.name === "string" && m.name ? m.name : "A mess with no name";
    $("owner-name").textContent = typeof m.owner_name === "string" && m.owner_name ? m.owner_name : "someone";
    document.title = "A shared mess · Mess Machine";
    var facts = $("facts");
    var doc = m.doc && typeof m.doc === "object" ? m.doc : {};
    var bpm = Number(doc.bpm);
    if (isFinite(bpm) && bpm > 0) facts.appendChild(h("li", { text: Math.floor(Math.min(180, Math.max(60, bpm))) + " BPM" }));
    if (Array.isArray(doc.kit)) {
      var pads = doc.kit.slice(0, 64).filter(function (k) { return typeof k === "string" && k !== ""; }).length;
      facts.appendChild(h("li", { text: pads + (pads === 1 ? " pad" : " pads") }));
    }
    facts.appendChild(h("li", { text: m.mode === "copy" ? "You can keep a copy" : "Listen only" }));
    $("open").setAttribute("href", "messmachine://m/" + token);
    MM.show(state, false);
    MM.show($("found"), true);
  }, function (e) {
    if (e.code === "not_found") gone("This link does not open a mess any more.");
    else { state.textContent = MM.say(e); state.classList.add("plain"); }
  });

  var rep = $("rep"), repOpen = $("rep-open"), repMsg = $("rep-msg");
  repOpen.addEventListener("click", function () {
    var open = rep.hidden;
    MM.show(rep, open);
    repOpen.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) rep.querySelector("input").focus();
  });
  rep.addEventListener("change", function () { MM.note(repMsg, ""); });
  rep.addEventListener("submit", function (ev) {
    ev.preventDefault();
    var picked = rep.querySelector("input[name=reason]:checked");
    if (!picked) { MM.note(repMsg, "Pick what is wrong first.", "bad"); return; }
    MM.press($("rep-send"), repMsg, async function () {
      MM.note(repMsg, "");
      await MM.rpc("report_mess", { p_mess: null, p_token: token, p_reason: picked.value, p_details: $("rep-details").value.slice(0, 500) });
      MM.show(rep, false);
      MM.show(repOpen, false);
      MM.show($("rep-done"), true);
      $("rep-done").focus();
    }, {
      rate_limited: "This mess already has a lot of reports today. We are looking at it.",
      not_found: "This link does not open a mess any more, so there is nothing to report."
    });
  });
})();
