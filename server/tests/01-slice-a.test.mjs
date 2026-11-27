// Slice A: my messes online, on two phones (ACCOUNTS-API.md 1.4). Every call: it works, each error it
// documents, and a stranger is refused. Plus the two-connection races for its limits (Review A M1).
import { uuid, sha } from './lib.mjs';

const svc = { role: 'service_role' };
const cfg = (T, key, value) => T.sql('update public.config set value = $2::jsonb where key = $1', [key, JSON.stringify(value)]);

export default async function (T) {
  const O = await T.user('Olive');        // owner
  const S = await T.user('Stranger');
  const N = await T.user('Newbie', { terms: false });

  T.section('A account');
  const fresh = uuid();
  await T.sql('insert into auth.users (id, email) values ($1, $2)', [fresh, 'fresh@test.invalid']);
  const made = await T.one('select display_name, name_set, terms_version from public.profiles where id = $1', [fresh]);
  T.check('signing up makes a profile named "Player" and 4 digits', /^Player \d{4}$/.test(made?.display_name) && made.name_set === false && made.terms_version === null, JSON.stringify(made));
  const me = await T.rpc(N, 'me');
  T.ok('me() has exactly the Me fields', me, (v) => Object.keys(v).sort().join() ===
    'colour,display_name,id,is_admin,mess_limit,messes,name_set,sound_bytes,sound_limit,terms_ok,terms_version');
  T.ok('me() before the terms: terms_ok false, the version to accept, the limits', me,
    (v) => v.id === N.id && v.terms_ok === false && v.terms_version === '2026-11' && v.sound_limit === 262144000 && v.mess_limit === 200 && v.messes === 0 && v.is_admin === false);
  T.ok('colour comes from the user id (last byte, modulo 8)', me, (v) => v.colour === parseInt(N.id.slice(-2), 16) % 8);
  T.err('accept_terms with an old version', await T.rpc(N, 'accept_terms', { p_version: '2025-01' }), 'terms_outdated');
  T.err('accept_terms with no version', await T.rpc(N, 'accept_terms', { p_version: null }), 'terms_outdated');
  T.ok('accept_terms with the current version', await T.rpc(N, 'accept_terms', { p_version: '2026-11' }), (v) => v.terms_ok === true);
  T.ok('accept_terms twice is fine', await T.rpc(N, 'accept_terms', { p_version: '2026-11' }));
  T.ok('me() after: terms_ok true', await T.rpc(N, 'me'), (v) => v.terms_ok === true);
  T.check('the acceptance is on record once', await T.val('select count(*)::int from public.terms_acceptances where user_id = $1', [N.id]) === 1);
  await T.sql('update public.profiles set name_set = false where id = $1', [N.id]);
  T.ok('set_profile trims and sets name_set', await T.rpc(N, 'set_profile', { p_display_name: '  Nia  ' }), (v) => v.display_name === 'Nia' && v.name_set === true);
  T.ok('set_profile takes 24 characters', await T.rpc(N, 'set_profile', { p_display_name: 'x'.repeat(24) }));
  T.err('set_profile with 25 characters', await T.rpc(N, 'set_profile', { p_display_name: 'x'.repeat(25) }), 'bad_input');
  T.err('set_profile with only spaces', await T.rpc(N, 'set_profile', { p_display_name: '   ' }), 'bad_input');
  T.err('set_profile with nothing', await T.rpc(N, 'set_profile', { p_display_name: null }), 'bad_input');
  T.err('set_profile with a blocked word', await T.rpc(N, 'set_profile', { p_display_name: 'the Nazi one' }), 'bad_name');
  T.err('set_profile with a blocked word hidden by dots', await T.rpc(N, 'set_profile', { p_display_name: 'h.i.t.l.e.r' }), 'bad_name');
  T.ok('a harmless word that only contains a blocked one is fine', await T.rpc(N, 'set_profile', { p_display_name: 'Grape Spice' }));

  T.section('A create_mess');
  const m1 = uuid();
  const doc = { v: 3, name: 'Basement', bpm: 97, kit: [] };
  T.ok('create_mess makes a mess at rev 0', await T.rpc(O, 'create_mess', { p_id: m1, p_name: ' Basement jam ', p_doc: doc }), (v) => v.id === m1 && v.rev === 0);
  T.ok('the same id again is a safe retry', await T.rpc(O, 'create_mess', { p_id: m1, p_name: 'Other', p_doc: { x: 1 } }), (v) => v.id === m1 && v.rev === 0);
  T.check('the retry changed nothing', await T.val('select name from public.messes where id = $1', [m1]) === 'Basement jam');
  T.err('an id that is someone else\'s mess', await T.rpc(S, 'create_mess', { p_id: m1, p_name: 'Mine now', p_doc: doc }), 'bad_input');
  T.err('a doc that is not an object', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'A', p_doc: [1, 2] }), 'bad_input');
  T.err('no id', await T.rpc(O, 'create_mess', { p_id: null, p_name: 'A', p_doc: doc }), 'bad_input');
  T.err('an empty name', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: '  ', p_doc: doc }), 'bad_name');
  T.err('a 49 character name', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'n'.repeat(49), p_doc: doc }), 'bad_name');
  T.err('a name with a blocked word', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'porn loops', p_doc: doc }), 'bad_name');
  const big = { v: 3, pad: 'x'.repeat(1048576) };
  T.err('a doc over 1 MB', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'Big', p_doc: big }), 'too_big');
  const nearly = { pad: 'x'.repeat(1048576 - 11) };   // Postgres counts the doc as it stores it: {"pad": "..."}
  T.ok('a doc of exactly 1 MB is taken', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'Nearly', p_doc: nearly }), (v) => v.rev === 0);
  await cfg(T, 'docs_limit', 1048576 + 100);
  T.err('over the owner\'s 30 MB of messes and History (limit lowered for the test)', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'Full', p_doc: { pad: 'x'.repeat(200) } }), 'docs_full');
  await cfg(T, 'docs_limit', 31457280);
  await cfg(T, 'mess_limit', 2);
  T.err('the 201st mess (limit lowered to 2 for the test)', await T.rpc(O, 'create_mess', { p_id: uuid(), p_name: 'Third', p_doc: doc }), 'too_many_messes');
  await cfg(T, 'mess_limit', 3);
  const raced = await T.race([1, 2, 3, 4].map((i) => ({ user: O, fn: 'create_mess', args: { p_id: uuid(), p_name: 'Race ' + i, p_doc: doc } })));
  T.check('RACE mess limit: four connections at once, one place left, exactly one gets it',
    raced.filter((r) => r.ok).length === 1 && raced.filter((r) => r.err === 'too_many_messes').length === 3, JSON.stringify(raced));
  T.check('the owner has exactly 3 messes', await T.val('select count(*)::int from public.messes where owner_id = $1', [O.id]) === 3);
  await cfg(T, 'mess_limit', 200);
  const sameId = uuid();
  const twins = await T.race([1, 2, 3].map(() => ({ user: O, fn: 'create_mess', args: { p_id: sameId, p_name: 'Twin', p_doc: doc } })));
  T.check('RACE one id from three connections: all get the same answer, one mess exists',
    twins.every((r) => r.ok && r.ok.id === sameId && r.ok.rev === 0) && await T.val('select count(*)::int from public.messes where id = $1', [sameId]) === 1, JSON.stringify(twins));

  T.section('A my_messes and open_mess');
  const list = await T.rpc(O, 'my_messes');
  T.ok('my_messes lists my messes with the MessRow fields', list, (v) => v.length === 4 && Object.keys(v[0]).sort().join() ===
    'blocked,doc_bytes,id,members,name,owner_name,rev,role,share_mode,updated_at');
  T.ok('newest first', list, (v) => v.every((r, i) => i === 0 || v[i - 1].updated_at >= r.updated_at));
  T.ok('a row says owner, 1 member, off, not blocked, and the time is ISO UTC', list, (v) => {
    const r = v.find((x) => x.id === m1);
    return r.role === 'owner' && r.owner_name === 'Olive' && r.members === 1 && r.share_mode === 'off' && r.blocked === false && r.rev === 0 && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z$/.test(r.updated_at);
  });
  T.ok('a stranger\'s list does not hold it', await T.rpc(S, 'my_messes'), (v) => v.length === 0);
  const opened = await T.rpc(O, 'open_mess', { p_mess: m1 });
  T.ok('open_mess has exactly the Opened fields', opened, (v) => Object.keys(v).sort().join() ===
    'doc,doc_rev,id,members,name,ops,ops_v,owner_id,rev,role,share_mode,share_token,sounds,topic');
  T.ok('open_mess gives the doc as saved, no ops, me as the one member', opened, (v) =>
    v.doc.name === 'Basement' && v.doc.bpm === 97 && Object.keys(v.doc).length === 4 && v.rev === 0 && v.doc_rev === 0 && v.ops.length === 0 && v.ops_v === 1 && v.sounds.length === 0 &&
    v.members.length === 1 && v.members[0].id === O.id && v.members[0].role === 'owner' && v.members[0].name === 'Olive' && v.role === 'owner' && v.owner_id === O.id && v.share_token === null);
  T.ok('the topic is mess:<id>:<16 hex>', opened, (v) => new RegExp(`^mess:${m1}:[0-9a-f]{16}$`).test(v.topic));
  T.err('open_mess as a stranger', await T.rpc(S, 'open_mess', { p_mess: m1 }), 'not_found');
  T.err('open_mess of nothing', await T.rpc(O, 'open_mess', { p_mess: uuid() }), 'not_found');

  T.section('A put_doc');
  await T.sql('delete from realtime.messages');
  const doc2 = { v: 3, name: 'A doc that names itself', bpm: 140 };
  const p1 = await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 0 });
  T.ok('put_doc saves: rev 1, and the first save makes a History entry', p1, (v) => v.rev === 1 && typeof v.version_id === 'string');
  const o2 = (await T.rpc(O, 'open_mess', { p_mess: m1 })).ok;
  T.check('afterwards doc_rev equals rev and ops is empty (what a Slice A app needs)', o2.rev === 1 && o2.doc_rev === 1 && o2.ops.length === 0 && o2.doc.bpm === 140);
  T.check('put_doc never writes the name', o2.name === 'Basement jam');
  const stored = await T.one('select ops, user_id, v from public.mess_ops where mess_id = $1 and rev = 1', [m1]);
  T.check('the batch [{"t":"doc.reset"}] is stored at the new rev', JSON.stringify(stored?.ops) === '[{"t":"doc.reset"}]' && stored.user_id === O.id);
  const cast = await T.broadcasts(m1);
  T.check('and broadcast as "ops" on the mess topic', cast.length === 1 && cast[0].event === 'ops' && cast[0].topic === o2.topic && cast[0].payload.rev === 1 && cast[0].payload.ops[0].t === 'doc.reset');
  T.ok('a second save soon after makes no History entry', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 1 }), (v) => v.rev === 2 && v.version_id === null);
  T.ok('a save with a label always makes one', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 2, p_label: 'Saved' }), (v) => v.rev === 3 && v.version_id !== null);
  await T.sql(`update public.mess_versions set at = at - interval '11 minutes' where mess_id = $1`, [m1]);
  T.ok('so does a save when the newest entry is over 10 minutes old', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 3 }), (v) => v.rev === 4 && v.version_id !== null);
  T.err('an old base_rev', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 2 }), 'conflict');
  T.err('a doc that is not an object', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: [1, 2], p_base_rev: 4 }), 'bad_input');
  T.err('a 41 character label', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 4, p_label: 'l'.repeat(41) }), 'bad_input');
  T.err('no base_rev', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: null }), 'bad_input');
  T.err('a doc over 1 MB', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: big, p_base_rev: 4 }), 'too_big');
  T.err('as a stranger', await T.rpc(S, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 4 }), 'not_found');
  await cfg(T, 'docs_limit', 100);
  T.err('over the owner\'s docs limit', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 4 }), 'docs_full');
  await cfg(T, 'docs_limit', 31457280);
  await T.sql('update public.messes set blocked = true where id = $1', [m1]);
  T.err('on a mess that was taken down (its owner)', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: 4 }), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [m1]);
  const two = await T.race([1, 2, 3, 4].map((i) => ({ user: O, fn: 'put_doc', args: { p_mess: m1, p_doc: { who: i }, p_base_rev: 4 } })));
  T.check('RACE four saves on one base rev: one wins, three get conflict',
    two.filter((r) => r.ok && r.ok.rev === 5).length === 1 && two.filter((r) => r.err === 'conflict').length === 3, JSON.stringify(two));
  await T.sql(`delete from mm_private.hits where what = 'put_doc'`);
  let rev = 5; let last;
  for (let i = 0; i < 13; i++) { last = await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: rev }); if (last.ok) rev = last.ok.rev; }
  T.check('twelve saves in a minute pass', rev === 17, `rev ${rev}`);
  T.err('the thirteenth', last, 'rate_limited');
  await T.sql(`update mm_private.hits set at = at - interval '61 seconds' where what = 'put_doc'`);
  T.ok('a minute later saving works again', await T.rpc(O, 'put_doc', { p_mess: m1, p_doc: doc2, p_base_rev: rev }), (v) => v.rev === 18);
  await T.sql(`delete from mm_private.hits where what = 'put_doc'`);
  const burst = await T.race(Array.from({ length: 16 }, (_, i) => ({ user: O, fn: 'put_doc', args: { p_mess: m1, p_doc: doc2, p_base_rev: 18 + i } })));
  T.check('RACE the save rate: sixteen at once never count more than twelve', await T.val(`select count(*)::int from mm_private.hits where what = 'put_doc'`) <= 12 && burst.filter((r) => r.ok).length <= 12, JSON.stringify(burst.map((r) => r.err || 'ok')));
  await T.sql(`delete from mm_private.hits where what = 'put_doc'`);

  T.section('A rename_mess and delete_mess');
  const m2 = await T.mess(O, 'To rename');
  T.ok('rename_mess by the owner: the name changes and, on a mess only ever saved whole, rev stays', await T.rpc(O, 'rename_mess', { p_mess: m2, p_name: '  New name ' }), (v) => v.rev === 0);
  T.check('the name was trimmed and stored', await T.val('select name from public.messes where id = $1', [m2]) === 'New name');
  T.err('rename to a blocked word', await T.rpc(O, 'rename_mess', { p_mess: m2, p_name: 'slut' }), 'bad_name');
  T.err('rename to nothing', await T.rpc(O, 'rename_mess', { p_mess: m2, p_name: '' }), 'bad_name');
  T.err('rename as a stranger', await T.rpc(S, 'rename_mess', { p_mess: m2, p_name: 'Hacked' }), 'not_found');
  T.err('delete as a stranger', await T.rpc(S, 'delete_mess', { p_mess: m2 }), 'not_found');
  const topic2 = (await T.rpc(O, 'open_mess', { p_mess: m2 })).ok.topic;
  T.ok('delete_mess by the owner', await T.rpc(O, 'delete_mess', { p_mess: m2 }), (v) => v.ok === true);
  T.err('it is gone', await T.rpc(O, 'open_mess', { p_mess: m2 }), 'not_found');
  T.err('deleting it again', await T.rpc(O, 'delete_mess', { p_mess: m2 }), 'not_found');
  const bye = await T.sql(`select payload from realtime.messages where topic = $1 and event = 'meta'`, [topic2]);
  T.check('the channel was told {kind:"deleted"} first', bye.length === 1 && bye[0].payload.kind === 'deleted');

  T.section('A register_sound');
  const reg = (who, text, over = {}) => T.rpc(who, 'register_sound', {
    p_sha256: sha(text), p_bytes: 1000, p_seconds: 2, p_rate: 44100, p_kind: 'imp', p_local_key: 'bn:' + text,
    p_title: text, p_bpm: 120, p_beats: 4, p_mkey: 'Am', ...over });
  const r1 = await reg(O, 'kick');
  T.ok('register_sound answers upload, the path <me>/<sha256>.wav, used and limit', r1, (v) =>
    v.state === 'upload' && v.path === `${O.id}/${sha('kick')}.wav` && v.used === 1000 && v.limit === 262144000);
  T.ok('registering the same sound again is fine', await reg(O, 'kick'), (v) => v.state === 'upload' && v.used === 1000);
  T.err('a hash in capitals', await reg(O, 'x', { p_sha256: sha('x').toUpperCase() }), 'bad_input');
  T.err('a hash that is too short', await reg(O, 'x', { p_sha256: 'abc' }), 'bad_input');
  T.err('a rate of 7999', await reg(O, 'x', { p_rate: 7999 }), 'bad_input');
  T.err('a rate of 96001', await reg(O, 'x', { p_rate: 96001 }), 'bad_input');
  T.err('an unknown kind', await reg(O, 'x', { p_kind: 'wave' }), 'bad_input');
  T.err('zero bytes', await reg(O, 'x', { p_bytes: 0 }), 'bad_input');
  T.err('101 seconds', await reg(O, 'x', { p_seconds: 100.5 }), 'too_long');
  T.ok('exactly 100 seconds is taken, for every kind', await reg(O, 'long take', { p_seconds: 100, p_kind: 'take' }), (v) => v.state === 'upload');
  T.err('a mic recording over 100 seconds is refused too (no long takes yet)', await reg(O, 'x', { p_seconds: 2400, p_kind: 'mic' }), 'too_long');
  T.err('one byte over 20 MB', await reg(O, 'x', { p_bytes: 20971521 }), 'too_big');
  T.ok('exactly 20 MB is taken', await reg(O, 'fat', { p_bytes: 20971520 }), (v) => v.state === 'upload');
  await T.sql(`delete from public.sound_files where owner_id = $1 and sha256 <> $2`, [O.id, sha('kick')]);
  for (let i = 0; i < 19; i++) await reg(O, 'p' + i);
  T.err('a 21st unfinished upload', await reg(O, 'p-over'), 'too_many_pending');
  await T.sql(`delete from public.sound_files where owner_id = $1 and sha256 = $2`, [O.id, sha('p18')]);
  const pend = await T.race(['a', 'b', 'c'].map((x) => ({ user: O, fn: 'register_sound', args: {
    p_sha256: sha('pr' + x), p_bytes: 10, p_seconds: 1, p_rate: 44100, p_kind: 'imp', p_local_key: '', p_title: '', p_bpm: 0, p_beats: 0, p_mkey: '' } })));
  T.check('RACE pending uploads: 19 waiting, three more at once, exactly one gets the last place',
    pend.filter((r) => r.ok).length === 1 && pend.filter((r) => r.err === 'too_many_pending').length === 2, JSON.stringify(pend.map((r) => r.err || 'ok')));
  await T.sql(`delete from public.sound_files where owner_id = $1`, [O.id]);
  await cfg(T, 'sound_limit', 5000);
  T.ok('quota: 3000 of 5000', await reg(O, 'q1', { p_bytes: 3000 }), (v) => v.used === 3000 && v.limit === 5000);
  T.err('quota: 2001 more is one byte too many', await reg(O, 'q2', { p_bytes: 2001 }), 'quota');
  T.ok('quota: exactly 2000 more fits, to the byte', await reg(O, 'q2', { p_bytes: 2000 }), (v) => v.used === 5000);
  T.err('quota: then even 1 byte is refused', await reg(O, 'q3', { p_bytes: 1 }), 'quota');
  await T.sql(`delete from public.sound_files where owner_id = $1 and sha256 = $2`, [O.id, sha('q2')]);
  const quota = await T.race(['a', 'b', 'c'].map((x) => ({ user: O, fn: 'register_sound', args: {
    p_sha256: sha('qr' + x), p_bytes: 2000, p_seconds: 1, p_rate: 44100, p_kind: 'imp', p_local_key: '', p_title: '', p_bpm: 0, p_beats: 0, p_mkey: '' } })));
  T.check('RACE quota: room for one more 2000 byte sound, three at once, exactly one fits',
    quota.filter((r) => r.ok).length === 1 && quota.filter((r) => r.err === 'quota').length === 2, JSON.stringify(quota.map((r) => r.err || 'ok')));
  await T.sql(`delete from public.sound_files where owner_id = $1`, [O.id]);
  await cfg(T, 'sound_limit', 262144000);

  T.section('A finish_upload');
  const snare = sha('snare');
  await reg(O, 'snare');
  T.err('finish_upload of a sound never registered', await T.rpc(O, 'finish_upload', { p_sha256: sha('nothing') }), 'not_found');
  T.err('finish_upload before the file is there', await T.rpc(O, 'finish_upload', { p_sha256: snare }), 'not_uploaded');
  T.err('finish_upload of someone else\'s sound', await T.rpc(S, 'finish_upload', { p_sha256: snare }), 'not_found');
  await T.put('sounds', `${O.id}/${snare}.wav`, 1001, O.id);
  T.err('the real file is not the size that was registered', await T.rpc(O, 'finish_upload', { p_sha256: snare }), 'too_big');
  T.check('a refused file is still only pending, and counts for nothing', await T.val('select state from public.sound_files where owner_id = $1 and sha256 = $2', [O.id, snare]) === 'pending'
    && (await T.rpc(O, 'me')).ok.sound_bytes === 0);
  await T.put('sounds', `${O.id}/${snare}.wav`, 1000, O.id);
  await cfg(T, 'sound_limit', 999);
  T.err('the real size no longer fits the quota', await T.rpc(O, 'finish_upload', { p_sha256: snare }), 'quota');
  await cfg(T, 'sound_limit', 262144000);
  T.ok('finish_upload: ready, and the bytes now count', await T.rpc(O, 'finish_upload', { p_sha256: snare }), (v) => v.state === 'ready' && v.used === 1000);
  T.ok('finish_upload again answers ready again', await T.rpc(O, 'finish_upload', { p_sha256: snare }), (v) => v.state === 'ready' && v.used === 1000);
  T.ok('me() shows the bytes', await T.rpc(O, 'me'), (v) => v.sound_bytes === 1000);
  T.ok('registering a ready sound answers ready: no second upload', await reg(O, 'snare'), (v) => v.state === 'ready' && v.used === 1000);
  await T.sound(O, 'fa', { bytes: 600, finish: false });
  await T.sound(O, 'fb', { bytes: 600, finish: false });
  await cfg(T, 'sound_limit', 1700);
  const fin = await T.race(['fa', 'fb'].map((x) => ({ user: O, fn: 'finish_upload', args: { p_sha256: sha(x) } })));
  T.check('RACE finishing two uploads at once with room for one: one ready, one quota',
    fin.filter((r) => r.ok).length === 1 && fin.filter((r) => r.err === 'quota').length === 1 && (await T.rpc(O, 'me')).ok.sound_bytes === 1600, JSON.stringify(fin));
  await cfg(T, 'sound_limit', 262144000);

  T.section('A attach_sound, my_sounds, delete_sound');
  const m3 = await T.mess(O, 'With sounds');
  const hat = await T.sound(O, 'hat', { bytes: 500 });
  const wait = await T.sound(O, 'not yet up', { bytes: 700, upload: false });
  T.ok('attach_sound lists my sound on my mess', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:hat', p_owner: O.id, p_sha256: hat.sha256 }), (v) => v.ok === true);
  T.ok('attaching again under the same key is fine', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:hat', p_owner: O.id, p_sha256: hat.sha256 }));
  T.ok('a sound still uploading may be attached', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:later', p_owner: O.id, p_sha256: wait.sha256 }));
  T.err('a key that does not start with bn:', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'kit:hat', p_owner: O.id, p_sha256: hat.sha256 }), 'bad_input');
  T.err('a key of 81 characters', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:' + 'k'.repeat(78), p_owner: O.id, p_sha256: hat.sha256 }), 'bad_input');
  T.err('a sound that does not exist', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:x', p_owner: O.id, p_sha256: sha('no such') }), 'not_found');
  T.err('a stranger attaching to my mess', await T.rpc(S, 'attach_sound', { p_mess: m3, p_key: 'bn:x', p_owner: O.id, p_sha256: hat.sha256 }), 'not_found');
  const ms = await T.mess(S, 'Stranger mess');
  T.err('a stranger attaching my sound to their own mess (it is not theirs and no mess of theirs lists it)',
    await T.rpc(S, 'attach_sound', { p_mess: ms, p_key: 'bn:stolen', p_owner: O.id, p_sha256: hat.sha256 }), 'not_found');
  await T.sql('update public.messes set blocked = true where id = $1', [m3]);
  T.err('attach on a mess that was taken down', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:hat2', p_owner: O.id, p_sha256: hat.sha256 }), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [m3]);
  const withSounds = (await T.rpc(O, 'open_mess', { p_mess: m3 })).ok;
  T.check('open_mess lists both with exactly the Sound fields', withSounds.sounds.length === 2 && Object.keys(withSounds.sounds[0]).sort().join() ===
    'beats,bpm,bytes,fmt,key,kind,mkey,owner,rate,seconds,sha256,src,state,title', JSON.stringify(withSounds.sounds[0]));
  const sHat = withSounds.sounds.find((x) => x.key === 'bn:hat');
  const sLater = withSounds.sounds.find((x) => x.key === 'bn:later');
  T.check('a ready sound: src user, my id, wav, its facts', sHat.src === 'user' && sHat.owner === O.id && sHat.fmt === 'wav' && sHat.bytes === 500 && sHat.rate === 44100 && sHat.kind === 'imp' && sHat.state === 'ready' && sHat.title === 'hat' && sHat.seconds === 2.5);
  T.check('one still uploading says pending', sLater.state === 'pending');
  await T.put('sounds', wait.path, 700, O.id);
  await T.sql('delete from realtime.messages');
  await T.rpc(O, 'finish_upload', { p_sha256: wait.sha256 });
  const told = await T.broadcasts(m3);
  T.check('when it finishes, the mess that lists it is told {kind:"sound"}', told.length === 1 && told[0].event === 'meta' && told[0].payload.kind === 'sound' && told[0].payload.sha256 === wait.sha256);
  const mine = await T.rpc(O, 'my_sounds');
  T.ok('my_sounds has exactly its fields, biggest first', mine, (v) => v.length >= 3 && Object.keys(v[0]).sort().join() === 'bytes,kind,messes,seconds,sha256,state,title,vault'
    && v.every((x, i) => i === 0 || v[i - 1].bytes >= x.bytes));
  T.ok('it counts the messes that use a sound, and vault is null', mine, (v) => { const h = v.find((x) => x.sha256 === hat.sha256); return h.messes === 1 && h.vault === null && h.state === 'ready'; });
  T.ok('a stranger sees none of them', await T.rpc(S, 'my_sounds'), (v) => v.length === 0);

  const urls = await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: [{ method: 'otp' }], p_body: { mess: m3 } }, svc);
  T.ok('sound-urls for a member lists the ready sounds with bucket and path', urls, (v) => v.sounds.length === 2 && v.sounds.every((x) => x.bucket === 'sounds' && x.path === `${O.id}/${x.sha256}.wav`)
    && JSON.stringify(Object.keys(v.sounds[0]).sort()) === '["bucket","bytes","key","path","sha256"]');
  T.err('sound-urls for a stranger', await T.rpc(null, 'svc_sound_urls', { p_user: S.id, p_amr: null, p_body: { mess: m3 } }, svc), 'not_found');
  T.err('sound-urls for nobody', await T.rpc(null, 'svc_sound_urls', { p_user: null, p_amr: null, p_body: { mess: m3 } }, svc), 'auth');
  T.err('sound-urls with a password session', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: [{ method: 'password' }], p_body: { mess: m3 } }, svc), 'auth');
  T.err('sound-urls with two things asked at once', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: { mess: m3, token: 'x' } }, svc), 'bad_input');
  T.err('sound-urls with an unknown key', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: { mes: m3 } }, svc), 'bad_input');
  T.err('sound-urls with an empty body', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: {} }, svc), 'bad_input');
  T.err('sound-urls with an id that is not an id', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: { mess: 'nope' } }, svc), 'bad_input');
  T.err('sound-urls with a number', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: { mess: 5 } }, svc), 'bad_input');

  T.err('delete_sound of a sound I do not have', await T.rpc(O, 'delete_sound', { p_sha256: sha('nothing') }), 'not_found');
  T.err('delete_sound of someone else\'s sound', await T.rpc(S, 'delete_sound', { p_sha256: hat.sha256 }), 'not_found');
  const usedBefore = (await T.rpc(O, 'me')).ok.sound_bytes;
  await T.sql('delete from realtime.messages');
  const d1 = await T.rpc(O, 'delete_sound', { p_sha256: hat.sha256 });
  T.ok('delete_sound, first call: deleting, the path, still counted, 1 mess lost it', d1, (v) => v.state === 'deleting' && v.path === hat.path && v.used === usedBefore && v.messes === 1
    && Object.keys(v).sort().join() === 'messes,path,state,used');
  const gone = (await T.rpc(O, 'open_mess', { p_mess: m3 })).ok.sounds.find((x) => x.key === 'bn:hat');
  T.check('the mess keeps the row but it now reads gone, with no owner', gone && gone.src === 'gone' && gone.owner === null && gone.title === 'hat');
  T.check('the mess was told', (await T.broadcasts(m3)).some((b) => b.payload.kind === 'sound' && b.payload.sha256 === hat.sha256));
  T.ok('sound-urls no longer offers it', await T.rpc(null, 'svc_sound_urls', { p_user: O.id, p_amr: null, p_body: { mess: m3 } }, svc), (v) => v.sounds.length === 1 && v.sounds[0].sha256 === wait.sha256);
  T.ok('second call while the object is still there: still deleting', await T.rpc(O, 'delete_sound', { p_sha256: hat.sha256 }), (v) => v.state === 'deleting' && v.used === usedBefore);
  await T.sql(`delete from storage.objects where bucket_id = 'sounds' and name = $1`, [hat.path]);
  T.ok('once the object is gone: deleted, and the bytes come off', await T.rpc(O, 'delete_sound', { p_sha256: hat.sha256 }), (v) => v.state === 'deleted' && v.used === usedBefore - 500);
  T.err('and then it is simply not there', await T.rpc(O, 'delete_sound', { p_sha256: hat.sha256 }), 'not_found');
  T.err('a deleted sound cannot be attached again', await T.rpc(O, 'attach_sound', { p_mess: m3, p_key: 'bn:hat', p_owner: O.id, p_sha256: hat.sha256 }), 'not_found');
  const never = await T.sound(O, 'never sent', { upload: false });
  await T.rpc(O, 'delete_sound', { p_sha256: never.sha256 });
  T.ok('deleting a sound that never finished uploading takes nothing off the total', await T.rpc(O, 'delete_sound', { p_sha256: never.sha256 }), (v) => v.state === 'deleted' && v.used === usedBefore - 500);
  T.check('the stored total always equals the sum of the counted files', await T.val(
    `select p.sound_bytes = coalesce((select sum(bytes) from public.sound_files f where f.owner_id = p.id and f.counted), 0) from public.profiles p where p.id = $1`, [O.id]) === true);

  T.section('A storage policies (through the stand-in Storage table)');
  const up = await T.sound(O, 'policy sound', { upload: false });
  const ins = (who, bucket, name) => T.as(who, `insert into storage.objects (bucket_id, name, owner) values ($1, $2, $3)`, [bucket, name, who ? who.id : null]);
  T.check('I may upload a file I registered, into my own folder', (await ins(O, 'sounds', up.path)).count === 1);
  T.check('I may not upload a file I never registered', (await ins(O, 'sounds', `${O.id}/${sha('unregistered')}.wav`)).sqlstate === '42501');
  T.check('I may not upload under another name in my folder', (await ins(O, 'sounds', `${O.id}/whatever.wav`)).sqlstate === '42501');
  T.check('a stranger may not upload my registered file', (await ins(S, 'sounds', up.path)).sqlstate === '42501');
  T.check('a stranger may not upload into their folder with my hash', (await ins(S, 'sounds', `${S.id}/${up.sha256}.wav`)).sqlstate === '42501');
  T.check('anon may not upload', (await ins(null, 'sounds', up.path)).sqlstate === '42501');
  T.check('nobody but an admin may upload to the vault bucket', (await ins(O, 'vault', `${up.sha256}.wav`)).sqlstate === '42501');
  await T.put('sounds', up.path, 1000, O.id);
  await T.rpc(O, 'finish_upload', { p_sha256: up.sha256 });
  await T.sql(`delete from storage.objects where name = $1`, [up.path]);
  T.check('once a sound is finished its path is closed: no second upload', (await ins(O, 'sounds', up.path)).sqlstate === '42501');
  await T.put('sounds', up.path, 1000, O.id);
  const sel = (who) => T.as(who, `select name from storage.objects where bucket_id = 'sounds' and name = $1`, [up.path]);
  const upd = (who) => T.as(who, `update storage.objects set metadata = '{"size":1}' where bucket_id = 'sounds' and name = $1`, [up.path]);
  const del = (who) => T.as(who, `delete from storage.objects where bucket_id = 'sounds' and name = $1 returning name`, [up.path]);
  T.check('I cannot read my own file with my token (listening goes through sound-urls)', (await sel(O)).count === 0);
  T.check('a stranger cannot read it', (await sel(S)).count === 0);
  T.check('anon cannot read it', (await sel(null)).count === 0);
  T.check('nobody can overwrite it: there is no update policy', (await upd(O)).count === 0 && (await upd(S)).count === 0);
  T.check('I cannot remove the object of a sound that is still in use', (await del(O)).count === 0);
  await T.rpc(O, 'delete_sound', { p_sha256: up.sha256 });
  T.check('after delete_sound I can remove it', (await del(O)).count === 1);
  T.check('a stranger still cannot', (await del(S)).count === 0 && (await sel(S)).count === 0);
  T.check('anon cannot', (await del(null)).count === 0);
  const banned = await T.user('Banned uploader');
  const bup = await T.sound(banned, 'banned sound', { upload: false });
  await T.sql('update public.profiles set banned = true where id = $1', [banned.id]);
  T.check('a banned person cannot upload even a registered file', (await ins(banned, 'sounds', bup.path)).sqlstate === '42501');
  const buckets = await T.sql(`select id, public, file_size_limit, allowed_mime_types from storage.buckets order by id`);
  T.check('the buckets: sounds and vault, private, 20 MB, wav and flac', buckets.length === 2 && buckets.every((b) => b.public === false && Number(b.file_size_limit) === 20971520 && b.allowed_mime_types.join() === 'audio/wav,audio/flac')
    && buckets[0].id === 'sounds' && buckets[1].id === 'vault', JSON.stringify(buckets));
  const pols = (await T.sql(`select polname, polcmd from pg_policy where polrelid = 'storage.objects'::regclass order by 1`)).map((p) => p.polname + ':' + p.polcmd).join(' ');
  T.check('storage.objects has no update policy, and no policy of ours beyond the six', pols === 'mm_sounds_delete:d mm_sounds_insert:a mm_sounds_select:r mm_vault_delete:d mm_vault_insert:a mm_vault_select:r', pols);
}
