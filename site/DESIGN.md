# Neat Shit and Dye (the site) notes for Claude

The live site is the claude.ai artifact https://claude.ai/artifact/9T2NmAHeYgL7WsNGeQ1NW2
(same artifact as 446b4b77-aa54-40d4-ac1f-7caea71ec557). The Brotherwife site is the same build with a flag,
published to https://claude.ai/artifact/Lf6uu3C9wgSpaFR7AHLHey (9f2f8a8c-df15-4d88-b82d-9170768b3cf2).
Never use em dashes in anything written for Tyler. Tag claims [Certain], [Likely] or [Guessing] in reports to Tyler.
Follow the bug-testing skill's design rules.

## Now

Rewritten at the end of every session and at each step of a long job. Read this first.

- **Live:** the Nov 10 publish (second NSD session): chunks 1 to 5 of the proposal doc's work order, the web
  Mess Machine rebuilt to follow the Android app, and the walk mode overhaul. `python3 site/build.py` rebuilds it.
- **Proposal doc:** https://claude.ai/artifact/L2m9XThxcL5SvrZeQnJd9s, items NSD-1 to NSD-29. Its Outcome section
  says what shipped.
- **Not done:** NSD-27 (an alpha copy with the BUG button). It needs its own session: the alpha is a second artifact
  that needs a copy of the live assets (about 180 MB) and the recorder's database rules. NSD-29's bugtest.json waits
  for it; `site/tools/precheck.sh` covers the checks until then.
- **Not done:** the three Nov 3 comment threads on the site are fixed but open; they are not sent to Claude.
- **Watch:** Tyler has not seen the walk overhaul or the darker machine yet. Feel numbers are in `tools/walk.js`.
- **Nov 12:** the band page's game block is an arcade cabinet (the broken "Find it in Coin-Op Purgatory" button is gone).
  Clips uploaded in Channel Nowhere also play on the band page (`renderClips`); full episodes still open YouTube,
  because claude.ai blocks YouTube inside a page. The Brotherwife site has a Merch room: the Junk Drawer engine
  with `BW_DESIGNS` (empty until Tyler sends the band's shirt designs; each needs `img/<key>_shirt.jpg` and
  `img/<key>_mug.jpg` mockups, made with the print-art pipeline from the first NSD chat).
- **Waiting on Tyler:** share the Brotherwife site and the Ergot Pizza Party game with the band on claude.ai (both are
  owner only); send the Brotherwife shirt designs (not in Drive under any shirt or merch name).

## Two tracks, one repo

- The app track (another conversation) owns `app/`, `tools/`, `.github/` and the root DESIGN.md.
  This track owns `site/` only. Pushes that only touch `site/` do not start the app build.
- Read the root DESIGN.md (Decisions, Control map, Overhaul) for what the app changed. Those are the updates to port.

## Build, test, publish

- Build: `python3 site/build.py` writes `site/index.html` and `site/bw.html`. Built files are not committed.
- Assets (img, samples, audio, loops, models, about 180 MB) live only in the published artifacts. Do not download
  them all. Publish with `publish_files` in patch mode: files left out of a publish are kept.
  New in this session: `img/*_art.webp`, `img/dragon_mark.webp`, `img/dragon_plaque.webp`, `img/tharbe_title.webp`,
  `img/bw_flyer.webp`, `img/flyer_collage.webp` (made from the PNG and JPG originals with Pillow, quality 80, 760 px max).
- build.py now writes a whole document (doctype and viewport meta): publishing requires the doctype, and the page
  no longer relies on claude.ai's wrapper for the phone viewport.
- Before every publish: `ASSETS=<folder holding img/> bash site/tools/precheck.sh`. It runs `mm.js` on both sites,
  `walk.js`, every room at phone and desktop (`run.js`) and the first-load weight (`weight.js`).
- `deep.js` still covers the lab, Hwat?, the house menu, Pockets and walk mode by eye (logs, no checks).
- Test hook: `window.__walk` (between `@@TEST` markers) exposes the walk mode to `walk.js`.

## Decisions

- 2026-11-10 The web Mess Machine follows the Android app (Tyler).
- 2026-11-10 Design guidelines and targets from the bug-testing skill apply here too (Tyler, BT-1 to BT-10).
- 2026-11-10 "Proceed through all remaining chunks" (Tyler). Decisions 3 to 7 were open, so Claude took the
  proposal doc's recommendation on each: re-floor by Words, Things, Sound, Make and play (3); the app lives on an
  Apps shelf in the Specimen Lab (4); alpha copy yes, not built yet (5); walk in becomes a small link under the
  house (6); Channel Nowhere shows a daytime rerun (7). Tyler can reverse any of them.
- 2026-11-10 The machine is darker and grittier than the rest of the site (Tyler said it could be).
- 2026-11-10 Walk mode aims for The Swords of Ditto: Mormo's Curse (Tyler): quick to full speed, quick stops,
  dense rooms with things to break and kick.

## How the house is laid out (NSD-17)

| Floor | What for | Rooms |
|---|---|---|
| Upstairs | Words | Bedroom: Ramblings Of; Study: The Perpetrator |
| Main floor | Things | Kitchen: The Junk Drawer; Workshop: Mr Dr Wethands Emporium |
| Garage | Sound | Garage: Brotherwife; Studio: Terrible Noise; TV Corner: Channel Nowhere |
| Basement | Make and play | Basement Lab: The Specimen Lab (with the Apps shelf); Intern Desk; Basement Arcade |
| Hidden | | Attic; Behind the Wall: The Heap |

Walk mode follows the same floors, with the garage off the main hallway.

## Control map

| Job | Home | Second spot | Why |
|---|---|---|---|
| Go to a room | Header house menu | Home drawing | The drawing is the site's idea. Footer map and band buttons cut (NSD-18) |
| Paint the house, lights | Footer Lights and paint panel | none | Home swatches cut (NSD-19) |
| Walk the house | Header Walk | Small link under the house | The invitation for a new visitor (decision 6) |
| House radio | Terrible Noise deck | Header player | Keeps music going in every room |
| Go to the internship | House menu | Brotherwife hero button | The band's own call to action; the other two links cut (NSD-20) |
| Pick a machine section | Dock: Sounds, Play, Steps, Roll, Song | none | Pane switch and tab row cut (NSD-22) |
| Pads, Keys, Perform | Play switch | none | As the app's Play tab |
| Keys under the pads | Pad menu | none | The dock Keys button is gone |
| Doctor a pad | Pad menu | Jar dock (Doctor), Sounds | Different starting points: a pad or a jar |
| Mute, solo, clear a pad | Pad menu (hold a pad or a Steps name) | none | The pad card and Steps row buttons are gone |
| Loop length, copy, clear, bottle, resample, random drums | Loop tools in Steps | none | As the app |
| Loop letters | Steps | Roll, Song | Roll edits one loop and Song paints with them |
| Tempo, swing, click | Tempo button in the dock | none | As the app's tempo sheet |
| Messes, redo, click to hear, jam | Dock menu (☰) | Studio amp (jam, in walk mode) | The amp is the jam's place in the house |
| Hush | Stop twice | none | As the app |
| How-to text | ? in the dock, shown once per section | none | As the app |
| Help for a room | Hwat? bubble | Dragon on the house and first visits | The dragon is the host (NSD-6) |

## Wishlist

- 2026-11-03 Mess Machine as a standalone Android app: done natively (TideClock/Mess-Machine). Removed from the
  public page 2026-11-10 (NSD-13); the Apps shelf in the Specimen Lab links to it (NSD-15).
