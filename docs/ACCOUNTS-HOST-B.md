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
