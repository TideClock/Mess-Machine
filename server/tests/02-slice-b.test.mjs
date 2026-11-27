// Slice B: two people in one mess (ACCOUNTS-API.md 1.5). Every call, each documented error, the denial
// cases, and a two-connection race for every limit (Review A M1).
import { uuid } from './lib.mjs';

const cfg = (T, key, value) => T.sql('update public.config set value = $2::jsonb where key = $1', [key, JSON.stringify(value)]);
const op = (n = 0) => ({ t: 'step.set', l: 0, p: n % 64, k: 'kit:kick', s: n % 16, on: true });

export default async function (T) {
  const O = await T.user('Owen');
  const E = await T.user('Edie');
  const E2 = await T.user('Eli');
  const S = await T.user('Sam');
  const m = await T.mess(O, 'Two of us');
  await T.join(O, m, E);
  await T.join(O, m, E2);
  // ages every stored batch and live seat of a mess, so the per-second rate and the 90 s seats start over
  const cool = async (mess, seats = false) => {
    await T.sql(`update public.mess_ops set at = at - interval '2 seconds' where mess_id = $1`, [mess]);
    if (seats) await T.sql(`update public.mess_live set seen_at = seen_at - interval '91 seconds' where mess_id = $1`, [mess]);
  };
  const ap = (u, mess, ops, over = {}) => T.rpc(u, 'apply_ops', { p_mess: mess, p_batch: uuid(), p_ops: ops, p_v: 1, ...over });

  T.section('B apply_ops');
  const a1 = await ap(E, m, [op(1)]);
  T.ok('an editor\'s batch is stored at rev 1, with the time as ISO UTC', a1, (v) => v.rev === 1 && /Z$/.test(v.at) && Object.keys(v).sort().join() === 'at,rev');
  const row = await T.one('select * from public.mess_ops where mess_id = $1 and rev = 1', [m]);
  T.check('the row holds the ops, the person, v 1 and its size', row.user_id === E.id && row.ops[0].t === 'step.set' && row.v === 1 && row.bytes > 0);
  const mrow = await T.one('select rev, doc_rev, ops_v, ops_bytes, op_mode from public.messes where id = $1', [m]);
  T.check('the mess: rev 1, doc_rev 0, ops_bytes grew, it is now in op mode', mrow.rev === '1' && mrow.doc_rev === '0' && Number(mrow.ops_bytes) === row.bytes && mrow.op_mode === true, JSON.stringify(mrow));
  const casts = await T.broadcasts(m);
  T.check('the Batch was broadcast as "ops" with exactly the Batch fields', casts.length >= 1 && casts.at(-1).event === 'ops' &&
    Object.keys(casts.at(-1).payload).sort().join() === 'at,batch,ops,rev,user,v' && casts.at(-1).payload.rev === 1 && casts.at(-1).payload.user === E.id);
  // 1. shape
  T.err('no batch id', await ap(E, m, [op()], { p_batch: null }), 'bad_input');
  T.err('ops that are not an array', await ap(E, m, { t: 'step.set' }), 'bad_input');
  T.err('an empty array', await ap(E, m, []), 'bad_input');
  T.err('501 ops', await ap(E, m, Array.from({ length: 501 }, (_, i) => op(i))), 'bad_input');
  T.err('v 0', await ap(E, m, [op()], { p_v: 0 }), 'bad_input');
  T.err('v 2 while config.ops_v_max is 1', await ap(E, m, [op()], { p_v: 2 }), 'bad_input');
  T.err('v 32767, the call that stopped snapshots for good (Review A M2)', await ap(E, m, [op()], { p_v: 32767 }), 'bad_input');
  T.check('ops_v did not move', await T.val('select ops_v from public.messes where id = $1', [m]) === 1);
  // 2. size
  const sizeOf = async (pad) => T.val(`select octet_length($1::jsonb::text)`, [JSON.stringify([{ t: 'x', pad }])]);
  const base = await sizeOf('');
  await cool(m);
  T.ok('exactly 262144 bytes is taken', await ap(E, m, [{ t: 'x', pad: 'a'.repeat(262144 - base) }]));
  T.err('262145 bytes', await ap(E, m, [{ t: 'x', pad: 'a'.repeat(262145 - base) }]), 'too_big');
  T.ok('500 ops is taken', await ap(E, m, Array.from({ length: 500 }, (_, i) => op(i))));
  const bigCast = (await T.broadcasts(m)).find((b) => b.payload.rev === 2);
  T.check('a batch over 32 KB is broadcast with ops null (the apps fetch it with ops_since)', bigCast && bigCast.payload.ops === null && bigCast.payload.batch);
  // 3. who
  T.err('a stranger', await ap(S, m, [op()]), 'not_found');
  T.err('a mess that does not exist', await ap(E, uuid(), [op()]), 'not_found');
  await T.sql('update public.messes set blocked = true where id = $1', [m]);
  T.err('an editor on a taken-down mess reads it as not there', await ap(E, m, [op()]), 'not_found');
  T.err('its owner is told it is blocked', await ap(O, m, [op()]), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [m]);
  // 4. the same batch twice
  await cool(m);
  const bid = uuid();
  const first = await ap(E, m, [op(5)], { p_batch: bid });
  const again = await ap(E, m, [op(6)], { p_batch: bid });
  T.check('the same batch id again gets its first answer, and nothing new is stored', first.ok && again.ok && first.ok.rev === again.ok.rev && first.ok.at === again.ok.at
    && await T.val('select count(*)::int from public.mess_ops where mess_id = $1 and batch_id = $2', [m, bid]) === 1);
  T.err('someone else sending my batch id', await ap(E2, m, [op()], { p_batch: bid }), 'bad_input');
  await cool(m);
  const shared = uuid();
  const six = await T.race([1, 2, 3, 4, 5, 6].map(() => ({ user: E, fn: 'apply_ops', args: { p_mess: m, p_batch: shared, p_ops: [op(9)], p_v: 1 } })));
  T.check('RACE one batch id from six connections: six identical answers, one row, no raw duplicate error',
    six.every((r) => r.ok && JSON.stringify(r.ok) === JSON.stringify(six[0].ok)) && await T.val('select count(*)::int from public.mess_ops where mess_id = $1 and batch_id = $2', [m, shared]) === 1,
    JSON.stringify(six.map((r) => r.err || r.ok.rev)));
  // 5. rate
  await cool(m);
  const four = [];
  for (let i = 0; i < 5; i++) four.push(await ap(E, m, [op(i)]));
  T.check('four batches in a second pass', four.slice(0, 4).every((r) => r.ok));
  T.err('the fifth', four[4], 'rate_limited');
  T.ok('someone else is not held up by my rate', await ap(E2, m, [op()]));
  await cool(m);
  const burst = await T.race(Array.from({ length: 12 }, (_, i) => ({ user: E, fn: 'apply_ops', args: { p_mess: m, p_batch: uuid(), p_ops: [op(i)], p_v: 1 } })));
  T.check('RACE twelve batches from one editor at the same instant: exactly 4 stored, 8 rate_limited',
    burst.filter((r) => r.ok).length === 4 && burst.filter((r) => r.err === 'rate_limited').length === 8, JSON.stringify(burst.map((r) => r.err || 'ok')));
  // order under load
  await cool(m);
  const people = [O, E, E2];
  const load = await T.race(people.flatMap((u) => [0, 1, 2, 3].map((i) => ({ user: u, fn: 'apply_ops', args: { p_mess: m, p_batch: uuid(), p_ops: [op(i)], p_v: 1 } }))));
  const revs = (await T.sql('select rev, at from public.mess_ops where mess_id = $1 order by rev', [m]));
  T.check('RACE twelve batches from three people at once: all stored', load.every((r) => r.ok), JSON.stringify(load.map((r) => r.err || 'ok')));
  T.check('revs run 1, 2, 3 ... with no gap and no repeat', revs.every((r, i) => Number(r.rev) === i + 1));
  T.check('and no batch has a time earlier than the one before it', revs.every((r, i) => i === 0 || r.at >= revs[i - 1].at));
  // 6. a day of edits
  await cool(m);
  const today = await T.val(`select op_bytes from mm_private.usage where user_id = $1`, [E2.id]);
  const one = await T.val(`select octet_length($1::jsonb::text)`, [JSON.stringify([op(1)])]);
  await cfg(T, 'ops_day_bytes', Number(today) + one * 2);
  T.ok('within the day\'s bytes', await ap(E2, m, [op(1)]));
  T.ok('to the byte', await ap(E2, m, [op(1)]));
  T.err('one more batch', await ap(E2, m, [op(1)]), 'day_limit');
  T.check('a refused batch does not count against the day', Number(await T.val(`select op_bytes from mm_private.usage where user_id = $1`, [E2.id])) === Number(today) + one * 2);
  const m2 = await T.mess(O, 'Second room');
  await T.join(O, m2, E2);
  await cfg(T, 'ops_day_bytes', Number(today) + one * 3);
  await cool(m);
  const day = await T.race([m, m2, m, m2].map((x) => ({ user: E2, fn: 'apply_ops', args: { p_mess: x, p_batch: uuid(), p_ops: [op(1)], p_v: 1 } })));
  T.check('RACE the day limit across two messes: room for one batch, four at once, exactly one stored',
    day.filter((r) => r.ok).length === 1 && day.filter((r) => r.err === 'day_limit').length === 3, JSON.stringify(day.map((r) => r.err || 'ok')));
  await cfg(T, 'ops_day_bytes', 20971520);
  // 7. live seats
  const L = await T.mess(O, 'Busy');
  const guests = [];
  for (let i = 0; i < 5; i++) { const g = await T.user('Guest' + i); await T.join(O, L, g); guests.push(g); }
  for (const g of guests.slice(0, 3)) await ap(g, L, [op()]);
  T.ok('the owner takes the fourth seat', await ap(O, L, [op()]));
  T.err('a fifth editor', await ap(guests[3], L, [op()]), 'mess_full_live');
  T.ok('one of the four keeps sending', await ap(guests[0], L, [op()]));
  await T.sql(`update public.mess_live set seen_at = seen_at - interval '91 seconds' where mess_id = $1 and user_id = $2`, [L, O.id]);
  T.ok('a seat quiet for 90 seconds is free again', await ap(guests[3], L, [op()]));
  await cool(L, true);
  const seats = await T.race([O, ...guests].map((u) => ({ user: u, fn: 'apply_ops', args: { p_mess: L, p_batch: uuid(), p_ops: [op()], p_v: 1 } })));
  T.check('RACE six editors\' first batch at the same instant: exactly 4 seats, 2 mess_full_live',
    seats.filter((r) => r.ok).length === 4 && seats.filter((r) => r.err === 'mess_full_live').length === 2
    && await T.val(`select count(*)::int from public.mess_live where mess_id = $1 and seen_at > now() - interval '90 seconds'`, [L]) === 4, JSON.stringify(seats.map((r) => r.err || 'ok')));
  // 8. renames
  await cool(m);
  const name = (v) => ({ t: 'mess.set', f: 'name', v });
  T.err('an editor renaming', await ap(E, m, [name('Mine')]), 'forbidden');
  T.err('an editor hiding a rename behind a null (Review A M3)', await ap(E, m, [name('x'), name(null)]), 'bad_input');
  T.err('a rename that is not a string, even from the owner', await ap(O, m, [name(5)]), 'bad_input');
  T.err('the owner renaming to a blocked word', await ap(O, m, [name('Hitler jams')]), 'bad_name');
  T.err('the owner renaming to nothing', await ap(O, m, [name('   ')]), 'bad_name');
  T.err('a good name first, a bad one last: the last one counts', await ap(O, m, [name('Fine'), name('nazi')]), 'bad_name');
  T.ok('the owner renames by an op', await ap(O, m, [op(), name('  Two of us, renamed ')]));
  T.check('messes.name follows, trimmed', await T.val('select name from public.messes where id = $1', [m]) === 'Two of us, renamed');
  T.ok('other ops named mess.set are not renames', await ap(E, m, [{ t: 'mess.set', f: 'bpm', v: 120 }]));
  // 9. snapshot first
  await cool(m);
  await T.sql('update public.messes set ops_bytes = 2097153 where id = $1', [m]);
  T.err('over 2 MB of ops since the snapshot', await ap(E, m, [op()]), 'snapshot_first');
  await T.sql(`update public.messes set ops_bytes = (select coalesce(sum(bytes), 0) from public.mess_ops where mess_id = $1) where id = $1`, [m]);
  const R = await T.mess(O, 'Long log');
  await T.sql('update public.messes set rev = 2000 where id = $1', [R]);
  T.err('2000 revs since the snapshot', await ap(O, R, [op()]), 'snapshot_first');
  // 10. a failed broadcast never undoes a batch
  await cool(m);
  const before = Number(await T.val('select rev from public.messes where id = $1', [m]));
  const quiet = await T.rpc(E, 'apply_ops', { p_mess: m, p_batch: uuid(), p_ops: [op()], p_v: 1 }, { settings: { 'mm_stub.send_fails': 'on' } });
  T.ok('with realtime.send failing the batch is still stored', quiet, (v) => v.rev === before + 1);
  T.check('and nothing was broadcast for it', !(await T.broadcasts(m)).some((b) => b.payload.rev === before + 1));

  T.section('B ops_since');
  const since = await T.rpc(E, 'ops_since', { p_mess: m, p_from: 0 });
  const total = Number(await T.val('select rev from public.messes where id = $1', [m]));
  T.ok('ops_since has exactly its fields: rev, doc_rev, members, topic, ops', since, (v) => Object.keys(v).sort().join() === 'doc_rev,members,ops,rev,topic' && v.rev === total && v.members === 3);
  T.ok('every batch, oldest first, each with the Batch fields', since, (v) => v.ops.length === total && v.ops.every((b, i) => b.rev === i + 1) && Object.keys(v.ops[0]).sort().join() === 'at,batch,ops,rev,user,v');
  T.ok('from a later rev, only what came after', await T.rpc(E, 'ops_since', { p_mess: m, p_from: total - 2 }), (v) => v.ops.length === 2 && v.ops[0].rev === total - 1);
  T.ok('from the newest rev, nothing', await T.rpc(E, 'ops_since', { p_mess: m, p_from: total }), (v) => v.ops.length === 0);
  T.ok('the topic matches open_mess', since, async () => true) && T.check('the topic is the one open_mess gives', since.ok.topic === (await T.rpc(E, 'open_mess', { p_mess: m })).ok.topic);
  T.err('a stranger', await T.rpc(S, 'ops_since', { p_mess: m, p_from: 0 }), 'not_found');
  const pg = await T.mess(O, 'Paging');
  await T.sql(`insert into public.mess_ops (mess_id, rev, user_id, batch_id, v, ops, bytes)
     select $1, g, $2, gen_random_uuid(), 1, '[{"t":"x"}]', 11 from generate_series(1, 600) g`, [pg, O.id]);
  await T.sql('update public.messes set rev = 600 where id = $1', [pg]);
  T.ok('at most 500 batches at a time', await T.rpc(O, 'ops_since', { p_mess: pg, p_from: 0 }), (v) => v.ops.length === 500 && v.ops[499].rev === 500 && v.rev === 600);
  await T.sql(`update public.mess_ops set ops = jsonb_build_array(jsonb_build_object('pad', repeat('z', 300000))), bytes = 300015 where mess_id = $1 and rev <= 10`, [pg]);
  T.ok('and at most 1 MB at a time (4 batches of 300 KB)', await T.rpc(O, 'ops_since', { p_mess: pg, p_from: 0 }), (v) => v.ops.length === 4);
  await T.sql('delete from public.mess_ops where mess_id = $1 and rev <= 100', [pg]);
  T.err('asking from a rev whose next batch was trimmed away', await T.rpc(O, 'ops_since', { p_mess: pg, p_from: 50 }), 'gap');
  T.ok('asking from the first rev still held is fine', await T.rpc(O, 'ops_since', { p_mess: pg, p_from: 100 }), (v) => v.ops[0].rev === 101);
  await T.sql(`update public.mess_live set seen_at = now() - interval '60 seconds' where mess_id = $1 and user_id = $2`, [m, E.id]);
  await T.rpc(E, 'ops_since', { p_mess: m, p_from: total });
  T.check('a poll keeps a quiet editor\'s seat (Review A S8)', await T.val(`select seen_at > now() - interval '5 seconds' from public.mess_live where mess_id = $1 and user_id = $2`, [m, E.id]) === true);
  await T.rpc(S, 'ops_since', { p_mess: m, p_from: 0 });
  T.check('a poll gives no seat to someone who holds none', await T.val(`select count(*)::int from public.mess_live where mess_id = $1 and user_id = $2`, [m, S.id]) === 0);

  T.section('B save_snapshot');
  const sn = await T.mess(O, 'Snaps');
  await T.join(O, sn, E);
  await T.sound(O, 'snap kick').then((s) => T.rpc(O, 'attach_sound', { p_mess: sn, p_key: 'bn:snap', p_owner: O.id, p_sha256: s.sha256 }));
  for (let i = 0; i < 3; i++) await ap(E, sn, [op(i)]);
  await cool(sn);
  const snapDoc = { v: 3, name: 'A name inside the doc', bpm: 99 };
  const s1 = await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 2, p_v: 1 });
  T.ok('an editor saves a snapshot at rev 2 (the first makes a History entry)', s1, (v) => v.doc_rev === 2 && v.version_id && Object.keys(v).sort().join() === 'doc_rev,version_id');
  const after = (await T.rpc(E, 'open_mess', { p_mess: sn })).ok;
  T.check('open_mess: the doc, doc_rev 2, and only the batch after it', after.doc_rev === 2 && after.rev === 3 && after.doc.bpm === 99 && after.ops.length === 1 && after.ops[0].rev === 3);
  T.check('a snapshot never writes the name (Review A M3)', after.name === 'Snaps');
  T.check('a snapshot never drops a sound row, even one its doc does not mention (Review B S8)', after.sounds.length === 1);
  T.check('ops_bytes now counts only what came after', Number(await T.val('select ops_bytes from public.messes where id = $1', [sn])) === Number(await T.val('select bytes from public.mess_ops where mess_id = $1 and rev = 3', [sn])));
  T.ok('a second one soon after, at a later rev, makes no History entry', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1 }), (v) => v.version_id === null);
  T.err('the same rev again with no label', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1 }), 'stale');
  T.err('an older rev', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 1, p_v: 1, p_label: 'Old' }), 'stale');
  T.ok('the same rev with a label: a named History entry', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1, p_label: 'Chorus' }), (v) => v.doc_rev === 3 && v.version_id);
  T.err('a rev the server has not reached', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 4, p_v: 1 }), 'ahead');
  T.err('v 0', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 0, p_label: 'x' }), 'bad_input');
  T.err('v above ops_v_max', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 2, p_label: 'x' }), 'bad_input');
  T.err('a doc that is not an object', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: [1], p_doc_rev: 3, p_v: 1, p_label: 'x' }), 'bad_input');
  T.err('a 41 character label', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1, p_label: 'l'.repeat(41) }), 'bad_input');
  T.err('a doc over 1 MB', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: { pad: 'x'.repeat(1048576) }, p_doc_rev: 3, p_v: 1, p_label: 'x' }), 'too_big');
  await cfg(T, 'docs_limit', 50);
  T.err('over the owner\'s docs limit (charged to the owner, whoever saves)', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1, p_label: 'x' }), 'docs_full');
  await cfg(T, 'docs_limit', 31457280);
  T.err('a stranger', await T.rpc(S, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1, p_label: 'x' }), 'not_found');
  await T.sql('update public.messes set blocked = true where id = $1', [sn]);
  T.err('on a taken-down mess (its owner)', await T.rpc(O, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 3, p_v: 1, p_label: 'x' }), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [sn]);
  await cfg(T, 'ops_v_max', 2);
  await ap(E, sn, [op()], { p_v: 2 });
  T.err('a phone with v 1 after a v 2 batch arrived', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 4, p_v: 1 }), 'client_too_old');
  T.ok('a v 2 phone may', await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 4, p_v: 2 }));
  await T.sql(`delete from mm_private.hits where what = 'snapshot'`);
  let lab = [];
  for (let i = 0; i < 7; i++) lab.push(await T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 4, p_v: 2, p_label: 'L' + i }));
  T.check('six labelled snapshots a minute pass', lab.slice(0, 6).every((r) => r.ok), JSON.stringify(lab.map((r) => r.err || 'ok')));
  T.err('the seventh', lab[6], 'rate_limited');
  T.ok('unlabelled snapshots are not held by that limit', await ap(E, sn, [op()], { p_v: 2 }).then(() => T.rpc(E, 'save_snapshot', { p_mess: sn, p_doc: snapDoc, p_doc_rev: 5, p_v: 2 })));
  await T.sql(`delete from mm_private.hits where what = 'snapshot'`);
  const labRace = await T.race(Array.from({ length: 10 }, (_, i) => ({ user: E, fn: 'save_snapshot', args: { p_mess: sn, p_doc: snapDoc, p_doc_rev: 5, p_v: 2, p_label: 'R' + i } })));
  T.check('RACE ten labelled snapshots at once: exactly six pass', labRace.filter((r) => r.ok).length === 6 && labRace.filter((r) => r.err === 'rate_limited').length === 4, JSON.stringify(labRace.map((r) => r.err || 'ok')));
  await cfg(T, 'ops_v_max', 1);
  const vcount = await T.one(`select count(*) filter (where label = '')::int as u, count(*) filter (where label <> '')::int as l from public.mess_versions where mess_id = $1`, [sn]);
  T.check('History keeps at most 20 named entries', vcount.l <= 20, JSON.stringify(vcount));
  // trimming
  const tr = await T.mess(O, 'Trim');
  await T.sql(`insert into public.mess_ops (mess_id, rev, user_id, batch_id, v, ops, bytes)
     select $1, g, $2, gen_random_uuid(), 1, '[{"t":"x"}]', 11 from generate_series(1, 1200) g`, [tr, O.id]);
  await T.sql('update public.messes set rev = 1200, ops_bytes = 13200 where id = $1', [tr]);
  await T.rpc(O, 'save_snapshot', { p_mess: tr, p_doc: snapDoc, p_doc_rev: 1100, p_v: 1 });
  T.check('a snapshot deletes ops below doc_rev - 500 at once', await T.val('select min(rev)::int from public.mess_ops where mess_id = $1', [tr]) === 600);
  T.err('a phone that last saw rev 10 is now told gap', await T.rpc(O, 'ops_since', { p_mess: tr, p_from: 10 }), 'gap');
  for (let i = 0; i < 35; i++) await T.sql(`insert into public.mess_versions (mess_id, rev, doc, doc_bytes, made_by, label, at) values ($1, 1, '{}', 2, $2, '', now() - interval '1 hour' * $3)`, [tr, O.id, i + 1]);
  for (let i = 0; i < 25; i++) await T.sql(`insert into public.mess_versions (mess_id, rev, doc, doc_bytes, made_by, label, at) values ($1, 1, '{}', 2, $2, 'n', now() - interval '1 hour' * $3)`, [tr, O.id, i + 1]);
  await T.rpc(O, 'save_snapshot', { p_mess: tr, p_doc: snapDoc, p_doc_rev: 1100, p_v: 1, p_label: 'Keep' });
  const kept = await T.one(`select count(*) filter (where label = '')::int as u, count(*) filter (where label <> '')::int as l from public.mess_versions where mess_id = $1`, [tr]);
  T.check('History is trimmed to the newest 30 plain and 20 named', kept.u === 30 && kept.l === 20, JSON.stringify(kept));
  T.check('the one just made is kept', await T.val(`select count(*)::int from public.mess_versions where mess_id = $1 and label = 'Keep'`, [tr]) === 1);
  const fat = { pad: 'f'.repeat(400000) };
  for (let i = 0; i < 6; i++) await T.rpc(O, 'save_snapshot', { p_mess: tr, p_doc: fat, p_doc_rev: 1100, p_v: 1, p_label: 'Fat' + i });
  T.check('History never holds more than 2 MB for a mess', Number(await T.val('select sum(doc_bytes) from public.mess_versions where mess_id = $1', [tr])) <= 2097152);

  T.section('B members and invites');
  const mem = await T.rpc(E, 'members', { p_mess: m });
  T.ok('members: owner first, each with exactly id, name, colour, role', mem, (v) => v.length === 3 && v[0].id === O.id && v[0].role === 'owner' && v.slice(1).every((x) => x.role === 'editor')
    && Object.keys(v[0]).sort().join() === 'colour,id,name,role' && v[0].name === 'Owen' && v[0].colour === parseInt(O.id.slice(-2), 16) % 8);
  T.err('members as a stranger', await T.rpc(S, 'members', { p_mess: m }), 'not_found');
  const im = await T.mess(O, 'Invites');
  const inv = await T.rpc(O, 'make_invite', { p_mess: im });
  T.ok('make_invite: an id, a 32 character base64url token, an expiry 7 days out', inv, (v) => /^[A-Za-z0-9_-]{32}$/.test(v.token) && v.id && Object.keys(v).sort().join() === 'expires_at,id,token'
    && Math.abs(new Date(v.expires_at) - Date.now() - 7 * 86400000) < 60000);
  const stored = await T.one('select token_hash, uses_left from public.invites where id = $1', [inv.ok.id]);
  T.check('only the sha256 of the token is kept, single use', stored.token_hash === (await import('./lib.mjs')).sha(inv.ok.token) && stored.uses_left === 1
    && !(await T.val(`select count(*)::int from public.invites where token_hash = $1`, [inv.ok.token])));
  T.ok('30 days is allowed', await T.rpc(O, 'make_invite', { p_mess: im, p_days: 30 }));
  T.err('0 days', await T.rpc(O, 'make_invite', { p_mess: im, p_days: 0 }), 'bad_input');
  T.err('31 days', await T.rpc(O, 'make_invite', { p_mess: im, p_days: 31 }), 'bad_input');
  await T.join(O, im, E);
  T.err('an editor making an invite', await T.rpc(E, 'make_invite', { p_mess: im }), 'forbidden');
  T.err('a stranger making an invite', await T.rpc(S, 'make_invite', { p_mess: im }), 'not_found');
  await T.sql('update public.messes set blocked = true where id = $1', [im]);
  T.err('the owner of a taken-down mess', await T.rpc(O, 'make_invite', { p_mess: im }), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [im]);
  const live = await T.val(`select count(*)::int from public.invites where mess_id = $1 and uses_left > 0 and not revoked`, [im]);
  for (let i = live; i < 19; i++) await T.rpc(O, 'make_invite', { p_mess: im });
  const invRace = await T.race([1, 2, 3].map(() => ({ user: O, fn: 'make_invite', args: { p_mess: im } })));
  T.check('RACE 19 live invites, three more at once: exactly one gets the twentieth place', invRace.filter((r) => r.ok).length === 1 && invRace.filter((r) => r.err === 'too_many_invites').length === 2, JSON.stringify(invRace.map((r) => r.err || 'ok')));
  T.err('a 21st live invite', await T.rpc(O, 'make_invite', { p_mess: im }), 'too_many_invites');
  const li = await T.rpc(O, 'list_invites', { p_mess: im });
  T.ok('list_invites: every invite with exactly id, expires_at, uses_left, revoked', li, (v) => v.length === 21 && Object.keys(v[0]).sort().join() === 'expires_at,id,revoked,uses_left' && v.filter((x) => x.uses_left === 0).length === 1);
  T.err('list_invites as an editor', await T.rpc(E, 'list_invites', { p_mess: im }), 'forbidden');
  T.err('list_invites as a stranger', await T.rpc(S, 'list_invites', { p_mess: im }), 'not_found');
  const victim = li.ok.find((x) => x.uses_left === 1).id;
  T.err('revoke_invite as an editor', await T.rpc(E, 'revoke_invite', { p_invite: victim }), 'not_found');
  T.err('revoke_invite as a stranger', await T.rpc(S, 'revoke_invite', { p_invite: victim }), 'not_found');
  T.err('revoke_invite of nothing', await T.rpc(O, 'revoke_invite', { p_invite: uuid() }), 'not_found');
  T.ok('revoke_invite by the owner', await T.rpc(O, 'revoke_invite', { p_invite: victim }), (v) => v.ok === true);
  T.ok('now there is room for one more', await T.rpc(O, 'make_invite', { p_mess: im }));
  await T.sql('delete from public.invites where mess_id = $1', [im]);

  T.section('B invite_info and redeem_invite');
  const J = await T.mess(O, 'Join me');
  const t1 = (await T.rpc(O, 'make_invite', { p_mess: J })).ok.token;
  T.ok('invite_info works signed out: the mess name and the owner name, nothing else', await T.rpc(null, 'invite_info', { p_token: t1 }), (v) => v.mess_name === 'Join me' && v.owner_name === 'Owen' && Object.keys(v).length === 2);
  T.ok('and signed in', await T.rpc(S, 'invite_info', { p_token: t1 }));
  T.err('a made-up token', await T.rpc(null, 'invite_info', { p_token: 'A'.repeat(32) }), 'invite_invalid');
  T.err('no token', await T.rpc(null, 'invite_info', { p_token: null }), 'invite_invalid');
  const N = await T.user('Nova', { terms: false });
  T.err('redeem before accepting the terms', await T.rpc(N, 'redeem_invite', { p_token: t1 }), 'terms');
  await T.sql('delete from realtime.messages');
  const joined = await T.rpc(S, 'redeem_invite', { p_token: t1 });
  T.ok('redeem_invite makes an editor', joined, (v) => v.mess_id === J && v.role === 'editor' && Object.keys(v).length === 2);
  T.check('the channel was told {kind:"members"}', (await T.broadcasts(J)).some((b) => b.event === 'meta' && b.payload.kind === 'members' && !('gone' in b.payload)));
  T.err('the used invite no longer shows', await T.rpc(null, 'invite_info', { p_token: t1 }), 'invite_invalid');
  T.ok('the joiner sending the same (now used) link again, as after a lost answer, is told they are in', await T.rpc(S, 'redeem_invite', { p_token: t1 }), (v) => v.mess_id === J && v.role === 'editor');
  const t2 = (await T.rpc(O, 'make_invite', { p_mess: J })).ok.token;
  T.ok('a member redeeming a fresh invite just gets their role back', await T.rpc(S, 'redeem_invite', { p_token: t2 }), (v) => v.role === 'editor');
  T.check('and the invite was not used up', await T.val(`select uses_left from public.invites where token_hash = $1`, [(await import('./lib.mjs')).sha(t2)]) === 1);
  T.ok('the owner redeeming answers owner', await T.rpc(O, 'redeem_invite', { p_token: t2 }), (v) => v.role === 'owner');
  T.err('someone else redeeming a used invite', await T.rpc(E2, 'redeem_invite', { p_token: t1 }), 'invite_invalid');
  const t3 = (await T.rpc(O, 'make_invite', { p_mess: J })).ok;
  await T.rpc(O, 'revoke_invite', { p_invite: t3.id });
  T.err('a revoked invite', await T.rpc(E2, 'redeem_invite', { p_token: t3.token }), 'invite_invalid');
  T.err('invite_info of a revoked invite', await T.rpc(null, 'invite_info', { p_token: t3.token }), 'invite_invalid');
  const t4 = (await T.rpc(O, 'make_invite', { p_mess: J })).ok.token;
  await T.sql(`update public.invites set expires_at = now() - interval '1 second' where token_hash = $1`, [(await import('./lib.mjs')).sha(t4)]);
  T.err('an expired invite', await T.rpc(E2, 'redeem_invite', { p_token: t4 }), 'invite_invalid');
  const t5 = (await T.rpc(O, 'make_invite', { p_mess: J })).ok.token;
  await T.sql('update public.messes set blocked = true where id = $1', [J]);
  T.err('an invite to a taken-down mess', await T.rpc(E2, 'redeem_invite', { p_token: t5 }), 'invite_invalid');
  T.err('invite_info of it', await T.rpc(null, 'invite_info', { p_token: t5 }), 'invite_invalid');
  await T.sql('update public.messes set blocked = false where id = $1', [J]);
  const one4two = await T.race([E, E2].map((u) => ({ user: u, fn: 'redeem_invite', args: { p_token: t5 } })));
  T.check('RACE one single-use link, two people at once: one joins, one is told invite_invalid',
    one4two.filter((r) => r.ok).length === 1 && one4two.filter((r) => r.err === 'invite_invalid').length === 1, JSON.stringify(one4two));
  const F = await T.mess(O, 'Full house');
  for (let i = 0; i < 6; i++) await T.join(O, F, await T.user('Fill' + i));
  const last = [await T.user('Late1'), await T.user('Late2')];
  const tk = [(await T.rpc(O, 'make_invite', { p_mess: F })).ok.token, (await T.rpc(O, 'make_invite', { p_mess: F })).ok.token];
  const full = await T.race(last.map((u, i) => ({ user: u, fn: 'redeem_invite', args: { p_token: tk[i] } })));
  T.check('RACE 7 members, two people with two links at once: one joins, one is told mess_full',
    full.filter((r) => r.ok).length === 1 && full.filter((r) => r.err === 'mess_full').length === 1 && await T.val('select count(*)::int from public.mess_members where mess_id = $1', [F]) === 8, JSON.stringify(full));
  const loser = full.findIndex((r) => r.err);
  T.check('the refused link was not used up', await T.val(`select uses_left from public.invites where token_hash = $1`, [(await import('./lib.mjs')).sha(tk[loser])]) === 1);
  T.err('a ninth member', await T.rpc(last[loser], 'redeem_invite', { p_token: tk[loser] }), 'mess_full');

  T.section('B remove_member, leave_mess');
  const X = await T.mess(O, 'Remove room');
  await T.join(O, X, E); await T.join(O, X, E2);
  await ap(E, X, [op()]);
  const oldTopic = (await T.rpc(O, 'open_mess', { p_mess: X })).ok.topic;
  T.err('an editor removing someone', await T.rpc(E2, 'remove_member', { p_mess: X, p_user: E.id }), 'forbidden');
  T.err('a stranger removing someone', await T.rpc(S, 'remove_member', { p_mess: X, p_user: E.id }), 'not_found');
  T.err('removing someone who is not in it', await T.rpc(O, 'remove_member', { p_mess: X, p_user: S.id }), 'not_found');
  T.err('removing the owner', await T.rpc(O, 'remove_member', { p_mess: X, p_user: O.id }), 'bad_input');
  await T.sql('delete from realtime.messages');
  T.ok('the owner removes an editor', await T.rpc(O, 'remove_member', { p_mess: X, p_user: E.id }), (v) => v.ok === true);
  const newTopic = (await T.rpc(O, 'open_mess', { p_mess: X })).ok.topic;
  const goneCast = await T.broadcasts(X);
  T.check('they were told {kind:"members", gone} on the OLD topic, before the key turned', goneCast.length === 1 && goneCast[0].topic === oldTopic && goneCast[0].payload.gone === E.id);
  T.check('the channel key turned', newTopic !== oldTopic && newTopic.startsWith(`mess:${X}:`));
  T.check('their live seat is freed', await T.val('select count(*)::int from public.mess_live where mess_id = $1 and user_id = $2', [X, E.id]) === 0);
  T.err('they can no longer open it', await T.rpc(E, 'open_mess', { p_mess: X }), 'not_found');
  T.check('the new topic comes back from ops_since', (await T.rpc(E2, 'ops_since', { p_mess: X, p_from: 0 })).ok.topic === newTopic);
  T.ok('they can be invited back (no block)', await T.join(O, X, E).then(() => ({ ok: 1 })).catch((e) => ({ err: e.message })));
  T.ok('remove and block', await T.rpc(O, 'remove_member', { p_mess: X, p_user: E.id, p_block: true }));
  T.check('a block row is made', await T.val('select count(*)::int from public.user_blocks where owner_id = $1 and blocked_id = $2', [O.id, E.id]) === 1);
  const back = (await T.rpc(O, 'make_invite', { p_mess: X })).ok.token;
  T.err('a blocked person cannot join with a fresh link', await T.rpc(E, 'redeem_invite', { p_token: back }), 'blocked');
  T.err('the owner cannot leave', await T.rpc(O, 'leave_mess', { p_mess: X }), 'owner_cannot_leave');
  T.err('a stranger leaving', await T.rpc(S, 'leave_mess', { p_mess: X }), 'not_found');
  await ap(E2, X, [op()]);
  await T.sql('delete from realtime.messages');
  T.ok('an editor leaves', await T.rpc(E2, 'leave_mess', { p_mess: X }), (v) => v.ok === true);
  T.check('they are out and their seat is gone', await T.val('select count(*)::int from public.mess_members where mess_id = $1 and user_id = $2', [X, E2.id]) === 0
    && await T.val('select count(*)::int from public.mess_live where mess_id = $1 and user_id = $2', [X, E2.id]) === 0);
  T.check('the channel heard who left', (await T.broadcasts(X)).some((b) => b.payload.kind === 'members' && b.payload.gone === E2.id));
  T.err('leaving twice', await T.rpc(E2, 'leave_mess', { p_mess: X }), 'not_found');

  T.section('B block_user, my_blocks, unblock_user');
  const A = await T.user('Ann'); const Bb = await T.user('Bob');
  const am = await T.mess(A, 'Ann room'); const bm = await T.mess(Bb, 'Bob room');
  await T.join(A, am, Bb); await T.join(Bb, bm, A);
  const ann2 = (await T.rpc(A, 'make_invite', { p_mess: am })).ok.token;
  const bob2 = (await T.rpc(Bb, 'make_invite', { p_mess: bm })).ok.token;
  const amTopic = (await T.rpc(A, 'open_mess', { p_mess: am })).ok.topic;
  T.err('blocking myself', await T.rpc(A, 'block_user', { p_user: A.id }), 'bad_input');
  T.err('blocking nobody', await T.rpc(A, 'block_user', { p_user: null }), 'bad_input');
  T.err('blocking someone who does not exist', await T.rpc(A, 'block_user', { p_user: uuid() }), 'not_found');
  T.ok('Ann blocks Bob (any member may block, not only owners)', await T.rpc(A, 'block_user', { p_user: Bb.id }), (v) => v.ok === true);
  T.err('Bob is out of Ann\'s mess', await T.rpc(Bb, 'open_mess', { p_mess: am }), 'not_found');
  T.err('Ann is out of Bob\'s mess', await T.rpc(A, 'open_mess', { p_mess: bm }), 'not_found');
  T.check('the key of Ann\'s mess turned', (await T.rpc(A, 'open_mess', { p_mess: am })).ok.topic !== amTopic);
  T.err('Bob\'s invites read invalid to Ann', await T.rpc(A, 'invite_info', { p_token: bob2 }), 'invite_invalid');
  T.err('and Ann cannot use them', await T.rpc(A, 'redeem_invite', { p_token: bob2 }), 'invite_invalid');
  T.ok('Bob still sees Ann\'s invite (he did not block her)', await T.rpc(Bb, 'invite_info', { p_token: ann2 }));
  T.err('but Ann\'s invites answer blocked to Bob', await T.rpc(Bb, 'redeem_invite', { p_token: ann2 }), 'blocked');
  T.ok('blocking twice is fine', await T.rpc(A, 'block_user', { p_user: Bb.id }));
  T.ok('my_blocks lists him with exactly id and name', await T.rpc(A, 'my_blocks'), (v) => v.length === 1 && v[0].id === Bb.id && v[0].name === 'Bob' && Object.keys(v[0]).length === 2);
  T.ok('Bob\'s list is empty', await T.rpc(Bb, 'my_blocks'), (v) => v.length === 0);
  T.ok('unblock_user', await T.rpc(A, 'unblock_user', { p_user: Bb.id }), (v) => v.ok === true);
  T.ok('my_blocks is empty again', await T.rpc(A, 'my_blocks'), (v) => v.length === 0);
  T.ok('unblocking someone not blocked is fine', await T.rpc(A, 'unblock_user', { p_user: uuid() }));
  T.ok('Ann may now join Bob\'s mess again', await T.rpc(A, 'redeem_invite', { p_token: bob2 }), (v) => v.mess_id === bm);

  T.section('B join_live, leave_live');
  const V = await T.mess(O, 'Live room');
  const vs = [];
  for (let i = 0; i < 5; i++) { const g = await T.user('Live' + i); await T.join(O, V, g); vs.push(g); }
  T.ok('join_live: a seat, 1 editor', await T.rpc(O, 'join_live', { p_mess: V }), (v) => v.live === true && v.editors === 1 && Object.keys(v).sort().join() === 'editors,live');
  T.ok('again: still mine, still 1', await T.rpc(O, 'join_live', { p_mess: V }), (v) => v.live === true && v.editors === 1);
  for (const g of vs.slice(0, 3)) await T.rpc(g, 'join_live', { p_mess: V });
  T.ok('a fifth person watches: live false, 4 editors', await T.rpc(vs[3], 'join_live', { p_mess: V }), (v) => v.live === false && v.editors === 4);
  T.err('a stranger', await T.rpc(S, 'join_live', { p_mess: V }), 'not_found');
  T.ok('leave_live frees the seat', await T.rpc(vs[0], 'leave_live', { p_mess: V }), (v) => v.ok === true);
  T.ok('so the fifth gets one now', await T.rpc(vs[3], 'join_live', { p_mess: V }), (v) => v.live === true && v.editors === 4);
  T.ok('leave_live on a mess I am not in is fine and does nothing', await T.rpc(S, 'leave_live', { p_mess: V }));
  await T.sql('delete from public.mess_live where mess_id = $1', [V]);
  const jl = await T.race([O, ...vs].map((u) => ({ user: u, fn: 'join_live', args: { p_mess: V } })));
  T.check('RACE six join_live at once: exactly four seats', jl.filter((r) => r.ok && r.ok.live).length === 4 && jl.filter((r) => r.ok && !r.ok.live).length === 2
    && await T.val('select count(*)::int from public.mess_live where mess_id = $1', [V]) === 4, JSON.stringify(jl.map((r) => r.err || r.ok.live)));
  const W = (jl.findIndex((r) => r.ok && !r.ok.live));
  T.err('the one left watching cannot send ops either', await ap([O, ...vs][W], V, [op()]), 'mess_full_live');

  T.section('B rename_mess and put_doc once a mess is in op mode');
  await cool(m);
  const cur0 = Number(await T.val('select rev from public.messes where id = $1', [m]));
  const rn = await T.rpc(O, 'rename_mess', { p_mess: m, p_name: 'Renamed by call' });
  const rnBatch = await T.one('select ops from public.mess_ops where mess_id = $1 and rev = $2', [m, rn.ok?.rev]);
  T.check('rename_mess now appends [{"t":"mess.set","f":"name","v":name}] at a new rev', rn.ok && rn.ok.rev === cur0 + 1 && rnBatch?.ops.length === 1
    && rnBatch.ops[0].t === 'mess.set' && rnBatch.ops[0].f === 'name' && rnBatch.ops[0].v === 'Renamed by call', JSON.stringify(rnBatch));
  const cur = Number(await T.val('select rev from public.messes where id = $1', [m]));
  const pd = await T.rpc(O, 'put_doc', { p_mess: m, p_doc: { v: 3 }, p_base_rev: cur });
  T.ok('put_doc on a shared mess still works, as a doc.reset at the new rev', pd, (v) => v.rev === cur + 1);
  T.ok('afterwards ops_since hands the doc.reset to the others', await T.rpc(E2, 'ops_since', { p_mess: m, p_from: cur }), (v) => v.ops.length === 1 && v.ops[0].ops[0].t === 'doc.reset' && v.doc_rev === cur + 1);
}
