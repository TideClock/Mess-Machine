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
