# Neat Shit and Dye (the site) notes for Claude

The live site is the claude.ai artifact https://claude.ai/artifact/9T2NmAHeYgL7WsNGeQ1NW2
(same artifact as 446b4b77-aa54-40d4-ac1f-7caea71ec557). Never use em dashes in anything written for Tyler.
Tag claims [Certain], [Likely] or [Guessing] in reports to Tyler. Follow the bug-testing skill's design rules.

## Now

Rewritten at the end of every session and at each step of a long job. Read this first.

- **Live:** the Nov 9 publish. `python3 site/build.py` rebuilds it byte for byte from `index2.src.html`.
- **This track:** the site overhaul, and rebuilding the web Mess Machine with the Android app's updates.
  Tyler decided 2026-11-10 that the web machine follows the app (decision 2 in the proposal doc).
- **Proposal doc (start here):** https://claude.ai/artifact/L2m9XThxcL5SvrZeQnJd9s, items NSD-1 to NSD-29.
  Chunks 1 to 3 need no decisions. Decisions 1 and 3 to 7 are still open.
- **In flight:** nothing yet.
- **Not done:** the three Nov 3 comment threads on the site are fixed but open; they are not sent to Claude.

## Two tracks, one repo

- The app track (another conversation) owns `app/`, `tools/`, `.github/` and the root DESIGN.md.
  This track owns `site/` only. Pushes that only touch `site/` do not start the app build.
- Read the root DESIGN.md (Decisions, Control map, and the plan sections) for what the app changed.
  Those are the updates to port. Do not edit it; ask the app track or Tyler.

## Build, test, publish

- Build: `python3 site/build.py` writes `site/index.html` and `site/bw.html` (the Brotherwife flag build).
  Built files are not committed.
- Assets (img, samples, audio, loops, models, about 180 MB) live only in the published artifact. Do not
  download them. Publish with `url` set to the live artifact: files left out of a publish are kept.
  To test locally, read the few assets a test needs with Artifact `read` and `path`.
- Tests: `site/tools/run.js` (every room, phone and desktop), `mm.js` (Mess Machine tabs and pad menu),
  `deep.js` (lab, Hwat?, house menu, Pockets, walk mode), `weight.js` (first-load size). Set `NSD_PAGE` to the
  built page's path. Results from 2026-11-10 are in the proposal doc's Full test results.

## Decisions

- 2026-11-10 The web Mess Machine follows the Android app (Tyler).
- 2026-11-10 Design guidelines and targets from the bug-testing skill apply here too (Tyler, BT-1 to BT-10).

## Wishlist

- 2026-11-03 Mess Machine as a standalone Android app: done natively (TideClock/Mess-Machine, build 56).
  Still sitting in a hidden section of the public page; remove it (NSD-13).
