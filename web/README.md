# Mess Machine on the web: putting it on Netlify

This folder holds two small websites. They are plain files. Nothing has to be built.

| Folder | What it is | Who sees it |
| --- | --- | --- |
| `web/account/` | Delete your account, share link page, invite link page, privacy, terms | Everyone |
| `web/vault/` | The Vault desk: listen to offered sounds, approve or decline, answer reports | Only you |

They are two sites on purpose. The share page shows names typed by strangers, so your keeper sign-in lives on
a different address where those names never appear.

Until you fill in `config.js`, every page says "Not set up yet" and calls nothing. It is safe to put them up early.

Tags: [Certain] tested here, [Likely] how the site worked when this was written, the buttons may have moved.

## What you need first

- Your Netlify login.
- The Supabase project made (the server builder's handoff covers that). From it you need two things, both under
  Project Settings in Supabase [Likely]: the **Project URL** (reads like `https://abcdefgh.supabase.co`) and the
  **publishable key** (starts with `sb_publishable_`). Never use a key that says "secret" or "service role".
  The pages refuse to run with one. [Certain]

## Step 1. Make the account site

1. Go to app.netlify.com and sign in.
2. Press **Add new site**, then **Import an existing project**. [Likely]
3. Pick **GitHub**, then the repository `hubbub0/Mess-Machine`.
4. Branch to deploy: `main` (use `accounts` until that branch is merged).
5. **Base directory**: `web/account`
6. **Build command**: leave it empty.
7. **Publish directory**: `web/account`
8. Press **Deploy**.
9. When it is done, open **Domain management** and give the site a name you like, for example
   `messmachine`. Its address is then `https://messmachine.netlify.app`. Write this address down. It is the
   "site host" the app builder needs (step 6).

## Step 2. Make the Vault site

1. Do step 1 again with the same repository, but type `web/vault` in both **Base directory** and
   **Publish directory**.
2. Give it a name that is hard to guess, for example `mm-desk-7q2k`. Do not link to it from anywhere.

## Step 3. Fill in config.js (two copies, the same)

Open `web/account/config.js`. On GitHub you can press the pencil to edit it in the browser. Fill the lines:

| Line | Put in |
| --- | --- |
| `MM_CLOUD_URL` | The Project URL from Supabase |
| `MM_CLOUD_KEY` | The publishable key from Supabase |
| `MM_SUPPORT_EMAIL` | The email people write to for help |
| `MM_DEVELOPER` | Your developer name, exactly as on Google Play |
| `MM_PLAY_URL` | The app's Google Play address, once it has one. Leave empty until then. |

Then make `web/vault/config.js` exactly the same. Copy the whole file across. The test fails if the two differ.

## Step 4. Name your server in the two security lists

Each site has a file called `_headers`. It tells browsers the pages may talk to one server only.

1. Open `web/account/_headers`. Find `https://YOUR-PROJECT.supabase.co` (it is there once).
2. Put your Project URL in its place. No slash at the end.
3. Do the same in `web/vault/_headers`.

If you skip this, the pages show: "This site's security list does not name its server yet."

## Step 5. Save and check

1. Commit the changes. Netlify puts both sites up again by itself in about a minute. [Likely]
2. Open `https://<your account site>/delete/`. You should see the email box, not "Not set up yet".
3. Open your Vault site. Sign in with your own email. If it says "This page is only for the Vault keeper",
   your account is not marked as an admin yet. That is one row in the server's `admins` table. Ask the server
   builder (or Claude) to add you.

## Step 6. Tell the others

- App builder: the site host is your account site's address without `https://` (for example
  `messmachine.netlify.app`). It goes in `mm.siteHost`.
- Google Play, Data safety form: the delete address is `https://<your account site>/delete/`.
- Google Play, store listing: the privacy address is `https://<your account site>/privacy/`.

## Step 7. Links that open the app (can wait)

Share and invite links work without this: the page shows an "Open in Mess Machine" button. To make links
open the app directly:

1. Android: open `web/account/.well-known/assetlinks.json` and replace
   `REPLACE_WITH_THE_SHA256_FINGERPRINT_OF_THE_APP_SIGNING_KEY` with the app signing key's SHA-256
   fingerprint. In Play Console it is under **Setup**, **App integrity**, **App signing** [Likely]. It reads
   like `AB:CD:12:...` with 32 pairs.
2. iPhone: open `web/account/.well-known/apple-app-site-association` and replace
   `REPLACE_TEAM_ID.REPLACE_IOS_BUNDLE_ID` with your Apple Team ID, a dot, and the iOS app's bundle id.

## Step 8. Read the two drafts

`/privacy/` and `/terms/` are drafts with a red box at the top. Before they go live:

1. Read both.
2. Fill the yellow blanks. The developer name and support email come from `config.js` (step 3). Two are in
   the pages themselves: where the server is (privacy), and whose law applies (terms).
3. Take the red box out of each page (the `<aside class="draft">` block), or ask Claude to.
4. The terms version on the page (2026-11) must match the server's `terms_version`. The Vault grant version
   must match the server's too; the terms page checks that itself and says so in section 8.

## A fuller Vault grant, if you want one

The server starts with a short grant (version 2026-11) and the terms page shows the same words. If you want
it spelled out more, here is a suggestion. Using it means a new version on the server (a new row, never an
edit), and the terms page then shows it by itself.

> The Vault is Mess Machine's shared shelf of sounds. Giving a sound to the Vault is your choice. When you
> give one and it is accepted, this is the deal:
> 1. You made this sound yourself, or you have every right to give it. It does not copy anyone else's recording.
> 2. It stays yours. You can keep using it any way you like.
> 3. You give the maker of Mess Machine permission to keep it, copy it, change its file format and put it in
>    Mess Machine and its sound packs, on every platform. Everyone who uses Mess Machine may use it in their
>    own music, including music they sell. This permission is free, covers the whole world, lasts for good
>    and cannot be taken back.
> 4. Your credit name is shown with the sound.
> 5. An accepted sound stays in the Vault even if you delete your account. Your credit name stays with it.
>    A sound that is still waiting when you delete your account is withdrawn and deleted.
> 6. While a sound waits, the Vault keeper listens to it to decide. We may say no to a sound, or take one
>    out of the Vault, at any time.

## For whoever builds here next

- Test: `node web/account/test/run.mjs` (needs the `playwright` package). It serves both folders the way
  Netlify would, runs a fake Supabase in memory and drives every page at 360 and 1280 px. Screenshots go to
  `$MM_SHOTS`, or the temp folder.
- `mm.js`, `mm.css`, `config.js` and `fonts/` in `web/vault/` are copies of the ones in `web/account/`. Change
  the account copy, then copy it over. The test checks they match.
- No inline script or style, no HTML built from strings, no outside files. The test checks these too.
- The contract is `docs/ACCOUNTS-API.md`. Invite links are served at `/join/<token>` and at `/j/<token>`.
- Fonts: Rubik Distressed and Archivo are cut down to the letters used (SIL Open Font License). Share Tech
  Mono is the app's file, unchanged.
