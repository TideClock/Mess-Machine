/* Mess Machine web pages: the shared part. Sign-in with an email code, calls to the server,
   the error envelope and the words for each error. Written against docs/ACCOUNTS-API.md.
   Rules: text reaches the page through textContent only. No HTML strings, no eval, no CDN.
   web/vault/mm.js must stay an exact copy of web/account/mm.js (the test checks). */
(function () {
  "use strict";
  var W = window;
  var D = document;

  function str(v) { return typeof v === "string" ? v.trim() : ""; }

  // ---------- config ----------
  var BASE = str(W.MM_CLOUD_URL).replace(/\/+$/, "");
  var KEY = str(W.MM_CLOUD_KEY);
  var problem = "";
  if (BASE || KEY) {
    var urlOk = /^https:\/\/[a-z0-9.-]+(:\d+)?$/i.test(BASE) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE);
    if (!BASE || !KEY) problem = "config.js is half filled in. It needs both the server address and the key.";
    else if (!urlOk) problem = "The server address in config.js does not look right. It should read like https://abcdefgh.supabase.co";
    else if (secretKey(KEY)) problem = "config.js holds a secret key. Take it out now and use the publishable key.";
  }
  var configured = !!BASE && !!KEY && !problem;

  // A secret key must never be in a web page. Refuse to run with one.
  function secretKey(k) {
    if (/^sb_secret_/i.test(k)) return true;
    var parts = k.split(".");
    if (parts.length !== 3) return false;
    try {
      var b = parts[1].replace(/-/g, "+").replace(/_/g, "/");
      while (b.length % 4) b += "=";
      return JSON.parse(atob(b)).role === "service_role";
    } catch (e) { return false; }
  }

  // ---------- errors ----------
  var CODES = ("auth terms not_found forbidden blocked bad_input bad_name too_big rate_limited terms_outdated " +
    "too_many_messes docs_full conflict too_long too_many_pending quota not_uploaded gap snapshot_first day_limit " +
    "mess_full_live client_too_old ahead stale too_many_invites invite_invalid mess_full owner_cannot_leave no_copy " +
    "stale_login not_ready grant_outdated already_submitted already_reviewed not_copied").split(" ");

  /** code: a server code of ACCOUNTS-API s1.1, or net (no answer), auth, http (anything else),
   *  code_wrong, code_wait, bad_hash. http: the status, 0 for none. */
  function Err(code, http, detail) { this.code = code; this.http = http || 0; this.detail = detail || ""; }
  Err.prototype.toString = function () { return "MM.Err " + this.code + " " + this.http; };

  var WORDS = {
    auth: "You are signed out. Sign in again.",
    terms: "Open the app and agree to the terms first.",
    not_found: "That is not here any more.",
    forbidden: "This account cannot do that.",
    blocked: "This has been taken down.",
    bad_input: "Something in that was not right. Check it and try again.",
    rate_limited: "Too many tries. Wait a bit, then try again.",
    stale_login: "It has been a while since you signed in. We need a fresh code.",
    invite_invalid: "This invite has been used or has run out. Ask for a new one.",
    already_reviewed: "This one was already decided.",
    not_copied: "The sound did not land in the Vault. Press Approve again.",
    net: "No answer. Check your connection and try again.",
    http: "Something went wrong on our side. Try again in a bit.",
    code_wrong: "That code is not right, or it ran out. Use the newest email, or ask for a new code.",
    code_wait: "Wait a minute before asking for another code.",
    bad_hash: "The sound that arrived is not the one that was sent in. Do not approve it."
  };
  var cspBlocked = false;
  D.addEventListener("securitypolicyviolation", function (e) {
    if (String(e.effectiveDirective || e.violatedDirective).indexOf("connect-src") === 0) cspBlocked = true;
  });

  /** Plain words for an error. extra: {code: words} to say something more exact on one page. */
  function say(e, extra) {
    var code = e && e.code ? e.code : "http";
    if (code === "net" && cspBlocked) return "This site's security list does not name its server yet. See web/README.md, step 4.";
    if (extra && extra[code]) return extra[code];
    return WORDS[code] || WORDS.http;
  }

  // ---------- session ----------
  // Kept in memory. A page may ask to keep it for the life of the tab (the Vault page does).
  var session = null;
  var keepTab = false;
  var SKEY = "mm.session";

  function saveSession() {
    if (!keepTab) return;
    try {
      if (session) W.sessionStorage.setItem(SKEY, JSON.stringify(session));
      else W.sessionStorage.removeItem(SKEY);
    } catch (e) { /* private mode: memory only */ }
  }
  function setSession(s) { session = s; saveSession(); }
  function keepForTab() {
    keepTab = true;
    try {
      var j = JSON.parse(W.sessionStorage.getItem(SKEY) || "null");
      if (j && typeof j.access === "string" && typeof j.refresh === "string") session = j;
    } catch (e) { /* nothing stored */ }
  }
  function toSession(j, at) {
    if (!j || typeof j.access_token !== "string" || typeof j.refresh_token !== "string") return null;
    var u = j.user || {};
    return { access: j.access_token, refresh: j.refresh_token, expiresAt: Number(j.expires_at) || 0,
      userId: String(u.id || ""), email: String(u.email || ""), at: at };
  }

  // ---------- http ----------
  /** Always answers {status, json}; status 0 means no answer. */
  async function send(method, url, headers, body) {
    try {
      var res = await fetch(url, { method: method, headers: headers, body: body, cache: "no-store",
        credentials: "omit", referrerPolicy: "no-referrer" });
      var text = "";
      try { text = await res.text(); } catch (e) { /* empty */ }
      var json = null;
      try { json = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
      return { status: res.status, json: json };
    } catch (e) {
      return { status: 0, json: null };
    }
  }
  function head(json, withToken) {
    var h = { apikey: KEY };
    if (json) h["Content-Type"] = "application/json";
    if (withToken && session) h.Authorization = "Bearer " + session.access;
    return h;
  }
  function need() { if (!configured) throw new Err("http", 0, "not set up"); }

  // ---------- sign-in endpoints (s1.2) ----------
  async function requestCode(email) {
    need();
    var r = await send("POST", BASE + "/auth/v1/otp", head(true, false), JSON.stringify({ email: email, create_user: true }));
    if (r.status >= 200 && r.status < 300) return true;
    if (r.status === 0) throw new Err("net", 0);
    if (r.status === 429) throw new Err("code_wait", 429);
    throw new Err("http", r.status);
  }
  async function verifyCode(email, code) {
    need();
    var r = await send("POST", BASE + "/auth/v1/verify", head(true, false), JSON.stringify({ type: "email", email: email, token: code }));
    if (r.status >= 200 && r.status < 300) {
      var s = toSession(r.json, Date.now());
      if (!s) throw new Err("http", r.status, "no session in the answer");
      setSession(s);
      return s;
    }
    if (r.status === 0) throw new Err("net", 0);
    if (r.status === 429) throw new Err("code_wait", 429);
    if (r.status >= 400 && r.status < 500) throw new Err("code_wrong", r.status);
    throw new Err("http", r.status);
  }
  var refreshing = null;
  /** Refresh tokens are single use: the new session is saved before anything else happens. */
  function refresh() {
    if (refreshing) return refreshing;
    refreshing = (async function () {
      if (!session) throw new Err("auth", 0);
      var r = await send("POST", BASE + "/auth/v1/token?grant_type=refresh_token", head(true, false), JSON.stringify({ refresh_token: session.refresh }));
      if (r.status >= 200 && r.status < 300) {
        var s = toSession(r.json, session.at);
        if (!s) throw new Err("http", r.status);
        setSession(s);
        return s;
      }
      if (r.status === 0) throw new Err("net", 0);
      if (r.status === 400 || r.status === 401 || r.status === 403) { setSession(null); throw new Err("auth", r.status); }
      throw new Err("http", r.status);
    })();
    refreshing.then(done, done);
    function done() { refreshing = null; }
    return refreshing;
  }
  async function signOut() {
    if (configured && session) await send("POST", BASE + "/auth/v1/logout?scope=local", head(false, true), undefined);
    setSession(null);
    return true;
  }

  // ---------- RPC and Edge Functions (s1.1) ----------
  async function withRetry(doSend) {
    var r = await doSend();
    if (r.status === 401 && session) { await refresh(); r = await doSend(); }
    return r;
  }
  /** POST <url>/rest/v1/rpc/<name>. Answers the JSON value or throws an Err. */
  async function rpc(name, args) {
    need();
    var r = await withRetry(function () {
      return send("POST", BASE + "/rest/v1/rpc/" + name, head(true, true), JSON.stringify(args || {}));
    });
    if (r.status >= 200 && r.status < 300) return r.json;
    if (r.status === 0) throw new Err("net", 0);
    var j = r.json || {};
    if (j.code === "P0001" && typeof j.message === "string") {
      throw new Err(CODES.indexOf(j.message) >= 0 ? j.message : "http", r.status, j.message);
    }
    if (r.status === 401) { setSession(null); throw new Err("auth", 401); }
    if (r.status === 403 || j.code === "42501") throw new Err("auth", r.status);
    throw new Err("http", r.status, typeof j.message === "string" ? j.message : "");
  }
  /** POST <url>/functions/v1/<name>. Answers the JSON value or throws an Err. */
  async function fn(name, body) {
    need();
    var r = await withRetry(function () {
      return send("POST", BASE + "/functions/v1/" + name, head(true, true), JSON.stringify(body || {}));
    });
    if (r.status >= 200 && r.status < 300) return r.json;
    if (r.status === 0) throw new Err("net", 0);
    var j = r.json || {};
    if (typeof j.error === "string") {
      var known = CODES.indexOf(j.error) >= 0;
      if (j.error === "auth" && r.status === 401) setSession(null);
      throw new Err(known ? j.error : "http", r.status, j.error);
    }
    if (r.status === 401) { setSession(null); throw new Err("auth", 401); }
    throw new Err("http", r.status);
  }

  // ---------- files (s2) ----------
  /** GET a sound-urls address with no headers. Answers an ArrayBuffer. */
  async function fetchBytes(url) {
    var res;
    try { res = await fetch(url, { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" }); }
    catch (e) { throw new Err("net", 0); }
    if (!res.ok) throw new Err("http", res.status);
    try { return await res.arrayBuffer(); } catch (e) { throw new Err("net", 0); }
  }
  async function sha256(buf) {
    var d = new Uint8Array(await W.crypto.subtle.digest("SHA-256", buf));
    var s = "";
    for (var i = 0; i < d.length; i++) s += (d[i] < 16 ? "0" : "") + d[i].toString(16);
    return s;
  }
  /** POST the bytes to <bucket>/<path>. "Already there" counts as done. */
  async function upload(bucketPath, bytes, type) {
    need();
    var r = await withRetry(function () {
      var h = head(false, true);
      h["Content-Type"] = type;
      h["x-upsert"] = "false";
      return send("POST", BASE + "/storage/v1/object/" + bucketPath, h, new Blob([bytes], { type: type }));
    });
    if (r.status >= 200 && r.status < 300) return true;
    if (r.status === 0) throw new Err("net", 0);
    var j = r.json || {};
    if ((r.status === 400 || r.status === 409) && (j.error === "Duplicate" || String(j.statusCode) === "409")) return true;
    if (r.status === 401 || r.status === 403) throw new Err("auth", r.status);
    throw new Err("http", r.status);
  }

  // ---------- small page tools ----------
  /** Make an element. attrs: {class, text, hidden, ...attributes}. kids: nodes or plain words. */
  function h(tag, attrs, kids) {
    var el = D.createElement(tag);
    for (var k in attrs || {}) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) continue;
      if (k === "text") el.textContent = String(v);
      else if (k === "class") el.className = v;
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, String(v));
    }
    (kids || []).forEach(function (c) {
      if (c === null || c === undefined) return;
      el.appendChild(typeof c === "string" ? D.createTextNode(c) : c);
    });
    return el;
  }
  function $(id) { return D.getElementById(id); }
  function show(el, on) { if (el) el.hidden = !on; }
  function note(el, words, kind) {
    if (!el) return;
    el.textContent = words || "";
    el.className = "msg" + (kind ? " " + kind : "");
  }
  function busy(btn, on) {
    if (!btn) return;
    btn.disabled = !!on;
    if (on) btn.setAttribute("aria-busy", "true"); else btn.removeAttribute("aria-busy");
  }
  /** Run an async job from a key: the key sleeps while it runs, errors land in the message line. */
  async function press(btn, msg, job, extra) {
    if (btn && btn.getAttribute("aria-busy") === "true") return;
    busy(btn, true);
    try { await job(); }
    catch (e) { note(msg, say(e, extra), "bad"); }
    finally { busy(btn, false); }
  }
  function bytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + " bytes";
    if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + " KB";
    return (n / 1048576).toFixed(n < 10485760 ? 1 : 0) + " MB";
  }
  function when(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  function shortId(id) { return String(id || "").slice(0, 8); }
  /** The token in /m/<token> or /join/<token>. "" when the address is not a clean link. */
  function pathToken(prefixes) {
    var parts = W.location.pathname.split("/").filter(Boolean);
    if (parts.length !== 2 || prefixes.indexOf(parts[0]) < 0) return "";
    return /^[A-Za-z0-9_-]{16,128}$/.test(parts[1]) ? parts[1] : "";
  }

  // ---------- the email code form ----------
  var formCount = 0;
  /** Build the sign-in steps inside mount. opts: {email (skip the email step; a code is asked for at once),
   *  codeLabel, done(session)}. */
  function signIn(mount, opts) {
    opts = opts || {};
    var n = ++formCount;
    var email = "";
    var emailIn = h("input", { id: "mm-email-" + n, type: "email", name: "email", autocomplete: "email", inputmode: "email",
      autocapitalize: "none", spellcheck: "false", required: true, "aria-describedby": "mm-email-hint-" + n });
    var sendKey = h("button", { class: "key go wide", type: "submit", text: "Send me a code" });
    var emailForm = h("form", { class: "signin-email", novalidate: true }, [
      h("div", { class: "field" }, [
        h("label", { for: "mm-email-" + n, text: "Your email" }),
        emailIn,
        h("span", { class: "hint", id: "mm-email-hint-" + n, text: "We mail you a code. There is no password." })
      ]),
      h("div", { class: "keys" }, [sendKey])
    ]);
    var sentTo = h("strong", { class: "sent-to" });
    var codeIn = h("input", { id: "mm-code-" + n, class: "code", type: "text", name: "code", inputmode: "numeric",
      autocomplete: "one-time-code", maxlength: "10", pattern: "[0-9]*", spellcheck: "false" });
    var checkKey = h("button", { class: "key go wide", type: "submit", text: opts.codeLabel || "Check the code" });
    var againKey = h("button", { class: "linkish", type: "button", text: "Send a new code" });
    var otherKey = h("button", { class: "linkish", type: "button", text: "Use a different email" });
    var codeForm = h("form", { class: "signin-code", novalidate: true, hidden: true }, [
      h("p", {}, ["We mailed a code to ", sentTo, ". It works for 10 minutes."]),
      h("div", { class: "field" }, [h("label", { for: "mm-code-" + n, text: "The code from the email" }), codeIn]),
      h("div", { class: "keys" }, [checkKey]),
      h("div", { class: "keys" }, opts.email ? [againKey] : [againKey, otherKey])
    ]);
    var msg = h("p", { class: "msg", role: "status", "aria-live": "polite" });
    mount.textContent = "";
    mount.appendChild(emailForm);
    mount.appendChild(codeForm);
    mount.appendChild(msg);

    var askWords = { http: "We could not send a code to that address. Check the spelling and try again." };
    function toCode() {
      sentTo.textContent = email;
      show(emailForm, false);
      show(codeForm, true);
      codeIn.value = "";
      codeIn.focus();
    }
    async function ask(btn, first) {
      await press(btn, msg, async function () {
        note(msg, "");
        await requestCode(email);
        toCode();
        note(msg, first ? "" : "A new code is on its way.", "ok");
      }, askWords);
    }
    emailForm.addEventListener("submit", function (ev) {
      ev.preventDefault();
      var v = emailIn.value.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) || v.length > 254) { note(msg, "Type your whole email address, like name@example.com.", "bad"); emailIn.focus(); return; }
      email = v;
      ask(sendKey, true);
    });
    codeForm.addEventListener("submit", function (ev) {
      ev.preventDefault();
      var code = codeIn.value.replace(/\s+/g, "");
      if (!/^[0-9]{6,10}$/.test(code)) { note(msg, "The code is the 6 digit number in the email.", "bad"); codeIn.focus(); return; }
      press(checkKey, msg, async function () {
        note(msg, "");
        var s = await verifyCode(email, code);
        if (opts.done) await opts.done(s);
      });
    });
    againKey.addEventListener("click", function () { ask(againKey, false); });
    otherKey.addEventListener("click", function () { show(codeForm, false); show(emailForm, true); note(msg, ""); emailIn.focus(); });

    if (opts.email) {
      email = opts.email;
      show(emailForm, false);
      show(codeForm, true);
      sentTo.textContent = email;
      ask(againKey, true);
    }
    return { message: msg };
  }

  // ---------- things every page fills in ----------
  function fillPage() {
    var mail = str(W.MM_SUPPORT_EMAIL);
    var dev = str(W.MM_DEVELOPER);
    var play = str(W.MM_PLAY_URL);
    var mailOk = /^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/.test(mail);
    D.querySelectorAll("[data-mm-support]").forEach(function (el) {
      el.textContent = "";
      if (mailOk) el.appendChild(h("a", { href: "mailto:" + mail, text: mail }));
      else el.appendChild(h("span", { class: "blank", text: "[support email]" }));
    });
    D.querySelectorAll("[data-mm-developer]").forEach(function (el) {
      el.textContent = "";
      if (dev) el.textContent = dev;
      else el.appendChild(h("span", { class: "blank", text: "[developer name]" }));
    });
    D.querySelectorAll("[data-mm-play]").forEach(function (el) {
      el.textContent = "";
      if (/^https:\/\/play\.google\.com\//.test(play)) el.appendChild(h("a", { class: "key wide", href: play, rel: "noopener", text: "Get the app on Google Play" }));
      else el.appendChild(h("p", { class: "quiet", text: "On Google Play soon." }));
    });
    D.querySelectorAll("[data-mm-needs-server]").forEach(function (el) { show(el, !configured); });
    D.querySelectorAll("[data-mm-flow]").forEach(function (el) { show(el, configured); });
    D.querySelectorAll("[data-mm-problem]").forEach(function (el) { el.textContent = problem; show(el, !!problem); });
  }
  fillPage();

  W.MM = {
    configured: function () { return configured; },
    problem: function () { return problem; },
    Err: Err, say: say,
    session: function () { return session; }, keepForTab: keepForTab,
    requestCode: requestCode, verifyCode: verifyCode, refresh: refresh, signOut: signOut,
    forget: function () { setSession(null); },
    rpc: rpc, fn: fn, fetchBytes: fetchBytes, sha256: sha256, upload: upload,
    h: h, $: $, show: show, note: note, busy: busy, press: press, bytes: bytes, when: when, shortId: shortId,
    pathToken: pathToken, signIn: signIn
  };
})();
