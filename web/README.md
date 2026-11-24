# Mess Machine on the web

The web build of the Android app, for the Neat Shit and Dye site. Branch `web-engine` only; `main` and the APK
builds are untouched.

- `engine/`: compiles the app's own engine (`app/src/main/java/.../engine/`) to JavaScript with TeaVM.
  `WebEngine.java` is the door the page talks through. Its edit functions are line-for-line ports of the matching
  `AppState` functions, so a tap on the web changes the mess exactly as on the phone.
  GitHub Actions (`.github/workflows/web-engine.yml`) builds it on every push to this branch, checks it against
  the same engine on the JVM (`test/Golden.java`, `compare.mjs`: the demo mess must match within 1e-4) and commits
  `engine/out/` back to the branch. The cloud workspace cannot reach Maven Central, so this is the only build path.
- `app/`: the page. `build.sh` makes `app/dist` (page, engine, the app's icons, fonts, `library.json`, and the 313
  sounds as base64 FLAC chunks, because artifacts cannot serve `.flac`). `dist` is published as the claude.ai
  artifact "Mess Machine (web)".

## Web 1 (Nov 23 2026)

Built: the panel Look top bar, tab slot and dock; pads with multitouch, swipe-to-cancel and swipe up for edit;
banks, Click and Count-in; Rec with one-undo takes; Hold; Repeat (rate and feel); Mute mode; the loop strip
(A to H, Wait bar, 1 to 8 bars, Copy to, Clear, song painter); Steps with accent and ghost cycling, drag paint,
playhead and bar pages; master throws (9 vials) and the master XY filter; Sounds by jar with search and preview;
pad edit sheet (doctor's basic fields, mute, solo, clear); tempo with tap and swing; key and scale; messes in the
browser, open file, copy and paste; Notes for Claude (to the artifact's db); What the FAQ with the app's own lines.

Not yet: Synth page, Roll, Padiano and Chords, Pack strips (FX, LFO, XY per pad), Drop, step sheet, the sample
sheet's Play page and effects, exports, Learn, MIDI, skins.
