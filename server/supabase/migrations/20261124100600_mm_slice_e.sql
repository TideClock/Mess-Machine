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
