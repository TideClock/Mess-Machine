// The table-driven check the rulings ask for (Review A S9): every function that takes an id (a mess, a
// person, an invite, a version, a report, a submission or a sound hash) is called by a stranger with the
// real ids of somebody else's things. Each must answer with an error and change nothing at all.
import { uuid } from './lib.mjs';
import { CONTRACT } from './00-catalogue.test.mjs';

export default async function (T) {
  // somebody else's world, with one of everything
  const O = await T.user('Owner');
  const E = await T.user('Editor');
  const S = await T.user('Stranger');
  const A = await T.user('Admin2');
  await T.sql('insert into public.admins (user_id) values ($1)', [A.id]);
  const m = await T.mess(O, 'Private');
  await T.join(O, m, E);
  const snd = await T.sound(O, 'private sound');
  await T.rpc(O, 'attach_sound', { p_mess: m, p_key: 'bn:p', p_owner: O.id, p_sha256: snd.sha256 });
  const version = (await T.rpc(O, 'put_doc', { p_mess: m, p_doc: { v: 3 }, p_base_rev: 0, p_label: 'v' })).ok.version_id;
  const invite = (await T.rpc(O, 'make_invite', { p_mess: m })).ok.id;
  const report = (await T.rpc(E, 'report_mess', { p_mess: m, p_token: null, p_reason: 'spam', p_details: '', p_user: null })).ok.id;
  const grant = (await T.rpc(null, 'vault_grant')).ok.version;
  const submission = (await T.rpc(O, 'vault_submit', { p_sha256: snd.sha256, p_credit_name: 'Owner', p_grant_version: grant })).ok.id;
  const rev = Number(await T.val('select rev from public.messes where id = $1', [m]));
  // a mess of the stranger's own, so "attach your sound to my mess" style calls have somewhere to aim
  const mine = await T.mess(S, 'Stranger own');

  // what each argument is set to. Ids are the other person's; everything else is a value that would work.
  const value = (fn, name, type) => {
    switch (name) {
      case 'p_mess': return fn === 'attach_sound' ? mine : m;
      case 'p_id': return ['create_mess', 'fork_mess'].includes(fn) ? m : fn.startsWith('admin_') ? report : submission;
      case 'p_from': return type === 'bigint' ? 0 : m;
      case 'p_version': return version;
      case 'p_invite': return invite;
      case 'p_user': return fn === 'remove_member' ? E.id : E.id;
      case 'p_owner': return O.id;
      case 'p_sha256': return snd.sha256;
      case 'p_token': return null;
      case 'p_batch': return uuid();
      case 'p_ops': return [{ t: 'song.set', b: 0, v: 1 }];
      case 'p_doc': return { v: 3, hijacked: true };
      case 'p_v': return 1;
      case 'p_base_rev': case 'p_doc_rev': return rev;
      case 'p_name': return 'Hijacked';
      case 'p_key': return 'bn:stolen';
      case 'p_mode': return 'copy';
      case 'p_new': case 'p_block': case 'p_blocked': case 'p_banned': return true;
      case 'p_days': return 7;
      case 'p_reason': return 'spam';
      case 'p_action': return 'block_mess';
      case 'p_decision': return 'approved';
      case 'p_credit_name': return 'Thief';
      case 'p_grant_version': return grant;
      case 'p_status': return 'open';
      case 'p_label': return 'Stolen';
      default: return name.includes('note') || name.includes('details') ? 'x' : null;
    }
  };
  // Answer ok by the contract: block_user (anyone may block anyone, Review A S7), and unblock_user and
  // leave_live, which only ever touch the caller's own rows. The last two are checked to change nothing.
  const allowed = new Set(['block_user', 'unblock_user', 'leave_live']);
  const idArgs = /\b(p_mess|p_id|p_from|p_version|p_invite|p_user|p_owner|p_sha256) (uuid|text)/;

  T.section('a stranger with somebody else\'s ids');
  const before = await T.fingerprint();
  let n = 0;
  for (const [fn, want] of Object.entries(CONTRACT)) {
    if (want.who === 'service' || allowed.has(fn) || !idArgs.test(want.args)) continue;
    const args = {};
    for (const part of want.args.split(', ')) {
      const [name, type] = part.split(' ');
      args[name] = value(fn, name, type);
    }
    for (const who of want.who === 'anon' ? [S, null] : [S]) {
      const r = await T.rpc(who, fn, args);
      n++;
      T.check(`${fn} as ${who ? 'a stranger' : 'anon'} answers an error`, !!r.err && r.sqlstate === 'P0001', r.err ? r.err : 'it worked: ' + JSON.stringify(r.ok).slice(0, 120));
    }
  }
  T.check(`all ${n} refused calls together changed nothing anywhere`, before === await T.fingerprint());
  T.ok('leave_live on somebody else\'s mess answers ok', await T.rpc(S, 'leave_live', { p_mess: m }));
  T.ok('unblock_user of somebody who was never blocked answers ok', await T.rpc(S, 'unblock_user', { p_user: O.id }));
  T.check('and those two changed nothing either', before === await T.fingerprint());
  T.check('the table covered at least 35 calls', n >= 35, String(n));

  T.section('the same ids, the same calls, from a removed editor');
  await T.rpc(O, 'remove_member', { p_mess: m, p_user: E.id });
  const before2 = await T.fingerprint();
  let n2 = 0;
  for (const fn of ['open_mess', 'put_doc', 'apply_ops', 'save_snapshot', 'ops_since', 'members', 'attach_sound', 'versions', 'get_version', 'restore_version', 'join_live', 'leave_mess', 'fork_mess', 'report_mess']) {
    const args = {};
    for (const part of CONTRACT[fn].args.split(', ')) { const [name, type] = part.split(' '); args[name] = value(fn, name, type); }
    if (fn === 'attach_sound') args.p_mess = m;
    const r = await T.rpc(E, fn, args);
    n2++;
    T.check(`${fn} as someone who was removed answers an error`, !!r.err && r.sqlstate === 'P0001', r.err || 'it worked');
  }
  T.check(`those ${n2} calls changed nothing`, before2 === await T.fingerprint());
}
