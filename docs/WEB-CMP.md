# The web build: the shared app in a browser

Nov 26 2026. The web version of the Mess Machine runs the same AppState and screens as Android and the iPhone
(Compose Multiplatform on WebAssembly), the way iOS does through `MainViewController`. This replaces the hand-ported
web app on the `web-engine` branch, which can no longer follow the app (its engine was the Java one, now gone).

- Build: `./gradlew -Pmm.web :ui:wasmJsBrowserDistribution` (the `wasmJs` targets in `core/` and `ui/` exist only
  with `-Pmm.web`, so the Android and iOS builds never need them). CI: `.github/workflows/web-cmp.yml` on the
  `web-cmp` branch pushes the page, its log and status to the `web-cmp-dist` branch.
- The browser's side: `ui/src/wasmJsMain`. `platform/Web.kt` holds every call into the page; the page's own helpers
  (files, assets, Back, the picker, the mic, the AudioWorklet) are in `resources/index.html` as `window.mm`.
- Files live in the page and last in IndexedDB; settings in localStorage. The sound: a timer renders the engine about
  60 ms ahead and an AudioWorklet plays the blocks.
- First version placeholders: the Workbench (import, mic), notes, packs, speech, the updater, the mouth mic and the
  speaker test. MIDI is not wired yet.

## Status (Nov 26 2026, night)

- Tyler's phone, claude.ai artifact (CI run 4): loads, pads sound, taps feel 100 to 150 ms late (the lead was 80 ms).
- Hubbub: https://universe-canon.netlify.app/play/mess-machine/latest/ (the zip from web-cmp-dist, Library card
  `Mess_Machine_01_Web_Build_v1`). Hubbub plays games in a sandboxed frame with an opaque origin: no IndexedDB (saved
  files under 96 KB go in localStorage, which Hubbub keeps per player, 1.5 MB in all; mic takes and imports last only
  until the page closes there), no mic, no Web MIDI. Files there are served through a function, so the engine files
  go in parts of at most 1.5 MB (`tools/web-pack.py`).
- claude.ai artifact: no mic for the page (Claude's `dictation` capability gives quick notes words instead), saves
  only through the `downloads` capability and only some types (WAV, MIDI, .mess go out in a zip).
- Built: the Workbench (imports decoded by the browser into WAVs; mic takes with Android's auto sample and trim),
  notes to GitHub (key or the new-issue page), Web MIDI, speech for quick notes, fallback fonts for the symbols and
  emoji (`mm_symbols.ttf` from DejaVu Sans, `mm_emoji.ttf` from Noto Emoji, both subset), the sound lead (Tight
  30 ms up to 180, Steady 60 up to 300; `?lead=` sets the start for tests).
- Where the page may not use the mic (Hubbub's frame, claude.ai), recording opens the phone's own recorder (a file
  input with `capture`) and the take comes back like an import; a blocked mic counts as allowed so quick notes reach
  dictation. The recorder only opens inside the tap; past that a notice asks for one more tap.
- Not built: the updater (a page is always the newest), the mouth mic, packs (the release has none), accounts and
  sharing (no cloud API on web yet), the speaker test.
- Headless Chromium on a cloud box (software drawing) settles at a 165 to 180 ms lead while playing; a phone with a
  GPU should settle far lower. The lag figure under the tempo shows it.

## Status (Nov 26 2026, the spike)

- CI run 4 builds the page: two WebAssembly files (8 MB and 5 MB), mm.js (0.5 MB), the app's fonts and art (2 MB)
  and its sounds (18 MB). Locally in Chromium it opens on the app's own start guide in about 1.3 s after the
  sounds load, and every lesson and screen is the app's. With the sound 80 ms ahead, 0 gaps in a 3 s check.
- claude.ai artifact: https://claude.ai/artifact/JKpgnmjpwvkkH2xAFT3DAq (private). An artifact takes neither
  .flac nor .wasm, so `tools/web-pack.py` turns both into base64 JSON and adds a fetch shim. Not yet opened on a
  phone.
- Next: the Workbench (import, mic), saving files out through the artifact's downloads capability, MIDI, notes,
  then putting it in the site's machine room in place of the hand-ported web app.
