// Slice D: share links (view and copy) and History (ACCOUNTS-API.md 1.7), and sound-urls with {"token"}.
import { uuid } from './lib.mjs';

const svc = { role: 'service_role' };
const cfg = (T, key, value) => T.sql('update public.config set value = $2::jsonb where key = $1', [key, JSON.stringify(value)]);

export default async function (T) {
  const O = await T.user('Dora');
  const E = await T.user('Dev');
  const S = await T.user('Stu');
  const m = await T.mess(O, 'Shared jam', { v: 3, bpm: 111 });
  await T.join(O, m, E);
  const snd = await T.sound(O, 'share kick', { bytes: 800 });
  await T.rpc(O, 'attach_sound', { p_mess: m, p_key: 'bn:kick', p_owner: O.id, p_sha256: snd.sha256 });
  await T.rpc(E, 'apply_ops', { p_mess: m, p_batch: uuid(), p_ops: [{ t: 'song.set', b: 0, v: 2 }], p_v: 1 });

  T.section('D set_share');
  T.err('a stranger', await T.rpc(S, 'set_share', { p_mess: m, p_mode: 'view' }), 'not_found');
  T.err('an editor', await T.rpc(E, 'set_share', { p_mess: m, p_mode: 'view' }), 'forbidden');
  T.err('an unknown mode', await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'public' }), 'bad_input');
  T.err('no mode', await T.rpc(O, 'set_share', { p_mess: m, p_mode: null }), 'bad_input');
  const v1 = await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'view' });
  T.ok('turning on: view, a fresh 32 character token, exactly two fields', v1, (v) => v.share_mode === 'view' && /^[A-Za-z0-9_-]{32}$/.test(v.share_token) && Object.keys(v).length === 2);
  T.ok('the same mode again keeps the link', await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'view' }), (v) => v.share_token === v1.ok.share_token);
  T.ok('switching to copy keeps the link', await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'copy' }), (v) => v.share_mode === 'copy' && v.share_token === v1.ok.share_token);
  const v2 = await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'view', p_new: true });
  T.ok('p_new makes a fresh link', v2, (v) => v.share_token !== v1.ok.share_token);
  T.err('the old link is dead', await T.rpc(null, 'open_shared', { p_token: v1.ok.share_token }), 'not_found');
  T.ok('open_mess shows the token to the owner', await T.rpc(O, 'open_mess', { p_mess: m }), (v) => v.share_token === v2.ok.share_token && v.share_mode === 'view');
  T.ok('and not to an editor', await T.rpc(E, 'open_mess', { p_mess: m }), (v) => v.share_token === null && v.share_mode === 'view');
  T.ok('my_messes shows the mode', await T.rpc(E, 'my_messes'), (v) => v.find((r) => r.id === m).share_mode === 'view');
  T.ok('off clears the token', await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'off' }), (v) => v.share_mode === 'off' && v.share_token === null);
  T.err('a link turned off is dead', await T.rpc(null, 'open_shared', { p_token: v2.ok.share_token }), 'not_found');
  const on = (await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'view' })).ok.share_token;
  T.check('turning it on again makes a new link, never the old one', on !== v2.ok.share_token && on !== v1.ok.share_token);
  await T.sql('update public.messes set blocked = true where id = $1', [m]);
  T.err('the owner of a taken-down mess', await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'copy' }), 'blocked');
  T.err('a taken-down mess\'s link does not open', await T.rpc(null, 'open_shared', { p_token: on }), 'not_found');
  await T.sql('update public.messes set blocked = false where id = $1', [m]);

  T.section('D open_shared');
  const sh = await T.rpc(null, 'open_shared', { p_token: on });
  T.ok('anon opens a view link with exactly its fields', sh, (v) => Object.keys(v).sort().join() === 'doc,doc_rev,mode,name,ops,owner_name,rev,sounds' && v.name === 'Shared jam' && v.owner_name === 'Dora' && v.mode === 'view' && v.doc.bpm === 111);
  T.ok('the ops are there, with every user id null', sh, (v) => v.ops.length === 1 && v.ops.every((b) => b.user === null) && v.rev === 1 && v.doc_rev === 0);
  T.ok('the sounds are there, with every owner null', sh, (v) => v.sounds.length === 1 && v.sounds.every((s) => s.owner === null && s.src === 'user'));
  T.check('nothing in the answer holds a person\'s id', !JSON.stringify(sh.ok).includes(O.id) && !JSON.stringify(sh.ok).includes(E.id));
  T.ok('a signed-in stranger opens it too', await T.rpc(S, 'open_shared', { p_token: on }));
  T.err('a made-up token', await T.rpc(null, 'open_shared', { p_token: 'x'.repeat(32) }), 'not_found');
  T.err('no token', await T.rpc(null, 'open_shared', { p_token: null }), 'not_found');
  await T.sql('update public.profiles set banned = true where id = $1', [O.id]);
  T.err('a banned owner\'s link does not open', await T.rpc(null, 'open_shared', { p_token: on }), 'not_found');
  await T.sql('update public.profiles set banned = false where id = $1', [O.id]);

  T.section('D sound-urls with a share link');
  const su = (body, u = null) => T.rpc(null, 'svc_sound_urls', { p_user: u ? u.id : null, p_amr: null, p_body: body }, svc);
  T.ok('a guest with the link gets the ready sounds', await su({ token: on }), (v) => v.sounds.length === 1 && v.sounds[0].key === 'bn:kick' && v.sounds[0].path === snd.path && v.sounds[0].bytes === 800);
  T.err('a made-up link', await su({ token: 'nope' }), 'not_found');
  await T.sql(`delete from mm_private.hits where what = 'share_urls'`);
  await cfg(T, 'share_urls_day', 3);
  for (let i = 0; i < 3; i++) await su({ token: on });
  T.err('the link is counted per day', await su({ token: on }), 'rate_limited');
  await T.sql(`delete from mm_private.hits where what = 'share_urls'`);
  const rr = await T.race([1, 2, 3, 4, 5, 6].map(() => ({ user: null, role: 'service_role', fn: 'svc_sound_urls', args: { p_user: null, p_amr: null, p_body: { token: on } } })));
  T.check('RACE six requests on one link at once with 3 left: exactly three get links', rr.filter((r) => r.ok).length === 3 && rr.filter((r) => r.err === 'rate_limited').length === 3, JSON.stringify(rr.map((r) => r.err || 'ok')));
  await cfg(T, 'share_urls_day', 300);
  T.ok('a member asking by mess is not counted against the link', await su({ mess: m }, E));

  T.section('D fork_mess');
  const fdoc = { v: 3, forked: true };
  const f1 = uuid();
  T.err('with a view link', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'Mine', p_doc: fdoc }), 'no_copy');
  T.err('with a made-up link', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: 'x', p_name: 'Mine', p_doc: fdoc }), 'not_found');
  T.err('a stranger naming the mess, no link', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: m, p_token: null, p_name: 'Mine', p_doc: fdoc }), 'not_found');
  await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'copy' });
  const fk = await T.rpc(S, 'fork_mess', { p_id: f1, p_from: null, p_token: on, p_name: 'My copy', p_doc: fdoc });
  T.ok('with a copy link: a new mess of my own at rev 0', fk, (v) => v.id === f1 && v.rev === 0 && Object.keys(v).length === 2);
  const fo = (await T.rpc(S, 'open_mess', { p_mess: f1 })).ok;
  T.check('it is mine, with the doc I sent and the source\'s sound rows', fo.role === 'owner' && fo.doc.forked === true && fo.sounds.length === 1 && fo.sounds[0].sha256 === snd.sha256 && fo.sounds[0].owner === O.id);
  T.check('forked_from is set', await T.val('select forked_from from public.messes where id = $1', [f1]) === m);
  T.ok('the copy\'s sounds can be fetched by its new owner', await su({ mess: f1 }, S), (v) => v.sounds.length === 1);
  T.ok('the same id again is a safe retry', await T.rpc(S, 'fork_mess', { p_id: f1, p_from: null, p_token: on, p_name: 'My copy', p_doc: fdoc }), (v) => v.id === f1);
  T.err('someone else\'s id', await T.rpc(E, 'fork_mess', { p_id: f1, p_from: m, p_token: null, p_name: 'X', p_doc: fdoc }), 'bad_input');
  T.ok('a member forks with no link', await T.rpc(E, 'fork_mess', { p_id: uuid(), p_from: m, p_token: null, p_name: 'Edie copy', p_doc: fdoc }));
  T.err('a bad name', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'rape', p_doc: fdoc }), 'bad_name');
  T.err('a doc that is not an object', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'X', p_doc: [1] }), 'bad_input');
  T.err('a doc over 1 MB', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'X', p_doc: { pad: 'x'.repeat(1048576) } }), 'too_big');
  await cfg(T, 'docs_limit', 30);
  T.err('over my docs limit', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'X', p_doc: fdoc }), 'docs_full');
  await cfg(T, 'docs_limit', 31457280);
  const have = await T.val('select count(*)::int from public.messes where owner_id = $1', [S.id]);
  await cfg(T, 'mess_limit', have + 1);
  const fr = await T.race([
    { user: S, fn: 'fork_mess', args: { p_id: uuid(), p_from: null, p_token: on, p_name: 'R1', p_doc: fdoc } },
    { user: S, fn: 'create_mess', args: { p_id: uuid(), p_name: 'R2', p_doc: fdoc } },
    { user: S, fn: 'fork_mess', args: { p_id: uuid(), p_from: null, p_token: on, p_name: 'R3', p_doc: fdoc } }]);
  T.check('RACE a fork and a create at once with one place left: exactly one is made', fr.filter((r) => r.ok).length === 1 && fr.filter((r) => r.err === 'too_many_messes').length === 2, JSON.stringify(fr.map((r) => r.err || 'ok')));
  T.err('over the mess limit', await T.rpc(S, 'fork_mess', { p_id: uuid(), p_from: null, p_token: on, p_name: 'X', p_doc: fdoc }), 'too_many_messes');
  await cfg(T, 'mess_limit', 200);

  T.section('D versions, get_version, restore_version');
  const h = await T.mess(O, 'History');
  await T.join(O, h, E);
  const save = async (doc, base, label = '') => (await T.rpc(O, 'put_doc', { p_mess: h, p_doc: doc, p_base_rev: base, p_label: label })).ok;
  const s1 = await save({ v: 3, take: 1 }, 0, 'First');
  await save({ v: 3, take: 2 }, 1, 'Second');
  const vs = await T.rpc(E, 'versions', { p_mess: h });
  T.ok('versions: newest first, each with exactly id, rev, label, by_name, at, bytes', vs, (v) => v.length === 2 && v[0].label === 'Second' && v[1].label === 'First'
    && Object.keys(v[0]).sort().join() === 'at,by_name,bytes,id,label,rev' && v[0].by_name === 'Dora' && v[1].rev === 1 && v[0].bytes > 0);
  T.err('versions as a stranger', await T.rpc(S, 'versions', { p_mess: h }), 'not_found');
  const gv = await T.rpc(E, 'get_version', { p_version: s1.version_id });
  T.ok('get_version: exactly id, rev, label, doc', gv, (v) => v.id === s1.version_id && v.rev === 1 && v.label === 'First' && v.doc.take === 1 && Object.keys(v).length === 4);
  T.err('get_version as a stranger', await T.rpc(S, 'get_version', { p_version: s1.version_id }), 'not_found');
  T.err('get_version of nothing', await T.rpc(E, 'get_version', { p_version: uuid() }), 'not_found');
  const other = await T.mess(O, 'Elsewhere');
  const ov = (await T.rpc(O, 'put_doc', { p_mess: other, p_doc: { v: 3, other: true }, p_base_rev: 0, p_label: 'x' })).ok.version_id;
  T.err('restoring a version of another mess into this one (Review A S8)', await T.rpc(O, 'restore_version', { p_mess: h, p_version: ov }), 'not_found');
  T.err('restore as a stranger', await T.rpc(S, 'restore_version', { p_mess: h, p_version: s1.version_id }), 'not_found');
  await T.rpc(E, 'apply_ops', { p_mess: h, p_batch: uuid(), p_ops: [{ t: 'song.set', b: 1, v: 1 }], p_v: 1 });
  T.err('restore while ops wait above the snapshot', await T.rpc(E, 'restore_version', { p_mess: h, p_version: s1.version_id }), 'snapshot_first');
  await T.rpc(E, 'save_snapshot', { p_mess: h, p_doc: { v: 3, take: 3 }, p_doc_rev: 3, p_v: 1 });
  await T.sql('update public.messes set blocked = true where id = $1', [h]);
  T.err('restore on a taken-down mess (owner)', await T.rpc(O, 'restore_version', { p_mess: h, p_version: s1.version_id }), 'blocked');
  await T.sql('update public.messes set blocked = false where id = $1', [h]);
  await T.sql('delete from realtime.messages');
  const rs = await T.rpc(E, 'restore_version', { p_mess: h, p_version: s1.version_id });
  T.ok('an editor restores: rev 4', rs, (v) => v.rev === 4 && Object.keys(v).length === 1);
  const ho = (await T.rpc(O, 'open_mess', { p_mess: h })).ok;
  T.check('the doc is the old one, and rev and doc_rev moved together', ho.doc.take === 1 && ho.rev === 4 && ho.doc_rev === 4 && ho.ops.length === 0);
  T.check('the mess before the restore was kept as "Before restore"', (await T.rpc(O, 'versions', { p_mess: h })).ok.some((v) => v.label === 'Before restore' && v.rev === 3));
  T.check('[{"t":"doc.reset"}] was stored and broadcast', (await T.one('select ops from public.mess_ops where mess_id = $1 and rev = 4', [h])).ops[0].t === 'doc.reset'
    && (await T.broadcasts(h)).some((b) => b.event === 'ops' && b.payload.rev === 4 && b.payload.ops[0].t === 'doc.reset'));
}
