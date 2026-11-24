// Shared test tools: make people, call a function as one of them the way Supabase would, count checks.
import pg from 'pg';
import { randomUUID, createHash } from 'node:crypto';

export const uuid = () => randomUUID();
export const sha = (text) => createHash('sha256').update(text).digest('hex');

export class Harness {
  constructor(url) {
    this.url = url;
    this.pool = new pg.Pool({ connectionString: url, max: 40 });
    this.pool.on('error', () => {});   // a connection closed under us is not a test result
    this.pass = 0;
    this.fail = 0;
    this.failed = [];
    this.group = '';
    this.n = 0;
  }

  // ----- counting -----
  section(name) { this.group = name; console.log(`\n# ${name}`); }
  check(name, cond, detail = '') {
    const label = `${this.group}: ${name}`;
    if (cond) { this.pass++; console.log(`PASS ${label}`); }
    else {
      this.fail++; this.failed.push(label);
      console.log(`FAIL ${label}${detail ? '  <- ' + detail : ''}`);
    }
    return !!cond;
  }
  // r is an answer from rpc(): it must be a value, and test(value) must be true
  ok(name, r, test = () => true) {
    let good = false; let why = '';
    if (r.err) why = `got error ${r.err}`;
    else { try { good = !!test(r.ok); if (!good) why = `answer was ${short(r.ok)}`; } catch (e) { why = String(e); } }
    return this.check(name, good, why);
  }
  // r must be the error code
  err(name, r, code) {
    return this.check(`${name} -> ${code}`, r.err === code, r.err ? `got error ${r.err}` : `got ${short(r.ok)}`);
  }

  // ----- the database as its owner (set-up and looking at what happened) -----
  async sql(text, params = []) { return (await this.pool.query(text, params)).rows; }
  async one(text, params = []) { return (await this.sql(text, params))[0]; }
  async val(text, params = []) { const r = await this.one(text, params); return r ? Object.values(r)[0] : undefined; }

  // ----- people -----
  // terms: accept the current terms. amr: how the session was made (Supabase puts this in the token).
  async user(label, { terms = true, method = 'otp' } = {}) {
    const id = uuid();
    const email = `${label}.${++this.n}@test.invalid`;
    await this.sql('insert into auth.users (id, email) values ($1, $2)', [id, email]);
    await this.sql('update public.profiles set display_name = $2, name_set = true where id = $1', [id, label]);
    const u = { id, email, label, method, at: Math.floor(Date.now() / 1000) };
    if (terms) await this.sql(
      `update public.profiles set terms_version = (select value #>> '{}' from public.config where key = 'terms_version'),
         terms_at = now() where id = $1`, [id]);
    return u;
  }
  claims(u) {
    if (!u) return { role: 'anon' };
    return { sub: u.id, role: 'authenticated', email: u.email, aal: 'aal1',
             amr: [{ method: u.method, timestamp: u.at }], session_id: uuid() };
  }

  // ----- calling a function the way PostgREST does: one transaction, the client role, the token's claims -----
  async begin(c, u, role) {
    await c.query('begin');
    await c.query(`set local role ${role || (u ? 'authenticated' : 'anon')}`);
    await c.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify(this.claims(u))]);
  }
  callText(fn, args, first = 1) {
    const keys = Object.keys(args);
    const named = keys.map((k, i) => `${k} => $${i + first}`).join(', ');
    const values = keys.map((k) => {
      const v = args[k];
      return v !== null && typeof v === 'object' ? JSON.stringify(v) : v;
    });
    return { text: `public.${fn}(${named})`, values };
  }
  async rpc(u, fn, args = {}, { role } = {}) {
    const c = await this.pool.connect();
    try {
      await this.begin(c, u, role);
      const { text, values } = this.callText(fn, args);
      const r = await c.query(`select ${text} as r`, values);
      await c.query('commit');
      return { ok: r.rows[0].r };
    } catch (e) {
      await c.query('rollback').catch(() => {});
      return { err: e.code === 'P0001' ? e.message : `sql ${e.code}: ${e.message}`, sqlstate: e.code };
    } finally { c.release(); }
  }
  // any SQL as a client role (for the "tables are closed" and policy checks)
  async as(u, text, params = [], { role, settings = {} } = {}) {
    const c = await this.pool.connect();
    try {
      await this.begin(c, u, role);
      for (const [k, v] of Object.entries(settings)) await c.query(`select set_config($1, $2, true)`, [k, v]);
      const r = await c.query(text, params);
      await c.query('rollback');
      return { ok: r.rows, count: r.rowCount };
    } catch (e) {
      await c.query('rollback').catch(() => {});
      return { err: e.code === 'P0001' ? e.message : `sql ${e.code}: ${e.message}`, sqlstate: e.code };
    } finally { c.release(); }
  }

  // ----- truly concurrent calls (Review A M1) -----
  // Every call gets its own connection and transaction. Each one stops inside its statement, on a lock
  // this function holds; when all of them are waiting the lock is let go and they run at the same instant.
  async race(calls) {
    const key = Math.floor(Math.random() * 2 ** 31);
    const gate = await this.pool.connect();
    const workers = await Promise.all(calls.map(() => this.pool.connect()));
    try {
      await gate.query('select pg_advisory_lock($1)', [key]);
      const running = calls.map(async (call, i) => {
        const c = workers[i];
        try {
          await this.begin(c, call.user, call.role);
          const { text, values } = this.callText(call.fn, call.args || {}, 2);
          const r = await c.query(
            `with gate as materialized (select pg_advisory_xact_lock_shared($1::bigint)) select ${text} as r from gate`,
            [key, ...values]);
          await c.query('commit');
          return { ok: r.rows[0].r };
        } catch (e) {
          await c.query('rollback').catch(() => {});
          return { err: e.code === 'P0001' ? e.message : `sql ${e.code}: ${e.message}`, sqlstate: e.code };
        }
      });
      for (let tries = 0; tries < 200; tries++) {
        const waiting = await gate.query(
          `select count(*)::int as n from pg_locks where locktype = 'advisory' and not granted and objid = $1`, [key]);
        if (waiting.rows[0].n >= calls.length) break;
        await new Promise((r) => setTimeout(r, 10));
      }
      await gate.query('select pg_advisory_unlock($1)', [key]);
      return await Promise.all(running);
    } finally {
      gate.release();
      workers.forEach((w) => w.release());
    }
  }

  // One text that changes when any row in any of our tables, Storage or Realtime changes.
  async fingerprint() {
    const tables = await this.sql(
      `select n.nspname as s, c.relname as t from pg_class c join pg_namespace n on n.oid = c.relnamespace
       where c.relkind = 'r' and n.nspname in ('public', 'mm_private', 'storage', 'realtime') order by 1, 2`);
    const parts = [];
    for (const { s, t } of tables) {
      parts.push(`${s}.${t}=` + await this.val(
        `select md5(coalesce(string_agg(x::text, '|' order by x::text), '')) from ${s}.${t} x`));
    }
    return parts.join(';');
  }

  // ----- short cuts used by many tests -----
  async mess(owner, name = 'Jam', doc = { v: 3, name, bpm: 120 }) {
    const id = uuid();
    const r = await this.rpc(owner, 'create_mess', { p_id: id, p_name: name, p_doc: doc });
    if (r.err) throw new Error(`could not make a mess: ${r.err}`);
    return id;
  }
  // owner invites, guest joins
  async join(owner, mess, guest) {
    const inv = await this.rpc(owner, 'make_invite', { p_mess: mess });
    if (inv.err) throw new Error(`could not invite: ${inv.err}`);
    const r = await this.rpc(guest, 'redeem_invite', { p_token: inv.ok.token });
    if (r.err) throw new Error(`could not join: ${r.err}`);
    return inv.ok;
  }
  // registers a sound and pretends the upload happened (a row in the stand-in Storage table)
  async sound(owner, text, { bytes = 1000, finish = true, upload = true, realBytes } = {}) {
    const h = sha(text);
    const reg = await this.rpc(owner, 'register_sound', {
      p_sha256: h, p_bytes: bytes, p_seconds: 2.5, p_rate: 44100, p_kind: 'imp', p_local_key: 'bn:' + text,
      p_title: text.slice(0, 40), p_bpm: 0, p_beats: 0, p_mkey: '' });
    if (reg.err) throw new Error(`could not register: ${reg.err}`);
    if (upload) await this.put('sounds', reg.ok.path, realBytes ?? bytes, owner.id);
    if (upload && finish) {
      const fin = await this.rpc(owner, 'finish_upload', { p_sha256: h });
      if (fin.err) throw new Error(`could not finish: ${fin.err}`);
    }
    return { sha256: h, path: reg.ok.path, bytes };
  }
  async put(bucket, name, size, owner = null) {
    await this.sql(
      `insert into storage.objects (bucket_id, name, owner, metadata) values ($1, $2, $3, $4)
       on conflict (bucket_id, name) do update set metadata = excluded.metadata`,
      [bucket, name, owner, JSON.stringify({ size, mimetype: 'audio/wav' })]);
  }
  async broadcasts(mess) {
    return this.sql(`select event, payload, topic from realtime.messages where topic like $1 order by inserted_at, id`,
      [`mess:${mess}:%`]);
  }
  async end() { await this.pool.end(); }
}

export function short(v) {
  const s = JSON.stringify(v);
  return s && s.length > 200 ? s.slice(0, 200) + '...' : s;
}
