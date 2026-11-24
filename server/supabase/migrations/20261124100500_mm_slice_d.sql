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
