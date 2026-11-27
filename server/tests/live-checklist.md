# The live checklist

What plain Postgres cannot prove. `node live.mjs` does every step marked **auto** against a real Supabase (the local
stack in CI, or the real project; see `server/README.md` step 15). The rest are done by hand once, on the real project.

| # | Step | How |
|---|---|---|
| 1 | The migrations apply cleanly on Supabase (CI: `supabase start` and `supabase db reset`). The catalogue on the real database: 54 functions in `public`, all definer with an empty search path, anon may run only `invite_info`, `open_shared`, `report_mess`, `vault_grant`; every table has row level security and no client grant; both buckets private at 20 MB; our six Storage and two Realtime policies. | auto (catalogue with `DB_URL`) |
| 2 | A code arrives by mail, is 6 digits, and `/auth/v1/verify` with `type: "email"` returns a SessionJson. A second request at once is 429. A wrong code is a 4xx with `error_code` `otp_expired`. Refresh works; a dead refresh token is 400, 401 or 403. A direct password sign-up gets no usable session. | auto (the mail part needs `MAIL_URL`, so it runs in CI) |
| 3 | The RPCs over PostgREST: the error envelope is HTTP 400 with `code` `P0001` and the code as `message`; no session is read as `auth`; create, save, the `conflict` clash, invite, join, a stranger's `not_found`. | auto |
| 4 | Storage: upload with the bearer token, `x-upsert: false`, 200 with a `Key`; the same upload again answers Duplicate; `finish_upload` reads the real size from `storage.objects.metadata`; a stranger cannot upload into my folder; nobody can upload an unregistered file, overwrite one, or read one with their own token; a signed URL downloads the same bytes with no headers. | auto |
| 5 | Realtime: a member joins `realtime:mess:<id>:<key>` with `private: true` and hears the `ops` broadcast that `apply_ops` sends (this checks `realtime.send`, the topic and the partition note); a stranger is refused or hears nothing; the plain `mess:<id>` hears nothing; a client broadcast is not delivered; presence comes through; a removed member hears `meta {kind:"members", gone}`. | auto |
| 6 | Edge Functions: CORS preflight; `sound-urls` for a member (absolute URL, the same bytes, `expires_in` 600), for a guest with a share link, a stranger's `not_found`, a bad token's 401 `auth`, `bad_input`; `sweep` refuses the wrong secret and runs with the right one; `delete-account` refuses no session and, with a fresh code, removes the rows, the files and the sign-in. | auto when the functions are served |
| 7 | The Vault: an admin copies a file into `vault`, nobody else can, `not_copied` before the copy, approve, and after the maker deletes the sound the mess plays the Vault copy. | auto |
| 8 | `delete-account` refuses a sign-in over 10 minutes old (`stale_login`). | by hand: sign in on the phone, wait 11 minutes, try Delete account. The rule itself is tested in SQL. |
| 9 | The real mail: the code mail arrives from your own sender (SMTP), with the code, within a minute, and not in spam. | by hand, on a phone, with your own address |
| 10 | The review account signs in with its password and can use the app; it is not an admin. A password sign-in to any other account cannot call anything. | by hand once (`signInPassword` in the app) |
| 11 | A 30 MB upload over a phone connection: refused at once by the bucket's 20 MB limit, with no long wait. A 19 MB upload finishes on a phone connection. | by hand on the phone |
| 12 | The daily sweep runs from the Cron job (its last run shows 200 in Edge Functions, Logs). | by hand the day after step 13 of the README |
| 13 | `https://<site>/m/<token>` opens the installed app. | by hand on the phone |

## Only provable on the real project

- The dashboard settings themselves (Confirm email on, code length and life, the templates, SMTP, Realtime "Allow
  public access" off). The CI stack has them from `supabase/config.toml`; the real project has them only if the README
  steps were done. `live.mjs` against the real project then checks their effects (steps 2, 5).
- Supabase's own limits and costs: Realtime message counts, egress from signed URLs, database size.
- That `amr` in a real token holds `{method: "otp", timestamp}` entries: `delete-account` passing with a fresh code in
  step 6 is the proof; the SQL tests assume this shape.
