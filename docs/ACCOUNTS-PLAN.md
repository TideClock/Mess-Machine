# Accounts, cloud messes and working together: a proposal

Written Nov 23 2026 for Tyler to decide on. Nothing here is built. Tags: [Certain] means checked, with a link in
Sources; [Likely] means strong reasons but not checked; [Guessing] means an estimate or a judgment call.

## Decided (Tyler, Nov 23 2026)

1. **Email code only to start.** Yes. Google and Apple come later, together.
2. **Live edits shared, live pad hits not.** Yes, and more precisely: when two people play at once, each phone plays
   only its own sound. What the other person records lands on your phone **at the start of the next loop**, so you
   hear it the next time round, never mid-loop. (Edits are held and applied together on the loop's downbeat.)
3. **Sound length.** A sound is capped at 100 seconds, **except recorded live parts**, which may be as long as a song:
   anyone must be able to record a live part over the whole thing.
4. **Pro the day accounts reach the Play Store.** Yes.
5. **Deleting an account removes that person's sounds from other people's messes, unless the sound was submitted to
   the Vault.** Vault sounds stay, with credit to the person, and may be used in new packs. Submitting to the Vault
   therefore needs a clear grant at submission time (credit, use in packs, survives account deletion).
6. **Netlify hosts the web side.** Yes.
7. **Cloud storage: 250 MB per account, full quality.** No compressed takes. [Likely] About 38 minutes of audio at
   roughly 6.5 MB a minute (FLAC, mono 48 kHz), so 5 to 8 song-length takes plus a normal library. Pro's 100 GB covers
   about 400 accounts at the full cap; after that storage costs $0.0213 per GB a month, about half a cent per full
   account. The app shows how much of the 250 MB is used and says plainly when a take will not fit.

## 1. Recommendation

Use one Supabase project for Android, iOS and the web: email code sign-in first (Google and Apple later, together),
messes saved as the same mess JSON in Postgres, your own sounds as FLAC files in Storage, and nothing changes for
people who never sign in. Build working together as small edit operations that the server puts in order and sends
out over Realtime, last writer wins per field, with soft locks per pad, each phone playing its own audio on its own
transport. Skip CRDT libraries for now, because none of them has a working Kotlin side for the shared core, and a
drum machine's mess is a set of small fields where "last writer wins" is the right answer anyway.

## 2. Accounts

### Sign-in methods

- **First: email code only.** You type your email, get a six digit code, type it in. No password, no links to catch,
  so it works the same in the Android app, the iOS app and a web page. [Certain] Supabase sends a code instead of a
  link when the email template uses `{{ .Token }}`, and both the Kotlin and Swift libraries have the calls
  (`signInWith(OTP)` and `verifyEmailOtp` in Kotlin, `signInWithOTP` and `verifyOTP` in Swift). Codes expire after
  an hour by default and a person can ask for a new one once a minute.
- **Email needs a real mail service.** [Certain] Supabase's built-in email only sends to members of your own Supabase
  team, about 2 an hour, with no promise of delivery. For anyone else you must plug in your own SMTP service. Supabase
  lists Resend, AWS SES, Postmark, SendGrid, ZeptoMail and Brevo. This is the one thing that blocks even a test with
  friends.
- **Later: Google and Apple, added together on all three platforms.** [Certain, from the facts checked today] Apple
  guideline 4.8: if the iPhone app offers Google sign-in, it must also offer an equal private option such as Sign in
  with Apple. Email-only does not trigger it. So email-only first keeps the iOS port free of this rule.
  - [Certain] Google on Android uses Android's Credential Manager and hands Supabase an ID token. It needs three
    Google OAuth client IDs (web, Android with the signing key's SHA-1, iOS with the bundle ID).
  - [Certain] Apple on iPhone is native. On Android and the web it is a web login page, which needs an Apple
    Services ID and a signing key, and Apple makes you make a new secret every 6 months or sign-in breaks.
  - [Likely] If a person signs in with Apple on the iPhone and wants the same account on Android, Android must offer
    Apple too. That is why Google and Apple should land on all three platforms in one step, not one at a time.
- **No passwords for normal people.** [Likely] App reviewers at Google and Apple need a test account they can get
  into without your inbox. Plan a hidden email and password login for one review account only.

### Guest mode

- The app stays fully usable without an account, as it is today. Local messes, the Workbench, packs, exports,
  sharing a mess file: all keep working. [Certain] Today messes live in `files/messes/*.json` and
  `files/current.json`, and Workbench sounds in `files/bench/` (see `AppState.kt` and `Workbench.kt`).
- Signing in adds three things only: cloud copies of your messes, share links, and editing with others.
- **Do not use Supabase's anonymous accounts for guests.** [Certain] Anonymous users are real rows in the database
  that need captcha protection and manual cleanup, and Apple's deletion rules cover guest or auto-made accounts too.
  A guest with no account at all has nothing to delete and costs nothing. [Guessing] It also keeps guests out of the
  monthly active user count; the pricing page does not say whether anonymous users count.
- A guest who opens a share link can listen to it and can copy it to their phone as a local mess. Editing with others
  needs an account.

### Moving existing local messes

- On the first sign-in the app asks once: "Put your 12 messes in the cloud? All / Pick / Not now." Each one uploads
  as a new cloud mess. The local file remembers its cloud id, so nothing is duplicated if you ask again.
- **The phone stays the first place a mess is saved.** Every save still writes the local file first (the crash-safe
  `.new` then rename from build 105), then sends it up. Offline, it waits and goes later. This means signing in can
  never lose work.
- Workbench sounds used by those messes upload in the background, on Wi-Fi by default. Sounds not used by any mess
  are asked about separately ("Back up your Workbench too?").
- Same account on two phones: if both changed the same mess while apart, you get both ("Basement jam" and
  "Basement jam (from Pixel)"), never a silent overwrite.
- Signing out keeps every local file. The cloud copies stay in the account.

### Deleting an account

- [Certain] Google Play: an app that lets people make accounts must offer deletion inside the app and on a web page,
  and the Data safety form must answer the deletion questions. Apple (5.1.1(v)) also requires deletion inside the app,
  of the whole account, not a "deactivate", and says content the person shared with others should go too. Deletion
  may take time if the person is told how long and gets a confirmation.
- **In the app:** Settings, Account, Delete account. It lists what will go (your cloud messes, their history, your
  uploaded sounds, your share links, your memberships) and what stays (the messes on this phone, which become guest
  messes). You confirm with a fresh email code.
- **If you own messes other people edit:** before deleting, the app offers to hand each one to one of its editors.
  Anything not handed over is deleted.
- **On a web page:** a small page (on Netlify, see question 6) where you sign in with an email code and press Delete,
  plus a support email for people who cannot sign in.
- **How it runs:** [Certain] deleting a Supabase user needs the secret service key, so it must run on the server,
  never in the app. A Supabase Edge Function `delete-account` checks the caller, deletes their rows and files, then
  deletes the auth user. With Sign in with Apple it must also revoke Apple's tokens.
- [Likely] Bug notes are GitHub issues, not tied to the account, so deletion does not touch them. Say so in the
  privacy policy.

## 3. Data model

### Tables

| Table | What a row is | Main columns |
|---|---|---|
| `profiles` | One per account | `id` (same as the auth user), `handle` (unique), `display_name`, `colour`, `sound_bytes` (used), `created_at` |
| `messes` | One cloud mess | `id`, `owner_id`, `name`, `doc` (the mess JSON, as today), `doc_rev`, `rev`, `format_v`, `forked_from`, `share_mode` (off, view, copy), `share_token`, `updated_at` |
| `mess_ops` | One batch of edits, in server order | `mess_id`, `rev` (1, 2, 3 ...), `user_id`, `ops` (JSON list), `at` |
| `mess_versions` | A saved point in a mess's history | `mess_id`, `rev`, `doc`, `made_by`, `label` (empty, or "Saved", "Before restore"), `at` |
| `mess_members` | A person on a mess | `mess_id`, `user_id`, `role` (owner, editor, viewer), `added_by`, `added_at` |
| `invites` | An invite link or an emailed invite | `id`, `mess_id`, `role`, `token_hash`, `email` (optional), `expires_at`, `uses_left`, `revoked` |
| `sound_files` | One stored audio file, by content | `sha256` (key), `bytes`, `seconds`, `rate`, `uploaded_by`, `at` |
| `sounds` | A person's own sound (a Workbench item) | `id`, `owner_id`, `sha256`, `local_key` (like `bn:mf3k2a417`), `kind`, `title`, `bpm`, `beats`, `mkey` |
| `mess_sounds` | A user sound a mess needs | `mess_id`, `sound_key`, `sound_id`, `added_by` |
| `reports` | "Report this mess" | `mess_id`, `reporter_id`, `reason`, `at` |

How the mess itself is kept:

- `doc` is the same mess JSON the app writes today (`Project.toJson`), so the file format stays the one contract
  (`shared/MESS-FORMAT.md`). [Certain] A busy-looking demo mess is about 4.4 KB (measured today with
  `Project.demo().toJson()`). [Guessing] A full mess with 8 loops, notes, locks and synths is 20 to 80 KB.
- `rev` counts edits. `doc_rev` says which edit `doc` already includes. A mess opened later is `doc` plus the
  `mess_ops` after `doc_rev`.
- Fields that belong to one phone are never shared: `sel` (picked pad), `cur` (current loop), `mode`, `at`, `app`.
- History: a version is kept at every "Save" you tap, after 10 minutes of editing, and before a restore or a big
  change (clear loop, swap all pads). Keep the last 30 plus every labelled one. Restoring makes a new version, so
  restore can be undone. Old `mess_ops` are trimmed 7 days after a version covers them.

### Who can do what (row level security in plain words)

[Certain] Supabase enforces these as Postgres policies, per table and per action; a membership check that reads
another table goes in a "security definer" function so policies do not loop; `auth.uid()` is the person asking.

- **Profiles:** anyone signed in can see a handle, name and colour (to show who is in a mess). Only you change yours.
- **Messes:** you can read a mess if you are on its member list. Share link viewers do not read the table; they go
  through a server function `open_shared(token)` that checks the token. Only the owner renames, changes sharing or
  deletes. You can create messes as yourself, up to a cap.
- **Edits:** nobody writes `doc` or `mess_ops` directly. Editors and owners call `apply_ops(mess, ops)`, which checks
  the role, gives the batch the next `rev`, stores it and sends it out. Viewers cannot call it.
- **Members:** members of a mess see its member list. Only the owner adds, removes or changes roles. Anyone can remove
  themselves (leave). Joining happens only through `redeem_invite(token)`.
- **Invites:** only the owner makes, sees and revokes them. The token is stored hashed.
- **Sounds:** you see your own sound rows, plus the rows of sounds used by a mess you can read. You add rows only as
  yourself and only under your quota.
- **Realtime channel `mess:<id>`:** [Certain] private channels check policies on `realtime.messages` when someone
  joins. Only members may join. Viewers can listen and show presence but cannot send edits.

### Where sound files live

- One private Storage bucket, `sounds`. Each file is stored once by its content: `sounds/<sha256>.flac`.
  [Certain] Storage access is also row level security, on `storage.objects`, and the same rules apply to public and
  private buckets; a signed URL is checked when it is made, then works by itself.
- Read rule: you may read a file if one of your own `sounds` rows points to it, or a mess you can read lists it in
  `mess_sounds`. Upload rule: signed in, under your quota, and the server checks the hash matches.
- Same content, one file: if two people record or import the same audio, or a mess is forked, the file is not
  copied. A nightly job deletes files nothing points to any more.
- **What needs uploading, by sound key** ([Certain] from `Library.java`, `Workbench.kt` and `Packs.kt`):
  - Built-in sounds (`samples/...`, `qb:...`): nothing. Every app has them.
  - Pack sounds (`pack:<id>:<clip>`): nothing. Packs are free on the public `packs` release; the other phone
    installs the pack when it opens the mess.
  - Synths (`synth:<n>`): nothing. The patch is inside the mess JSON.
  - Chops (`chop:<id>`): no file; the start and end ride in the mess, and the sound it cuts from comes along.
  - Your Workbench sounds (`bn:<id>`: imports, mic takes, resamples, saved edits): these upload.
- **How a shared mess brings its sounds along:** the mess gets a `sounds` map, key to `{sha256, title, kind, bpm,
  beats, mkey, seconds}`. A phone that opens the mess looks up each key. Same hash already on the phone: use it.
  Otherwise download it into a "From shared messes" jar, cached by hash, so each phone downloads each sound once.
  [Certain] Changing the mess format follows `shared/README.md`: bump `v`, keep loading every older version, add a
  fixture. Old apps ignore the new map.
- **FLAC, not WAV.** [Certain] Workbench sounds are 16 bit mono WAV at 44.1 kHz (`Wav.writeMono16`), 88 KB a second,
  and the engine can read FLAC but not write it. [Likely] A small FLAC writer in the engine (fixed predictors and
  Rice codes, testable in `EngineTest`) halves the size, and the iOS and web ports get it through the shared core.

### Size limits (proposed)

| Thing | Limit | Why |
|---|---|---|
| One sound | 100 seconds (decided Nov 23); recorded live parts up to song length | [Certain] the Workbench import already stops at 60 s. About 3 MB as FLAC [Likely]. |
| Your sounds in the cloud | 250 MB per account (decided Nov 23) | [Likely] about 38 minutes of full-quality audio; storage is the first wall (section 6). |
| One mess | 1 MB of JSON | 10 times a big mess. Protects the database. |
| Messes per account | 200 | Protects the database from scripts. |
| People on one mess | 8 members, 4 editing live at once | See section 5. |
| Edit batches | 5 a second per person | Realtime message limits (section 5). |

## 4. Sharing

- **Share link, view or copy.** The owner turns on a link and picks View (listen and look, nothing saved) or Copy
  (listen, then "Make it mine" makes your own copy). The link is a web address, like `https://<site>/m/<token>`. On a
  phone with the app it opens the app (Android App Links, iOS Universal Links); otherwise it opens the web version.
  Turning the link off, or making a new one, kills the old one.
- **Invite to edit.** The owner shares an invite link (works once, or up to N times, expires in 7 days) or types an
  email. The person signs in, taps Join, and is an editor. The owner can make anyone a viewer or remove them. What
  they added stays in the mess.
- **Fork a copy.** "Make it mine" (from a Copy link) or "Duplicate" (any mess you can read) makes a new mess you own,
  with `forked_from` set. Sounds are shared by hash, so a fork costs almost no storage. Changes never flow between a
  fork and the original. [Certain] This is how BandLab does it: the owner must allow forks, and a fork is your own
  version.
- **Public gallery: not in this plan.** [Certain] Google Play's user content policy already applies to shared links
  and invites (content visible to "at least a subset" of users): people must accept terms before uploading, and the
  app must have in-app reporting. So this plan includes terms on sign-up and "Report this mess" on any mess you did
  not make. A public gallery adds browsing strangers' work, which needs real moderation, blocking and probably
  search. [Guessing] Worth it only once there are a few hundred active people. Decide then.

## 5. Working at the same time

### What other apps did

- [Certain] **Ableton Note:** Ableton Cloud syncs your own sets between your own devices, 8 sets at most. It is not
  for working with others; you send a set with "Share To".
- [Certain] **Koala Sampler:** Koala Connect links two Koalas "on the same network" to work on one session or pass a
  song, plus Ableton Link for tempo. No accounts, no internet sessions.
- [Certain] **BandLab:** invites by username or email, edit or view access, forks the owner must allow, and each save
  is a revision. [Likely, from a third-party guide] real time editing "like Google Docs" is limited; people mostly
  take turns and sync, and the advice is to tell your partner before deleting their part.
- [Certain] **Endlesss:** real time jams like a chat room. You played into a looper and committed a "Rifff" of up to 8
  layers; others remixed or added to Rifffs; earlier ones were kept. Free jams held 100 Rifffs. Loops were one bar
  by default. Reviews noted latency when playing in and that you could not see what others were doing. It shut down
  on 31 May 2024 and the apps stopped working, so people had to export their music.
- [Certain] **Figma** (not music, but the closest design): the server decides the order, the last value the server
  gets for a property wins, a client ignores server updates that clash with its own unconfirmed change, and after
  being offline a client loads a fresh copy and reapplies its edits. They looked at CRDTs and chose this because the
  server already exists and it is simpler.

What this tells us: nobody ships true live playing together over the internet in a phone app that lasted. The ones
that work share edits (BandLab, Figma style) or whole loop passes (Endlesss), not live hits.

### The approach

1. **Edits are small operations.** Every change in a shared mess becomes an op in the shared core: turn step 7 of
   pad 5 on in loop B, set pad 12's filter to 0.4, add a note at tick 96 pitch 3, set bar 9 of the song to C, set the
   tempo to 96. Ops name pads by slot (0 to 64), not by sound key, because [Certain] the mess keys steps and notes by
   sound key, and a sound swap would otherwise break someone else's edit in flight.
2. **The phone applies its own ops at once** (no waiting) and sends them in batches every 200 ms.
3. **The server puts them in order.** `apply_ops` gives each batch the next `rev` and stores it, then sends it to the
   mess's private Realtime channel. [Certain] A database function can send a broadcast with `realtime.send()`.
4. **Last writer wins per field.** A field is the smallest thing an op sets: one step cell, one accent, one chance,
   one lock value, one note (a note is its tick plus its pitch), one sound setting, one synth knob, one pad option,
   one song bar, one mess setting. Two people on different fields never clash. Two people on the same field: the one
   the server got second wins. Big ops (clear loop, copy loop A to B, change loop length) are one op each, applied in
   order; a step edit past a loop's new end is dropped.
5. **No flicker.** Like Figma, a phone ignores incoming values for a field where its own op has not come back yet.
6. **The mess catches up from snapshots.** The server stores ops and their order but does not run the engine. An
   editor's phone uploads a fresh `doc` every 50 ops or a minute (`save_snapshot`, accepted only if newer). Someone
   joining loads `doc` and the ops after it. [Likely] This keeps the mess logic in one place, the shared core, instead
   of a fourth copy in SQL. Once the engine compiles to JavaScript, an Edge Function can check snapshots.
7. **Who is here: Presence.** Each phone shares who it is, its colour, its page, its loop, and which pad it is
   shaping. [Certain] Supabase says Presence is for slow-changing state like this, not fast updates.
8. **Soft locks per pad and per loop.** While you have a pad's Edit tray, Sample sheet or synth knobs open, that pad
   wears your colour for everyone else. Someone else who opens it sees "Mia is shaping this pad. Watch, or take
   over?" Step cells are never locked. Clearing a loop or changing its length while someone is in it asks first. Locks
   are advice, not walls: they go away when you close the tray or leave.
9. **Tempo and key are shared fields; play is yours.** Tempo, swing, key, scale and loop lengths are part of the mess,
   last writer wins. Play, Stop, Rec, which loop you hear, mutes and solos stay on your own phone, so muting the kick
   to work on hats does not mute it for your friend.
10. **Follow (later step).** Tap Follow on someone and your Play starts on their next bar line, at their loop or song
   position. Each phone works out the server's clock from a few pings. [Guessing] On a decent network the two phones
   land within about 30 to 80 ms, fine for listening to the same thing, not for playing in time across the internet.
11. **Each phone renders its own audio.** Nothing is streamed. Everyone hears the same notes from the same engine
   (once iOS and web run the shared core). Your live pad hits are heard only by you until they are recorded.
12. **Recording.** Live Rec takes record on your phone and are sent as one batch at the end of each loop pass, so
   others see your hits land pass by pass. A new mic take shows on others' pads as "arriving" until its file uploads.
13. **Undo undoes your own last op**, by sending its opposite as a new op. If someone has changed that field since,
   undo says so and skips it. [Certain] Today's undo keeps whole snapshots of the mess (80 steps), which cannot work
   with others editing, so a shared mess switches to op undo.

### What is limited, and why

- **No live jamming over the internet.** Network delay (often 50 to 300 ms, [Guessing]) makes another person's
  live hits arrive late and wobbly. Endlesss solved this by passing whole loops, and that is the deferred "jam mode".
- **4 people editing live, 8 members.** [Certain] Free Supabase allows 100 Realtime messages a second, Pro 500, and
  each broadcast counts once sent plus once per person receiving it. Four people each sending 5 batches a second is
  up to 80 messages a second for one mess. Presence is limited to 20 updates a second on Free and 50 on Pro, which is
  why only slow things (page, pad you are shaping) go through it. [Likely] These limits are for the whole project,
  not per mess, so they matter more as users grow.
- **Phone settings never sync:** layouts, skins, MIDI maps, HotKeys, the Desk.
- **Realtime only while a shared mess is open.** [Certain] Free allows 200 connected at once and Pro 500, then $10 per
  1,000. A solo mess never opens a Realtime connection.
- **Kotlin library gaps.** [Certain] The Kotlin Realtime library cannot send binary payloads or replay missed
  database broadcasts yet, so ops are JSON and a phone that dropped off catches up by reading `mess_ops`. Also
  [Certain] a database broadcast fails if nobody has connected yet that day, so `apply_ops` must store the op first
  and treat the broadcast as best effort.

### Offline edits and merging

- Offline, ops queue on the phone with the `rev` they were based on.
- Back online: fetch the ops since that `rev`, apply them, then replay your queue on top and send it. Your offline
  edits land last, so they win on any field both of you touched. The app says "3 of your changes replaced Mia's" and
  each one can be undone.
- Gone a long time (over a day, or over 500 ops on either side): the app offers "Merge" or "Keep mine as a copy". The
  copy is a fork, so nothing is lost.

### Server ordered ops or a CRDT?

| | Server ordered ops, last writer wins (proposed) | CRDT library (Automerge, Yjs, Loro) |
|---|---|---|
| Kotlin for the shared core | Plain Kotlin, written once in the shared core | [Certain] Automerge's Java binding is version 0.0.x, wraps native Rust (per-ABI libraries in the APK), and is not Kotlin Multiplatform. Yjs's Kotlin binding (ykt) had its last push in January 2023. Loro has Swift but no Kotlin. |
| Swift | Comes with the shared core | [Certain] Automerge Swift is active; YSwift says "work in progress". |
| JavaScript | Comes with the shared core | [Certain] Automerge and Yjs are mature in JS. |
| Offline merge | Good: replay on top, newest wins per field; long gaps offer a copy | Best: any order, no server needed |
| Server | Supabase as it is | [Certain] Automerge Repo brings its own sync server; Supabase does not run it. Binary sync messages would need Kotlin binary broadcast, which is missing. |
| Fits the mess | Yes: the mess is cells and knobs, where newest wins is the right answer | A CRDT map gives the same per-field result, plus history that keeps growing |

**Choice: server ordered ops.** [Likely] A CRDT would mean three different libraries (or native Rust in three apps),
a second sync server, and a growing history, to win only on long offline gaps, which "keep mine as a copy" already
handles. Revisit only if offline group editing becomes the main way people use it.

### Deferred

Jam mode (pass whole loop passes, Endlesss style), live hits between phones on the same Wi-Fi (Koala style), chat
and comments, push notifications for invites, a public gallery, paid accounts.

## 6. Cost

[Certain] prices from the Supabase pricing page, checked today: Free has 50,000 monthly active users, 500 MB database,
1 GB file storage, 5 GB egress, 200 Realtime connections and 2 million Realtime messages a month, pauses after a week
idle and allows 2 active projects. Pro is $25 a month with 100,000 users, 8 GB disk, 100 GB storage, 250 GB egress,
500 connections, 5 million messages, never paused, $10 of compute credit, and then $0.0213 per GB of storage, $0.09
per GB of egress, $0.125 per GB of disk, $2.50 per million messages, $10 per 1,000 connections, $0.00325 per user.

Assumptions [Guessing]: a person who records keeps about 8 MB of FLAC sounds and about 1 MB of messes with history;
about a third of accounts are active in a month; each active person downloads about 15 MB a month (new phones,
shared messes); a tenth of active people use a shared mess for an hour a month.

| Accounts | Plan | Storage | Database | Egress | Realtime | About |
|---|---|---|---|---|---|---|
| 10 | Free | 80 MB of 1 GB | 10 MB of 500 MB | 0.05 GB of 5 GB | tiny | $0 |
| 1,000 | Pro | 8 GB of 100 GB | 1 GB of 8 GB | 5 GB of 250 GB | tiny | $25 a month |
| 50,000 | Pro plus extras | 400 GB, about $6 over | 50 GB, about $5 over | 250 GB, about $0 to $25 over | peaks near 500 connections, $0 to $10 | [Guessing] $60 to $120 a month, more if the database needs a bigger computer |

Plus, outside Supabase: the email service (free tiers cover a test group; [Guessing] about $20 a month at 50,000
accounts), Apple's developer program ($99 a year, already needed for iOS), and Google Play's one-time fee.

**When Free stops being enough** [Guessing]: sound storage first, at roughly 100 to 200 people who record (1 GB);
egress next, at a few hundred active people (5 GB); the database around 500 people with history. Two things can end
Free sooner:

- **Pausing.** A project with a quiet week pauses and sign-in stops working until you wake it. A test group of 10 can
  easily have a quiet week.
- **Two free projects.** [Likely] You already run Supabase for other apps. If two free projects are active, a third
  needs Pro or one of the others paused.

## 7. What Tyler must do himself

Claude cannot make accounts, keys or paid services. Each step below is yours; Claude can write the click-by-click
guide for any of them when you get there.

Before step 1:

1. **Create the Supabase project** (name it `mess-machine`, pick a US West region). Send Claude the project URL and
   the publishable (anon) key; those are safe in the app.
2. **Keep the secret keys secret.** Put the service role key and a Supabase access token in GitHub (repo Settings,
   Secrets) and in Supabase Edge Function secrets yourself. Never paste them in a chat or a note. Claude's workflow
   then applies the database changes from the repo.
3. **Email:** sign up for a mail service (Resend is one Supabase lists), verify a sending domain (DNS records on a
   domain you own), and paste its SMTP details into Supabase Auth settings. Turn on email codes.
4. **A web home:** a Netlify site (you already use Netlify) for the account deletion page, the privacy policy and
   terms, and share links. It also hosts the two small files that make links open the app
   (`.well-known/assetlinks.json` for Android, `apple-app-site-association` for iOS).

Before Play Store with accounts:

5. **Play Console:** fill Data safety (you collect email, a user id, audio files and messes; deletion is offered;
   the deletion web address), add the privacy policy link, and give reviewers the test account.

Before Google and Apple sign-in (step 9):

6. **Google Cloud Console:** set up the OAuth consent screen, then three OAuth client IDs: Web (with the Supabase
   callback address), Android (with the SHA-1 of the signing key; once on Play, also the SHA-1 of Play's app signing
   key from Play Console), and iOS (with the bundle id). Paste them into Supabase's Google provider.
7. **Apple Developer account:** an App ID with Sign in with Apple, a Services ID for Android and web with Supabase's
   callback address, a signing key (`.p8`) and your Team ID, your sending domain registered for Apple's private email
   relay, and a calendar reminder to make a new Apple secret every 6 months.

## 8. Build order

Each step ships on its own, keeps guests exactly as today, and has tests: the engine side in `tools/EngineTest.java`,
screens in the big test (with a fake cloud), and the database rules in pgTAP tests run against a local Supabase in
GitHub Actions. iOS and web take each step through the shared core when they reach it.

1. **Sign in and account.** Email code sign-in, profile handle, sign out, terms on sign-up, Delete account (in the
   app and on the web page) with its Edge Function. Test: rules allow and deny each table for each role; deletion
   leaves no rows or files behind.
2. **Cloud save for your own messes.** The Messes sheet shows "On this phone" and "In the cloud", saves go up after
   the local save, the first-sign-in move offer, "keep both" when two phones clash. Messes with Workbench sounds say
   their sounds stay on this phone for now. Test: two fake phones, offline and back.
3. **Your sounds in the cloud.** FLAC writer in the engine, the `sounds` bucket, upload on Wi-Fi, one file per hash,
   the mess `sounds` map (format bump and fixture in `shared/`), a quota line in Account. Test: FLAC round trip is
   sample exact; a mess opened on a clean phone has every sound.
4. **History.** A mess's versions list, restore as a new version.
5. **Share links.** View and Copy links, the landing page, links that open the app, "Make it mine", Report this mess.
6. **Invite to edit, taking turns.** Members, roles, invite links. Editors open and save the same mess; a clash
   keeps both. Useful by itself, BandLab style.
7. **Live together, part 1.** Ops in the shared core, `apply_ops`, the Realtime channel, presence (who is here and
   where), last writer wins, op undo. Test: a property test where two simulated phones make random ops in random
   network order and must end with the same mess.
8. **Live together, part 2.** Soft locks, the offline queue and rebase, the long-gap copy, Follow.
9. **Google and Apple sign-in** on Android, iOS and web together, with identity linking.
10. **Later, if wanted:** jam mode, public gallery, invite notifications. Also worth a look: bug notes sent through
    the account, so the app no longer needs a GitHub key pasted in; the server would hold it.

## 9. Deciding questions

1. **Sign-in to start with: email code only, then Google and Apple together in step 9?**
   Recommended: yes. Works on all three platforms with one setup, and avoids Apple's rule until iOS is ready.
2. **How "together" for the first version: shared edits live, each person's own Play, and Follow later, but no live
   pad hits sent between phones?**
   Recommended: yes. Live hits over the internet arrive late; every app that tried live jamming either passed whole
   loops or stayed on one Wi-Fi.
3. **Sound limits for a free account: 60 seconds a sound, 100 MB in total, no paid tier for now?**
   Recommended: yes. Storage is the first cost wall; a paid tier needs Play and Apple billing, a project of its own.
   Decided: 100 seconds a sound, song-length live takes allowed, 250 MB per account at full quality, no paid tier.
4. **When to pay for Supabase Pro ($25 a month)?**
   Recommended: stay on Free for you and a test group, and move to Pro the day accounts reach the Play Store, or
   sooner if your two free projects are already used. Pausing alone makes Free unsafe for real users.
5. **Deleting an account: remove everything the person made, including their sounds inside other people's messes,
   after offering to hand shared messes to an editor?**
   Recommended: yes. It is the strict reading of Apple's guidance, and it is simple to explain. The cost: a friend's
   mess can lose a sound when its maker leaves; the pad will say so.
6. **Where the web side lives: a Netlify site for sign-in, share links and the deletion page?**
   Recommended: yes. [Guessing] A claude.ai artifact is unlikely to work as a sign-in return address or as the home of
   links that open the app, and the web build's first probe (`docs/handoff/WEB-GAP.md`, step 0) checks whether an
   artifact may talk to Supabase at all.

## Sources

Pages opened for this plan (Nov 23 2026):

- Supabase pricing: https://supabase.com/pricing
- Supabase Realtime limits: https://supabase.com/docs/guides/realtime/limits
- Supabase Realtime Broadcast: https://supabase.com/docs/guides/realtime/broadcast
- Supabase Realtime Presence: https://supabase.com/docs/guides/realtime/presence
- Supabase Realtime message counting: https://supabase.com/docs/guides/platform/manage-your-usage/realtime-messages
- Supabase row level security: https://supabase.com/docs/guides/database/postgres/row-level-security
- Supabase Storage access control: https://supabase.com/docs/guides/storage/security/access-control
- Supabase anonymous sign-ins: https://supabase.com/docs/guides/auth/auth-anonymous
- Supabase deleting a user (admin): https://supabase.com/docs/reference/javascript/auth-admin-deleteuser
- Supabase Sign in with Apple: https://supabase.com/docs/guides/auth/social-sign-in/auth-apple
- Supabase Sign in with Google: https://supabase.com/docs/guides/auth/social-sign-in/auth-google.md
- Supabase custom SMTP: https://supabase.com/docs/guides/auth/auth-smtp
- Supabase email codes and links: https://supabase.com/docs/guides/auth/auth-email-passwordless
- Supabase Kotlin reference (community maintained, not official): https://supabase.com/docs/reference/kotlin/v2
- Supabase Kotlin Android tutorial: https://supabase.com/docs/guides/getting-started/tutorials/with-kotlin
- Supabase Swift reference: https://supabase.com/docs/reference/swift/introduction
- Google Play account deletion: https://support.google.com/googleplay/android-developer/answer/13327111
- Google Play user generated content policy: https://support.google.com/googleplay/android-developer/answer/9876937
- Apple, offering account deletion: https://developer.apple.com/support/offering-account-deletion-in-your-app
- Apple guideline 5.1.1(v): https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion
- Automerge: https://automerge.org/
- Automerge Java: https://github.com/automerge/automerge-java
- Automerge Swift: https://github.com/automerge/automerge-swift/blob/0.5.15
- YSwift: https://github.com/y-crdt/yswift
- ykt (Yjs for Kotlin) listing: https://awesome.ecosyste.ms/projects/github.com%2Fy-crdt%2Fykt
- Loro bindings: https://docs.rs/crate/loro-ffi/latest/source/README.md
- How Figma's multiplayer works (Evan Wallace's copy of the Figma post):
  https://madebyevan.com/figma/how-figmas-multiplayer-technology-works/
- Ableton Cloud FAQ: https://help.ableton.com/hc/en-us/articles/7355427492380
- Koala Sampler on the App Store: https://apps.apple.com/za/app/koala-sampler/id1449584007
- BandLab, inviting collaborators: https://help.bandlab.com/hc/en-us/articles/48010528581529-How-do-I-invite-other-users-to-collaborate
- BandLab, forking: https://help.bandlab.com/hc/en-us/articles/48041963115033-What-is-Forking
- BandLab, saving issues: https://help.bandlab.com/hc/en-us/articles/115002945193-Saving-Issues
- BandLab collaboration guide (third party): https://www.audeobox.com/learn/bandlab/bandlab-collaboration-features/
- Endlesss shutting down (CDM): https://cdm.link/2024/05/endlesss-discontinued/
- Endlesss first look (Engadget): https://www.engadget.com/2020-03-31-endless-music-collaboration-app.html
- Endlesss review (MusicTech): https://musictech.com/reviews/software-instruments/endlesss/

Not opened: Apple's full App Review Guidelines page and the supabase-kt GitHub page (both blocked today). The Apple 4.8
rule is cited from the facts checked earlier today in the main session.
