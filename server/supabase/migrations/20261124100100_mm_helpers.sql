-- Mess Machine accounts, file 2 of 9: private helpers every function leans on.
-- All of these live in mm_private. No client role can reach that schema, so none of these can be called
-- from the app; they run only inside the public functions (which run as their owner).

create or replace function mm_private.fail(p_code text) returns void
language plpgsql set search_path = '' as $$
begin
  raise exception using errcode = 'P0001', message = p_code;
end $$;

create or replace function mm_private.cfg(p_key text) returns jsonb
language sql stable set search_path = '' as $$
  select c.value from public.config c where c.key = p_key
$$;

create or replace function mm_private.cfg_n(p_key text) returns bigint
language sql stable set search_path = '' as $$
  select (c.value #>> '{}')::bigint from public.config c where c.key = p_key
$$;

create or replace function mm_private.cfg_t(p_key text) returns text
language sql stable set search_path = '' as $$
  select c.value #>> '{}' from public.config c where c.key = p_key
$$;

-- Times travel as ISO 8601 UTC text, the same whatever the database's time zone is.
create or replace function mm_private.iso(p_at timestamptz) returns text
language sql immutable set search_path = '' as $$
  select to_char(p_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

create or replace function mm_private.colour(p_id uuid) returns int
language sql immutable set search_path = '' as $$
  select get_byte(uuid_send(p_id), 15) % 8
$$;

create or replace function mm_private.sha_hex(p_text text) returns text
language sql immutable set search_path = '' as $$
  select encode(sha256(convert_to(p_text, 'UTF8')), 'hex')
$$;

-- 24 random bytes as 32 base64url characters. Built from gen_random_uuid() and sha256(), which are part
-- of Postgres itself, because pgcrypto sits in another schema on Supabase (Review A S8).
create or replace function mm_private.rand_token() returns text
language sql volatile set search_path = '' as $$
  select translate(encode(substring(
    sha256(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid())) from 1 for 24), 'base64'), '+/', '-_')
$$;

create or replace function mm_private.rand_key() returns text
language sql volatile set search_path = '' as $$
  select left(encode(sha256(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid())), 'hex'), 16)
$$;

-- A good sign-in: a profile exists, it is not banned, and the session was not made with a password
-- (only the store review account may use one). Review A M6 and M7.
create or replace function mm_private.valid_user(p_user uuid, p_amr jsonb) returns boolean
language plpgsql stable set search_path = '' as $$
declare
  v_banned boolean;
begin
  if p_user is null then return false; end if;
  select p.banned into v_banned from public.profiles p where p.id = p_user;
  if not found or v_banned then return false; end if;
  if jsonb_typeof(p_amr) = 'array' and exists (
       select 1 from jsonb_array_elements(p_amr) e
       where (jsonb_typeof(e.value) = 'object' and e.value ->> 'method' = 'password')
          or (jsonb_typeof(e.value) = 'string' and e.value #>> '{}' = 'password'))
     and p_user::text is distinct from mm_private.cfg_t('review_user') then
    return false;
  end if;
  return true;
end $$;

-- The one guard. Every public function not marked anon starts with it, in place of auth.uid().
create or replace function mm_private.caller() returns uuid
language plpgsql stable set search_path = '' as $$
declare
  v_user uuid := (select auth.uid());
begin
  if not mm_private.valid_user(v_user, (select auth.jwt()) -> 'amr') then
    perform mm_private.fail('auth');
  end if;
  return v_user;
end $$;

-- For the anon calls: the caller's id when a good session came with the request, else null.
create or replace function mm_private.caller_or_null() returns uuid
language plpgsql stable set search_path = '' as $$
declare
  v_user uuid := (select auth.uid());
begin
  if mm_private.valid_user(v_user, (select auth.jwt()) -> 'amr') then return v_user; end if;
  return null;
end $$;

create or replace function mm_private.need_terms(p_user uuid) returns void
language plpgsql stable set search_path = '' as $$
begin
  if not exists (select 1 from public.profiles p
                 where p.id = p_user and p.terms_version = mm_private.cfg_t('terms_version')) then
    perform mm_private.fail('terms');
  end if;
end $$;

-- The review account is never an admin, whatever the admins table says.
create or replace function mm_private.is_admin(p_user uuid) returns boolean
language sql stable set search_path = '' as $$
  select exists (select 1 from public.admins a where a.user_id = p_user)
     and p_user::text is distinct from mm_private.cfg_t('review_user')
$$;

create or replace function mm_private.need_admin(p_user uuid) returns void
language plpgsql stable set search_path = '' as $$
begin
  if not mm_private.is_admin(p_user) then perform mm_private.fail('forbidden'); end if;
end $$;

-- A person's role on a mess, or null. A taken-down mess answers null to everyone but its owner.
create or replace function mm_private.role_of(p_user uuid, p_mess uuid) returns text
language sql stable set search_path = '' as $$
  select mm.role from public.mess_members mm join public.messes m on m.id = mm.mess_id
  where mm.mess_id = p_mess and mm.user_id = p_user and (not m.blocked or mm.role = 'owner')
$$;

-- Names (display names, mess names, Vault credits): no control characters, none of the blocked words.
create or replace function mm_private.clean_name(p_name text) returns boolean
language sql stable set search_path = '' as $$
  select p_name is not null
     and p_name !~ '[[:cntrl:]]'
     and not exists (
       select 1 from mm_private.bad_words w
       where (w.anywhere and position(w.word in regexp_replace(lower(p_name), '[^a-z]', '', 'g')) > 0)
          or w.word = any (regexp_split_to_array(lower(p_name), '[^a-z]+')))
$$;

-- A mess name as stored: trimmed, 1 to 48 characters, clean. Null when it will not do.
create or replace function mm_private.mess_name(p_name text) returns text
language sql stable set search_path = '' as $$
  select case when char_length(btrim(p_name)) between 1 and 48 and mm_private.clean_name(btrim(p_name))
              then btrim(p_name) end
$$;

-- Counts one action against a limit. False when the limit is already reached. The advisory lock makes
-- two connections take turns, so the count cannot be raced (Review A M1).
create or replace function mm_private.hit(p_what text, p_scope text, p_window interval, p_max int) returns boolean
language plpgsql set search_path = '' as $$
declare
  v_n int;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_what || ':' || p_scope, 0));
  select count(*) into v_n from mm_private.hits h
    where h.what = p_what and h.scope = p_scope and h.at > clock_timestamp() - p_window;
  if v_n >= p_max then return false; end if;
  insert into mm_private.hits (what, scope) values (p_what, p_scope);
  return true;
end $$;

-- Broadcasts to everyone joined to a mess. Best effort: a failed send must never undo stored work,
-- which is why it sits in its own block and why the apps also poll.
create or replace function mm_private.cast_to(p_mess uuid, p_event text, p_payload jsonb) returns void
language plpgsql set search_path = '' as $$
declare
  v_key text;
begin
  select m.channel_key into v_key from public.messes m where m.id = p_mess;
  if v_key is null then return; end if;
  begin
    perform realtime.send(p_payload, p_event, 'mess:' || p_mess::text || ':' || v_key, true);
  exception when others then
    null;
  end;
end $$;

-- A new channel key. Anyone still listening on the old topic hears nothing more (Review A S3).
create or replace function mm_private.rotate_key(p_mess uuid) returns void
language sql set search_path = '' as $$
  update public.messes m set channel_key = mm_private.rand_key() where m.id = p_mess
$$;

create or replace function mm_private.topic(p_mess uuid) returns text
language sql stable set search_path = '' as $$
  select 'mess:' || m.id::text || ':' || m.channel_key from public.messes m where m.id = p_mess
$$;

create or replace function mm_private.me_json(p_user uuid) returns jsonb
language sql stable set search_path = '' as $$
  select jsonb_build_object(
    'id', p.id, 'display_name', p.display_name, 'name_set', p.name_set,
    'colour', mm_private.colour(p.id),
    'sound_bytes', p.sound_bytes, 'sound_limit', mm_private.cfg_n('sound_limit'),
    'messes', (select count(*) from public.messes m where m.owner_id = p.id),
    'mess_limit', mm_private.cfg_n('mess_limit'),
    'terms_ok', p.terms_version is not distinct from mm_private.cfg_t('terms_version'),
    'terms_version', mm_private.cfg_t('terms_version'),
    'is_admin', mm_private.is_admin(p.id))
  from public.profiles p where p.id = p_user
$$;

create or replace function mm_private.members_json(p_mess uuid) returns jsonb
language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id, 'name', p.display_name, 'colour', mm_private.colour(p.id), 'role', mm.role)
      order by (mm.role = 'owner') desc, mm.added_at, p.id), '[]'::jsonb)
  from public.mess_members mm join public.profiles p on p.id = mm.user_id
  where mm.mess_id = p_mess
$$;

-- The Sound list of a mess. p_hide blanks the owners (share links carry no ids of people).
create or replace function mm_private.sounds_json(p_mess uuid, p_hide boolean) returns jsonb
language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'key', s.sound_key, 'sha256', s.sha256, 'src', s.src,
      'owner', case when p_hide then null else s.owner_id end,
      'fmt', 'wav',
      'bytes', coalesce(f.bytes, v.bytes, 0), 'seconds', coalesce(f.seconds, v.seconds, 0),
      'rate', coalesce(f.rate, v.rate, 0), 'kind', coalesce(f.kind, v.kind, s.kind),
      'title', coalesce(f.title, v.title, s.title), 'bpm', coalesce(f.bpm, v.bpm, 0),
      'beats', coalesce(f.beats, v.beats, 0), 'mkey', coalesce(f.mkey, v.mkey, ''),
      'state', coalesce(f.state, 'ready')) order by s.sound_key), '[]'::jsonb)
  from public.mess_sounds s
  left join public.sound_files f on s.src = 'user' and f.owner_id = s.owner_id and f.sha256 = s.sha256
  left join public.vault_sounds v on s.src = 'vault' and v.sha256 = s.sha256
  where s.mess_id = p_mess
$$;

create or replace function mm_private.batch_json(p_rev bigint, p_user uuid, p_batch uuid, p_v int, p_ops jsonb,
                                                 p_at timestamptz) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_build_object('rev', p_rev, 'user', p_user, 'batch', p_batch, 'v', p_v, 'ops', p_ops,
                            'at', mm_private.iso(p_at))
$$;

-- Every batch after p_after, oldest first. p_hide blanks the user ids.
create or replace function mm_private.batches_json(p_mess uuid, p_after bigint, p_hide boolean) returns jsonb
language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(mm_private.batch_json(o.rev, case when p_hide then null else o.user_id end,
                                                  o.batch_id, o.v, o.ops, o.at) order by o.rev), '[]'::jsonb)
  from public.mess_ops o where o.mess_id = p_mess and o.rev > p_after
$$;

-- Bytes of one person's messes plus their History, against config.docs_limit (Review A M4).
create or replace function mm_private.docs_used(p_owner uuid) returns bigint
language sql stable set search_path = '' as $$
  select coalesce((select sum(m.doc_bytes) from public.messes m where m.owner_id = p_owner), 0)
       + coalesce((select sum(v.doc_bytes) from public.mess_versions v
                   join public.messes m on m.id = v.mess_id where m.owner_id = p_owner), 0)
$$;

-- Would a save with this label make a History entry? Yes with a label, or when the newest is over 10 minutes old.
create or replace function mm_private.version_due(p_mess uuid, p_label text) returns boolean
language sql stable set search_path = '' as $$
  select p_label <> '' or not exists (
    select 1 from public.mess_versions v
    where v.mess_id = p_mess and v.at > clock_timestamp() - interval '10 minutes')
$$;

-- Adds a History entry and trims: the newest 30 without a label, the newest 20 with one, 2 MB a mess.
create or replace function mm_private.keep_version(p_mess uuid, p_rev bigint, p_doc jsonb, p_bytes int,
                                                   p_by uuid, p_label text) returns uuid
language plpgsql set search_path = '' as $$
declare
  v_id uuid;
  v_total bigint;
begin
  insert into public.mess_versions (mess_id, rev, doc, doc_bytes, made_by, label)
    values (p_mess, p_rev, p_doc, p_bytes, p_by, p_label) returning id into v_id;
  delete from public.mess_versions d where d.mess_id = p_mess and d.id in (
    select x.id from (
      select v.id, v.label, row_number() over (partition by (v.label = '') order by v.at desc, v.id) as n
      from public.mess_versions v where v.mess_id = p_mess) x
    where (x.label = '' and x.n > 30) or (x.label <> '' and x.n > 20));
  loop
    select coalesce(sum(v.doc_bytes), 0) into v_total from public.mess_versions v where v.mess_id = p_mess;
    exit when v_total <= 2097152;
    delete from public.mess_versions d where d.id = (
      select v.id from public.mess_versions v where v.mess_id = p_mess and v.id <> v_id
      order by (v.label <> ''), v.at, v.id limit 1);
    exit when not found;
  end loop;
  return v_id;
end $$;

-- Stores one batch at the next rev and tells everyone. The caller already holds the lock on the mess row.
-- This is "apply_ops's code": rename_mess, put_doc and restore_version come through here too.
create or replace function mm_private.append_batch(p_mess uuid, p_user uuid, p_batch uuid, p_ops jsonb, p_v int,
                                                   p_at timestamptz) returns bigint
language plpgsql set search_path = '' as $$
declare
  v_rev bigint;
  v_bytes int := octet_length(p_ops::text);
begin
  update public.messes m
    set rev = m.rev + 1, ops_v = greatest(m.ops_v, p_v::smallint), ops_bytes = m.ops_bytes + v_bytes,
        updated_at = p_at
    where m.id = p_mess returning m.rev into v_rev;
  insert into public.mess_ops (mess_id, rev, user_id, batch_id, v, ops, bytes, at)
    values (p_mess, v_rev, p_user, p_batch, p_v, p_ops, v_bytes, p_at);
  -- Realtime caps message size: a big batch goes out with ops null and the apps fetch it with ops_since.
  perform mm_private.cast_to(p_mess, 'ops', mm_private.batch_json(
    v_rev, p_user, p_batch, p_v, case when v_bytes > 32768 then null else p_ops end, p_at));
  return v_rev;
end $$;

-- Takes or keeps a live editing seat. False when four other people already hold one.
create or replace function mm_private.take_seat(p_mess uuid, p_user uuid, p_at timestamptz) returns boolean
language plpgsql set search_path = '' as $$
declare
  v_n int;
begin
  if not exists (select 1 from public.mess_live l where l.mess_id = p_mess and l.user_id = p_user
                   and l.seen_at > p_at - interval '90 seconds') then
    select count(*) into v_n from public.mess_live l
      where l.mess_id = p_mess and l.user_id <> p_user and l.seen_at > p_at - interval '90 seconds';
    if v_n >= mm_private.cfg_n('live_limit') then return false; end if;
  end if;
  insert into public.mess_live (mess_id, user_id, seen_at) values (p_mess, p_user, p_at)
    on conflict (mess_id, user_id) do update set seen_at = excluded.seen_at;
  return true;
end $$;

-- Takes a person out of a mess: tells the channel first, then turns the key so they hear nothing more.
create or replace function mm_private.drop_member(p_mess uuid, p_user uuid) returns void
language plpgsql set search_path = '' as $$
begin
  perform mm_private.cast_to(p_mess, 'meta', jsonb_build_object('kind', 'members', 'gone', p_user));
  delete from public.mess_members mm where mm.mess_id = p_mess and mm.user_id = p_user and mm.role <> 'owner';
  delete from public.mess_live l where l.mess_id = p_mess and l.user_id = p_user;
  perform mm_private.rotate_key(p_mess);
end $$;

-- p_me blocks p_user: they leave every mess of mine, I leave every mess of theirs.
create or replace function mm_private.do_block(p_me uuid, p_user uuid) returns void
language plpgsql set search_path = '' as $$
declare
  v_mess uuid;
begin
  insert into public.user_blocks (owner_id, blocked_id) values (p_me, p_user) on conflict do nothing;
  for v_mess in
    select m.id from public.messes m join public.mess_members mm on mm.mess_id = m.id
    where (m.owner_id = p_me and mm.user_id = p_user) or (m.owner_id = p_user and mm.user_id = p_me)
    order by m.id
  loop
    perform 1 from public.messes m where m.id = v_mess for update;
    perform mm_private.drop_member(v_mess, case when (select m.owner_id from public.messes m where m.id = v_mess) = p_me
                                                then p_user else p_me end);
  end loop;
end $$;

-- Takes a mess down or puts it back. Down: the share link dies, the channel is told, the key turns.
create or replace function mm_private.block_mess(p_mess uuid, p_blocked boolean) returns void
language plpgsql set search_path = '' as $$
begin
  if p_blocked then
    perform mm_private.cast_to(p_mess, 'meta', jsonb_build_object('kind', 'blocked'));
    update public.messes m
      set blocked = true, share_mode = 'off', share_token = null, channel_key = mm_private.rand_key()
      where m.id = p_mess;
  else
    update public.messes m set blocked = false where m.id = p_mess;
  end if;
end $$;

-- A file is going away (its maker deleted it or left, or an admin took it down): every mess that lists it
-- switches to the Vault copy when there is one, else to 'gone'. Returns how many messes it touched.
create or replace function mm_private.detach_file(p_owner uuid, p_sha256 text, p_allow_vault boolean) returns int
language plpgsql set search_path = '' as $$
declare
  v_vault boolean := p_allow_vault and exists (select 1 from public.vault_sounds v where v.sha256 = p_sha256);
  v_mess uuid;
  v_n int := 0;
begin
  for v_mess in
    select distinct s.mess_id from public.mess_sounds s where s.owner_id = p_owner and s.sha256 = p_sha256
  loop
    v_n := v_n + 1;
    perform mm_private.cast_to(v_mess, 'meta', jsonb_build_object('kind', 'sound', 'sha256', p_sha256));
  end loop;
  update public.mess_sounds s
    set src = case when v_vault then 'vault' else 'gone' end, owner_id = null,
        title = coalesce((select f.title from public.sound_files f
                          where f.owner_id = p_owner and f.sha256 = p_sha256), s.title),
        kind = coalesce((select f.kind from public.sound_files f
                         where f.owner_id = p_owner and f.sha256 = p_sha256), s.kind)
    where s.owner_id = p_owner and s.sha256 = p_sha256;
  return v_n;
end $$;

-- The last step of deleting a sound: the row goes and the bytes come off the total.
create or replace function mm_private.forget_file(p_owner uuid, p_sha256 text) returns void
language plpgsql set search_path = '' as $$
declare
  v_file public.sound_files;
begin
  delete from public.sound_files f where f.owner_id = p_owner and f.sha256 = p_sha256 returning * into v_file;
  if found and v_file.counted then
    update public.profiles p set sound_bytes = greatest(p.sound_bytes - v_file.bytes, 0) where p.id = p_owner;
  end if;
end $$;

-- How big is this object in Storage really? Null when it is not there (or Storage has not sized it yet).
create or replace function mm_private.object_size(p_bucket text, p_name text) returns bigint
language sql stable set search_path = '' as $$
  select (o.metadata ->> 'size')::bigint from storage.objects o
  where o.bucket_id = p_bucket and o.name = p_name
$$;

create or replace function mm_private.object_there(p_bucket text, p_name text) returns boolean
language sql stable set search_path = '' as $$
  select exists (select 1 from storage.objects o where o.bucket_id = p_bucket and o.name = p_name)
$$;

-- Makes the profile row when someone signs in for the first time.
create or replace function mm_private.on_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
    values (new.id, 'Player ' || lpad(floor(random() * 10000)::int::text, 4, '0'))
    on conflict (id) do nothing;
  return new;
end $$;

create or replace trigger mm_on_new_user after insert on auth.users
  for each row execute function mm_private.on_new_user();

-- Anyone who signed up before this file ran gets a profile too.
insert into public.profiles (id, display_name)
  select u.id, 'Player ' || lpad(floor(random() * 10000)::int::text, 4, '0') from auth.users u
  on conflict (id) do nothing;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;
