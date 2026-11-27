// The live checklist (server/tests/live-checklist.md, spec 8e) against a REAL Supabase: the local stack
// that "supabase start" runs (the CI job), or the real project. It proves what plain Postgres cannot:
// sign-in by email code, PostgREST's error envelope, Storage, Realtime and the Edge Functions.
//
//   SUPABASE_URL          the project URL, e.g. http://127.0.0.1:54321
//   SUPABASE_KEY          the publishable (or anon) key: what the apps carry
//   SUPABASE_SERVICE_KEY  the secret (or service_role) key: used only to make and remove the test accounts
//   MAIL_URL              optional: the local mail catcher (Mailpit or Inbucket). With it, the first account
//                         signs in by a real code read from the mail. Without it, codes come from the admin API.
//   DB_URL                optional: a Postgres login to the same database, for the catalogue checks
//   MM_SWEEP_SECRET       optional: the sweep function's secret, to check the sweep runs
//   MM_FUNCTIONS          optional: "off" skips the Edge Function checks (they are found automatically)
//   MM_KEEP=1             optional: leave the test accounts in place afterwards
//
// One line per check: PASS, FAIL or SKIP. A total at the end. Exit code 1 when anything failed.
import { createHash, randomUUID } from 'node:crypto';

const URL0 = (process.env.SUPABASE_URL || '').replace(/\/$/, '');
const KEY = process.env.SUPABASE_KEY || '';
const SECRET = process.env.SUPABASE_SERVICE_KEY || '';
const MAIL = (process.env.MAIL_URL || '').replace(/\/$/, '');
const run = randomUUID().slice(0, 8);
let pass = 0; let fail = 0; let skip = 0; const failed = [];
let group = '';

const section = (n) => { group = n; console.log(`\n# ${n}`); };
function check(name, cond, detail = '') {
  const label = `${group}: ${name}`;
  if (cond) { pass++; console.log(`PASS ${label}`); } else { fail++; failed.push(label); console.log(`FAIL ${label}${detail ? '  <- ' + detail : ''}`); }
  return !!cond;
}
function skipped(name, why) { skip++; console.log(`SKIP ${group}: ${name} (${why})`); }
const sha = (b) => createHash('sha256').update(b).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const short = (v) => { const s = typeof v === 'string' ? v : JSON.stringify(v); return s && s.length > 220 ? s.slice(0, 220) + '...' : s; };

// ----- HTTP the way the apps do it -----
async function http(method, path, { token, body, headers = {}, key = KEY, raw } = {}) {
  const h = { apikey: key, ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  else if (key === SECRET && SECRET.split('.').length === 3) h.Authorization = `Bearer ${SECRET}`;   // legacy JWT keys
  let data;
  if (raw !== undefined) data = raw;
  else if (body !== undefined) { data = JSON.stringify(body); h['Content-Type'] = h['Content-Type'] || 'application/json'; }
  try {
    const res = await fetch(path.startsWith('http') ? path : URL0 + path, { method, headers: h, body: data });
    const buf = Buffer.from(await res.arrayBuffer());
    let json = null; try { json = JSON.parse(buf.toString('utf8')); } catch { /* not JSON */ }
    return { status: res.status, json, buf, text: buf.toString('utf8') };
  } catch (e) { return { status: 0, json: null, text: String(e), buf: Buffer.alloc(0) }; }
}
// An RPC, read as CloudApi reads it: a value, or an error code (s1.1 of the contract)
async function rpc(token, name, args = {}) {
  const r = await http('POST', `/rest/v1/rpc/${name}`, { token, body: args });
  if (r.status >= 200 && r.status < 300) return { ok: r.json, status: r.status };
  let err = 'http';
  if (r.status === 0) err = 'net';
  else if (r.json && r.json.code === 'P0001') err = r.json.message;
  else if (r.status === 401 || r.status === 403 || (r.json && r.json.code === '42501')) err = 'auth';
  return { err, status: r.status, body: r.json || r.text };
}
const fn = (token, name, body, headers = {}) => http('POST', `/functions/v1/${name}`, { token, body, headers });

// ----- accounts -----
async function readCode(email) {
  for (let i = 0; i < 40; i++) {
    // Mailpit (newer CLI)
    let mp = await http('GET', `${MAIL}/api/v1/search?query=${encodeURIComponent('to:' + email)}`, { key: '' });
    if (!(mp.status === 200 && mp.json?.messages?.length)) {
      const all = await http('GET', `${MAIL}/api/v1/messages`, { key: '' });
      const hit = (all.json?.messages || []).filter((x) => (x.To || []).some((t) => (t.Address || '').toLowerCase() === email));
      mp = { status: all.status, json: { messages: hit } };
    }
    if (mp.status === 200 && mp.json?.messages?.length) {
      const msg = await http('GET', `${MAIL}/api/v1/message/${mp.json.messages[0].ID}`, { key: '' });
      const m = /\b(\d{6,10})\b/.exec((msg.json?.Text || '') + ' ' + (msg.json?.HTML || ''));
      if (m) return m[1];
    }
    // Inbucket (older CLI)
    const box = email.split('@')[0];
    const ib = await http('GET', `${MAIL}/api/v1/mailbox/${box}`, { key: '' });
    if (ib.status === 200 && Array.isArray(ib.json) && ib.json.length) {
      const msg = await http('GET', `${MAIL}/api/v1/mailbox/${box}/${ib.json.at(-1).id}`, { key: '' });
      const m = /\b(\d{6,10})\b/.exec((msg.json?.body?.text || '') + ' ' + (msg.json?.body?.html || ''));
      if (m) return m[1];
    }
    await sleep(500);
  }
  return null;
}
// A fresh account, signed in with a code that came from the admin API (no mail needed)
async function account(label) {
  const email = `mm-live-${run}-${label}@example.com`;
  await http('POST', '/auth/v1/admin/users', { key: SECRET, body: { email, email_confirm: true } });
  const link = await http('POST', '/auth/v1/admin/generate_link', { key: SECRET, body: { type: 'magiclink', email } });
  const otp = link.json?.email_otp || link.json?.properties?.email_otp;
  const v = await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: otp } });
  if (!v.json?.access_token) throw new Error(`could not sign in ${label}: ${short(link.json)} ${short(v.json)}`);
  return { email, label, token: v.json.access_token, id: v.json.user.id, session: v.json };
}
const made = [];

// ----- a small WAV file -----
function wav(n, seed) {
  const data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) data.writeInt16LE(Math.round(Math.sin((i + seed) / 7) * 9000), i * 2);
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(44100, 24);
  h.writeUInt32LE(88200, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}
const upload = (token, path, bytes, upsert = false) => http('POST', `/storage/v1/object/sounds/${path}`, {
  token, raw: bytes, headers: { 'Content-Type': 'audio/wav', 'x-upsert': upsert ? 'true' : 'false' } });

// ----- realtime -----
function socket() {
  const ws = new WebSocket(`${URL0.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${encodeURIComponent(KEY)}&vsn=1.0.0`);
  const got = []; let ref = 0;
  ws.addEventListener('message', (e) => { try { got.push(JSON.parse(e.data)); } catch { /* ignore */ } });
  const open = new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
  const beat = setInterval(() => { try { ws.send(JSON.stringify({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: String(++ref) })); } catch { /* closed */ } }, 20000);
  return {
    got, open,
    async join(topic, token, me) {
      const r = String(++ref);
      ws.send(JSON.stringify({ topic: 'realtime:' + topic, event: 'phx_join', ref: r, join_ref: r, payload: {
        config: { broadcast: { ack: false, self: false }, presence: { enabled: true, key: me }, private: true }, access_token: token } }));
      for (let i = 0; i < 50; i++) {
        const rep = got.find((m) => m.event === 'phx_reply' && m.ref === r);
        if (rep) return { ok: rep.payload?.status === 'ok', reply: rep, joinRef: r };
        const err = got.find((m) => m.topic === 'realtime:' + topic && (m.event === 'phx_error' || m.event === 'system' && m.payload?.status === 'error'));
        if (err) return { ok: false, reply: err, joinRef: r };
        await sleep(100);
      }
      return { ok: false, reply: 'no answer', joinRef: r };
    },
    push(topic, event, payload, joinRef) { ws.send(JSON.stringify({ topic: 'realtime:' + topic, event, payload, ref: String(++ref), join_ref: joinRef })); },
    async wait(test, ms = 6000) { for (let i = 0; i < ms / 100; i++) { const m = got.find(test); if (m) return m; await sleep(100); } return null; },
    close() { clearInterval(beat); try { ws.close(); } catch { /* closed */ } },
  };
}

async function main() {
  section('set-up');
  if (!check('SUPABASE_URL, SUPABASE_KEY and SUPABASE_SERVICE_KEY are set', URL0 && KEY && SECRET)) return;
  const health = await http('GET', '/auth/v1/health');
  if (!check('the project answers', health.status === 200, `${health.status} ${short(health.text)}`)) return;

  // 1. the catalogue, on the real database
  section('1 catalogue on the real database');
  if (process.env.DB_URL) {
    const pg = (await import('pg')).default;
    const db = new pg.Client({ connectionString: process.env.DB_URL });
    await db.connect();
    const q = async (t) => (await db.query(t)).rows;
    const fns = await q(`select p.proname f, p.prosecdef d, coalesce(array_to_string(p.proconfig, ','), '') c,
        has_function_privilege('anon', p.oid, 'execute') a, has_function_privilege('authenticated', p.oid, 'execute') u
        from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public'`);
    const anonOk = ['invite_info', 'open_shared', 'report_mess', 'vault_grant'];
    check('public has the 49 calls of the contract and the 5 service calls', fns.length === 54, String(fns.length));
    check('every public function is security definer with an empty search path', fns.every((x) => x.d && x.c.includes('search_path=""')), fns.filter((x) => !x.d || !x.c.includes('search_path=""')).map((x) => x.f).join());
    check('anon may run only invite_info, open_shared, report_mess and vault_grant', fns.filter((x) => x.a).map((x) => x.f).sort().join() === anonOk.join(), fns.filter((x) => x.a).map((x) => x.f).join());
    check('no client may run a svc_ function', fns.filter((x) => x.f.startsWith('svc_') && (x.a || x.u)).length === 0);
    const tabs = await q(`select c.relname t, c.relrowsecurity r, has_table_privilege('anon', c.oid, 'select') or has_table_privilege('authenticated', c.oid, 'select')
        or has_table_privilege('authenticated', c.oid, 'insert') or has_table_privilege('authenticated', c.oid, 'update') g
        from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname in ('public', 'mm_private') and c.relkind = 'r'`);
    check('every table has row level security on and no client grant', tabs.every((x) => x.r && !x.g), tabs.filter((x) => !x.r || x.g).map((x) => x.t).join());
    const b = await q(`select id, public, file_size_limit::bigint l, allowed_mime_types m from storage.buckets where id in ('sounds', 'vault') order by id`);
    check('buckets sounds and vault: private, 20 MB, wav and flac', b.length === 2 && b.every((x) => !x.public && Number(x.l) === 20971520 && x.m.join() === 'audio/wav,audio/flac'), short(b));
    const sp = (await q(`select polname from pg_policy where polrelid = 'storage.objects'::regclass and polname like 'mm_%' order by 1`)).map((x) => x.polname).join();
    check('our six storage policies are in place (no update policy)', sp === 'mm_sounds_delete,mm_sounds_insert,mm_sounds_select,mm_vault_delete,mm_vault_insert,mm_vault_select', sp);
    const rp = (await q(`select polname from pg_policy where polrelid = 'realtime.messages'::regclass and polname like 'mm_%' order by 1`)).map((x) => x.polname).join();
    check('our two realtime policies are in place', rp === 'mm_rt_listen,mm_rt_presence', rp);
    await db.end();
  } else skipped('the catalogue checks', 'DB_URL not set');

  // 2. sign-in
  section('2 sign in with an email code');
  let A;
  if (MAIL) {
    const email = `mm-live-${run}-a@example.com`;
    const ask = await http('POST', '/auth/v1/otp', { body: { email, create_user: true } });
    check('POST /auth/v1/otp answers 200', ask.status === 200, `${ask.status} ${short(ask.text)}`);
    const code = await readCode(email);
    check('a code arrives by mail, 6 digits', code && /^\d{6}$/.test(code), String(code));
    const again = await http('POST', '/auth/v1/otp', { body: { email, create_user: true } });
    check('asking again at once is refused with 429 (code_wait)', again.status === 429, `${again.status} ${short(again.text)}`);
    const wrong = await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: code === '000000' ? '111111' : '000000' } });
    check('a wrong code is a 4xx that is not 429 (code_wrong)', wrong.status >= 400 && wrong.status < 500 && wrong.status !== 429, `${wrong.status} ${short(wrong.json)}`);
    check('its error_code is otp_expired', wrong.json?.error_code === 'otp_expired', short(wrong.json));
    const v = await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: code } });
    const s = v.json || {};
    check('the right code with type "email" gives a SessionJson', v.status === 200 && s.access_token && s.token_type === 'bearer' && Number.isInteger(s.expires_in)
      && Number.isInteger(s.expires_at) && s.refresh_token && s.user?.id && s.user?.email === email, short(s));
    if (s.access_token) A = { email, label: 'a', token: s.access_token, id: s.user.id, session: s };
  } else skipped('the mailed code', 'MAIL_URL not set; the code comes from the admin API instead');
  if (!A) A = await account('a');
  made.push(A);
  const ref = await http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: A.session.refresh_token } });
  check('refresh gives a new session', ref.status === 200 && ref.json?.access_token && ref.json?.refresh_token, `${ref.status}`);
  if (ref.json?.access_token) { A.token = ref.json.access_token; A.session = ref.json; }
  const badRef = await http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: 'not-a-token' } });
  check('a dead refresh token is a 400, 401 or 403 (auth)', [400, 401, 403].includes(badRef.status), String(badRef.status));
  const B = await account('b'); const C = await account('c'); const D = await account('d'); const ADM = await account('admin');
  made.push(B, C, D, ADM);
  const pw = await http('POST', '/auth/v1/signup', { body: { email: `mm-live-${run}-pw@example.com`, password: 'Password-123-xyz' } });
  if (pw.json?.access_token) {
    made.push({ id: pw.json.user.id });
    check('a direct password sign-up cannot call me() (Review A M7)', (await rpc(pw.json.access_token, 'me')).err === 'auth');
  } else {
    if (pw.json?.id || pw.json?.user?.id) made.push({ id: pw.json.id || pw.json.user.id });
    check('a direct password sign-up gets no session (Confirm email is on)', !pw.json?.access_token, short(pw.json));
  }

  // 3. the RPCs over PostgREST
  section('3 calls over PostgREST');
  const anonMe = await rpc(null, 'me');
  check('me() with no session is refused (read as auth)', anonMe.err === 'auth', `${anonMe.status} ${short(anonMe.body)}`);
  const me0 = await rpc(A.token, 'me');
  check('me() works and the terms are not yet accepted', me0.ok?.id === A.id && me0.ok.terms_ok === false && me0.ok.terms_version, short(me0));
  const early = await rpc(A.token, 'create_mess', { p_id: randomUUID(), p_name: 'x', p_doc: {} });
  check('the error envelope: HTTP 400, code P0001, message "terms"', early.status === 400 && early.err === 'terms', `${early.status} ${short(early.body)}`);
  for (const u of [A, B, C, D, ADM]) {
    await rpc(u.token, 'accept_terms', { p_version: me0.ok.terms_version });
    await rpc(u.token, 'set_profile', { p_display_name: 'Live ' + u.label });
  }
  check('accept_terms and set_profile', (await rpc(A.token, 'me')).ok?.terms_ok === true);
  const mess = randomUUID();
  check('create_mess', (await rpc(A.token, 'create_mess', { p_id: mess, p_name: 'Live check', p_doc: { v: 3, bpm: 120 } })).ok?.rev === 0);
  const p1 = await rpc(A.token, 'put_doc', { p_mess: mess, p_doc: { v: 3, bpm: 121 }, p_base_rev: 0, p_label: 'Saved' });
  check('put_doc', p1.ok?.rev === 1, short(p1));
  const clash = await rpc(A.token, 'put_doc', { p_mess: mess, p_doc: { v: 3 }, p_base_rev: 0 });
  check('put_doc on an old rev: 400 and "conflict"', clash.status === 400 && clash.err === 'conflict', short(clash));
  check('my_messes lists it', (await rpc(A.token, 'my_messes')).ok?.some((r) => r.id === mess));
  const inv = await rpc(A.token, 'make_invite', { p_mess: mess });
  check('make_invite', /^[A-Za-z0-9_-]{32}$/.test(inv.ok?.token || ''), short(inv));
  check('invite_info with no session', (await rpc(null, 'invite_info', { p_token: inv.ok?.token })).ok?.mess_name === 'Live check');
  check('redeem_invite', (await rpc(B.token, 'redeem_invite', { p_token: inv.ok?.token })).ok?.role === 'editor');
  check('a stranger cannot open it', (await rpc(C.token, 'open_mess', { p_mess: mess })).err === 'not_found');
  const opened = await rpc(B.token, 'open_mess', { p_mess: mess });
  check('the editor opens it and gets a topic', /^mess:[0-9a-f-]{36}:[0-9a-f]{16}$/.test(opened.ok?.topic || ''), short(opened));
  const topic = opened.ok?.topic;

  // 4. storage
  section('4 storage: upload, finish, refusals, a signed download');
  const file = wav(4000, 1); const h = sha(file);
  const reg = await rpc(A.token, 'register_sound', { p_sha256: h, p_bytes: file.length, p_seconds: 4000 / 44100, p_rate: 44100, p_kind: 'imp',
    p_local_key: 'bn:live', p_title: 'Live kick', p_bpm: 0, p_beats: 0, p_mkey: '' });
  check('register_sound gives the path', reg.ok?.state === 'upload' && reg.ok.path === `${A.id}/${h}.wav`, short(reg));
  const stranger = await upload(C.token, reg.ok?.path, file);
  check('a stranger cannot upload into my folder', stranger.status >= 400, `${stranger.status} ${short(stranger.text)}`);
  const unreg = await upload(A.token, `${A.id}/${sha('unregistered')}.wav`, file);
  check('I cannot upload a file I did not register', unreg.status >= 400, `${unreg.status} ${short(unreg.text)}`);
  const up = await upload(A.token, reg.ok?.path, file);
  check('the upload answers 200 with a Key', up.status === 200 && (up.json?.Key || up.json?.key), `${up.status} ${short(up.text)}`);
  const dup = await upload(A.token, reg.ok?.path, file);
  check('the same upload again answers "Duplicate" (400 or 409), which counts as done', [400, 409].includes(dup.status) && /duplicate|exists/i.test(dup.text), `${dup.status} ${short(dup.text)}`);
  const fin = await rpc(A.token, 'finish_upload', { p_sha256: h });
  check('finish_upload reads the real size from storage.objects.metadata', fin.ok?.state === 'ready' && fin.ok.used >= file.length, short(fin));
  const over = await upload(A.token, reg.ok?.path, wav(10, 2), true);
  check('nobody can overwrite it, not even with x-upsert', over.status >= 400, `${over.status} ${short(over.text)}`);
  const read = await http('GET', `/storage/v1/object/authenticated/sounds/${reg.ok?.path}`, { token: A.token });
  check('not even its owner can read it with their own token (all reading goes through sound-urls)', read.status >= 400, String(read.status));
  check('attach_sound', (await rpc(A.token, 'attach_sound', { p_mess: mess, p_key: 'bn:live', p_owner: A.id, p_sha256: h })).ok?.ok === true);
  const signed = await http('POST', `/storage/v1/object/sign/sounds/${reg.ok?.path}`, { key: SECRET, body: { expiresIn: 600 } });
  const surl = signed.json?.signedURL || signed.json?.signedUrl;
  const got = surl ? await http('GET', surl.startsWith('http') ? surl : `${URL0}/storage/v1${surl}`, { key: '' }) : { status: 0, buf: Buffer.alloc(0) };
  check('a signed URL downloads the same bytes with no headers', got.status === 200 && sha(got.buf) === h, `${got.status}`);

  // 5. realtime
  section('5 realtime');
  const sA = socket(); const sB = socket(); const sC = socket();
  try {
    await Promise.all([sA.open, sB.open, sC.open]);
    const jB = await sB.join(topic, B.token, B.id);
    check('a member joins the private mess topic', jB.ok, short(jB.reply));
    const jA = await sA.join(topic, A.token, A.id);
    check('the owner joins too', jA.ok, short(jA.reply));
    const jC = await sC.join(topic, C.token, C.id);
    const plain = await sC.join(`mess:${mess}`, B.token, B.id);
    await sleep(500);
    const ap = await rpc(A.token, 'apply_ops', { p_mess: mess, p_batch: randomUUID(), p_ops: [{ t: 'song.set', b: 0, v: 1 }], p_v: 1 });
    check('apply_ops', ap.ok?.rev > 0, short(ap));
    const heard = await sB.wait((m) => m.event === 'broadcast' && m.payload?.event === 'ops' && m.payload?.payload?.rev === ap.ok?.rev);
    check('the other member hears the batch as a broadcast "ops"', !!heard, short(sB.got.slice(-3)));
    check('a stranger is refused the join, or hears nothing', !jC.ok || !sC.got.some((m) => m.event === 'broadcast' && m.topic === 'realtime:' + topic));
    check('the plain topic mess:<id> hears nothing', !sC.got.some((m) => m.event === 'broadcast' && m.topic === `realtime:mess:${mess}`), short(plain.reply));
    sB.push(topic, 'broadcast', { type: 'broadcast', event: 'ops', payload: { fake: true } }, jB.joinRef);
    const fake = await sA.wait((m) => m.event === 'broadcast' && m.payload?.payload?.fake, 2500);
    check('a client broadcast is not delivered (no insert policy for broadcast)', !fake);
    sB.push(topic, 'presence', { type: 'presence', event: 'track', payload: { u: B.id, page: 'play', loop: 0, pad: -1, playing: false, rec: false } }, jB.joinRef);
    const pres = await sA.wait((m) => (m.event === 'presence_diff' || m.event === 'presence_state') && JSON.stringify(m.payload).includes(B.id));
    check('presence comes through', !!pres);
    await rpc(A.token, 'remove_member', { p_mess: mess, p_user: B.id });
    const gone = await sB.wait((m) => m.event === 'broadcast' && m.payload?.event === 'meta' && m.payload?.payload?.gone === B.id);
    check('a removed member is told {kind:"members", gone}', !!gone);
  } catch (e) {
    check('realtime ran', false, String(e));
  } finally { sA.close(); sB.close(); sC.close(); }

  // 6. the Edge Functions
  section('6 Edge Functions');
  const probe = await http('OPTIONS', '/functions/v1/sound-urls', { headers: { Origin: 'https://example.com', 'Access-Control-Request-Method': 'POST' } });
  if (process.env.MM_FUNCTIONS === 'off' || probe.status === 404 || probe.status === 0 || probe.status >= 500) {
    skipped('every Edge Function check', `not served here (${probe.status})`);
  } else {
    check('CORS: the preflight is answered with Access-Control-Allow-Origin', probe.status < 300, String(probe.status));
    const link = (await rpc(A.token, 'set_share', { p_mess: mess, p_mode: 'view' })).ok?.share_token;
    const mine = await fn(A.token, 'sound-urls', { mess });
    const one = mine.json?.sounds?.[0];
    check('sound-urls {"mess"} as a member: one sound, an absolute URL, expires_in 600', mine.status === 200 && mine.json.expires_in === 600 && one?.sha256 === h && /^https?:\/\//.test(one?.url || ''), `${mine.status} ${short(mine.json)}`);
    if (one?.url) { const dl = await http('GET', one.url, { key: '' }); check('the URL gives the same bytes with no headers', dl.status === 200 && sha(dl.buf) === h, String(dl.status)); }
    const guest = await fn(null, 'sound-urls', { token: link });
    check('sound-urls {"token"} works with no session', guest.status === 200 && guest.json?.sounds?.length === 1, `${guest.status} ${short(guest.json)}`);
    const nope = await fn(C.token, 'sound-urls', { mess });
    check('a stranger gets 400 {"error":"not_found"}', nope.status === 400 && nope.json?.error === 'not_found', `${nope.status} ${short(nope.json)}`);
    const badTok = await fn('not.a.token', 'sound-urls', { mess });
    check('a bad token gets 401 {"error":"auth"}', badTok.status === 401 && badTok.json?.error === 'auth', `${badTok.status} ${short(badTok.json)}`);
    const two = await fn(A.token, 'sound-urls', { mess, token: link });
    check('two things at once gets 400 {"error":"bad_input"}', two.status === 400 && two.json?.error === 'bad_input', short(two.json));
    const sw0 = await fn(null, 'sweep', {}, { 'x-mm-sweep': 'wrong' });
    check('sweep with the wrong secret: 401 {"error":"auth"}', sw0.status === 401 && sw0.json?.error === 'auth', `${sw0.status} ${short(sw0.json)}`);
    if (process.env.MM_SWEEP_SECRET) {
      const sw = await fn(null, 'sweep', {}, { 'x-mm-sweep': process.env.MM_SWEEP_SECRET });
      check('sweep with the secret: {removed, finished}', sw.status === 200 && Number.isInteger(sw.json?.removed) && Number.isInteger(sw.json?.finished), `${sw.status} ${short(sw.json)}`);
    } else skipped('sweep with the secret', 'MM_SWEEP_SECRET not set');
    const noTok = await fn(null, 'delete-account', {});
    check('delete-account with no session: 401 {"error":"auth"}', noTok.status === 401 && noTok.json?.error === 'auth', `${noTok.status} ${short(noTok.json)}`);
    const dfile = wav(500, 3); const dh = sha(dfile);
    const dreg = await rpc(D.token, 'register_sound', { p_sha256: dh, p_bytes: dfile.length, p_seconds: 0.02, p_rate: 44100, p_kind: 'mic', p_local_key: '', p_title: 'd', p_bpm: 0, p_beats: 0, p_mkey: '' });
    await upload(D.token, dreg.ok?.path, dfile); await rpc(D.token, 'finish_upload', { p_sha256: dh });
    const del = await fn(D.token, 'delete-account', {});
    check('delete-account with a fresh sign-in: {ok: true}', del.status === 200 && del.json?.ok === true, `${del.status} ${short(del.json)}`);
    check('afterwards their token is refused', (await rpc(D.token, 'me')).err === 'auth');
    const left = await http('POST', '/storage/v1/object/list/sounds', { key: SECRET, body: { prefix: D.id + '/', limit: 10 } });
    check('and their files are gone', left.status === 200 && Array.isArray(left.json) && left.json.length === 0, `${left.status} ${short(left.json)}`);
    const user = await http('GET', `/auth/v1/admin/users/${D.id}`, { key: SECRET });
    check('and so is their sign-in', user.status === 404, String(user.status));
    D.gone = true;
    skipped('delete-account refuses a sign-in over 10 minutes old', 'needs a 10 minute wait; proven in SQL by the stale_login tests');
  }

  // 7. the Vault
  section('7 the Vault');
  const addAdmin = await http('POST', '/rest/v1/admins', { key: SECRET, body: { user_id: ADM.id }, headers: { Prefer: 'return=minimal' } });
  if (!check('an admin row can be added with the secret key', addAdmin.status < 300, `${addAdmin.status} ${short(addAdmin.text)}`)) return;
  const grant = (await rpc(null, 'vault_grant')).ok;
  check('vault_grant with no session', grant?.version && grant.body_sha256 === sha(grant.body));
  const sub = await rpc(A.token, 'vault_submit', { p_sha256: h, p_credit_name: 'Live A', p_grant_version: grant?.version });
  check('vault_submit', sub.ok?.status === 'pending', short(sub));
  check('vault_queue as the admin lists it', (await rpc(ADM.token, 'vault_queue')).ok?.some((x) => x.id === sub.ok?.id));
  check('vault_review before the copy: not_copied', (await rpc(ADM.token, 'vault_review', { p_id: sub.ok?.id, p_decision: 'approved' })).err === 'not_copied');
  const vup = await http('POST', `/storage/v1/object/vault/${h}.wav`, { token: ADM.token, raw: file, headers: { 'Content-Type': 'audio/wav', 'x-upsert': 'false' } });
  vaultSha = h;
  check('the admin copies the file into the vault bucket', vup.status === 200, `${vup.status} ${short(vup.text)}`);
  const notAdmin = await http('POST', `/storage/v1/object/vault/${sha('x')}.wav`, { token: A.token, raw: file, headers: { 'Content-Type': 'audio/wav' } });
  check('nobody else may', notAdmin.status >= 400, String(notAdmin.status));
  check('vault_review approves', (await rpc(ADM.token, 'vault_review', { p_id: sub.ok?.id, p_decision: 'approved' })).ok?.status === 'approved');
  const d1 = await rpc(A.token, 'delete_sound', { p_sha256: h });
  const rm = await http('DELETE', `/storage/v1/object/sounds/${d1.ok?.path}`, { token: A.token });
  check('I may remove my own object once delete_sound marked it', rm.status === 200 || rm.status === 404, `${rm.status} ${short(rm.text)}`);
  const d2 = await rpc(A.token, 'delete_sound', { p_sha256: h });
  check('delete_sound in two calls: deleting, then deleted', d1.ok?.state === 'deleting' && d2.ok?.state === 'deleted', short([d1, d2]));
  const kept = (await rpc(A.token, 'open_mess', { p_mess: mess })).ok?.sounds?.find((x) => x.key === 'bn:live');
  check('the mess now plays the Vault copy', kept?.src === 'vault', short(kept));
}

let vaultSha = null;
async function cleanup() {
  if (process.env.MM_KEEP === '1' || !SECRET) return;
  if (vaultSha) {   // the Vault copy belongs to the test admin, so it goes first
    await http('DELETE', '/storage/v1/object/vault', { key: SECRET, body: { prefixes: [`${vaultSha}.wav`] } }).catch(() => {});
    await http('DELETE', `/rest/v1/vault_sounds?sha256=eq.${vaultSha}`, { key: SECRET }).catch(() => {});
    await http('DELETE', `/rest/v1/vault_submissions?sha256=eq.${vaultSha}`, { key: SECRET }).catch(() => {});
  }
  for (const u of made) {
    if (!u.id || u.gone) continue;
    try {
      const r = await http('POST', '/rest/v1/rpc/svc_delete_account', { key: SECRET, body: { p_user: u.id, p_amr: null } });
      const paths = r.json?.paths || [];
      if (paths.length) await http('DELETE', '/storage/v1/object/sounds', { key: SECRET, body: { prefixes: paths } });
      await http('DELETE', `/auth/v1/admin/users/${u.id}`, { key: SECRET });
    } catch { /* best effort */ }
  }
}

main().catch((e) => check('the live run finished', false, e.stack || String(e))).finally(async () => {
  await cleanup();
  console.log(`\nTOTAL ${pass + fail} checks: ${pass} PASS, ${fail} FAIL, ${skip} SKIP`);
  if (fail) { console.log('Failed:\n' + failed.map((x) => '  ' + x).join('\n')); process.exitCode = 1; }
});
