-- Mess Machine accounts, file 9 of 9: what the three Edge Functions call (sound-urls, delete-account, sweep).
-- These functions are named svc_ and only the service key may run them. No app can: the catalogue test
-- checks that anon and authenticated hold no right on them. All the deciding is done here, in SQL, so it
-- is tested; the Edge Functions only check who is calling, then do the Storage and Auth work SQL cannot.

-- Which files may this caller listen to? Body is exactly one of {"mess"}, {"token"}, {"report"}, {"submission"}.
-- p_user and p_amr come from the caller's token, as checked by the Edge Function (null for a guest).
-- Lists only files that are ready, leaves out a banned person's files and sounds taken down.
create or replace function public.svc_sound_urls(p_user uuid, p_amr jsonb, p_body jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_keys text[];
  v_kind text;
  v_val text;
  v_mess uuid;
  v_m public.messes;
  v_r public.reports;
  v_s public.vault_submissions;
begin
  if p_body is null or jsonb_typeof(p_body) <> 'object' then perform mm_private.fail('bad_input'); end if;
  select array_agg(k) into v_keys from jsonb_object_keys(p_body) k;
  if v_keys is null or array_length(v_keys, 1) <> 1 or v_keys[1] not in ('mess', 'token', 'report', 'submission')
     or jsonb_typeof(p_body -> v_keys[1]) <> 'string' then
    perform mm_private.fail('bad_input');
  end if;
  v_kind := v_keys[1];
  v_val := p_body ->> v_kind;

  if v_kind = 'token' then
    v_m := mm_private.shared_mess(v_val);
    if v_m.id is null then perform mm_private.fail('not_found'); end if;
    -- a popular link to 250 MB of sounds is the owner's download bill (Review A S6)
    if not mm_private.hit('share_urls', mm_private.sha_hex(v_val), interval '1 day',
                          mm_private.cfg_n('share_urls_day')::int) then
      perform mm_private.fail('rate_limited');
    end if;
    v_mess := v_m.id;
  else
    if not mm_private.valid_user(p_user, p_amr) then perform mm_private.fail('auth'); end if;
    if v_val !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' then
      perform mm_private.fail('bad_input');
    end if;
    if v_kind = 'mess' then
      if mm_private.role_of(p_user, v_val::uuid) is null then perform mm_private.fail('not_found'); end if;
      v_mess := v_val::uuid;
    elsif v_kind = 'report' then
      -- an admin hears a private sound only through an open report or a pending submission (Review A S4)
      select * into v_r from public.reports r where r.id = v_val::uuid and r.status = 'open';
      if not found or not mm_private.is_admin(p_user) then perform mm_private.fail('not_found'); end if;
      v_mess := v_r.mess_id;
    else
      select * into v_s from public.vault_submissions s where s.id = v_val::uuid and s.status = 'pending';
      if not found or not mm_private.is_admin(p_user) then perform mm_private.fail('not_found'); end if;
      return jsonb_build_object('sounds', (
        select coalesce(jsonb_agg(jsonb_build_object('key', 'submission', 'sha256', f.sha256, 'bytes', f.bytes,
                 'bucket', 'sounds', 'path', f.owner_id::text || '/' || f.sha256 || '.wav')), '[]'::jsonb)
        from public.sound_files f
        where f.owner_id = v_s.submitter_id and f.sha256 = v_s.sha256 and f.state = 'ready' and not f.blocked));
    end if;
  end if;

  return jsonb_build_object('sounds', (
    select coalesce(jsonb_agg(jsonb_build_object(
        'key', s.sound_key, 'sha256', s.sha256, 'bytes', coalesce(f.bytes, v.bytes),
        'bucket', case when s.src = 'user' then 'sounds' else 'vault' end,
        'path', case when s.src = 'user' then s.owner_id::text || '/' || s.sha256 || '.wav'
                     else s.sha256 || '.wav' end) order by s.sound_key), '[]'::jsonb)
    from public.mess_sounds s
    left join public.sound_files f on s.src = 'user' and f.owner_id = s.owner_id and f.sha256 = s.sha256
         and f.state = 'ready' and not f.blocked
    left join public.profiles p on p.id = f.owner_id
    left join public.vault_sounds v on s.src = 'vault' and v.sha256 = s.sha256
    where s.mess_id = v_mess
      and ((s.src = 'user' and f.sha256 is not null and (not p.banned or p.id is not distinct from p_user))
        or (s.src = 'vault' and v.sha256 is not null
            and not exists (select 1 from mm_private.blocked_sounds b where b.sha256 = s.sha256)))));
end $$;

-- Deletes everything one person has here, in one transaction, and notes the deletion so the daily sweep
-- can finish it if a later step fails (Review A M6). Safe to run twice.
-- p_amr: the token's sign-in list. Deletion must follow a fresh code: the newest entry under 10 minutes
-- old, else stale_login. Pass null to skip the check (the sweep does, when finishing a deletion).
-- Owned messes go (no hand-over). In other people's messes the person's sounds switch to the Vault copy
-- when there is one, else to 'gone'. Submissions still waiting are withdrawn and deleted; approved ones
-- keep their credit name and lose the link to the person. Reports keep their text and lose the ids.
create or replace function public.svc_delete_account(p_user uuid, p_amr jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_mess uuid;
  v_sha text;
  v_newest bigint;
begin
  if p_user is null then perform mm_private.fail('auth'); end if;
  if p_amr is not null then
    if jsonb_typeof(p_amr) = 'array' then
      select max((e.value ->> 'timestamp')::bigint) into v_newest from jsonb_array_elements(p_amr) e
        where jsonb_typeof(e.value) = 'object' and (e.value ->> 'timestamp') ~ '^[0-9]+$';
    end if;
    if v_newest is null or v_newest < extract(epoch from now()) - 600 then
      perform mm_private.fail('stale_login');
    end if;
  end if;
  insert into mm_private.deletions (user_id) values (p_user) on conflict (user_id) do nothing;

  for v_mess in select m.id from public.messes m where m.owner_id = p_user order by m.id loop
    perform 1 from public.messes m where m.id = v_mess for update;
    perform mm_private.cast_to(v_mess, 'meta', jsonb_build_object('kind', 'deleted'));
  end loop;
  delete from public.messes m where m.owner_id = p_user;
  for v_mess in select mm.mess_id from public.mess_members mm where mm.user_id = p_user order by mm.mess_id loop
    perform 1 from public.messes m where m.id = v_mess for update;
    perform mm_private.drop_member(v_mess, p_user);
  end loop;
  for v_sha in select f.sha256 from public.sound_files f where f.owner_id = p_user loop
    perform mm_private.detach_file(p_user, v_sha, true);
  end loop;
  delete from public.sound_files f where f.owner_id = p_user;
  delete from public.vault_submissions s where s.submitter_id = p_user and s.status <> 'approved';
  update public.vault_submissions s set submitter_id = null where s.submitter_id = p_user;
  update public.reports r set reporter_id = null where r.reporter_id = p_user;
  update public.reports r set owner_id = null where r.owner_id = p_user;
  update public.reports r set about_user = null where r.about_user = p_user;
  delete from mm_private.usage g where g.user_id = p_user;
  delete from mm_private.hits h where h.scope = p_user::text or h.scope like p_user::text || ':%';
  delete from public.admins a where a.user_id = p_user;
  delete from public.profiles p where p.id = p_user;   -- terms, memberships, seats, blocks go with it

  return jsonb_build_object('user', p_user, 'paths', (
    select coalesce(jsonb_agg(o.name order by o.name), '[]'::jsonb) from storage.objects o
    where o.bucket_id = 'sounds' and o.name like p_user::text || '/%'));
end $$;

-- The Edge Function calls this last, once the files and the sign-in are gone too.
create or replace function public.svc_deletion_done(p_user uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  update mm_private.deletions d set done_at = now() where d.user_id = p_user and d.done_at is null;
  return jsonb_build_object('ok', true);
end $$;

-- The daily sweep, part one: forget uploads nobody finished in 24 hours, then say which objects to
-- remove (files being deleted, and objects no row knows) and which account deletions stopped half way.
create or replace function public.svc_sweep() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_file record;
begin
  for v_file in
    select f.owner_id, f.sha256 from public.sound_files f
    where f.state = 'pending' and f.at < now() - interval '24 hours'
  loop
    perform mm_private.detach_file(v_file.owner_id, v_file.sha256, true);
    delete from public.sound_files f where f.owner_id = v_file.owner_id and f.sha256 = v_file.sha256;
  end loop;
  delete from mm_private.hits h where h.at < now() - interval '2 days';
  delete from mm_private.usage g where g.day < (now() at time zone 'UTC')::date - 2;
  delete from public.mess_live l where l.seen_at < now() - interval '1 day';
  return jsonb_build_object(
    'remove', (
      select coalesce(jsonb_agg(x.name order by x.name), '[]'::jsonb) from (
        select o.name from storage.objects o
        where o.bucket_id = 'sounds' and not exists (
          select 1 from public.sound_files f
          where f.state <> 'deleting' and o.name = f.owner_id::text || '/' || f.sha256 || '.wav')
        order by o.name limit 1000) x),
    'deletions', (
      select coalesce(jsonb_agg(jsonb_build_object('user', d.user_id, 'paths', (
          select coalesce(jsonb_agg(o.name order by o.name), '[]'::jsonb) from storage.objects o
          where o.bucket_id = 'sounds' and o.name like d.user_id::text || '/%')) order by d.asked_at), '[]'::jsonb)
      from mm_private.deletions d where d.done_at is null and d.asked_at < now() - interval '5 minutes'));
end $$;

-- The daily sweep, part two, after the objects were removed: files marked 'deleting' whose object is
-- gone are forgotten and their bytes come off the person's total.
create or replace function public.svc_sweep_done() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_file record;
  v_n int := 0;
begin
  for v_file in
    select f.owner_id, f.sha256 from public.sound_files f
    where f.state = 'deleting'
      and not mm_private.object_there('sounds', f.owner_id::text || '/' || f.sha256 || '.wav')
    order by f.owner_id, f.sha256
  loop
    perform 1 from public.profiles p where p.id = v_file.owner_id for update;
    perform mm_private.forget_file(v_file.owner_id, v_file.sha256);
    v_n := v_n + 1;
  end loop;
  return jsonb_build_object('forgotten', v_n);
end $$;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;

grant execute on function
  public.svc_sound_urls(uuid, jsonb, jsonb), public.svc_delete_account(uuid, jsonb),
  public.svc_deletion_done(uuid), public.svc_sweep(), public.svc_sweep_done()
  to service_role;
