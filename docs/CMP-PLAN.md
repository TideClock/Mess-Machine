# Screens and app logic to Compose Multiplatform (issue #25)

Nov 24 2026. From the iOS chat, approved by Tyler. The second half of `docs/handoff/IOS.md`: the screens and the app
logic move into shared Kotlin, so the iPhone shows the same UI through `ComposeUIViewController`. One dedicated job,
in small steps. Each step is one push, and each one must keep the big test, the audit, the phone test and the engine
test green before the next starts.

## The rule for every step: Android looks and behaves exactly the same

- The big test's screenshots are the reference. A step adds a screenshot comparison against the last published
  `test-screens` zip (step 1); a screen that changes by more than anti-aliasing fails the step unless the step says why.
- No behaviour change rides along with a move. A bug found during a move gets its own commit.
- Test tags stay exactly as they are (`Modifier.tagged`), so the iPhone UI test finds the same controls.

## The shape when it is done

- `core/` (engine and cloud client, common Kotlin) stays as it is.
- A new KMP module `ui/` (Compose Multiplatform; not `shared/`, which already holds the platform contract): AppState, the screens, Faq, Tutorials, resources. It depends on
  `core` with `api(...)` and is the one place iOS builds a framework from.
- **One static framework, named `MessMachine`,** built by `ui` with `export(project(":core"))`, so the engine and
  the screens share one Kotlin runtime. `core` stops building its own `MessEngine` framework in the same step that
  `MessMachine` appears (the iOS chat updates `tools/build-engine.sh`).
- `ui/src/iosMain`: `fun MainViewController(platform: Platform): UIViewController`.
- `app/` keeps only Android: `MainActivity`, the Android `Platform`, the updater, notification permission, display
  refresh, and the tests.

## The Platform interfaces (common), each implemented in app/ (Android) and in Swift (iOS)

| Interface | What it covers | Android today |
| --- | --- | --- |
| `AudioOut` | start, stop, rate, buffer frames, underruns, lag line, the fill loop calling `Engine.render` / `renderInto` | AudioOut.kt (AAudio, AudioTrack) |
| `MidiIn` | delivers midiDown, midiUp, midiCc, midiBend | Midi.kt |
| `MicIn` | samples to MouthTracker; Workbench takes and imports as WAV in an app folder | Workbench.kt, MouthMic.kt |
| `Files` | the app's folders, open a mess or sound, share or export a mess, WAV, stems, MIDI | AppState file code, ShareScreens.kt |
| `Background` | keep playing with a Stop control | PlayService |
| `NotesOut` | post an issue, read replies, speech to text | Notes.kt, VoiceNote.kt |
| `Device` | haptics, keep awake, the settings store (prefs), the back action, a main-thread poster, a worker | Activity, SharedPreferences, Handler, executors |
| `Cloud` | CloudHost, SupabaseApi, Realtime: Ktor in common if it builds for both, otherwise this interface | CloudHost.kt, SupabaseApi.kt, Realtime.kt |

## Steps (each one push, each green)

0. **Engine timing check** in `tools/EngineTest.java` (done with this plan): fails when the average block takes over
   5% of real time or 1 in 100 over 25%. Measured here: the Kotlin engine and the old Java engine take the same time,
   about 22 microseconds per 256-frame block, under half a percent of real time.
1. **Build plumbing, no code moves.** Compose Multiplatform plugin and versions matched to the Compose that Android
   uses today (so nothing draws differently), the empty `ui/` module wired into `app`, the screenshot comparison
   step in CI. iOS targets only on a Mac, as `core` does.
   Done Nov 24 as two pushes on the `cmp` branch (it builds and tests but never publishes): 1a the screen gate
   (`tools/screen-diff.py`; the first run seeds the `cmp-ref` release, later runs publish `cmp-diff`; `[screens-ok]`
   in a commit message accepts a change and resets the reference), 1b the module. Compose Multiplatform 1.7.0 asks
   for Android Compose 1.7.1; `app/build.gradle.kts` holds every Compose library at the tested 1.7.0. Moved files
   keep their package (`com.tideclock.messmachine.ui`), so a move changes folders, not code.
2. **Resources.** Fonts, icons and art into `ui/composeResources`; `R.drawable` and `R.font` uses go through one
   lookup. Screenshots must match pixel for pixel.
   Done Nov 25 (run 203): all 482 screens identical. Fonts are `@Composable` getters now, so a Canvas draw reads its
   font in the composable around it. Robolectric starts no content providers, so `TestMessApp` starts Compose
   Multiplatform's resources provider for the tests.
3. **Pure logic.** Faq, Tutorials, BarPlan, Icons' tables, the skins and colours, Theory helpers used by the UI.
   3a (Nov 25, run 207 green): Faq, BarPlan, UiLook and the Icons table. Tutorials reads AppState, so it waits for step 5.
   3b: Widgets (the skins, colours, fonts, keys, cards and text), the tag machinery (`tagged`, TutHi) and tileColor.
   Android's Bitmap and java.util.Random drew the grit; it is now drawn through Compose's own canvas with JavaRandom,
   the same sequence (JavaRandomTest checks 100,000 draws), so the speckle is unchanged. Run 208 green.
4. (progress) 4a, Nov 26: `platform/Settings` (AppState's prefs; Android wraps SharedPreferences call for call),
   `platform/MainThread` and `platform/Worker` (the Handler and the executor). A posted function is its own cancel
   handle (`cancel(f)`), so the old Runnables became function vals. Run 209 green.
   4b: `platform/MFile` and `FileStore`. AppState imports MFile as `File`, so its file code reads as before; Android's
   store is java.io.File (`JavaFileStore`), and `.java()` hands a real File to sharing, zips and the Workbench. Run 210 green.
   4c: `platform/Device` (assets, the speaker's rate and burst, the uptime clock, background play, the share sheet,
   the mic permission, the screen size, the network, the clipboard), AndroidDevice making the same calls as before.
   4d: `platform/AudioOut` (AndroidAudioOut inside is unchanged). Runs 211 and 212 green.
   4e: `SpeechIn` (VoiceNote), `MouthIn` (MouthMic) and `AppUpdater` (Updater; iOS's will find nothing, the App Store
   updates it). The shared module now depends on `core` (`api`). Run 213 green.
   4f: `Packs` (AndroidPacks) and `NoteBook` (NotesBook); MNote, the note categories and REPO moved to shared code. Run 214 green.
   4g: `Bench` (Workbench) with BenchItem in shared code. Picked files travel as the platform's address in a String
   (`importUri`, `importSounds`, `Bench.decode`), and `Device` reads them (`readUri`, `displayName`).
4. **The Platform interfaces** in common, the Android implementations wrapping today's classes. AppState still in app/,
   now calling the interfaces instead of Android directly.
5. **AppState to commonMain.** `java.io.File`, `Handler`, executors and `SharedPreferences` are gone from it by then.
6. **Screens, a few files per step:** Widgets and Icons first, then the pages (Pads, Synth, Steps, Roll, Song, Perform,
   Sounds), then the sheets and overlays (Doctor, Settings, Files, Share, Cloud, Notes, FAQ, Tutor).
7. **iOS entry point** (`MainViewController`) and the `MessMachine` framework; the iOS chat hosts it.
8. **Cloud:** Ktor in common if it builds for iOS and Android with the same behaviour, otherwise behind `Cloud`.

## Risks

- [Likely] Compose Multiplatform pins its own Compose version. If it is newer than the Android Compose the app uses
  today, text and layout can shift by a pixel. Step 1 picks the version that matches, or shows the differences before
  anything else moves.
- [Certain] Every step is a full CI cycle (about 20 to 25 minutes), and the Android build cannot run in the cloud
  workspace, so a compile error costs a cycle.
- [Guessing] AppState (5,100 lines) is the longest step; it may need to be split by area (playing, files, cloud,
  lessons) to stay small.
