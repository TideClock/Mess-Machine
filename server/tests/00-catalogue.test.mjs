// The checks that read Postgres's own catalogue (Review A S1 and S9): nothing is open by accident.
// They read pg_class and pg_proc, not a hand-kept list, so a table or function added later is covered.

// Every call of docs/ACCOUNTS-API.md section 1, with its exact arguments. who: anon, user, or service.
// T: the current terms must be accepted.
export const CONTRACT = {
  // Slice A
  me: { args: '', who: 'user' },
  set_profile: { args: 'p_display_name text', who: 'user' },
  accept_terms: { args: 'p_version text', who: 'user' },
  create_mess: { args: 'p_id uuid, p_name text, p_doc jsonb', who: 'user', T: true },
  my_messes: { args: '', who: 'user' },
  open_mess: { args: 'p_mess uuid', who: 'user' },
  put_doc: { args: "p_mess uuid, p_doc jsonb, p_base_rev bigint, p_label text DEFAULT ''::text", who: 'user', T: true },
  rename_mess: { args: 'p_mess uuid, p_name text', who: 'user', T: true },
  delete_mess: { args: 'p_mess uuid', who: 'user' },
  register_sound: { args: 'p_sha256 text, p_bytes bigint, p_seconds real, p_rate integer, p_kind text, p_local_key text, p_title text, p_bpm real, p_beats integer, p_mkey text', who: 'user', T: true },
  finish_upload: { args: 'p_sha256 text', who: 'user' },
  attach_sound: { args: 'p_mess uuid, p_key text, p_owner uuid, p_sha256 text', who: 'user', T: true },
  my_sounds: { args: '', who: 'user' },
  delete_sound: { args: 'p_sha256 text', who: 'user' },
  // Slice B
  ops_since: { args: 'p_mess uuid, p_from bigint', who: 'user' },
  apply_ops: { args: 'p_mess uuid, p_batch uuid, p_ops jsonb, p_v integer', who: 'user', T: true },
  save_snapshot: { args: "p_mess uuid, p_doc jsonb, p_doc_rev bigint, p_v integer, p_label text DEFAULT ''::text", who: 'user', T: true },
  members: { args: 'p_mess uuid', who: 'user' },
  make_invite: { args: 'p_mess uuid, p_days integer DEFAULT 7', who: 'user', T: true },
  list_invites: { args: 'p_mess uuid', who: 'user' },
  revoke_invite: { args: 'p_invite uuid', who: 'user' },
  invite_info: { args: 'p_token text', who: 'anon' },
  redeem_invite: { args: 'p_token text', who: 'user', T: true },
  remove_member: { args: 'p_mess uuid, p_user uuid, p_block boolean DEFAULT false', who: 'user' },
  leave_mess: { args: 'p_mess uuid', who: 'user' },
  block_user: { args: 'p_user uuid', who: 'user' },
  my_blocks: { args: '', who: 'user' },
  unblock_user: { args: 'p_user uuid', who: 'user' },
  join_live: { args: 'p_mess uuid', who: 'user', T: true },
  leave_live: { args: 'p_mess uuid', who: 'user' },
  // Slice D
  set_share: { args: 'p_mess uuid, p_mode text, p_new boolean DEFAULT false', who: 'user', T: true },
  open_shared: { args: 'p_token text', who: 'anon' },
  fork_mess: { args: 'p_id uuid, p_from uuid, p_token text, p_name text, p_doc jsonb', who: 'user', T: true },
  versions: { args: 'p_mess uuid', who: 'user' },
  get_version: { args: 'p_version uuid', who: 'user' },
  restore_version: { args: 'p_mess uuid, p_version uuid', who: 'user', T: true },
  // Slice E
  report_mess: { args: "p_mess uuid, p_token text, p_reason text, p_details text DEFAULT ''::text, p_user uuid DEFAULT NULL::uuid", who: 'anon' },
  admin_reports: { args: "p_status text DEFAULT 'open'::text", who: 'user' },
  admin_open_report: { args: 'p_id uuid', who: 'user' },
  admin_resolve_report: { args: "p_id uuid, p_action text, p_note text DEFAULT ''::text", who: 'user' },
  admin_block_mess: { args: 'p_mess uuid, p_blocked boolean', who: 'user' },
  admin_ban_user: { args: 'p_user uuid, p_banned boolean', who: 'user' },
  admin_block_sound: { args: 'p_owner uuid, p_sha256 text', who: 'user' },
  // Slice F
  vault_grant: { args: '', who: 'anon' },
  vault_submit: { args: "p_sha256 text, p_credit_name text, p_grant_version text, p_note text DEFAULT ''::text", who: 'user', T: true },
  vault_mine: { args: '', who: 'user' },
  vault_withdraw: { args: 'p_id uuid', who: 'user' },
  vault_queue: { args: "p_status text DEFAULT 'pending'::text", who: 'user' },
  vault_review: { args: "p_id uuid, p_decision text, p_note text DEFAULT ''::text", who: 'user' },
  // What the Edge Functions call. Not in the app's contract; the service key only.
  svc_sound_urls: { args: 'p_user uuid, p_amr jsonb, p_body jsonb', who: 'service' },
  svc_delete_account: { args: 'p_user uuid, p_amr jsonb', who: 'service' },
  svc_deletion_done: { args: 'p_user uuid', who: 'service' },
  svc_sweep: { args: '', who: 'service' },
  svc_sweep_done: { args: '', who: 'service' },
};

const CONFIG = {
  terms_version: '2026-11', sound_limit: 262144000, mess_limit: 200, member_limit: 8, live_limit: 4,
  mess_bytes: 1048576, sound_seconds: 100, sound_bytes: 20971520, docs_limit: 31457280, ops_v_max: 1,
  ops_day_bytes: 20971520, review_user: null,
};

export default async function (T) {
  T.section('catalogue: tables');
  const tables = await T.sql(
    `select n.nspname as s, c.relname as t, c.relrowsecurity as rls,
            (select a.attname from pg_attribute a where a.attrelid = c.oid and a.attnum = 1) as col,
            (select count(*)::int from pg_policy p where p.polrelid = c.oid) as policies,
            (select coalesce(string_agg(r || ':' || p, ' '), '') from unnest(array['anon', 'authenticated']) r,
               unnest(array['select', 'insert', 'update', 'delete', 'truncate', 'references', 'trigger']) p
             where has_table_privilege(r, c.oid, p)) as grants
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where c.relkind in ('r', 'p', 'v', 'm') and n.nspname in ('public', 'mm_private', 'mm_policy') order by 1, 2`);
  T.check('there are tables to check (17 in public)', tables.filter((x) => x.s === 'public').length === 17,
    `public has ${tables.filter((x) => x.s === 'public').length}`);
  for (const x of tables) {
    T.check(`${x.s}.${x.t} has row level security on`, x.rls === true);
    T.check(`${x.s}.${x.t} has no policy at all`, x.policies === 0, `${x.policies} policies`);
    T.check(`${x.s}.${x.t} gives the client roles no privilege`, x.grants === '', x.grants);
  }
  const seqs = await T.val(
    `select count(*)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where c.relkind = 'S' and n.nspname in ('public', 'mm_private')
       and (has_sequence_privilege('anon', c.oid, 'usage') or has_sequence_privilege('authenticated', c.oid, 'usage'))`);
  T.check('no sequence is open to a client role', seqs === 0);

  // the same thing proved by trying it, for every table and both client roles
  const someone = await T.user('prober');
  for (const x of tables) {
    for (const who of [null, someone]) {
      const role = who ? 'authenticated' : 'anon';
      const tries = [
        `select * from ${x.s}.${x.t} limit 1`, `insert into ${x.s}.${x.t} default values`,
        `update ${x.s}.${x.t} set ${x.col} = ${x.col}`, `delete from ${x.s}.${x.t}`];
      const answers = [];
      for (const q of tries) answers.push((await T.as(who, q)).sqlstate);
      T.check(`${role} cannot select, insert, update or delete ${x.s}.${x.t}`,
        answers.every((a) => a === '42501'), answers.join(','));
    }
  }

  T.section('catalogue: functions');
  const fns = await T.sql(
    `select n.nspname as s, p.proname as f, p.prosecdef as definer, pg_get_function_arguments(p.oid) as args,
            pg_get_function_result(p.oid) as ret, coalesce(array_to_string(p.proconfig, ','), '') as config,
            has_function_privilege('anon', p.oid, 'execute') as anon,
            has_function_privilege('authenticated', p.oid, 'execute') as authed,
            has_function_privilege('service_role', p.oid, 'execute') as service,
            exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                    where a.grantee = 0 and a.privilege_type = 'EXECUTE') as everyone
     from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname in ('public', 'mm_private', 'mm_policy') order by 1, 2`);
  const pub = fns.filter((x) => x.s === 'public');
  const names = pub.map((x) => x.f);
  T.check('public holds exactly the contract\'s 49 calls and the 5 service calls',
    JSON.stringify(names) === JSON.stringify(Object.keys(CONTRACT).sort()),
    `extra: ${names.filter((n) => !CONTRACT[n])} missing: ${Object.keys(CONTRACT).filter((n) => !names.includes(n))}`);
  T.check('no function name is used twice (no overloads)', new Set(names).size === names.length);
  for (const x of pub) {
    const want = CONTRACT[x.f];
    if (!want) continue;
    T.check(`${x.f}(${want.args}) has the contract's arguments`, x.args === want.args, x.args);
    T.check(`${x.f} is security definer with an empty search path and returns jsonb`,
      x.definer && x.config.split(',').includes('search_path=""') && x.ret === 'jsonb', `${x.definer} ${x.config} ${x.ret}`);
    const rights = `${x.everyone ? 'PUBLIC ' : ''}${x.anon ? 'anon ' : ''}${x.authed ? 'authenticated ' : ''}${x.service ? 'service_role' : ''}`.trim();
    const wanted = { anon: 'anon authenticated', user: 'authenticated', service: 'service_role' }[want.who];
    T.check(`${x.f} can be run by: ${wanted}, and nobody else`, rights === wanted, rights);
  }
  for (const x of fns.filter((y) => y.s === 'mm_private')) {
    T.check(`mm_private.${x.f} cannot be run by any client or service role`,
      !x.everyone && !x.anon && !x.authed && !x.service);
    T.check(`mm_private.${x.f} has an empty search path`, x.config.split(',').includes('search_path=""'), x.config);
  }
  const pol = fns.filter((y) => y.s === 'mm_policy');
  T.check('mm_policy holds only the four policy helpers',
    JSON.stringify(pol.map((x) => x.f)) === JSON.stringify(['is_admin', 'may_listen', 'may_remove', 'may_upload']),
    pol.map((x) => x.f).join(','));
  for (const x of pol) {
    T.check(`mm_policy.${x.f} is definer, empty search path, for signed-in people only`,
      x.definer && x.config.split(',').includes('search_path=""') && x.authed && !x.anon && !x.everyone && !x.service);
  }
  const usage = await T.one(
    `select has_schema_privilege('anon', 'mm_private', 'usage') as a1, has_schema_privilege('authenticated', 'mm_private', 'usage') as a2,
            has_schema_privilege('service_role', 'mm_private', 'usage') as a3, has_schema_privilege('anon', 'mm_policy', 'usage') as b1,
            has_schema_privilege('authenticated', 'mm_policy', 'usage') as b2`);
  T.check('no client role may use schema mm_private', !usage.a1 && !usage.a2 && !usage.a3);
  T.check('only signed-in people may use schema mm_policy (the policies need it)', !usage.b1 && usage.b2);
  const priv = await T.as(someone, `select mm_private.is_admin($1)`, [someone.id]);
  T.check('a signed-in person cannot call into mm_private', priv.sqlstate === '42501', priv.err);
  const danger = await T.as(someone, `select public.svc_delete_account($1, null)`, [someone.id]);
  T.check('a signed-in person cannot run the account delete function', danger.sqlstate === '42501', danger.err);

  T.section('catalogue: who gets in');
  const nulls = (name) => Object.fromEntries(
    (CONTRACT[name].args ? CONTRACT[name].args.split(', ') : []).map((a) => [a.split(' ')[0], null]));
  const ghost = await T.user('ghost');                   // a good token, but the profile row is gone
  await T.sql('delete from public.profiles where id = $1', [ghost.id]);
  const banned = await T.user('banned');
  await T.sql('update public.profiles set banned = true where id = $1', [banned.id]);
  const password = await T.user('password', { method: 'password' });   // signed up with a password (Review A M7)
  const review = await T.user('review', { method: 'password' });       // the store review account
  await T.sql(`update public.config set value = to_jsonb($1::text) where key = 'review_user'`, [review.id]);
  const noTerms = await T.user('noterms', { terms: false });
  const before = await T.fingerprint();
  for (const [name, want] of Object.entries(CONTRACT)) {
    if (want.who === 'service') {
      for (const who of [null, someone]) {
        const r = await T.rpc(who, name, nulls(name));
        T.check(`${who ? 'a signed-in person' : 'anon'} is refused ${name} by Postgres`, r.sqlstate === '42501', r.err);
      }
      continue;
    }
    if (want.who === 'anon') continue;
    const a = await T.rpc(null, name, nulls(name));
    T.check(`anon is refused ${name} by Postgres`, a.sqlstate === '42501', a.err || 'it ran');
    T.err(`${name} with a good token but no profile`, await T.rpc(ghost, name, nulls(name)), 'auth');
    T.err(`${name} as a banned person`, await T.rpc(banned, name, nulls(name)), 'auth');
    T.err(`${name} with a password session`, await T.rpc(password, name, nulls(name)), 'auth');
    if (want.T) T.err(`${name} before accepting the terms`, await T.rpc(noTerms, name, nulls(name)), 'terms');
  }
  T.check('none of those refused calls changed anything', before === await T.fingerprint());
  T.ok('the review account may use its password', await T.rpc(review, 'me'), (v) => v.id === review.id);
  await T.sql('insert into public.admins (user_id) values ($1)', [review.id]);
  T.ok('the review account is never an admin, even with a row in admins', await T.rpc(review, 'me'), (v) => v.is_admin === false);
  T.err('so it is refused the admin calls', await T.rpc(review, 'admin_reports'), 'forbidden');
  await T.sql('delete from public.admins where user_id = $1', [review.id]);
  await T.sql(`update public.config set value = 'null' where key = 'review_user'`);
  T.err('with no review account set, a password session is refused', await T.rpc(review, 'me'), 'auth');

  T.section('catalogue: config');
  const cfg = Object.fromEntries((await T.sql('select key, value from public.config')).map((r) => [r.key, r.value]));
  for (const [k, v] of Object.entries(CONFIG)) T.check(`config.${k} is ${JSON.stringify(v)}`, cfg[k] === v && k in cfg, JSON.stringify(cfg[k]));
  T.check('config has no take_seconds row', !('take_seconds' in cfg));
}
