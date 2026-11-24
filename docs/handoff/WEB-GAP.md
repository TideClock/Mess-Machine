# Web Mess Machine: the gap to the Android app

Written Nov 23 2026, at Android build 170, for the Claude chat that overhauls the Mess Machine on the Neat Shit and
Dye (NSD) site. Goal from Tyler: the web Mess Machine should be almost exactly like the Android app.

Read this whole file first, then in this repo: `CLAUDE.md`, `DESIGN.md` (sections `## Now`, `## Decisions`,
`## Control map`, `## Cancelled`), `site/DESIGN.md`, `shared/README.md` and `docs/handoff/IOS.md` (the iPhone port
follows the same plan; the two ports should share choices).

Tags: [Certain] means read in the code or docs. [Likely] means strong evidence but not tested. [Guessing] means
untested; check it before you build on it.

## 0. The short version

- [Certain] The site's machine matches the app as it was around Nov 10 (roughly build 50). The app is now at build
  170. Since then the app gained a new look (the panel Look, skins, pixel icons, three fonts), a new layout (tab slot,
  bottom dock, loop strip, perform row, Edit tray, FannyPack), velocity, chance, step locks, off-grid timing, 1 to 8
  bar loops, six synths, per-pad XY, LFO and FX vials, Hold counts, 12-chord sets, Padiano, MIDI, mouth control,
  lessons, the FAQ, notes and more.
- My judgment: the gap is a rewrite of the machine, not an update. The house, the lab, jams, the Ring and the rest of
  the site can stay. The machine's screens, its engine and its mess loader all need replacing.
- The biggest single choice is the engine. Do not hand-port it. Run the same engine the app runs, compiled to
  JavaScript, inside an AudioWorklet (section 3).
- The second biggest is hosting. Several things the app does (MIDI, mic, fast shared memory, posting notes to
  GitHub) may be blocked inside a claude.ai artifact. Test that on day one (build step 0).

## 1. How the site is built today

### Files and build

- [Certain] One source file, `site/index2.src.html` (about 600 KB, 6,440 lines). It holds the whole NSD site: the
  house, every room, walk mode, the lab, the Mess Machine, creature cards, the Ring, jams, Channel Nowhere and the
  arcade. CSS first, then the markup per room (`<section class="view" id="v-...">`), then one big script.
- [Certain] `site/build.py` fills five placeholders (`__RAMBLINGS__`, `__LOOPS__`, `__CRATES__`, `__QTRACKS__`,
  `__QLAB__`) from the JSON files next to it, adds a doctype and viewport meta, and writes `site/index.html`. It
  also writes `site/bw.html`, the Brotherwife site: the same build with `window.NSD_SITE = "bw"`.
- [Certain] Built files are not committed. `site/DESIGN.md` names test scripts in `site/tools/` (`precheck.sh`,
  `mm.js`, `walk.js`, `run.js`, `weight.js`, `deep.js`), but `site/tools/` is empty in this copy of the repo. Look for
  them in the site chat's workspace or rebuild them.
- [Certain] The Mess Machine lives in the Specimen Lab room (`#v-specimens`). The script's machine part starts at the
  comment `THE MESS MACHINE, Koala-style` (around line 2792) and the app-style layer at `the app's way of doing
  things, on the web (NSD-23)` (around line 3747).

### Publishing

- [Certain] Not Netlify. The live site is a claude.ai artifact: https://claude.ai/artifact/9T2NmAHeYgL7WsNGeQ1NW2 .
  The Brotherwife site is a second artifact: https://claude.ai/artifact/Lf6uu3C9wgSpaFR7AHLHey .
- [Certain] Assets (images, samples, audio, loops, models, about 180 MB) live only in the published artifacts, not in
  git. Publish in patch mode: files left out of a publish are kept. Do not download them all.
- [Certain] Two tracks share this repo. The site chat owns `site/` only. Pushes that only touch `site/` do not start
  the Android build. The app chat owns `app/`, `tools/`, `.github/`, `shared/` and the root `DESIGN.md`.

### Audio engine today

- [Certain] Plain Web Audio nodes on the main thread. No AudioWorklet, no ScriptProcessor. One shared `AudioContext`
  (`ac()`), made with no `latencyHint`.
- [Certain] Each hit builds its own little graph (`voiceEd`): BufferSource, an optional Biquad for tone, a Gain with
  ramps for attack and release, an optional StereoPanner, into the live FX bus. Choke groups cut older voices.
- [Certain] The live bus (`fxBus`): a Biquad filter, a WaveShaper for dirt, an oscillator ring mod, a square-wave gate,
  a WaveShaper crusher, a delay echo, then a DynamicsCompressor master (threshold -10, ratio 4).
- [Certain] The clock is the classic two-clock scheduler: `setInterval(mmTick, 25)` looks 120 ms ahead and schedules
  buffer starts on the audio clock. Swing, stutter, tape stop, note repeat and the arp are done in that loop. Live
  pads play at `currentTime`, so a busy main thread delays them.
- [Certain] Bounces, Bottle it and resample use `OfflineAudioContext` with the same node graph.
- [Certain] Mic takes use `getUserMedia` plus `MediaRecorder`.
- [Certain] So the site and the app are two different engines today. They will never sound the same this way.

### Storage

- [Certain] `localStorage` keys: `nsd.favs`, `nsd.seqs` (saved messes), `nsd.edits` (doctor settings per jar, for
  the whole browser, not per mess), `nsd.mtab`, `nsd.pane`, `nsd.chord`, `nsd.arp`, `nsd.mmhelp`, `nsd.col`.
- [Certain] IndexedDB `nsd-bench`, store `wav`: Your Workbench sounds (chops, resamples, imports, mic takes).
- [Certain] claude.ai capabilities through `claude.use()`: `db`, `assets`, `downloads`, `user` and `room`.
  Signed-in viewers who can write keep favorites and messes in `data/users/<id>/lab` and `.../lab/seqs`; everyone
  else stays in the browser. `assets` is for the owner's uploads. `downloads` saves mess files (JSON) and audio
  (as a zip, because WAV and MP3 are not on the download allowlist).
- [Certain] `room` runs the people-here layer: presence (nickname, colour, which room), duel challenges, and jams.
  A jam is `room.join('jam')` with each player's sound, row, BPM and hits in presence. The jam has its own small grid,
  not the machine. Duels run the Ring for two people with the same dice on both screens.

### The mess file: site vs Android v3

Both write `"v": 3`. The site wrote the format first; the app has grown it since. `shared/MESS-FORMAT.md` is meant
to become the contract, but [Certain] only `shared/README.md` exists today. Until it lands, `Project.java`
(`toJson`, `fromJson`) is the truth.

| Field | Site writes | Android writes | What breaks today |
|---|---|---|---|
| `name`, `bpm`, `v`, `sel`, `steps`, `swing`, `mutes`, `solos`, `scale`, `root`, `fold`, `at` | yes | yes | nothing |
| `kit` | 64 keys | 65 keys (slot 64 is the scratch synth) | the site drops slot 64 |
| `pats[].len` | 16 or 32 | 16 to 128, any multiple of 16 (1 to 8 bars) | [Certain] the site loads any other length as 16: an 8 bar loop loses 7 bars |
| `pats[].steps`, `pats[].notes` | yes | yes (notes `s.p.l`, Roll reaches -36 to +24) | nothing |
| `pats[].motion` | no | XY moves per step | lost on a site save |
| `pats[].offs` | no | off-grid hits, 24 ticks a step | lost |
| `pats[].acc` | no | `g . a` per step (ghost, normal, accent) | lost |
| `pats[].chance` | no | 0 to 100, plus 101 to 104 for 1:2, 2:2, 1:4, 4:4 | lost |
| `pats[].locks` | no | `"key@target"` per step, empty for none | lost |
| `song` | 16 bars | up to 64 bars | the site keeps the string but the lane UI is 16 |
| `squash`, `level` | no | master bus, written only when changed | lost |
| `edits` | 14 base fields | base fields plus `held`, `beatN`, `pitch`, `bake`, `fx` (rvb, echo, dirt, crush, chorus, wob) | [Likely] kept only when a base field is also changed, then dropped on the next clean-up |
| `synths` | no | a synth patch per `synth:<n>` key | lost, and the pad loads empty |
| `padOpts` | no | per slot: glide, legato, route, xy, pack, fx, `ftype`, lfo | lost |
| `app`, `mode`, `cur` | no | `"android"`, loop or song, current loop | lost |
| sound keys | site keys | same keys plus loops and hits; 390 old chop keys alias to hits (`library.json` `aliases`) | [Certain] the site drops any key it does not know, so app loops, hits, packs and synths vanish |

Other differences: the site keeps doctor settings per browser and the app keeps them per mess. The site's Workbench
sounds are in IndexedDB and never travel; the app loads those pads empty and says how many.

Rule for the overhaul: load and save exactly what `Project.java` does, keep any field you do not understand, and
never shorten a loop or drop a key. A site save must open on Android with nothing lost.

## 2. The gap table

"Remove" in the last column means the app dropped it on purpose. Take it out of the web machine too.

### Layout and navigation

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Sections | A dock nav: Sounds, Play, Steps, Roll, Song. One pane at a time (jars or machine). | Five tabs in a milled tab slot, the current tab raised: Pads, Synth, Steps, Perform, Sounds (`BAR_TABS`). Song has no tab. | Build the five tabs and the tab slot. Remove the Song and Roll sections as tabs. |
| Steps and Roll | Two sections | One Steps tab with a Steps / Roll slider (`SeqSwitch`) | One tab, one slider |
| Play view switch | Pads, Keys, FX (`#playsw`) | Gone (Step and Keys modes retired Nov 18) | Remove the switch. FX becomes the Perform tab. Keys under pads becomes the Padiano. |
| Top bar | Play, Stop, Rec, Undo, tempo button with position, ?, menu, fold, all in the bottom dock | One row: Play, Stop, Rec, Undo, BPM readout (lag in ms and clicks this minute), Loop readout (tap opens the loop strip, swipe for next or previous loop), ? (What the FAQ), menu | Build the top bar. Transport moves from the bottom to the top. |
| Menu | Messes, Redo, click to hear, Jam | Messes, Settings, Notes for Claude, Learn, Mouth control, MIDI map, Packs, Redo | Same menu. Jam stays a site extra (see below). |
| Loop strip | Loop letters on Steps, Roll, Song; loop tools menu on Steps | The one home for loops, under the top bar on every page: pad-sized A to H (hold for the loop menu), Wait bar / Now, minus, plus, 2x (1 to 8 bars), the loop's bars, Copy to, Clear, Auto, Bottle it, and the Song painter (Loop / Song slider, Play loop and Play song). Holds at most half the screen. | Build it. Remove loop rows and loop tools from Steps, Roll and Song. |
| Bank row | Bank buttons over the pads | Banks 1 to 4, Click and Count (count-in off, 1, 2 bars) on the Pads page | Move Click and Count here (out of the tempo sheet) |
| Bottom dock | Transport and section nav, folds on phones | Edit, Pack, Hold, Chords, Repeat, Mute, Drop, and the key readout (amber LCD: "C minor · chords"). All keys one width, 44 real dp, down to 38 on a folded phone, More only when narrower still (Pack first, Edit never). Shown on Pads, My Desk and Synth. | Build it with the same priority plan (`ui/BarPlan.kt`) |
| Key panel | Root and scale in the dock, chord mode | Opens above the dock from the key readout: root, scale, chords, Hold count, Let go | Build it |
| Perform row | none | Swipes up above the bottom bar: a short panel of your picked keys (Lock, pitch wheel, filter and resonance, Repeat, throws, mouth, XY, desk, stutter, echo); pitch wheel and filter on its sides. Not Hold or Rec. | Build it |
| Edit tray | Pad menu (hold a pad 450 ms or right-click): keys, hear, doctor, swap, mute, solo, star, clear | Edit on the dock, then tap a pad, opens the Edit tray with drawers: sound, fx, keys, mod, more. Edit twice = the last thing touched. A flick up off a pad opens it too. | Build the tray. Remove hold-a-pad-for-menu: on the app a hold always plays. |
| FannyPack | none | Per pad: the strips you add (keys, fx, lfo, xy) open under the pad's row. Pack opens and shuts it; a quick swipe up on Pack picks strips. No zipper bar. | Build it |
| Sheets and panels | Messes sheet, tempo pop-up, doctor modal | Sheets for Tempo, Messes, Settings, Notes, Learn, Mouth, Packs, Picker, Sample (Doctor), XY, Latency, Start guide. Every panel closes with a CloseScrew; Back closes every panel. | Same sheets. Map the browser Back button to closing the top panel. |
| Layouts | one | Settings, Layout: New (default), Side, Classic | Build New only. Ask Tyler before building Side or Classic. |
| My Desk | none on the web | A Play layout you build from pieces (grid, resize, float, pin), saved per device | Later step |
| Help | `.mmhelp` paragraphs per section, shown once, then behind ? | No explanation lines (Help is a no-op). ? is What the FAQ: tap it, then tap any control to learn what it is. Settings, Explain brings lines back. | Remove the help paragraphs. Build the FAQ mode with the app's text (`Faq.kt`). |
| Bug button | none in the machine (NSD-27 alpha copy not built) | Notes for Claude, bottom left by the tabs, on every page | Build it (see section 3, Notes) |

### The look

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Overall | Memphis stickers with hard shadows, made darker and grittier for the machine (NSD-23) | The panel Look is the default: bakelite keys set in worn metal. Bars and trays are one plate (#24211F) framed by one brass rule. The master section is a metal plate with brass trim and four screws. | Build the panel Look first. Numbers: `ui/Widgets.kt` (`Hw`, `UiLook`, `Skins`) and later `shared/tokens.json`. |
| Keys | Sticker buttons | One size in real dp at any screen size (no growing with width). A key with an icon is filled with its group's tile colour: transport yellow, pages and synth purple, playing tools pink, sound teal, tools slate. Icon beside the word when there is room. Large keys setting (about 1.25x). | Same. One CSS px is about one Android dp in Chrome [Likely], so the floors carry over as px. |
| Lamps | aria-pressed styling | A lamp in a metal lip under each key, only on keys that switch something. Yellow = on, pink = recording only, oxblood = takes sound away (Mute, Drop), teal = a shape, amber = readouts. | Same meanings, exactly. |
| Pick-one groups | chip rows and selects | Sliders: a milled track with a raised block on the pick (synth kind, Simple/Full, Poly/Mono/Acid, LFO wave, filter Lo/Hi/Band, Straight/Triplet/Dotted, Steps/Roll, Loop/Song) | Build one slider widget and use it everywhere |
| Readouts | text | Amber LCD windows (`Modifier.lcd`) that shrink their text to fit | Same |
| Fonts | Archivo, Bungee, Bungee Shade, IBM Plex Mono, Special Elite | Rubik Distressed on keys, Black Ops One for stamped words, Share Tech Mono on readouts, plus Archivo, Bungee and IBM Plex Mono | All are on Google Fonts, which artifacts may load [Certain for the allowlist, Likely for each family being there]. |
| Icons | Unicode symbols | 102 pixel-art icons, 40 by 40 PNG (`app/src/main/res/drawable-nodpi`, made by `tools/icons/`), drawn only at whole multiples, tinted through `enamel()` (less saturation, warmer) with a dark offset shadow | Use the same PNGs. See section 3 for crisp scaling. |
| Skins | one | Classic, Specimen, Stage, Felt and Paper (light), Rust: colours, grit strength, shadow, and pad face (jar, glass, band, felt) | CSS custom properties per skin. Panel Look plus Classic skin first, the rest later. |
| Pads | Jar colours | Pad face by skin; marks for an LFO (yellow), a lane in this loop (teal) and a resting FX fader (red); H on a latched pad | Same |
| Beat | `.now` class on steps | The top bar's brass rule flashes on each beat (brighter on the one); optional beat pulse behind the screen | Same, on the compositor (CSS transform or opacity), never a full repaint |

### Playing

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Pads | 4 banks of 16, pointer events, multitouch, keyboard keys 1234 qwer asdf zxcv | 4 banks of 16 plus a hidden scratch pad (64) for the Synth tab. Fire on touch down, every finger tracked on its own. | Same, plus keep keyboard keys as a web-only extra on desktop |
| Swipes off a pad | none | HotKeys (Settings), one action per direction. Defaults: up Edit, down Padiano, left Sample, right FX. Mute is an option. The same swipe again closes what it opened. A quick swipe up on Pack, Hold, Chords, Repeat, the key readout and perform keys opens their settings. | Build it |
| Swipe cancels its hit | n/a | The hit sounds on touch. If the finger travels 24 dp within 300 ms, its voice fades in 6 ms and a recorded hit is taken back (only if it turned the step on). The swipe's own job needs 44 dp. | Same numbers |
| Empty pads | Double tap opens the sound picker | Top corner records, bottom corner picks a sound; acts on a still lift | Same. Remove the double tap. |
| Velocity | none | 16 Levels across the pads; accents per step; MIDI velocity in | Build it |
| Mute and Drop | Mute and solo in the pad menu | MUTE key: hold it for a moment or tap to latch, then pads are mute switches; hold a bank number to mute the bank; DROP held leaves only the pads you touch (never saved) | Build it |
| Hold | Latches on keys | Count 1 to 8 or Last; latches grid pads (ids 1000+pad) and keys; On the beat (1, 2, 4 beats); Let go; Hold's tray is one bar (minus, count, plus, Let go) | Build it |
| Repeat | 1/8, 1/16, 1/32 and an arp select | Any rate, Straight/Triplet/Dotted, styles Cut/Ring/Flow, patterns (`x` plays, `.` rests, 2 to 16 long, presets plus your own), arp modes. Rate and pattern are two words under the key and two selects in the tray. | Build it |
| Chords | 8 chords per set, 8 sets, chord types | 12 sets of 12 chords, all in the set's home scale (seven degree chords, five colour chords); a set in another scale moves the scale; `Theory.smooth` picks the nearest inversion; types include 1-4-5 (sus) and Octaves; chords never fold notes | Use `engine/Theory.java` logic as is |
| Padiano | Keys strip under the pads | Squares (default) or Keys; sizes S M L XL; rows 1 to 3 (each an octave up); 7 to 36 semitones shown; In key only; reaches C1 to C6; chord boxes; glide and legato per pad; pitch wheel (range, all or pad, stay or spring) | Build it |
| XY | One "Mud filter" pad on the FX section | Per pad: presets Sweep, Dub, Bend, Lo-fi, Scrub and your own; 2-way or 4-way; spring or stay; At the start / At the edge. Draw a circle on a pad or a Padiano note for a sphere. XY moves record as motion. The Perform tab keeps the master Mud/Thin filter XY. | Build it |
| Perform effects | 9 hold buttons (stutter, echo throw, crush, tape stop, gate, dirty, ring mod, octave down, reverse) with key letters | Master throws are glass vials on a brass plate: where the finger lands is how much. Plus Pump, Tape, Phaser and Freeze. | Replace the buttons with vials |
| Pad FX | none | FX vials per pad (in/out lamp each, hold for an Auto lane, Bypass restores exactly). Swipe right opens them as a strip under the row: two rows of wider vials on a phone, one row in the Edit tray. Saved FX: Wash, Grit, Tape, Space, Dry, plus your own. | Build it |
| LFO and filter | none per pad | Per pad LFO (rate, depth, cutoff, res, shapes, Wobble preset) and one pad filter Lo/Hi/Band | Build it |
| Control priority | n/a | Each pad control is hand, then step lock, then lane, then home; the LFO adds on top (`Engine.combine`) | Comes free with the shared engine |
| Mouth control | none | Mic into `MouthTracker`: vowel moves one XY target, loudness another; records as XY moves | Later step, if the mic works in the host (section 3) |
| MIDI | none | Controller-agnostic map (menu, MIDI map): touch a pad, key or knob, press a controller key; layered pages and shift; CC knobs; pitch bend to the wheel; velocity | Web MIDI where the browser and host allow (section 3) |
| Lock | none | Freezes the page (no scroll, tabs, sheets, undo); playing still works; asks Android to pin | Freeze only. Browsers have no screen pinning. |

### Sequencing

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Loops | 8 loops, 1 or 2 bars | 8 loops, 1 to 8 bars (minus, plus adds an empty bar, 2x copies); plus and minus clear accents, chances, locks and off-grid hits past the end | Build it |
| Steps | One row per sound, tap to toggle | Names are grid cells level with their steps. Tap a lit step: normal, accent, ghost, off. Hold a step for the step sheet (chance, time round, strength). Step locks. Drag paint. Row tools. Hold a sound name for the pad menu. | Build it |
| Timing grid | 16th steps | 24 ticks a step; triplet and dotted repeats record off grid; Rec exact as a setting | Comes with the shared engine and format |
| Auto lanes | none | Auto lanes draw in place over the vials (`laneHere`), lane tools (lane, smooth, stamp) | Build it |
| Roll | Two octaves around the sound, fold | C1 to C6, two octaves on screen, scale rows, fold, lengths 1, 2, 4, 8, chords as stacks | Build it |
| Song | A Song section with a 16-bar lane | Song painter inside the loop strip, up to 64 bars | Move it into the strip |
| Recording | Rec while playing | Count-in, undo per take, a loop pick waits for the bar line (or Now), recorded hits carry ids | Build it |
| Click | In the tempo pop-up | On the bank row, on by default | Move it |
| Tempo | Pop-up with slider, tap, swing | Tempo sheet (the old-school feel Tyler likes), slider sets tempo as it moves, one undo on release | Keep the feel, follow the app's rules |
| Undo | Undo, redo, toasts with Undo after clears | 80 steps, undo per take, toasts with Undo | Same |

### Sounds

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Library | Lab cabinets of jars; Heap and Qbldy 0.35 s chops from sprite WAVs | 313 sounds plus loops and hits with key and tempo; the 390 old chops alias to hits from the same song; types and feels, By jar, Starred, search by key names | Load `library.json` and its aliases. Upload the loops and hits to the artifact. |
| Sounds tab | The jars pane and the picked-jar bar | Sounds tab with rows, a sample sheet per sound, Workbench, Packs | Build the tab. The house's lab room can still show jars; inside the machine follow the app. |
| Sample sheet | Doctor modal: trim, zoom, snap, attack, release, tune, vol, pan, tone, reverse, loop, gate, normalize, choke, fit to tempo, chop | All that, plus a Play page first (pitch mode Stretch, Grain or Auto; when held: loop, tail, once; beat rate; Sustain), per-sound FX (reverb, echo, dirty, crush, chorus, wobble), Bake, key display | Build it |
| Synths | none | Synth tab: Analog (poly, mono, acid), FM (4 operators), Wavetable, Granular, Drums, Pluck; Simple/Full; opens on the scratch synth; Put on pad (a second tap to replace a pad with a sound) | Comes with the shared engine; build the screen |
| Workbench | Chops, resamples, imports, mic takes in IndexedDB | Same jobs, stored on the phone | Keep IndexedDB |
| Packs | none | Packs sheet lists the `packs` release on GitHub, installs zips (pack.json plus FLAC clips) | Only if the host can fetch GitHub (section 3) |

### Files and the rest

| Area | Site today | Android app now | What the web overhaul must do |
|---|---|---|---|
| Messes | Logbook under the machine; db or localStorage; Save as file, Open a file | Messes sheet: autosave (crash-safe), save, save new, open, toss, new, import, share, mess pictures | One Messes sheet. Remove the logbook block from the page. |
| Exports | Song or loop WAV in a zip | WAV loop or song, stems (all padded to one length), MIDI file (format 1, PPQ 96), Pour it out, For a DAW | Build it with the shared engine rendering offline |
| Lessons | none | Learn: 29 interactive lessons that check the real state | Later step |
| Start guide | none | `assets/guide.json` once per build | Optional: a "what changed" card per publish |
| Latency screen | none | Latency (audio check), CPU banner, lag readout | Build a small one (section 3) |
| Settings | none | Layout, Look, Skin, HotKeys, padFlick, Large keys, Smooth screen, Sound out, Buffer cap, Explain | Only the ones that mean something on the web |

### Site things to keep, drop or ask about

| Thing | Status | What to do |
|---|---|---|
| Hold a pad for its menu | App dropped it (a hold always plays) | Remove |
| Double tap an empty pad | App uses the two corners | Remove |
| Pads / Keys / FX play switch, keys strip, Song section, loop rows on Steps and Roll, `.mmhelp` lines, logbook, the Sample key on Play | App dropped or moved each | Remove |
| Hush button | Both use Stop twice | Keep Stop twice |
| Keyboard shortcuts | App has none (a phone) | Keep on desktop as a web-only extra; no on-screen key hints |
| Jams (room) and the Ring and duels | App cannot join claude.ai rooms; creature cards wait on Tyler | Keep as site extras outside the machine's controls. Ask Tyler where the Jam entry lives (the menu today, the studio amp in walk mode). |
| Picked-jar bar and the lab's jar shelves | Site only | Keep in the lab room; inside the machine, sounds go through the Sounds tab and the Edit tray |
| Side and Classic layouts, Looks other than panel | App keeps them as settings | Do not build until Tyler asks |
| Cancelled by Tyler (Nov 22): hold a pad to sample; scenes with a crossfade; four coloured macros per screen; follow actions on loop letters; Ableton Link; a reorderable FX chain per sound | Cancelled | Never build or propose them |
| Wishlist (root `DESIGN.md`): Compact layout, user chord sets, jam station brain, hardware, several Padianos at once | Wishlist | Never build them |

## 3. Web constraints and recommendations

### Day-one unknowns inside a claude.ai artifact

The site runs in a published claude.ai artifact, which is a sandboxed page with its own origin. These decide the plan,
and none of them have been tested. Build step 0 tests them all on one small page.

| Question | Why it matters | My guess |
|---|---|---|
| Does `audioWorklet.addModule()` load a module published as a supporting file (or a blob URL)? | The whole engine plan | [Likely] yes for a published file; [Guessing] for blob URLs |
| Is the page cross-origin isolated (`crossOriginIsolated`)? | `SharedArrayBuffer` for a lock-free ring like the app's | [Likely] no. Plan for `MessagePort` messages. |
| Does `navigator.requestMIDIAccess()` work? | MIDI | [Guessing] blocked: an iframe needs `allow="midi"` |
| Does `getUserMedia` work? | Mic takes and mouth control | [Guessing] the site already calls it; nobody has said it works there |
| Can the page `fetch` api.github.com or GitHub release files? | Notes for Claude and Packs | [Guessing] blocked by the page's content rules |
| Are `.flac` files served, and does `decodeAudioData` play them? | Sound weight | [Likely] not served (the Vault note says FLAC is not a type artifacts serve). Use WAV or MP3 like the site does now. |
| `navigator.vibrate`, `navigator.wakeLock`, Fullscreen | Pad buzz, screen on while playing | [Guessing] each may need iframe permission |

If MIDI, mic and notes are all blocked, the honest answer is: can't via a claude.ai artifact, here's the other way.
Host the machine on Netlify (Tyler already has a Netlify connector, and his Hubbub hub is moving there), where you set
the headers yourself (cross-origin isolation, MIDI, mic) and can add a service worker so it installs to the home
screen and works offline. The cost: `room`, `db` and `user` exist only on claude.ai, so jams, duels and signed-in
saves would stay on the site and the site would link to the machine. That is Tyler's call. Bring him the probe
results and this trade-off in a few lines.

### The engine: run the app's engine, do not write a third one

- [Certain] The engine is 5,746 lines of plain Java with no Android imports: `Engine.java` (1,973), `Synth.java`
  (1,051), `Project.java` (691), `Theory.java`, `Bake.java`, `SoundEdit.java`, `PadOpts.java`, `KeyFinder.java`,
  `MouthTracker.java` and small helpers. Its API is a command queue (`play`, `stop`, `down`, `up`, `cancel`, `fx`,
  `xy`, `mod`, `home`, `padFilter`, ...) plus `render(float[] out, int frames)`. That shape fits an AudioWorklet
  `process()` exactly.
- [Certain] The app ships engine changes several times a day. A hand port to TypeScript would be stale within a week
  and would never sound quite the same. I disagree with a hand port for that reason.
- The plan, in order of preference:
  1. **The shared Kotlin engine, compiled to JavaScript.** `docs/handoff/IOS.md` says the engine is being converted
     to a Kotlin Multiplatform module for iOS. Ask the app chat (by a `[Web]` pull request or through Tyler) to add a
     `js` target (Kotlin/JS IR, ES module output) to that module. Bundle it into the worklet file. Then the web, iOS
     and Android all run one engine and a fix lands once. [Likely] Kotlin/JS runs in an AudioWorkletGlobalScope if the
     engine avoids timers, `fetch` and coroutines, which it does today. A Wasm target is faster but needs WasmGC
     (Safari 18.2 and up) and its loader expects `fetch`, which a worklet does not have [Guessing on the details].
  2. **Stopgap if the Kotlin module is not ready when your shell is:** compile today's Java engine to JavaScript
     with TeaVM [Guessing: it should handle `ArrayList`, `LinkedHashMap` and `ConcurrentLinkedQueue`, but test].
     Same source as the app, so no drift while you wait.
  3. Hand port only if both fail, and then lock it with golden tests: render each `shared/fixtures` mess on the JVM
     (`Engine.bounce`) and in Node, and fail the build when the two differ by more than a small tolerance.
- Keep the current Web Audio engine behind the same adapter until the new one passes those tests, so the live site
  never goes silent.
- Worklet details:
  - [Certain] A worklet renders 128 frames per `process()` call. The engine takes any frame count, so call
    `render(out, 128)` and split the interleaved output into the two channel arrays.
  - [Certain] `decodeAudioData` is not available inside the worklet. Decode on the main thread, then transfer the
    Float32Array channel data to the worklet (transfer, do not copy). Decode lazily, per pad in use, like the app.
  - Commands go over `port.postMessage` as small plain arrays. Stamp every pad down and up with the event's
    `timeStamp` and convert to audio time with `AudioContext.getOutputTimestamp()`, so a late message does not skew
    where a recorded hit lands (this replaces the `System.nanoTime()` in `Engine.down`).
  - The playhead, heard step and meters come back over the port about 30 to 60 times a second.
  - Never allocate in `process()` (the app's rule since build 47). JavaScript garbage collection in the audio thread
    is the web's version of the app's GC worry.
  - Heavy offline work (Bake's phase vocoder, bounces, stems, KeyFinder) runs the same engine in a Web Worker, not in
    the worklet and not in `OfflineAudioContext`, so files match what you heard.
- [Guessing] JavaScript runs this kind of float code maybe 1.5 to 3 times slower than the JVM. Grain voices cost about
  3x Stretch on the JVM already. Measure on Tyler's phone in step 2 and keep the app's voice limit (48, quietest
  fades first).

### Latency

- [Certain] Make the context with `new AudioContext({latencyHint: 'interactive'})`. Browsers do not let a page pick
  its buffer size directly; the hint is all you get.
- [Certain] Show `baseLatency + outputLatency` in ms in the BPM readout, like the app's lag line. Count glitches where
  the browser reports them.
- [Guessing] Expect 20 to 60 ms from touch to sound on Android Chrome and somewhat less on an iPhone. The app with
  AAudio exclusive mode will always beat it. Say so to Tyler with the numbers from the probe, not from memory.
- [Likely] The biggest web-only delay is a busy main thread holding back `pointerdown`. Keep hit handling to "post a
  message, add a class", draw the beat glow and meters on the compositor or a canvas, and never re-render the whole
  pad grid on a hit (the site's `renderPads()` on every `padDown` does that today).
- [Certain] With the clock inside the worklet, timing no longer depends on `setInterval`, so a hidden or throttled tab
  does not drift.

### Touch on phones

- [Certain] Use Pointer Events. On every playing surface: `touch-action: none`, `user-select: none`,
  `-webkit-user-select: none`, `-webkit-touch-callout: none`, `contextmenu` prevented, `setPointerCapture` per
  pointer. Track each `pointerId` on its own for swipes and holds (the app's two-finger drumming bug came from
  sharing one tracker).
- [Certain] `overscroll-behavior: none` on the page stops Chrome's pull-to-refresh from eating a downward swipe.
- [Likely] iOS Safari and Android gesture navigation steal swipes that start at the screen edges. Keep swipe-start
  zones a little in from the left and right edges.
- [Likely] iPhones report about five touches at once, iPads more. Enough for drumming; note it for chords on the
  Padiano.
- [Certain] Pinch zoom and double-tap zoom stop on surfaces with `touch-action: none`; leave zoom alone elsewhere.
- Hold and swipe numbers are in dp in the app; use the same numbers as CSS px (one CSS px is about one dp in Chrome on
  Android [Likely]).

### Fonts and pixel art

- [Certain] Load fonts from Google Fonts (the only stylesheet host artifacts allow). Use `font-display: block` for the
  key font so keys do not change width when it arrives.
- [Certain] The icons are 40 px pixel art and must only be drawn at whole multiples. Phones often have a fractional
  `devicePixelRatio` (2.625 is common [Likely]), so a CSS size like 40 px is not a whole number of device pixels.
  Pick the CSS size as `n * 40 / devicePixelRatio` for a whole `n`, set `image-rendering: pixelated`, and snap the
  icon's position to whole device pixels. Or draw icons into a canvas at device-pixel size with image smoothing off.
- [Likely] The enamel tint and dark offset shadow are easier to bake into the PNGs once (an export step beside
  `tools/icons/export.py`) than to recreate with CSS filters. Ask the app chat for that export so both use the same
  files (`shared/ASSETS.md` is meant to list them).

### Saving and opening files

- [Certain] Save a mess with the `downloads` capability (the viewer confirms). Audio must leave as a zip, as now.
- [Certain] Open a mess with a file input; that works today.
- [Likely] Autosave belongs in IndexedDB (localStorage tops out around 5 MB). Signed-in writers can also keep messes
  in `db` as now. Write the new copy first, then swap, like the app's crash-safe save.
- [Guessing] The File System Access API and Web Share with files are blocked or missing inside the artifact frame.

### What cannot match the app

- [Likely] Background play: iOS Safari stops audio when the page is hidden or the phone locks; Android Chrome may keep
  going for a while but there is no foreground service and no Stop control in the notification.
- [Certain] MIDI: Web MIDI works in Chrome, Edge and Firefox, never in Safari or on an iPhone. Plus the host question
  above.
- [Certain] Haptics: `navigator.vibrate` works on Android Chrome only. No pad buzz on an iPhone.
- [Certain] In-app updates do not apply; a republish is the update.
- [Likely] Exclusive low-latency output (AAudio) has no web equivalent.
- Notes for Claude: if the page cannot reach GitHub, store notes in the artifact's `db` (the NSD-27 alpha idea) and
  have the app chat's triage read them there. Voice notes can use the `dictation` capability in Chromium browsers.

### The screens

- The behaviour that makes the app feel like the app is not all in the engine. [Certain] `AppState.kt` is 3,748 lines
  of hold, latch, chords, recording, undo, swipes, mute and drop rules, mixed with Android code. Port that logic with
  the app's numbers, not from memory, and keep a test per rule.
- [Guessing] Compose Multiplatform has a browser target (Wasm, drawn on a canvas). Once the iOS work moves the screens
  to Compose Multiplatform, the web could run the very same screens. Today it is heavy (several MB), young, and weak
  for accessibility and text. Not for now; worth a look when the shared UI exists.
- Use the app's tags (`Modifier.tagged`) as `data-tag` attributes on the web. The FAQ, lessons and tests can then share
  one list of controls, and `shared/PARITY.md` (once it lands) can track each one with a web column.

## 4. Build order

Small steps. Each one ships to the live site and leaves it working. Each step removes the old site piece it replaces.

0. **Probe.** One throwaway artifact that answers every question in section 3's table and prints the latency numbers on
   Tyler's phone. Bring Tyler the results and the hosting choice. Nothing else changes.
1. **Mess format.** A loader and saver that match `Project.java` field for field, keep unknown fields, accept 16 to
   128 steps, slot 64, aliases and unknown keys (shown as missing, never dropped). Round-trip the `shared/fixtures`
   messes (or messes exported from the app until fixtures land). Ship: app messes open on the site with nothing lost.
2. **Engine in a worklet.** The adapter, the worklet host, sample loading and transfer, the latency readout. Engine
   source per section 3 (Kotlin/JS, else TeaVM). Golden render tests against the JVM. Ship behind a setting, old
   engine still the default.
3. **Shell and look.** Top bar, tab slot with the five tabs, bottom dock, the panel Look, fonts, icons, Classic skin.
   Pads page with banks, Click and Count. Switch the default to the new engine when step 2's tests pass.
4. **Pads, fully.** Multitouch, swipes with HotKeys, swipe cancels its hit, empty-pad corners, 16 Levels, Mute and Drop,
   undo toasts. Remove the old pad menu and double tap.
5. **Loop strip and Steps.** Loop strip (bars 1 to 8, Copy to, Clear, Bottle it, Wait bar, Song painter), Steps with
   accent and ghost, the step sheet (chance, time round, strength), locks, row tools, Roll behind the slider. Remove the
   old Song, Roll and loop rows.
6. **Edit tray, Sample sheet and Sounds tab.** Library with loops, hits and aliases (upload the WAV or MP3 copies),
   Starred, search, Workbench, the full Sample sheet.
7. **Keys.** Hold (counts, Last, On the beat), Chords (12 sets, smooth voicing), Repeat (rates, styles, patterns), the
   key panel, the Padiano, the pitch wheel.
8. **Perform.** FannyPack, FX vials per pad (saved FX, Bypass, Auto lanes), LFO and pad filter, XY per pad with
   circles and motion recording, the Perform tab with master throws, the perform row.
9. **Synth tab.** All six kinds, the scratch pad, Put on pad.
10. **Files.** Messes sheet with autosave, WAV, stems and MIDI file exports through the worker.
11. **Help and settings.** What the FAQ, Learn lessons, Settings (Skin, HotKeys, padFlick, Large keys), the other four
    skins, Notes for Claude.
12. **Web-only extras last.** MIDI map (where allowed), mouth control (where the mic works), keyboard shortcuts on
    desktop, wake lock.

For each step: update `site/DESIGN.md` `## Now`, run the site's precheck (rebuild `site/tools/` if it is still
missing), and tell Tyler in a few lines what changed and what to try, with [Certain], [Likely] or [Guessing] tags.
