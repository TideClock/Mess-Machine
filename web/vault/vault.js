/* The Vault desk: the keeper's review page. Its own site, apart from the account pages.
   Calls: me, vault_queue, vault_review, admin_reports, admin_open_report, admin_resolve_report,
   admin_block_sound, admin_ban_user, and the Edge Function sound-urls ({"submission"} or {"report"}).
   Every name, note and title here was typed by a stranger: textContent only.
   A sound is downloaded once. The bytes that play are the bytes that get approved. */
(function () {
  "use strict";
  var MM = window.MM;
  if (!MM || !MM.configured()) return;
  var $ = MM.$, h = MM.h;
  MM.keepForTab();

  var gate = $("gate"), notadmin = $("notadmin"), desk = $("desk"), deskMsg = $("desk-msg");
  var counts = { queue: 0, reports: 0 };
  var KINDS = { imp: "Brought in from a file", mic: "Recorded with the mic", rs: "Resampled in the app", take: "A live take" };
  var REASONS = { sexual: "Sexual content", hate: "Hate", harassment: "Harassment or bullying", copyright: "Uses someone's work", spam: "Spam or a scam", other: "Something else" };
  var SHA = /^[0-9a-f]{64}$/;

  function only(el) { [gate, notadmin, desk].forEach(function (x) { MM.show(x, x === el); }); }
  function showGate(words) {
    only(gate);
    var f = MM.signIn($("signin"), { done: enter });
    if (words) MM.note(f.message, words, "bad");
  }
  function showNotAdmin() {
    only(notadmin);
    $("notadmin-words").focus();
  }
  /** Run a key's job. A dead session sends the keeper back to the sign-in. */
  function act(btn, msg, job, extra) {
    return MM.press(btn, msg, async function () {
      try { await job(); }
      catch (e) {
        if (!MM.session()) { showGate("You were signed out. Sign in again."); return; }
        throw e;
      }
    }, extra);
  }

  async function enter() {
    var me;
    try { me = await MM.rpc("me", {}); }
    catch (e) {
      if (!MM.session()) { showGate(e.code === "auth" ? "" : MM.say(e)); return; }
      if (e.code === "net" || e.code === "http") { showGate(MM.say(e)); return; }
      showNotAdmin();
      return;
    }
    if (!me || me.is_admin !== true) { showNotAdmin(); return; }
    var s = MM.session();
    $("who").textContent = "Vault keeper: " + (me.display_name || (s && s.email) || "signed in");
    only(desk);
    await loadAll();
  }

  async function loadAll() {
    MM.note(deskMsg, "");
    try {
      var got = await Promise.all([MM.rpc("vault_queue", { p_status: "pending" }), MM.rpc("admin_reports", { p_status: "open" })]);
      drawQueue(Array.isArray(got[0]) ? got[0] : []);
      drawReports(Array.isArray(got[1]) ? got[1] : []);
    } catch (e) {
      if (!MM.session()) { showGate("You were signed out. Sign in again."); return; }
      if (e.code === "forbidden") { showNotAdmin(); return; }
      MM.note(deskMsg, MM.say(e), "bad");
    }
  }
  function setCount(which, n) {
    counts[which] = Math.max(0, n);
    $("n-" + which).textContent = String(counts[which]);
  }

  // ---------- small parts ----------
  function row(dl, name, value) {
    dl.appendChild(h("dt", { text: name }));
    dl.appendChild(h("dd", { text: value }));
  }
  function ago(iso) {
    var ms = Date.now() - new Date(iso).getTime();
    if (!isFinite(ms)) return "";
    var m = Math.max(0, Math.round(ms / 60000));
    if (m < 60) return m + (m === 1 ? " minute ago" : " minutes ago");
    var hrs = Math.floor(m / 60);
    if (hrs < 48) return hrs + (hrs === 1 ? " hour ago" : " hours ago");
    return Math.floor(hrs / 24) + " days ago";
  }
  function noteField(id, label) {
    var ta = h("textarea", { id: id, maxlength: "300" });
    return { box: h("div", { class: "field" }, [h("label", { for: id, text: label }), ta]), input: ta };
  }
  /** Ask before a step that cannot be taken back. The keys row hides while the question is up. */
  function ask(host, keys, words, yesWords, yesClass, msg, job, extra) {
    host.textContent = "";
    var yes = h("button", { class: "key small " + yesClass, type: "button", text: yesWords });
    var no = h("button", { class: "key small", type: "button", text: "No, go back" });
    host.appendChild(h("div", { class: "ask" }, [h("p", { text: words }), h("div", { class: "keys" }, [no, yes])]));
    MM.show(keys, false);
    no.focus();
    function close() { host.textContent = ""; MM.show(keys, true); }
    no.addEventListener("click", close);
    yes.addEventListener("click", function () {
      act(yes, msg, async function () {
        try { await job(); } finally { close(); }
      }, extra);
    });
  }
  function finish(art, words, kind, which) {
    var title = art.querySelector("h3");
    var keep = title ? title.textContent : "";
    art.textContent = "";
    art.classList.add("done");
    art.appendChild(h("h3", { text: keep }));
    art.appendChild(h("p", { class: "msg " + kind, text: words }));
    setCount(which, counts[which] - 1);
  }

  // ---------- the sound queue ----------
  function drawQueue(list) {
    var box = $("queue");
    box.textContent = "";
    setCount("queue", list.length);
    if (!list.length) { box.appendChild(h("p", { class: "lcd", text: "Nothing waiting" })); return; }
    list.forEach(function (s, i) { box.appendChild(subCard(s || {}, i)); });
  }

  function subCard(s, i) {
    var id = "s" + i;
    var held = null, blobUrl = "", heard = false;
    var art = h("article", { class: "item", "aria-labelledby": id + "-t", "data-sub": String(s.id || "") });
    art.appendChild(h("h3", { id: id + "-t", text: s.title || "No title" }));

    var dl = h("dl", { class: "kv" });
    row(dl, "Credit name", s.credit_name || "");
    row(dl, "From", s.submitter_id ? (s.submitter_name || "no name") + " (account " + MM.shortId(s.submitter_id) + ")" : "an account that is gone");
    row(dl, "Length", (Number(s.seconds) || 0).toFixed(1) + " seconds at " + (Number(s.rate) || 0) + " Hz");
    row(dl, "Made how", KINDS[s.kind] || String(s.kind || ""));
    if (Number(s.bpm) > 0) row(dl, "Tempo", Number(s.bpm) + " BPM, " + (Number(s.beats) || 0) + " beats");
    if (s.mkey) row(dl, "Key", String(s.mkey));
    row(dl, "Size", MM.bytes(s.bytes));
    row(dl, "Sent", MM.when(s.submitted_at) + " (" + ago(s.submitted_at) + ")");
    row(dl, "Grant agreed", "version " + (s.grant_version || "?"));
    art.appendChild(dl);

    art.appendChild(h("p", { class: "label", text: "Their note" }));
    art.appendChild(s.note ? h("p", { class: "said", text: s.note }) : h("p", { class: "quiet", text: "No note." }));

    var others = Array.isArray(s.others) ? s.others : [];
    if (!others.length) {
      art.appendChild(h("p", { class: "theft" }, [h("span", { class: "lamp ok", "aria-hidden": "true" }), "Nobody else has this exact sound."]));
    } else {
      var ul = h("ul", {});
      others.forEach(function (o) {
        ul.appendChild(h("li", {}, ["account ", h("span", { class: "mono", text: MM.shortId(o && o.owner) }), ", since " + MM.when(o && o.at)]));
      });
      art.appendChild(h("div", { class: "theft warn" }, [
        h("p", {}, [h("span", { class: "lamp bad", "aria-hidden": "true" }),
          h("strong", { text: others.length === 1 ? "1 other account has this exact sound." : others.length + " other accounts have this exact sound." }),
          " Check who had it first before you approve."]),
        ul
      ]));
    }

    var audio = h("audio", { controls: true, preload: "auto", hidden: true, "aria-label": "The sound: " + (s.title || "no title") });
    var loadKey = h("button", { class: "key small go", type: "button", text: "Load the sound" });
    var playKey = h("button", { class: "key small go", type: "button", text: "Play", hidden: true });
    var hint = h("p", { class: "msg", role: "status", "aria-live": "polite", text: "Load the sound, then listen." });
    art.appendChild(h("div", { class: "keys" }, [loadKey, playKey]));
    art.appendChild(audio);
    art.appendChild(hint);

    var nf = noteField(id + "-note", "Note to the maker (you can leave this empty)");
    art.appendChild(nf.box);
    var approve = h("button", { class: "key go", type: "button", text: "Approve", disabled: true });
    var decline = h("button", { class: "key danger", type: "button", text: "Decline" });
    var keys = h("div", { class: "keys" }, [approve, decline]);
    var asking = h("div", {});
    var msg = h("p", { class: "msg", role: "status", "aria-live": "polite" });
    art.appendChild(keys);
    art.appendChild(asking);
    art.appendChild(msg);

    function drop() {
      if (blobUrl) URL.revokeObjectURL(blobUrl);
      blobUrl = "";
      held = null;
    }

    loadKey.addEventListener("click", function () {
      act(loadKey, hint, async function () {
        if (!SHA.test(String(s.sha256))) throw new MM.Err("bad_hash", 0);
        MM.note(hint, "Loading...", "");
        var u = await MM.fn("sound-urls", { submission: s.id });
        var hit = ((u && u.sounds) || []).filter(function (x) { return x && x.sha256 === s.sha256; })[0];
        if (!hit || typeof hit.url !== "string") throw new MM.Err("not_found", 0);
        var buf = await MM.fetchBytes(hit.url);
        if (buf.byteLength !== Number(s.bytes) || (await MM.sha256(buf)) !== s.sha256) throw new MM.Err("bad_hash", 0);
        held = buf;
        blobUrl = URL.createObjectURL(new Blob([buf], { type: "audio/wav" }));
        audio.src = blobUrl;
        MM.show(audio, true);
        MM.show(loadKey, false);
        MM.show(playKey, true);
        MM.note(hint, "Loaded " + MM.bytes(buf.byteLength) + ". It is the same sound that was sent in. Now press Play.", "ok");
        playKey.focus();
      }, { not_found: "The sound file is not on the server.", forbidden: "This account cannot listen to that." });
    });
    playKey.addEventListener("click", function () {
      if (audio.paused) { var p = audio.play(); if (p && p.catch) p.catch(function () { MM.note(hint, "This browser could not play it. Do not approve what you cannot hear.", "bad"); }); }
      else audio.pause();
    });
    audio.addEventListener("playing", function () {
      playKey.textContent = "Pause";
      if (!heard && held) {
        heard = true;
        approve.disabled = false;
        MM.note(hint, "You are hearing the exact file that Approve puts in the Vault.", "ok");
      }
    });
    audio.addEventListener("pause", function () { playKey.textContent = "Play"; });
    audio.addEventListener("ended", function () { playKey.textContent = "Play again"; });

    approve.addEventListener("click", function () {
      if (!held || !heard) return;
      ask(asking, keys, "Approve this sound? It joins the Vault for everyone, with the credit name above, and stays for good.",
        "Yes, approve", "go", msg, async function () {
          MM.note(msg, "Copying it into the Vault...", "");
          await MM.upload("vault/" + s.sha256 + ".wav", held, "audio/wav");
          await MM.rpc("vault_review", { p_id: s.id, p_decision: "approved", p_note: nf.input.value.slice(0, 300) });
          drop();
          finish(art, "Approved. It is in the Vault.", "ok", "queue");
        }, { already_reviewed: "This one was already decided. Press Look again.", auth: "This account cannot put sounds in the Vault." });
    });
    decline.addEventListener("click", function () {
      ask(asking, keys, "Decline this sound? The maker sees your note. This cannot be changed after.",
        "Yes, decline", "danger", msg, async function () {
          await MM.rpc("vault_review", { p_id: s.id, p_decision: "declined", p_note: nf.input.value.slice(0, 300) });
          drop();
          finish(art, "Declined.", "", "queue");
        }, { already_reviewed: "This one was already decided. Press Look again." });
    });
    return art;
  }

  // ---------- reports ----------
  function drawReports(list) {
    var box = $("reports");
    box.textContent = "";
    setCount("reports", list.length);
    if (!list.length) { box.appendChild(h("p", { class: "lcd", text: "No open reports" })); return; }
    list.forEach(function (r, i) { box.appendChild(repCard(r || {}, i)); });
  }

  function repCard(r, i) {
    var id = "r" + i;
    var urls = null;
    var art = h("article", { class: "item", "aria-labelledby": id + "-t", "data-report": String(r.id || "") });
    art.appendChild(h("h3", { id: id + "-t", text: r.mess_id ? (r.mess_name || "A mess with no name") : "A mess that is gone" }));

    var hours = (Date.now() - new Date(r.at).getTime()) / 3600000;
    if (hours >= 20) art.appendChild(h("p", { class: "msg bad" }, [h("span", { class: "lamp bad", "aria-hidden": "true" }), "This report is " + Math.floor(hours) + " hours old. Answer it today."]));

    var dl = h("dl", { class: "kv" });
    row(dl, "What is wrong", REASONS[r.reason] || String(r.reason || ""));
    row(dl, "Sent", MM.when(r.at) + " (" + ago(r.at) + ")");
    row(dl, "From", r.via_token ? "a share link page" : "inside the app, by someone in the mess");
    row(dl, "Mess owner", r.owner_id ? "account " + MM.shortId(r.owner_id) : "gone");
    if (r.about_user) row(dl, "About a person", "account " + MM.shortId(r.about_user));
    art.appendChild(dl);
    art.appendChild(h("p", { class: "label", text: "Their words" }));
    art.appendChild(r.details ? h("p", { class: "said", text: r.details }) : h("p", { class: "quiet", text: "No details." }));

    var openKey = h("button", { class: "key small go", type: "button", text: "Open the report" });
    var opened = h("div", { class: "opened" });
    var openMsg = h("p", { class: "msg", role: "status", "aria-live": "polite" });
    art.appendChild(h("div", { class: "keys" }, [openKey]));
    art.appendChild(opened);
    art.appendChild(openMsg);

    var nf = noteField(id + "-note", "Note for the record (you can leave this empty)");
    art.appendChild(nf.box);
    var down = h("button", { class: "key danger", type: "button", text: "Take the mess down", hidden: !r.mess_id });
    var dismiss = h("button", { class: "key", type: "button", text: "Dismiss" });
    var keys = h("div", { class: "keys" }, [down, dismiss]);
    var asking = h("div", {});
    var msg = h("p", { class: "msg", role: "status", "aria-live": "polite" });
    function banKey(user, words) {
      var banned = false;
      var b = h("button", { class: "key small danger", type: "button", text: words });
      b.addEventListener("click", function () {
        var to = !banned;
        ask(asking, keys, to ? "Ban account " + MM.shortId(user) + "? All their messes come down, their share links stop and they cannot sign in."
          : "Undo the ban on account " + MM.shortId(user) + "?", to ? "Yes, ban" : "Yes, undo it", to ? "danger" : "", msg, async function () {
          var a = await MM.rpc("admin_ban_user", { p_user: user, p_banned: to });
          banned = !!(a && a.banned);
          b.textContent = banned ? "Undo the ban" : words;
          MM.note(msg, banned ? "Banned. Their messes are down and their links are off. Now close this report." : "The ban is undone.", banned ? "ok" : "");
        });
      });
      return b;
    }
    if (r.owner_id) keys.appendChild(banKey(r.owner_id, "Ban the mess owner"));
    if (r.about_user && r.about_user !== r.owner_id) keys.appendChild(banKey(r.about_user, "Ban the reported person"));
    art.appendChild(keys);
    art.appendChild(asking);
    art.appendChild(msg);

    function soundRow(s) {
      var box = h("div", { class: "sound" });
      box.appendChild(h("p", {}, [h("strong", { text: s.title || "No title" })]));
      box.appendChild(h("p", { class: "quiet small", text: [KINDS[s.kind] || String(s.kind || ""), (Number(s.seconds) || 0).toFixed(1) + " s",
        s.src === "vault" ? "from the Vault" : s.owner ? "owner account " + MM.shortId(s.owner) : "its maker is gone"].join(" · ") }));
      var sMsg = h("p", { class: "msg", role: "status", "aria-live": "polite" });
      var listen = h("button", { class: "key small go", type: "button", text: "Listen" });
      var sKeys = h("div", { class: "keys" }, [listen]);
      var sAsk = h("div", {});
      var audio = null;
      listen.addEventListener("click", function () {
        if (audio) { if (audio.paused) audio.play().catch(function () {}); else audio.pause(); return; }
        act(listen, sMsg, async function () {
          if (!urls) urls = await MM.fn("sound-urls", { report: r.id });
          var hit = ((urls && urls.sounds) || []).filter(function (x) { return x && x.sha256 === s.sha256; })[0];
          if (!hit || typeof hit.url !== "string") throw new MM.Err("not_found", 0);
          var buf = await MM.fetchBytes(hit.url);
          audio = h("audio", { controls: true, "aria-label": "The sound: " + (s.title || "no title") });
          audio.src = URL.createObjectURL(new Blob([buf], { type: "audio/wav" }));
          box.insertBefore(audio, sKeys);
          listen.textContent = "Play or pause";
          audio.play().catch(function () {});
        }, { not_found: "That sound cannot be played. It may be gone or already blocked." });
      });
      if (s.src === "user" && s.owner && SHA.test(String(s.sha256))) {
        var block = h("button", { class: "key small danger", type: "button", text: "Block this sound" });
        block.addEventListener("click", function () {
          ask(sAsk, sKeys, "Block this sound? It goes silent in every mess that uses it, whoever owns the mess.", "Yes, block it", "danger", sMsg, async function () {
            var a = await MM.rpc("admin_block_sound", { p_owner: s.owner, p_sha256: s.sha256 });
            var n = Number(a && a.messes) || 0;
            MM.show(block, false);
            MM.note(sMsg, "Blocked. It went silent in " + n + (n === 1 ? " mess." : " messes."), "ok");
          });
        });
        sKeys.appendChild(block);
      }
      box.appendChild(sKeys);
      box.appendChild(sAsk);
      box.appendChild(sMsg);
      return box;
    }

    openKey.addEventListener("click", function () {
      act(openKey, openMsg, async function () {
        MM.note(openMsg, "");
        var o = await MM.rpc("admin_open_report", { p_id: r.id });
        opened.textContent = "";
        var doc = o && o.doc && typeof o.doc === "object" ? o.doc : null;
        var facts = h("ul", { class: "facts" });
        if (doc && isFinite(Number(doc.bpm))) facts.appendChild(h("li", { text: Math.floor(Number(doc.bpm)) + " BPM" }));
        if (doc && Array.isArray(doc.kit)) facts.appendChild(h("li", { text: doc.kit.slice(0, 64).filter(function (k) { return typeof k === "string" && k !== ""; }).length + " pads in use" }));
        var sounds = (o && Array.isArray(o.sounds)) ? o.sounds : [];
        facts.appendChild(h("li", { text: sounds.length + (sounds.length === 1 ? " sound" : " sounds") }));
        opened.appendChild(facts);
        if (!doc) opened.appendChild(h("p", { class: "quiet", text: "The mess itself is gone." }));
        sounds.forEach(function (s) { opened.appendChild(soundRow(s || {})); });
        MM.show(openKey, false);
      }, { not_found: "This report is closed already. Press Look again." });
    });

    function resolve(action, words, question, yesWords, yesClass) {
      ask(asking, keys, question, yesWords, yesClass, msg, async function () {
        await MM.rpc("admin_resolve_report", { p_id: r.id, p_action: action, p_note: nf.input.value.slice(0, 300) });
        finish(art, words, action === "dismiss" ? "" : "ok", "reports");
      }, { not_found: "This report is closed already. Press Look again." });
    }
    down.addEventListener("click", function () {
      resolve("block_mess", "Taken down. The mess is blocked and its share link is off.", "Take this mess down? Nobody but its owner can open it after, and its share link stops.", "Yes, take it down", "danger");
    });
    dismiss.addEventListener("click", function () {
      resolve("dismiss", "Dismissed. Nothing was taken down.", "Dismiss this report? Nothing is taken down.", "Yes, dismiss", "");
    });
    return art;
  }

  // ---------- tabs, keys ----------
  var tabs = [$("tab-queue"), $("tab-reports")];
  function pick(tab, focus) {
    tabs.forEach(function (t) {
      var on = t === tab;
      t.setAttribute("aria-selected", on ? "true" : "false");
      t.tabIndex = on ? 0 : -1;
      MM.show($(t.getAttribute("aria-controls")), on);
    });
    if (focus) tab.focus();
  }
  tabs.forEach(function (t, i) {
    t.addEventListener("click", function () { pick(t, false); });
    t.addEventListener("keydown", function (ev) {
      if (ev.key === "ArrowRight" || ev.key === "ArrowLeft") { ev.preventDefault(); pick(tabs[(i + 1) % tabs.length], true); }
    });
  });
  $("again").addEventListener("click", function () { act($("again"), deskMsg, loadAll); });
  function out() { MM.signOut().then(function () { showGate(""); }); }
  $("out").addEventListener("click", out);
  $("out2").addEventListener("click", out);

  if (MM.session()) enter(); else showGate("");
})();
