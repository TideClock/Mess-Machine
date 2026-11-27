-- A stand-in for Supabase on plain Postgres, so the real migrations load unchanged and can be tested
-- with no Supabase at all. Run by a superuser on a fresh database, before the migrations.
-- It copies only what the migrations and policies touch:
--   roles anon, authenticated, service_role and a migrator that is NOT a superuser (like Supabase's "postgres")
--   auth.users, auth.uid(), auth.jwt(), auth.role()   (the function bodies are Supabase's own)
--   storage.buckets, storage.objects (row level security on), storage.foldername()
--   realtime.messages (row level security on), realtime.topic(), realtime.send()
--   Supabase's default grants in "public" (everything open to the client roles), which migration 1 must undo

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then create role service_role nologin bypassrls; end if;
  if not exists (select 1 from pg_roles where rolname = 'mm_stub_migrator') then
    create role mm_stub_migrator nologin nosuperuser createrole bypassrls;
  end if;
  -- stands in for supabase_auth_admin, supabase_storage_admin and supabase_realtime_admin
  if not exists (select 1 from pg_roles where rolname = 'mm_stub_platform') then create role mm_stub_platform nologin; end if;
end $$;

-- On Supabase the "postgres" role may write policies on storage.objects and realtime.messages without
-- owning them (a Supabase extension allows it). Plain Postgres has no such switch, so here the migrator
-- is made a member of the role that owns them.
grant mm_stub_platform to mm_stub_migrator;

-- public, as Supabase sets it up: owned by the migrating role, open to the client roles by default
alter schema public owner to mm_stub_migrator;
grant usage on schema public to anon, authenticated, service_role;
alter default privileges for role mm_stub_migrator in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges for role mm_stub_migrator in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges for role mm_stub_migrator in schema public grant all on sequences to anon, authenticated, service_role;
do $$ begin execute format('grant create on database %I to mm_stub_migrator', current_database()); end $$;

-- auth
create schema auth authorization mm_stub_platform;
create table auth.users (
  id uuid primary key default gen_random_uuid(),
  email text unique,
  encrypted_password text,
  email_confirmed_at timestamptz,
  created_at timestamptz not null default now()
);
alter table auth.users owner to mm_stub_platform;

create function auth.uid() returns uuid language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  )::uuid
$$;
create function auth.role() returns text language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.role', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role')
  )::text
$$;
create function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim', true), ''),
    nullif(current_setting('request.jwt.claims', true), '')
  )::jsonb
$$;
grant usage on schema auth to anon, authenticated, service_role, mm_stub_migrator;
grant execute on function auth.uid(), auth.role(), auth.jwt() to public;
grant all on auth.users to mm_stub_migrator;

-- storage
create schema storage authorization mm_stub_platform;
create table storage.buckets (
  id text primary key,
  name text not null unique,
  owner uuid,
  public boolean default false,
  file_size_limit bigint,
  allowed_mime_types text[],
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text references storage.buckets(id),
  name text,
  owner uuid,
  owner_id text,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  last_accessed_at timestamptz default now(),
  metadata jsonb,
  path_tokens text[] generated always as (string_to_array(name, '/')) stored,
  version text,
  user_metadata jsonb,
  unique (bucket_id, name)
);
alter table storage.buckets owner to mm_stub_platform;
alter table storage.objects owner to mm_stub_platform;
alter table storage.buckets enable row level security;
alter table storage.objects enable row level security;
create function storage.foldername(name text) returns text[] language sql immutable as $$
  select (string_to_array(name, '/'))[1 : array_length(string_to_array(name, '/'), 1) - 1]
$$;
grant usage on schema storage to anon, authenticated, service_role, mm_stub_migrator;
grant all on storage.buckets, storage.objects to anon, authenticated, service_role, mm_stub_migrator;
grant execute on function storage.foldername(text) to public;

-- realtime
create schema realtime authorization mm_stub_platform;
create table realtime.messages (
  id uuid not null default gen_random_uuid(),
  topic text not null,
  extension text not null,
  payload jsonb,
  event text,
  private boolean default false,
  inserted_at timestamp not null default now(),
  updated_at timestamp not null default now()
);
alter table realtime.messages owner to mm_stub_platform;
alter table realtime.messages enable row level security;
create function realtime.topic() returns text language sql stable as $$
  select nullif(current_setting('realtime.topic', true), '')::text
$$;
-- Supabase's own realtime.send, plus one test switch: with the setting mm_stub.send_fails = 'on' it
-- throws, so a test can prove that a failed broadcast never undoes stored work.
create function realtime.send(payload jsonb, event text, topic text, private boolean default true) returns void
language plpgsql as $$
begin
  if current_setting('mm_stub.send_fails', true) = 'on' then
    raise exception 'stub: realtime.send was told to fail';
  end if;
  execute format('set local realtime.topic to %L', topic);
  insert into realtime.messages (payload, event, topic, private, extension)
    values (payload, event, topic, private, 'broadcast');
end $$;
grant usage on schema realtime to anon, authenticated, service_role, mm_stub_migrator;
grant select, insert, update, delete on realtime.messages to anon, authenticated, service_role, mm_stub_migrator;
grant execute on function realtime.topic(), realtime.send(jsonb, text, text, boolean) to public;
