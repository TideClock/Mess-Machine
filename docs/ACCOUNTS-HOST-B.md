# Driving SyncSession from the Android app (Slice B), from the core builder (Nov 24 2026)

- Local edit: call `sync.hint(Ops.padPut(p))` (or `loopLen`, `loopCopy`, `loopClear`, `padSwap`) before a structural
  mutation. Then mutate, then `changed()`: rebuild the engine snapshot (seq++), schedule the save,
  `sync.localChanged(commit)`, then `arm()`. Motion writes in `AppState.tick` must also go through `changed()`.
- `arm()`, at most once a frame: `p = sync.preview()`. If it is not null: `Snapshot.build(p)`, set its `seq` to the
  current local-rebuild count, `engine.setSnapshotAtWrap(s, ++token)`, and remember `sync.previewRev` for that token.
  Run it at the end of `changed()`, from `onHeld()`, and when a sound finishes downloading while playing.
- Loop top: the frame loop calls `sync.tick()` and watches `engine.swapToken`. When it equals an armed token, call
  `sync.loopTop(<the rev armed with that token>)`.
- `setPlaying(true/false)` on Play and Stop (Stop releases at once). `background()` on pause, `close()` on leaving the
  mess. `replace(p)` swaps the project, rebuilds the snapshot, redraws and saves.
- Tests: tools/sync-check.sh (402 checks, a 4-phone fuzz over 200 seeds, a plain-Project oracle and a bounce oracle).

## What the Android app added while building Slices B to F (Nov 24 2026), for the iOS and web builders

- **Which mess gets a session.** The open mess, when it is linked to a cloud mess, the account is signed in, no lesson
  is running, and the mess is "edited by ops": its row says more than one member, or this phone made an invite for it,
  joined it by invite, or ever ran a session on it (kept in settings, so it sticks). Everything else stays on Slice A's
  whole-mess saves. The check runs every frame (`AppState.syncTick`); the session closes before the screen's mess is
  swapped for another, while the old mess is still on screen.
- **The store leaves those messes alone** (contract s4.4 rule 8): the host sets `CloudStore.byOps`, a function from a
  cloud id to "edited by ops". For such a link `saved()` only queues its sounds (upload and attach, never `put_doc`),
  pull never takes the server's doc, `open()` accepts `doc_rev < rev`, and `soundsChanged(localId)` reads the sounds
  list again. Call it when a pad names a `bn:` key this phone does not have (the app asks every 5 s while one is
  missing, and at once on a `meta` `sound` broadcast).
- **The mess file follows the screen.** While a session is open the app's autosave also writes the mess's own file, and
  once more at close. `SyncSession.open` then finds the file equal to one of its marks and sends nothing old.
- **A first open keeps unsaved work.** With no cache for the mess, the first `replace` shows the server's mess. If the
  screen differed from the mess file (edits never saved), the app first keeps the screen as "<name> (my copy)".
  "Invite to edit" on the open mess saves the screen online first, so the owner never sees that copy.
- **Slice C needs three small things from the session:** `nudge()` (an `ops` or `meta` broadcast came: poll at the next
  tick), `socketJoined` (the poll drops to `POLL_JOINED_MS`), `memberCount()`. The topic comes from the `open_mess` and
  `ops_since` answers: the app wraps the `CloudApi` it hands the session and reads `topic` and `members` as they pass.
  Broadcast batches are not applied directly; they only cause the poll, so the log stays in server order.
- **Presence:** page is `edit` while a pad's Edit tray is open (pad is that pad), else the tab's name; pad is the
  selected pad. "Mia is shaping this too" shows when a peer's page is `edit` on the same pad.
- **Tags decided while building:** `share-link-off`, `share-link-view`, `share-link-copy`, `share-link-send`,
  `share-link-new`; `shared-open`, `shared-mine`, `shared-no`; `report-mess`, `report-user-<uid>`, `report-r-<reason>`,
  `report-send`, `report-send-block`, `report-no`; `del-send`, `del-code`, `del-go`, `del-no`; `acct-review`,
  `acct-review-pass`, `acct-review-go`. A non-owner blocks a person through "Send and block" on a report (`mem-block`
  stays cut).
- **The review account** signs in through the store's code step: the app hands the store a `CloudApi` whose
  `verifyCode` sends a code that starts with `pw:` to `signInPassword`. Nothing else changes for the store.
- **Not built:** taking a Vault submission back (`vault_withdraw` is not in `CloudApi`; the rulings cut `vault-mine`).
  The Workbench row shows the status from `my_sounds` instead.
- **One mess changed on two of my phones (Nov 24, Tyler's answer 7).** When the clash is on a mess I own, the store
  keeps both as before but posts `N_TWO_PHONES` and sets `two` (main id, copy id, name, `canMerge`) instead of
  `N_KEPT_BOTH`. The host shows a question and calls `settleTwo(TWO_KEEP_BOTH | TWO_THIS_ONE | TWO_MERGE)`. The store
  keeps the last doc it and the server agreed on in the cache (`<cloud id>.base.json`), so Merge can lay this phone's
  changes since then (`Ops.diff`) on the other phone's doc. Keep this one and Merge write the main mess with
  `takeServer`, keep the copy off the cloud (deleting it online if it went up) and call the new `StoreHost.dropLocal`
  for its file. tools/CloudTest.java checks all three answers.

