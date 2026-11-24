/* /delete/: delete your account from the web. Calls me(), then the Edge Function delete-account.
   The server wants a sign-in under 10 minutes old (stale_login), so an older one gets a fresh code first. */
(function () {
  "use strict";
  var MM = window.MM;
  if (!MM || !MM.configured()) return;
  var $ = MM.$, h = MM.h;
  var FRESH_MS = 9 * 60 * 1000;

  var stepIn = $("step-in"), stepGo = $("step-go"), done = $("done");
  var agree = $("agree"), go = $("go"), msg = $("go-msg");
  var sure = $("sure"), fresh = $("fresh");

  MM.signIn($("signin"), { done: signedIn });

  async function signedIn(s) {
    $("who").textContent = "Signed in as " + s.email;
    var facts = $("facts");
    facts.textContent = "";
    MM.show(stepIn, false);
    MM.show(stepGo, true);
    $("go-h").focus();
    try {
      var me = await MM.rpc("me", {});
      if (me && me.name_set && me.display_name) facts.appendChild(h("li", { text: "Name: " + me.display_name }));
      facts.appendChild(h("li", { text: "Online messes: " + (Number(me.messes) || 0) }));
      facts.appendChild(h("li", { text: "Online sounds: " + MM.bytes(me.sound_bytes) }));
    } catch (e) {
      // The numbers are a courtesy. Deleting still works without them.
    }
  }

  agree.addEventListener("change", function () { go.disabled = !agree.checked; });

  go.addEventListener("click", function () {
    if (!agree.checked) return;
    var s = MM.session();
    if (!s) { MM.note(msg, MM.say({ code: "auth" }), "bad"); return; }
    if (Date.now() - s.at > FRESH_MS) { askFresh(); return; }
    MM.press(go, msg, remove);
  });

  async function remove() {
    MM.note(msg, "Deleting...", "");
    try {
      await MM.fn("delete-account", {});
    } catch (e) {
      if (e.code === "stale_login") { askFresh(); return; }
      if (e.code === "auth") { MM.note(msg, "That sign-in ran out. Load this page again and sign in.", "bad"); return; }
      throw e;
    }
    MM.forget();
    MM.show(stepGo, false);
    MM.show(done, true);
    $("done-h").focus();
  }

  function askFresh() {
    var s = MM.session();
    if (!s) return;
    MM.note(msg, "");
    MM.show(sure, false);
    MM.show(fresh, true);
    MM.signIn($("fresh-form"), {
      email: s.email, codeLabel: "Check the code and delete my account",
      done: async function () {
        try { await remove(); }
        catch (e) { MM.note(msg, MM.say(e), "bad"); }
      }
    });
  }

  $("out").addEventListener("click", async function () {
    await MM.signOut();
    agree.checked = false;
    go.disabled = true;
    MM.show(stepGo, false);
    MM.show(stepIn, true);
    MM.signIn($("signin"), { done: signedIn });
  });
})();
