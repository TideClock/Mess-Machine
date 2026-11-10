# Mess Machine (Android) notes for Claude

Native rebuild of the Mess Machine sequencer from Tyler's Neat Shit and Dye site. Read DESIGN.md first.
Never use em dashes in anything written for Tyler (docs, replies, release notes, issue comments).

## Building

The cloud workspace cannot reach Google's Android servers, so the APK builds on GitHub Actions
(`.github/workflows/build.yml`). Push to `main`, then watch the run with
`gh api repos/TideClock/Mess-Machine/actions/runs`. A green run republishes the `latest` release,
which is where Tyler installs from. The engine (`app/src/main/java/.../engine/`, plain Java) can be
tested here: see README.md.

Every build also runs the big test (`app/src/test/.../BigTest.kt`, Robolectric with real drawing):
every screen driven by touch, checks on the mess and the engine, and screenshots published to the
`test-screens` release (download its zip with `gh api` and look at them). Add a flow there for
anything new.

After the big test, a phone test runs the release APK (the file Tyler installs) on an Android emulator
(`tools/phone-test.sh`: fresh launch with the start guide, relaunch, every tab, gestures, two long monkey
runs). The build only publishes if it passes. Its screenshots and logcat go to the `phone-screens`
release; `mapping.txt` rides along in `latest` for turning Tyler's crash traces back into names.

Before each push that Tyler will install, rewrite `app/src/main/assets/guide.json`
(`title`, `thisBuild`, `watch`, `limits`). The app shows it once per new build.

## Tyler's notes (the in-app bug button)

Notes arrive as GitHub issues labeled `from-app` plus a category label. Read them with
`gh api "repos/TideClock/Mess-Machine/issues?labels=from-app&state=open&per_page=100"`.
Each body has the build, device, audio buffer and underruns, the mess summary, the last actions, a
crash trace when there was one, and a hidden `mm-note:<id>` marker the app uses to find replies.

Follow the bug-testing skill's triage rules:

- **bug / tuning**: fix it, add an engine test in `tools/EngineTest.java` when the engine is involved,
  push, and when the build is green comment "Fixed in build <run number>: <one line>", add the `fixed`
  label and close the issue.
- **feature / question**: comment a one-line proposal or answer and leave it open until Tyler decides.
  Build it only if he says to.
- **wishlist**: never build. Add one line to `## Wishlist` in DESIGN.md (date, build, idea), comment
  "Filed in the wishlist", keep the `wishlist` label and close.
- **discuss**: do not answer, fix or close. Count them per topic (`[Discuss: <topic>]` in the title).
  When Tyler says "let's discuss <topic>", group the notes into themes, then ask the deciding
  questions. Record the outcome under `## Discussions` in DESIGN.md and comment it on each issue.

Comments are what Tyler sees in the app under each note, so keep them short and plain.
Tag claims [Certain], [Likely] or [Guessing] in reports to Tyler.
