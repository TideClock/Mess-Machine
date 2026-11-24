# Accounts: the frozen interfaces (Nov 24 2026)
The contract every builder codes against. It is `docs/ACCOUNTS-SPEC.md` after `docs/reviews/ACCOUNTS-DECISIONS.md` and both reviews are applied. Where this file and the
spec differ, this file wins. A change here needs a line in this file and a word to the other builders. Lines marked **decided here** filled a real gap. Slices A to F are
the rulings' slices. Tags: [Certain] read on Supabase's pages today, [Likely] not checked today.

## 1. Server calls
### 1.1 Rules for every call
- RPC: `POST <url>/rest/v1/rpc/<name>`, body a JSON object of the named arguments, answer is the JSON value. Edge Function: `POST <url>/functions/v1/<name>`, JSON body,
  JSON answer.
- Headers on every request: `apikey: <publishable key>`. When signed in also `Authorization: Bearer <access token>`. The publishable key never goes in `Authorization`
  [Certain]. JSON bodies: `Content-Type: application/json`.
- **The error envelope (RPC).** Functions fail with `raise exception using errcode = 'P0001', message = '<code>'`. The client sees HTTP 400 and
  `{"code":"P0001","message":"<code>","details":null,"hint":null}` [Likely] and reads `message` only when `code` is `P0001`. HTTP 401 (dead or missing token; the client
  refreshes once and retries), and HTTP 403 or code `42501`, become `auth`. No answer is `net`. Anything else is `http`.
- **The error envelope (Edge Functions), decided here:** HTTP 400 (401 for `auth`) and `{"error":"<code>"}`. A non-2xx answer without a string `error` is `auth` on 401,
  else `http`. Every function sends CORS headers and trusts only `auth.getUser(token)` for who is calling.
- Every function is in `public`, `security definer`, `set search_path = ''`, returns `jsonb`. Its first statement is `u := mm_private.caller()`, which raises `auth` when
  the uid is null, has no profile, `profiles.banned` is set, or the token's `amr` holds method `password` and the uid is not `config.review_user`. Calls marked **anon**
  skip this. Calls marked **T** then raise `terms` unless `profiles.terms_version = config.terms_version`.
- Checks run in the order written; the first that fails wins. Before any count is read, the function locks the row the count hangs on (`messes` row for members, invites,
  live seats, revs; the caller's `profiles` row for messes owned, sound bytes, pending uploads, submissions).
- The Errors column leaves out `auth` (every call not marked anon) and `terms` (every call marked T).
- Who: **anon** (no session), **signed in**, **member** (role `owner` or `editor`; there is no viewer role), **owner**, **admin** (a row in `admins`). `not_found` means
  no such thing or you are not on it. `forbidden` means on it but not the owner (or not an admin). A taken-down mess reads `not_found` to everyone but its owner.
- Types: `uuid` and `timestamptz` travel as strings (ISO 8601 UTC), `bigint` as a JSON number.
- `config` rows: `terms_version "2026-11"`, `sound_limit 262144000`, `mess_limit 200`, `member_limit 8`, `live_limit 4`, `mess_bytes 1048576`, `sound_seconds 100`,
  `sound_bytes 20971520`, `docs_limit 31457280`, `ops_v_max 1`, `ops_day_bytes 20971520`, `review_user null`. No `take_seconds`.

All codes: `auth terms not_found forbidden blocked bad_input bad_name too_big rate_limited terms_outdated too_many_messes docs_full conflict too_long too_many_pending
quota not_uploaded gap snapshot_first day_limit mess_full_live client_too_old ahead stale too_many_invites invite_invalid mess_full owner_cannot_leave no_copy stale_login
not_ready grant_outdated already_submitted already_reviewed not_copied`. Client-made codes: `net http code_wrong code_wait bad_hash unbuilt`. The names `conflict`, `docs_full`, `day_limit` and the header `x-mm-sweep` are **decided here**.

### 1.2 Sign-in endpoints (Supabase Auth; all [Likely] on field names, checked by the CI job against `supabase start`)
| Need | Request | Answer | Errors the client maps |
|---|---|---|---|
| Ask for a code | `POST /auth/v1/otp` `{"email": e, "create_user": true}` | 200 `{}` | 429: `code_wait` (one code per 60 s [Certain]) |
| Check a code | `POST /auth/v1/verify` `{"type": "email", "email": e, "token": code}` | 200 SessionJson | any 4xx but 429: `code_wrong` (`error_code` `otp_expired` covers wrong and expired); 429: `code_wait` |
| Refresh | `POST /auth/v1/token?grant_type=refresh_token` `{"refresh_token": r}` | 200 SessionJson | 400, 401, 403: `auth` (sign out and say so); no answer: `net` (keep the session, try again) |
| Sign out | `POST /auth/v1/logout?scope=local` with the bearer token, no body | 204 | ignored; the phone forgets the session either way |
| Review account (Slice E) | `POST /auth/v1/token?grant_type=password` `{"email", "password"}` | 200 SessionJson | 400: `code_wrong` |

SessionJson: `{access_token: text, token_type: "bearer", expires_in: int (seconds), expires_at: int (epoch seconds), refresh_token: text, user: {id: uuid, email: text}}`.
Auth errors look like `{"code": 400, "error_code": "otp_expired", "msg": "..."}`. Refresh tokens are single use: the new session is saved before anything else happens.
Project settings: code length 6, code life 600 s, "Confirm email" on, mail template uses `{{ .Token }}` [Certain]. The app accepts 6 to 10 digits.

### 1.3 JSON shapes used below
| Name | Fields |
|---|---|
| Me | `{id: uuid, display_name: text, name_set: bool, colour: int 0 to 7, sound_bytes: bigint, sound_limit: bigint, messes: int, mess_limit: int, terms_ok: bool, terms_version: text, is_admin: bool}`. `terms_version` is `config.terms_version`, the one to accept. |
| MessRow | `{id: uuid, name: text, role: text, owner_name: text, rev: bigint, updated_at: text, members: int, share_mode: text, doc_bytes: int, blocked: bool}` |
| Opened | `{id, name, role, owner_id: uuid, rev: bigint, doc_rev: bigint, doc: object, ops_v: int, ops: [Batch], sounds: [Sound], members: [Member], share_mode: text, share_token: text or null, topic: text or null}`. `ops` is every batch after `doc_rev` (always `[]` before Slice B). `share_token` is null unless you are the owner. `topic` is null before Slice C. |
| Batch | `{rev: bigint, user: uuid or null, batch: uuid, v: int, ops: [op] or null, at: text}` |
| Member | `{id: uuid, name: text, colour: int, role: text}` |
| Sound | `{key: text, sha256: text, src: "user" or "vault" or "gone", owner: uuid or null, fmt: "wav", bytes: bigint, seconds: real, rate: int, kind: "imp" or "mic" or "rs" or "take", title: text, bpm: real, beats: int, mkey: text, state: "pending" or "ready"}` |

`colour` is made by the server from the user id (`get_byte(uuid_send(id), 15) % 8`); nobody picks one. **decided here** (the rulings only say "from the user id").
`name_set` is false until `set_profile` has run once, so the app can ask for a name at the first share or join on any phone. **decided here**.

### 1.4 Slice A: my messes online (14 RPC, 1 Edge Function)
| Call | Checks and effect | Returns | Errors | Who |
|---|---|---|---|---|
| `me()` | | Me | | signed in |
| `set_profile(p_display_name text)` | trimmed, 1 to 24 chars, passes `mm_private.clean_name`. Sets `name_set`. | Me | `bad_input`, `bad_name` | signed in |
| `accept_terms(p_version text)` | equals `config.terms_version` | `{terms_ok: true}` | `terms_outdated` | signed in |
| `create_mess(p_id uuid, p_name text, p_doc jsonb)` T | lock my profile; `p_id` exists and is mine: return it (safe retry); exists and not mine, or doc not an object: `bad_input`; name cleaned (1 to 48); doc at most 1 MB; owned under 200; my docs plus versions under `docs_limit`. Makes the mess and my owner row. Makes no sound rows. | `{id: uuid, rev: bigint}` (rev 0 when new) | `bad_input`, `bad_name`, `too_big`, `too_many_messes`, `docs_full` | signed in |
| `my_messes()` | | `[MessRow]`, newest first | | signed in |
| `open_mess(p_mess uuid)` | | Opened | `not_found` | member |
| `put_doc(p_mess uuid, p_doc jsonb, p_base_rev bigint, p_label text default '')` T | lock the mess; member; not blocked; doc an object, label at most 40; doc at most 1 MB; at most 12 a minute per person per mess (**decided here**); `p_base_rev = rev` else `conflict`; owner's `docs_limit`. Sets `doc`, `rev = rev + 1`, `doc_rev = rev`. Never writes `name` or sound rows. Makes a version when a label is given or the newest is over 10 minutes old; keeps 30 unlabelled, 20 labelled, 2 MB a mess. From Slice B it also stores and sends the batch `[{"t":"doc.reset"}]` at the new rev (**decided here**). | `{rev: bigint, version_id: uuid or null}` | `not_found`, `blocked`, `bad_input`, `too_big`, `rate_limited`, `conflict`, `docs_full` | member |
| `rename_mess(p_mess uuid, p_name text)` T | owner; name cleaned. Slice A: sets `name`, rev stays. From Slice B: appends the batch `[{"t":"mess.set","f":"name","v":name}]` through `apply_ops`'s code. | `{rev: bigint}` | `not_found`, `forbidden`, `bad_name` | owner |
| `delete_mess(p_mess uuid)` | owner. From Slice C sends `meta {kind:"deleted"}` first. | `{ok: true}` | `not_found`, `forbidden` | owner |
| `register_sound(p_sha256 text, p_bytes bigint, p_seconds real, p_rate int, p_kind text, p_local_key text, p_title text, p_bpm real, p_beats int, p_mkey text)` T | lock my profile; sha is 64 lower hex, rate 8000 to 96000, kind known, else `bad_input`; a `ready` row of mine with this sha: answer `ready`; `p_seconds <= 100` else `too_long`; `p_bytes <= 20 MB` else `too_big`; under 20 pending else `too_many_pending`; ready plus pending plus `p_bytes` within 250 MB else `quota`. Upserts a `pending` row. | `{state: "ready" or "upload", path: text, used: bigint, limit: bigint}`; `path` is `<my uuid>/<sha256>.wav` | `bad_input`, `too_long`, `too_big`, `too_many_pending`, `quota` | signed in |
| `finish_upload(p_sha256 text)` | lock my profile; my pending row else `not_found` (a `ready` row answers ready again); the object `sounds/<path>` exists else `not_uploaded`; its real size equals the registered `bytes`, else the row is deleted and `too_big`; total within 250 MB else row deleted and `quota`. Sets `ready`, adds to `sound_bytes`. From Slice C sends `meta {kind:"sound", sha256}` to each mess that lists it. | `{state: "ready", used: bigint}` | `not_found`, `not_uploaded`, `too_big`, `quota` | signed in |
| `attach_sound(p_mess uuid, p_key text, p_owner uuid, p_sha256 text)` T | member; not blocked; `p_key` starts `bn:`, 1 to 80 chars, else `bad_input`; the file row `(p_owner, p_sha256)` exists (pending is fine) and is mine, or is already listed by a mess I am on, else `not_found` (its maker left: the pad reads Missing). Upserts `mess_sounds` with `added_rev = rev`. Rows stay for the life of the mess. | `{ok: true}` | `not_found`, `blocked`, `bad_input` | member |
| `my_sounds()` | | `[{sha256: text, title: text, kind: text, bytes: bigint, seconds: real, state: text, messes: int, vault: text or null}]`, biggest first | | signed in |
| `delete_sound(p_sha256 text)` | Called twice (**decided here**). First: my `ready` or `pending` row becomes `deleting` (still counted); other rows in `mess_sounds` for it become `vault` if `vault_sounds` has the sha, else `gone`; a pending Vault submission is withdrawn. The app then deletes the object (s2) and calls again: while the object is still there the answer stays `deleting`; once it is gone the row goes, `sound_bytes` drops and the answer is `deleted`. The sweep finishes any left over. | `{state: "deleting" or "deleted", path: text, used: bigint, messes: int}` | `not_found` | signed in |
| Edge Function `sound-urls` | Body is exactly one of `{"mess": uuid}` (member), `{"token": text}` (anon, a live share link, Slice D, counted per token per day), `{"report": uuid}` (admin, Slice E), `{"submission": uuid}` (admin, Slice F). Lists rows that are `ready` with `src` `user` or `vault`; leaves out files of a banned owner and blocked sounds. | `{sounds: [{key: text, sha256: text, bytes: bigint, url: text}], expires_in: 600}`; `url` is absolute and good for 10 minutes | `auth`, `not_found`, `bad_input`, `rate_limited` | as listed |

The Slice A app, on an Opened with `doc_rev < rev` (a newer app is editing it by ops), says "Update to open this mess" and leaves it alone. **decided here**. Duplicate is
`create_mess` with a new id plus `attach_sound` per sound. `my_sounds` and `delete_sound` are built on the server and in `CloudApi` now; their controls are deferred.

### 1.5 Slice B: two people in one mess (16 RPC; `open_mess`, `rename_mess`, `put_doc` change as noted above)
| Call | Checks and effect | Returns | Errors | Who |
|---|---|---|---|---|
| `ops_since(p_mess uuid, p_from bigint)` | member; batch `p_from + 1` trimmed away: `gap`. Refreshes my `mess_live.seen_at` if I hold a seat. | `{rev: bigint, doc_rev: bigint, members: int, topic: text or null, ops: [Batch]}`, at most 500 batches and 1 MB, oldest first | `not_found`, `gap` | member |
| `apply_ops(p_mess uuid, p_batch uuid, p_ops jsonb, p_v int)` T | 1. `p_batch` not null, `p_ops` an array of 1 to 500, `p_v` between 1 and `config.ops_v_max`, else `bad_input`. 2. `octet_length(p_ops::text) <= 262144` else `too_big`. 3. Lock the mess row; `ts := clock_timestamp()`; member else `not_found`; blocked: `blocked`. 4. This batch id already stored: mine, return its first answer; someone else's, `bad_input`. 5. My batches on this mess in the last second under 4 else `rate_limited`. 6. My op bytes today within `ops_day_bytes` else `day_limit`. 7. Live seat: I hold one, or under 4 others seen in 90 s, else `mess_full_live`; upsert my seat. 8. If any op is `mess.set` with `f` `name`: every such `v` is a JSON string else `bad_input`; I am the owner else `forbidden`; the last one passes `clean_name` else `bad_name`; it becomes `messes.name`. 9. `rev - doc_rev >= 2000` or `ops_bytes > 2097152`: `snapshot_first` (save a snapshot, send the same batch id again). 10. `rev = rev + 1`, `ops_v = greatest(ops_v, p_v)`, `ops_bytes` grows, the row is stored at `ts`. From Slice C the Batch is broadcast, best effort, in its own exception block. | `{rev: bigint, at: text}` | `bad_input`, `too_big`, `not_found`, `blocked`, `rate_limited`, `day_limit`, `mess_full_live`, `forbidden`, `bad_name`, `snapshot_first` | member |
| `save_snapshot(p_mess uuid, p_doc jsonb, p_doc_rev bigint, p_v int, p_label text default '')` T | lock the mess; member; `p_v` between 1 and `ops_v_max` else `bad_input`; `p_v >= ops_v` else `client_too_old`; `p_doc_rev <= rev` else `ahead`; `p_doc_rev > doc_rev`, or equal with a label, else `stale`; 1 MB; `docs_limit`; at most 6 labelled a minute per person. Sets `doc`, `doc_rev`; resets `ops_bytes`; deletes ops below `doc_rev - 500` at once. Never writes `name`. Never drops sound rows. Version rule as `put_doc`. | `{doc_rev: bigint, version_id: uuid or null}` | `not_found`, `blocked`, `bad_input`, `client_too_old`, `ahead`, `stale`, `too_big`, `docs_full`, `rate_limited` | member |
| `members(p_mess uuid)` | | `[Member]`, owner first | `not_found` | member |
| `make_invite(p_mess uuid, p_days int default 7)` T | lock the mess; owner; not blocked; days 1 to 30; under 20 live invites. Single use, always editor. The token is 24 random bytes, base64url (32 chars); only `sha256(token)` is stored. | `{id: uuid, token: text, expires_at: text}`; the token is shown once | `not_found`, `forbidden`, `blocked`, `bad_input`, `too_many_invites` | owner |
| `list_invites(p_mess uuid)` | | `[{id: uuid, expires_at: text, uses_left: int, revoked: bool}]` | `not_found`, `forbidden` | owner |
| `revoke_invite(p_invite uuid)` | owner of its mess | `{ok: true}` | `not_found` | owner |
| `invite_info(p_token text)` anon | live: exists, not revoked, not expired, unused, mess not blocked; and, when signed in, I have not blocked the owner | `{mess_name: text, owner_name: text}` | `invite_invalid` | anon |
| `redeem_invite(p_token text)` T | live as above else `invite_invalid`; the owner blocked me: `blocked`; already a member: use nothing, return; lock the mess; under 8 members else `mess_full`; `update invites set uses_left = uses_left - 1 where id = x and uses_left > 0 returning` else `invite_invalid`. From Slice C sends `meta {kind:"members"}`. | `{mess_id: uuid, role: "editor"}` | `invite_invalid`, `blocked`, `mess_full` | signed in |
| `remove_member(p_mess uuid, p_user uuid, p_block boolean default false)` | owner; target is a member (`not_found`) and not the owner (`bad_input`). Removes them, frees their live seat, rotates the channel key. With `p_block` also does `block_user`. Sends `meta {kind:"members", gone: p_user}` before the key turns. | `{ok: true}` | `not_found`, `forbidden`, `bad_input` | owner |
| `leave_mess(p_mess uuid)` | member; not the owner | `{ok: true}` | `not_found`, `owner_cannot_leave` | member |
| `block_user(p_user uuid)` | not me, a real profile. Adds the `user_blocks` row; removes them from every mess I own (keys rotate); takes me out of every mess they own; their invites read `invite_invalid` to me and mine answer `blocked` to them. | `{ok: true}` | `bad_input`, `not_found` | signed in |
| `my_blocks()` | | `[{id: uuid, name: text}]` | | signed in |
| `unblock_user(p_user uuid)` | | `{ok: true}` | | signed in |
| `join_live(p_mess uuid)` T | lock the mess; member; I hold a seat, or under 4 others seen in 90 s: upsert my seat | `{live: bool, editors: int}`; `live` false means watch only | `not_found` | member |
| `leave_live(p_mess uuid)` | | `{ok: true}` | | signed in |

Two reviews gave two caps and two rates. **decided here**: the batch cap is Review B's 256 KB and 500 ops (one `loop.put` is about 48 KB, so Review A's 16 KB cannot hold
it); Review A's storage bound is kept by `snapshot_first` and `day_limit`. The rate is 4 batches a second with the client's `SEND_MS` at 400 (Review A's cost, Review B's
headroom). `day_limit` is the code for the 20 MB a day of ops. Cut: `transfer_mess`, `set_role`, `p_role`, `p_uses`, `p_email`, `email_mismatch`, the viewer role.

### 1.6 Slice C: live feel (no new call)
`open_mess` and `ops_since` return `topic`. `apply_ops`, `put_doc`, `restore_version` broadcast `ops`; the calls that say so above send `meta`. Everything else is in s2.

### 1.7 Slice D: share links and History (6 RPC, plus `sound-urls` with `{"token"}`)
| Call | Checks and effect | Returns | Errors | Who |
|---|---|---|---|---|
| `set_share(p_mess uuid, p_mode text, p_new boolean default false)` T | owner; not blocked; mode `off`, `view` or `copy`. `off` clears the token. Turning on, or `p_new`, makes a fresh token (24 random bytes, base64url, 32 chars); the old link dies. | `{share_mode: text, share_token: text or null}` | `not_found`, `forbidden`, `blocked`, `bad_input` | owner |
| `open_shared(p_token text)` anon | token matches, mode not `off`, mess not blocked, owner not banned | `{name: text, owner_name: text, mode: text, rev: bigint, doc_rev: bigint, doc: object, ops: [Batch], sounds: [Sound]}` with every `user` and `owner` null | `not_found` | anon |
| `fork_mess(p_id uuid, p_from uuid, p_token text, p_name text, p_doc jsonb)` T | read right on the source: member of `p_from`, or `p_token` opens it with mode `copy` (`view`: `no_copy`); then as `create_mess` with `forked_from` set; copies the source's `mess_sounds` rows. The app sends the doc because the server never applies ops. | `{id: uuid, rev: bigint}` | `not_found`, `no_copy`, `bad_input`, `bad_name`, `too_big`, `too_many_messes`, `docs_full` | signed in |
| `versions(p_mess uuid)` | | `[{id: uuid, rev: bigint, label: text, by_name: text, at: text, bytes: int}]`, newest first | `not_found` | member |
| `get_version(p_version uuid)` | member of its mess | `{id: uuid, rev: bigint, label: text, doc: object}` | `not_found` | member |
| `restore_version(p_mess uuid, p_version uuid)` T | lock the mess; member; not blocked; the version belongs to `p_mess` else `not_found`; `doc_rev = rev` else `snapshot_first`. Keeps the current doc as a version `Before restore`, sets `doc`, bumps `rev` and `doc_rev` together, stores and sends `[{"t":"doc.reset"}]`. | `{rev: bigint}` | `not_found`, `blocked`, `snapshot_first` | member |

### 1.8 Slice E: store requirements (7 RPC, 2 Edge Functions, the password sign-in of 1.2)
| Call | Checks and effect | Returns | Errors | Who |
|---|---|---|---|---|
| `report_mess(p_mess uuid, p_token text, p_reason text, p_details text default '', p_user uuid default null)` anon | reason one of `sexual hate harassment copyright spam other`, details at most 500, else `bad_input`. Signed-in member of `p_mess`: without `p_user` I must not be its owner; with `p_user` it must be another member (stored as `about_user`); 5 a day. Or `p_token` opens a shared mess (signed in or not): `p_user` must be null; 20 a day per mess. | `{id: uuid}` | `not_found`, `rate_limited`, `bad_input` | anon |
| Edge Function `delete-account` | Body `{}`. `stale_login` unless the newest `amr` entry is under 10 minutes old. Writes `mm_private.deletions`, deletes the data in one transaction (owned messes go, no hand-over; pending Vault submissions are withdrawn and deleted), removes `sounds/<user>/`, deletes the auth user. The sweep finishes a deletion that stopped half way. | `{ok: true}` | `auth`, `stale_login` | signed in |
| Edge Function `sweep` | Header `x-mm-sweep: <secret>`, run daily. Removes pending rows over 24 hours old, `deleting` rows, orphan objects; finishes `deletions` rows with no `done_at`. Never called by an app. | `{removed: int, finished: int}` | `auth` | the schedule |
| `admin_reports(p_status text default 'open')` | | `[{id: uuid, mess_id: uuid or null, mess_name: text, owner_id: uuid or null, about_user: uuid or null, reason: text, details: text, via_token: bool, status: text, at: text}]`, oldest first | `forbidden` | admin |
| `admin_open_report(p_id uuid)` | the report is open | `{report: (a row as above), doc: object or null, sounds: [Sound]}`; the files come from `sound-urls {"report"}` | `forbidden`, `not_found` | admin |
| `admin_resolve_report(p_id uuid, p_action text, p_note text default '')` | action `dismiss` or `block_mess` (as `admin_block_mess(mess, true)`) | `{status: "actioned" or "dismissed"}` | `forbidden`, `not_found`, `bad_input` | admin |
| `admin_block_mess(p_mess uuid, p_blocked boolean)` | sets `blocked`; on true clears the share link, rotates the channel key, sends `meta {kind:"blocked"}` | `{blocked: bool}` | `forbidden`, `not_found` | admin |
| `admin_ban_user(p_user uuid, p_banned boolean)` | sets `profiles.banned`; on true blocks their messes and clears their share links. The review user is never an admin. | `{banned: bool}` | `forbidden`, `not_found` | admin |
| `admin_block_sound(p_owner uuid, p_sha256 text)` | marks the file blocked; every `mess_sounds` row with this sha256, any owner, becomes `gone` | `{messes: int}` | `forbidden`, `not_found` | admin |

Admins read a private sound only through `sound-urls` with an open report or a pending submission.

### 1.9 Slice F: the Vault (6 RPC, plus `sound-urls` with `{"submission"}`)
| Call | Checks and effect | Returns | Errors | Who |
|---|---|---|---|---|
| `vault_grant()` anon | | the newest grant: `{version: text, body: text, body_sha256: text}` | | anon |
| `vault_submit(p_sha256 text, p_credit_name text, p_grant_version text, p_note text default '')` T | lock my profile; my file and `ready` else `not_ready`; grant is the newest else `grant_outdated`; credit name 1 to 40 and clean else `bad_name`; no pending or approved row for this sha else `already_submitted`; under 10 pending else `too_many_pending` | `{id: uuid, status: "pending"}` | `not_ready`, `grant_outdated`, `bad_name`, `already_submitted`, `too_many_pending`, `bad_input` | signed in |
| `vault_mine()` | | `[{id: uuid, sha256: text, title: text, credit_name: text, status: text, reviewer_note: text, submitted_at: text, reviewed_at: text or null}]` | | signed in |
| `vault_withdraw(p_id uuid)` | mine and pending | `{status: "withdrawn"}` | `not_found` | signed in |
| `vault_queue(p_status text default 'pending')` | | `[{id: uuid, submitter_id: uuid or null, submitter_name: text, sha256: text, title: text, credit_name: text, note: text, grant_version: text, bytes: bigint, seconds: real, rate: int, kind: text, bpm: real, beats: int, mkey: text, submitted_at: text, others: [{owner: uuid, at: text}]}]`, oldest first; `others` are other owners of the same sha | `forbidden` | admin |
| `vault_review(p_id uuid, p_decision text, p_note text default '')` | pending else `already_reviewed`; decision `approved` or `declined`. For `approved` the object `vault/<sha256>.wav` exists with size equal to the submission's `bytes`, else `not_copied`; the grant is frozen into `vault_sounds` (`grant_version`, `body_sha256`, `granted_at`, granter uuid). | `{status: text}` | `forbidden`, `not_found`, `already_reviewed`, `not_copied`, `bad_input` | admin |

`vault_mine`, `vault_withdraw` and every admin call are server and web only for now; they are not in `CloudApi`.

## 2. Storage and realtime
| Bucket | Public | File limit | Types | Path | Slice |
|---|---|---|---|---|---|
| `sounds` | no | 20971520 (**decided here**: the 20 MB cap is then also real at upload time) | `audio/wav`, `audio/flac` | `<owner uuid>/<sha256>.wav` | A |
| `vault` | no | 20971520 | `audio/wav`, `audio/flac` | `<sha256>.wav` | F |

- Policies on `storage.objects`: insert into `sounds` only for a file the caller registered and has not finished (`mm_policy.may_upload(name)`); delete in `sounds` only
  under the caller's own folder; insert and delete in `vault` for admins. **There is no select policy and no update policy**: nobody reads a file with their own token and
  nobody overwrites one. All reading goes through `sound-urls`.
- Upload: `register_sound` gives `path`. `POST <url>/storage/v1/object/sounds/<path>` with `apikey`, the bearer token, `Content-Type: audio/wav`, `x-upsert: false`, the
  file as the body with a fixed length. 200 `{"Key": ...}`. An "already exists" answer (HTTP 400 or 409 with `"error": "Duplicate"`) counts as done [Likely]. Then
  `finish_upload`. No answer is `net`: send the same file again.
- Download: `sound-urls` gives `url`. `GET <url>` with no headers, streamed to a file. The reader stops after the listed `bytes`, checks sha256, and on a mismatch deletes
  the file and answers `bad_hash`. Every downloaded sound is hostile: the WAV reader stops at `seconds x rate` samples from the Sound row.
- Remove my object: `DELETE <url>/storage/v1/object/sounds/<path>` with the bearer token (the middle step of `delete_sound`). 200 or 404 both count as gone.

**Realtime (Slice C)**
- Socket: `wss://<project host>/realtime/v1/websocket?apikey=<publishable key>&vsn=1.0.0` [Certain]. Frames are JSON objects `{topic, event, payload, ref, join_ref}`.
  Heartbeat `{topic: "phoenix", event: "heartbeat", payload: {}}` at least every 25 s [Certain]. Reconnect after 1, 2, 5, 10 s. With no socket the poll carries
  everything.
- Topic: `mess:<mess uuid>:<channel_key>`. `channel_key` is 16 lower hex chars in `messes.channel_key`. It is rotated by `remove_member`, `block_user`, `admin_block_mess`
  and `admin_ban_user`. The client never builds a topic: it uses `topic` from `open_mess`, and when `ops_since` returns a different one it leaves and joins again. On the
  wire the Phoenix topic is `realtime:` plus the topic.
- Join: event `phx_join`, payload `{"config": {"broadcast": {"ack": false, "self": false}, "presence": {"enabled": true, "key": "<my user id>"}, "private": true},
  "access_token": "<access token>"}` [Certain]. A refreshed token is sent as event `access_token`, payload `{"access_token": t}` [Certain]. "Allow public access" is off.
- Policies on `realtime.messages`: select for `broadcast` and `presence` when the caller is a member and the topic's key is the current one; insert for `presence` only.
  No client can broadcast.
- Server events arrive as event `broadcast`, payload `{"type": "broadcast", "event": E, "payload": P}` [Certain]:

| E | P |
|---|---|
| `ops` | a Batch. When its ops are over 32 KB, `ops` is null and the client gets it with `ops_since` (**decided here**: Realtime caps message size). |
| `meta` | `{kind: "members", gone: uuid or absent}`, `{kind: "sound", sha256: text}`, `{kind: "deleted"}`, `{kind: "blocked"}`. The app answers `members` and `sound` with `open_mess`; `gone` equal to me, `deleted` and `blocked` end the session. |

- Presence: track with event `presence`, payload `{"type": "presence", "event": "track", "payload": S}` [Certain], at join and at most once every 2 s. S is exactly `{u:
  uuid, page: text (at most 12 chars), loop: int 0 to 7, pad: int -1 to 63, playing: bool, rec: bool}`. Receivers clamp every field and drop a `u` that is not in
  `members()`. Name, colour and role come from `members()`, never from presence. The server sends `presence_state` (`{"<key>": {"metas": [S + phx_ref]}}`) and
  `presence_diff` (`{joins, leaves}` of the same shape) [Certain].
- Polling: `ops_since` every 2 s while a shared mess is open (Slice B), every 10 s once the socket is joined (Slice C), every 30 s for a mess with one member.

## 3. Op format (Slice B)
A batch is a JSON array of ops; each op is an object with `"t"`. `OPS_V = 1`. Loop `l` 0 to 7, pad `p` 0 to 63, step `s` 0 to 127, target `g` a control number
(MESS-FORMAT s6.8). Rules: apply is a pure function of the mess and the op; after any op the part it touched equals what save then load would give; per-step data at or
past a loop's `len` does not exist; apply ignores slot 64.

**Every op that names a pad `p` also carries `k`, the sound key the sender saw on that pad.** Apply to pad `p` if it holds `k`, else to the pad that now holds `k`, else
to `p`. Keys and scopes are worked out after that. An op for an empty pad does nothing.

| `t` | Fields | Last writer wins on | Inverse |
|---|---|---|---|
| `step.set` | `l, p, k, s, on` | `s/<l>/<p>/<s>` | same op, old value |
| `acc.set` | `l, p, k, s, v` (0 ghost, 1 normal, 2 accent) | `a/<l>/<p>/<s>` | same op, old value |
| `chance.set` | `l, p, k, s, v` (0 to 104) | `c/<l>/<p>/<s>` | same op, old value |
| `off.set` | `l, p, k, tk, on` (`tk` a tick, not a multiple of 24; renamed from `k`, **decided here**) | `o/<l>/<p>/<tk>` | same op, old value |
| `note.set` | `l, p, k, s, n, len` (`n` -36 to 24; `len` 0 removes) | `n/<l>/<p>/<s>/<n>` | same op, old `len` |
| `lock.set` | `l, p, k, g, s, v` (number or null) | `k/<l>/<p>/<g>/<s>` | same op, old value |
| `lane.set` | `l, p, k, g, s, v` (number or null) | `m/<l>/<p>/<g>/<s>` | same op, old value |
| `edit.set` | `p, k, f, v`; `f` one of `att rel rev loop mode tune fine vol pan tone norm choke held beatN pitch bake fx.rvb fx.rvbSize fx.echo fx.echoT fx.dirt fx.crush fx.chorus fx.wob fx.wobRate` | `e/<p>/<f>` | same op, old value |
| `edit.trim` | `p, k, st, en` (both ends as one op; replaces `edit.set` of `st` or `en`) | `e/<p>/trim` | `edit.trim` with the old pair |
| `opt.set` | `p, k, f, v`; `f` one of `glide glideT legato route ftype xy pack lfo.on lfo.wave lfo.rate lfo.target lfo.depth lfo.retrig`, or `fx.<g>` (0 removes) | `q/<p>/<f>` | same op, old value |
| `synth.set` | `p, k, id, v`; `id` a parameter id of the patch's kind, `@name` or `@src` | `y/<p>/<id>` | same op, old value |
| `song.set` | `b, v` (`b` 0 to 63; `v` -1 rest, 0 to 7) | `g/<b>` | same op, old value |
| `mess.set` | `f, v`; `f` one of `bpm swing squash level scale root fold name` (`name`: owner only, a string) | `t/<f>` | same op, old value |

Values are rounded as the writer rounds them: locks, lanes and `fx.<g>` to 3 decimals. `xy` and `pack` are whole strings. A cell key `x/<l>/<p>/...` lies in scopes
`R/<l>/<p>`, `L/<l>` and `P/<p>`; `e`, `q`, `y` keys lie in `P/<p>`.

| `t` | Fields | Does | Scope | Replaces | Inverse |
|---|---|---|---|---|---|
| `row.put` | `l, p, k, row` | sets one pad's whole row in one loop. `row` is `{steps, acc, chance, offs, notes, locks: {"<g>": "..."}, motion: {"<g>": "..."}}`, strings in MESS-FORMAT s6 form, absent keys empty | `R/<l>/<p>` | no | `row.put` of the old row |
| `loop.len` | `l, len, fill` (16 to 128, a multiple of 16; `fill` true repeats the old length into the new steps exactly as `AppState.setLen` does today, false leaves them empty) | sets the length; shrinking clears what falls off | `L/<l>` | no | `loop.put` of the old loop |
| `loop.clear` | `l` | empties the loop, length stays | `L/<l>` | yes | `loop.put` |
| `loop.copy` | `from, to` | `to` becomes a copy of `from` as it stands when applied | `L/<to>` | yes (`to`) | `loop.put` of the old `to` |
| `loop.put` | `l, len, pads` (`pads` maps a pad number to a row object) | replaces the whole loop | `L/<l>` | yes | `loop.put` |
| `pad.put` | `p, key, new?, sound?, edit?, synth?, opts?, loops?` (`key` `""` empties; `loops` maps a loop number to a row object; `sound` is a Sound without `key`, `src`, `state` for a `bn:` key) | replaces all pad `p` holds. `key` already on pad `q` and no `new: true`: `q` is emptied first (a move). `new: true` with a `synth:` key in use: applied under the lowest free `synth:<n>`. | `P/<p>`, and `P/<q>` | yes | `pad.put` of each pad touched |
| `pad.swap` | `a, b, ka, kb` | the two pads trade everything, **only if** pad `a` still holds `ka` and pad `b` still holds `kb`; else nothing | `P/<a>`, `P/<b>` | no | `pad.swap {a, b, ka: kb, kb: ka}` |
| `doc.reset` | none | server made (`restore_version`, `put_doc`). The phone keeps a local copy if it has unsent work, drops its copy and calls `open_mess`. | `*` | no | none |

- **Hints.** Structural ops are named by the app, never guessed: `setLen` gives `loop.len`, `copySlotTo` `loop.copy`, `clearSlotAt` `loop.clear`, `swapPads` `pad.swap`,
  and `putOnPad`, `clearPad`, `newSynth`, `scratchToPad` give `pad.put`. Hints are applied to the shadow with `Ops.apply`; whatever still differs goes as field ops.
  Without a hint a changed pad key or synth kind goes as `pad.put`, and a changed length as `loop.len` with `fill` false then cells. More than 64 changed cells in one
  pad's row of one loop go as one `row.put`. `loop.put` comes only from hints and undo.
- **Squash** drops a field op when a later op in the list sets the same key or **replaces** a scope it lies in. Only the four ops marked yes replace.
- **Batch size.** The client sends the longest run of waiting ops that fits 400 ops and 48 KB (UTF-8 bytes of the JSON array). One op bigger than that goes alone. Server
  cap: 500 ops and 262144 bytes.
- **Unknown ops.** An op is held as its whole JSON object and written back whole. A batch whose `v` is above mine is kept in the log, skipped, and sets `sawNewer`. An
  unknown `t` in a batch whose `v` is not above mine is junk and is dropped by everyone. A phone with `sawNewer` uploads no snapshot and sends cells, never a put built
  from its copy.
- **Never synced** (kept per phone by `Ops.carryLocal`): `sel`, `cur`, `mode`, `mutes`, `solos`, `at`, `app`, `v`, the old top-level `steps`, slot 64, `dropKeep`, unknown
  keys. A shared mess's snapshot blanks them. A mess with one member uploads them (`uploadDoc(carryLocal(host.project(), base + log))`) and a change to one makes a
  snapshot due.
- The mess format stays v3 in Slices A and B (**decided here** for B): the sounds list lives beside the mess, from `open_mess`, and `pad.put` carries `sound`.

## 4. Kotlin interfaces
Package `com.tideclock.messmachine.cloud`, in `core/src/commonMain/kotlin/com/tideclock/messmachine/cloud/`. Common Kotlin: no `java.*`, no coroutines, no `kotlinx`; it
must pass `tools/kt-check.sh js`. One thread: every method and every callback here runs on one thread (Android: the main thread). Network and file work happens elsewhere
and the answer is posted back. A callback is never run inside the call that took it.

### 4.1 Results, errors, small seams (`CloudApi.kt`)
```kotlin
/** code: a server code of s1.1, or net (no answer), auth (session dead), http (anything else), code_wrong,
 *  code_wait, bad_hash, unbuilt (this slice is not built yet). http: the status, 0 for none. */
class CloudErr(@JvmField val code: String, @JvmField val http: Int, @JvmField val detail: String)
/** Exactly one of the two is null. Calls with nothing to return pass true. */
fun interface Done<T> { fun done(value: T?, err: CloudErr?) }
fun interface Clock { fun nowMs(): Long }
/** The signed-in session between launches: one string or null. Android seals it with the Keystore. */
interface TokenStore { fun load(): String?; fun save(json: String?) }
/** Small JSON files by name (index.json, <cloud id>.sounds.json, <mess>.base.json, <mess>.queue.json). null deletes. */
interface CloudCache { fun load(name: String): String?; fun save(name: String, json: String?) }
```

### 4.2 Data classes (`Shapes.kt`)
Every parameter below is a `@JvmField val` (the annotation is left off here to save space). Names are the server's JSON names. A missing or null text is `""`, number 0,
bool false, list empty, unless the type is nullable. JSON objects that are whole messes (`doc`) are held as JSON text. `object Wire` in `Wire.kt` has one parser per
class, named in lower camel case (`Wire.me(j: Any?): Me`), built on the engine's `Json`; unknown fields are ignored.
```kotlin
class Session(access: String, refresh: String, expiresAt: Long /* epoch s */, userId: String, email: String)
class Me(id: String, display_name: String, name_set: Boolean, colour: Int, sound_bytes: Long, sound_limit: Long,
         messes: Int, mess_limit: Int, terms_ok: Boolean, terms_version: String, is_admin: Boolean)
class MessRow(id: String, name: String, role: String, owner_name: String, rev: Long, updated_at: String,
              members: Int, share_mode: String, doc_bytes: Int, blocked: Boolean)
class Batch(rev: Long, user: String, batch: String, v: Int, ops: List<Any?>? /* null: fetch with opsSince */, at: String)
class Member(id: String, name: String, colour: Int, role: String)
class Sound(key: String, sha256: String, src: String, owner: String, fmt: String, bytes: Long, seconds: Double,
            rate: Int, kind: String, title: String, bpm: Double, beats: Int, mkey: String, state: String)
class Opened(id: String, name: String, role: String, owner_id: String, rev: Long, doc_rev: Long, doc: String, ops_v: Int,
             ops: List<Batch>, sounds: List<Sound>, members: List<Member>, share_mode: String, share_token: String, topic: String)
class Rev(id: String, rev: Long)                 // create_mess, fork_mess, rename_mess, restore_version
class Saved(rev: Long, version_id: String)       // put_doc
class Prepared(path: String /* file on this device */, sha256: String, bytes: Long, seconds: Double, rate: Int)
class Registered(state: String, path: String /* in the bucket */, used: Long, limit: Long)
class Used(state: String, used: Long)            // finish_upload
class MySound(sha256: String, title: String, kind: String, bytes: Long, seconds: Double, state: String, messes: Int, vault: String)
class Deleted(state: String, path: String, used: Long, messes: Int)
class SoundUrl(key: String, sha256: String, bytes: Long, url: String)
// Slice B
class Since(rev: Long, doc_rev: Long, members: Int, topic: String, ops: List<Batch>)
class Applied(rev: Long, at: String);            class Snapped(doc_rev: Long, version_id: String)
class Invite(id: String, token: String, expires_at: String)
class InviteRow(id: String, expires_at: String, uses_left: Int, revoked: Boolean)
class InviteInfo(mess_name: String, owner_name: String);   class Joined(mess_id: String, role: String)
class Blocked(id: String, name: String);         class Live(live: Boolean, editors: Int)
// Slice C, D, F
class Presence(u: String, page: String, loop: Int, pad: Int, playing: Boolean, rec: Boolean)
class Share(share_mode: String, share_token: String)
class Shared(name: String, owner_name: String, mode: String, rev: Long, doc_rev: Long, doc: String, ops: List<Batch>, sounds: List<Sound>)
class VersionRow(id: String, rev: Long, label: String, by_name: String, at: String, bytes: Int)
class Version(id: String, rev: Long, label: String, doc: String)
class Grant(version: String, body: String, body_sha256: String)
```

### 4.3 `CloudApi` (`CloudApi.kt`)
One method per call of s1, in s1's order. `doc` and `opsJson` are JSON text. Errors are the call's codes from s1 plus `net`, `auth`, `http`. Methods of slices not built
yet have default bodies that answer `unbuilt`; a builder overrides the ones for their slice. `FakeCloud` and `HttpCloud` are the two implementations.
```kotlin
fun interface SessionListener { fun onSession(s: Session?) }        // a refresh replaced the session (save it); null: it is dead (sign out)
interface RealtimeListener {                                        // Slice C
    fun onJoin(topic: String, ok: Boolean, reason: String)          // the join was answered
    fun onOps(topic: String, b: Batch)                              // an `ops` broadcast
    fun onMeta(topic: String, kind: String, gone: String, sha256: String)   // a `meta` broadcast
    fun onPresence(topic: String, peers: List<Presence>)            // the whole list, clamped, each time it changes
    fun onDrop(topic: String)                                       // the socket or the channel went away
}
interface CloudApi {
    fun configured(): Boolean                                       // false: no server in this build; call nothing else
    fun newId(): String                                             // a fresh random uuid, lower case (FakeCloud counts up)
    fun setSession(s: Session?)                                     // the token every later call carries; null signs out locally
    fun setSessionListener(l: SessionListener?)                     // the api refreshes once on a 401 and retries; it reports here
    // sign-in (s1.2)
    fun requestCode(email: String, cb: Done<Boolean>)               // mails a code; code_wait when asked too soon
    fun verifyCode(email: String, code: String, cb: Done<Session>)  // code_wrong, code_wait; does not call setSession
    fun refresh(refreshToken: String, cb: Done<Session>)            // auth means the session is dead; net means try again
    fun signOut(cb: Done<Boolean>)                                  // tells the server, then answers true whatever it said
    fun signInPassword(email: String, password: String, cb: Done<Session>)   // Slice E, the review account only
    // Slice A: account and messes
    fun me(cb: Done<Me>);                                           fun setProfile(displayName: String, cb: Done<Me>)
    fun acceptTerms(version: String, cb: Done<Boolean>)
    fun createMess(id: String, name: String, doc: String, cb: Done<Rev>)       // safe to repeat with the same id
    fun myMesses(cb: Done<List<MessRow>>);                          fun openMess(messId: String, cb: Done<Opened>)
    fun putDoc(messId: String, doc: String, baseRev: Long, label: String, cb: Done<Saved>)   // conflict when baseRev is old
    fun renameMess(messId: String, name: String, cb: Done<Rev>);    fun deleteMess(messId: String, cb: Done<Boolean>)
    // Slice A: sounds. Files are named by a path on this device; the platform does the file work off the main thread.
    fun prepareUpload(key: String, cb: Done<Prepared>)              // finds the WAV for a bn: key and hashes it; not_found
    fun registerSound(p: Prepared, kind: String, localKey: String, title: String, bpm: Double, beats: Int, mkey: String, cb: Done<Registered>)
    fun upload(path: String, localFile: String, bytes: Long, cb: Done<Boolean>)   // to bucket sounds; "already there" is done
    fun finishUpload(sha256: String, cb: Done<Used>);               fun mySounds(cb: Done<List<MySound>>)
    fun attachSound(messId: String, key: String, owner: String, sha256: String, cb: Done<Boolean>)
    fun deleteSound(sha256: String, cb: Done<Deleted>)              // one server call; the caller runs the three steps
    fun removeObject(path: String, cb: Done<Boolean>)               // deletes my object from bucket sounds
    fun soundUrls(messId: String, token: String, cb: Done<List<SoundUrl>>)     // exactly one of the two is not ""
    fun cachedSound(sha256: String, cb: Done<String>)               // the device path of verified audio with this hash, or not_found
    fun fetchSound(url: String, sha256: String, bytes: Long, cb: Done<String>)  // download, verify, answer the device path; bad_hash
    fun dropSound(sha256: String)                                   // forget the cached file
    // Slice B: ops and people
    fun opsSince(messId: String, from: Long, cb: Done<Since>)       // the poll; gap means open again
    fun applyOps(messId: String, batchId: String, opsJson: String, v: Int, cb: Done<Applied>)   // safe to repeat with the same id
    fun saveSnapshot(messId: String, doc: String, docRev: Long, v: Int, label: String, cb: Done<Snapped>)
    fun members(messId: String, cb: Done<List<Member>>)
    fun makeInvite(messId: String, days: Int, cb: Done<Invite>);    fun listInvites(messId: String, cb: Done<List<InviteRow>>)
    fun revokeInvite(inviteId: String, cb: Done<Boolean>);          fun redeemInvite(token: String, cb: Done<Joined>)
    fun inviteInfo(token: String, cb: Done<InviteInfo>)             // works signed out
    fun removeMember(messId: String, userId: String, block: Boolean, cb: Done<Boolean>)
    fun leaveMess(messId: String, cb: Done<Boolean>);               fun blockUser(userId: String, cb: Done<Boolean>)
    fun myBlocks(cb: Done<List<Blocked>>);                          fun unblockUser(userId: String, cb: Done<Boolean>)
    fun joinLive(messId: String, cb: Done<Live>);                   fun leaveLive(messId: String, cb: Done<Boolean>)
    // Slice C: realtime. One socket for all topics; the caller joins again after onDrop.
    fun join(topic: String, l: RealtimeListener);  fun track(topic: String, state: Presence);  fun leave(topic: String)
    // Slice D
    fun setShare(messId: String, mode: String, fresh: Boolean, cb: Done<Share>)
    fun openShared(token: String, cb: Done<Shared>)                 // works signed out
    fun forkMess(id: String, from: String, token: String, name: String, doc: String, cb: Done<Rev>)
    fun versions(messId: String, cb: Done<List<VersionRow>>);       fun getVersion(versionId: String, cb: Done<Version>)
    fun restoreVersion(messId: String, versionId: String, cb: Done<Rev>)
    // Slice E and F
    fun reportMess(messId: String, token: String, reason: String, details: String, aboutUser: String, cb: Done<Boolean>)
    fun deleteAccount(cb: Done<Boolean>)                            // stale_login: ask for a fresh code first
    fun vaultGrant(cb: Done<Grant>)
    fun vaultSubmit(sha256: String, creditName: String, grantVersion: String, note: String, cb: Done<Boolean>)
}
```

**`HttpCloud` (decided here, so three platforms do not each write fifty calls).** Common Kotlin, `HttpCloud.kt`. It implements every sign-in, RPC and Edge Function method
of `CloudApi`: URLs, bodies, parsing, the error envelopes, one refresh and retry on 401. A platform extends it and writes only the two abstract members and the `CloudApi`
methods `newId`, `prepareUpload`, `cachedSound`, `fetchSound`, `dropSound`, `join`, `track`, `leave`.
```kotlin
class HttpReq(@JvmField val method: String /* GET, POST, DELETE */, @JvmField val url: String,
              @JvmField val headers: Map<String, String>, @JvmField val body: String? /* UTF-8 JSON */)
class HttpRes(@JvmField val status: Int /* 0: no answer */, @JvmField val body: String)
abstract class HttpCloud(url: String, key: String) : CloudApi {
    protected abstract fun http(r: HttpReq, cb: Done<HttpRes>)   // RPC queue. Always answers a value (status 0 for no answer), never an err
    protected abstract fun sendFile(r: HttpReq, localFile: String, bytes: Long, cb: Done<HttpRes>)   // file queue, fixed length body
}
```

`FakeCloud` (`fake/FakeCloud.kt`, common Kotlin): `class FakeCloud(clock: Clock)` is a whole server in memory with the same checks, order and codes as s1. `fun
client(email: String): CloudApi` is one pretend phone with its own device files. Nothing happens inside a call: answers queue until `deliver()`. Test controls:
`deliver()`, `advance(ms: Long)`, `setOnline(c: CloudApi, on: Boolean)`, `dropNextAnswer(c: CloudApi)`, `expireToken(c: CloudApi)`, `lastCode(email: String): String`,
`makeAdmin(email: String)`, `putLocalSound(c: CloudApi, key: String, wav: ByteArray, seconds: Double, rate: Int)`, and for Slice C `shuffleBroadcasts(seed: Long)`,
`delayBroadcasts(n: Int)`.

### 4.4 `CloudStore` (Slice A, `CloudStore.kt`): account, list and whole-mess sync
```kotlin
/** The app side of Slice A. It owns the mess files on the phone; the store never touches them. */
interface StoreHost {
    fun onAccount();  fun onMesses()                                 // state, me, usage / rows or a mark changed: redraw
    fun localDoc(localId: String): String?                           // the mess file as last saved here, or null when gone
    fun needs(localId: String): List<String>                         // the bn: keys it needs (pads, chop bases, granular src)
    fun adopt(row: MessRow, doc: String): String                     // a cloud mess new to this phone: write a file, return its local id
    fun takeServer(localId: String, name: String, doc: String)       // overwrite the file (and the screen if open); nothing local is unsent
    /** A clash. Copy my file to a new local id named copyName (the screen follows the copy if it was open),
     *  write the server's doc and name into localId, return the new local id. */
    fun keepBoth(localId: String, copyName: String, serverName: String, serverDoc: String): String
    fun soundArrived(s: Sound, path: String)                         // add to the Library under s.key ("From shared messes" if not mine)
    fun soundGone(s: Sound)                                          // its maker left: drop the Library entry; the pad reads Missing
    fun notice(kind: Int, text: String)
}
class CloudStore(api: CloudApi, tokens: TokenStore, cache: CloudCache, clock: Clock, host: StoreHost) {
    companion object {
        const val OUT = 0; const val TERMS = 1; const val IN = 2                       // account state
        const val M_PHONE = 0; const val M_SYNCED = 1; const val M_WAITING = 2; const val M_SENDING = 3   // a mess's cloud mark
        const val M_OFFLINE = 4; const val M_BLOCKED = 5; const val M_REFUSED = 6
        const val N_KEPT_BOTH = 1; const val N_SIGNED_OUT = 2; const val N_QUOTA = 3; const val N_TOO_BIG = 4
        const val N_TOO_LONG = 5; const val N_UPDATE = 6; const val N_FULL = 7
        const val SAVE_MS = 5000L; const val PULL_MS = 60000L
    }
    @JvmField var state = OUT;  @JvmField var me: Me? = null
    @JvmField var rows: List<MessRow> = emptyList()   // the last my_messes answer (kept in list.json for offline)
    @JvmField var dataOk = false                      // host sets it: on Wi-Fi, or acct-data is on. false: sounds wait
    fun start()                                       // load the token, setSession, me, my_messes. Safe with nothing stored
    fun tick()                                        // at least once a second: refresh 5 min early, retries (1, 2, 5, 10 s), queues
    fun background()                                  // push whatever waits now
    fun requestCode(email: String, cb: Done<Boolean>);  fun setName(name: String, cb: Done<Me>)
    fun verify(email: String, code: String, cb: Done<Me>)   // saves the session, then me(); state becomes TERMS or IN
    fun acceptTerms(cb: Done<Boolean>)                // accept_terms(me.terms_version); nothing uploads before it
    fun signOut()                                     // also terms-decline. Phone files and links stay
    fun refresh()                                     // my_messes and me, then the pull rule
    fun cloudId(localId: String): String;  fun localId(cloudId: String): String   // "" when not online / not on this phone
    fun mark(localId: String): Int;  fun waiting(): Int   // M_* of one mess / how many messes have changes not sent yet
    fun missing(localId: String): Int                 // its sounds not on this phone yet
    fun saved(localId: String, name: String)          // call after every local save: signed in and not kept off means it goes online
    fun keepOff(localId: String, off: Boolean)        // "keep off the cloud": true unlinks (and deletes my cloud mess), false links again
    fun open(cloudId: String, cb: Done<String>)       // answers the local id: open_mess, then adopt or takeServer, then sounds
    fun rename(localId: String, name: String, cb: Done<Boolean>)
    fun delete(localId: String, cb: Done<Boolean>)    // delete_mess when mine; the phone file stays
    fun getSounds(localId: String)                    // "Get 3 sounds now": fetch once whatever dataOk says
}
```
Rules, so the fake and the app agree:
1. `index.json` is `{v: 1, links: [{local, cloud, rev, dirty, off}]}`. `<cloud id>.sounds.json` is the last `sounds` list of that mess. `rev` is the server rev this
   phone's file was last equal to.
2. **Push.** `saved` sets `dirty`. After `SAVE_MS`, or at once on `background`: first the sounds (per `needs` key: mine go `prepareUpload`, `registerSound`, `upload`,
   `finishUpload`, `attachSound`; a sound whose listed owner is not me is only attached, never uploaded). Not linked: `createMess(newId, name, doc)`. Linked:
   `putDoc(cloud, doc, link.rev, "Saved")`, then `link.rev` is the answer's rev. A sound refused with `quota`, `too_long` or `too_big` gives a notice and stays on this
   phone; the doc still saves.
3. **Clash, keep both.** `conflict`: `openMess`, `host.keepBoth(...)` with copy name `<name> (this phone)`, link the copy with `createMess`, set the old link's `rev` to
   the server's, `notice(N_KEPT_BOTH)`. **decided here** (which of the two keeps the cloud id).
4. **Pull.** On `start`, `refresh` and every `PULL_MS` while the Messes sheet is open: for a linked row with `row.rev > link.rev`: not dirty, `openMess` then
   `host.takeServer`; dirty, push (which clashes and keeps both).
5. The server's `name` wins on every pull. A linked mess that answers `not_found` is unlinked; the phone file stays.
6. After an open: for each Sound, `gone` gives `dropSound` and `host.soundGone` before anything plays; else `cachedSound`, else (when `dataOk`) `soundUrls` and
   `fetchSound`, then `host.soundArrived`.
7. `net` gives `M_OFFLINE` and a retry. A dead session (`SessionListener` null) signs out with `N_SIGNED_OUT`. `blocked` gives `M_BLOCKED`. `too_many_messes`,
   `docs_full`, `too_big` give `M_REFUSED` and `N_FULL`.
8. From Slice B a mess with a `SyncSession` open is left alone by push and pull; `saved` calls `session.saveVersion("Saved")`. Slice B apps never call `putDoc`.

### 4.5 `Ops` (Slice B, `Ops.kt`)
```kotlin
class Op(@JvmField val m: MutableMap<String, Any?>) { val t: String get() = Json.str(m["t"], "") }   // the whole JSON object
/** One local change: the ops that make it, the ops that take it back, and shownRev when it was made. */
class Change(@JvmField val ops: MutableList<Op>, @JvmField val undo: MutableList<Op>, @JvmField var seenRev: Long)
object Ops {
    const val V = 1; const val MAX_OPS = 400; const val MAX_BYTES = 49152; const val ROW_PUT = 64
    /** Applies hints to shadow, then compares shadow and project in place (no copies), writing old values into
     *  undo in the same pass, and patches shadow until its shared part equals project's. */
    @JvmStatic fun change(shadow: Project, project: Project, hints: List<Op>): Change
    @JvmStatic fun diff(a: Project, b: Project): MutableList<Op>   // no hints: change(clone(a), b, empty).ops. Used on reopen from the cache
    @JvmStatic fun apply(p: Project, ops: List<Op>): Int   // in order, in place, by the rules of s3; returns how many it skipped as unknown
    @JvmStatic fun known(op: Op): Boolean;  @JvmStatic fun structural(op: Op): Boolean
    @JvmStatic fun key(op: Op): String                  // the last-writer-wins key, "" for a structural or unknown op
    @JvmStatic fun scopes(op: Op): List<String>         // R/, L/ and P/ scopes it lies in or covers; "*" for doc.reset and unknown
    @JvmStatic fun replaces(op: Op): Boolean            // true only for loop.clear, loop.put, loop.copy, pad.put
    @JvmStatic fun squash(ops: List<Op>): MutableList<Op>   // order kept
    @JvmStatic fun fit(ops: List<Op>): Int              // how many leading ops make the next batch (400 ops, 48 KB, a big one alone)
    @JvmStatic fun canon(p: Project): String            // the shared part as text in one fixed order, for comparing
    @JvmStatic fun uploadDoc(p: Project, shared: Boolean): String   // toJson as is when solo; the never-synced fields blanked when shared
    @JvmStatic fun carryLocal(from: Project, to: Project)           // copies the never-synced fields
    @JvmStatic fun clone(p: Project): Project           // field by field, no JSON round trip
    @JvmStatic fun toJson(ops: List<Op>): String;  @JvmStatic fun fromList(l: List<Any?>): MutableList<Op>
}
```
Law: `canon(apply(clone(a), diff(a, b))) == canon(b)` for any two messes.

### 4.6 `SyncSession` (Slice B, `SyncSession.kt`)
```kotlin
class Peer(@JvmField val id: String, @JvmField val name: String, @JvmField val colour: Int, @JvmField val role: String, @JvmField val page: String,
           @JvmField val loop: Int, @JvmField val pad: Int, @JvmField val playing: Boolean, @JvmField val rec: Boolean)
/** The app side: owns the Project the screens draw. */
interface SyncHost {
    fun project(): Project
    fun replace(p: Project)                              // a new Project (local-only fields carried): swap it in, redraw
    fun patched()                                        // project() was changed in place: redraw, rebuild the engine snapshot
    fun onState(state: Int);  fun onNotice(kind: Int, n: Int, who: String)
    fun onSound(s: Sound)                                // a sound the mess needs: fetch it (CloudStore rule 6)
    fun onHeld()                                         // someone else's batch entered the log while playing: run arm()
    fun keepCopy(why: Int)                               // called before pending is ever dropped: save the screen's mess as a phone copy
    fun onPeers(peers: List<Peer>)                       // Slice C; self left out
}
class SyncSession(api: CloudApi, cache: CloudCache, clock: Clock, host: SyncHost, @JvmField val messId: String, @JvmField val me: String) {
    companion object {
        const val OPENING = 0; const val SOLO = 1; const val LIVE = 2; const val WATCH = 3   // WATCH: 4 others already editing
        const val OFFLINE = 4; const val GONE = 6                                            // GONE: removed, deleted or blocked
        const val N_REPLACED = 1; const val N_UNDO_SKIPPED = 2; const val N_JOINED = 3; const val N_LEFT = 4
        const val N_NEWER = 5; const val N_FULL = 6; const val N_KEPT_COPY = 7
        const val W_REFUSED = 1; const val W_GAP = 2; const val W_RESET = 3; const val W_GONE = 4   // keepCopy reasons
        const val SEND_MS = 400L; const val SOLO_SEND_MS = 5000L
        const val POLL_MS = 2000L; const val POLL_JOINED_MS = 10000L; const val SOLO_POLL_MS = 30000L
        const val SNAP_OPS = 50; const val SNAP_MS = 60000L; const val GAP_OPS = 500; const val GAP_MS = 86400000L
    }
    @JvmField var state = OPENING;  @JvmField var role = "";  @JvmField var sawNewer = false
    @JvmField var serverRev = 0L        // highest rev held with no gap below it
    @JvmField var shownRev = 0L         // other people's ops up to here are on screen
    @JvmField var previewRev = 0L       // serverRev when preview() last ran
    fun open()                          // both cache files, then open_mess or ops_since, then join_live
    fun close()                         // flush, snapshot if due, leave_live, write both cache files
    fun background()                    // flush and write both cache files
    fun tick()                          // at least every 100 ms while open: sending, the poll, retries, snapshots
    fun hint(op: Op)                    // a structural edit is about to be reported by localChanged
    fun localChanged(undoable: Boolean) // after every change to host.project()
    fun setPlaying(playing: Boolean);  fun setRecording(on: Boolean)   // both feed presence (`playing`, `rec`)
    fun setPerPass(on: Boolean)         // Rec is running: own ops go out at loop tops only
    fun setPresence(page: String, loop: Int, pad: Int)
    fun held(): Int                     // other people's batches waiting for the loop top
    fun preview(): Project?             // the mess after a release of everything held now; sets previewRev; null when held() == 0
    fun loopTop(previewRev: Long)       // the engine swapped the armed snapshot in: release batches up to that rev only
    fun canUndo(): Boolean;  fun undo() // shared messes: own changes only. No redo in a shared mess
    fun pending(): Int                  // own ops not confirmed yet
    fun saveVersion(label: String)      // a snapshot now, with a label
    fun shared(): Boolean               // more than one member
}
```
Rules (Review B M1 to M5, S5, S7):
1. **The screen is `base`, then my batches in `log`, then `pending`.** `base` changes only by server batches in rev order. My confirmed batches at the head of `log` fold
   into `base` at once. A batch is mine only if its id is in `sent` or equals `flight.id`, never by user id (two phones of one account are two people).
2. **Cache (M1).** `<mess>.base.json` is `{v: 1, rev: shownRev, base: doc}`, written after a release. `<mess>.queue.json` is `{v: 1, sent: [{id, ops}], flight: {id, ops}
   or null, waiting: [op]}`, written before every `applyOps` and after every answer; `sent` is my confirmed batches above `shownRev`. On open: `screen0 = base + sent +
   flight + waiting`; `pending = flight + waiting + Ops.diff(screen0, host.project())`. A flight batch that `opsSince` does not return is sent again under the same id. A
   phone that changed nothing sends zero ops.
3. **Sending.** One batch in flight. `localChanged` runs `Ops.change(shadow, host.project(), hints)`. Every `SEND_MS` (5 s solo; at once on `close`, `background`,
   `saveVersion`): `squash`, `fit`, a `newId`, `applyOps`. `net`: same batch, same id, after 1, 2, 5, 10 s, state `OFFLINE`. `rate_limited`: wait 1 s. `snapshot_first`:
   `saveSnapshot`, then the same batch. `too_big`, `bad_input`, `bad_name`, `forbidden`, `day_limit`: `host.keepCopy(W_REFUSED)`, drop that batch, rebuild the screen.
   `not_found`, `blocked`: `keepCopy(W_GONE)`, `GONE`. `mess_full_live`: `WATCH`, the ops wait.
4. **Receiving.** `opsSince` every `POLL_MS` (and broadcasts from Slice C) fill the log in rev order. `gap` or a `doc.reset` batch: `keepCopy(W_RESET)` if anything is
   pending, then `openMess`. On open, more than `GAP_OPS` on either side, or a cache older than `GAP_MS` with ops on both sides: `keepCopy(W_GAP)`, drop `pending`, show
   the server's, `N_KEPT_COPY`. No question is asked.
5. **Release (M2).** Not playing: as soon as the log holds a batch of theirs. Playing: only in `loopTop(rev)`, and only batches with rev at or below `rev`; later ones
   wait for the next arm. A release applies the log to `base`, builds `next = clone(base)` plus `pending`, `carryLocal(host.project(), next)`, `host.replace(next)`.
6. **Arm (M2).** The host's `arm()` is: `p = session.preview()`; if not null, build a `Snapshot` from it with `seq` = the host's count of local rebuilds, and
   `engine.setSnapshotAtWrap(it, ++token)`. `arm()` runs at the end of `changed()`, after `localChanged`, and from `onHeld()`, at most once a frame. When
   `engine.swapToken` changes the host calls `session.loopTop(the previewRev it armed with)`. Stop and pause clear the armed snapshot. A sound that finishes downloading
   while playing also goes through `arm()`. Engine side, already in `Engine.kt` on this branch: `fun setSnapshotAtWrap(s: Snapshot?, token: Int)`, `Snapshot.seq`,
   `@Volatile @JvmField var swapToken`.
7. **Snapshots.** `LIVE` or `SOLO`, not `sawNewer`, and `serverRev - docRev >= SNAP_OPS` or `SNAP_MS` passed with new revs; also on `close` and `saveVersion`. Shared:
   `uploadDoc(base + log, true)`, timed uploads by the live editor with the lowest user id, the others at twice the thresholds. One member (M4):
   `uploadDoc(carryLocal(host.project(), base + log), false)`, and a change to a never-synced field makes one due. `stale` is ignored.
8. **Undo (S5).** A `Change` records `seenRev`. An undo op is skipped when someone else touched its key or a scope around it at a rev above `seenRev`; skipped parts give
   `N_UNDO_SKIPPED`.

## 5. Android seam
- `class AppState(private val app: Context, cloudApi: CloudApi? = null)`. `@JvmField val cloud: CloudApi? = cloudApi?.takeIf { it.configured() }`. **When `cloud` is null
  no control below is composed, no network call is made and the app behaves as today.** `val store: CloudStore?` is built only when `cloud` is not null; `var sync:
  SyncSession?` (Slice B) is the open mess's session.
- The app: `MainActivity` passes `SupabaseApi.orNull(this)`, which is null when `BuildConfig.CLOUD_URL` or `CLOUD_KEY` is blank. Tests: `AppState(ctx,
  fake.client("a@test"))`; `AppState(ctx)` is the no-server build.
- `gradle.properties` gains `mm.cloudUrl=`, `mm.cloudKey=` (the publishable key; renamed from `mm.cloudAnon`, **decided here**) and `mm.siteHost=`, all empty.
  `app/build.gradle.kts` turns them into `BuildConfig.CLOUD_URL`, `CLOUD_KEY`, `SITE_HOST` and the manifest placeholder `siteHost` (default `unset.invalid`). CI may set
  them with the env vars `MM_CLOUD_URL`, `MM_CLOUD_KEY`, `MM_SITE_HOST`, which win over the file. **decided here.**
- Files: `SupabaseApi.kt` (`class SupabaseApi : HttpCloud`, `HttpURLConnection`, one RPC thread and one file thread, uploads with `setFixedLengthStreamingMode`, callbacks
  posted to the main thread; OkHttp only from Slice C), `CloudPlat.kt` (`AndroidTokens : TokenStore` in prefs `cloud` sealed by the Keystore, `AndroidCache : CloudCache`
  in `files/cloud/`, both left out of backups), `CloudHost.kt` (`StoreHost`, later `SyncHost`), `ui/CloudScreens.kt`.
- Hooks in `AppState.kt`, all no-ops when `cloud` is null: `saveMess` calls `store.saved(id, name)` after the local write; the frame loop calls `store.tick()`; going to
  the background calls `store.background()`. Slice B adds: `changed(commit)` ends with `sync.localChanged(commit)` then `arm()`; the structural actions call
  `sync.hint(op)` first; `undo()` and `canUndo` hand over when `sync.shared()`; the frame loop calls `sync.tick()` and watches `engine.swapToken`; `AppState.tick` calls
  `changed()` when it writes motion; `DoctorScreen` re-reads its copy when `version` changes.

**Test tags** (each needs its line in `Faq.kt` and a row in `shared/PARITY.md`; `<id>` is a local mess id; `<cid>`, `<uid>`, `<iid>` are the first 8 characters of a cloud
mess, user or invite id)

| Slice | Tags | Control |
|---|---|---|
| A | `acct-open` | row at the top of the Messes sheet: "Sign in to save online" or your name |
| A | `acct-email`, `acct-send`, `acct-code`, `acct-verify`, `acct-resend` | the email code steps |
| A | `terms-text`, `terms-agree`, `terms-decline` | terms; decline signs out |
| A | `cloud-move-all`, `cloud-move-pick`, `cloud-move-later` | "Put your 12 messes online?", once after the first sign-in (from Slice B: after any waiting invite join) |
| A | `acct-name`, `acct-usage`, `acct-data`, `acct-terms`, `acct-privacy`, `acct-signout` | the Account page |
| A | `mess-cloud-<id>`, `mess-offcloud-<id>` | the cloud mark on a row of the one mess list (synced, waiting, offline, refused); row menu "Keep off the cloud" and back. **decided here** |
| A | `omess-<cid>`, `omess-dup-<cid>`, `omess-del-<cid>` | an online mess not on this phone (tap opens it); Duplicate; Delete (owner; from Slice B Leave for others) |
| A | `sync-state` | lamp on the open mess's row: green online, amber "3 changes waiting", grey offline |
| A | `sounds-get` | on a pad whose sound has not come: "Get 3 sounds now". **decided here** |
| B | `omess-share-<cid>`, `share-invite`, `share-members` | the Share sheet; "Invite to edit" makes a 7 day single-use link and opens the phone's share sheet; Members |
| B | `mem-<uid>`, `mem-leave`, `inv-revoke-<iid>` | a person (colour, name, role); leave (not the owner); cancel an unused invite (owner) |
| B | `mem-remove-<uid>`, `mem-remove-go`, `mem-remove-block` | owner only; the question's answers are "Remove" and "Remove and block". **decided here** for the two answer tags |
| B | `link-paste`, `invite-join`, `invite-no` | paste an invite link (share links from Slice D); "Join Basement jam?". An invite opened while signed out survives sign-in and terms |
| B | `name-ask`, `name-ask-ok` | asked once, at the first share or join, while `me.name_set` is false. **decided here** |
| B | `acct-blocks`, `block-undo-<uid>` | people you blocked, with Unblock (`block-undo` **decided here**) |
| B | `sync-newer` | "Someone here has a newer Mess Machine. Update to see everything they do." |

Cut and never to be composed: `mess-where`, `mess-tab-phone`, `mess-tab-online`, `omess-keep-*`, `hist-save`, `share-invite-email`, `share-invite-role`, `mem-role-*`,
`mem-block-*`, `mem-owner-*`, `acct-sounds`, `acct-sound-del-*`, `acct-colour-*`, `vault-mine`, `next-loop`, `pad-lock-*`, `lock-watch`, `lock-take`, `gap-merge`,
`gap-copy`, `del-hand-*`. Later slices: `here-chip`, `here-<uid>`, `loop-ask-go`, `loop-ask-no` (C); `share-link*`, `shared-*`, `hist-open`, `hist-<n>`,
`hist-restore-<n>` (D); `report-*`, `del-*`, `acct-delete` (E); `vault-give`, `vault-grant`, `vault-credit`, `vault-agree`, `vault-send` (F).
