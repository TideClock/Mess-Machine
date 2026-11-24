# Accounts: what the reviews changed (rulings, Nov 24 2026)

Two critical reviews of `docs/ACCOUNTS-SPEC.md` are in this folder (`ACCOUNTS-REVIEW-A.md`: server and safety;
`ACCOUNTS-REVIEW-B.md`: sync, client and app). Both said "build after the must-fixes". These are the rulings. Where this
file, a review and the spec disagree, this file wins, then the reviews, then the spec.

## Accepted in full

- Review A: M1 to M7 and S1 to S10.
- Review B: M1 to M5 and S1 to S12.

Every fix is taken with the reviewer's "better way" as written, unless a line below changes it.

## Build order (replaces spec section 9)

Slices, each shippable and tested alone. The app shows no account feature unless a server is configured.

- **Slice A: my messes online, on two phones.** Sign in with an email code, terms, Save online, one mess list with a
  cloud mark, open on another phone, user sounds uploaded as WAV. Whole-mess saves with a compare on rev
  (`put_doc(mess, doc, base_rev)`; on a clash keep both). No ops, no SyncSession, no engine change, no mess format
  change (the sounds list is kept beside the mess, not in it; the format stays v3), no websocket, no OkHttp.
- **Slice B: two people in one mess.** Single-use invite links, join, leave, remove, block. Ops and SyncSession with
  Review B's M1 to M5. The engine hook (`setSnapshotAtWrap` as Review B's M2 writes it). Transport is `ops_since` every
  2 seconds; decision 2 hides the delay, since other people's edits wait for the next loop anyway.
- **Slice C: live feel.** Websocket (OkHttp), presence with the rotating channel key (Review A S3), "who is here".
- **Slice D: share links (view and copy) and History.**
- **Slice E: store requirements.** Delete account in the app and on the web, report a mess or a person, block, terms
  wording, the review account, privacy policy text.
- **Slice F: the Vault.** Submit in the app, the admin review page. Tyler asked for it, so it is not cut, only last.
  Only Tyler's account approves (he confirmed).

## Cut or deferred (not built now)

- Handing a mess to an editor when its owner deletes their account (`transfer_mess`): delete with a warning instead;
  editors can Duplicate first.
- Email-locked and multi-use invites: single-use links only.
- The viewer role by invite and `set_role`: an invite makes an editor; the View share link covers watching.
- Sounds over 100 seconds: 100 s and 20 MB for every sound now. Tyler's decision 3 (song-length live takes) is met
  when the long recorder is built, right after this, with one config row.
- The FLAC writer: WAV first (250 MB of 16 bit mono WAV is 45 to 49 minutes). FLAC later to halve storage.
- The pglite test runner (Postgres 16 is installed here), the web `/` account page, redo in shared messes.
- The long-gap question: over the limit, keep mine as a local copy and load the server's, no question.
- From the play screen: the `next-loop` count and the `pad-lock` mark. A person's colour shows in the Edit tray, with
  one line ("Mia is shaping this too"), no question.
- From the sheets: `mess-where`, `omess-keep`, `hist-save`, `share-invite-email`, `share-invite-role`, `acct-sounds`,
  `acct-colour` (a colour comes from the user id), `vault-mine`. `mem-block` folds into the Remove question.

## Tyler's answers (Nov 24 2026)

1. Song-length live takes: the recorder is its own piece, built right after accounts.
2. A Vault submission still pending when its submitter deletes their account is withdrawn and deleted.
3. Two phones on one account editing the same mess merge, like two people.

## Test additions that are now required

- Server: the two-connection race per limit (A M1), the table-driven "a stranger gets an error and changes nothing"
  check, the catalogue checks (A S9), migrations run as a non-superuser.
- Core: kill-and-reopen from the cache, "changed nothing sends zero ops", the plain-Project oracle and the bounce
  oracle, the EngineTest cases for the armed snapshot (B S10).
- CI: a job that runs `supabase start` on GitHub's runner and the live checklist (spec 8e steps 2 to 9) against it with
  its mail catcher. It does not hold up the phone build.
