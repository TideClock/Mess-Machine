# Accounts, cloud messes and playing together: the build spec

Nov 24 2026. This turns `docs/ACCOUNTS-PLAN.md` into something three builders can build apart and two reviewers
can attack. The plan's research is not repeated; it is cited as "plan s5" and so on. Tyler's seven decisions at the
top of the plan are final ("decision 2" below means that list).

Tags: no tag means read from this repo's code or docs. [Certain] checked today against a page or by running it.
[Likely] strong reasons, not checked. [Guessing] an estimate or a judgment call.

Words: a **mess** is the saved file (`shared/MESS-FORMAT.md`). The **doc** is that JSON on the server. An **op** is
one small edit. A **batch** is a list of ops sent together. **Rev** is the server's count of batches for one mess.

Where this spec departs from the plan's body (never from Tyler's decisions), and why:

| Plan | Spec | Why |
|---|---|---|
| One file per content hash, shared by everyone | One file per owner and hash: `sounds/<owner>/<sha256>.flac` | Decision 5 says a deleted person's sounds go. With shared files that needs reference counting across people. Per owner, deletion is "remove the folder". It also stops one person planting a bad file under a hash someone else will use. |
| Tables readable through row level security | No table is readable by the app. Every read and write is a server function (RPC) | The fake server and the real one can then be compared call for call (s8d), and a policy slip cannot leak a column. Policies remain on Storage and Realtime, where they are the only gate. |
| Own messes upload as whole files; two phones clashing keep both | Every cloud mess syncs by ops. A solo mess just sends them slowly, with no Realtime | One sync path to build and test. Two phones of one account merge field by field; a long gap still offers "Keep mine as a copy" (plan s5). |
| `profiles.handle`, unique | `display_name`, not unique | Nothing looks people up by handle (invites are links). Unique names need moderation and a "taken" flow for no gain. |
| pgTAP on a local Supabase | Plain Node tests on Postgres or pglite | Docker cannot pull images here (s8a). pgTAP is not installed. |
| Invite by typing an email sends a mail | The invite link can be locked to one email; the phone's own share sheet sends it | No server mail to build or pay for. |

## 1. Scope

**In**

| Thing | Rule |
|---|---|
| Accounts | Email code only (decision 1). One hidden password path for the store review account (s7). |
| Guest mode | Unchanged. With no cloud config in the build, no account feature is visible at all (s5.1). |
| Messes online | Any mess can be saved online with the user sounds it needs. The phone file stays the first save. |
| Share link | Off, View or Copy. A guest can listen and, on Copy, make a local copy. |
| Invite to edit | Invite links, members, roles owner, editor, viewer. Owner can remove, block, hand over. |
| Playing together | 2 to 4 people editing one mess by ops. Other people's ops are **held and applied together at the top of the local player's next loop pass while playing, at once when stopped** (decision 2). Each phone plays only its own audio. |
| Presence and soft locks | Who is here, which page, loop and pad. A pad someone is shaping wears their colour. Locks are advice. |
| History | Saved versions, restore as a new version. |
| Vault | Submit a sound with a grant and a credit name. Tyler reviews on a web page. Approved sounds are copied and survive account deletion with credit (decision 5). |
| Safety | Terms before any upload, report a mess, block a person, admin takedown. |
| Deletion | In the app and on a web page. |

**Quotas** (enforced on the server; the app checks first so it can say so plainly)

| Quota | Value | Enforced in |
|---|---|---|
| One sound | 100 s, 20 MB. Recorded live takes (kind `mic`) have no 100 s cap; they are bound by the account quota and the bucket file limit | `register_sound` |
| Sounds per account | 250 MB (262,144,000 bytes) | `register_sound`, `finish_upload` |
| One mess | 1 MB of JSON (1,048,576 bytes) | `create_mess`, `save_snapshot`, `fork_mess` |
| Messes per account | 200 owned | `create_mess`, `fork_mess` |
| Members per mess | 8, owner included | `redeem_invite` |
| Live editors per mess | 4 | `join_live`, `apply_ops` |
| Op batches | 5 a second per person per mess; 500 ops and 64 KB a batch | `apply_ops` |

**Out**: Google and Apple sign-in, a public gallery, live audio or live pad hits between phones, Follow, paid packs
or accounts, jam mode, chat, push notifications, server-sent invite mail.

## 2. Server (Supabase)

Files: `server/supabase/migrations/<yyyymmddhhmmss>_<name>.sql` (applied in name order),
`server/supabase/functions/<name>/index.ts`, `server/tests/`. Everything below is Postgres 15 safe.

Rules for every function the app can call:

- It lives in schema `public`, is `security definer`, has `set search_path = ''`, and names every object in full.
- Every migration ends with `revoke execute on all functions in schema public from public, anon, authenticated`
  (and the same for `mm_private`), then grants each function by name. A new function is callable by everyone until
  that runs: Postgres grants execute to `public` by default, and [Likely] Supabase adds its own default grant to
  `anon` and `authenticated`. A default cannot be revoked for one schema only, so the test in s8a reads the catalogue
  and fails on any function the wrong role can call.
- It returns `jsonb`. It fails with `raise exception using errcode = 'P0001', message = '<code>'`. [Likely] PostgREST
  answers HTTP 400 with `{"code":"P0001","message":"<code>",...}`; the app reads `message` only.
- Helpers live in schema `mm_private`, which the API does not expose and no client role may use.

Error codes used everywhere: `auth` (not signed in), `terms` (current terms not accepted), `not_found` (no such
thing, or you are not on it: the two look the same on purpose), `forbidden` (on it, role too low), `blocked` (the
mess was taken down, or the owner blocked you), `too_big`, `rate_limited`, `bad_input`.

### 2.1 Tables

All SQL in sections 2.1 to 2.7 was loaded into Postgres 16.15 here, on a stand-in for Supabase's own schemas, and
the policies and `apply_ops` were run as an owner, an editor, a viewer, a stranger and anon. [Certain]

```sql
create schema if not exists mm_private;

create table public.config (
  key text primary key,
  value jsonb not null
);
insert into public.config values
  ('terms_version', '"2026-11"'), ('sound_limit', '262144000'), ('mess_limit', '200'),
  ('member_limit', '8'), ('live_limit', '4'), ('mess_bytes', '1048576'),
  ('sound_seconds', '100'), ('sound_bytes', '20971520'), ('take_seconds', '2400');

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 24),
  colour smallint not null default 0 check (colour between 0 and 7),
  sound_bytes bigint not null default 0 check (sound_bytes >= 0),
  terms_version text,
  terms_at timestamptz,
  created_at timestamptz not null default now()
);

-- Who may review the Vault and act on reports. Rows are added by hand in the SQL editor. Never a client claim.
create table public.admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  added_at timestamptz not null default now()
);

create table public.terms_acceptances (
  user_id uuid not null references public.profiles(id) on delete cascade,
  version text not null,
  at timestamptz not null default now(),
  primary key (user_id, version)
);

create table public.messes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id),
  name text not null check (char_length(name) between 1 and 48),
  doc jsonb not null check (jsonb_typeof(doc) = 'object'),
  doc_bytes int not null check (doc_bytes between 2 and 1048576),
  rev bigint not null default 0,
  doc_rev bigint not null default 0,
  ops_v smallint not null default 1,
  format_v smallint not null default 4,
  forked_from uuid references public.messes(id) on delete set null,
  share_mode text not null default 'off' check (share_mode in ('off', 'view', 'copy')),
  share_token text unique check (share_token is null or share_token ~ '^[A-Za-z0-9_-]{32}$'),
  blocked boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (doc_rev <= rev),
  check ((share_mode = 'off') = (share_token is null))
);
create index messes_owner on public.messes (owner_id);

create table public.mess_members (
  mess_id uuid not null references public.messes(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null check (role in ('owner', 'editor', 'viewer')),
  added_by uuid references public.profiles(id) on delete set null,
  added_at timestamptz not null default now(),
  primary key (mess_id, user_id)
);
create index mess_members_user on public.mess_members (user_id);
create unique index mess_one_owner on public.mess_members (mess_id) where role = 'owner';

create table public.mess_ops (
  mess_id uuid not null references public.messes(id) on delete cascade,
  rev bigint not null check (rev > 0),
  user_id uuid references public.profiles(id) on delete set null,
  batch_id uuid not null,
  v smallint not null default 1,
  ops jsonb not null check (jsonb_typeof(ops) = 'array'),
  at timestamptz not null default clock_timestamp(),
  primary key (mess_id, rev),
  unique (mess_id, batch_id)
);
create index mess_ops_rate on public.mess_ops (mess_id, user_id, at desc);

create table public.mess_versions (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid not null references public.messes(id) on delete cascade,
  rev bigint not null,
  doc jsonb not null,
  doc_bytes int not null,
  made_by uuid references public.profiles(id) on delete set null,
  label text not null default '' check (char_length(label) <= 40),
  at timestamptz not null default now()
);
create index mess_versions_mess on public.mess_versions (mess_id, at desc);

create table public.mess_live (
  mess_id uuid not null references public.messes(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  seen_at timestamptz not null default clock_timestamp(),
  primary key (mess_id, user_id)
);

create table public.invites (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid not null references public.messes(id) on delete cascade,
  role text not null check (role in ('editor', 'viewer')),
  token_hash text not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
  email text check (email is null or email = lower(email)),
  expires_at timestamptz not null,
  uses_left int not null check (uses_left >= 0),
  revoked boolean not null default false,
  made_by uuid not null references public.profiles(id) on delete cascade,
  at timestamptz not null default now()
);
create index invites_mess on public.invites (mess_id);

create table public.user_blocks (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  at timestamptz not null default now(),
  primary key (owner_id, blocked_id)
);

-- One stored audio file of one person. The object is sounds/<owner_id>/<sha256>.flac.
create table public.sound_files (
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
  state text not null default 'pending' check (state in ('pending', 'ready')),
  at timestamptz not null default now(),
  primary key (owner_id, sha256)
);

-- A user sound a mess needs. src: 'user' = the owner's file, 'vault' = the Vault copy, 'gone' = its owner left.
create table public.mess_sounds (
  mess_id uuid not null references public.messes(id) on delete cascade,
  sound_key text not null check (char_length(sound_key) between 1 and 80),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  owner_id uuid,
  src text not null default 'user' check (src in ('user', 'vault', 'gone')),
  added_by uuid references public.profiles(id) on delete set null,
  added_rev bigint not null default 0,
  primary key (mess_id, sound_key),
  foreign key (owner_id, sha256) references public.sound_files (owner_id, sha256),
  check ((src = 'user') = (owner_id is not null))
);
create index mess_sounds_file on public.mess_sounds (owner_id, sha256);
create index mess_sounds_sha on public.mess_sounds (sha256) where src = 'vault';

-- The exact grant texts people have been shown. A new wording is a new row; old rows never change.
create table public.vault_grants (
  version text primary key,
  body text not null,
  body_sha256 text not null check (body_sha256 ~ '^[0-9a-f]{64}$'),
  at timestamptz not null default now()
);

create table public.vault_submissions (
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
create unique index vault_one_open on public.vault_submissions (submitter_id, sha256)
  where status in ('pending', 'approved');
create index vault_by_status on public.vault_submissions (status, submitted_at);

-- The Vault's own copy of an approved sound: vault/<sha256>.flac. Not tied to any account.
create table public.vault_sounds (
  sha256 text primary key check (sha256 ~ '^[0-9a-f]{64}$'),
  submission_id uuid not null references public.vault_submissions(id),
  title text not null,
  credit_name text not null,
  bytes bigint not null, seconds real not null, rate int not null, kind text not null,
  bpm real not null default 0, beats int not null default 0, mkey text not null default '',
  approved_by uuid references auth.users(id) on delete set null,
  approved_at timestamptz not null default now()
);

create table public.reports (
  id uuid primary key default gen_random_uuid(),
  mess_id uuid references public.messes(id) on delete set null,
  mess_name text not null default '',
  owner_id uuid,
  reporter_id uuid references public.profiles(id) on delete set null,
  via_token boolean not null default false,
  reason text not null check (reason in ('sexual', 'hate', 'harassment', 'copyright', 'spam', 'other')),
  details text not null default '' check (char_length(details) <= 500),
  status text not null default 'open' check (status in ('open', 'actioned', 'dismissed')),
  admin_note text not null default '',
  at timestamptz not null default now(),
  resolved_at timestamptz
);
create index reports_open on public.reports (status, at);
```

A profile row is made by a trigger on `auth.users` insert (`mm_private.on_new_user`, security definer, empty search
path, exactly as Supabase's guide shows [Certain]); `display_name` starts as `'Player ' || 4 digits`.

### 2.2 Row level security

```sql
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
revoke all on all tables in schema public from anon, authenticated;
revoke all on schema mm_private from public, anon, authenticated;
```

**Table policy, stated exactly: there are none.** Row level security is on, no policy exists, and `anon` and
`authenticated` hold no table privilege. A direct read or write of any table from the app is denied twice. The
tests prove it for every table and both roles (s8a). Only the functions in 2.3 touch tables.

Helpers the Storage and Realtime policies call:

```sql
create function mm_private.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.admins a where a.user_id = (select auth.uid()))
$$;

-- The caller's role on a mess, or null. A taken-down mess answers null to everyone but its owner.
create function mm_private.role_in(p_mess uuid) returns text
language sql stable security definer set search_path = '' as $$
  select mm.role from public.mess_members mm join public.messes m on m.id = mm.mess_id
  where mm.mess_id = p_mess and mm.user_id = (select auth.uid()) and (not m.blocked or mm.role = 'owner')
$$;

-- 'mess:<uuid>' to the uuid, or null when the topic is anything else.
create function mm_private.topic_mess(p_topic text) returns uuid
language sql immutable set search_path = '' as $$
  select case when p_topic ~ '^mess:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              then substr(p_topic, 6)::uuid end
$$;

-- May the caller upload this object? Only into their own folder, only a file they registered and not yet finished.
create function mm_private.may_upload(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.sound_files f
    where f.owner_id = (select auth.uid()) and f.state = 'pending'
      and p_name = f.owner_id::text || '/' || f.sha256 || '.flac')
$$;

-- May the caller read this object in 'sounds'? Their own, one a mess they are on needs, or they are an admin.
create function mm_private.may_read_sound(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_name like (select auth.uid())::text || '/%'
      or mm_private.is_admin()
      or exists (select 1 from public.mess_sounds ms
           join public.mess_members mm on mm.mess_id = ms.mess_id and mm.user_id = (select auth.uid())
           join public.messes m on m.id = ms.mess_id and (not m.blocked or mm.role = 'owner')
           where ms.src = 'user' and p_name = ms.owner_id::text || '/' || ms.sha256 || '.flac')
$$;

create function mm_private.may_read_vault(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select mm_private.is_admin()
      or exists (select 1 from public.mess_sounds ms
           join public.mess_members mm on mm.mess_id = ms.mess_id and mm.user_id = (select auth.uid())
           where ms.src = 'vault' and p_name = ms.sha256 || '.flac')
$$;
grant usage on schema mm_private to authenticated;
revoke execute on all functions in schema mm_private from public, anon;
grant execute on function mm_private.is_admin(), mm_private.role_in(uuid), mm_private.topic_mess(text),
  mm_private.may_upload(text), mm_private.may_read_sound(text), mm_private.may_read_vault(text) to authenticated;
```

(`authenticated` needs `usage` on `mm_private` only so policies can call these six; it can call nothing else there.)

### 2.3 Functions (RPC)

Call shape: `POST <url>/rest/v1/rpc/<name>` with a JSON object of the named arguments. [Likely]
"Member" below means `mm_private.role_in` is not null. Checks run in the order written; the first that fails wins.
Every function starts with: `auth` if `auth.uid()` is null (unless marked anon). Functions marked T also fail with
`terms` unless `profiles.terms_version` equals `config.terms_version`.

**Account**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `me()` | | `{id, display_name, colour, sound_bytes, sound_limit, messes, mess_limit, terms_ok, terms_version, is_admin}` | `auth` |
| `set_profile(p_display_name text, p_colour int)` | name trimmed, 1 to 24 chars, passes `mm_private.clean_name`; colour 0 to 7 | same as `me()` | `bad_input`, `bad_name` |
| `accept_terms(p_version text)` | equals `config.terms_version` | `{terms_ok: true}`; writes `profiles` and `terms_acceptances` | `terms_outdated` |

**Messes**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `create_mess(p_id uuid, p_name text, p_doc jsonb)` T | if `p_id` exists and is yours, return it (safe retry); if it exists and is not yours `bad_input`; owned count under 200; `octet_length(p_doc::text)` at most 1 MB; name cleaned. Makes the mess, the owner member row, and a `mess_sounds` row for each entry of `p_doc->'sounds'` whose file is yours and `ready` (others are skipped) | `{id, rev: 0}` | `too_many_messes`, `too_big`, `bad_name` |
| `my_messes()` | | `[{id, name, role, owner_name, rev, updated_at, members, share_mode, doc_bytes, blocked}]`, newest first | |
| `open_mess(p_mess uuid)` | member | `{id, name, role, owner_id, rev, doc_rev, doc, ops_v, ops: [batch], sounds: [sound], members: [member], share_mode, share_token}`. `ops` is every batch after `doc_rev`. `share_token` is null unless you are the owner. | `not_found` |
| `ops_since(p_mess uuid, p_from bigint)` | member; if `p_from < doc_rev` and batch `p_from + 1` has been trimmed: `gap` | `{rev, members, ops: [batch]}`, at most 500 batches, oldest first | `not_found`, `gap` |
| `apply_ops(p_mess uuid, p_batch uuid, p_ops jsonb, p_v int)` T | see 2.4 | `{rev, at}` | see 2.4 |
| `save_snapshot(p_mess uuid, p_doc jsonb, p_doc_rev bigint, p_v int, p_label text default '')` T | owner or editor; `p_v >= messes.ops_v` else `client_too_old`; `p_doc_rev <= rev` else `ahead`; `p_doc_rev > doc_rev`, or equal with a label, else `stale`; size. Sets `doc`, `doc_rev`, `name` from the doc. Makes a version when a label is given or the newest version is over 10 minutes old. Trims versions (2.5). Drops `mess_sounds` rows whose key is not in `p_doc->'sounds'` and whose `added_rev <= p_doc_rev`. | `{doc_rev, version_id}` (`version_id` null when none made) | `forbidden`, `client_too_old`, `ahead`, `stale`, `too_big` |
| `rename_mess(p_mess uuid, p_name text)` T | owner; name cleaned. Works by appending the batch `[{"t":"mess.set","f":"name","v":...}]` through the same code as `apply_ops` | `{rev}` | `forbidden`, `bad_name` |
| `delete_mess(p_mess uuid)` | owner. Sends `meta {kind:"deleted"}` first | `{ok: true}` | `forbidden` |
| `set_share(p_mess uuid, p_mode text, p_new boolean default false)` T | owner; mode `off`, `view` or `copy`. `off` clears the token. Turning on, or `p_new`, makes a fresh 32 char token (24 random bytes, base64url), which kills the old link | `{share_mode, share_token}` | `forbidden`, `bad_input`, `blocked` |
| `open_shared(p_token text)` anon | token matches, mode not `off`, not blocked | `{name, owner_name, mode, rev, doc_rev, doc, ops: [batch], sounds: [sound]}`. No ids of people. | `not_found` |
| `fork_mess(p_id uuid, p_from uuid, p_token text, p_name text, p_doc jsonb)` T | read right on the source: member of `p_from`, or `p_token` opens it with mode `copy` (`view` gives `no_copy`); then as `create_mess` with `forked_from` set. `mess_sounds` rows are copied from the source for keys listed in `p_doc->'sounds'`. The app sends the doc because the server never applies ops. | `{id, rev: 0}` | `not_found`, `no_copy`, `too_many_messes`, `too_big` |
| `transfer_mess(p_mess uuid, p_to uuid)` | owner; `p_to` is an editor of it; `p_to` owns under 200. Old owner becomes editor. | `{ok: true}` | `forbidden`, `bad_input`, `too_many_messes` |

`batch` is `{rev, user, batch, v, ops, at}`. `member` is `{id, name, colour, role}`.
`sound` is `{key, sha256, src, owner, bytes, seconds, rate, kind, title, bpm, beats, mkey, state}`.

**Members and invites**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `members(p_mess uuid)` | member | `[member]` | `not_found` |
| `make_invite(p_mess uuid, p_role text, p_uses int default 1, p_email text default null, p_days int default 7)` T | owner; role `editor` or `viewer`; uses 1 to 8; days 1 to 30; under 20 live invites on the mess. Stores only `sha256(token)`. | `{id, token, expires_at}`. The token is shown once. | `forbidden`, `bad_input`, `too_many_invites` |
| `list_invites(p_mess uuid)` | owner | `[{id, role, email, expires_at, uses_left, revoked}]` | `forbidden` |
| `revoke_invite(p_invite uuid)` | owner of its mess | `{ok: true}` | `not_found` |
| `invite_info(p_token text)` anon | token live | `{mess_name, owner_name, role}` | `invite_invalid` |
| `redeem_invite(p_token text)` T | token exists, not revoked, not expired, `uses_left > 0` else `invite_invalid`; mess not blocked; invite email, if set, equals the caller's email else `email_mismatch`; owner has not blocked the caller else `blocked`; already a member: raise viewer to editor if the invite says editor, use nothing, return; member count under 8 else `mess_full`. Takes one use. Sends `meta {kind:"members"}`. | `{mess_id, role}` | as listed |
| `set_role(p_mess uuid, p_user uuid, p_role text)` | owner; target is a member and not the owner; role `editor` or `viewer`. Sends `meta {kind:"members"}`. | `{ok: true}` | `forbidden`, `not_found`, `bad_input` |
| `remove_member(p_mess uuid, p_user uuid, p_block boolean default false)` | owner; target not the owner. With `p_block`, adds a `user_blocks` row: that person can join none of this owner's messes. Sends `meta {kind:"members", gone: p_user}`. | `{ok: true}` | `forbidden`, `not_found` |
| `leave_mess(p_mess uuid)` | member; the owner cannot leave | `{ok: true}` | `not_found`, `owner_cannot_leave` |
| `my_blocks()` / `unblock_user(p_user uuid)` | | `[{id, name}]` / `{ok: true}` | |
| `join_live(p_mess uuid)` T | owner or editor (a viewer gets `{live: false}`); counts `mess_live` rows of other people seen in the last 90 s; under 4: upsert own row | `{live, editors}` | `not_found` |
| `leave_live(p_mess uuid)` | | `{ok: true}` | |

**History**

| Function | Checks | Returns | Errors |
|---|---|---|---|
| `versions(p_mess uuid)` | member | `[{id, rev, label, by_name, at, bytes}]`, newest first | `not_found` |
| `get_version(p_version uuid)` | member of its mess | `{id, rev, label, doc}` | `not_found` |
| `restore_version(p_mess uuid, p_version uuid)` T | owner or editor; `doc_rev = rev` else `snapshot_first` (the app saves a snapshot, then calls again). Keeps the current doc as a version labelled `Before restore`, sets `doc`, bumps `rev` and `doc_rev` together, stores the batch `[{"t":"doc.reset"}]` at that rev and broadcasts it. | `{rev}` | `forbidden`, `not_found`, `snapshot_first` |

**Sounds**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `register_sound(p_sha256 text, p_bytes bigint, p_seconds real, p_rate int, p_kind text, p_local_key text, p_title text, p_bpm real, p_beats int, p_mkey text)` T | sha is 64 hex; a `ready` row of yours with this sha: return `ready`; kind is not `mic`: `p_seconds <= 100` else `too_long`, `p_bytes <= 20 MB` else `too_big`; kind `mic`: `p_seconds <= 2400` else `too_long`; under 20 pending rows else `too_many_pending`; `sound_bytes + pending bytes + p_bytes <= 250 MB` else `quota`. Upserts a `pending` row. | `{state: "ready" or "upload", path, used, limit}`; `path` is `<uid>/<sha256>.flac` | as listed, `bad_input` |
| `finish_upload(p_sha256 text)` | your pending row exists; `storage.objects` has `bucket_id = 'sounds'`, `name = path` else `not_uploaded`; real size `(metadata->>'size')::bigint` [Likely] must be within 250 MB with the rest, else the row is deleted and `quota` (the sweep removes the object). Stores the real size, sets `ready`, adds it to `profiles.sound_bytes`, sends `meta {kind:"sound", sha256}` to every mess that lists it. | `{state: "ready", used}` | `not_found`, `not_uploaded`, `quota` |
| `attach_sound(p_mess uuid, p_key text, p_owner uuid, p_sha256 text)` T | owner or editor of the mess; the file row exists (pending is fine); the file is yours, or it is already listed by a mess you are on; `p_key` starts with `bn:`. Upserts `mess_sounds` with `added_rev = messes.rev`. | `{ok: true}` | `forbidden`, `not_found`, `bad_input` |
| `my_sounds()` | | `[{sha256, title, kind, bytes, seconds, state, messes, vault}]`; `vault` is the status of your submission or null | |
| `delete_sound(p_sha256 text)` | yours. `mess_sounds` rows for it become `vault` if `vault_sounds` has the sha, else `gone`. Deletes the row, lowers `sound_bytes`, withdraws a pending submission. The app then deletes the object. | `{path, freed, messes}` | `not_found` |

The hash check, stated plainly: the server checks the object's name and size, not its content. SQL cannot read
file bytes. Every app verifies sha256 after each download and treats a mismatch as a missing sound. Because a file
lives under its owner's folder, a wrong hash only breaks the uploader's own sound. [Certain about the mechanism;
Likely about `metadata->>'size'`, checked in s8e.]

**Vault**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `vault_grant()` anon | | the newest `vault_grants` row: `{version, body, body_sha256}` | |
| `vault_submit(p_sha256 text, p_credit_name text, p_grant_version text, p_note text default '')` T | your file, `ready`, else `not_ready`; `p_grant_version` is the newest else `grant_outdated`; credit name 1 to 40 chars and clean; no pending or approved row for this sha else `already_submitted`; under 10 pending else `too_many_pending`. Copies the file's facts into the row. | `{id, status: "pending"}` | as listed, `bad_name` |
| `vault_mine()` | | `[{id, sha256, title, credit_name, status, reviewer_note, submitted_at, reviewed_at}]` | |
| `vault_withdraw(p_id uuid)` | yours and pending | `{status: "withdrawn"}` | `not_found` |
| `vault_queue(p_status text default 'pending')` | admin | rows of that status, oldest first, each with `submitter_id`, `submitter_name`, `path`, `grant_version` and the file's facts | `forbidden` |
| `vault_review(p_id uuid, p_decision text, p_note text default '')` | admin; row is pending else `already_reviewed`; decision `approved` or `declined`. For `approved`: `storage.objects` must already hold `bucket_id = 'vault'`, `name = sha256 || '.flac'` else `not_copied`; inserts `vault_sounds` (or leaves an existing row for that sha). | `{status}` | `forbidden`, `not_found`, `already_reviewed`, `not_copied`, `bad_input` |

How an approved sound survives its maker leaving (decision 5): the review page downloads
`sounds/<owner>/<sha>.flac` with the admin's own sign-in, checks the sha256 in the browser, uploads it as
`vault/<sha>.flac`, then calls `vault_review`. The copy belongs to the admin's account, so it is not in the way when
the submitter's account is deleted ([Certain] a user who owns Storage objects cannot be deleted). `vault_sounds` keeps
the title and the credit name. When the submitter deletes their account, `vault_submissions.submitter_id` goes
null, the row and its `credit_name` stay, and `mess_sounds` rows in other people's messes switch to `src = 'vault'`.

**Reports and admin**

| Function | Checks, in order | Returns | Errors |
|---|---|---|---|
| `report_mess(p_mess uuid, p_token text, p_reason text, p_details text default '')` anon | signed in: member of `p_mess` and not its owner, at most 5 reports a day; or `p_token` opens a shared mess (signed in or not), at most 20 token reports a day per mess. Copies name and owner into the row. | `{id}` | `not_found`, `rate_limited`, `bad_input` |
| `admin_reports(p_status text default 'open')` | admin | rows with `mess_name`, `owner_id`, `reason`, `details`, `at`, and the share token if the mess still has one | `forbidden` |
| `admin_resolve_report(p_id uuid, p_action text, p_note text default '')` | admin; action `dismiss` or `block_mess` (sets `messes.blocked`, clears the share link, sends `meta {kind:"blocked"}`) | `{status}` | `forbidden`, `not_found`, `bad_input` |
| `admin_block_mess(p_mess uuid, p_blocked boolean)` | admin | `{blocked}` | `forbidden`, `not_found` |

Banning a person is done in the Supabase dashboard (Auth, Users). It needs no function here.

**Service only** (granted to `service_role` alone, called by Edge Functions)

| Function | Does |
|---|---|
| `mm_private.delete_account_data(p_user uuid) returns jsonb` | In one transaction: deletes the messes the user still owns (members, ops, versions, invites and sound rows go with them); removes the user from other messes; for each of the user's files, other people's `mess_sounds` rows become `vault` when `vault_sounds` has the sha, else `gone`; deletes `sound_files` rows; pending and declined submissions are deleted, approved ones keep `credit_name` and lose `submitter_id`; reports the user made, and reports about the user's messes, keep their text and lose `reporter_id` and `owner_id`; deletes blocks, terms rows and the profile. Returns `{paths: [...]}`, every object under `sounds/<user>/`. Safe to run twice. |
| `mm_private.sweep() returns jsonb` | Deletes `pending` file rows older than 24 hours and returns their paths, plus paths in `sounds` with no row (found by reading `storage.objects`). |
| `mm_private.trim_ops()` | Deletes `mess_ops` rows with `rev <= doc_rev` and `at` over 7 days old. Run daily by `pg_cron` [Likely available]. |

### 2.4 `apply_ops`, the one function everything else leans on

```sql
create function public.apply_ops(p_mess uuid, p_batch uuid, p_ops jsonb, p_v int default 1) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := (select auth.uid());
  r text; old record; new_rev bigint; n int; nm text; ts timestamptz := clock_timestamp();
begin
  if me is null then raise exception using errcode = 'P0001', message = 'auth'; end if;
  if not exists (select 1 from public.profiles p where p.id = me and p.terms_version =
       (select c.value #>> '{}' from public.config c where c.key = 'terms_version')) then
    raise exception using errcode = 'P0001', message = 'terms'; end if;
  r := mm_private.role_in(p_mess);
  if r is null then raise exception using errcode = 'P0001', message = 'not_found'; end if;
  if r = 'viewer' then raise exception using errcode = 'P0001', message = 'forbidden'; end if;
  if (select m.blocked from public.messes m where m.id = p_mess) then
    raise exception using errcode = 'P0001', message = 'blocked'; end if;
  if p_batch is null or jsonb_typeof(p_ops) is distinct from 'array' or p_v not between 1 and 32767
     or jsonb_array_length(p_ops) not between 1 and 500 then
    raise exception using errcode = 'P0001', message = 'bad_input'; end if;
  if octet_length(p_ops::text) > 65536 then raise exception using errcode = 'P0001', message = 'too_big'; end if;

  -- a batch sent twice (the answer was lost) gets its first answer again
  select o.rev, o.at, o.user_id into old from public.mess_ops o where o.mess_id = p_mess and o.batch_id = p_batch;
  if found then
    if old.user_id is distinct from me then raise exception using errcode = 'P0001', message = 'bad_input'; end if;
    return jsonb_build_object('rev', old.rev, 'at', old.at);
  end if;

  select count(*) into n from public.mess_ops o
    where o.mess_id = p_mess and o.user_id = me and o.at > ts - interval '1 second';
  if n >= 5 then raise exception using errcode = 'P0001', message = 'rate_limited'; end if;

  if not exists (select 1 from public.mess_live l where l.mess_id = p_mess and l.user_id = me
                   and l.seen_at > ts - interval '90 seconds') then
    select count(*) into n from public.mess_live l
      where l.mess_id = p_mess and l.user_id <> me and l.seen_at > ts - interval '90 seconds';
    if n >= 4 then raise exception using errcode = 'P0001', message = 'mess_full_live'; end if;
  end if;
  insert into public.mess_live (mess_id, user_id, seen_at) values (p_mess, me, ts)
    on conflict (mess_id, user_id) do update set seen_at = excluded.seen_at;

  -- only the owner renames; the name column follows the op
  select o.value ->> 'v' into nm from jsonb_array_elements(p_ops) with ordinality o
    where o.value ->> 't' = 'mess.set' and o.value ->> 'f' = 'name' order by o.ordinality desc limit 1;
  if nm is not null then
    if r <> 'owner' then raise exception using errcode = 'P0001', message = 'forbidden'; end if;
    if char_length(btrim(nm)) not between 1 and 48 then
      raise exception using errcode = 'P0001', message = 'bad_name'; end if;
  end if;

  -- the row lock on the mess is what puts batches in order
  update public.messes m set rev = m.rev + 1, ops_v = greatest(m.ops_v, p_v::smallint), updated_at = now(),
         name = coalesce(btrim(nm), m.name)
    where m.id = p_mess returning m.rev into new_rev;
  insert into public.mess_ops (mess_id, rev, user_id, batch_id, v, ops, at)
    values (p_mess, new_rev, me, p_batch, p_v, p_ops, ts);

  -- best effort: a failed broadcast must never undo a stored batch (plan s5, "Kotlin library gaps")
  begin
    perform realtime.send(
      jsonb_build_object('rev', new_rev, 'user', me, 'batch', p_batch, 'v', p_v, 'ops', p_ops, 'at', ts),
      'ops', 'mess:' || p_mess::text, true);
  exception when others then null;
  end;
  return jsonb_build_object('rev', new_rev, 'at', ts);
end $$;
revoke execute on function public.apply_ops(uuid, uuid, jsonb, int) from public, anon;
grant execute on function public.apply_ops(uuid, uuid, jsonb, int) to authenticated;
```

Errors: `auth`, `terms`, `not_found`, `forbidden`, `blocked`, `bad_input`, `too_big`, `rate_limited`,
`mess_full_live`, `bad_name`. The server never reads an op beyond the one name check. It stores and orders.
[Certain] `realtime.send` fails when today's partition of `realtime.messages` does not exist yet, and only a
websocket client creates it. That is why the send sits in its own block and why clients also poll (s4.2).

### 2.5 History and trimming

- A version is made by `save_snapshot` when a label is given (the Save key sends `Saved`) or the newest version is
  over 10 minutes old, and by `restore_version` (`Before restore`). The app sends `Before clear` and `Before swap`
  labelled snapshots itself before a loop clear or a kit swap in a shared mess.
- Kept per mess: the newest 30 unlabelled and the newest 20 labelled, and never more than 8 MB of versions. Oldest
  unlabelled go first.

### 2.6 Storage buckets

| Bucket | Public | File limit | Types | Path |
|---|---|---|---|---|
| `sounds` | no | 50 MB on Free ([Certain] Free cannot go higher), 250 MB once on Pro | `audio/flac` | `<owner uuid>/<sha256>.flac` |
| `vault` | no | 250 MB | `audio/flac` | `<sha256>.flac` |

```sql
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('sounds', 'sounds', false, 52428800, array['audio/flac']),
  ('vault',  'vault',  false, 262144000, array['audio/flac']);

create policy mm_sounds_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'sounds' and mm_private.may_upload(name));
create policy mm_sounds_select on storage.objects for select to authenticated
  using (bucket_id = 'sounds' and mm_private.may_read_sound(name));
create policy mm_sounds_delete on storage.objects for delete to authenticated
  using (bucket_id = 'sounds' and name like (select auth.uid())::text || '/%');
create policy mm_vault_select on storage.objects for select to authenticated
  using (bucket_id = 'vault' and mm_private.may_read_vault(name));
create policy mm_vault_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'vault' and mm_private.is_admin());
create policy mm_vault_delete on storage.objects for delete to authenticated
  using (bucket_id = 'vault' and mm_private.is_admin());
```

No update policy exists, so nobody can overwrite a file ([Certain] an overwrite needs select, insert and update).
`anon` has no policy: a guest gets sounds only through the `shared-sounds` function. Objects are deleted through
the Storage API only ([Certain] a SQL delete leaves the file behind).

### 2.7 Realtime

- One private channel per mess, named `mess:<mess uuid>`. Only opened while a mess with more than one member is on
  screen. "Allow public access" is turned off in Realtime settings. [Certain] that setting is what enforces private.
- Events the server broadcasts: `ops` (the payload built in 2.4) and `meta`
  (`{kind: "members" | "sound" | "deleted" | "blocked", ...}`; the app answers by calling `open_mess` or `members`).
- Presence: each phone tracks one small object (s4.2). Nothing else is sent by clients.

```sql
create policy mm_rt_listen on realtime.messages for select to authenticated
  using (extension in ('broadcast', 'presence')
     and mm_private.role_in(mm_private.topic_mess((select realtime.topic()))) is not null);
create policy mm_rt_presence on realtime.messages for insert to authenticated
  with check (extension = 'presence'
     and mm_private.role_in(mm_private.topic_mess((select realtime.topic()))) is not null);
```

There is no insert policy for `broadcast`. No client, editor or not, can put an `ops` message on a channel; only
`apply_ops` can. Viewers listen and show presence. Do not add `enable row level security` for `realtime.messages`
to a migration ([Certain] it is already on and the statement fails).

[Certain] Realtime checks these policies when a client joins and caches the answer for the life of the connection.
A removed member keeps hearing broadcasts until their token is replaced. So: token life stays at 1 hour, the app
leaves the channel when it gets `meta {kind:"members", gone: <me>}`, and every RPC still refuses them at once.

### 2.8 Edge Functions

| Function | Why it needs the service key | What it does |
|---|---|---|
| `delete-account` | [Certain] only the service key can delete an auth user | Body `{}` with the caller's token. Checks the token with `auth.getUser`. Refuses with `stale_login` unless the token's newest `amr` entry is under 10 minutes old [Likely the claim's shape; checked in s8e], so deletion always follows a fresh code. Calls `delete_account_data`, removes the returned paths through the Storage API in groups of 1,000, calls `auth.admin.deleteUser`. Returns `{ok: true}`. If a step fails it stops and can be run again. |
| `shared-sounds` | a guest has no account for Storage policies to check | Body `{token}`. Runs `open_shared`'s check, then answers `{sounds: [{key, sha256, url}]}` with signed URLs good for 1 hour, for `user` and `vault` rows. Also used by signed-in people who hold a link but are not members. |
| `sweep` | deletes other people's leftover objects | Run daily by a schedule. Calls `mm_private.sweep()` and removes those paths. |

## 3. The op format

A batch on the wire is a JSON array of ops. Each op is an object with `"t"` (its type) and its fields. `OPS_V = 1`.
Ops name a pad by slot `p` (0 to 63), never by sound key (plan s5 item 1). When an op is applied, `p` means
"whatever sound is on pad `p` now". An op for an empty pad does nothing.

Three rules make every phone land on the same mess:

1. **Apply is a pure function** of the mess and the op. It never reads anything local.
2. **After any op, the part it touched must equal what saving and loading it would give**: the clamps of
   MESS-FORMAT s3, s5, s6 and s8, the `st`/`en` reset, note clamps, an off-grid tick on a step line becoming a step.
3. **Per-step data at or past a loop's `len` does not exist.** `loop.len` clears it when a loop shrinks, and clears
   the new range when it grows, before any later op fills it.

### 3.1 Field ops (last writer wins per field key)

Loop `l` is 0 to 7, step `s` 0 to 127, target `g` a control number from MESS-FORMAT s6.8. The inverse of a field op
is the same op carrying the value the field had before.

| `t` | Fields | Sets | Field key | Saved as |
|---|---|---|---|---|
| `step.set` | `l, p, s, on` | one step cell | `s/<l>/<p>/<s>` | `pats[l].steps` |
| `acc.set` | `l, p, s, v` (0 ghost, 1 normal, 2 accent) | one accent | `a/<l>/<p>/<s>` | `acc` |
| `chance.set` | `l, p, s, v` (0 to 104) | one chance | `c/<l>/<p>/<s>` | `chance` |
| `off.set` | `l, p, k, on` (`k` a tick, not a multiple of 24) | one off-grid hit | `o/<l>/<p>/<k>` | `offs` |
| `note.set` | `l, p, s, n, len` (`n` pitch -36 to 24; `len` 0 removes) | one note, named by step and pitch | `n/<l>/<p>/<s>/<n>` | `notes` |
| `lock.set` | `l, p, g, s, v` (number, or null for none) | one step lock | `k/<l>/<p>/<g>/<s>` | `locks` |
| `lane.set` | `l, p, g, s, v` (number or null) | one motion lane value | `m/<l>/<p>/<g>/<s>` | `motion` |
| `edit.set` | `p, f, v` | one doctor's table field of the pad's sound. `f` is one of `st en att rel rev loop mode tune fine vol pan tone norm choke held beatN pitch bake fx.rvb fx.rvbSize fx.echo fx.echoT fx.dirt fx.crush fx.chorus fx.wob fx.wobRate ap apKind` | `e/<p>/<f>` | `edits` |
| `opt.set` | `p, f, v` | one pad option. `f` is one of `glide glideT legato route ftype xy pack lfo.on lfo.wave lfo.rate lfo.target lfo.depth lfo.retrig`, or `fx.<g>` (where that FX fader rests, 0 removes) | `q/<p>/<f>` | `padOpts` |
| `synth.set` | `p, id, v` | one knob of the synth on the pad. `id` is a parameter id of its kind (MESS-FORMAT s8.3), or `@name`, or `@src` | `y/<p>/<id>` | `synths` |
| `song.set` | `b, v` (`b` bar 0 to 63; `v` -1 rest, 0 to 7 a loop) | one bar of the song lane | `g/<b>` | `song` |
| `mess.set` | `f, v` | one mess setting. `f` is one of `bpm swing squash level scale root fold name` | `t/<f>` | top level |

Values are rounded the way the writer rounds them before they are compared or sent: locks, lanes and `fx.<g>` to 3
decimals. `xy` and `pack` are whole strings (an XY setup is edited as one thing). The LFO's old `type`, `cut` and
`res` fields never sync; they are always their defaults after load. A synth's kind cannot be set by op: a new kind
is a new patch, sent as `pad.put`. `mess.set name` is accepted from the owner only (2.4).

### 3.2 Structural ops (applied in server order; they do not merge)

| `t` | Fields | Does | Scope | Inverse |
|---|---|---|---|---|
| `loop.len` | `l, len` (16 to 128, a multiple of 16) | sets the length and clears per rule 3 | `L/<l>` | `loop.put` of the loop as it was |
| `loop.clear` | `l` | empties the loop (length stays; its unknown keys go, as `Pattern.clear` does) | `L/<l>` | `loop.put` |
| `loop.copy` | `from, to` | loop `to` becomes a copy of loop `from` as it stands when the op is applied | `L/<to>` | `loop.put` of `to` |
| `loop.put` | `l, len, pads` | replaces the whole loop. `pads` maps a pad number to `{steps, acc, chance, offs, notes, locks: {"<g>": "..."}, motion: {"<g>": "..."}}`, each string in its MESS-FORMAT s6 form, absent keys empty | `L/<l>` | `loop.put` |
| `pad.put` | `p, key, new?, sound?, edit?, synth?, opts?, loops?` | replaces everything pad `p` holds. `key` `""` empties it. `edit`, `synth`, `opts` are the MESS-FORMAT s8.1, s8.3, s8.2 objects. `loops` maps a loop number to the pad's row object (same shape as inside `loop.put`). `sound` is the mess `sounds` entry for a `bn:` or `chop:` key (s4.5). | `P/<p>`, and `P/<q>` for a pad it empties | `pad.put` of each pad touched |
| `pad.swap` | `a, b` | the two pads trade sound, rows, lanes and options (`Project.swapPads`) | `P/<a>`, `P/<b>` | `pad.swap` |
| `doc.reset` | none | server made (restore). The phone throws away its copy and calls `open_mess`. | `*` | none |

`pad.put` rules, so two people cannot leave one sound on two pads:

- If `key` is already on another pad `q` and the op has no `new: true`, pad `q` is emptied first (a move).
- If `new: true` and `key` is a `synth:` key in use on another pad, the op is applied under the lowest free
  `synth:<n>` instead. Two people who both make "synth:1" at once get `synth:1` and `synth:2`, the same on every phone.

### 3.3 What never syncs

`sel`, `cur`, `mode`, `mutes`, `solos` (plan s5 item 9), `at`, `app`, `v`, the old top-level `steps` copy, slot 64
(the Synth tab's scratch synth, its patch, options, rows and lanes), `dropKeep`, and unknown keys (`extra`): those
ride only in snapshots. A snapshot of a mess with more than one member is written with `mutes` and `solos` empty,
`sel` 0, `cur` 0, `mode` `loop` and slot 64 empty, so one person's mutes never land on another. A phone keeps its
own values for all of these across every rebuild (`Ops.carryLocal`).

### 3.4 Ops this app does not know

The model is the mess format's `extra` rule (MESS-FORMAT s10): keep it, pass it on, never drop it.

- An op is held in memory as its whole parsed JSON object. Fields the app does not know stay in it and are written
  back out wherever the op is written (the cache, the fake server, tests).
- An op whose `t` is unknown, or a batch whose `v` is above `OPS_V`, is kept in the log in its place and skipped when
  applied. The session then sets `sawNewer`. A phone with `sawNewer` shows "Someone here has a newer Mess Machine.
  Update to see everything they do." and **stops uploading snapshots**, because its copy is missing that op's effect
  and a snapshot would bake the loss in.
- The server backs this up: `save_snapshot` refuses `client_too_old` when `p_v` is below the highest op version the
  mess has seen. Old ops are trimmed only after a snapshot covers them, and only a new enough app can make that snapshot.

## 4. The client core (common Kotlin)

Package `com.tideclock.messmachine.cloud`, in `core/src/commonMain/kotlin/com/tideclock/messmachine/cloud/`.
Rules of `docs/KOTLIN-ENGINE.md` apply: no `java.*`, no coroutines library, no `kotlinx`, `@JvmField` and
`@JvmStatic` so the Java tests can call it, and it must pass a JS compile. One thread: every method of every class
here, and every callback into it, runs on one thread (on Android the main thread). Implementations of `CloudApi` do
their network work elsewhere and post results back.

Files: `Ops.kt`, `SyncSession.kt`, `CloudApi.kt`, `CloudCalls.kt`, `Account.kt`, `SoundSync.kt`, `Sha256.kt`,
`fake/FakeCloud.kt`. Plus `engine/FlacEnc.kt`, `Project.sounds` and `Engine.setSnapshotAtWrap` (4.5, 4.6).

### 4.1 `Ops`

```kotlin
class Op(@JvmField val m: MutableMap<String, Any?>) {        // the whole JSON object, "t" included
    val t: String get() = Json.str(m["t"], "")
}

/** What one local change was: the ops that make it and the ops that take it back. */
class Change(@JvmField val ops: MutableList<Op>, @JvmField val undo: MutableList<Op>)

object Ops {
    const val V = 1
    /** Ops that turn `before` into `after`, shared fields only. Never looks past a loop's len. */
    @JvmStatic fun diff(before: Project, after: Project): MutableList<Op>
    /** diff both ways: ops = diff(before, after), undo = diff(after, before). */
    @JvmStatic fun change(before: Project, after: Project): Change
    /** Applies in order, in place. Returns how many ops it did not know (they are skipped). */
    @JvmStatic fun apply(p: Project, ops: List<Op>): Int
    @JvmStatic fun known(op: Op): Boolean
    /** The field key of 3.1, or "" for a structural or unknown op. */
    @JvmStatic fun key(op: Op): String
    /** "L/<l>" and "P/<p>" scopes the op lies in or replaces; "*" for doc.reset and unknown ops. */
    @JvmStatic fun scopes(op: Op): List<String>
    @JvmStatic fun structural(op: Op): Boolean
    /** Drops a field op when a later op in the list sets the same key or replaces its scope. Order is kept. */
    @JvmStatic fun squash(ops: List<Op>): MutableList<Op>
    /** The shared part of a mess as text, in one fixed order (notes by step then pitch), for comparing. */
    @JvmStatic fun canon(p: Project): String
    /** The JSON to upload: toJson() as is when solo, with the 3.3 fields blanked when shared. */
    @JvmStatic fun uploadDoc(p: Project, shared: Boolean): String
    /** Copies the 3.3 fields from one mess to another (renaming the scratch synth if its key is taken). */
    @JvmStatic fun carryLocal(from: Project, to: Project)
    @JvmStatic fun toJson(ops: List<Op>): String
    @JvmStatic fun fromList(l: List<Any?>): MutableList<Op>
}
```

How `diff` works, so every builder gets the same ops:

1. `scratch = before.copy()`.
2. Pad swaps: for pads `a < b` where both changed and `after.kit[a] == scratch.kit[b]` and
   `after.kit[b] == scratch.kit[a]`: emit `pad.swap`, apply it to `scratch`.
3. Every pad whose key still differs: emit `pad.put` with all the pad holds in `after` (with `new: true` when the
   key was on no pad in `before`), apply to `scratch`. Also when a synth's kind changed.
4. Per loop: if `after` is empty and `scratch` is not, `loop.clear`. Else if `after` equals another loop of
   `scratch` and differs from its own, `loop.copy`. Else if `len` differs, `loop.len`. Apply to `scratch`.
5. What still differs, field by field, becomes field ops. If one loop needs more than 64 of them, send one
   `loop.put` instead; if one pad's rows across loops need more than 64, one `pad.put`.

Law the tests hold it to: `canon(apply(copy(a), diff(a, b))) == canon(b)` for any two messes.

**Undo.** Solo messes: today's undo, unchanged (80 whole snapshots in `AppState`). A restore changes the mess, the
session diffs it like any change, and the ops go out. Shared messes (more than one member): `AppState.undo()` and
`redo()` hand over to the session's op undo (4.2). When a mess goes from solo to shared, the snapshot history is
cleared once and the app says "Undo now takes back only your own changes."

### 4.2 `SyncSession`

One session per open cloud mess.

```kotlin
fun interface Clock { fun nowMs(): Long }

/** The app side: owns the Project the screens draw. */
interface SyncHost {
    fun project(): Project
    /** The session built a new Project (local-only fields already carried). Swap it in and redraw. */
    fun replace(p: Project)
    /** The session changed project() in place. Redraw and rebuild the engine snapshot. */
    fun patched()
    fun onState(state: Int)
    fun onPeers(peers: List<Peer>)
    fun onNotice(kind: Int, n: Int, who: String)
    /** A sound the mess needs. Fetch it with SoundSync, register it, then rebuild the engine snapshot. */
    fun onSound(ref: SoundRef)
    /** Save or load the small cache for one mess (4.2, "Offline"). null = nothing saved. */
    fun cacheSave(messId: String, json: String)
    fun cacheLoad(messId: String): String?
}

class Peer(@JvmField val id: String, @JvmField val name: String, @JvmField val colour: Int, @JvmField val page: String,
           @JvmField val loop: Int, @JvmField val pad: Int, @JvmField val playing: Boolean, @JvmField val role: String)

class SyncSession(calls: CloudCalls, clock: Clock, host: SyncHost, @JvmField val messId: String, @JvmField val me: String) {
    companion object {
        const val OPENING = 0; const val SOLO = 1; const val LIVE = 2; const val WATCH = 3   // viewer, or 4 already editing
        const val OFFLINE = 4; const val GAP = 5; const val GONE = 6                         // removed, deleted or blocked
        const val N_REPLACED = 1     // n of your offline changes replaced who's
        const val N_UNDO_SKIPPED = 2 // n parts of the undo were skipped: who changed them since
        const val N_JOINED = 3; const val N_LEFT = 4; const val N_NEWER = 5; const val N_FULL = 6
        const val SEND_MS = 200L; const val SOLO_SEND_MS = 5000L; const val POLL_MS = 10000L; const val SOLO_POLL_MS = 30000L
        const val SNAP_OPS = 50; const val SNAP_MS = 60000L; const val GAP_OPS = 500; const val GAP_MS = 86400000L
    }
    @JvmField var state = OPENING
    @JvmField var role = ""
    @JvmField var serverRev = 0L       // highest rev applied to the server copy, with no gaps below it
    @JvmField var shownRev = 0L        // other people's ops up to here are on screen
    @JvmField var sawNewer = false

    fun open()                                   // cacheLoad, then open_mess (or ops_since), then join when shared
    fun close()                                  // flush, snapshot if due, leave_live, leave the channel, cacheSave
    fun tick()                                   // call at least every 100 ms while open
    fun localChanged(undoable: Boolean)          // call after every change to host.project()
    fun setPlaying(playing: Boolean)
    fun setPerPass(on: Boolean)                  // Rec is running: send own ops at loop tops only
    fun setPresence(page: String, loop: Int, pad: Int)
    fun held(): Int                              // other people's batches waiting for the loop top
    fun preview(): Project?                      // the mess as it will be after loopTop(); null when held() == 0
    fun loopTop()                                // the local player just wrapped
    fun canUndo(): Boolean; fun canRedo(): Boolean
    fun undo(); fun redo()
    fun pending(): Int                           // own ops not yet confirmed
    fun saveVersion(label: String)               // snapshot now, with a label
    fun resolveGap(merge: Boolean)
    fun shared(): Boolean                        // more than one member
}
```

**What it holds**

| Name | What |
|---|---|
| `base` | A `Project`: the server's mess at `shownRev`. Only ever changed by applying server batches in rev order. |
| `log` | Server batches with `shownRev < rev <= serverRev`, in order, each marked mine or theirs. |
| `inbox` | Batches that arrived with a rev above `serverRev + 1` (a gap below them). |
| `pending` | My ops not confirmed: the batch in flight (with its id) and the ops waiting behind it. |
| `shadow` | A `Project` equal to the screen's mess as of the last `localChanged`. |
| `undoStack`, `redoStack` | Up to 80 `Change`s, each with the rev it was confirmed at. |
| `touched` | Field key or scope to the rev of the last op **by someone else** that hit it. |

The one sentence that defines the screen: **the mess on screen is `base`, then my batches in `log`, then `pending`.**
Other people's batches in `log` are not on screen yet.

**Local change.** `localChanged` computes `Ops.change(shadow, host.project())`, appends the ops to `pending`, applies
them to `shadow`, and when `undoable` pushes the `Change` (changes with `undoable = false`, such as a fader drag or
hits during a take, fold into the next undoable one). `redoStack` clears.

**Sending.** One batch in flight at a time. When none is in flight and `pending` has waiting ops and `SEND_MS` has
passed (5 s when solo; at once on `close` and `saveVersion`): `squash` them, give the batch a fresh uuid, call
`apply_ops`. On success the batch moves into `inbox` at its rev, marked mine. On a network error, send the same
batch with the same id again after 1, 2, 5, then 10 s; state goes `OFFLINE` after the first failure. On
`rate_limited`, wait 1 s. On `forbidden`, `not_found` or `blocked`: state `WATCH` or `GONE`, `pending` is dropped and
the screen goes back to `base` plus `log`. With `setPerPass(true)`, waiting ops go only from `loopTop()`.

**Receiving.** A broadcast `ops` or an `ops_since` answer puts batches in `inbox` by rev (a rev already seen is
ignored; my own batch comes back by broadcast too). While `inbox` holds `serverRev + 1`, move it to `log`, raise
`serverRev`, and note in `touched` every key and scope of a batch that is not mine. If a gap lasts 2 s, call
`ops_since(serverRev)`. Every `POLL_MS` (30 s solo) call `ops_since` anyway: broadcasts are best effort (2.4). An
answer of `gap`, or a `doc.reset` batch, means: call `open_mess` and rebuild `base` from its doc and ops.

**Release** moves `log` into `base` and puts other people's ops on screen:

- Not playing: release as soon as `log` has a batch of theirs.
- Playing: only in `loopTop()`. This is decision 2. The host calls `loopTop()` when the engine says its loop wrapped.
- To release: apply every batch of `log` to `base` in order, set `shownRev = serverRev`, empty `log`. Then build
  `next = base.copy()`, apply `pending`, `Ops.carryLocal(host.project(), next)`, call `host.replace(next)`, and set
  `shadow` to a copy of it. This rebuild **is** the definition. It also gives the no-flicker rule for free: my
  unconfirmed value for a field is applied last, so another person's value for that field never shows (plan s5 item 5).
- Fast path, allowed but not required: when every op of theirs in `log` is a known field op, and none of their keys
  or scopes is hit by one of my later batches in `log` or by `pending`, apply their ops straight to `host.project()`
  and `shadow` and call `host.patched()`. It must give the same `canon` as the rebuild; the fuzz test checks both.
- `preview()` returns what the rebuild would give, without changing anything, so the host can hand the engine the
  next loop's snapshot ahead of the wrap (4.6).

**Undo** (shared messes). Pop the newest `Change`. For each op in its `undo` list: skip it when `touched` shows
someone else hit its key, or a scope around it, after the rev this change was confirmed at (a change not confirmed yet
is never skipped). For a structural undo op, skip it when anyone else touched anything inside its scope since. Apply
the rest to `host.project()`, call `host.patched()`, treat them as a normal local change that is not itself
undoable, and push the reverse onto `redoStack`. If any were skipped: `onNotice(N_UNDO_SKIPPED, n, who)`.

**Snapshots.** A phone uploads `save_snapshot` when it is `LIVE` or `SOLO`, `sawNewer` is false, and
`serverRev - docRev >= SNAP_OPS` or `SNAP_MS` passed with `serverRev > docRev`; also on `close` and `saveVersion`.
The doc is `Ops.uploadDoc(copy of base with all of log applied, shared())`: the server's mess at `serverRev`, with
none of `pending`. In a shared mess only the peer with the lowest user id among live editors does the timed upload;
the others step in only at twice the thresholds. `stale` is a normal answer and is ignored.

**Opening and catching up.** `open()`: load the cache (`{v: 1, mess, rev, base}`: the base doc and its rev). No
cache: `open_mess`, `base = fromJson(doc)` then its ops; the screen's mess becomes `base` (first open on this
phone). With a cache: `base` from it, `pending = Ops.diff(base, host.project())` (so edits made offline, or just
before a crash, are found again without trusting a saved queue), then `ops_since(rev)`. Other people's batches go
into `log` and are released; `pending` replays on top and is sent. Count the pending ops whose key they touched:
`onNotice(N_REPLACED, n, who)` (plan s5, "Offline edits"). If their ops number over `GAP_OPS`, or mine do, or the
cache is over `GAP_MS` old with ops on both sides: state `GAP`, nothing is sent, and the host asks. `resolveGap(true)`
goes on as above. `resolveGap(false)`: the host first saves the screen's mess as a new cloud mess (`fork_mess` with
its doc), then the session drops `pending` and shows `base`. After `open_mess` the session calls `host.onSound` for
each `sounds` entry, `join_live`, and, when the mess has more than one member, joins `mess:<id>`. A solo session
that sees `members > 1` in an `ops_since` answer turns itself into a shared one (state `LIVE`, op undo, channel).

**Presence.** The session tracks `{u, n, c, page, loop, pad, playing, role}` on the channel: at join, and when
`setPresence` or `setPlaying` changes something, at most once every 2 s. `pad` is the pad being shaped (Edit tray,
Sample sheet or synth knobs open), else -1. `onPeers` gets the full list, self left out, whenever it changes.
[Certain] Presence is capped at 20 messages a second for a whole Free project, 50 on Pro.

The cache is written through `host.cacheSave` at most every 2 s after a release or a confirmation, and on `close`.

### 4.3 `CloudApi`, `CloudCalls` and the session store

```kotlin
class CloudErr(@JvmField val code: String, @JvmField val http: Int, @JvmField val detail: String)
// code: a server code from section 2, or "net" (no answer), "auth" (401 after one refresh), "http" (anything else)

fun interface Done<T> { fun done(value: T?, err: CloudErr?) }   // exactly one of the two is null (Unit calls pass true)

class Session(@JvmField val access: String, @JvmField val refresh: String, @JvmField val expiresAt: Long,   // epoch seconds
              @JvmField val userId: String, @JvmField val email: String)

interface RealtimeListener {
    fun onJoin(topic: String, ok: Boolean, reason: String)
    fun onBroadcast(topic: String, event: String, payload: Map<String, Any?>)
    fun onPresence(topic: String, peers: List<Map<String, Any?>>)      // the whole list, each time it changes
    fun onDrop(topic: String)                                          // the socket or channel went away
}

/** What a platform implements. Nothing here knows a table or an op. */
interface CloudApi {
    fun configured(): Boolean
    // sign-in
    fun requestCode(email: String, cb: Done<Boolean>)
    fun verifyCode(email: String, code: String, cb: Done<Session>)
    fun signInPassword(email: String, password: String, cb: Done<Session>)     // the review account only
    fun refresh(refreshToken: String, cb: Done<Session>)
    fun signOut(cb: Done<Boolean>)
    fun setSession(s: Session?)                                               // the token every later call carries
    // server functions
    fun rpc(name: String, args: Map<String, Any?>, cb: Done<Any?>)            // the parsed JSON answer
    fun fn(name: String, body: Map<String, Any?>, cb: Done<Any?>)             // an Edge Function
    // files, by path on the device (CloudFiles reads and writes them)
    fun upload(bucket: String, path: String, localFile: String, cb: Done<Boolean>)
    fun download(bucket: String, path: String, localFile: String, cb: Done<Boolean>)
    fun downloadUrl(url: String, localFile: String, cb: Done<Boolean>)        // a signed URL from shared-sounds
    fun remove(bucket: String, path: String, cb: Done<Boolean>)
    // realtime
    fun join(topic: String, presenceKey: String, l: RealtimeListener)
    fun track(topic: String, state: Map<String, Any?>)
    fun leave(topic: String)
}

/** Device files, so the core and FakeCloud never touch java.io. */
interface CloudFiles {
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
    fun size(path: String): Long
    fun delete(path: String)
    fun sha256(path: String): String          // may stream; must equal Sha256.hex(read(path))
}

/** Where the signed-in session is kept between launches. One string, or null. */
interface SessionStore { fun load(): String?; fun save(json: String?) }
```

`CloudCalls(api: CloudApi)` is common Kotlin and wraps `rpc` and `fn` in one typed method per function of section
2 (`openMess(messId, cb: Done<OpenedMess>)`, `applyOps(messId, batchId, ops, cb: Done<Applied>)`, and so on, with
small result classes parsed from the JSON). Builders add a method there when they need one; they never call `rpc`
with a bare name elsewhere. This is what lets `FakeCloud` and the real server be compared by name (s8d).

`Account(api, store: SessionStore, clock: Clock)` is common Kotlin: `signedIn()`, `userId()`, `email()`,
`requestCode`, `verify(email, code, cb)` (a "code" that is not 6 to 10 digits is tried as the review password, s7),
`signOut`, and `tick()`, which refreshes the token 5 minutes before it runs out and calls `api.setSession`. A
refresh that fails with anything but `net` signs the person out and says so. [Likely] Supabase refresh tokens are
single use, so the new one is saved before anything else happens.

### 4.4 `FakeCloud`

`cloud/fake/FakeCloud.kt`, common Kotlin: `class FakeCloud(clock: Clock, files: CloudFiles)` holds a whole server
in memory and hands out one `CloudApi` per pretend phone: `fun client(email: String): CloudApi`. It implements every
function of section 2 with the same checks, in the same order, with the same error codes. Test controls:
`deliver()` (run queued callbacks), `setOnline(client, on)`, `dropNextAnswer(client)`, `shuffleBroadcasts(seed)`,
`delayBroadcasts(n)`, `lastCode(email)` (the sign-in code it "mailed"), `makeAdmin(email)`, `advance(ms)`. Nothing
happens inside a call: answers and broadcasts queue until `deliver()`, so tests choose every interleaving.

### 4.5 Sounds

Which keys upload (plan s3, "Where sound files live"): only `bn:` keys. `chop:` keys send no file; their base
sound comes along. A granular synth's `src`, when it is a `bn:` key, comes along too. Built-in, `qb:`, `pack:` and
`synth:` keys need nothing.

**FLAC, not WAV.** Decided: FLAC. Why: decision 7's sums (250 MB is about 38 minutes) only hold with it;
[Likely] it halves storage and download; the engine already reads FLAC (`Flac.read`); iOS and the web get the same
bytes through the shared core, so the same audio gives the same sha256 on every platform.

- The writer: `core/src/commonMain/kotlin/com/tideclock/messmachine/engine/FlacEnc.kt`,
  `object FlacEnc { @JvmStatic fun encodeMono16(pcm: ShortArray, rate: Int): ByteArray }`. Mono, 16 bit, blocks of
  4096, fixed predictors of order 0 to 4 picked per block, Rice coding with one partition, a verbatim block when
  that is smaller, CRC-8 and CRC-16 as the format requires, MD5 field left as zeros (allowed: it means "not
  computed"). Its output must depend only on its input. Plus `Wav.pcm16(b: ByteArray): ShortArray?` to take the
  samples out of a Workbench WAV untouched.
- Tested in `tools/EngineTest.java`: `Flac.read(FlacEnc.encodeMono16(x, r))` is sample exact for silence, a full
  scale square, seeded noise, the bundled kick re-encoded, lengths 1, 4095, 4096, 4097 and 100,000, at 44,100 and
  48,000; noise is under 101 percent of raw size and the kick under 70 percent; two runs give the same bytes. And
  against an outside decoder: `ffmpeg -v error -i x.flac -f s16le -` must give back the same samples
  ([Certain] `ffmpeg` is installed here at `/usr/local/bin/ffmpeg`; CI installs it with `apt-get`).
- `Sha256.kt`: `object Sha256 { @JvmStatic fun hex(b: ByteArray): String }`, plain Kotlin, tested on the standard
  vectors (`"abc"` gives `ba7816bf...15ad`). Android's `CloudFiles.sha256` may use the platform's digest.

**The mess `sounds` map** (a format change: `Project.V` becomes 4, `"sounds"` joins `TOP_KEYS`, MESS-FORMAT s3
gains a row and a section 4.4, new fixture `shared/fixtures/cloud-sounds.mess.json`; older apps keep the key
untouched as an unknown key and say the file is newer):

```
"sounds": {
  "bn:mf3k2a417":  {"sha256": "<64 hex>", "owner": "<uuid>", "title": "Mic take 3", "kind": "mic",
                    "bpm": 96, "beats": 0, "mkey": "A minor", "seconds": 4.2, "rate": 48000, "bytes": 123456},
  "chop:mf3k9q201": {"kind": "chop", "base": "bn:mf3k2a417", "title": "Chop 1"}
}
```

In code: `class SoundRef` (those fields plus `key`), `@JvmField val sounds: MutableMap<String, SoundRef>` on
`Project`. Written only for keys on a pad, a chop's base, or a granular `src`. `Ops.apply` of a `pad.put` with a
`sound` adds the entry. When online, the `sounds` list from `open_mess` wins over the map (it knows `vault` and `gone`).

**`SoundSync(calls, files: CloudFiles, clock)`**, common Kotlin, one per account:

| Step | Rule |
|---|---|
| Send a sound up | Encode FLAC to `<cloud dir>/up/<sha256>.flac`; `register_sound`; on `upload`: `api.upload('sounds', path, file)` then `finish_upload`; then `attach_sound` for each mess that uses it. On Wi-Fi only unless the person allows mobile data. One upload at a time. `quota`, `too_long`, `too_big` surface as plain messages and the sound stays on this phone. |
| A new take in a shared mess | `register_sound`, `attach_sound`, send the `pad.put` at once, upload behind it. Other phones show the pad as "Arriving" until `meta {kind:"sound"}` comes. |
| Fetch a sound | Cache file `<cloud dir>/sounds/<sha256>.flac`. Present and its hash right: use it. Else `download` from `sounds/<owner>/<sha>.flac` (`src` user) or `vault/<sha>.flac` (`src` vault), verify sha256, else delete it and report missing. `src` gone: the pad reads "Missing: its maker left." Guests use `shared-sounds` URLs. |
| Register it | The host adds it to the Library under the mess's own key (`bn:<id>`), in a jar named "From shared messes". If this phone's own Workbench already has that key with other audio (ids are a millisecond clock plus 3 digits, so [Guessing] about never), the Workbench wins and the pad reads Missing. |
| Free space | The cache may be cleared at any time; sounds download again on next open. |

### 4.6 The engine hook for "next loop"

`Engine.kt` gains two things (a new feature, not a translation, so `KOTLIN-ENGINE.md`'s "no improvements" rule is
not broken; `EngineTest` gets a case):

```kotlin
/** Held until the next time the step counter wraps to 0 (a loop pass ends, or a song slot ends), then swapped in on the audio thread. null cancels. */
fun setSnapshotAtWrap(s: Snapshot?)
/** Goes up by one each time a held snapshot was swapped in. Read from any thread. */
@JvmField @Volatile var wrapSwaps: Int
```

The swap happens where the code today does `step = 0; loops++`, before step 0 of the new pass is fired, so a hit
someone else put on step 0 sounds on that very pass. `setSnapshot` (an immediate change) leaves a held snapshot in place.

## 5. Android

### 5.1 Configuration

`gradle.properties` (checked in) gains `mm.cloudUrl=`, `mm.cloudAnon=`, `mm.siteHost=`, empty by default.
`app/build.gradle.kts` turns them into `BuildConfig.CLOUD_URL`, `CLOUD_ANON`, `SITE_HOST` and the manifest
placeholder `siteHost` (default `unset.invalid`). Gradle properties rather than a JSON asset because the manifest
needs the host at build time for links that open the app. The anon key is safe to check in (plan s7 step 1).

**When `CLOUD_URL` or `CLOUD_ANON` is empty, `AppState.cloud` is null**, no control in 5.3 is composed, no network
call is made, and the app behaves exactly as today. The big test asserts this on a build with empty values.

### 5.2 Platform layer (`app/src/main/java/com/tideclock/messmachine/`)

| File | What |
|---|---|
| `SupabaseApi.kt` | `class SupabaseApi(url, anon, main: Handler) : CloudApi`. REST with `HttpURLConnection`, in the style of `Updater.kt` and `Packs.kt`, on one background thread; every callback posted to the main thread. Only GET, POST, PUT and DELETE without a body are used, because `HttpURLConnection` cannot send PATCH. |
| `SupabaseRealtime.kt` | The websocket, inside `SupabaseApi`. Phoenix protocol version 1.0.0, JSON text frames. |
| `CloudState.kt` | `class CloudState(app, api: CloudApi, state: AppState) : SyncHost`. Everything the screens read: `account`, `me`, `onlineMesses`, `session`, `peers`, `usage`, sign-in steps, share and member lists, upload queue. Keeps cloud code out of the 190 KB `AppState.kt`. |
| `CloudStore.kt` | `SessionStore` and `CloudFiles` for Android. The session is one string in a private prefs file `cloud`, sealed with an AES-GCM key from the Android Keystore, plain if the Keystore fails. `files/cloud/` holds `index.json` (local mess id to cloud mess id), `<messId>.cache.json`, `sounds/`, `up/`. `cloud` prefs and `files/cloud/` are left out of device backups. |

Calls `SupabaseApi` makes ([Likely] unless marked; all are in the s8e checklist):

| Need | Request |
|---|---|
| Ask for a code | `POST /auth/v1/otp` `{"email": e, "create_user": true}` |
| Check a code | `POST /auth/v1/verify` `{"type": "email", "email": e, "token": code}`; the answer is the session |
| Review password | `POST /auth/v1/token?grant_type=password` `{"email", "password"}` |
| Refresh | `POST /auth/v1/token?grant_type=refresh_token` `{"refresh_token"}` |
| Sign out | `POST /auth/v1/logout` |
| RPC | `POST /rest/v1/rpc/<name>` |
| Edge Function | `POST /functions/v1/<name>` |
| Upload | `POST /storage/v1/object/sounds/<path>`, `Content-Type: audio/flac`, streamed from the file ([Certain] this endpoint; Supabase advises resumable uploads above 6 MB, see risk 5) |
| Download | `GET /storage/v1/object/authenticated/<bucket>/<path>`, streamed to a file |
| Delete | `DELETE /storage/v1/object/sounds/<path>` |
| Realtime | `wss://<project>.supabase.co/realtime/v1/websocket?apikey=<anon>&vsn=1.0.0` [Certain]. Join: topic `realtime:mess:<id>`, event `phx_join`, payload `{"config": {"broadcast": {"ack": false, "self": false}, "presence": {"enabled": true, "key": "<uid>"}, "private": true}, "access_token": "<jwt>"}` [Certain]. Heartbeat on topic `phoenix` every 25 s [Certain]. A new token is sent as event `access_token` [Certain]. Reconnect after 1, 2, 5, 10 s. |

Every request carries `apikey: <anon>` and, when signed in, `Authorization: Bearer <access>`.

**The websocket dependency: `com.squareup.okhttp3:okhttp:4.12.0`, used for the websocket only.** Android has no
websocket client of its own. The smaller ways are worse: writing RFC 6455 by hand over `SSLSocket` means owning
hostname checks, masking, fragments, ping and close, where a mistake is a security or battery bug; the small
libraries (`Java-WebSocket`, `nv-websocket-client`) are [Likely] less kept up and save a few hundred KB at most
after R8. If the socket cannot connect at all, a session still works by its `ops_since` poll, without presence.

**Hooks in `AppState.kt`** (the only edits to it; all no-ops when `cloud` is null):

| Where | Call |
|---|---|
| constructor | `AppState(app, cloudApi: CloudApi? = null)`: tests pass a `FakeCloud` client; the app passes `SupabaseApi` when configured |
| `changed(commit)` | `cloud?.localChanged(commit)` after the engine rebuild |
| `undo()`, `redo()`, `canUndo`, `canRedo` | when `cloud?.sharedSession()` is true, hand over to the session |
| `replaceProject` and `openMess` | close the old session; open one if the opened mess is in `index.json` |
| `saveMess` | after the local write: `cloud?.saved(id, online)` (make the cloud mess, or `saveVersion("Saved")`) |
| the frame loop in `ui/App.kt` (`withFrameNanos`) | `cloud?.tick()`; when `engine.wrapSwaps` changed, `session.loopTop()` |
| `rebuild()` | when `session.held() > 0`: `engine.setSnapshotAtWrap(Snapshot.build(session.preview(), lib, live))` |
| play, stop, Rec | `session.setPlaying`, `session.setPerPass(live.rec && playing)` |

`SyncHost.replace` uses the same path as today's undo `restore()`, minus the undo push.

### 5.3 Screens and controls

All in a new `ui/CloudScreens.kt` unless a file is named. Each tag also needs its one plain line in `Faq.kt` (the big
test fails without it) and a row in `shared/PARITY.md` in the same commit. `<id>` is the first 8 characters of an id.

Design rules that bind this list (DESIGN.md pillars, and "fight overwhelm"): nothing is added to the Pads page
except the two things marked **play**. Everything else lives behind the Messes sheet. Pick-one choices are sliders,
like the rest of the app. Words are short and say what happens.

**Sign in** (opened from the Messes sheet)

| Tag | Behaviour |
|---|---|
| `acct-open` | A row at the top of the Messes sheet: "Sign in to save online" or your name. Opens the Account page. |
| `acct-email` | Email field. |
| `acct-send` | "Send me a code." Mails a 6 digit code. |
| `acct-code` | Code field, digits. |
| `acct-verify` | "Sign in." Checks the code. |
| `acct-resend` | "Send a new code." Wakes up after 60 seconds. |
| `terms-text`, `terms-agree`, `terms-decline` | First sign-in, and whenever the terms change: the terms in a scroll box, "I agree", "Not now" (signs out). Nothing uploads before "I agree". |
| `cloud-move-all`, `cloud-move-pick`, `cloud-move-later` | Asked once after the first sign-in: "Put your 12 messes online? All / Pick / Not now." |

**Account page**

| Tag | Behaviour |
|---|---|
| `acct-name` | The name others see next to your colour. |
| `acct-colour-<n>` | Your colour, 8 to pick from (`shared/tokens.json`). |
| `acct-usage` | A bar and a line: "112 MB of 250 MB used." Red when a take will not fit, with the reason. |
| `acct-sounds` | Your online sounds, biggest first: size, how many messes use each. |
| `acct-sound-del-<id>` | Remove one from online. Says first: "2 messes use this. It will read Missing there." |
| `acct-data` | "Use mobile data for sounds": off out of the box. |
| `vault-mine` | Your Vault submissions and where each stands. `vault-withdraw-<id>` takes back one still waiting. |
| `acct-blocks` | People you blocked, with Unblock. |
| `acct-terms`, `acct-privacy` | Open the two web pages. |
| `acct-signout` | Signs out. Says: "Your messes on this phone stay." |
| `acct-delete` | Opens Delete account. |

**Messes sheet** (`ui/FilesScreen.kt`)

| Tag | Behaviour |
|---|---|
| `mess-where` | Two-way slider on the Save dialog: "Save online" / "Keep on this phone only". Remembers the last pick. Shown only when signed in. |
| `mess-tab-phone`, `mess-tab-online` | The list's two sides: "On this phone", "Online". |
| `omess-<id>` | An online mess: name, owner if not you, a people mark when shared, "2 here" when others have it open. Tap opens it. |
| `omess-share-<id>`, `omess-dup-<id>`, `omess-del-<id>`, `omess-keep-<id>` | Share, Duplicate (a copy you own), Delete (owner) or Leave (others), "Keep a copy on this phone". |
| `link-paste` | "Open a link": paste a share or invite link. |
| `sync-state` | One lamp on the open mess's row: green online, amber "3 changes waiting", grey offline. |
| `hist-open` | "History" for the open online mess. |
| `report-open` | "Report this mess", on any mess you did not make. |

**Share sheet**

| Tag | Behaviour |
|---|---|
| `share-link` | Three-way slider: Off / View / Copy. View: people can listen. Copy: they can also make it theirs. |
| `share-link-send` | Sends the link with the phone's share sheet. |
| `share-link-new` | "Make a new link." The old one stops working. |
| `share-invite` | "Invite to edit." Makes a link good for 7 days and sends it with the phone's share sheet. |
| `share-invite-role` | Two-way slider: Editor / Viewer. |
| `share-invite-email` | Optional: only this email can use the invite. |
| `share-members` | Opens Members. |

**Members**

| Tag | Behaviour |
|---|---|
| `mem-<id>` | A person: colour, name, role. |
| `mem-role-<id>` | Owner only. Two-way slider: Editor / Viewer. |
| `mem-remove-<id>` | Owner only. Removes them. What they added stays. |
| `mem-block-<id>` | Owner only. Removes them and keeps them out of all your messes. |
| `mem-owner-<id>` | Owner only. "Hand this mess to Mia." |
| `mem-leave` | Not the owner: leave this mess. |
| `inv-revoke-<id>` | Owner only. Cancels an invite not used yet. |
| `invite-join`, `invite-no` | Opening an invite link: "Join Basement jam as an editor?" |

**While a shared mess is open**

| Tag | Behaviour |
|---|---|
| `here-chip` | Coloured dots, one per other person here, in the top row of every page **except Pads**. Hidden when alone. Tap opens the Here list. |
| `here-<id>` | A person here: name, page, loop, "shaping pad 5". |
| `pad-lock-<pad>` | **play.** A thin ring in a person's colour on a pad they are shaping. Not tappable. Only drawn when someone else is shaping. |
| `next-loop` | **play.** A small lamp with a count by the loop strip: "2" means two changes from others arrive at the top of the next loop. Only drawn while playing and something is held. Not tappable. |
| `lock-watch`, `lock-take` | Opening a pad someone is shaping: "Mia is shaping this pad. Watch, or take over?" |
| `loop-ask-go`, `loop-ask-no` | Clearing or resizing a loop someone is in: "Mia is in loop B. Clear it anyway?" |
| `sync-newer` | A line in the Messes sheet: "Someone here has a newer Mess Machine. Update to see everything they do." |
| `gap-merge`, `gap-copy` | After a long time apart: "Merge" / "Keep mine as a copy". |
| `shared-banner`, `shared-mine`, `shared-close` | Viewing a share link: "Mia's mess. Nothing you do here is saved." "Make it mine" (Copy links). Close. |

A pad whose sound is still coming reads "Arriving"; one whose maker left reads "Missing". Both use the existing
Missing pad face (MESS-FORMAT s4.2).

**History**

| Tag | Behaviour |
|---|---|
| `hist-<n>` | A saved version: when, who, its label. |
| `hist-save` | "Save a version now", with a name. |
| `hist-restore-<n>` | "Go back to this." The mess as it is now is kept as "Before restore". |

**Vault** (on a Workbench sound's menu, `ui/SoundsScreen.kt`)

| Tag | Behaviour |
|---|---|
| `vault-give` | "Give this sound to the Vault." Uploads the sound first if it is not online. |
| `vault-grant` | The grant text, in a scroll box, as served by `vault_grant()`. |
| `vault-credit` | "Credit me as": the name shown with the sound. |
| `vault-agree` | A switch: "I made this sound and I agree." |
| `vault-send` | Sends it. Asleep until the switch is on and a credit name is typed. |

**Report**

| Tag | Behaviour |
|---|---|
| `report-reason-<id>` | Pick one: sexual, hate, harassment, copyright, spam, other. |
| `report-details` | Optional words, up to 500 characters. |
| `report-send` | Sends it. "Thanks. We look at every report." |

**Delete account**

| Tag | Behaviour |
|---|---|
| `del-what` | Two short lists. Goes: your online messes and their history, your online sounds (also out of other people's messes), your share links, your place in other people's messes. Stays: the messes and sounds on this phone, and any sound the Vault accepted, with your credit name. |
| `del-hand-<id>` | For each mess of yours that others edit: pick an editor to hand it to, or "Delete it". |
| `del-code-send`, `del-code` | A fresh code, mailed now. |
| `del-go` | "Delete my account." Asleep until the code is typed. Ends with "Your account is deleted." and guest mode. |

Links that open the app: `AndroidManifest.xml` gains a verified filter for `https://${siteHost}/m/*` and `/j/*`
and an unverified one for `messmachine://m/<token>` and `messmachine://j/<token>` (the landing page's button, which
works before the verified links do).

Sound length in the app today: imports stop at 60 s (`Workbench.decode`) and mic takes at 30 s
(`Workbench.kt`, "at 30 seconds"). Phase 3 raises imports to 100 s. A song-length live take needs a recorder that
writes to disk while the song plays; the server and the format are ready for it, the recorder is risk 6.

## 6. Web pieces on Netlify

Folder `web/account/`, published as the Netlify site (decision 6). Plain static pages. `supabase-js` 2 comes from
`https://cdn.jsdelivr.net/npm/@supabase/supabase-js@<exact version>` with an integrity hash; pin the version on
the day it is built. Sign-in on every page is the same email code. All data goes through the section 2 functions.

| Path | File | What |
|---|---|---|
| `/` | `index.html` | "Mess Machine account": sign in, see your name and usage, links to the pages below. |
| `/delete/` | `delete/index.html` | Account deletion. Names the app and the developer as on Play. Says what goes and what stays (the `del-what` words). Sign in with a code, hand over or delete shared messes, type a fresh code, press Delete: calls `delete-account`. Also a support email for people who cannot sign in. |
| `/m/<token>` | `m/index.html` | Share link landing. Calls `open_shared`: shows the mess name, the owner's name, tempo and pad count. "Open in Mess Machine" (`messmachine://m/<token>`), "Get the app", "Report this mess" (`report_mess` with the token). When the web Mess Machine exists it plays here. |
| `/j/<token>` | `j/index.html` | Invite landing. Calls `invite_info`. "Open in Mess Machine", "Get the app". |
| `/vault/` | `vault/index.html` | Tyler's review page. Sign in; `me()` must say `is_admin`, else the page says "Not for you" and the server refuses every call anyway. Lists `vault_queue`: play the sound, title, credit name, note, grant version. Approve (copy the file to `vault`, then `vault_review`) or Decline with a note. A second tab lists `admin_reports` with Dismiss and "Take the mess down". |
| `/privacy/`, `/terms/` | `privacy/index.html`, `terms/index.html` | The two texts. The terms page carries its version; `config.terms_version` must match it. |
| | `config.js` | The project URL and anon key. |
| | `mm.js`, `mm.css` | Shared sign-in and look (`shared/tokens.json` colours). |
| | `_redirects` | `/m/* /m/index.html 200`, `/j/* /j/index.html 200`. |
| | `.well-known/assetlinks.json`, `.well-known/apple-app-site-association` | So links open the app. The Android file lists the SHA-256 of the sideload key now and Play's signing key later. |

## 7. Google Play and Apple

| Requirement | How it is met |
|---|---|
| Play: delete the account in the app [Certain] | `acct-delete` to `del-go` (5.3). |
| Play: a web link to delete, working without the app, naming the app or developer [Certain] | `/delete/`. Its address goes in the Data safety form. |
| Play: deleting the account deletes its data; say what is kept [Certain] | `delete_account_data` (2.3). Kept, and said on `/delete/` and in the policy: Vault sounds with the credit name, and the text of reports. |
| Play Data safety [Likely the right answers] | Collected: email address and user ID (account management), audio the person uploads ("Voice or sound recordings" and "Music files"), other user content (messes, display name, credit name), all for app functionality, all optional (the app works as a guest). Shared with third parties: no (Supabase and the mail service are processors). Encrypted in transit: yes. Deletion can be requested: yes, with the link. |
| Play user content policy: accept terms before making or uploading content [Certain] | `terms-agree`; the server refuses `terms` on every upload function. |
| Play: define what is not allowed [Certain] | The terms page lists it. |
| Play: report content and people in the app; block people where there is 1 to 1 contact [Certain] | `report-open` on every mess you did not make and on the share banner; `mem-block-<id>`; `leave` on any mess. |
| Play: act on reports [Certain] | `/vault/` reports tab, `admin_block_mess`, banning in the dashboard. |
| Play and Apple: reviewers need a way in without your inbox [Likely] | One account `review@<site host>` made by hand with a password. On the code screen, a "code" that is not 6 to 10 digits is tried as a password. Reviewer notes say: email, then type the password where the code goes. No extra screen. |
| Apple 5.1.1(v): delete the whole account in the app; shared content goes too [Certain per plan] | Same flow. Decision 5 is the rule for shared content. |
| Apple 1.2 user content: filter, report, block, contact details [Likely] | Names pass `mm_private.clean_name` (a short blocked-word list); report and block as above; a support email on every web page. Audio cannot be filtered ahead; it is handled by reports. |
| Apple 4.8 | Not triggered: email code only (decision 1). |
| Privacy policy additions | What is stored (email, messes, sounds you choose to put online, display name), where (Supabase, region), who sees it (people you share with), how long, how to delete, what the Vault grant keeps, that bug notes are GitHub issues outside the account (plan s2), the support address. |

## 8. Test plan

No second person is needed for any of it.

### 8a. SQL and policies against a real Postgres

What runs in this workspace, checked today:

| Option | Result |
|---|---|
| Docker | The daemon starts (`dockerd`), but `docker run --rm hello-world` fails: `Forbidden (agent proxy: no rule or network setting allows this host)`. Same for ECR, GHCR, Quay and the GCR mirror. **Not usable here.** [Certain] |
| The Postgres 16 already installed | `pg_ctlcluster 16 main start` gives PostgreSQL 16.15 with `pgcrypto`. Row level security, roles and two racing connections all work. **This is the one to use here.** [Certain] |
| `npm i @electric-sql/pglite` | Installs (0.6.0), runs, enforces row level security, has `pgcrypto`. One connection only, and it reports PostgreSQL 19.0, newer than Supabase. **The fallback, and the easy choice in CI.** [Certain] |

```
pg_ctlcluster 16 main start
su postgres -c "psql -X -q -c \"alter user postgres password 'postgres'\""
cd server/tests && npm ci          # pg 8.x and @electric-sql/pglite 0.6.x
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5432/postgres node run.mjs    # makes and drops database mm_test
node run.mjs --pglite              # the same tests with no server at all (skips the two-connection cases)
```

- `server/tests/stub.sql` stands in for Supabase: roles `anon`, `authenticated`, `service_role`; `auth.users`;
  `storage.buckets`, `storage.objects` (row level security on) and `storage.foldername`; `realtime.messages` (row
  level security on), `realtime.topic()` reading the setting `realtime.topic`, and `realtime.send` writing to that
  table so tests can read what was broadcast. `auth.uid()` is Supabase's own [Certain: it reads `sub` from
  `request.jwt.claim.sub`, falling back to the `sub` key of `request.jwt.claims`]:

```sql
create function auth.uid() returns uuid language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claim.sub', true), ''),
                  (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub'))::uuid $$;
```

- `run.mjs` loads the stub, then every migration in order, then each `server/tests/*.test.mjs`. A test acts as a
  person with `as(user, fn)`: `begin; set local role authenticated; select set_config('request.jwt.claims',
  '{"sub":"<id>","role":"authenticated","email":"<e>"}', true); ... rollback or commit`. `asAnon` uses role `anon`.
- Users in every file: owner O, editor E, viewer V, stranger S, admin A, blocked person B, and anon.

| Test file | Proves |
|---|---|
| `deny.test.mjs` | For each of the 17 tables and both client roles: select, insert, update and delete are all refused. Every function not granted to `anon` refuses `anon`. No function in `mm_private` can be called except the six policy helpers. |
| `messes.test.mjs` | create (and the retry with the same id), the 200 cap, the 1 MB cap, open as each role and as S (`not_found`), rename and delete as non-owner (`forbidden`), transfer. |
| `ops.test.mjs` | Order: 200 batches from two connections at once get revs 1 to 200 with no gap or repeat (Postgres only). The same batch id twice returns the first rev. Viewer `forbidden`, S `not_found`, no terms `terms`, 6th batch in a second `rate_limited`, 501 ops and 65 KB refused, a 5th live editor `mess_full_live`, an editor's rename `forbidden`, `ops_since` paging and `gap` after a trim, a failing `realtime.send` (the stub can be told to throw) still stores the batch. |
| `snapshot.test.mjs` | `stale`, `ahead`, `client_too_old`, version made on label and after 10 minutes, trimming to 30, 20 and 8 MB, restore and `snapshot_first`, `doc.reset` stored and broadcast. |
| `share.test.mjs` | Token made, replaced, killed; `open_shared` on off, view, copy, blocked; fork with a view token `no_copy`; anon can call only the anon functions. |
| `members.test.mjs` | Invite once, N times, expired, revoked, locked to an email, the 9th member `mess_full`, blocked person `blocked`, role change, remove, leave, owner cannot leave. |
| `sounds.test.mjs` | Quota to the byte, `too_long` for non-mic and not for mic, 21 pending, `not_uploaded`, a bigger object than declared pushes over quota, attach by a stranger refused, delete marks `gone` or `vault`. Storage policies: upload only to your own folder and only a registered pending file; read your own, a mess-mate's listed sound, never a stranger's; no overwrite; delete only your own. |
| `realtime.test.mjs` | With `realtime.topic` set: members can select, strangers cannot, nobody can insert a `broadcast` row, members can insert `presence`, a blocked mess denies everyone but its owner. |
| `vault.test.mjs` | Submit needs a ready sound, the current grant and a clean name; one open submission per sound; non-admin refused on queue and review; approve without the copied file `not_copied`; approve, decline, withdraw. |
| `delete.test.mjs` | After `delete_account_data`: no row anywhere names the user except approved submissions (by credit name only) and reports (text only); other people's messes show `gone` or `vault`; running it twice is fine; the returned paths are exactly the user's folder. |
| `reports.test.mjs` | Member and token reports, the owner cannot report their own mess, both rate limits, admin resolve and take-down. |

### 8b. Core tests on the JVM

`tools/cloud-check.sh` is a sibling of `tools/kt-check.sh`: it compiles `core/src/commonMain` (engine and cloud)
with `kotlinc-jvm`, compiles `tools/CloudTest.java` with `javac`, runs it, and expects `ALL GOOD`.
`tools/cloud-check.sh js` compiles the lot for JavaScript. `tools/CloudTest.java` is a plain `main` with `check()`,
in the style of `tools/EngineTest.java`. Everything runs against `FakeCloud` with a fake clock.

| Group | Cases |
|---|---|
| Ops | For every op type: apply, its key, its scopes, its inverse restores `canon`. `diff` law (4.1) on the fixtures in `shared/fixtures/` and on 2,000 seeded random pairs. After every apply, `canon(p) == canon(fromJson(p.toJson()))` (rule 2). Loop shrink then grow shows nothing old (rule 3). Two new synths at once get two keys. One sound never ends on two pads. |
| Two phones | Different fields: both land. Same field: the later rev wins on both. No flicker: A holds an unconfirmed value, B's older value arrives, A's screen never shows B's. A structural op against a field op in both orders. |
| Next loop | B playing: A's three batches do not change B's screen until `loopTop()`, then all three at once; `held()` counts 3 then 0; `preview()` equals the screen after `loopTop()`. B stopped: they show on `deliver()`. B's own edits show at once either way. |
| Four phones | Four editors, a fifth gets `WATCH`. A viewer never sends. |
| Offline | A edits offline, B edits online, A returns: A's edits land last, `N_REPLACED` counts the clashes, both converge. A dropped answer: the batch is sent again with the same id and applied once. A restart mid-flight (cache only): nothing lost, nothing doubled. Long gap: state `GAP`, both answers. |
| Undo | Undo and redo of each op type alone. Skipped when another person touched the field since; not skipped when they touched another field. Structural undo skipped when anything inside changed. Solo mess: the app's snapshot undo produces ops that converge. |
| Unknown ops | A "newer" phone sends `{"t":"future.thing","x":1}` and a known op with an extra field: the old phone keeps both in its log text, applies what it knows, sets `sawNewer`, uploads no snapshot, and gets `client_too_old` if forced. |
| Catch-up | Join at a rev where `doc_rev` is behind: doc plus ops equals the others. Ops trimmed: `gap`, then a clean reopen. `doc.reset` reopens. Broadcasts lost entirely: the poll catches up. Broadcasts out of order: applied in rev order. |
| Fuzz | 300 seeded runs. 2 to 4 phones, each step picks one of: a random valid edit on a random phone (through `Project` methods, as the app would), deliver one queued message in random order, toggle a phone offline, toggle playing, `loopTop()`, undo, close and reopen from cache. At the end: all online, deliver all, `loopTop()` on all. **Every phone's `Ops.canon` must be identical**, equal to `FakeCloud`'s doc plus ops, and equal to a rebuild from `open_mess`. A failing seed prints itself. |
| Sounds | Upload once per hash, quota refusal keeps the sound local, download verifies sha256 and rejects a corrupted file, `gone` and `vault` sources, FLAC and SHA-256 cases of 4.5. |

### 8c. Android: the big test

`BigTest.kt` builds `AppState(ctx, fake.client("a@test"))`. New flows, each with screenshots to `test-screens`:
`signInWithACode` (wrong code, right code, terms, decline signs out), `noCloudConfigShowsNothing` (no tag from 5.3
exists), `saveOnlineAndOpenOnASecondPhone` (two `AppState`s on one `FakeCloud`), `shareLinkViewAndCopy`,
`inviteJoinRolesRemoveBlock`, `twoPhonesEditTogether` (steps, a fader, a pad swap; undo takes back only mine),
`changesArriveNextLoop` (phone B playing: `next-loop` shows 2, the step grid changes only after the wrap),
`softLockAsksBeforeTakingOver`, `offlineThenBack` ("3 changes waiting", then the notice), `historyRestore`,
`usageBarAndQuotaRefusal`, `giveASoundToTheVault`, `reportAMess`, `deleteAccountKeepsThePhonesMesses`. The existing
`MonkeyTest` gets a run with a shared session open and a second fake phone making random edits. The phone test
(`tools/phone-test.sh`) is unchanged: release builds have no cloud config until Tyler has a project.

### 8d. The fake and the real server must agree

`server/tests/contract/*.json` are scenario scripts: a list of steps `{as, call, args, expect}` where `expect` is
`{"ok": {subset of the answer}}` or `{"err": "<code>"}`. Ids and times are named (`"$mess1"`, `"$t"`) so the two
sides can differ in the actual values. Two runners read the same files and write one line per step:
`node server/tests/contract.mjs` (the SQL, on Postgres or pglite) and `tools/cloud-check.sh contract`
(`tools/CloudContract.java` driving `FakeCloud`). `diff` of the two transcripts must be empty; CI fails otherwise.
The scenarios cover every function of 2.3 and every error code at least once, and the listed check order where
two errors could both apply.

### 8e. Only once Tyler has made the Supabase project

`server/tests/live.mjs`, run by hand with the project URL, the anon key and two password test accounts Tyler
makes in the dashboard:

1. Migrations apply cleanly; `pg_cron` schedules `trim_ops`.
2. A code arrives by mail, is 6 digits, and `/auth/v1/verify` with `type: "email"` returns a session.
3. `apply_ops` from account 1 reaches account 2 over the websocket: this checks the `realtime.send` role, the topic
   name `mess:<id>` and the partition note in 2.4.
4. A stranger's join of `mess:<id>` is refused; a client `broadcast` push is refused; presence comes through.
5. Upload, `finish_upload` reads the real size from `storage.objects.metadata`; a second account cannot read the
   file until it is attached to a shared mess; an overwrite is refused.
6. A 30 MB upload over the plain endpoint finishes on a phone connection (risk 5).
7. `shared-sounds` gives working signed URLs to a client with no session.
8. `delete-account` on a throwaway account: refuses a stale login, then leaves no rows and no objects.
9. The Vault page copies a file to `vault` and approves; the copy survives deleting the submitter.
10. `https://<site>/m/<token>` opens the installed app.

## 9. Build phases

A session is one working session of Claude. A build is one green CI run that republishes `latest`.

| # | Ships | Sessions | Builds | Needs | Testable alone by |
|---|---|---|---|---|---|
| 1 | `server/`: migrations for all of section 2, the stub, tests 8a, the SQL side of 8d, the three Edge Functions (written, not deployed), a CI job for the tests | 2 to 3 | 0 (server job only) | this spec | 8a |
| 2 | `core/.../cloud/`: Ops, SyncSession, CloudApi, CloudCalls, Account, SoundSync, Sha256, FakeCloud; `FlacEnc`, `Wav.pcm16`, `Project.sounds` (format v4, fixture, MESS-FORMAT), `Engine.setSnapshotAtWrap`; `tools/cloud-check.sh`, `CloudTest.java`, the fake side of 8d | 3 to 4 | 1 to 2 | this spec | 8b, 8d |
| 3 | Android: `SupabaseApi`, `CloudStore`, `CloudState`, config, sign in, terms, account page with usage, Save online, online messes list, sounds up and down, imports to 100 s. Solo sync only. | 3 | 4 to 6 | 2 (1 for live checks) | 8c flows 1 to 3, 11 |
| 4 | Share link, landing pages `/m/` and `/j/`, links that open the app, invites, members, roles, block, leave, duplicate, history | 2 | 3 | 3 | 8c flows 4, 5, 10 |
| 5 | Live editing: shared sessions, the websocket, presence, `here-chip`, soft locks, `next-loop`, op undo, offline notices, long gap | 3 | 4 to 6 | 3 | 8c flows 6 to 9, the monkey run |
| 6 | Vault: `vault-give` flow, `/vault/` review page with the reports tab | 1 to 2 | 2 | 3 | 8c flow 12, 8e step 9 |
| 7 | Delete account in app and on `/delete/`, report, privacy and terms pages, the review account, Data safety answers written out for Tyler, `shared/PARITY.md` complete | 2 | 2 to 3 | 4 | 8c flows 13, 14, 8e step 8 |

What can run side by side: **1, 2 and the transport half of 3** (`SupabaseApi` needs only the `CloudApi` interface
and the request table in 5.2) start together, one builder each. The web pages of 4, 6 and 7 can be built any time
after 1. Phases 4, 5 and 6 do not depend on each other and can be taken in any order after 3. Phase 7 must be in
before any build goes to Play, and before anyone outside Tyler's test group gets cloud config in their build.

Frozen between builders (a change needs a line in this file and a word to the others):

| Interface | Frozen text | Between |
|---|---|---|
| Server functions: names, arguments, answers, error codes, check order | 2.3, 2.4 | 1 and 2 (FakeCloud), 1 and web |
| Op format | section 3 | 2 and iOS, web |
| `CloudApi`, `CloudFiles`, `SessionStore`, `Done`, `CloudErr` | 4.3 | 2 and 3 |
| `SyncSession` public methods and `SyncHost` | 4.2 | 2 and 3, 5 |
| Test tags | 5.3 | 3 to 7 and iOS, web |
| Mess format v4 `sounds` | 4.5 | 2 and iOS, web |

The app stays shippable throughout: with empty cloud config nothing shows, and each phase is behind that config.

## 10. Open risks, each with a default so nothing waits

1. **`realtime.send` from a security definer function.** [Likely] it works under the function owner; [Certain] it
   fails before the day's first websocket join. Default: best effort, plus the 10 s poll. Live editing degrades to
   "arrives within 10 s", never to lost edits. Checked in 8e step 3.
2. **The hand-written websocket client** (Phoenix frames, private join, presence shapes). The documented protocol
   was read today, but not run. Default: if 8e step 4 fails, ship phase 5 on the poll alone, without presence and
   soft locks, and fix forward.
3. **A removed member keeps hearing broadcasts until their token is replaced** (up to 1 hour). [Certain] Default:
   accept; the app leaves on the `meta` event and every function refuses them at once.
4. **The server does not hash file content.** Default: phones verify every download; per-owner folders contain the
   damage. An Edge Function that hashes on `finish_upload` can be added later without changing the app.
5. **Big uploads.** [Certain] Free caps a file at 50 MB and Supabase advises resumable uploads over 6 MB.
   Default: plain upload with a retry; the app refuses a take over the bucket limit with "This take is too long to
   save online for now"; raise the limit on Pro (decision 4); add resumable uploads only if 8e step 6 fails.
6. **Song-length live takes have no recorder yet** (mic stops at 30 s, kept in memory). Decision 3 allows them; the
   server and format do. Default: not in these phases. Ask Tyler whether the long-take recorder is part of phase 3
   or its own piece. [Guessing] a first version should stop at 10 minutes.
7. **A Vault submission still waiting when its maker deletes their account.** Decision 5 covers accepted sounds.
   Default: a waiting one is withdrawn and deleted, and the grant text says so. Tyler may prefer "kept for review".
8. **Two phones of one account now merge instead of keeping both** (the table at the top). Default: merge, with
   the long-gap copy. Tell Tyler; it differs from plan s2.
9. **Slot 64 and mutes are personal in a shared mess** (3.3). Default as written. A scratch synth reaches others
   only when it is put on a pad.
10. **Rebuild cost.** Each release copies the mess through JSON ([Guessing] 1 to 5 ms on a phone for a big mess).
    Default: ship the rebuild; add the fast path only if the frame-time numbers in the bug notes show it.
11. **Storage abuse.** Up to 20 pending uploads of 50 MB can sit for a day before the sweep; versions can reach
    8 MB a mess. Default: accept at this scale; watch storage in the dashboard.
12. **The test stand-in is not Supabase**, and Postgres here is 16 (pglite says 19) against Supabase's own version.
    Default: migrations use only Postgres 15 features; 8e is the real check; CI may later run `supabase start`,
    where Docker can pull.
13. **Name filter.** [Guessing] a short blocked-word list satisfies Apple 1.2 for names; audio is report-only.
14. **Presence limits.** [Certain] 20 presence messages a second for the whole Free project. Default: one update
    per phone per 2 s at most; Pro before Play (decision 4).
15. **Email settings are manual.** The code length must be set to 6 and the template must use `{{ .Token }}`
    ([Certain] for the template). The app accepts 6 to 10 digits in case it is not.
16. **`bn:` key clash between phones.** [Guessing] about never. Default: the pad reads Missing; no re-keying code.
17. **OkHttp's size.** [Guessing] 300 to 500 KB after R8. Default: accept.
18. **Verified links need the signing key's fingerprint**, and Play will sign with its own key later. Default: the
    `messmachine://` button on the landing page works from day one; add Play's fingerprint when it exists.
