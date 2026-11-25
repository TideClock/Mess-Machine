// The web pages' test. Run:  node web/account/test/run.mjs
// It serves web/account and web/vault on localhost the way Netlify would (it reads their _headers
// and _redirects), runs a small fake Supabase in memory (the auth endpoints, the RPCs and the Edge
// Functions these pages call, shaped as docs/ACCOUNTS-API.md says, with one fixed code) and drives
// every page in Chromium at 360 px and 1280 px wide. It prints PASS or FAIL per check and a total.
// Screenshots go to $MM_SHOTS (default: <temp folder>/mm-web-shots). Needs the playwright package.
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ACCOUNT = path.resolve(HERE, "..");
const VAULT = path.resolve(HERE, "../../vault");
const WEB = path.resolve(HERE, "../..");
const SHOTS = process.env.MM_SHOTS || path.join(os.tmpdir(), "mm-web-shots");
const PLACEHOLDER = "https://YOUR-PROJECT.supabase.co";
const KEY = "sb_publishable_test_key";
const CODE = "424242";
const XSS = "<img src=x onerror=alert(1)>";
const XSS2 = "\"><script>alert(2)</script>";
const XSS3 = "<svg onload=alert(3)>";
const GOOD = "Sh4reT0ken_abcdefghijklmnop-12345";
const INVITE = "Inv1teT0ken_abcdefghijklmnop-1234";
const DEAD = "DeadT0ken_abcdefghijklmnopqrs-123";
const DASH = new RegExp("[" + String.fromCharCode(0x2013, 0x2014) + "]");

let pass = 0, fail = 0;
function check(name, ok, detail) {
  console.log((ok ? "PASS  " : "FAIL  ") + name + (!ok && detail ? "   [" + String(detail).slice(0, 300) + "]" : ""));
  if (ok) pass++; else fail++;
}
const sha = (b) => crypto.createHash("sha256").update(b).digest("hex");
const uuid = () => crypto.randomUUID();
const hoursAgo = (h) => new Date(Date.now() - h * 3600e3).toISOString();

function wav(freq, seconds = 0.5, rate = 22050) {
  const n = Math.floor(seconds * rate);
  const b = Buffer.alloc(44 + n * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + n * 2, 4); b.write("WAVEfmt ", 8); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) b.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 9000), 44 + i * 2);
  return b;
}

// ---------------------------------------------------------------- the fake Supabase
function makeFake() {
  const st = {
    origin: "", users: new Map(), access: new Map(), refresh: new Map(),
    calls: [], violations: [], otp: [], verifies: 0, refreshes: 0, reports: [], vault: new Map(), gets: new Map(),
    blockedSounds: [], bans: new Map(), uploads: [],
    grant: { version: "2026-11", body: "The Vault grant as the server holds it.\n\n1. A line.\n2. Another line." },
  };
  const user = (email, more = {}) => {
    const u = { id: uuid(), email, admin: false, banned: false, name: email.split("@")[0], messes: 3, sound_bytes: 12 * 1048576, ...more };
    st.users.set(email, u);
    return u;
  };
  const keeper = user("keeper@example.test", { admin: true, name: "Tyler" });
  const mia = user("mia@example.test", { name: "Mia" });
  const bad = user("bad@example.test", { name: "Bad Actor" });
  const other = user("other@example.test");
  user("del@example.test", { name: "Del" });
  user("stale@example.test", { name: "Stale" });
  st.ids = { keeper: keeper.id, mia: mia.id, bad: bad.id, other: other.id };

  const A = wav(440), B = wav(660), C = wav(880);
  const tampered = Buffer.from(C); tampered[60] ^= 0xff;
  st.files = new Map([[sha(A), A], [sha(B), B], [sha(C), tampered]]);
  const sub = (o) => ({ id: uuid(), status: "pending", reviewer_note: "", grant_version: "2026-11", seconds: 0.5, rate: 22050, bpm: 0, beats: 0, mkey: "", others: [], ...o });
  st.subs = [
    sub({ submitter_id: mia.id, submitter_name: XSS2, sha256: sha(A), bytes: A.length, title: "Tin roof rain", credit_name: XSS, note: "Recorded on my porch. <b>All mine</b>", kind: "mic", submitted_at: hoursAgo(72), others: [{ owner: other.id, at: hoursAgo(480) }] }),
    sub({ submitter_id: mia.id, submitter_name: "Mia", sha256: sha(B), bytes: B.length, title: "Fridge hum", credit_name: "Mia K", note: "", kind: "imp", bpm: 96, beats: 4, mkey: "A minor", submitted_at: hoursAgo(30) }),
    sub({ submitter_id: bad.id, submitter_name: "Bad Actor", sha256: sha(C), bytes: C.length, title: "Swapped file", credit_name: "Sneaky", note: "trust me", kind: "imp", submitted_at: hoursAgo(5) }),
  ];
  const messId = uuid();
  const snd = (o) => ({ fmt: "wav", seconds: 0.5, rate: 22050, kind: "imp", bpm: 0, beats: 0, mkey: "", state: "ready", ...o });
  st.adminReports = [
    { id: uuid(), mess_id: messId, mess_name: XSS3, owner_id: bad.id, about_user: null, reason: "copyright", details: "This is my song " + XSS, via_token: true, status: "open", at: hoursAgo(21),
      doc: { name: "x", bpm: 120, kit: ["bn:a", "bn:v", "kick", ""] },
      sounds: [snd({ key: "bn:a", sha256: sha(A), src: "user", owner: bad.id, bytes: A.length, title: XSS }), snd({ key: "bn:v", sha256: sha(B), src: "vault", owner: null, bytes: B.length, title: "Vault clap" })] },
    { id: uuid(), mess_id: uuid(), mess_name: "Basement jam", owner_id: mia.id, about_user: other.id, reason: "harassment", details: "", via_token: false, status: "open", at: hoursAgo(2), doc: { bpm: 96, kit: [] }, sounds: [] },
  ];

  const session = (u, authAt) => {
    const a = "at_" + crypto.randomBytes(12).toString("hex"), r = "rt_" + crypto.randomBytes(12).toString("hex");
    st.access.set(a, { uid: u.id, email: u.email, dead: false, authAt });
    st.refresh.set(r, { email: u.email, authAt });
    return { access_token: a, token_type: "bearer", expires_in: 3600, expires_at: Math.floor(Date.now() / 1000) + 3600, refresh_token: r, user: { id: u.id, email: u.email } };
  };
  const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-upsert, x-client-info", "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Max-Age": "600" };
  const json = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json", ...cors }); res.end(body === undefined ? "" : JSON.stringify(body)); };
  const raise = (res, code) => json(res, 400, { code: "P0001", message: code, details: null, hint: null });

  // Who is calling. "none": no token. "dead": a token that ran out. Else the user.
  function caller(req) {
    const h = req.headers.authorization || "";
    if (!h) return "none";
    const t = st.access.get(h.replace(/^Bearer /, ""));
    if (!t || t.dead) return "dead";
    const u = st.users.get(t.email);
    return u ? { ...u, _tok: t } : "dead";
  }

  const rpc = {
    me(u) { return { id: u.id, display_name: u.name, name_set: true, colour: 3, sound_bytes: u.sound_bytes, sound_limit: 262144000, messes: u.messes, mess_limit: 200, terms_ok: true, terms_version: "2026-11", is_admin: u.admin }; },
    vault_queue(u, a) { return st.subs.filter((s) => s.status === (a.p_status || "pending")).map(({ status, reviewer_note, ...s }) => s); },
    vault_review(u, a) {
      const s = st.subs.find((x) => x.id === a.p_id);
      if (!s) throw "not_found";
      if (s.status !== "pending") throw "already_reviewed";
      if (a.p_decision !== "approved" && a.p_decision !== "declined") throw "bad_input";
      if (a.p_decision === "approved") { const o = st.vault.get(s.sha256); if (!o || o.length !== s.bytes) throw "not_copied"; }
      s.status = a.p_decision; s.reviewer_note = a.p_note || "";
      return { status: s.status };
    },
    admin_reports(u, a) { return st.adminReports.filter((r) => r.status === (a.p_status || "open")).map(({ doc, sounds, ...r }) => r); },
    admin_open_report(u, a) {
      const r = st.adminReports.find((x) => x.id === a.p_id && x.status === "open");
      if (!r) throw "not_found";
      const { doc, sounds, ...row } = r;
      return { report: row, doc, sounds };
    },
    admin_resolve_report(u, a) {
      const r = st.adminReports.find((x) => x.id === a.p_id && x.status === "open");
      if (!r) throw "not_found";
      if (a.p_action !== "dismiss" && a.p_action !== "block_mess") throw "bad_input";
      r.status = a.p_action === "dismiss" ? "dismissed" : "actioned"; r.note = a.p_note || "";
      return { status: r.status };
    },
    admin_block_sound(u, a) { st.blockedSounds.push({ owner: a.p_owner, sha256: a.p_sha256 }); return { messes: 2 }; },
    admin_ban_user(u, a) { st.bans.set(a.p_user, a.p_banned); return { banned: a.p_banned }; },
  };
  const ADMIN = ["vault_queue", "vault_review", "admin_reports", "admin_open_report", "admin_resolve_report", "admin_block_sound", "admin_ban_user"];
  const anon = {
    open_shared(a) {
      if (a.p_token !== GOOD) throw "not_found";
      return { name: XSS, owner_name: XSS2, mode: "copy", rev: 7, doc_rev: 7, doc: { name: XSS, bpm: 96.7, kit: ["kick", "clap", "", "synth:1", XSS] }, ops: [], sounds: [] };
    },
    invite_info(a) {
      if (a.p_token !== INVITE) throw "invite_invalid";
      return { mess_name: "Basement jam " + XSS3, owner_name: XSS };
    },
    report_mess(a) {
      if (!["sexual", "hate", "harassment", "copyright", "spam", "other"].includes(a.p_reason) || String(a.p_details || "").length > 500) throw "bad_input";
      if (a.p_token !== GOOD) throw "not_found";
      st.reports.push(a);
      return { id: uuid() };
    },
    vault_grant() { return { version: st.grant.version, body: st.grant.body, body_sha256: sha(st.grant.body) }; },
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks);
      if (req.method === "OPTIONS") { res.writeHead(204, cors); res.end(); return; }
      const p = url.pathname;
      // A signed download: no headers at all.
      if (req.method === "GET" && p.startsWith("/storage/v1/object/sign/sounds/")) {
        if (req.headers.authorization || req.headers.apikey) st.violations.push("headers on a signed download");
        const s = p.split("/").pop().replace(".wav", "");
        st.gets.set(s, (st.gets.get(s) || 0) + 1);
        const f = st.files.get(s);
        if (!f || !url.searchParams.get("token")) { json(res, 400, { error: "InvalidJWT" }); return; }
        res.writeHead(200, { "Content-Type": "audio/wav", "Content-Length": f.length, ...cors }); res.end(f); return;
      }
      if (req.headers.apikey !== KEY) st.violations.push("no apikey on " + p);
      if ((req.headers.authorization || "").includes(KEY)) st.violations.push("the publishable key in Authorization on " + p);
      let body = {};
      if ((req.headers["content-type"] || "").includes("application/json")) { try { body = JSON.parse(raw.toString() || "{}"); } catch { body = {}; } }
      const who = caller(req);

      if (p === "/auth/v1/otp") { st.otp.push(body); if (!st.users.has(body.email)) user(body.email); json(res, 200, {}); return; }
      if (p === "/auth/v1/verify") {
        st.verifies++;
        const u = st.users.get(body.email);
        if (body.type !== "email" || !u || body.token !== CODE) { json(res, 403, { code: 403, error_code: "otp_expired", msg: "Token has expired or is invalid" }); return; }
        json(res, 200, session(u, Date.now())); return;
      }
      if (p === "/auth/v1/token" && url.searchParams.get("grant_type") === "refresh_token") {
        st.refreshes++;
        const r = st.refresh.get(body.refresh_token);
        const u = r && st.users.get(r.email);
        if (!u) { json(res, 400, { code: 400, error_code: "refresh_token_not_found", msg: "Invalid Refresh Token" }); return; }
        st.refresh.delete(body.refresh_token);
        json(res, 200, session(u, r.authAt)); return;
      }
      if (p === "/auth/v1/logout") { const t = st.access.get((req.headers.authorization || "").replace(/^Bearer /, "")); if (t) t.dead = true; res.writeHead(204, cors); res.end(); return; }

      if (p.startsWith("/rest/v1/rpc/")) {
        const name = p.slice("/rest/v1/rpc/".length);
        st.calls.push({ name, who: typeof who === "object" ? who.email : who, body });
        if (anon[name]) { try { json(res, 200, anon[name](body)); } catch (e) { raise(res, e); } return; }
        if (!rpc[name]) { json(res, 404, { code: "PGRST202", message: "no such function" }); return; }
        if (who === "dead") { json(res, 401, { code: "PGRST301", message: "JWT expired" }); return; }
        if (who === "none" || who.banned) { raise(res, "auth"); return; }
        if (ADMIN.includes(name) && !who.admin) { raise(res, "forbidden"); return; }
        try { json(res, 200, rpc[name](who, body)); } catch (e) { raise(res, e); }
        return;
      }
      if (p.startsWith("/functions/v1/")) {
        const name = p.slice("/functions/v1/".length);
        st.calls.push({ name, who: typeof who === "object" ? who.email : who, body });
        if (who === "dead" || who === "none") { json(res, 401, { error: "auth" }); return; }
        if (name === "delete-account") {
          if (Date.now() - who._tok.authAt > 10 * 60e3) { json(res, 400, { error: "stale_login" }); return; }
          st.users.delete(who.email);
          json(res, 200, { ok: true }); return;
        }
        if (name === "sound-urls") {
          const keys = Object.keys(body);
          if (keys.length !== 1) { json(res, 400, { error: "bad_input" }); return; }
          if (!who.admin) { json(res, 400, { error: "not_found" }); return; }
          const mk = (s, owner) => ({ key: s.key || "bn:x", sha256: s.sha256, bytes: s.bytes, url: `${st.origin}/storage/v1/object/sign/sounds/${owner}/${s.sha256}.wav?token=signed` });
          if (body.submission) {
            const s = st.subs.find((x) => x.id === body.submission && x.status === "pending");
            if (!s) { json(res, 400, { error: "not_found" }); return; }
            json(res, 200, { sounds: [mk(s, s.submitter_id)], expires_in: 600 }); return;
          }
          if (body.report) {
            const r = st.adminReports.find((x) => x.id === body.report && x.status === "open");
            if (!r) { json(res, 400, { error: "not_found" }); return; }
            json(res, 200, { sounds: r.sounds.map((s) => mk(s, s.owner || "vault")), expires_in: 600 }); return;
          }
          json(res, 400, { error: "bad_input" }); return;
        }
        json(res, 404, { error: "no such function" }); return;
      }
      if (req.method === "POST" && p.startsWith("/storage/v1/object/vault/")) {
        const s = p.split("/").pop().replace(".wav", "");
        st.uploads.push({ sha: s, type: req.headers["content-type"], upsert: req.headers["x-upsert"], who: typeof who === "object" ? who.email : who });
        if (who === "dead") { json(res, 401, { statusCode: "401", error: "Unauthorized", message: "jwt expired" }); return; }
        if (who === "none" || !who.admin) { json(res, 403, { statusCode: "403", error: "Unauthorized", message: "new row violates row-level security policy" }); return; }
        if (st.vault.has(s)) { json(res, 400, { statusCode: "409", error: "Duplicate", message: "The resource already exists" }); return; }
        st.vault.set(s, raw);
        json(res, 200, { Key: "vault/" + s + ".wav" }); return;
      }
      json(res, 404, { message: "not found" });
    });
  });
  return { st, server };
}

// ---------------------------------------------------------------- a static server that reads _headers and _redirects
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".json": "application/json", ".txt": "text/plain; charset=utf-8", ".woff2": "font/woff2", ".ttf": "font/ttf" };
function globMatch(pattern, p) {
  if (pattern.endsWith("/*")) return p === pattern.slice(0, -2) || p.startsWith(pattern.slice(0, -1));
  return pattern === p;
}
function readHeaders(dir) {
  const blocks = [];
  let cur = null;
  for (const line of fs.readFileSync(path.join(dir, "_headers"), "utf8").split("\n")) {
    if (!line.trim() || line.trim().startsWith("#")) continue;
    if (!/^\s/.test(line)) { cur = { pattern: line.trim(), headers: [] }; blocks.push(cur); }
    else if (cur) { const i = line.indexOf(":"); cur.headers.push([line.slice(0, i).trim(), line.slice(i + 1).trim()]); }
  }
  return blocks;
}
function readRedirects(dir) {
  const f = path.join(dir, "_redirects");
  if (!fs.existsSync(f)) return [];
  return fs.readFileSync(f, "utf8").split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#")).map((l) => {
    const [from, to, code = "301"] = l.split(/\s+/);
    return { from, to, status: parseInt(code, 10), force: code.endsWith("!") };
  });
}
function serveSite(dir, cloudOrigin, configJs) {
  const blocks = readHeaders(dir), rules = readRedirects(dir);
  const fileFor = (p) => {
    let f = path.join(dir, p);
    if (!f.startsWith(dir)) return null;
    if (fs.existsSync(f) && fs.statSync(f).isDirectory()) f = path.join(f, "index.html");
    return fs.existsSync(f) && fs.statSync(f).isFile() ? f : null;
  };
  return http.createServer((req, res) => {
    let p;
    try { p = decodeURIComponent(new URL(req.url, "http://x").pathname); } catch { p = "/"; }
    let status = 200;
    let file = null;
    const forced = rules.find((r) => r.force && globMatch(r.from, p));
    if (forced) { file = fileFor(forced.to); status = forced.status; }
    if (!file) file = fileFor(p);
    if (!file) { const r = rules.find((x) => !x.force && globMatch(x.from, p)); if (r) { file = fileFor(r.to); status = r.status; } }
    if (!file) { file = fileFor("/404.html"); status = 404; }
    const headers = { "Content-Type": TYPES[path.extname(file)] || "application/octet-stream" };
    for (const b of blocks) if (globMatch(b.pattern, p)) for (const [k, v] of b.headers) headers[k] = v.split(PLACEHOLDER).join(cloudOrigin || PLACEHOLDER);
    let body = fs.readFileSync(file);
    if (configJs && path.basename(file) === "config.js") body = Buffer.from(configJs);
    res.writeHead(status, headers);
    res.end(body);
  });
}
const listen = (server) => new Promise((ok) => server.listen(0, "127.0.0.1", () => ok("http://127.0.0.1:" + server.address().port)));

// ---------------------------------------------------------------- checks on the files themselves
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, out); else out.push(f);
  }
  return out;
}
function sourceChecks() {
  const files = walk(WEB);
  const text = files.filter((f) => /\.(html|js|mjs|css|md|json|txt)$|_headers$|_redirects$|apple-app-site-association$/.test(f));
  const dashes = text.filter((f) => DASH.test(fs.readFileSync(f, "utf8")));
  check("no em or en dashes anywhere under web/", dashes.length === 0, dashes.join(", "));
  const pageJs = files.filter((f) => f.endsWith(".js") && !f.includes(path.sep + "test" + path.sep));
  const risky = /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\s*\(|new Function|srcdoc|createContextualFragment|DOMParser/;
  const hits = pageJs.filter((f) => risky.test(fs.readFileSync(f, "utf8")));
  check("page scripts never build HTML from strings (textContent only)", hits.length === 0, hits.join(", "));
  const html = files.filter((f) => f.endsWith(".html"));
  const inline = html.filter((f) => { const s = fs.readFileSync(f, "utf8"); return /<script(?![^>]*\ssrc=)[^>]*>/i.test(s) || /<style[\s>]/i.test(s) || /\sstyle\s*=/i.test(s) || /\son[a-z]+\s*=/i.test(s) || /https?:\/\/[^"']*\.(js|css)/i.test(s); });
  check("no inline script, inline style, on-handlers or CDN files in any page", inline.length === 0, inline.join(", "));
  for (const f of ["mm.js", "mm.css", "config.js", ...fs.readdirSync(path.join(ACCOUNT, "fonts")).map((n) => "fonts/" + n)]) {
    const a = path.join(ACCOUNT, f), b = path.join(VAULT, f);
    check("the Vault site's copy of " + f + " is the same as the account site's", fs.existsSync(b) && fs.readFileSync(a).equals(fs.readFileSync(b)));
  }
  const cfg = fs.readFileSync(path.join(ACCOUNT, "config.js"), "utf8");
  check("config.js ships with an empty MM_CLOUD_URL and MM_CLOUD_KEY", /MM_CLOUD_URL = "";/.test(cfg) && /MM_CLOUD_KEY = "";/.test(cfg));
  for (const [name, dir] of [["account", ACCOUNT], ["vault", VAULT]]) {
    const csp = (readHeaders(dir).find((b) => b.pattern === "/*").headers.find(([k]) => k === "Content-Security-Policy") || [])[1] || "";
    const srcs = (d) => ((csp.split(";").map((s) => s.trim()).find((s) => s.startsWith(d + " ")) || "").split(/\s+/).slice(1));
    check(name + " CSP: default-src 'none', scripts and styles from self only", srcs("default-src").join() === "'none'" && srcs("script-src").join() === "'self'" && srcs("style-src").join() === "'self'", csp);
    check(name + " CSP: connect only to self and the one Supabase placeholder", srcs("connect-src").join() === "'self'," + PLACEHOLDER, csp);
    check(name + " CSP: no unsafe-inline, unsafe-eval, wildcards or data:", !/unsafe|\*|data:/.test(csp), csp);
  }
  // The terms page's own copy of the Vault grant is the server's seeded wording, version for version.
  const mig = path.resolve(WEB, "../server/supabase/migrations");
  const seedFile = fs.existsSync(mig) ? fs.readdirSync(mig).find((f) => /slice_f\.sql$/.test(f)) : null;
  if (seedFile) {
    const sql = fs.readFileSync(path.join(mig, seedFile), "utf8");
    const part = sql.slice(sql.indexOf("insert into public.vault_grants"), sql.indexOf("as body"));
    const version = (part.match(/select '([^']+)', g\.body/) || [])[1];
    const body = [...part.slice(part.indexOf("from (select")).matchAll(/'((?:[^']|'')*)'/g)].map((m) => m[1].replace(/''/g, "'")).join("");
    const page = fs.readFileSync(path.join(ACCOUNT, "terms/index.html"), "utf8");
    const mine = (page.match(/id="grant-body">([^<]*)</) || [])[1];
    const myVersion = (page.match(/id="grant-version">([^<]*)</) || [])[1];
    check("terms page: its Vault grant text and version are the ones the server migration seeds", !!body && mine === body && myVersion === version, myVersion + " vs " + version);
  }
  let links = null, aasa = null;
  try { links = JSON.parse(fs.readFileSync(path.join(ACCOUNT, ".well-known/assetlinks.json"), "utf8")); } catch { /* fails below */ }
  try { aasa = JSON.parse(fs.readFileSync(path.join(ACCOUNT, ".well-known/apple-app-site-association"), "utf8")); } catch { /* fails below */ }
  check("assetlinks.json names the app package and has a fingerprint slot", !!links && links[0].target.package_name === "app.shotgunstudio.messmachine" && links[0].target.sha256_cert_fingerprints.length === 1);
  const comps = aasa ? aasa.applinks.details[0].components.map((c) => c["/"]) : [];
  check("apple-app-site-association covers /m/* and /join/*", comps.includes("/m/*") && comps.includes("/join/*"), comps.join());
}

// ---------------------------------------------------------------- browser helpers
async function open(ctx, url, origins) {
  const page = await ctx.newPage();
  page.mm = { dialogs: 0, errors: [], outside: [] };
  page.on("dialog", (d) => { page.mm.dialogs++; d.dismiss().catch(() => {}); });
  page.on("pageerror", (e) => page.mm.errors.push("pageerror: " + e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource/.test(m.text())) page.mm.errors.push(m.text()); });
  page.on("request", (r) => { const u = r.url(); if (!/^(blob:|data:)/.test(u) && !origins.some((o) => u.startsWith(o))) page.mm.outside.push(u); });
  await page.goto(url, { waitUntil: "networkidle" });
  return page;
}
async function vis(page, sel, t = 5000) {
  try { await page.locator(sel).first().waitFor({ state: "visible", timeout: t }); return true; } catch { return false; }
}
async function has(page, sel, words, t = 5000) {
  try { await page.locator(sel).filter({ hasText: words }).first().waitFor({ state: "visible", timeout: t }); return true; } catch { return false; }
}
const text = (page, sel) => page.locator(sel).first().textContent();
async function clean(page, name) {
  check(name + ": no dialog fired, no script error, no blocked or outside request", page.mm.dialogs === 0 && page.mm.errors.length === 0 && page.mm.outside.length === 0,
    JSON.stringify(page.mm));
}
async function layout(page, name, w) {
  await page.evaluate(() => document.fonts.ready);
  const r = await page.evaluate(() => {
    const seen = (el) => { const b = el.getBoundingClientRect(); return b.width > 0 && b.height > 0 && getComputedStyle(el).visibility !== "hidden"; };
    const small = [...document.querySelectorAll("button, a.key, .brand, footer a, input:not([type=checkbox]):not([type=radio]), textarea, label.check, label.choice")]
      .filter(seen).filter((el) => el.getBoundingClientRect().height < 44).map((el) => el.tagName + "." + el.className + " " + Math.round(el.getBoundingClientRect().height));
    const bare = [...document.querySelectorAll("input, textarea")].filter(seen).filter((el) => !(el.labels && el.labels.length) && !el.getAttribute("aria-label")).map((el) => el.id || el.name);
    return { wide: document.documentElement.scrollWidth - window.innerWidth, small, bare, fonts: document.fonts.check("16px Worn") && document.fonts.check("16px Body") && document.fonts.check("16px Lcd"),
      h1: document.querySelectorAll("h1").length, lang: document.documentElement.lang };
  });
  check(`${name} @${w}: fits the width, tap targets 44 px or more, every field labelled, fonts loaded`,
    r.wide <= 0 && r.small.length === 0 && r.bare.length === 0 && r.fonts && r.h1 === 1 && r.lang === "en", JSON.stringify(r));
}
async function shot(page, name, w) {
  await page.screenshot({ path: path.join(SHOTS, `${w}-${name}.png`), fullPage: true });
}
async function signIn(page, mount, email, code) {
  await page.fill(`${mount} input[type=email]`, email);
  await page.click(`${mount} .signin-email button[type=submit]`);
  await vis(page, `${mount} .signin-code`);
  await page.fill(`${mount} input.code`, code);
  await page.click(`${mount} .signin-code button[type=submit]`);
}

// ---------------------------------------------------------------- the run at one width
async function runAt(browser, w) {
  const T = (s) => `[${w}] ${s}`;
  const { st, server: cloud } = makeFake();
  const cloudOrigin = await listen(cloud);
  st.origin = cloudOrigin;
  const cfg = `var MM_CLOUD_URL = "${cloudOrigin}";\nvar MM_CLOUD_KEY = "${KEY}";\nvar MM_SUPPORT_EMAIL = "help@example.com";\nvar MM_DEVELOPER = "Test Developer";\nvar MM_PLAY_URL = "";\n`;
  const servers = [cloud];
  const up = async (dir, live) => { const s = serveSite(dir, live ? cloudOrigin : "", live ? cfg : ""); servers.push(s); return listen(s); };
  const acct0 = await up(ACCOUNT, false), acct = await up(ACCOUNT, true), vault0 = await up(VAULT, false), vault = await up(VAULT, true);
  const ctx = await browser.newContext({ viewport: { width: w, height: w < 600 ? 740 : 900 }, deviceScaleFactor: w < 600 ? 2 : 1, hasTouch: w < 600 });
  let page;

  // ---- not set up: the real, empty config.js
  for (const [name, url] of [["home", acct0 + "/"], ["delete", acct0 + "/delete/"], ["share", acct0 + "/m/" + GOOD], ["invite", acct0 + "/join/" + INVITE], ["privacy", acct0 + "/privacy/"], ["terms", acct0 + "/terms/"], ["vault", vault0 + "/"]]) {
    page = await open(ctx, url, [acct0, vault0]);
    const calm = await page.evaluate(() => {
      const n = document.querySelector("[data-mm-needs-server]"), f = document.querySelector("[data-mm-flow]");
      return !!n && !n.hidden && /not set up yet/i.test(n.textContent) && (!f || f.hidden);
    });
    check(T(`not set up: ${name} says so calmly and shows no form`), calm);
    await clean(page, T(`not set up: ${name}`));
    if (name === "delete") { await layout(page, "delete, not set up", w); await shot(page, "delete-notsetup", w); }
    await page.close();
  }
  check(T("not set up: no page called the server"), st.calls.length === 0 && st.otp.length === 0);

  // ---- headers and rewrites as served
  let r = await fetch(acct + "/delete/");
  check(T("served: /delete/ carries the CSP and the security headers"), /default-src 'none'/.test(r.headers.get("content-security-policy") || "") && (r.headers.get("content-security-policy") || "").includes(cloudOrigin)
    && r.headers.get("x-content-type-options") === "nosniff" && r.headers.get("x-frame-options") === "DENY" && r.headers.get("referrer-policy") === "no-referrer");
  r = await fetch(acct + "/.well-known/apple-app-site-association");
  check(T("served: the two app-link files answer as JSON"), r.status === 200 && r.headers.get("content-type") === "application/json" && (await fetch(acct + "/.well-known/assetlinks.json")).status === 200);
  check(T("served: the test folder is not published"), (await fetch(acct + "/test/run.mjs")).status === 404);
  check(T("served: an unknown address is a 404 page"), (await fetch(acct + "/nope")).status === 404);

  // ---- home
  page = await open(ctx, acct + "/", [acct, cloudOrigin]);
  check(T("home: links to privacy, terms and delete"), (await page.locator("main a.key").count()) === 3);
  await layout(page, "home", w); await shot(page, "home", w); await clean(page, T("home")); await page.close();

  // ---- delete: wrong code, right code, delete, confirmation
  page = await open(ctx, acct + "/delete/", [acct, cloudOrigin]);
  check(T("delete: says what goes and what stays before any sign-in"), (await page.locator(".goes li").count()) >= 5 && (await page.locator(".stays li").count()) >= 3 && await vis(page, "#signin input[type=email]"));
  await page.click("#signin .signin-email button[type=submit]");
  check(T("delete: an empty email is caught on the page"), await has(page, "#signin .msg", "whole email") && st.otp.length === 0);
  await page.fill("#signin input[type=email]", "del@example.test");
  await page.click("#signin .signin-email button[type=submit]");
  check(T("delete: asking for a code calls /auth/v1/otp with create_user true"), await vis(page, "#signin .signin-code") && st.otp.length === 1 && st.otp[0].email === "del@example.test" && st.otp[0].create_user === true);
  await page.fill("#signin input.code", "12");
  await page.click("#signin .signin-code button[type=submit]");
  check(T("delete: a code that is too short never reaches the server"), await has(page, "#signin .msg", "6 digit") && st.verifies === 0);
  await page.fill("#signin input.code", "000000");
  await page.click("#signin .signin-code button[type=submit]");
  check(T("delete: a wrong code says so and stays on the code step"), await has(page, "#signin .msg", "not right") && await vis(page, "#signin input.code") && !(await page.locator("#step-go").isVisible()));
  await page.fill("#signin input.code", CODE);
  await page.click("#signin .signin-code button[type=submit]");
  check(T("delete: the right code opens step 2 with who is signed in"), await vis(page, "#step-go") && /del@example.test/.test(await text(page, "#who")) && await has(page, "#facts", "Online messes: 3"));
  check(T("delete: the Delete key sleeps until the box is ticked"), await page.locator("#go").isDisabled());
  await page.check("#agree");
  check(T("delete: ticking the box wakes the Delete key"), await page.locator("#go").isEnabled());
  await layout(page, "delete, step 2", w); await shot(page, "delete", w);
  await page.click("#go");
  const delCalls = () => st.calls.filter((c) => c.name === "delete-account");
  check(T("delete: delete-account ran once and the page confirms"), await has(page, "#done", "Your account is deleted.") && delCalls().length === 1 && delCalls()[0].who === "del@example.test" && !st.users.has("del@example.test"));
  await shot(page, "delete-done", w); await clean(page, T("delete")); await page.close();

  // ---- delete: a sign-in the server calls stale needs a fresh code
  page = await open(ctx, acct + "/delete/", [acct, cloudOrigin]);
  await signIn(page, "#signin", "stale@example.test", CODE);
  await vis(page, "#step-go");
  for (const t of st.access.values()) if (t.email === "stale@example.test") t.authAt = Date.now() - 11 * 60e3;
  const otpBefore = st.otp.length;
  await page.check("#agree"); await page.click("#go");
  check(T("delete: stale_login asks for a fresh code and mails one"), await vis(page, "#fresh-form input.code") && st.otp.length === otpBefore + 1 && st.users.has("stale@example.test"));
  await page.fill("#fresh-form input.code", CODE);
  await page.click("#fresh-form .signin-code button[type=submit]");
  check(T("delete: the fresh code finishes the delete"), await has(page, "#done", "Your account is deleted.") && !st.users.has("stale@example.test"));
  await clean(page, T("delete, fresh code")); await page.close();

  // ---- share landing with hostile names
  page = await open(ctx, acct + "/m/" + GOOD, [acct, cloudOrigin]);
  await vis(page, "#found");
  check(T("share: a hostile mess name and maker name show as plain text"), (await text(page, "#mess-name")) === XSS && (await text(page, "#owner-name")) === XSS2
    && await page.evaluate(() => document.querySelector("#mess-name").childElementCount === 0 && document.querySelector("#owner-name").childElementCount === 0 && document.images.length === 0 && !document.querySelector("main script, main svg")));
  check(T("share: tempo and pad count come from the mess"), await has(page, "#facts", "96 BPM") && await has(page, "#facts", "4 pads") && await has(page, "#facts", "keep a copy"));
  check(T("share: Open in Mess Machine points at the app link"), (await page.getAttribute("#open", "href")) === "messmachine://m/" + GOOD);
  check(T("share: the Play Store slot is a placeholder until a link is set"), await has(page, "[data-mm-play]", "On Google Play soon"));
  await page.click("#rep-open");
  await page.click("#rep-send");
  check(T("share: a report needs a reason"), await has(page, "#rep-msg", "Pick what is wrong") && st.reports.length === 0);
  await page.check("#rep input[value=copyright]");
  await page.fill("#rep-details", "mine " + XSS);
  await layout(page, "share", w); await shot(page, "share", w);
  await page.click("#rep-send");
  check(T("share: report_mess is sent with the token, the reason and no mess id"), await vis(page, "#rep-done") && st.reports.length === 1 && st.reports[0].p_token === GOOD && st.reports[0].p_mess === null && st.reports[0].p_reason === "copyright" && st.reports[0].p_details === "mine " + XSS && !("p_user" in st.reports[0]));
  const anonCalls = st.calls.filter((c) => ["open_shared", "report_mess"].includes(c.name));
  check(T("share: the page calls the server signed out"), anonCalls.length >= 2 && anonCalls.every((c) => c.who === "none"));
  await clean(page, T("share")); await page.close();

  page = await open(ctx, acct + "/m/" + DEAD, [acct, cloudOrigin]);
  check(T("share: a dead link says so"), await has(page, "#gone", "does not open a mess any more") && !(await page.locator("#found").isVisible()));
  await clean(page, T("share, dead link")); await page.close();
  const before = st.calls.length;
  page = await open(ctx, acct + "/m/" + encodeURIComponent(XSS), [acct, cloudOrigin]);
  check(T("share: a link that is not a token is refused on the page, no call made"), await has(page, "#gone", "not a whole share link") && st.calls.length === before);
  await clean(page, T("share, hostile address")); await page.close();

  // ---- invite landing
  page = await open(ctx, acct + "/join/" + INVITE, [acct, cloudOrigin]);
  await vis(page, "#found");
  check(T("invite: names from invite_info show as plain text"), (await text(page, "#mess-name")) === "Basement jam " + XSS3 && (await text(page, "#owner-name")) === XSS && await page.evaluate(() => document.querySelector(".nameplate").querySelectorAll("img, svg, script").length === 0));
  check(T("invite: Open in Mess Machine points at the app link"), (await page.getAttribute("#open", "href")) === "messmachine://j/" + INVITE);
  await layout(page, "invite", w); await shot(page, "invite", w); await clean(page, T("invite")); await page.close();
  page = await open(ctx, acct + "/j/" + INVITE, [acct, cloudOrigin]);
  check(T("invite: the short address /j/<token> opens the same page"), await vis(page, "#found") && (await page.getAttribute("#open", "href")) === "messmachine://j/" + INVITE);
  await page.close();
  page = await open(ctx, acct + "/join/" + DEAD, [acct, cloudOrigin]);
  check(T("invite: a used or ended invite says so"), await has(page, "#gone", "used or has run out"));
  await clean(page, T("invite, dead")); await page.close();

  // ---- privacy and terms
  page = await open(ctx, acct + "/privacy/", [acct, cloudOrigin]);
  check(T("privacy: marked as a draft, with a blank to fill"), await has(page, ".draft", "Draft") && (await page.locator(".blank").count()) >= 1 && await has(page, "main", "up to 7 days") && await has(page, "main", "GitHub"));
  await layout(page, "privacy", w); await shot(page, "privacy", w); await clean(page, T("privacy")); await page.close();
  page = await open(ctx, acct + "/terms/", [acct, cloudOrigin]);
  await vis(page, "#grant-state");
  check(T("terms: marked as a draft; 13 or older, no tolerance, 24 hours"), await has(page, ".draft", "Draft") && await has(page, "main", "13 or older") && await has(page, "main", "no tolerance for objectionable content or abusive users") && await has(page, "main", "within 24 hours"));
  check(T("terms: the Vault grant version matches vault_grant and shows the server's text"), (await text(page, "#grant-version")) === st.grant.version && (await text(page, "#grant-body")) === st.grant.body && await has(page, "#grant-state.ok", "version 2026-11"));
  await layout(page, "terms", w); await shot(page, "terms", w); await clean(page, T("terms")); await page.close();
  st.grant = { version: "2027-01", body: "New words <b>bold</b> " + XSS };
  page = await open(ctx, acct + "/terms/", [acct, cloudOrigin]);
  await vis(page, "#grant-state");
  check(T("terms: a newer grant on the server is shown as text and flagged"), (await text(page, "#grant-version")) === "2027-01" && (await text(page, "#grant-body")) === st.grant.body && await has(page, "#grant-state.bad", "Update this page") && await page.evaluate(() => document.querySelector("#grant-body").childElementCount === 0));
  await clean(page, T("terms, newer grant")); await page.close();

  // ---- the Vault desk, not an admin
  const vctx = await browser.newContext({ viewport: { width: w, height: w < 600 ? 740 : 900 }, deviceScaleFactor: w < 600 ? 2 : 1, hasTouch: w < 600 });
  page = await open(vctx, vault + "/", [vault, cloudOrigin]);
  await signIn(page, "#signin", "mia@example.test", CODE);
  check(T("vault, not an admin: only the keeper line shows"), await vis(page, "#notadmin") && (await text(page, "#notadmin-words")) === "This page is only for the Vault keeper." && !(await page.locator("#desk").isVisible()));
  check(T("vault, not an admin: no admin call was even tried"), st.calls.filter((c) => c.who === "mia@example.test").every((c) => c.name === "me"));
  await layout(page, "vault, not an admin", w); await shot(page, "vault-notadmin", w);
  await page.click("#out2");
  check(T("vault, not an admin: Sign out goes back to the sign-in"), await vis(page, "#signin input[type=email]"));
  await clean(page, T("vault, not an admin")); await page.close();

  // ---- the Vault desk, as the keeper
  page = await open(vctx, vault + "/", [vault, cloudOrigin]);
  await signIn(page, "#signin", "keeper@example.test", "111111");
  check(T("vault: a wrong code says so"), await has(page, "#signin .msg", "not right") && !(await page.locator("#desk").isVisible()));
  await page.fill("#signin input.code", CODE);
  await page.click("#signin .signin-code button[type=submit]");
  check(T("vault: the keeper gets the desk with both counts"), await vis(page, "#desk") && await has(page, "#who", "Vault keeper: Tyler") && (await text(page, "#n-queue")) === "3" && (await text(page, "#n-reports")) === "2");
  const [s1, s2, s3] = st.subs;
  const c1 = `article[data-sub="${s1.id}"]`, c2 = `article[data-sub="${s2.id}"]`, c3 = `article[data-sub="${s3.id}"]`;
  check(T("vault: a hostile credit name, maker name and note show as plain text"), await has(page, c1 + " dd", XSS) && await has(page, c1 + " dd", XSS2) && await has(page, c1 + " .said", "<b>All mine</b>") && await page.evaluate(() => document.images.length === 0 && !document.querySelector("main script, main svg, main b")));
  check(T("vault: the theft check lists another owner of the same sound, with a date"), await has(page, c1 + " .theft.warn", "1 other account has this exact sound") && await has(page, c1 + " .theft.warn li", st.ids.other.slice(0, 8)) && await has(page, c2 + " .theft", "Nobody else"));
  check(T("vault: Approve sleeps before the sound is loaded and heard"), await page.locator(c1 + " button:text-is('Approve')").isDisabled());
  await page.click(c1 + " button:text-is('Load the sound')");
  check(T("vault: the sound is fetched once and its fingerprint checked"), await has(page, c1, "same sound that was sent in") && await vis(page, c1 + " audio") && st.gets.get(s1.sha256) === 1);
  check(T("vault: loaded but not heard, Approve still sleeps"), await page.locator(c1 + " button:text-is('Approve')").isDisabled());
  await page.click(c1 + " button:text-is('Play')");
  let woke = true;
  try { await page.locator(c1 + " button:text-is('Approve')").and(page.locator(":enabled")).waitFor({ timeout: 5000 }); } catch { woke = false; }
  check(T("vault: playing it wakes Approve"), woke && await page.evaluate((sel) => document.querySelector(sel + " audio").src.startsWith("blob:"), c1));
  await page.fill(c1 + " textarea", "Lovely. " + XSS);
  await layout(page, "vault desk", w); await shot(page, "vault", w);
  for (const t of st.access.values()) if (t.email === "keeper@example.test") t.dead = true;   // the access token runs out mid-review
  const refreshesBefore = st.refreshes;
  await page.click(c1 + " button:text-is('Approve')");
  check(T("vault: Approve asks first"), await has(page, c1 + " .ask", "stays for good"));
  await page.click(c1 + " button:text-is('Yes, approve')");
  check(T("vault: approved, after a silent token refresh"), await has(page, c1, "Approved. It is in the Vault.") && st.refreshes === refreshesBefore + 1 && s1.status === "approved" && s1.reviewer_note === "Lovely. " + XSS);
  const stored = st.vault.get(s1.sha256);
  check(T("vault: the bytes in the Vault are the bytes that were played (same sha256, fetched once)"), !!stored && sha(stored) === s1.sha256 && stored.length === s1.bytes && st.gets.get(s1.sha256) === 1
    && st.uploads.at(-1).type === "audio/wav" && st.uploads.at(-1).upsert === "false");
  check(T("vault: the count drops"), (await text(page, "#n-queue")) === "2");

  await page.fill(c2 + " textarea", "Too quiet");
  await page.click(c2 + " button:text-is('Decline')");
  await page.click(c2 + " button:text-is('Yes, decline')");
  check(T("vault: declined with the note, nothing copied"), await has(page, c2, "Declined.") && s2.status === "declined" && s2.reviewer_note === "Too quiet" && !st.vault.has(s2.sha256) && (await text(page, "#n-queue")) === "1");

  await page.click(c3 + " button:text-is('Load the sound')");
  check(T("vault: a file that is not the one sent in is refused and cannot be approved"), await has(page, c3 + " .msg.bad", "not the one that was sent in") && !(await page.locator(c3 + " audio").isVisible()) && await page.locator(c3 + " button:text-is('Approve')").isDisabled());

  await page.click("#tab-reports");
  const [r1, r2] = st.adminReports;
  const d1 = `article[data-report="${r1.id}"]`, d2 = `article[data-report="${r2.id}"]`;
  check(T("vault: the reports tab lists open reports, hostile names as text, old ones flagged"), await vis(page, "#panel-reports") && (await text(page, d1 + " h3")) === XSS3 && await has(page, d1 + " .msg.bad", "21 hours old") && await has(page, d1 + " .said", XSS) && !(await page.locator("#panel-queue").isVisible()));
  await page.click(d1 + " button:text-is('Open the report')");
  check(T("vault: admin_open_report shows the mess and its sounds"), await has(page, d1 + " .facts", "120 BPM") && await has(page, d1 + " .facts", "2 sounds") && (await page.locator(d1 + " .sound").count()) === 2 && await has(page, d1 + " .sound strong", XSS));
  await page.click(d1 + " .sound >> nth=0 >> button:text-is('Listen')");
  check(T("vault: a reported sound can be heard through sound-urls {report}"), await vis(page, d1 + " .sound >> nth=0 >> audio") && st.calls.some((c) => c.name === "sound-urls" && c.body.report === r1.id));
  await layout(page, "vault reports", w); await shot(page, "vault-reports", w);
  check(T("vault: only a person's own sound can be blocked, not a Vault one"), (await page.locator(d1 + " button:text-is('Block this sound')").count()) === 1);
  await page.click(d1 + " button:text-is('Block this sound')");
  await page.click(d1 + " button:text-is('Yes, block it')");
  check(T("vault: admin_block_sound gets the owner and the sha256"), await has(page, d1 + " .sound", "went silent in 2 messes") && st.blockedSounds.length === 1 && st.blockedSounds[0].owner === st.ids.bad && st.blockedSounds[0].sha256 === r1.sounds[0].sha256);
  await page.click(d1 + " button:text-is('Ban the mess owner')");
  check(T("vault: a ban asks first"), await has(page, d1 + " .ask", "cannot sign in") && st.bans.size === 0);
  await page.click(d1 + " button:text-is('Yes, ban')");
  check(T("vault: admin_ban_user bans the mess owner"), await has(page, d1, "Banned.") && st.bans.get(st.ids.bad) === true && await vis(page, d1 + " button:text-is('Undo the ban')"));
  await page.click(d1 + " button:text-is('Take the mess down')");
  await page.click(d1 + " button:text-is('Yes, take it down')");
  check(T("vault: the report is closed as actioned"), await has(page, d1, "Taken down.") && r1.status === "actioned" && (await text(page, "#n-reports")) === "1");
  check(T("vault: a report about a person offers to ban that person too"), await vis(page, d2 + " button:text-is('Ban the reported person')") && await has(page, d2 + " dd", st.ids.other.slice(0, 8)));
  await page.click(d2 + " button:text-is('Dismiss')");
  await page.click(d2 + " button:text-is('Yes, dismiss')");
  check(T("vault: a report can be dismissed"), await has(page, d2, "Dismissed.") && r2.status === "dismissed" && (await text(page, "#n-reports")) === "0");

  await page.reload({ waitUntil: "networkidle" });
  check(T("vault: a reload keeps the keeper signed in for this tab"), await vis(page, "#desk") && (await text(page, "#n-queue")) === "1" && !(await page.locator("#gate").isVisible()));
  await page.click("#out");
  check(T("vault: Sign out goes back to the sign-in"), await vis(page, "#signin input[type=email]") && !(await page.locator("#desk").isVisible()));
  await page.reload({ waitUntil: "networkidle" });
  check(T("vault: after Sign out a reload stays signed out"), await vis(page, "#signin input[type=email]"));
  await clean(page, T("vault desk")); await page.close();

  check(T("every call carried the apikey header, and the key never rode in Authorization"), st.violations.length === 0, st.violations.join("; "));
  await ctx.close(); await vctx.close();
  await Promise.all(servers.map((s) => new Promise((ok) => { s.closeAllConnections?.(); s.close(ok); })));
}

// ---------------------------------------------------------------- go
fs.mkdirSync(SHOTS, { recursive: true });
sourceChecks();
const browser = await chromium.launch({ args: ["--autoplay-policy=no-user-gesture-required"] });
try {
  for (const w of [360, 1280]) await runAt(browser, w);
} catch (e) {
  check("the run finished without crashing", false, e && e.stack ? e.stack : e);
} finally {
  await browser.close();
}
console.log(`\n${pass} passed, ${fail} failed. Screenshots: ${SHOTS}`);
console.log(fail === 0 ? "PASS" : "FAIL");
process.exit(fail === 0 ? 0 : 1);
