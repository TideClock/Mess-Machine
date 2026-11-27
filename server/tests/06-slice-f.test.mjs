// Slice F: the Vault (ACCOUNTS-API.md 1.9), and sound-urls with {"submission"}.
import { uuid, sha } from './lib.mjs';

const svc = { role: 'service_role' };

export default async function (T) {
  const U = await T.user('Vic');
  const M = await T.user('Mate');
  const S = await T.user('Sol');
  const A = await T.user('VaultAdmin');
  await T.sql('insert into public.admins (user_id) values ($1)', [A.id]);
  const s1 = await T.sound(U, 'vault one', { bytes: 1234 });
  const s2 = await T.sound(U, 'vault two');
  const sp = await T.sound(U, 'vault pending', { finish: false });
  const sub = (u, h, over = {}) => T.rpc(u, 'vault_submit', { p_sha256: h, p_credit_name: 'Vic V', p_grant_version: '2026-11', p_note: '', ...over });

  T.section('F vault_grant');
  const g = await T.rpc(null, 'vault_grant');
  T.ok('anon reads the newest grant: version, body, and the sha256 of the body', g, (v) => v.version === '2026-11' && v.body.length > 100 && v.body_sha256 === sha(v.body) && Object.keys(v).length === 3);
  T.check('the grant text says a waiting sound is withdrawn when its maker leaves', /withdrawn and deleted/.test(g.ok.body));

  T.section('F vault_submit');
  T.err('a sound still uploading', await sub(U, sp.sha256), 'not_ready');
  T.err('a sound I do not have', await sub(U, sha('none')), 'not_ready');
  T.err('someone else\'s sound', await sub(S, s1.sha256), 'not_ready');
  T.err('an old grant', await sub(U, s1.sha256, { p_grant_version: '2025-01' }), 'grant_outdated');
  T.err('no credit name', await sub(U, s1.sha256, { p_credit_name: '  ' }), 'bad_name');
  T.err('41 characters of credit', await sub(U, s1.sha256, { p_credit_name: 'c'.repeat(41) }), 'bad_name');
  T.err('a blocked word as credit', await sub(U, s1.sha256, { p_credit_name: 'The Retard' }), 'bad_name');
  T.err('a note over 200 characters', await sub(U, s1.sha256, { p_note: 'n'.repeat(201) }), 'bad_input');
  const v1 = await sub(U, s1.sha256, { p_note: 'my best kick' });
  T.ok('submit: an id, pending', v1, (v) => v.id && v.status === 'pending' && Object.keys(v).length === 2);
  const row = await T.one('select * from public.vault_submissions where id = $1', [v1.ok.id]);
  T.check('the file\'s facts are copied into the submission', Number(row.bytes) === 1234 && row.rate === 44100 && row.kind === 'imp' && row.title === 'vault one' && row.credit_name === 'Vic V');
  T.err('the same sound again while it waits', await sub(U, s1.sha256), 'already_submitted');
  T.ok('my_sounds shows the submission\'s state', await T.rpc(U, 'my_sounds'), (v) => v.find((x) => x.sha256 === s1.sha256).vault === 'pending');
  for (let i = 0; i < 9; i++) { const x = await T.sound(U, 'many ' + i); await sub(U, x.sha256); }
  const extra = [await T.sound(U, 'extra a'), await T.sound(U, 'extra b')];
  T.err('an eleventh waiting submission', await sub(U, extra[0].sha256), 'too_many_pending');
  await T.sql(`delete from public.vault_submissions where submitter_id = $1 and title like 'many 8'`, [U.id]);
  const vr = await T.race(extra.map((x) => ({ user: U, fn: 'vault_submit', args: { p_sha256: x.sha256, p_credit_name: 'Vic V', p_grant_version: '2026-11', p_note: '' } })));
  T.check('RACE 9 waiting, two more at once: exactly one gets the tenth place', vr.filter((r) => r.ok).length === 1 && vr.filter((r) => r.err === 'too_many_pending').length === 1, JSON.stringify(vr.map((r) => r.err || 'ok')));

  T.section('F vault_mine and vault_withdraw');
  const mine = await T.rpc(U, 'vault_mine');
  T.ok('vault_mine: mine, newest first, with exactly its fields', mine, (v) => v.length === 10 && Object.keys(v[0]).sort().join() === 'credit_name,id,reviewed_at,reviewer_note,sha256,status,submitted_at,title' && v[0].reviewed_at === null);
  T.ok('a stranger sees none', await T.rpc(S, 'vault_mine'), (v) => v.length === 0);
  const w = mine.ok.find((x) => x.title === 'many 0').id;
  T.err('withdrawing someone else\'s', await T.rpc(S, 'vault_withdraw', { p_id: w }), 'not_found');
  T.ok('withdraw my own waiting one', await T.rpc(U, 'vault_withdraw', { p_id: w }), (v) => v.status === 'withdrawn');
  T.err('withdrawing it twice', await T.rpc(U, 'vault_withdraw', { p_id: w }), 'not_found');
  T.err('withdrawing nothing', await T.rpc(U, 'vault_withdraw', { p_id: uuid() }), 'not_found');

  T.section('F vault_queue and vault_review');
  // a mess-mate sends up the same file and submits it too: the queue must show both owners (Review A S5)
  const room = await T.mess(U, 'Vault room');
  await T.join(U, room, M);
  await T.rpc(U, 'attach_sound', { p_mess: room, p_key: 'bn:v1', p_owner: U.id, p_sha256: s1.sha256 });
  const copy = await T.sound(M, 'vault one', { bytes: 1234 });
  T.ok('a second owner of the same bytes may also submit', await sub(M, copy.sha256, { p_credit_name: 'Mate' }));
  T.err('vault_queue as a member', await T.rpc(U, 'vault_queue'), 'forbidden');
  const q = await T.rpc(A, 'vault_queue');
  T.ok('vault_queue: pending, oldest first, every field', q, (v) => v.length === 10 && v[0].id === v1.ok.id && Object.keys(v[0]).sort().join() ===
    'beats,bpm,bytes,credit_name,grant_version,id,kind,mkey,note,others,rate,seconds,sha256,submitted_at,submitter_id,submitter_name,title' && v[0].submitter_name === 'Vic' && v[0].note === 'my best kick');
  T.ok('it lists the other owner of the same sound, with a date', q, (v) => v[0].others.length === 1 && v[0].others[0].owner === M.id && typeof v[0].others[0].at === 'string');
  T.ok('the withdrawn one is under withdrawn', await T.rpc(A, 'vault_queue', { p_status: 'withdrawn' }), (v) => v.length === 1 && v[0].id === w);
  const su = (body, u) => T.rpc(null, 'svc_sound_urls', { p_user: u.id, p_amr: null, p_body: body }, svc);
  T.ok('sound-urls {"submission"} lets the admin listen to the submitter\'s file', await su({ submission: v1.ok.id }, A), (v) => v.sounds.length === 1 && v.sounds[0].path === s1.path && v.sounds[0].bucket === 'sounds');
  T.err('nobody else', await su({ submission: v1.ok.id }, U), 'not_found');
  T.err('vault_review as a member', await T.rpc(U, 'vault_review', { p_id: v1.ok.id, p_decision: 'approved' }), 'forbidden');
  T.err('a submission that does not exist', await T.rpc(A, 'vault_review', { p_id: uuid(), p_decision: 'approved' }), 'not_found');
  T.err('an unknown decision', await T.rpc(A, 'vault_review', { p_id: v1.ok.id, p_decision: 'maybe' }), 'bad_input');
  T.err('approve before the file is copied to the vault', await T.rpc(A, 'vault_review', { p_id: v1.ok.id, p_decision: 'approved' }), 'not_copied');
  await T.put('vault', `${s1.sha256}.wav`, 999);
  T.err('approve when the copy is the wrong size', await T.rpc(A, 'vault_review', { p_id: v1.ok.id, p_decision: 'approved' }), 'not_copied');
  const ins = (u) => T.as(u, `insert into storage.objects (bucket_id, name) values ('vault', $1)`, [`${s2.sha256}.wav`]);
  T.check('only an admin may put a file in the vault bucket', (await ins(U)).sqlstate === '42501' && (await ins(A)).count === 1);
  T.check('an admin may read the vault bucket; nobody else', (await T.as(A, `select name from storage.objects where bucket_id = 'vault'`)).count >= 1
    && (await T.as(U, `select name from storage.objects where bucket_id = 'vault'`)).count === 0);
  await T.put('vault', `${s1.sha256}.wav`, 1234);
  T.ok('approve once the copy is right', await T.rpc(A, 'vault_review', { p_id: v1.ok.id, p_decision: 'approved', p_note: 'great' }), (v) => v.status === 'approved' && Object.keys(v).length === 1);
  const vs = await T.one('select * from public.vault_sounds where sha256 = $1', [s1.sha256]);
  T.check('the grant is frozen into vault_sounds: version, body hash, when, who', vs.grant_version === '2026-11' && vs.body_sha256 === sha(g.ok.body) && vs.granted_at && vs.granter === U.id && vs.approved_by === A.id && vs.credit_name === 'Vic V');
  T.err('reviewing it again', await T.rpc(A, 'vault_review', { p_id: v1.ok.id, p_decision: 'declined' }), 'already_reviewed');
  T.err('withdrawing an approved one', await T.rpc(U, 'vault_withdraw', { p_id: v1.ok.id }), 'not_found');
  T.err('submitting a sound already in the Vault, from anyone', await sub(M, copy.sha256, { p_credit_name: 'Mate again' }).then(async (r) => {
    const mates = (await T.rpc(A, 'vault_queue')).ok.find((x) => x.submitter_id === M.id);
    await T.rpc(A, 'vault_review', { p_id: mates.id, p_decision: 'declined' });
    return sub(M, copy.sha256, { p_credit_name: 'Mate again' });
  }), 'already_submitted');
  T.ok('vault_mine shows the review', await T.rpc(U, 'vault_mine'), (v) => { const x = v.find((y) => y.id === v1.ok.id); return x.status === 'approved' && x.reviewer_note === 'great' && x.reviewed_at; });
  const dec = (await T.rpc(A, 'vault_queue')).ok[0].id;
  T.ok('decline', await T.rpc(A, 'vault_review', { p_id: dec, p_decision: 'declined', p_note: 'too quiet' }), (v) => v.status === 'declined');
  T.err('after a decline the files are closed to the admin', await su({ submission: dec }, A), 'not_found');

  T.section('F an approved sound outlives its maker');
  await T.rpc(U, 'delete_sound', { p_sha256: s1.sha256 });
  const after = (await T.rpc(M, 'open_mess', { p_mess: room })).ok.sounds.find((x) => x.key === 'bn:v1');
  T.check('when its maker deletes the sound, the mess switches to the Vault copy', after.src === 'vault' && after.owner === null && after.title === 'vault one' && after.bytes === 1234);
  T.ok('and it still plays, from the vault bucket', await su({ mess: room }, M), (v) => v.sounds.some((x) => x.key === 'bn:v1' && x.bucket === 'vault' && x.path === `${s1.sha256}.wav`));
  T.ok('a pending submission of a deleted sound is withdrawn', await (async () => {
    const z = await T.sound(U, 'withdraw me'); const id = (await sub(U, z.sha256)).ok?.id;
    await T.rpc(U, 'delete_sound', { p_sha256: z.sha256 });
    return { ok: await T.val('select status from public.vault_submissions where id = $1', [id]) };
  })(), (v) => v === 'withdrawn');
}
