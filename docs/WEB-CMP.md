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
