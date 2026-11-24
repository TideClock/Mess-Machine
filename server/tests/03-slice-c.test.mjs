// Slice C: live feel (ACCOUNTS-API.md 1.6 and section 2, Realtime). The policies on realtime.messages,
// checked through the stand-in the way Supabase Realtime checks them: with the setting realtime.topic
// holding the topic being joined, a select (to listen) or an insert (to send) must pass row level security.
import { uuid } from './lib.mjs';

export default async function (T) {
  const O = await T.user('Rita');
  const E = await T.user('Ravi');
  const S = await T.user('Rex');
  const m = await T.mess(O, 'Live');
  await T.join(O, m, E);
  const topic = async () => (await T.rpc(O, 'open_mess', { p_mess: m })).ok.topic;
  const t0 = await topic();
  // a broadcast row on the topic, as realtime.send would leave it
  await T.sql(`insert into realtime.messages (topic, extension, event, payload, private) values ($1, 'broadcast', 'ops', '{}', true)`, [t0]);
  await T.sql(`insert into realtime.messages (topic, extension, event, payload, private) values ($1, 'presence', 'presence', '{}', true)`, [t0]);
  const hears = async (u, t, opts = {}) => {
    const r = await T.as(u, `select count(*)::int as n from realtime.messages where extension in ('broadcast', 'presence')`, [], { settings: { 'realtime.topic': t }, ...opts });
    return r.err ? -1 : r.ok[0].n;
  };
  const sends = async (u, t, ext) => {
    const r = await T.as(u, `insert into realtime.messages (topic, extension, event, payload, private) values ($1, $2, 'x', '{}', true)`, [t, ext], { settings: { 'realtime.topic': t } });
    return r.err ? r.sqlstate : 'ok';
  };

  T.section('C who may listen');
  T.check('the owner hears the mess topic', await hears(O, t0) > 0);
  T.check('an editor hears it', await hears(E, t0) > 0);
  T.check('a stranger hears nothing', await hears(S, t0) === 0);
  T.check('anon hears nothing', await hears(null, t0) === 0);
  T.check('the plain topic mess:<id> (no key) is never heard (Review A S9)', await hears(O, `mess:${m}`) === 0);
  T.check('a made-up key is never heard', await hears(O, `mess:${m}:0123456789abcdef`) === 0);
  T.check('a topic that is not ours is never heard', await hears(O, 'realtime:public') === 0 && await hears(O, null) === 0);
  T.check('the topic of another mess is not heard by this mess\'s editor', await hears(E, (await T.rpc(S, 'open_mess', { p_mess: await T.mess(S, 'Other') })).ok.topic) === 0);

  T.section('C who may send');
  T.check('no member may put a broadcast on the channel', await sends(O, t0, 'broadcast') === '42501' && await sends(E, t0, 'broadcast') === '42501');
  T.check('a member may send presence', await sends(E, t0, 'presence') === 'ok');
  T.check('a stranger may not send presence', await sends(S, t0, 'presence') === '42501');
  T.check('anon may not send anything', await sends(null, t0, 'presence') === '42501' && await sends(null, t0, 'broadcast') === '42501');
  T.check('no update or delete of realtime messages by a client gets through',
    (await T.as(E, `update realtime.messages set payload = '{}' where topic = $1`, [t0], { settings: { 'realtime.topic': t0 } })).count === 0
    && (await T.as(E, `delete from realtime.messages where topic = $1`, [t0], { settings: { 'realtime.topic': t0 } })).count === 0);
  const pols = (await T.sql(`select polname, polcmd from pg_policy where polrelid = 'realtime.messages'::regclass order by 1`)).map((p) => p.polname + ':' + p.polcmd).join(' ');
  T.check('realtime.messages has exactly our two policies: select to listen, insert for presence', pols === 'mm_rt_listen:r mm_rt_presence:a', pols);

  T.section('C the key turns');
  const Z = await T.user('Zed');
  await T.join(O, m, Z);
  T.check('a new member hears the current topic', await hears(Z, t0) > 0);
  await T.rpc(O, 'remove_member', { p_mess: m, p_user: Z.id });
  const t1 = await topic();
  T.check('after a removal the topic changed', t1 !== t0);
  T.check('the removed person hears neither the old topic nor the new one', await hears(Z, t0) === 0 && await hears(Z, t1) === 0);
  T.check('the others no longer hear the old topic either, only the new one', await hears(E, t0) === 0 && await hears(E, t1) >= 0
    && (await T.as(E, `select mm_policy.may_listen($1) as ok`, [t1])).ok[0].ok === true);
  T.check('ops_since gives the others the new topic, so they join again', (await T.rpc(E, 'ops_since', { p_mess: m, p_from: 0 })).ok.topic === t1);
  const ob = await T.user('Obi'); const obm = await T.mess(ob, 'Obi room');
  await T.join(ob, obm, O);
  const obT = (await T.rpc(ob, 'open_mess', { p_mess: obm })).ok.topic;
  await T.rpc(ob, 'block_user', { p_user: O.id });
  T.check('block_user turns the key of the blocker\'s messes', (await T.rpc(ob, 'open_mess', { p_mess: obm })).ok.topic !== obT);
  const listen = async (u, t) => (await T.as(u, `select mm_policy.may_listen($1) as ok`, [t])).ok?.[0]?.ok;
  T.check('the blocked person may not listen to it', await listen(O, (await T.rpc(ob, 'open_mess', { p_mess: obm })).ok.topic) === false);

  T.section('C taken down, banned, password sessions');
  const t2 = await topic();
  await T.sql('update public.messes set blocked = true where id = $1', [m]);
  T.check('a taken-down mess: its editor may not listen', await listen(E, t2) === false);
  T.check('its owner still may', await listen(O, t2) === true);
  await T.sql('update public.messes set blocked = false where id = $1', [m]);
  await T.sql('update public.profiles set banned = true where id = $1', [E.id]);
  T.check('a banned member may not listen', await listen(E, t2) === false);
  await T.sql('update public.profiles set banned = false where id = $1', [E.id]);
  const P = await T.user('Pat', { method: 'password' });
  await T.join(O, m, P).catch(() => {});   // a password session cannot even join
  await T.sql(`insert into public.mess_members (mess_id, user_id, role) values ($1, $2, 'editor') on conflict do nothing`, [m, P.id]);
  T.check('a member signed in with a password (not the review account) may not listen', await listen(P, t2) === false);
  T.check('anon may not run the policy helper at all', (await T.as(null, `select mm_policy.may_listen($1)`, [t2])).sqlstate === '42501');

  T.section('C what the functions broadcast');
  await T.sql('delete from realtime.messages');
  const g = await T.user('Gia');
  await T.join(O, m, g);
  await T.rpc(g, 'apply_ops', { p_mess: m, p_batch: uuid(), p_ops: [{ t: 'song.set', b: 0, v: 1 }], p_v: 1 });
  await T.rpc(O, 'remove_member', { p_mess: m, p_user: g.id });
  const evs = await T.broadcasts(m);
  T.check('in order: meta members (a join), ops, meta members gone', evs.map((e) => e.event + (e.payload.kind ? ':' + e.payload.kind : '') + (e.payload.gone ? '+gone' : '')).join(' ') === 'meta:members ops meta:members+gone',
    evs.map((e) => e.event + ':' + JSON.stringify(e.payload)).join(' '));
  T.check('every broadcast is private and on mess:<id>:<key>', evs.every((e) => /^mess:[0-9a-f-]{36}:[0-9a-f]{16}$/.test(e.topic)));
  const ops = evs.find((e) => e.event === 'ops').payload;
  T.check('an ops broadcast carries the Batch: rev, user, batch, v, ops, at', ops.user === g.id && ops.v === 1 && ops.ops[0].t === 'song.set' && typeof ops.at === 'string');
}
