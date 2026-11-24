# Mess Machine for iOS: handoff

Written Nov 23 2026, at Android build 170, for the Claude chat that builds the iPhone version. Read this whole file,
then `CLAUDE.md`, `DESIGN.md` and everything in `shared/` in this repo, before touching code.

## What you are building

The iPhone version of Mess Machine, almost exactly like the Android app: same screens, same keys, same gestures, same
sound, same saved messes. Tyler is the only user today. He plays on an Android phone and a foldable, and wants the app
on the App Store and Google Play. He sends notes from the app's bug button; read `## Tyler's notes` in `CLAUDE.md`.

## Where things live

- **`hubbub0/Mess-Machine`** (this repo): the Android app and the source of truth for everything shared: the engine,
  the screens, the mess format, icons, fonts, sounds, the start guide, the FAQ and lessons.
- **`hubbub0/Mess-Machine-iOS`** (yours): the iPhone app. Tyler creates it empty. It holds only what is iPhone-only:
  the Xcode project, the Core Audio, mic, MIDI and file pieces, the iOS build and test workflow, and its own `CLAUDE.md`.
- It brings in the shared code as a git submodule at `core/`, pointing at `hubbub0/Mess-Machine` and pinned to a
  commit. Moving the pin to a newer Android build is how the iPhone app gets every fix and feature.

## The plan: one shared core, not two apps

[Certain] The engine is 5,746 lines of plain Java with no Android imports (`app/src/main/java/.../engine/`). Java
cannot run on an iPhone. There are two ways to get it there:

1. **Shared core (the plan).** The engine is converted to Kotlin in this repo as a Kotlin Multiplatform module, then the
   screens move to Compose Multiplatform. Android and iOS then run the same engine and the same screen code. A fix lands
   once and both apps get it. This is the plan in Tyler's earlier proposal ("Mess Machine: Play Store ready and iOS
   port", https://claude.ai/artifact/GCqsR8x4K8omrH2dT45BBP), and it is the only way to stay truly in sync.
2. A Swift rewrite in your repo. Faster to start, but every feature is built twice and the two drift. Not chosen.

[Likely] The risky part is real-time audio. Kotlin on iOS has a garbage collector that can pause, so the audio callback
itself must not be Kotlin. Copy the design `app/src/main/cpp/mmaudio.c` already uses on Android: the engine fills a
lock-free ring buffer from its own thread, and a small C callback reads it. On iOS that callback sits on Core Audio
(an AudioUnit render callback, or AVAudioSourceNode) instead of AAudio. Nothing in the callback allocates, locks or
calls into Kotlin.

## Who changes what (the sync rules)

- Shared code (engine, screens, `shared/`, icons, fonts, sounds, guide, FAQ, lessons) changes **only** in
  `hubbub0/Mess-Machine`. If the iOS work needs a shared change, open a pull request there with a clear title starting
  `[iOS]`; do not push to its `main`. The Android chat reviews and merges it, then you move the `core/` pin.
- iPhone-only code lives only in your repo.
- `shared/PARITY.md` is the feature list. Each row has an iOS column: set it to `done`, `partial: <what>` or
  `todo` (through a pull request to this repo, or keep a copy of the column in your repo's `PARITY-IOS.md` if that is
  simpler, and say which you chose in your `CLAUDE.md`).
- Messes are files in the format in `shared/MESS-FORMAT.md`. An iPhone mess must open on Android and the other way
  round. `shared/fixtures/` holds example messes; load and re-save them in your tests.
- Tyler's notes: one inbox. The iPhone bug button posts issues to `hubbub0/Mess-Machine` with the labels `from-app`,
  the category and `ios`, the same body format as Android (`app/src/main/java/.../Notes.kt`). Fix iOS-only bugs in
  your repo and close them with the same "Fixed in build N" comment rule.

## What you can do from day one

The Kotlin engine does not exist yet. Do not wait for it:

1. **Repo and CI.** A minimal SwiftUI app shell, the `core/` submodule, and a GitHub Actions workflow on a macOS
   runner that builds for the iOS Simulator, runs XCTest and XCUITest, takes screenshots and publishes them to a
   release (like Android's `test-screens`). [Likely] macOS runners are free for public repos.
2. **The audio host.** `ios/Audio/` in C: the ring buffer and the Core Audio callback, the same interface as
   `mmaudio.c` (`open(rate, fill)`, `write(buf, frames)`, `setFill`, `clicks`, `behind`, `mode`), with a test tone
   writer standing in for the engine. Handle the audio session (category playback with mix-in off, interruptions, route
   changes such as headphones out, the sample rate the hardware gives you).
3. **Platform pieces** behind small interfaces that match what the Android side uses: mic recording and level
   (`MouthMic.kt`, `Workbench.kt`), CoreMIDI in (`Midi.kt`), file open and share for messes and sounds, background
   play with a Stop control (`PlaybackService.kt`), the bug note poster (`Notes.kt`), haptics (pad buzz).
4. **Mess files.** Load and save `shared/fixtures/*.mess.json` in Swift as a check of the format, until the shared
   engine takes over loading.

Once the Android chat lands the Kotlin engine module with `iosArm64` and `iosSimulatorArm64` targets, link it, feed
the ring, and the app makes sound. Then the screens move to Compose Multiplatform and iOS shows the same UI through
`ComposeUIViewController`.

## Things that differ on iOS (decide, do not guess)

- **Updates.** Android updates itself from GitHub releases (`Updater.kt`). iOS cannot install apps that way. Use
  TestFlight. That needs Tyler's Apple Developer Program membership ($99 a year), which only he can sign up for, and
  signing set up in the workflow. Until then you can only run on the Simulator. Ask Tyler before setting up anything in
  his Apple account.
- **Sign in.** Accounts are being planned (`docs/ACCOUNTS-PLAN.md`). [Certain] If the app offers Google sign-in, Apple
  requires an equivalent private option such as Sign in with Apple (App Review Guideline 4.8).
- **Notifications, background audio, file pickers, MIDI permissions**: all differ. Keep them behind the interfaces above.
- **Fonts and pixel art.** The icons are 40 px pixel art drawn at whole-pixel multiples only (`InlayIcon` in
  `ui/Widgets.kt`). Keep that: no smoothing, no in-between scales. Fonts are in `app/src/main/res/font/`.

## Tyler's standing rules (they apply to you too)

- Never use em dashes in anything written for Tyler. Tag claims [Certain], [Likely] or [Guessing].
- Lead with the uncomfortable truth. No warm-up. Disagree with structure: "I disagree because ... Here's what I'd do
  instead ... The risk in your approach is ...". Hold your position unless he gives new information.
- Top priority is fighting overwhelm: cut rather than add, rapid access first, understandability second.
- Do not raise the repos being public or the signing key as a risk. He has said so many times.
- Never create accounts, keys or paid services for him; tell him the steps.
- Report real build costs. Usage is tight.
- Before saying something can't be done, check every layer (approach, platform, tools, data, constraints) and offer
  "can't via X, here's Y".

## First message to Tyler

Say what you set up, what runs on the Simulator, what is blocked on the shared engine, and the one decision you need
from him. Keep it short.
