-- Mess Machine accounts, file 3 of 9. Slice A: my messes online, on two phones (ACCOUNTS-API.md 1.4).
-- Every function: public, security definer, empty search path, returns jsonb, starts with caller().
-- T in a comment means the current terms must be accepted first.

create or replace function public.me() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  return mm_private.me_json(v_me);
end $$;

create or replace function public.set_profile(p_display_name text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_name text := btrim(p_display_name);
begin
  if v_name is null or char_length(v_name) not between 1 and 24 then perform mm_private.fail('bad_input'); end if;
  if not mm_private.clean_name(v_name) then perform mm_private.fail('bad_name'); end if;
  update public.profiles p set display_name = v_name, name_set = true where p.id = v_me;
  return mm_private.me_json(v_me);
end $$;

create or replace function public.accept_terms(p_version text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  if p_version is distinct from mm_private.cfg_t('terms_version') then
    perform mm_private.fail('terms_outdated');
  end if;
  update public.profiles p set terms_version = p_version, terms_at = now() where p.id = v_me;
  insert into public.terms_acceptances (user_id, version) values (v_me, p_version) on conflict do nothing;
  return jsonb_build_object('terms_ok', true);
end $$;

-- The body of create_mess and fork_mess. The caller's profile row is already locked.
create or replace function mm_private.new_mess(p_me uuid, p_id uuid, p_name text, p_doc jsonb, p_from uuid)
returns jsonb
language plpgsql set search_path = '' as $$
declare
  v_name text := mm_private.mess_name(p_name);
  v_bytes int;
begin
  if p_id is null or p_doc is null or jsonb_typeof(p_doc) <> 'object' then perform mm_private.fail('bad_input'); end if;
  if v_name is null then perform mm_private.fail('bad_name'); end if;
  v_bytes := octet_length(p_doc::text);
  if v_bytes > mm_private.cfg_n('mess_bytes') then perform mm_private.fail('too_big'); end if;
  if (select count(*) from public.messes m where m.owner_id = p_me) >= mm_private.cfg_n('mess_limit') then
    perform mm_private.fail('too_many_messes');
  end if;
  if mm_private.docs_used(p_me) + v_bytes > mm_private.cfg_n('docs_limit') then
    perform mm_private.fail('docs_full');
  end if;
  insert into public.messes (id, owner_id, name, doc, doc_bytes, forked_from)
    values (p_id, p_me, v_name, p_doc, v_bytes, p_from);
  insert into public.mess_members (mess_id, user_id, role, added_by) values (p_id, p_me, 'owner', p_me);
  return jsonb_build_object('id', p_id, 'rev', 0);
end $$;

-- T. Safe to repeat with the same id.
create or replace function public.create_mess(p_id uuid, p_name text, p_doc jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_old public.messes;
begin
  perform mm_private.need_terms(v_me);
  perform 1 from public.profiles p where p.id = v_me for update;
  select * into v_old from public.messes m where m.id = p_id;
  if found then
    if v_old.owner_id = v_me then return jsonb_build_object('id', v_old.id, 'rev', v_old.rev); end if;
    perform mm_private.fail('bad_input');
  end if;
  return mm_private.new_mess(v_me, p_id, p_name, p_doc, null);
end $$;

create or replace function public.my_messes() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', m.id, 'name', m.name, 'role', mm.role,
        'owner_name', (select p.display_name from public.profiles p where p.id = m.owner_id),
        'rev', m.rev, 'updated_at', mm_private.iso(m.updated_at),
        'members', (select count(*) from public.mess_members x where x.mess_id = m.id),
        'share_mode', m.share_mode, 'doc_bytes', m.doc_bytes, 'blocked', m.blocked)
        order by m.updated_at desc, m.id), '[]'::jsonb)
    from public.mess_members mm join public.messes m on m.id = mm.mess_id
    where mm.user_id = v_me and (not m.blocked or mm.role = 'owner'));
end $$;

create or replace function public.open_mess(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text := mm_private.role_of(v_me, p_mess);
  v_m public.messes;
begin
  if v_role is null then perform mm_private.fail('not_found'); end if;
  select * into v_m from public.messes m where m.id = p_mess;
  return jsonb_build_object(
    'id', v_m.id, 'name', v_m.name, 'role', v_role, 'owner_id', v_m.owner_id,
    'rev', v_m.rev, 'doc_rev', v_m.doc_rev, 'doc', v_m.doc, 'ops_v', v_m.ops_v,
    'ops', mm_private.batches_json(p_mess, v_m.doc_rev, false),
    'sounds', mm_private.sounds_json(p_mess, false),
    'members', mm_private.members_json(p_mess),
    'share_mode', v_m.share_mode,
    'share_token', case when v_role = 'owner' then v_m.share_token end,
    'topic', 'mess:' || v_m.id::text || ':' || v_m.channel_key);
end $$;

-- T. A whole-mess save with a compare on rev. On a clash the app keeps both.
create or replace function public.put_doc(p_mess uuid, p_doc jsonb, p_base_rev bigint, p_label text default '')
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_label text := coalesce(p_label, '');
  v_bytes int;
  v_due boolean;
  v_total bigint;
  v_rev bigint;
  v_version uuid;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  if p_doc is null or jsonb_typeof(p_doc) <> 'object' or p_base_rev is null or char_length(v_label) > 40 then
    perform mm_private.fail('bad_input');
  end if;
  v_bytes := octet_length(p_doc::text);
  if v_bytes > mm_private.cfg_n('mess_bytes') then perform mm_private.fail('too_big'); end if;
  if not mm_private.hit('put_doc', v_me::text || ':' || p_mess::text, interval '1 minute', 12) then
    perform mm_private.fail('rate_limited');
  end if;
  if p_base_rev <> v_m.rev then perform mm_private.fail('conflict'); end if;
  v_due := mm_private.version_due(p_mess, v_label);
  perform 1 from public.profiles p where p.id = v_m.owner_id for update;
  v_total := mm_private.docs_used(v_m.owner_id) - v_m.doc_bytes + v_bytes;
  if v_due then v_total := v_total + v_bytes; end if;   -- the History entry this save will make
  if v_total > mm_private.cfg_n('docs_limit') then
    perform mm_private.fail('docs_full');
  end if;

  v_rev := mm_private.append_batch(p_mess, v_me, gen_random_uuid(), '[{"t":"doc.reset"}]'::jsonb, 1, clock_timestamp());
  update public.messes m set doc = p_doc, doc_bytes = v_bytes, doc_rev = v_rev, ops_bytes = 0 where m.id = p_mess;
  delete from public.mess_ops o where o.mess_id = p_mess and o.rev < v_rev - 500;
  if v_due then
    v_version := mm_private.keep_version(p_mess, v_rev, p_doc, v_bytes, v_me, v_label);
  end if;
  return jsonb_build_object('rev', v_rev, 'version_id', v_version);
end $$;

-- T. Owner only. A mess that has only ever been saved whole (Slice A) keeps its rev; once anyone has sent
-- ops to it (Slice B) the new name also travels as a batch, so open phones hear it.
create or replace function public.rename_mess(p_mess uuid, p_name text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_name text := mm_private.mess_name(p_name);
  v_rev bigint;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  if v_name is null then perform mm_private.fail('bad_name'); end if;
  update public.messes m set name = v_name, updated_at = now() where m.id = p_mess;
  v_rev := v_m.rev;
  if v_m.op_mode then
    v_rev := mm_private.append_batch(p_mess, v_me, gen_random_uuid(),
      jsonb_build_array(jsonb_build_object('t', 'mess.set', 'f', 'name', 'v', v_name)), 1, clock_timestamp());
  end if;
  return jsonb_build_object('rev', v_rev);
end $$;

create or replace function public.delete_mess(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
begin
  perform 1 from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  perform mm_private.cast_to(p_mess, 'meta', jsonb_build_object('kind', 'deleted'));
  delete from public.messes m where m.id = p_mess;
  return jsonb_build_object('ok', true);
end $$;

-- T. Step one of sending a sound up. The answer says where to put the file.
create or replace function public.register_sound(p_sha256 text, p_bytes bigint, p_seconds real, p_rate int,
    p_kind text, p_local_key text, p_title text, p_bpm real, p_beats int, p_mkey text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_old public.sound_files;
  v_path text;
  v_limit bigint := mm_private.cfg_n('sound_limit');
  v_counted bigint;
  v_pending bigint;
  v_n int;
begin
  perform mm_private.need_terms(v_me);
  select p.sound_bytes into v_counted from public.profiles p where p.id = v_me for update;
  if p_sha256 is null or p_sha256 !~ '^[0-9a-f]{64}$' or p_rate is null or p_rate not between 8000 and 96000
     or p_kind is null or p_kind not in ('imp', 'mic', 'rs', 'take')
     or p_bytes is null or p_bytes <= 0 or p_seconds is null or not (p_seconds > 0) then
    perform mm_private.fail('bad_input');
  end if;
  v_path := v_me::text || '/' || p_sha256 || '.wav';
  select * into v_old from public.sound_files f where f.owner_id = v_me and f.sha256 = p_sha256;
  select coalesce(sum(f.bytes), 0), count(*) into v_pending, v_n from public.sound_files f
    where f.owner_id = v_me and f.state = 'pending' and f.sha256 <> p_sha256;
  if v_old.state = 'ready' then
    return jsonb_build_object('state', 'ready', 'path', v_path, 'used', v_counted + v_pending, 'limit', v_limit);
  end if;
  if p_seconds > mm_private.cfg_n('sound_seconds') then perform mm_private.fail('too_long'); end if;
  if p_bytes > mm_private.cfg_n('sound_bytes') then perform mm_private.fail('too_big'); end if;
  if v_n >= 20 then perform mm_private.fail('too_many_pending'); end if;
  if v_old.state = 'deleting' and v_old.counted then
    v_counted := greatest(v_counted - v_old.bytes, 0);   -- a sound being deleted that is sent up again starts over
  end if;
  if v_counted + v_pending + p_bytes > v_limit then perform mm_private.fail('quota'); end if;
  update public.profiles p set sound_bytes = v_counted where p.id = v_me;
  insert into public.sound_files (owner_id, sha256, bytes, seconds, rate, kind, local_key, title, bpm, beats,
                                  mkey, state, counted, blocked)
    values (v_me, p_sha256, p_bytes, p_seconds, p_rate, p_kind, left(coalesce(p_local_key, ''), 80),
            left(coalesce(p_title, ''), 40), coalesce(p_bpm, 0), coalesce(p_beats, 0), left(coalesce(p_mkey, ''), 24),
            'pending', false, exists (select 1 from mm_private.blocked_sounds b where b.sha256 = p_sha256))
    on conflict (owner_id, sha256) do update set
      bytes = excluded.bytes, seconds = excluded.seconds, rate = excluded.rate, kind = excluded.kind,
      local_key = excluded.local_key, title = excluded.title, bpm = excluded.bpm, beats = excluded.beats,
      mkey = excluded.mkey, state = 'pending', counted = false, at = now();
  return jsonb_build_object('state', 'upload', 'path', v_path, 'used', v_counted + v_pending + p_bytes,
                            'limit', v_limit);
end $$;

-- Step three, after the upload. The size is the real one from Storage, not the one that was claimed.
-- A refused file (too_big, quota) keeps its pending row, because a function that answers with an error
-- cannot also keep a delete. It still counts against the pending limits, and delete_sound or the daily
-- sweep clears it.
create or replace function public.finish_upload(p_sha256 text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_file public.sound_files;
  v_counted bigint;
  v_size bigint;
  v_mess uuid;
begin
  select p.sound_bytes into v_counted from public.profiles p where p.id = v_me for update;
  select * into v_file from public.sound_files f where f.owner_id = v_me and f.sha256 = p_sha256;
  if v_file.state = 'ready' then return jsonb_build_object('state', 'ready', 'used', v_counted); end if;
  if v_file.state is distinct from 'pending' then perform mm_private.fail('not_found'); end if;
  v_size := mm_private.object_size('sounds', v_me::text || '/' || p_sha256 || '.wav');
  if v_size is null then perform mm_private.fail('not_uploaded'); end if;
  if v_size <> v_file.bytes then perform mm_private.fail('too_big'); end if;
  if v_counted + v_file.bytes > mm_private.cfg_n('sound_limit') then perform mm_private.fail('quota'); end if;
  update public.sound_files f set state = 'ready', counted = true where f.owner_id = v_me and f.sha256 = p_sha256;
  update public.profiles p set sound_bytes = v_counted + v_file.bytes where p.id = v_me;
  for v_mess in select distinct s.mess_id from public.mess_sounds s where s.owner_id = v_me and s.sha256 = p_sha256 loop
    perform mm_private.cast_to(v_mess, 'meta', jsonb_build_object('kind', 'sound', 'sha256', p_sha256));
  end loop;
  return jsonb_build_object('state', 'ready', 'used', v_counted + v_file.bytes);
end $$;

-- T. Lists a sound on a mess. I may list my own file, or one a mess I am on already lists.
-- A sound whose maker is not me is attached, never uploaded again by me (Review A M5).
create or replace function public.attach_sound(p_mess uuid, p_key text, p_owner uuid, p_sha256 text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_file public.sound_files;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  if p_key is null or p_key not like 'bn:%' or char_length(p_key) not between 1 and 80 then
    perform mm_private.fail('bad_input');
  end if;
  select * into v_file from public.sound_files f
    where f.owner_id = p_owner and f.sha256 = p_sha256 and f.state in ('pending', 'ready') and not f.blocked;
  if not found then perform mm_private.fail('not_found'); end if;
  if p_owner <> v_me and not exists (
       select 1 from public.mess_sounds s
       where s.owner_id = p_owner and s.sha256 = p_sha256 and s.src = 'user'
         and mm_private.role_of(v_me, s.mess_id) is not null) then
    perform mm_private.fail('not_found');
  end if;
  insert into public.mess_sounds (mess_id, sound_key, sha256, owner_id, src, title, kind, added_by, added_rev)
    values (p_mess, p_key, p_sha256, p_owner, 'user', v_file.title, v_file.kind, v_me, v_m.rev)
    on conflict (mess_id, sound_key) do update set
      sha256 = excluded.sha256, owner_id = excluded.owner_id, src = 'user', title = excluded.title,
      kind = excluded.kind, added_by = excluded.added_by, added_rev = excluded.added_rev;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.my_sounds() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'sha256', f.sha256, 'title', f.title, 'kind', f.kind, 'bytes', f.bytes, 'seconds', f.seconds,
        'state', f.state,
        'messes', (select count(distinct s.mess_id) from public.mess_sounds s
                   where s.owner_id = f.owner_id and s.sha256 = f.sha256),
        'vault', (select v.status from public.vault_submissions v
                  where v.submitter_id = f.owner_id and v.sha256 = f.sha256
                  order by v.submitted_at desc limit 1))
        order by f.bytes desc, f.sha256), '[]'::jsonb)
    from public.sound_files f where f.owner_id = v_me);
end $$;

-- Called twice. First: the row becomes 'deleting' and every mess that listed the sound loses it.
-- The app then deletes the object and calls again: 'deleted' once the object is really gone.
create or replace function public.delete_sound(p_sha256 text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_file public.sound_files;
  v_path text;
  v_n int := 0;
  v_state text := 'deleting';
begin
  perform 1 from public.profiles p where p.id = v_me for update;
  select * into v_file from public.sound_files f where f.owner_id = v_me and f.sha256 = p_sha256;
  if not found then perform mm_private.fail('not_found'); end if;
  v_path := v_me::text || '/' || p_sha256 || '.wav';
  if v_file.state in ('pending', 'ready') then
    v_n := mm_private.detach_file(v_me, p_sha256, true);
    update public.sound_files f set state = 'deleting' where f.owner_id = v_me and f.sha256 = p_sha256;
    update public.vault_submissions v set status = 'withdrawn', reviewed_at = now()
      where v.submitter_id = v_me and v.sha256 = p_sha256 and v.status = 'pending';
  end if;
  if v_file.state = 'deleting' and not mm_private.object_there('sounds', v_path) then
    perform mm_private.forget_file(v_me, p_sha256);
    v_state := 'deleted';
  end if;
  return jsonb_build_object('state', v_state, 'path', v_path,
    'used', (select p.sound_bytes from public.profiles p where p.id = v_me), 'messes', v_n);
end $$;

-- Storage (ACCOUNTS-API.md section 2). Private buckets, 20 MB a file, so the cap is real at upload time.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('sounds', 'sounds', false, 20971520, array['audio/wav', 'audio/flac']),
  ('vault',  'vault',  false, 20971520, array['audio/wav', 'audio/flac'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- May the caller upload this object? Only a file they registered and have not finished, in their own folder.
create or replace function mm_policy.may_upload(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select mm_private.valid_user((select auth.uid()), (select auth.jwt()) -> 'amr')
     and exists (select 1 from public.sound_files f
       where f.owner_id = (select auth.uid()) and f.state = 'pending'
         and p_name = f.owner_id::text || '/' || f.sha256 || '.wav')
$$;

-- May the caller remove this object? Only in their own folder, and only once delete_sound has marked the
-- file (or when no row knows it). The same test guards reading, because the Storage service reads a row
-- while it deletes it; so a person's own token can read their own file only while it is being deleted.
create or replace function mm_policy.may_remove(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select mm_private.valid_user((select auth.uid()), (select auth.jwt()) -> 'amr')
     and p_name like (select auth.uid())::text || '/%'
     and not exists (select 1 from public.sound_files f
       where f.owner_id = (select auth.uid()) and f.state <> 'deleting'
         and p_name = f.owner_id::text || '/' || f.sha256 || '.wav')
$$;

create or replace function mm_policy.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select mm_private.valid_user((select auth.uid()), (select auth.jwt()) -> 'amr')
     and mm_private.is_admin((select auth.uid()))
$$;

-- There is no update policy: nobody overwrites a file. Nobody reads a sound with their own token either
-- (all listening goes through the sound-urls function), apart from the two narrow cases the Storage
-- service needs to carry out a delete.
drop policy if exists mm_sounds_insert on storage.objects;
create policy mm_sounds_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'sounds' and mm_policy.may_upload(name));
drop policy if exists mm_sounds_delete on storage.objects;
create policy mm_sounds_delete on storage.objects for delete to authenticated
  using (bucket_id = 'sounds' and mm_policy.may_remove(name));
drop policy if exists mm_sounds_select on storage.objects;
create policy mm_sounds_select on storage.objects for select to authenticated
  using (bucket_id = 'sounds' and mm_policy.may_remove(name));
drop policy if exists mm_vault_insert on storage.objects;
create policy mm_vault_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'vault' and mm_policy.is_admin());
drop policy if exists mm_vault_delete on storage.objects;
create policy mm_vault_delete on storage.objects for delete to authenticated
  using (bucket_id = 'vault' and mm_policy.is_admin());
drop policy if exists mm_vault_select on storage.objects;
create policy mm_vault_select on storage.objects for select to authenticated
  using (bucket_id = 'vault' and mm_policy.is_admin());

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;
revoke all on all functions in schema mm_policy from public, anon, authenticated, service_role;
grant execute on function mm_policy.may_upload(text), mm_policy.may_remove(text), mm_policy.is_admin() to authenticated;

grant execute on function
  public.me(), public.set_profile(text), public.accept_terms(text),
  public.create_mess(uuid, text, jsonb), public.my_messes(), public.open_mess(uuid),
  public.put_doc(uuid, jsonb, bigint, text), public.rename_mess(uuid, text), public.delete_mess(uuid),
  public.register_sound(text, bigint, real, int, text, text, text, real, int, text),
  public.finish_upload(text), public.attach_sound(uuid, text, uuid, text),
  public.my_sounds(), public.delete_sound(text)
  to authenticated;
