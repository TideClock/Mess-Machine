-- Mess Machine accounts: every file of supabase/migrations/ in name order, for pasting into the dashboard's SQL Editor in one go.
-- Made by tools/sql-all.sh; do not edit here. Safe to run twice.

-- ===== 20261124100000_mm_tables.sql =====
-- Mess Machine accounts, file 1 of 9: who may touch what by default, the schemas, every table.
-- Contract: docs/ACCOUNTS-API.md. Safe to run again (nothing here drops or overwrites data).
--
-- The rule for the whole server: the app never reads or writes a table. Row level security is on for
-- every table, there is no table policy at all, and the client roles hold no table privilege. Only the
-- functions in the later files touch tables.

-- Review A S1. On Supabase a new table or function in "public" is open to anon and authenticated until
-- someone remembers a revoke. Close that first, for everything this role creates from now on.
alter default privileges revoke execute on functions from public;
alter default privileges in schema public revoke all on functions from anon, authenticated, service_role;
alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;

create schema if not exists mm_private;   -- helpers and private tables: no client role may use it
create schema if not exists mm_policy;    -- only the helpers that Storage and Realtime policies call
revoke all on schema mm_private from public, anon, authenticated, service_role;
revoke all on schema mm_policy from public, anon, authenticated, service_role;
grant usage on schema mm_policy to authenticated;

-- Numbers the functions read. Change a limit here, in the SQL editor, with no new app build.
create table if not exists public.config (
  key text primary key,
  value jsonb not null
);
insert into public.config (key, value) values
  ('terms_version', '"2026-11"'),
  ('sound_limit', '262144000'),      -- bytes of sounds per person (250 MB)
  ('mess_limit', '200'),             -- messes one person may own
  ('member_limit', '8'),             -- people in one mess
  ('live_limit', '4'),               -- people editing one mess at once
  ('mess_bytes', '1048576'),         -- one mess document (1 MB)
  ('sound_seconds', '100'),
  ('sound_bytes', '20971520'),       -- one sound file (20 MB)
  ('docs_limit', '31457280'),        -- one person's messes plus their History (30 MB)
  ('ops_v_max', '1'),                -- the newest op format any released app sends
  ('ops_day_bytes', '20971520'),     -- edits one person may send in a day (20 MB)
  ('review_user', 'null'),           -- the store review account's user id, as a JSON string
  ('share_urls_day', '300')          -- sound link requests per share link per day (server only)
on conflict (key) do nothing;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 24),
  name_set boolean not null default false,
  sound_bytes bigint not null default 0 check (sound_bytes >= 0),
  terms_version text,
  terms_at timestamptz,
  banned boolean not null default false,
  created_at timestamptz not null default now()
);

-- Who may review the Vault and act on reports. Rows are added by hand in the SQL editor. Never a client claim.
create table if not exists public.admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  added_at timestamptz not null default now()
);

create table if not exists public.terms_acceptances (
  user_id uuid not null references public.profiles(id) on delete cascade,
  version text not null,
  at timestamptz not null default now(),
  primary key (user_id, version)
);

create table if not exists public.messes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id),
  name text not null check (char_length(name) between 1 and 48),
  doc jsonb not null check (jsonb_typeof(doc) = 'object'),
  doc_bytes int not null check (doc_bytes between 2 and 1048576),
  rev bigint not null default 0,
  doc_rev bigint not null default 0,
  ops_v smallint not null default 1,
  ops_bytes bigint not null default 0 check (ops_bytes >= 0),   -- bytes of ops stored after doc_rev
  op_mode boolean not null default false,                       -- true once a batch has come through apply_ops
  channel_key text not null default left(encode(sha256(uuid_send(gen_random_uuid())), 'hex'), 16)
    check (channel_key ~ '^[0-9a-f]{16}$'),
  forked_from uuid references public.messes(id) on delete set null,
  share_mode text not null default 'off' check (share_mode in ('off', 'view', 'copy')),
  share_token text unique check (share_token is null or share_token ~ '^[A-Za-z0-9_-]{32}$'),
  blocked boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (doc_rev <= rev),
  check ((share_mode = 'off') = (share_token is null))
);
create index if not exists messes_owner on public.messes (owner_id);

create table if not exists public.mess_members (
  mess_id uuid not null references public.messes(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null check (role in ('owner', 'editor')),
  added_by uuid references public.profiles(id) on delete set null,
  added_at timestamptz not null default now(),
  primary key (mess_id, user_id)
);
create index if not exists mess_members_user on public.mess_members (user_id);
create unique index if not exists mess_one_owner on public.mess_members (mess_id) where role = 'owner';

create table if not exists public.mess_ops (
  mess_id uuid not null references public.messes(id) on delete cascade,
  rev bigint not null check (rev > 0),
  user_id uuid references public.profiles(id) on delete set null,
  batch_id uuid not null,
  v smallint not null default 1,
  ops jsonb not null check (jsonb_typeof(ops) = 'array'),
  bytes int not null default 0,
  at timestamptz not null default clock_timestamp(),
  primary key (mess_id, rev),
  unique (mess_id, batch_id)
);
create index if not exists mess_ops_rate on public.mess_ops (mess_id, user_id, at desc);

create table if not exists public.mess_versions (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid not null references public.messes(id) on delete cascade,
  rev bigint not null,
  doc jsonb not null,
  doc_bytes int not null,
  made_by uuid references public.profiles(id) on delete set null,
  label text not null default '' check (char_length(label) <= 40),
  at timestamptz not null default clock_timestamp()
);
create index if not exists mess_versions_mess on public.mess_versions (mess_id, at desc);

create table if not exists public.mess_live (
  mess_id uuid not null references public.messes(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  seen_at timestamptz not null default clock_timestamp(),
  primary key (mess_id, user_id)
);

-- Single use, always an editor. Only the sha256 of the token is kept.
create table if not exists public.invites (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid not null references public.messes(id) on delete cascade,
  token_hash text not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
  expires_at timestamptz not null,
  uses_left int not null default 1 check (uses_left >= 0),
  revoked boolean not null default false,
  made_by uuid not null references public.profiles(id) on delete cascade,
  at timestamptz not null default now()
);
create index if not exists invites_mess on public.invites (mess_id);

-- owner_id is the person who did the blocking.
create table if not exists public.user_blocks (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  at timestamptz not null default now(),
  primary key (owner_id, blocked_id)
);

-- One stored audio file of one person. The object is sounds/<owner_id>/<sha256>.wav.
-- counted: its bytes are inside profiles.sound_bytes (true from finish_upload until the row goes).
create table if not exists public.sound_files (
  owner_id uuid not null references public.profiles(id),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  bytes bigint not null check (bytes > 0),
  seconds real not null check (seconds > 0),
  rate int not null check (rate between 8000 and 96000),
  kind text not null check (kind in ('imp', 'mic', 'rs', 'take')),
  local_key text not null default '' check (char_length(local_key) <= 80),
  title text not null default '' check (char_length(title) <= 40),
  bpm real not null default 0,
  beats int not null default 0,
  mkey text not null default '' check (char_length(mkey) <= 24),
  state text not null default 'pending' check (state in ('pending', 'ready', 'deleting')),
  counted boolean not null default false,
  blocked boolean not null default false,
  at timestamptz not null default now(),
  primary key (owner_id, sha256)
);
create index if not exists sound_files_sha on public.sound_files (sha256);

-- A user sound a mess needs. src: 'user' = the owner's file, 'vault' = the Vault copy, 'gone' = its maker
-- left or it was taken down. Rows stay for the life of the mess (Review B S8).
create table if not exists public.mess_sounds (
  mess_id uuid not null references public.messes(id) on delete cascade,
  sound_key text not null check (char_length(sound_key) between 1 and 80),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  owner_id uuid,
  src text not null default 'user' check (src in ('user', 'vault', 'gone')),
  title text not null default '',
  kind text not null default 'imp',
  added_by uuid references public.profiles(id) on delete set null,
  added_rev bigint not null default 0,
  primary key (mess_id, sound_key),
  foreign key (owner_id, sha256) references public.sound_files (owner_id, sha256),
  check ((src = 'user') = (owner_id is not null))
);
create index if not exists mess_sounds_file on public.mess_sounds (owner_id, sha256);
create index if not exists mess_sounds_sha on public.mess_sounds (sha256);

-- The exact grant texts people have been shown. A new wording is a new row; old rows never change.
create table if not exists public.vault_grants (
  version text primary key,
  body text not null,
  body_sha256 text not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
  at timestamptz not null default now()
);

create table if not exists public.vault_submissions (
  id uuid primary key default gen_random_uuid(),
  submitter_id uuid references public.profiles(id) on delete set null,
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  title text not null check (char_length(title) between 1 and 40),
  credit_name text not null check (char_length(credit_name) between 1 and 40),
  grant_version text not null references public.vault_grants(version),
  note text not null default '' check (char_length(note) <= 200),
  status text not null default 'pending' check (status in ('pending', 'approved', 'declined', 'withdrawn')),
  reviewer_id uuid references auth.users(id) on delete set null,
  reviewer_note text not null default '' check (char_length(reviewer_note) <= 300),
  bytes bigint not null, seconds real not null, rate int not null, kind text not null,
  bpm real not null default 0, beats int not null default 0, mkey text not null default '',
  submitted_at timestamptz not null default now(),
  reviewed_at timestamptz
);
create unique index if not exists vault_one_open on public.vault_submissions (submitter_id, sha256)
  where status in ('pending', 'approved');
create index if not exists vault_by_status on public.vault_submissions (status, submitted_at);

-- The Vault's own copy of an approved sound: vault/<sha256>.wav. Not tied to any account.
-- The grant is frozen here at approval (Review A S5); granter has no foreign key on purpose.
create table if not exists public.vault_sounds (
  sha256 text primary key check (sha256 ~ '^[0-9a-f]{64}$'),
  submission_id uuid not null references public.vault_submissions(id),
  title text not null,
  credit_name text not null,
  bytes bigint not null, seconds real not null, rate int not null, kind text not null,
  bpm real not null default 0, beats int not null default 0, mkey text not null default '',
  grant_version text not null,
  body_sha256 text not null,
  granted_at timestamptz not null,
  granter uuid,
  approved_by uuid references auth.users(id) on delete set null,
  approved_at timestamptz not null default now()
);

create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid references public.messes(id) on delete set null,
  mess_name text not null default '',
  owner_id uuid,
  about_user uuid,
  reporter_id uuid references public.profiles(id) on delete set null,
  via_token boolean not null default false,
  reason text not null check (reason in ('sexual', 'hate', 'harassment', 'copyright', 'spam', 'other')),
  details text not null default '' check (char_length(details) <= 500),
  status text not null default 'open' check (status in ('open', 'actioned', 'dismissed')),
  admin_note text not null default '',
  at timestamptz not null default now(),
  resolved_at timestamptz
);
create index if not exists reports_open on public.reports (status, at);

-- Private tables. Nothing outside the functions ever sees them.

-- An account deletion that was asked for. The daily sweep finishes any row with no done_at (Review A M6).
create table if not exists mm_private.deletions (
  user_id uuid primary key,
  asked_at timestamptz not null default now(),
  done_at timestamptz
);

-- Edits sent per person per day (Review A M4).
create table if not exists mm_private.usage (
  user_id uuid not null,
  day date not null,
  op_bytes bigint not null default 0,
  primary key (user_id, day)
);

-- One row per counted action, for the "so many a minute" and "so many a day" limits.
create table if not exists mm_private.hits (
  what text not null,
  scope text not null,
  at timestamptz not null default clock_timestamp()
);
create index if not exists hits_lookup on mm_private.hits (what, scope, at);

-- Sounds an admin took down. A file with one of these hashes can never be attached to a mess again.
create table if not exists mm_private.blocked_sounds (
  sha256 text primary key,
  at timestamptz not null default now()
);

-- Words a display name, a mess name or a Vault credit may not hold. Add or remove rows in the SQL editor.
-- anywhere = false: only as a whole word. anywhere = true: also inside a longer word.
create table if not exists mm_private.bad_words (
  word text primary key check (word = lower(word)),
  anywhere boolean not null default false
);
insert into mm_private.bad_words (word, anywhere) values
  ('nigger', true), ('nigga', true), ('faggot', true), ('kike', false), ('spic', false), ('chink', false),
  ('tranny', false), ('retard', false), ('cunt', false), ('rape', false), ('rapist', false), ('nazi', false),
  ('hitler', true), ('porn', false), ('pedo', false), ('pedophile', true), ('whore', false), ('slut', false)
on conflict (word) do nothing;

alter table public.config            enable row level security;
alter table public.profiles          enable row level security;
alter table public.admins            enable row level security;
alter table public.terms_acceptances enable row level security;
alter table public.messes            enable row level security;
alter table public.mess_members      enable row level security;
alter table public.mess_ops          enable row level security;
alter table public.mess_versions     enable row level security;
alter table public.mess_live         enable row level security;
alter table public.invites           enable row level security;
alter table public.user_blocks       enable row level security;
alter table public.sound_files       enable row level security;
alter table public.mess_sounds       enable row level security;
alter table public.vault_grants      enable row level security;
alter table public.vault_submissions enable row level security;
alter table public.vault_sounds      enable row level security;
alter table public.reports           enable row level security;
alter table mm_private.deletions      enable row level security;
alter table mm_private.usage          enable row level security;
alter table mm_private.hits           enable row level security;
alter table mm_private.blocked_sounds enable row level security;
alter table mm_private.bad_words      enable row level security;

-- Belt and braces for a project where these tables were made before the defaults above were set.
revoke all on all tables in schema public from anon, authenticated;
revoke all on all tables in schema mm_private from public, anon, authenticated, service_role;
revoke all on all sequences in schema public from anon, authenticated;

-- ===== 20261124100100_mm_helpers.sql =====
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

-- ===== 20261124100200_mm_slice_a.sql =====
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

-- ===== 20261124100300_mm_slice_b.sql =====
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

-- ===== 20261124100400_mm_slice_c.sql =====
-- Mess Machine accounts, file 5 of 9. Slice C: live feel (ACCOUNTS-API.md 1.6 and section 2, Realtime).
-- No new call. The functions already send 'ops' and 'meta' through mm_private.cast_to. This file is the
-- rule for who may listen.
--
-- Topic: mess:<mess uuid>:<channel_key>. The key is turned when someone is removed or blocked and when a
-- mess is taken down, so a socket that is still joined to the old topic hears nothing more (Review A S3).
-- In the dashboard, Realtime settings, "Allow public access" must be off: that is what makes the channels
-- private.

-- May the caller join this topic? A good sign-in, a member of that mess, and the key is the current one.
create or replace function mm_policy.may_listen(p_topic text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  v_me uuid := (select auth.uid());
  v_mess uuid;
begin
  if p_topic is null
     or p_topic !~ '^mess:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{16}$' then
    return false;
  end if;
  if not mm_private.valid_user(v_me, (select auth.jwt()) -> 'amr') then return false; end if;
  v_mess := substr(p_topic, 6, 36)::uuid;
  return mm_private.role_of(v_me, v_mess) is not null
     and exists (select 1 from public.messes m where m.id = v_mess and m.channel_key = substr(p_topic, 43));
end $$;

-- Members hear broadcasts and see presence. Members may send presence. There is no insert policy for
-- 'broadcast': no client can put a message on a channel; only the functions can.
-- (Row level security is already on for realtime.messages. Do not add an "enable" line for it here.)
drop policy if exists mm_rt_listen on realtime.messages;
create policy mm_rt_listen on realtime.messages for select to authenticated
  using (extension in ('broadcast', 'presence') and mm_policy.may_listen((select realtime.topic())));
drop policy if exists mm_rt_presence on realtime.messages;
create policy mm_rt_presence on realtime.messages for insert to authenticated
  with check (extension = 'presence' and mm_policy.may_listen((select realtime.topic())));

revoke all on all functions in schema mm_policy from public, anon, authenticated, service_role;
grant execute on function mm_policy.may_upload(text), mm_policy.may_remove(text), mm_policy.is_admin(),
  mm_policy.may_listen(text) to authenticated;

-- ===== 20261124100500_mm_slice_d.sql =====
-- Mess Machine accounts, file 6 of 9. Slice D: share links (view and copy) and History (ACCOUNTS-API.md 1.7).

-- The mess a share link opens, or null: the token matches, sharing is on, the mess is not taken down and
-- its owner is not banned.
create or replace function mm_private.shared_mess(p_token text) returns public.messes
language sql stable set search_path = '' as $$
  select m.* from public.messes m join public.profiles p on p.id = m.owner_id
  where p_token is not null and m.share_token = p_token and m.share_mode <> 'off'
    and not m.blocked and not p.banned
$$;

-- T. Owner only. Turning sharing on, or p_new, makes a fresh link and the old one dies.
create or replace function public.set_share(p_mess uuid, p_mode text, p_new boolean default false) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_token text;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  v_role := mm_private.role_of(v_me, p_mess);
  if v_role is null then perform mm_private.fail('not_found'); end if;
  if v_role <> 'owner' then perform mm_private.fail('forbidden'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  if p_mode is null or p_mode not in ('off', 'view', 'copy') then perform mm_private.fail('bad_input'); end if;
  v_token := case when p_mode = 'off' then null
                  when v_m.share_token is null or coalesce(p_new, false) then mm_private.rand_token()
                  else v_m.share_token end;
  update public.messes m set share_mode = p_mode, share_token = v_token where m.id = p_mess;
  return jsonb_build_object('share_mode', p_mode, 'share_token', v_token);
end $$;

-- anon. What a share link shows. It carries no ids of people.
create or replace function public.open_shared(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_m public.messes := mm_private.shared_mess(p_token);
begin
  if v_m.id is null then perform mm_private.fail('not_found'); end if;
  return jsonb_build_object(
    'name', v_m.name,
    'owner_name', (select p.display_name from public.profiles p where p.id = v_m.owner_id),
    'mode', v_m.share_mode, 'rev', v_m.rev, 'doc_rev', v_m.doc_rev, 'doc', v_m.doc,
    'ops', mm_private.batches_json(v_m.id, v_m.doc_rev, true),
    'sounds', mm_private.sounds_json(v_m.id, true));
end $$;

-- T. A copy of a mess I can read: I am on it, or I hold a "copy" share link. The app sends the doc
-- because the server never applies ops.
create or replace function public.fork_mess(p_id uuid, p_from uuid, p_token text, p_name text, p_doc jsonb)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_old public.messes;
  v_src public.messes;
  v_out jsonb;
begin
  perform mm_private.need_terms(v_me);
  perform 1 from public.profiles p where p.id = v_me for update;
  select * into v_old from public.messes m where m.id = p_id;
  if found then
    if v_old.owner_id = v_me then return jsonb_build_object('id', v_old.id, 'rev', v_old.rev); end if;
    perform mm_private.fail('bad_input');
  end if;
  if p_from is not null and mm_private.role_of(v_me, p_from) is not null then
    select * into v_src from public.messes m where m.id = p_from;
  else
    v_src := mm_private.shared_mess(p_token);
    if v_src.id is null then perform mm_private.fail('not_found'); end if;
    if v_src.share_mode <> 'copy' then perform mm_private.fail('no_copy'); end if;
  end if;
  v_out := mm_private.new_mess(v_me, p_id, p_name, p_doc, v_src.id);
  insert into public.mess_sounds (mess_id, sound_key, sha256, owner_id, src, title, kind, added_by, added_rev)
    select p_id, s.sound_key, s.sha256, s.owner_id, s.src, s.title, s.kind, v_me, 0
    from public.mess_sounds s where s.mess_id = v_src.id;
  return v_out;
end $$;

create or replace function public.versions(p_mess uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', v.id, 'rev', v.rev, 'label', v.label,
        'by_name', coalesce((select p.display_name from public.profiles p where p.id = v.made_by), ''),
        'at', mm_private.iso(v.at), 'bytes', v.doc_bytes) order by v.at desc, v.id), '[]'::jsonb)
    from public.mess_versions v where v.mess_id = p_mess);
end $$;

create or replace function public.get_version(p_version uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_v public.mess_versions;
begin
  select * into v_v from public.mess_versions v where v.id = p_version;
  if not found or mm_private.role_of(v_me, v_v.mess_id) is null then perform mm_private.fail('not_found'); end if;
  return jsonb_build_object('id', v_v.id, 'rev', v_v.rev, 'label', v_v.label, 'doc', v_v.doc);
end $$;

-- T. Puts an old version back. The current mess is kept first, as "Before restore".
create or replace function public.restore_version(p_mess uuid, p_version uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_m public.messes;
  v_v public.mess_versions;
  v_rev bigint;
begin
  perform mm_private.need_terms(v_me);
  select * into v_m from public.messes m where m.id = p_mess for update;
  if mm_private.role_of(v_me, p_mess) is null then perform mm_private.fail('not_found'); end if;
  if v_m.blocked then perform mm_private.fail('blocked'); end if;
  -- the version must belong to this mess (Review A S8)
  select * into v_v from public.mess_versions v where v.id = p_version and v.mess_id = p_mess;
  if not found then perform mm_private.fail('not_found'); end if;
  if v_m.doc_rev <> v_m.rev then perform mm_private.fail('snapshot_first'); end if;
  v_rev := mm_private.append_batch(p_mess, v_me, gen_random_uuid(), '[{"t":"doc.reset"}]'::jsonb, 1, clock_timestamp());
  update public.messes m set doc = v_v.doc, doc_bytes = v_v.doc_bytes, doc_rev = v_rev, ops_bytes = 0 where m.id = p_mess;
  delete from public.mess_ops o where o.mess_id = p_mess and o.rev < v_rev - 500;
  perform mm_private.keep_version(p_mess, v_m.rev, v_m.doc, v_m.doc_bytes, v_me, 'Before restore');
  return jsonb_build_object('rev', v_rev);
end $$;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;

grant execute on function
  public.set_share(uuid, text, boolean), public.fork_mess(uuid, uuid, text, text, jsonb),
  public.versions(uuid), public.get_version(uuid), public.restore_version(uuid, uuid)
  to authenticated;
grant execute on function public.open_shared(text) to anon, authenticated;

-- ===== 20261124100600_mm_slice_e.sql =====
-- Mess Machine accounts, file 7 of 9. Slice E: store requirements (ACCOUNTS-API.md 1.8).
-- Report a mess or a person, and what an admin can do about it. Deleting an account is in file 9.

-- anon. A member reports a mess (or a person in it), or anyone holding a share link reports that mess.
create or replace function public.report_mess(p_mess uuid, p_token text, p_reason text, p_details text default '',
                                              p_user uuid default null) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller_or_null();
  v_role text;
  v_m public.messes;
  v_details text := coalesce(p_details, '');
  v_via boolean := false;
  v_id uuid;
begin
  if p_reason is null or p_reason not in ('sexual', 'hate', 'harassment', 'copyright', 'spam', 'other')
     or char_length(v_details) > 500 then
    perform mm_private.fail('bad_input');
  end if;
  -- the share page sends no mess id, only the token; with neither there is nothing to report
  if p_mess is null and coalesce(p_token, '') = '' then perform mm_private.fail('bad_input'); end if;
  if v_me is not null and p_mess is not null then v_role := mm_private.role_of(v_me, p_mess); end if;
  if v_role is not null then
    select * into v_m from public.messes m where m.id = p_mess;
    if p_user is null then
      if v_role = 'owner' then perform mm_private.fail('bad_input'); end if;   -- not your own mess
    elsif p_user = v_me or not exists (select 1 from public.mess_members mm
                                       where mm.mess_id = p_mess and mm.user_id = p_user) then
      perform mm_private.fail('not_found');                                     -- must be another member
    end if;
    if not mm_private.hit('report', v_me::text, interval '1 day', 5) then perform mm_private.fail('rate_limited'); end if;
  else
    v_m := mm_private.shared_mess(p_token);
    if v_m.id is null then perform mm_private.fail('not_found'); end if;
    if p_user is not null then perform mm_private.fail('bad_input'); end if;
    if not mm_private.hit('report_link', v_m.id::text, interval '1 day', 20) then
      perform mm_private.fail('rate_limited');
    end if;
    v_via := true;
  end if;
  insert into public.reports (mess_id, mess_name, owner_id, about_user, reporter_id, via_token, reason, details)
    values (v_m.id, v_m.name, v_m.owner_id, p_user, v_me, v_via, p_reason, v_details)
    returning id into v_id;
  return jsonb_build_object('id', v_id);
end $$;

create or replace function mm_private.report_json(p_r public.reports) returns jsonb
language sql stable set search_path = '' as $$
  select jsonb_build_object('id', p_r.id, 'mess_id', p_r.mess_id, 'mess_name', p_r.mess_name,
    'owner_id', p_r.owner_id, 'about_user', p_r.about_user, 'reason', p_r.reason, 'details', p_r.details,
    'via_token', p_r.via_token, 'status', p_r.status, 'at', mm_private.iso(p_r.at))
$$;

create or replace function public.admin_reports(p_status text default 'open') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  perform mm_private.need_admin(v_me);
  return (select coalesce(jsonb_agg(mm_private.report_json(r) order by r.at, r.id), '[]'::jsonb)
          from public.reports r where r.status = coalesce(p_status, 'open'));
end $$;

-- The mess behind an open report. The files come from the sound-urls function with {"report": id}.
create or replace function public.admin_open_report(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_r public.reports;
begin
  perform mm_private.need_admin(v_me);
  select * into v_r from public.reports r where r.id = p_id and r.status = 'open';
  if not found then perform mm_private.fail('not_found'); end if;
  return jsonb_build_object('report', mm_private.report_json(v_r),
    'doc', (select m.doc from public.messes m where m.id = v_r.mess_id),
    'sounds', mm_private.sounds_json(v_r.mess_id, false));
end $$;

create or replace function public.admin_block_mess(p_mess uuid, p_blocked boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  perform mm_private.need_admin(v_me);
  perform 1 from public.messes m where m.id = p_mess for update;
  if not found then perform mm_private.fail('not_found'); end if;
  perform mm_private.block_mess(p_mess, coalesce(p_blocked, false));
  return jsonb_build_object('blocked', coalesce(p_blocked, false));
end $$;

create or replace function public.admin_resolve_report(p_id uuid, p_action text, p_note text default '') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_r public.reports;
  v_status text;
begin
  perform mm_private.need_admin(v_me);
  select * into v_r from public.reports r where r.id = p_id and r.status = 'open' for update;
  if not found then perform mm_private.fail('not_found'); end if;
  if p_action is null or p_action not in ('dismiss', 'block_mess') then perform mm_private.fail('bad_input'); end if;
  if p_action = 'block_mess' then
    perform 1 from public.messes m where m.id = v_r.mess_id for update;
    if found then perform mm_private.block_mess(v_r.mess_id, true); end if;
    v_status := 'actioned';
  else
    v_status := 'dismissed';
  end if;
  update public.reports r set status = v_status, admin_note = left(coalesce(p_note, ''), 300), resolved_at = now()
    where r.id = p_id;
  return jsonb_build_object('status', v_status);
end $$;

-- A ban: every call and every channel refuses them at once, their messes come down, their links die.
create or replace function public.admin_ban_user(p_user uuid, p_banned boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_mess uuid;
begin
  perform mm_private.need_admin(v_me);
  update public.profiles p set banned = coalesce(p_banned, false) where p.id = p_user;
  if not found then perform mm_private.fail('not_found'); end if;
  if coalesce(p_banned, false) then
    for v_mess in
      select mm.mess_id from public.mess_members mm where mm.user_id = p_user order by mm.mess_id
    loop
      perform 1 from public.messes m where m.id = v_mess for update;
      if exists (select 1 from public.messes m where m.id = v_mess and m.owner_id = p_user) then
        perform mm_private.block_mess(v_mess, true);
      else
        -- a mess they only edit: they stay listed, but a socket they still hold goes quiet
        perform mm_private.cast_to(v_mess, 'meta', jsonb_build_object('kind', 'members', 'gone', p_user));
        delete from public.mess_live l where l.mess_id = v_mess and l.user_id = p_user;
        perform mm_private.rotate_key(v_mess);
      end if;
    end loop;
  end if;
  return jsonb_build_object('banned', coalesce(p_banned, false));
end $$;

-- Takes one sound down everywhere: every mess that lists this hash, whoever uploaded it, reads 'gone',
-- and the hash can never be attached again (a take-down must not miss copies and forks, Review A S4).
create or replace function public.admin_block_sound(p_owner uuid, p_sha256 text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_n int;
  v_mess uuid;
begin
  perform mm_private.need_admin(v_me);
  if not exists (select 1 from public.sound_files f where f.owner_id = p_owner and f.sha256 = p_sha256) then
    perform mm_private.fail('not_found');
  end if;
  insert into mm_private.blocked_sounds (sha256) values (p_sha256) on conflict do nothing;
  update public.sound_files f set blocked = true where f.sha256 = p_sha256;
  select count(distinct s.mess_id) into v_n from public.mess_sounds s where s.sha256 = p_sha256 and s.src <> 'gone';
  for v_mess in select distinct s.mess_id from public.mess_sounds s where s.sha256 = p_sha256 and s.src <> 'gone' loop
    perform mm_private.cast_to(v_mess, 'meta', jsonb_build_object('kind', 'sound', 'sha256', p_sha256));
  end loop;
  update public.mess_sounds s set src = 'gone', owner_id = null where s.sha256 = p_sha256 and s.src <> 'gone';
  return jsonb_build_object('messes', v_n);
end $$;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;

grant execute on function
  public.admin_reports(text), public.admin_open_report(uuid), public.admin_resolve_report(uuid, text, text),
  public.admin_block_mess(uuid, boolean), public.admin_ban_user(uuid, boolean),
  public.admin_block_sound(uuid, text)
  to authenticated;
grant execute on function public.report_mess(uuid, text, text, text, uuid) to anon, authenticated;

-- ===== 20261124100700_mm_slice_f.sql =====
-- Mess Machine accounts, file 8 of 9. Slice F: the Vault (ACCOUNTS-API.md 1.9).
-- People offer a sound; an admin listens, copies the file to the vault bucket and approves. An approved
-- sound has its own copy and its own facts, so it outlives its maker's account, with credit.

-- The first grant text. A new wording is a new row with a new version; old rows never change, because
-- they are what people agreed to. Replace this with the owner's own wording before the Vault opens:
-- add a row, do not edit this one.
insert into public.vault_grants (version, body, body_sha256)
  select '2026-11', g.body, encode(sha256(convert_to(g.body, 'UTF8')), 'hex')
  from (select 'By sending this sound to the Mess Machine Vault you say that you made it, or have the right '
            || 'to share it, and you let Mess Machine include it in the app and on its website for anyone to '
            || 'play and use in their own music, free of charge, with credit to the name you give. If it is '
            || 'approved it stays in the Vault even if you later delete your account. A sound still waiting '
            || 'for review when you delete your account is withdrawn and deleted. You keep your own rights '
            || 'to the sound.' as body) g
  on conflict (version) do nothing;

-- anon. The newest grant text.
create or replace function public.vault_grant() returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  return coalesce(
    (select jsonb_build_object('version', g.version, 'body', g.body, 'body_sha256', g.body_sha256)
     from public.vault_grants g order by g.at desc, g.version desc limit 1),
    jsonb_build_object('version', '', 'body', '', 'body_sha256', ''));
end $$;

-- T. Offer one of my sounds.
create or replace function public.vault_submit(p_sha256 text, p_credit_name text, p_grant_version text,
                                               p_note text default '') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_file public.sound_files;
  v_credit text := btrim(p_credit_name);
  v_note text := coalesce(p_note, '');
  v_id uuid;
begin
  perform mm_private.need_terms(v_me);
  perform 1 from public.profiles p where p.id = v_me for update;
  if char_length(v_note) > 200 then perform mm_private.fail('bad_input'); end if;
  select * into v_file from public.sound_files f
    where f.owner_id = v_me and f.sha256 = p_sha256 and f.state = 'ready' and not f.blocked;
  if not found then perform mm_private.fail('not_ready'); end if;
  if p_grant_version is distinct from (select g.version from public.vault_grants g
                                       order by g.at desc, g.version desc limit 1) then
    perform mm_private.fail('grant_outdated');
  end if;
  if v_credit is null or char_length(v_credit) not between 1 and 40 or not mm_private.clean_name(v_credit) then
    perform mm_private.fail('bad_name');
  end if;
  if exists (select 1 from public.vault_submissions s
             where s.submitter_id = v_me and s.sha256 = p_sha256 and s.status in ('pending', 'approved'))
     or exists (select 1 from public.vault_sounds v where v.sha256 = p_sha256) then
    perform mm_private.fail('already_submitted');
  end if;
  if (select count(*) from public.vault_submissions s
      where s.submitter_id = v_me and s.status = 'pending') >= 10 then
    perform mm_private.fail('too_many_pending');
  end if;
  insert into public.vault_submissions (submitter_id, sha256, title, credit_name, grant_version, note,
                                        bytes, seconds, rate, kind, bpm, beats, mkey)
    values (v_me, p_sha256, coalesce(nullif(btrim(v_file.title), ''), 'Untitled'), v_credit, p_grant_version, v_note,
            v_file.bytes, v_file.seconds, v_file.rate, v_file.kind, v_file.bpm, v_file.beats, v_file.mkey)
    returning id into v_id;
  return jsonb_build_object('id', v_id, 'status', 'pending');
end $$;

create or replace function public.vault_mine() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', s.id, 'sha256', s.sha256, 'title', s.title, 'credit_name', s.credit_name, 'status', s.status,
        'reviewer_note', s.reviewer_note, 'submitted_at', mm_private.iso(s.submitted_at),
        'reviewed_at', mm_private.iso(s.reviewed_at)) order by s.submitted_at desc, s.id), '[]'::jsonb)
    from public.vault_submissions s where s.submitter_id = v_me);
end $$;

create or replace function public.vault_withdraw(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  update public.vault_submissions s set status = 'withdrawn', reviewed_at = now()
    where s.id = p_id and s.submitter_id = v_me and s.status = 'pending';
  if not found then perform mm_private.fail('not_found'); end if;
  return jsonb_build_object('status', 'withdrawn');
end $$;

-- Admin. others: other people who hold a file with the same hash, with dates, so a copied sound shows.
create or replace function public.vault_queue(p_status text default 'pending') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
begin
  perform mm_private.need_admin(v_me);
  return (
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', s.id, 'submitter_id', s.submitter_id,
        'submitter_name', coalesce((select p.display_name from public.profiles p where p.id = s.submitter_id), ''),
        'sha256', s.sha256, 'title', s.title, 'credit_name', s.credit_name, 'note', s.note,
        'grant_version', s.grant_version, 'bytes', s.bytes, 'seconds', s.seconds, 'rate', s.rate,
        'kind', s.kind, 'bpm', s.bpm, 'beats', s.beats, 'mkey', s.mkey,
        'submitted_at', mm_private.iso(s.submitted_at),
        'others', (select coalesce(jsonb_agg(jsonb_build_object('owner', f.owner_id, 'at', mm_private.iso(f.at))
                                             order by f.at, f.owner_id), '[]'::jsonb)
                   from public.sound_files f
                   where f.sha256 = s.sha256 and f.owner_id is distinct from s.submitter_id))
        order by s.submitted_at, s.id), '[]'::jsonb)
    from public.vault_submissions s where s.status = coalesce(p_status, 'pending'));
end $$;

-- Admin. Approve only after the file has been copied to vault/<sha256>.wav and is the size that was offered.
create or replace function public.vault_review(p_id uuid, p_decision text, p_note text default '') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_s public.vault_submissions;
begin
  perform mm_private.need_admin(v_me);
  select * into v_s from public.vault_submissions s where s.id = p_id for update;
  if not found then perform mm_private.fail('not_found'); end if;
  if v_s.status <> 'pending' then perform mm_private.fail('already_reviewed'); end if;
  if p_decision is null or p_decision not in ('approved', 'declined') then perform mm_private.fail('bad_input'); end if;
  if p_decision = 'approved' then
    if mm_private.object_size('vault', v_s.sha256 || '.wav') is distinct from v_s.bytes then
      perform mm_private.fail('not_copied');
    end if;
    insert into public.vault_sounds (sha256, submission_id, title, credit_name, bytes, seconds, rate, kind, bpm,
                                     beats, mkey, grant_version, body_sha256, granted_at, granter, approved_by)
      select v_s.sha256, v_s.id, v_s.title, v_s.credit_name, v_s.bytes, v_s.seconds, v_s.rate, v_s.kind, v_s.bpm,
             v_s.beats, v_s.mkey, g.version, g.body_sha256, v_s.submitted_at, v_s.submitter_id, v_me
      from public.vault_grants g where g.version = v_s.grant_version
      on conflict (sha256) do nothing;
  end if;
  update public.vault_submissions s
    set status = p_decision, reviewer_id = v_me, reviewer_note = left(coalesce(p_note, ''), 300), reviewed_at = now()
    where s.id = p_id;
  return jsonb_build_object('status', p_decision);
end $$;

revoke all on all functions in schema mm_private from public, anon, authenticated, service_role;

grant execute on function
  public.vault_submit(text, text, text, text), public.vault_mine(), public.vault_withdraw(uuid),
  public.vault_queue(text), public.vault_review(uuid, text, text)
  to authenticated;
grant execute on function public.vault_grant() to anon, authenticated;

-- ===== 20261124100800_mm_service.sql =====
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
    -- asked by the person: they need a profile, and a password session only works for the review account.
    -- A banned person may still delete their account.
    if not exists (select 1 from public.profiles p where p.id = p_user)
       or not mm_private.valid_user(p_user, p_amr) and not exists (
         select 1 from public.profiles p where p.id = p_user and p.banned
           and not (jsonb_typeof(p_amr) = 'array' and exists (
             select 1 from jsonb_array_elements(p_amr) e where e.value ->> 'method' = 'password'))) then
      perform mm_private.fail('auth');
    end if;
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
