# Mess Machine accounts: the server

Everything the accounts need on Supabase lives in this folder. The app, the shared core and the web pages talk to
it through the calls in `docs/ACCOUNTS-API.md`.

| Folder | What it is |
|---|---|
| `supabase/migrations/` | The database, in nine files applied in name order: tables, helpers, then one file per slice (A to F), then what the Edge Functions call. Safe to apply twice. |
| `supabase/functions/` | The three Edge Functions: `sound-urls` (download links for sounds), `delete-account`, `sweep` (the daily tidy-up). |
| `supabase/config.toml`, `supabase/templates/` | Settings for the Supabase command line tool and the local test stack, and the sign-in mail (it carries the code). |
| `tests/` | The tests. `npm test` runs every call against a real Postgres. `live.mjs` runs the live checklist against a real Supabase. See `tests/live-checklist.md`. |

Tags: [Certain] checked by a test here, [Likely] how Supabase says it works but not yet checked on a real project.

## Setting up the real project (for Tyler)

You do this once. It takes about half an hour. Nothing here needs code, but steps 4 to 6 use a Terminal window.

### A. Make the project

1. Go to supabase.com, sign in, and press **New project**.
2. Name it `mess-machine`. Pick the region nearest to you (West US). Make a strong database password and keep it
   in your password manager.
3. Choose the **Pro** plan and leave the **spend cap on**. The free plan pauses a project after a week with no use and
   keeps no backups. [Likely]
4. When the project is ready, open **Project Settings**, then **API Keys**. Note three things: the **Project URL**,
   the **publishable key** and the **secret key**. The secret key is like a master password: never put it in the app,
   a web page or a chat.

### B. Put the database and functions on it

5. On a computer with Node installed, open a Terminal in this repository's `server` folder and run these, one at a
   time (the first opens a browser to sign in; the second asks for the database password from step 2):

   ```
   npx supabase login
   npx supabase link --project-ref <the id in your project URL, the part before .supabase.co>
   npx supabase db push
   npx supabase functions deploy sound-urls --no-verify-jwt
   npx supabase functions deploy delete-account --no-verify-jwt
   npx supabase functions deploy sweep --no-verify-jwt
   ```

   `db push` applies the nine files in `supabase/migrations/`. "No verify JWT" is right: each function checks who is
   calling itself, and `sound-urls` must also work for a guest with a share link.
6. Make a sweep secret (any long random text, for example from your password manager) and store it:

   ```
   npx supabase secrets set MM_SWEEP_SECRET=<your long random text>
   ```

   No Terminal? In the dashboard, open **SQL Editor**, then paste and run each file of `supabase/migrations/` in name
   order, one at a time. The functions then go up with **Edge Functions**, **Deploy a new function**, one per folder
   in `supabase/functions/` (paste `index.ts`, and `_shared/mm.ts` next to it), with "Verify JWT" off.

### C. Settings made by hand in the dashboard

7. **Authentication**, **Sign In / Providers**, **Email**: Email on, **Confirm email on**, email OTP length **6**,
   email OTP expiry **600** seconds (10 minutes). Confirm email must stay on: with it off, anyone could make an account
   with a password and no proof of their address. [Likely: menu names change now and then]
8. **Authentication**, **Emails**, **Templates**: open **Confirm signup** and **Magic Link**. In both, set the subject
   to `Your Mess Machine code` and replace the body with the contents of `supabase/templates/code.html`. The important
   part is `{{ .Token }}`: that is the 6 digit code. Without it people get a link the app cannot use.
9. **Authentication**, **Emails**, **SMTP Settings**: turn on custom SMTP and enter your mail service's details (for
   example Resend: host `smtp.resend.com`, port 465, user `resend`, password your Resend key), with a sender like
   `Mess Machine <noreply@your domain>`. Supabase's own mailer only sends a few mails an hour and only to your team.
10. **Realtime**, **Settings**: turn **off** "Allow public access". This is what keeps each mess's live channel private.
11. Make yourself the admin (the only one who approves Vault sounds and acts on reports). Sign in to the app once with
    your email so your account exists, then in **SQL Editor** run:

    ```
    insert into public.admins (user_id) select id from auth.users where email = 'you@example.com';
    ```

12. The store review account (Apple and Google reviewers sign in with a password, not a code). In **Authentication**,
    **Users**, press **Add user**, **Create new user**, give it an email like `review@your domain` and a strong
    password, and tick **Auto Confirm User**. Then in **SQL Editor** run:

    ```
    update public.config set value = to_jsonb(id::text) from auth.users
      where public.config.key = 'review_user' and auth.users.email = 'review@your domain';
    ```

    Only this one account may sign in with a password. It is never an admin. Put its email and password in the review
    notes of both stores.
13. The daily sweep. In **Integrations**, **Cron**, make a job that runs once a day (say 3:17 at night) and sends an HTTP
    POST to `https://<your project>.supabase.co/functions/v1/sweep` with the header `x-mm-sweep` set to the secret from
    step 6 and the body `{}`. [Likely]
14. Give the app its address: the Project URL and the **publishable** key go in `gradle.properties` as `mm.cloudUrl=`
    and `mm.cloudKey=` (or as the GitHub secrets `MM_CLOUD_URL` and `MM_CLOUD_KEY`). Never the secret key.

### D. Check it works

15. In a Terminal in `server/tests`, run `npm ci` once, then:

    ```
    SUPABASE_URL=<Project URL> SUPABASE_KEY=<publishable key> SUPABASE_SERVICE_KEY=<secret key> MM_SWEEP_SECRET=<step 6> node live.mjs
    ```

    It makes a few throwaway accounts, tries everything, removes them again and prints PASS or FAIL per check. Every
    line should say PASS or SKIP. Then do the by-hand steps at the end of `tests/live-checklist.md`.

## Changing a limit later

Every limit is a row in the table `public.config` (sound space per person, messes per person, people per mess and so
on). Change one in **SQL Editor**, for example `update public.config set value = '300' where key = 'mess_limit';`.
No new app build is needed. Words a name may not contain are rows in `mm_private.bad_words`. A new wording of the
Vault grant is a new row in `public.vault_grants` (never edit an old one: it is what people agreed to).

## For builders

- Tests: `pg_ctlcluster 16 main start`, then in `server/tests` run `npm ci` and `npm test`. It makes a scratch database
  on `postgres://postgres:postgres@127.0.0.1:5432`, loads `tests/stub.sql` (a stand-in for Supabase's own schemas) and
  the migrations as a role that is not a superuser, runs every `*.test.mjs`, and drops the database. `npm test -- slice-b`
  runs one file. Another Postgres: set `DATABASE_URL` to a superuser login.
- CI: the job `accounts-server` in `.github/workflows/build.yml` starts a local Supabase on GitHub's runner, runs the
  same tests on its Postgres, then `live.mjs` against its API, mail catcher and functions. It holds up nothing.
- Rules every function keeps: in schema `public`, `security definer`, `set search_path = ''`, returns `jsonb`, first
  statement `mm_private.caller()` (unless the contract marks the call anon), locks the row a count hangs on before
  reading the count, fails with `raise exception using errcode = 'P0001', message = '<code>'`. `00-catalogue.test.mjs`
  reads Postgres's own catalogue and fails on any function or table that breaks them.
- The `svc_` functions are only for the Edge Functions (the secret key). No app can call them.
