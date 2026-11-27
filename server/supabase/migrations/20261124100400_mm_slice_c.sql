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
