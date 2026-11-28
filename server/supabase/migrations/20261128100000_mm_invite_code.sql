-- Nov 28 (Tyler): an invite also has a short code to type, so joining from another screen does not need a link
-- on the clipboard. 10 letters and digits with no look-alikes (no I, L, O, 0, 1): about 8e14 codes, single use,
-- 7 days, like the link. Only its sha256 is kept. Either the link's token or the code finds the invite.

alter table public.invites add column if not exists code_hash text unique
  check (code_hash is null or code_hash ~ '^[0-9a-f]{64}$');

create or replace function mm_private.rand_invite_code() returns text
language sql volatile set search_path = '' as $$
  select string_agg(substr('ABCDEFGHJKMNPQRSTUVWXYZ23456789', 1 + (get_byte(x.b, g.i) % 31), 1), '' order by g.i)
  from (select sha256(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid())) as b) x,
       generate_series(0, 9) as g(i)
$$;

-- what someone typed or pasted, as a code: letters and digits only, upper case (dashes and spaces dropped)
create or replace function mm_private.invite_code_norm(p text) returns text
language sql immutable set search_path = '' as $$
  select case when length(c) = 10 then c else '' end
  from (select upper(regexp_replace(coalesce(p, ''), '[^A-Za-z0-9]', '', 'g')) as c) x
$$;

create or replace function mm_private.live_invite(p_token text) returns public.invites
language sql stable set search_path = '' as $$
  select i.* from public.invites i join public.messes m on m.id = i.mess_id
  where p_token is not null and (i.token_hash = mm_private.sha_hex(p_token) or i.code_hash = mm_private.sha_hex(mm_private.invite_code_norm(p_token)))
    and not i.revoked and i.expires_at > now() and i.uses_left > 0 and not m.blocked
$$;

create or replace function public.make_invite(p_mess uuid, p_days int default 7) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_me uuid := mm_private.caller();
  v_role text;
  v_m public.messes;
  v_token text := mm_private.rand_token();
  v_code text := mm_private.rand_invite_code();
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
  insert into public.invites (mess_id, token_hash, code_hash, expires_at, uses_left, made_by)
    values (p_mess, mm_private.sha_hex(v_token), mm_private.sha_hex(v_code), now() + make_interval(days => p_days), 1, v_me)
    returning * into v_inv;
  return jsonb_build_object('id', v_inv.id, 'token', v_token, 'code', v_code, 'expires_at', mm_private.iso(v_inv.expires_at));
end $$;

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
    where p_token is not null and (i.token_hash = mm_private.sha_hex(p_token) or i.code_hash = mm_private.sha_hex(mm_private.invite_code_norm(p_token)));
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
