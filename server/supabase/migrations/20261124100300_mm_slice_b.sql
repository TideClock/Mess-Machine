-- Mess Machine accounts, file 4 of 9. Slice B: two people in one mess (ACCOUNTS-API.md 1.5).
-- The rule that makes limits hold (Review A M1): lock the mess row first, then read any count.

create or replace function public.ops_since(p_mess uuid, p_from bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_m public.messes;
  v_from bigint := coalesce(p_from, 0);
  v_ops jsonb;
begin
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  select * into v_m from public.messes m where m.id = p_mess;
  if v_from < v_m.rev and not exists (
       select 1 from public.mess_ops o where o.mess_id = p_mess and o.rev = v_from + 1) then
    perform mm_private.fail('gap');
  end if;
  -- there is no heartbeat: a poll keeps a quiet editor's seat (Review A S8)
  update public.mess_live l set seen_at = clock_timestamp()
    where l.mess_id = p_mess and l.user_id = v_me and l.seen_at > clock_timestamp() - interval '90 seconds';
  -- at most 500 batches and 1 MB, oldest first; the first batch always goes
  select coalesce(jsonb_agg(mm_private.batch_json(x.rev, x.user_id, x.batch_id, x.v, x.ops, x.at) order by x.rev),
                  '[]'::jsonb) into v_ops
  from (
    select o.*, sum(o.bytes) over (order by o.rev) - o.bytes as before
    from (select * from public.mess_ops q where q.mess_id = p_mess and q.rev > v_from order by q.rev limit 500) o
  ) x where x.before < 1048576;
  return jsonb_build_object('rev', v_m.rev, 'doc_rev', v_m.doc_rev,
    'members', (select count(*) from public.mess_members mm where mm.mess_id = p_mess),
    'topic', 'mess:' || v_m.id::text || ':' || v_m.channel_key, 'ops', v_ops);
end $$;

-- T. The one function everything else leans on. The server stores and orders; it reads no op except a rename.
create or replace function public.apply_ops(p_mess uuid, p_batch uuid, p_ops jsonb, p_v int) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_old public.mess_ops;
  v_ts timestamptz;
  v_bytes int;
  v_n bigint;
  v_name text;
  v_rev bigint;
begin
  perform mm_private.need_terms(v_me);
  -- 1 and 2: the shape and the size
  if p_batch is null or p_ops is null or jsonb_typeof(p_ops) <> 'array' or p_v is null
     or p_v not between 1 and mm_private.cfg_n('ops_v_max')
     or jsonb_array_length(p_ops) not between 1 and 500 then
    perform mm_private.fail('bad_input');
  end if;
  v_bytes := octet_length(p_ops::text);
  if v_bytes > 262144 then perform mm_private.fail('too_big'); end if;
  -- 3: the lock that puts batches in order and makes every count below safe
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_ts := clock_timestamp();
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  -- 4: a batch sent twice (the answer was lost) gets its first answer again
  select * into v_old from public.mess_ops o where o.mess_id = p_mess and o.batch_id = p_batch;
  if found then
    if v_old.user_id is distinct from v_me then perform mm_private.fail('bad_input'); end if;
    return jsonb_build_object('rev', v_old.rev, 'at', mm_private.iso(v_old.at));
  end if;
  -- 5: four batches a second
  select count(*) into v_n from public.mess_ops o
    where o.mess_id = p_mess and o.user_id = v_me and o.at > v_ts - interval '1 second';
  if v_n >= 4 then perform mm_private.fail('rate_limited'); end if;
  -- 6: 20 MB of edits a day (an error below undoes this count with everything else)
  insert into mm_private.usage as g (user_id, day, op_bytes)
    values (v_me, (v_ts at time zone 'UTC')::date, v_bytes)
    on conflict (user_id, day) do update set op_bytes = g.op_bytes + excluded.op_bytes
    returning g.op_bytes into v_n;
  if v_n > mm_private.cfg_n('ops_day_bytes') then perform mm_private.fail('day_limit'); end if;
  -- 7: a live seat
  if not mm_private.take_seat(p_mess, v_me, v_ts) then perform mm_private.fail('mess_full_live'); end if;
  -- 8: only the owner renames, and the name is checked like any other name (Review A M3)
  if exists (select 1 from jsonb_array_elements(p_ops) o
             where o.value ->> 't' = 'mess.set' and o.value ->> 'f' = 'name') then
    if exists (select 1 from jsonb_array_elements(p_ops) o
               where o.value ->> 't' = 'mess.set' and o.value ->> 'f' = 'name'
                 and jsonb_typeof(o.value -> 'v') is distinct from 'string') then
      perform mm_private.fail('bad_input');
    end if;
    if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
    select mm_private.mess_name(o.value ->> 'v') into v_name
      from jsonb_array_elements(p_ops) with ordinality o(value, n)
      where o.value ->> 't' = 'mess.set' and o.value ->> 'f' = 'name' order by o.n desc limit 1;
    if v_name is null then perform mm_private.fail('bad_name'); end if;
  end if;
  -- 9: the database is the costly store, so the log is kept short (Review A M4)
  if v_m.rev - v_m.doc_rev >= 2000 or v_m.ops_bytes > 2097152 then perform mm_private.fail('snapshot_first'); end if;
  -- 10
  update public.messes m set name = coalesce(v_name, m.name), op_mode = true where m.id = p_mess;
  v_rev := mm_private.append_batch(p_mess, v_me, p_batch, p_ops, p_v, v_ts);
  return jsonb_build_object('rev', v_rev, 'at', mm_private.iso(v_ts));
end $$;

-- T. A phone uploads the whole mess as it stands at p_doc_rev, so the log before it can go.
create or replace function public.save_snapshot(p_mess uuid, p_doc jsonb, p_doc_rev bigint, p_v int,
                                                p_label text default '') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_m public.messes;
  v_label text := coalesce(p_label, '');
  v_bytes int;
  v_due boolean;
  v_total bigint;
  v_version uuid;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  if p_doc is null or jsonb_typeof(p_doc) <> 'object' or p_doc_rev is null or char_length(v_label) > 40
     or p_v is null or p_v not between 1 and mm_private.cfg_n('ops_v_max') then
    perform mm_private.fail('bad_input');
  end if;
  if p_v < v_m.ops_v then perform mm_private.fail('client_too_old'); end if;
  if p_doc_rev > v_m.rev then perform mm_private.fail('ahead'); end if;
  if p_doc_rev < v_m.doc_rev or (p_doc_rev = v_m.doc_rev and v_label = '') then perform mm_private.fail('stale'); end if;
  v_bytes := octet_length(p_doc::text);
  if v_bytes > mm_private.cfg_n('mess_bytes') then perform mm_private.fail('too_big'); end if;
  v_due := mm_private.version_due(p_mess, v_label);
  perform 1 from public.profiles p where p.id = v_m.owner_id for update;
  v_total := mm_private.docs_used(v_m.owner_id) - v_m.doc_bytes + v_bytes;
  if v_due then v_total := v_total + v_bytes; end if;   -- the History entry this save will make
  if v_total > mm_private.cfg_n('docs_limit') then
    perform mm_private.fail('docs_full');
  end if;
  if v_label <> '' and not mm_private.hit('snapshot', v_me::text, interval '1 minute', 6) then
    perform mm_private.fail('rate_limited');
  end if;
  -- never writes the name, never drops a sound row (Review A M3, Review B S8)
  delete from public.mess_ops o where o.mess_id = p_mess and o.rev < p_doc_rev - 500;
  update public.messes m
    set doc = p_doc, doc_bytes = v_bytes, doc_rev = p_doc_rev,
        ops_bytes = coalesce((select sum(o.bytes) from public.mess_ops o
                              where o.mess_id = p_mess and o.rev > p_doc_rev), 0)
    where m.id = p_mess;
  if v_due then
    v_version := mm_private.keep_version(p_mess, p_doc_rev, p_doc, v_bytes, v_me, v_label);
  end if;
  return jsonb_build_object('doc_rev', p_doc_rev, 'version_id', v_version);
end $$;

create or replace function public.members(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  return mm_private.members_json(p_mess);
end $$;

-- An invite that still works: not revoked, not expired, not used, its mess not taken down.
create or replace function mm_private.live_invite(p_token text) returns public.invites
language sql stable set search_path = '' as $$
  select i.* from public.invites i join public.messes m on m.id = i.mess_id
  where p_token is not null and i.token_hash = mm_private.sha_hex(p_token)
    and not i.revoked and i.expires_at > now() and i.uses_left > 0 and not m.blocked
$$;

-- T. Owner only. Single use, always an editor. The token is shown once; only its hash is kept.
create or replace function public.make_invite(p_mess uuid, p_days int default 7) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_token text := mm_private.rand_token();
  v_inv public.invites;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  if p_days is null or p_days not between 1 and 30 then perform mm_private.fail('bad_input'); end if;
  if (select count(*) from public.invites i
      where i.mess_id = p_mess and not i.revoked and i.expires_at > now() and i.uses_left > 0) >= 20 then
    perform mm_private.fail('too_many_invites');
  end if;
  insert into public.invites (mess_id, token_hash, expires_at, uses_left, made_by)
    values (p_mess, mm_private.sha_hex(v_token), now() + make_interval(days => p_days), 1, v_me)
    returning * into v_inv;
  return jsonb_build_object('id', v_inv.id, 'token', v_token, 'expires_at', mm_private.iso(v_inv.expires_at));
end $$;

create or replace function public.list_invites(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text := mm_private.role_of(v_me, p_mess);
begin
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  return (
    select coalesce(jsonb_agg(jsonb_build_object('id', i.id, 'expires_at', mm_private.iso(i.expires_at),
        'uses_left', i.uses_left, 'revoked', i.revoked) order by i.at desc, i.id), '[]'::jsonb)
    from public.invites i where i.mess_id = p_mess);
end $$;

create or replace function public.revoke_invite(p_invite uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  update public.invites i set revoked = true
    where i.id = p_invite and mm_private.role_of(v_me, i.mess_id) = 'owner';
  if not found then perform mm_private.fail('not_found'); end if;
  return jsonb_build_object('ok', true);
end $$;

-- anon. What an invite link shows before sign-in. No ids, no counts.
create or replace function public.invite_info(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller_or_null();
  v_inv public.invites := mm_private.live_invite(p_token);
  v_m public.messes;
begin
  select * into v_m from public.messes m where m.id = v_inv.mess_id;
  if not found or exists (select 1 from public.user_blocks b
                          where b.owner_id = v_me and b.blocked_id = v_m.owner_id) then
    perform mm_private.fail('invite_invalid');
  end if;
  return jsonb_build_object('mess_name', v_m.name,
    'owner_name', (select p.display_name from public.profiles p where p.id = v_m.owner_id));
end $$;

-- T. Join a mess with an invite link.
create or replace function public.redeem_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_inv public.invites;
  v_m public.messes;
  v_role text;
begin
  perform mm_private.need_terms(v_me);
  -- Already in the mess this link is for (say the first answer was lost and the phone sends it again):
  -- answer as a join, even though the link is used up now. Only a member learns anything from this.
  select mm.mess_id, mm.role into v_m.id, v_role from public.invites i
    join public.mess_members mm on mm.mess_id = i.mess_id and mm.user_id = v_me
    join public.messes x on x.id = i.mess_id and not x.blocked
    where p_token is not null and i.token_hash = mm_private.sha_hex(p_token);
  if found then return jsonb_build_object('mess_id', v_m.id, 'role', v_role); end if;
  v_inv := mm_private.live_invite(p_token);
  select * into v_m from public.messes m where m.id = v_inv.mess_id;
  if not found or exists (select 1 from public.user_blocks b
                          where b.owner_id = v_me and b.blocked_id = v_m.owner_id) then
    perform mm_private.fail('invite_invalid');
  end if;
  if exists (select 1 from public.user_blocks b where b.owner_id = v_m.owner_id and b.blocked_id = v_me) then
    perform mm_private.fail('blocked');
  end if;
  perform 1 from public.messes m where m.id = v_m.id for update;
  select mm.role into v_role from public.mess_members mm where mm.mess_id = v_m.id and mm.user_id = v_me;
  if found then return jsonb_build_object('mess_id', v_m.id, 'role', v_role); end if;   -- already in: use nothing
  if (select count(*) from public.mess_members mm where mm.mess_id = v_m.id) >= mm_private.cfg_n('member_limit') then
    perform mm_private.fail('mess_full');
  end if;
  update public.invites i set uses_left = i.uses_left - 1
    where i.id = v_inv.id and i.uses_left > 0 and not i.revoked;
  if not found then perform mm_private.fail('invite_invalid'); end if;
  insert into public.mess_members (mess_id, user_id, role, added_by) values (v_m.id, v_me, 'editor', v_inv.made_by);
  perform mm_private.cast_to(v_m.id, 'meta', jsonb_build_object('kind', 'members'));
  return jsonb_build_object('mess_id', v_m.id, 'role', 'editor');
end $$;

create or replace function public.remove_member(p_mess uuid, p_user uuid, p_block boolean default false) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_target text;
begin
  perform 1 from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  select mm.role into v_target from public.mess_members mm where mm.mess_id = p_mess and mm.user_id = p_user;
  if not found then perform mm_private.fail('not_found'); end if;
  if v_target = 'owner' then perform mm_private.fail('bad_input'); end if;
  perform mm_private.drop_member(p_mess, p_user);
  if coalesce(p_block, false) then perform mm_private.do_block(v_me, p_user); end if;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.leave_mess(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
begin
  perform 1 from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role = 'owner' then perform mm_private.fail('owner_cannot_leave'); end if;
  perform mm_private.cast_to(p_mess, 'meta', jsonb_build_object('kind', 'members', 'gone', v_me));
  delete from public.mess_members mm where mm.mess_id = p_mess and mm.user_id = v_me;
  delete from public.mess_live l where l.mess_id = p_mess and l.user_id = v_me;
  return jsonb_build_object('ok', true);
end $$;

-- Anyone may block anyone (Review A S7): they leave my messes, I leave theirs, and neither can join the other's.
create or replace function public.block_user(p_user uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  if p_user is null or p_user = v_me then perform mm_private.fail('bad_input'); end if;
  if not exists (select 1 from public.profiles p where p.id = p_user) then perform mm_private.fail('not_found'); end if;
  perform mm_private.do_block(v_me, p_user);
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.my_blocks() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  return (
    select coalesce(jsonb_agg(jsonb_build_object('id', p.id, 'name', p.display_name) order by b.at desc, p.id),
                    '[]'::jsonb)
    from public.user_blocks b join public.profiles p on p.id = b.blocked_id where b.owner_id = v_me);
end $$;

create or replace function public.unblock_user(p_user uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  delete from public.user_blocks b where b.owner_id = v_me and b.blocked_id = p_user;
  return jsonb_build_object('ok', true);
end $$;

-- T. Ask for an editing seat. live false means watch only: four others are already editing.
create or replace function public.join_live(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_ts timestamptz;
  v_live boolean;
begin
  perform mm_private.need_terms(v_me);
  perform 1 from public.messes m where m.id = p_mess for update;
  v_ts := clock_timestamp();
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  v_live := mm_private.take_seat(p_mess, v_me, v_ts);
  return jsonb_build_object('live', v_live, 'editors', (
    select count(*) from public.mess_live l
    where l.mess_id = p_mess and l.seen_at > v_ts - interval '90 seconds'));
end $$;

create or replace function public.leave_live(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  delete from public.mess_live l where l.mess_id = p_mess and l.user_id = v_me;
  return jsonb_build_object('ok', true);
end $$;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;

grant execute on function
  public.ops_since(uuid, bigint), public.apply_ops(uuid, uuid, jsonb, int),
  public.save_snapshot(uuid, jsonb, bigint, int, text), public.members(uuid),
  public.make_invite(uuid, int), public.list_invites(uuid), public.revoke_invite(uuid),
  public.redeem_invite(text), public.remove_member(uuid, uuid, boolean), public.leave_mess(uuid),
  public.block_user(uuid), public.my_blocks(), public.unblock_user(uuid),
  public.join_live(uuid), public.leave_live(uuid)
  to authenticated;
grant execute on function public.invite_info(text) to anon, authenticated;
