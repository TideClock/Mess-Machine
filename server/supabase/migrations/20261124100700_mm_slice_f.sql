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
