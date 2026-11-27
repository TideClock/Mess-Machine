// Slice E: store requirements (ACCOUNTS-API.md 1.8): reports, admin calls, deleting an account, the sweep.
import { uuid, sha } from './lib.mjs';

const svc = { role: 'service_role' };
const freshAmr = () => [{ method: 'otp', timestamp: Math.floor(Date.now() / 1000) - 60 }];

export default async function (T) {
  const O = await T.user('Opal');
  const E = await T.user('Ezra');
  const E2 = await T.user('Ezzy');
  const S = await T.user('Seth');
  const A = await T.user('Admin');
  await T.sql('insert into public.admins (user_id) values ($1)', [A.id]);
  const m = await T.mess(O, 'Reported jam');
  await T.join(O, m, E); await T.join(O, m, E2);
  const link = (await T.rpc(O, 'set_share', { p_mess: m, p_mode: 'view' })).ok.share_token;
  const rep = (u, args) => T.rpc(u, 'report_mess', { p_mess: null, p_token: null, p_reason: 'spam', p_details: '', p_user: null, ...args });

  T.section('E report_mess');
  const r1 = await rep(E, { p_mess: m, p_reason: 'hate', p_details: 'bad words in the name' });
  T.ok('a member reports the mess: an id', r1, (v) => typeof v.id === 'string' && Object.keys(v).length === 1);
  const row = await T.one('select * from public.reports where id = $1', [r1.ok.id]);
  T.check('the report keeps the mess name and owner, who sent it, not by link', row.mess_name === 'Reported jam' && row.owner_id === O.id && row.reporter_id === E.id && row.via_token === false && row.about_user === null && row.status === 'open');
  T.ok('a member reports another member', await rep(E, { p_mess: m, p_reason: 'harassment', p_user: E2.id }), (v) => v.id);
  T.check('stored as about_user', await T.val(`select count(*)::int from public.reports where about_user = $1`, [E2.id]) === 1);
  T.ok('the owner may report a member of their mess', await rep(O, { p_mess: m, p_reason: 'harassment', p_user: E.id }));
  T.err('the owner reporting their own mess', await rep(O, { p_mess: m }), 'bad_input');
  T.err('a member reporting a person who is not in the mess', await rep(E, { p_mess: m, p_user: S.id }), 'not_found');
  T.err('a member reporting themselves', await rep(E, { p_mess: m, p_user: E.id }), 'not_found');
  T.err('an unknown reason', await rep(E, { p_mess: m, p_reason: 'boring' }), 'bad_input');
  T.err('no reason', await rep(E, { p_mess: m, p_reason: null }), 'bad_input');
  T.err('501 characters of details', await rep(E, { p_mess: m, p_details: 'd'.repeat(501) }), 'bad_input');
  T.err('a stranger naming the mess with no link', await rep(S, { p_mess: m }), 'not_found');
  T.err('anon naming the mess with no link', await rep(null, { p_mess: m }), 'not_found');
  await T.sql(`delete from mm_private.hits where what = 'report'`);
  const five = [];
  for (let i = 0; i < 6; i++) five.push(await rep(E2, { p_mess: m, p_reason: 'other' }));
  T.check('five reports a day from one person pass', five.slice(0, 5).every((r) => r.ok));
  T.err('the sixth', five[5], 'rate_limited');
  await T.sql(`delete from mm_private.hits where what = 'report'`);
  const rr = await T.race([1, 2, 3, 4, 5, 6, 7].map(() => ({ user: E2, fn: 'report_mess', args: { p_mess: m, p_token: null, p_reason: 'spam', p_details: '', p_user: null } })));
  T.check('RACE seven reports at once from one person: exactly five stored', rr.filter((r) => r.ok).length === 5 && rr.filter((r) => r.err === 'rate_limited').length === 2, JSON.stringify(rr.map((r) => r.err || 'ok')));
  // the share page: no mess id, only the token (asked for by the web pages)
  const byLink = await rep(null, { p_mess: null, p_token: link, p_reason: 'copyright' });
  T.ok('anon reports by share link with p_mess null', byLink, (v) => v.id);
  T.check('stored against the right mess, by link, no reporter', (await T.one('select mess_id, via_token, reporter_id from public.reports where id = $1', [byLink.ok.id])).mess_id === m
    && (await T.one('select via_token from public.reports where id = $1', [byLink.ok.id])).via_token === true);
  T.ok('a signed-in stranger reports by link too', await rep(S, { p_token: link }));
  T.ok('a link report may also name the mess', await rep(null, { p_mess: m, p_token: link }));
  T.err('a bad link with p_mess null', await rep(null, { p_token: 'not-a-real-link' }), 'not_found');
  T.err('neither a mess nor a link', await rep(null, {}), 'bad_input');
  T.err('neither, signed in', await rep(S, { p_token: '' }), 'bad_input');
  T.err('a link report about a person', await rep(null, { p_token: link, p_user: O.id }), 'bad_input');
  await T.sql(`delete from mm_private.hits where what = 'report_link'`);
  const lr = await T.race(Array.from({ length: 24 }, () => ({ user: null, fn: 'report_mess', args: { p_mess: null, p_token: link, p_reason: 'spam', p_details: '', p_user: null } })));
  T.check('RACE 24 link reports at once: exactly 20 a day per mess', lr.filter((r) => r.ok).length === 20 && lr.filter((r) => r.err === 'rate_limited').length === 4, JSON.stringify(lr.map((r) => r.err || 'ok')));
  T.err('the 21st', await rep(S, { p_token: link }), 'rate_limited');

  T.section('E admin reports');
  T.err('admin_reports as a member', await T.rpc(E, 'admin_reports'), 'forbidden');
  const ar = await T.rpc(A, 'admin_reports');
  T.ok('admin_reports: the open ones, oldest first, with exactly their fields', ar, (v) => v.length > 10 && v.every((x, i) => i === 0 || v[i - 1].at <= x.at) && v[0].id === r1.ok.id
    && Object.keys(v[0]).sort().join() === 'about_user,at,details,id,mess_id,mess_name,owner_id,reason,status,via_token');
  T.ok('none dismissed yet', await T.rpc(A, 'admin_reports', { p_status: 'dismissed' }), (v) => v.length === 0);
  T.err('admin_open_report as a member', await T.rpc(E, 'admin_open_report', { p_id: r1.ok.id }), 'forbidden');
  T.err('admin_open_report of nothing', await T.rpc(A, 'admin_open_report', { p_id: uuid() }), 'not_found');
  const snd = await T.sound(O, 'reported sound');
  await T.rpc(O, 'attach_sound', { p_mess: m, p_key: 'bn:r', p_owner: O.id, p_sha256: snd.sha256 });
  T.ok('admin_open_report: the report, the doc and the sounds', await T.rpc(A, 'admin_open_report', { p_id: r1.ok.id }), (v) => v.report.id === r1.ok.id && v.doc && v.sounds.length === 1 && Object.keys(v).sort().join() === 'doc,report,sounds');
  const su = (body, u) => T.rpc(null, 'svc_sound_urls', { p_user: u.id, p_amr: null, p_body: body }, svc);
  T.ok('sound-urls {"report"} gives the admin the files of an open report', await su({ report: r1.ok.id }, A), (v) => v.sounds.length === 1);
  T.err('but not to anyone else', await su({ report: r1.ok.id }, E), 'not_found');
  T.err('an admin may not read a private mess with no report (Review A S4)', await su({ mess: m }, A), 'not_found');
  T.err('admin_resolve_report as a member', await T.rpc(E, 'admin_resolve_report', { p_id: r1.ok.id, p_action: 'dismiss' }), 'forbidden');
  T.err('an unknown action', await T.rpc(A, 'admin_resolve_report', { p_id: r1.ok.id, p_action: 'delete' }), 'bad_input');
  T.err('a report that does not exist', await T.rpc(A, 'admin_resolve_report', { p_id: uuid(), p_action: 'dismiss' }), 'not_found');
  const others = (await T.rpc(A, 'admin_reports')).ok;
  T.ok('dismiss', await T.rpc(A, 'admin_resolve_report', { p_id: others[1].id, p_action: 'dismiss', p_note: 'fine' }), (v) => v.status === 'dismissed');
  T.err('resolving it again', await T.rpc(A, 'admin_resolve_report', { p_id: others[1].id, p_action: 'dismiss' }), 'not_found');
  T.err('admin_open_report of a closed report', await T.rpc(A, 'admin_open_report', { p_id: others[1].id }), 'not_found');
  T.err('and its files are closed to the admin again', await su({ report: others[1].id }, A), 'not_found');
  const topic0 = (await T.rpc(O, 'open_mess', { p_mess: m })).ok.topic;
  await T.sql('delete from realtime.messages');
  T.ok('block_mess: actioned', await T.rpc(A, 'admin_resolve_report', { p_id: r1.ok.id, p_action: 'block_mess' }), (v) => v.status === 'actioned');
  const bo = (await T.rpc(O, 'open_mess', { p_mess: m })).ok;
  T.check('the mess is down: blocked, no share link, a new channel key', bo.share_mode === 'off' && bo.share_token === null && bo.topic !== topic0
    && await T.val('select blocked from public.messes where id = $1', [m]) === true);
  T.check('the channel was told {kind:"blocked"} on the old topic', (await T.broadcasts(m)).some((b) => b.topic === topic0 && b.payload.kind === 'blocked'));
  T.err('members now read not_found', await T.rpc(E, 'open_mess', { p_mess: m }), 'not_found');
  T.ok('and it leaves their list', await T.rpc(E, 'my_messes'), (v) => !v.some((r) => r.id === m));
  T.ok('the owner still sees it, marked blocked', await T.rpc(O, 'my_messes'), (v) => v.find((r) => r.id === m).blocked === true);
  T.err('the old share link is dead', await T.rpc(null, 'open_shared', { p_token: link }), 'not_found');
  T.ok('the dismissed list holds the dismissed one', await T.rpc(A, 'admin_reports', { p_status: 'dismissed' }), (v) => v.length === 1);

  T.section('E admin_block_mess, admin_ban_user, admin_block_sound');
  T.err('admin_block_mess as a member', await T.rpc(O, 'admin_block_mess', { p_mess: m, p_blocked: false }), 'forbidden');
  T.err('admin_block_mess of nothing', await T.rpc(A, 'admin_block_mess', { p_mess: uuid(), p_blocked: true }), 'not_found');
  T.ok('put the mess back', await T.rpc(A, 'admin_block_mess', { p_mess: m, p_blocked: false }), (v) => v.blocked === false);
  T.ok('members see it again', await T.rpc(E, 'open_mess', { p_mess: m }));
  T.ok('take it down directly', await T.rpc(A, 'admin_block_mess', { p_mess: m, p_blocked: true }), (v) => v.blocked === true);
  await T.rpc(A, 'admin_block_mess', { p_mess: m, p_blocked: false });

  const B = await T.user('Bandit');
  const bm = await T.mess(B, 'Bandit room');
  await T.join(B, bm, E);
  const bl = (await T.rpc(B, 'set_share', { p_mess: bm, p_mode: 'copy' })).ok.share_token;
  const bs = await T.sound(B, 'bandit sound');
  await T.rpc(B, 'attach_sound', { p_mess: bm, p_key: 'bn:b', p_owner: B.id, p_sha256: bs.sha256 });
  await T.join(O, m, B);
  await T.rpc(B, 'attach_sound', { p_mess: m, p_key: 'bn:bandit', p_owner: B.id, p_sha256: bs.sha256 });
  const mTopic = (await T.rpc(O, 'open_mess', { p_mess: m })).ok.topic;
  T.err('admin_ban_user as a member', await T.rpc(O, 'admin_ban_user', { p_user: B.id, p_banned: true }), 'forbidden');
  T.err('admin_ban_user of nobody', await T.rpc(A, 'admin_ban_user', { p_user: uuid(), p_banned: true }), 'not_found');
  T.ok('ban', await T.rpc(A, 'admin_ban_user', { p_user: B.id, p_banned: true }), (v) => v.banned === true);
  T.err('every call now refuses them', await T.rpc(B, 'me'), 'auth');
  T.check('their messes are taken down and their links cleared', await T.val('select blocked and share_token is null from public.messes where id = $1', [bm]) === true);
  T.err('their share link is dead', await T.rpc(null, 'open_shared', { p_token: bl }), 'not_found');
  T.check('a mess they only edit turned its key', (await T.rpc(O, 'open_mess', { p_mess: m })).ok.topic !== mTopic);
  T.ok('their files are left out for everyone else', await su({ mess: m }, O), (v) => !v.sounds.some((x) => x.sha256 === bs.sha256));
  T.ok('unban', await T.rpc(A, 'admin_ban_user', { p_user: B.id, p_banned: false }), (v) => v.banned === false);
  T.ok('they may call again', await T.rpc(B, 'me'));
  T.ok('and their files come back', await su({ mess: m }, O), (v) => v.sounds.some((x) => x.sha256 === bs.sha256));
  const Rv = await T.user('Reviewer', { method: 'password' });
  await T.sql(`update public.config set value = to_jsonb($1::text) where key = 'review_user'`, [Rv.id]);
  await T.sql('insert into public.admins (user_id) values ($1)', [Rv.id]);
  T.err('the review account cannot ban, even with an admins row', await T.rpc(Rv, 'admin_ban_user', { p_user: B.id, p_banned: true }), 'forbidden');
  await T.sql('delete from public.admins where user_id = $1', [Rv.id]);

  // a second person with the same file: a take-down must catch every copy (Review A S4)
  const C = await T.user('Copycat');
  const cm = await T.mess(C, 'Copy room');
  const cs = await T.sound(C, 'bandit sound');
  await T.rpc(C, 'attach_sound', { p_mess: cm, p_key: 'bn:copy', p_owner: C.id, p_sha256: cs.sha256 });
  T.err('admin_block_sound as a member', await T.rpc(O, 'admin_block_sound', { p_owner: B.id, p_sha256: bs.sha256 }), 'forbidden');
  T.err('admin_block_sound of a file that does not exist', await T.rpc(A, 'admin_block_sound', { p_owner: B.id, p_sha256: sha('none') }), 'not_found');
  T.ok('admin_block_sound: how many messes lost it, any owner', await T.rpc(A, 'admin_block_sound', { p_owner: B.id, p_sha256: bs.sha256 }), (v) => v.messes === 3);
  T.check('every row with that hash now reads gone', await T.val(`select count(*)::int from public.mess_sounds where sha256 = $1 and src <> 'gone'`, [bs.sha256]) === 0);
  T.ok('sound-urls offers none of them', await su({ mess: cm }, C), (v) => v.sounds.length === 0);
  T.err('the hash cannot be attached again', await T.rpc(C, 'attach_sound', { p_mess: cm, p_key: 'bn:again', p_owner: C.id, p_sha256: cs.sha256 }), 'not_found');
  const N2 = await T.user('Newcomer');
  const n2 = await T.sound(N2, 'bandit sound', { finish: false });
  T.err('even when someone new sends the same file up, it never plays', await T.rpc(N2, 'attach_sound', { p_mess: await T.mess(N2, 'N room'), p_key: 'bn:n', p_owner: N2.id, p_sha256: n2.sha256 }), 'not_found');

  T.section('E deleting an account (what the delete-account function runs)');
  const D = await T.user('Dee');
  const keep = await T.user('Kay');
  const dm = await T.mess(D, 'Dee owns');
  await T.join(D, dm, keep);
  const km = await T.mess(keep, 'Kay owns');
  await T.join(keep, km, D);
  const ds = await T.sound(D, 'dee sound');
  const dv = await T.sound(D, 'dee vault sound');
  const dp = await T.sound(D, 'dee pending', { finish: false });
  await T.rpc(D, 'attach_sound', { p_mess: km, p_key: 'bn:d1', p_owner: D.id, p_sha256: ds.sha256 });
  await T.rpc(D, 'attach_sound', { p_mess: km, p_key: 'bn:d2', p_owner: D.id, p_sha256: dv.sha256 });
  await T.rpc(D, 'attach_sound', { p_mess: km, p_key: 'bn:d3', p_owner: D.id, p_sha256: dp.sha256 });
  await T.rpc(D, 'apply_ops', { p_mess: km, p_batch: uuid(), p_ops: [{ t: 'song.set', b: 0, v: 0 }], p_v: 1 });
  await T.rpc(D, 'put_doc', { p_mess: km, p_doc: { v: 3 }, p_base_rev: 1, p_label: 'by Dee' }).catch(() => {});
  const grant = (await T.rpc(null, 'vault_grant')).ok.version;
  const sub1 = (await T.rpc(D, 'vault_submit', { p_sha256: dv.sha256, p_credit_name: 'DJ Dee', p_grant_version: grant })).ok.id;
  const sub2 = (await T.rpc(D, 'vault_submit', { p_sha256: ds.sha256, p_credit_name: 'DJ Dee', p_grant_version: grant })).ok.id;
  await T.put('vault', `${dv.sha256}.wav`, dv.bytes);
  await T.rpc(A, 'vault_review', { p_id: sub1, p_decision: 'approved' });
  await T.rpc(keep, 'report_mess', { p_mess: km, p_token: null, p_reason: 'spam', p_details: 'x', p_user: D.id });
  await T.rpc(D, 'report_mess', { p_mess: km, p_token: null, p_reason: 'spam', p_details: 'y', p_user: null });
  await T.rpc(D, 'block_user', { p_user: S.id });
  const stale = [{ method: 'otp', timestamp: Math.floor(Date.now() / 1000) - 601 }];
  T.err('a sign-in over 10 minutes old', await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: stale }, svc), 'stale_login');
  T.err('a token with no sign-in time', await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: [] }, svc), 'stale_login');
  T.err('a token whose amr is not a list', await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: { method: 'otp' } }, svc), 'stale_login');
  T.check('a refused delete changed nothing', await T.val('select count(*)::int from public.profiles where id = $1', [D.id]) === 1
    && await T.val('select count(*)::int from mm_private.deletions where user_id = $1', [D.id]) === 0);
  T.err('nobody', await T.rpc(null, 'svc_delete_account', { p_user: null, p_amr: freshAmr() }, svc), 'auth');
  T.err('a fresh password session (not the review account)', await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: [{ method: 'password', timestamp: Math.floor(Date.now() / 1000) }] }, svc), 'auth');
  T.err('someone with no profile', await T.rpc(null, 'svc_delete_account', { p_user: uuid(), p_amr: freshAmr() }, svc), 'auth');
  const Bn = await T.user('Banned leaver');
  await T.sql('update public.profiles set banned = true where id = $1', [Bn.id]);
  T.ok('a banned person may still delete their account', await T.rpc(null, 'svc_delete_account', { p_user: Bn.id, p_amr: freshAmr() }, svc), (v) => v.user === Bn.id);
  const del = await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: freshAmr() }, svc);
  T.ok('a fresh sign-in: the data goes, and the answer lists exactly the files in their folder', del, (v) => v.user === D.id
    && JSON.stringify(v.paths) === JSON.stringify([ds.path, dv.path, dp.path].sort()));
  const left = await T.val(`select string_agg(t, ',') from (
     select 'profiles' t from public.profiles where id = $1 union all select 'members' from public.mess_members where user_id = $1
     union all select 'messes' from public.messes where owner_id = $1 union all select 'files' from public.sound_files where owner_id = $1
     union all select 'mess_sounds' from public.mess_sounds where owner_id = $1 or added_by = $1 union all select 'ops' from public.mess_ops where user_id = $1
     union all select 'versions' from public.mess_versions where made_by = $1 union all select 'invites' from public.invites where made_by = $1
     union all select 'blocks' from public.user_blocks where owner_id = $1 or blocked_id = $1 union all select 'terms' from public.terms_acceptances where user_id = $1
     union all select 'live' from public.mess_live where user_id = $1 union all select 'reports' from public.reports where reporter_id = $1 or owner_id = $1 or about_user = $1
     union all select 'subs' from public.vault_submissions where submitter_id = $1 union all select 'usage' from mm_private.usage where user_id = $1) x`, [D.id]);
  T.check('no row anywhere names them any more', left === null, left);
  T.err('their own mess is gone', await T.rpc(keep, 'open_mess', { p_mess: dm }), 'not_found');
  const ks = (await T.rpc(keep, 'open_mess', { p_mess: km })).ok;
  T.check('in a mess of someone else: their plain sound reads gone, the approved one reads vault, the unfinished one gone',
    ks.sounds.find((x) => x.key === 'bn:d1').src === 'gone' && ks.sounds.find((x) => x.key === 'bn:d2').src === 'vault' && ks.sounds.find((x) => x.key === 'bn:d3').src === 'gone', JSON.stringify(ks.sounds.map((x) => x.key + ':' + x.src)));
  T.ok('the Vault copy still plays there', await su({ mess: km }, keep), (v) => v.sounds.length === 1 && v.sounds[0].bucket === 'vault' && v.sounds[0].path === `${dv.sha256}.wav`);
  T.check('the approved submission keeps its credit name and loses the person', (await T.one('select submitter_id, credit_name, status from public.vault_submissions where id = $1', [sub1])).credit_name === 'DJ Dee'
    && (await T.one('select submitter_id from public.vault_submissions where id = $1', [sub1])).submitter_id === null);
  T.check('the pending submission was withdrawn and deleted (Tyler\'s answer 2)', await T.val('select count(*)::int from public.vault_submissions where id = $1', [sub2]) === 0);
  T.check('reports keep their text', await T.val(`select count(*)::int from public.reports where details in ('x', 'y') and mess_id = $1`, [km]) === 2);
  T.check('the deletion is on record, not yet done', await T.val('select done_at is null from mm_private.deletions where user_id = $1', [D.id]) === true);
  T.ok('running it again is fine', await T.rpc(null, 'svc_delete_account', { p_user: D.id, p_amr: null }, svc), (v) => v.paths.length === 3);
  T.ok('svc_deletion_done marks it done', await T.rpc(null, 'svc_deletion_done', { p_user: D.id }, svc));
  T.check('done_at is set', await T.val('select done_at is not null from mm_private.deletions where user_id = $1', [D.id]) === true);
  T.err('after deletion their token is refused everywhere (no profile)', await T.rpc(D, 'me'), 'auth');

  T.section('E the daily sweep');
  const W = await T.user('Wes');
  const old = await T.sound(W, 'old pending', { finish: false });
  const young = await T.sound(W, 'young pending', { finish: false });
  const ready = await T.sound(W, 'ready one');
  const going = await T.sound(W, 'going');
  const wm = await T.mess(W, 'Wes room');
  await T.rpc(W, 'attach_sound', { p_mess: wm, p_key: 'bn:old', p_owner: W.id, p_sha256: old.sha256 });
  await T.rpc(W, 'delete_sound', { p_sha256: going.sha256 });
  await T.sql(`update public.sound_files set at = now() - interval '25 hours' where owner_id = $1 and sha256 = $2`, [W.id, old.sha256]);
  await T.put('sounds', `${W.id}/${'f'.repeat(64)}.wav`, 10, W.id);   // an object no row knows
  const D2 = await T.user('Half');
  const h2 = await T.sound(D2, 'half way');
  await T.rpc(null, 'svc_delete_account', { p_user: D2.id, p_amr: freshAmr() }, svc);
  await T.sql(`update mm_private.deletions set asked_at = now() - interval '1 hour' where user_id = $1`, [D2.id]);
  const sw = await T.rpc(null, 'svc_sweep', {}, svc);
  T.ok('the sweep lists what to remove: the deleting file, the orphan, the 24 hour old upload', sw, (v) => {
    const r = v.remove;
    return r.includes(going.path) && r.includes(`${W.id}/${'f'.repeat(64)}.wav`) && r.includes(old.path) && !r.includes(young.path) && !r.includes(ready.path);
  });
  T.ok('and the deletion that stopped half way, with its files', sw, (v) => v.deletions.some((d) => d.user === D2.id && d.paths.includes(h2.path)) && !v.deletions.some((d) => d.user === D.id));
  T.check('the 24 hour old upload is forgotten, and its mess reads gone', await T.val('select count(*)::int from public.sound_files where owner_id = $1 and sha256 = $2', [W.id, old.sha256]) === 0
    && (await T.rpc(W, 'open_mess', { p_mess: wm })).ok.sounds[0].src === 'gone');
  T.check('a fresh unfinished upload is left alone', await T.val('select count(*)::int from public.sound_files where owner_id = $1 and sha256 = $2', [W.id, young.sha256]) === 1);
  const used = (await T.rpc(W, 'me')).ok.sound_bytes;
  T.ok('svc_sweep_done before the objects go: nothing forgotten', await T.rpc(null, 'svc_sweep_done', {}, svc), (v) => v.forgotten === 0);
  await T.sql(`delete from storage.objects where bucket_id = 'sounds' and name = $1`, [going.path]);
  T.ok('after the objects go, the deleting file is forgotten', await T.rpc(null, 'svc_sweep_done', {}, svc), (v) => v.forgotten === 1);
  T.check('and its bytes come off the total', (await T.rpc(W, 'me')).ok.sound_bytes === used - going.bytes);
  T.check('the sweep functions are for the service key only', (await T.rpc(W, 'svc_sweep')).sqlstate === '42501' && (await T.rpc(null, 'svc_sweep')).sqlstate === '42501');
}
