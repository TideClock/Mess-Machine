# Web Mess Machine overhaul: handoff

Written Nov 23 2026, at Android build 170, for the Claude chat that works on the Neat Shit and Dye (NSD) site. Tyler
wants the web Mess Machine to be almost exactly like the Android app. Read this file, then `WEB-GAP.md` next to it
(the full gap, section by section), then `shared/` and the screenshots in this packet.

## The uncomfortable part first

[Certain] This is a rewrite of the machine, not an update. The site is at about Android build 50 in features; the app is
at 170, with a new layout, a new look, new gestures, multi-bar loops and a different save format in places. The site's
house and lab around the machine can stay.

## Where things live

- **The Android app**: `github.com/hubbub0/Mess-Machine` (public). It is the source of truth for how the machine looks
  and behaves. `DESIGN.md` has every decision, newest first under `## Decisions`. `CLAUDE.md` has the working rules.
- **The shared contract**: `shared/` in that repo. `MESS-FORMAT.md` (the save file every platform reads and writes),
  `PARITY.md` (every feature and control, with a Web column for you to fill), `tokens.json` (colours, sizes, fonts),
  `ASSETS.md` (icons, fonts, sounds, start guide, FAQ, lessons), `fixtures/` (messes to round-trip in your tests).
- **The site**: the claude.ai artifact `9T2NmAHeYgL7WsNGeQ1NW2` (and its Brotherwife copy). The copy of its source in
  the Android repo's `site/` is old; work from the live artifact.
- **The iOS app** is being built in another chat (`hubbub0/Mess-Machine-iOS`). Same contract.

## The one big decision: whose engine

[Likely] Do not hand-write a third engine. Two engines already need to match (Android now, iOS soon); a TypeScript port
would drift from both. Since Nov 23 2026 the engine is common Kotlin in `core/` of the Android repo, and it already
compiles for JavaScript (`./gradlew :core:compileKotlinJs -Pmm.js`, or `tools/kt-check.sh js`), so the web uses that
module in an AudioWorklet; no TeaVM, no third engine. [Likely] Kotlin/JS prints numbers differently from the JVM
("1" for 1.0), so check saved messes against `shared/fixtures/` by loading them, not by comparing text. `WEB-GAP.md` section 3 has the detail and the
risks. Probe first (build order step 0): what the artifact sandbox allows (AudioWorklet, SharedArrayBuffer, mic, Web
MIDI, fetches) decides whether the machine can stay an artifact or needs Netlify. Bring Tyler that answer before
building on it.

## Build order

`WEB-GAP.md` section 4 has twelve small steps, each shipping to the live site and removing the old piece it replaces.
Start with the probe, then the mess format (app messes open on the site with nothing lost), then the engine.

## Staying in sync

- Read the Android repo's latest `DESIGN.md` decisions at the start of each session; the app keeps moving.
- Changes you need in `shared/` (a format question, a missing token) go to Tyler or as a pull request titled `[Web]`
  to `hubbub0/Mess-Machine` if your chat has the repo. Never change the mess format on the web side alone.
- Fill the Web column in `PARITY.md` as you go (or keep `site/PARITY-WEB.md` and say so).
- Messes saved on the web must open in the app and the other way round. Test with `shared/fixtures/`.

## Tyler's standing rules (they apply to you too)

- Never use em dashes in anything written for Tyler. Tag claims [Certain], [Likely] or [Guessing].
- Lead with the uncomfortable truth. No warm-up. Disagree with structure: "I disagree because ... Here's what I'd do
  instead ... The risk in your approach is ...". Hold your position unless he gives new information.
- Top priority is fighting overwhelm: cut rather than add, rapid access first, understandability second. Cancelled
  ideas stay cancelled (`DESIGN.md ## Cancelled`).
- Never create accounts, keys or paid services for him; tell him the steps.
- Before saying something can't be done, check every layer and offer "can't via X, here's Y".
