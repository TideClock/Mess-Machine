# Mess Machine for Android: design doc

## Now

Rewritten at the end of every session and at each step of a long job (BT-2). Read this first.

- **In progress (2026-11-21): the overhaul**, Tyler's go on Nov 21 for all builds with rollback built in. Plan and
  review: https://claude.ai/artifact/PjAnmWrBStAqX9wNEc6ZHf ; skin mockups: https://claude.ai/artifact/WBbiA2MqRCDftPaEuayoVV .
  Rollback: build 104's code is commit f704a4c (also release build-104); every green build from 105 on keeps its own
  build-N release. A rollback rebuilds old code with a new build number (Android will not install a lower one).
  Build 1 (trust fixes): saved FX faders (PadOpts.fx), undo per take, Bottle it makes a pad (Pour it out shares),
  cleared lanes let go, crash-safe saving (.new then rename, .bak kept), start card for new installs, Wave names.
  Build 2 (shared layer): each pad control is hand (mod/release), else lane, else home (PadOpts.fx, adopted from every
  snapshot); the LFO adds on top. One pad filter (PadOpts.ftype; the LFO's own filter is gone and old messes move
  over in PadOpts.fromMap; "ftype" in a mess marks the new format). Swaps and clears carry lanes and pad options.
  Every mess is copied once to files/kept-before-106 before the new format saves.
  Build 3 (doors): Menu is Edit (pencil icon ic_edit), Repeat on the bottom bar (FX moved to the perform row's Throws),
  the side dock became the perform row (PerformRow in Dock.kt, swipes up above the bottom bar; PERFORM_SKIP leaves
  out Hold and Rec). Settings, Layout: new / side / classic (classic = build 104's Menu, FX and side dock). Pads
  mark an LFO (yellow), a lane in this loop (teal) and a resting FX fader (red). Not yet: the one Edit tray.
  Build 4 (velocity): Pattern.acc per sound per step (0 ghost, 1 normal, 2 accent; "acc" in the mess, g . a);
  Engine.STEP_GAIN .42/.85/1; engine.down(..., vel) with velGain .3 + .85 v; RecEvent.vel records ghost (<=.55) or
  accent (>=.95). Tap a lit step: normal, accent, ghost, off. 16 Levels (levelsPad, ids 4000+). MIDI velocity in.
  Build 5 (skins): ui.Skins (classic, specimen, stage); C's colours are getters over C.skin (state), so every screen
  follows. Skin also sets grit strength, sticker shadow (null keeps each sticker's own) and pad face (jar, glass, band).
  Pref "skin". Look (classic, dark, light) is the button skin and stays separate. 
  Build 6 (chance): Pattern.chance per sound per step (0 to 100 percent, 101 1:2, 102 2:2, 103 1:4, 104 4:4; "chance" in
  the mess). Engine.loops counts loop wraps since Play; dice is seeded (11). Hold a step for the step sheet (chance,
  time round, strength). One-step locks that snap back are not built: step pitch (notes) and lanes cover most of it.
  Builds 3 to 6 went out in one push.
  Build 7 (stems and MIDI): Engine.bounceStems soloes each used, audible sound and pads all stems to one length;
  MidiOut writes format 1, PPQ 96, a tempo track plus one track per sound (C3 = 60 plus the step pitch, velocity 48,
  100, 127 by strength, chance ignored). Messes, Pour it out, For a DAW. The master bus control is not built.
  Build 8 (two more skins): felt (light: Cream is the words colour, so it is dark; pad face "felt"; Skin.light turns
  the status bar icons dark) and rust. ink() now picks whichever of Ink and Cream contrasts more, so it works on light
  skins. Builds 7 and 8 went out in one push.
  Last build (the rest of the plan): the Edit tray (tray "edit:<pad>", ui/EditTray.kt; drawers sound, fx, keys, mod,
  more; pref editTab) opens from Edit in New and Side, Classic keeps PadMenu. Step locks: Pattern.locks "sound@target",
  NaN for none, saved as "locks"; Engine.combine is hand, then lock, then lane, then rest; a lock holds for its step and
  lets go on the next step, Stop or panic. Master bus: Project.squash (default .5) and level (default 1), saved only
  when changed; thr 10^-squash, ratio 1 + 6 squash, makeup (1 + .5 squash) level, so the defaults are the old bus.
  Nov 21 (Tyler's notes, all approved): the panel Look (UiLook.id "panel", default for everyone once via pref
  panelLook; Btn draws Modifier.bakelite, BarBtn draws PanelKey with the Pro icons tinted to one colour; keys keep a
  40 dp real hit area at any view size). CloseScrew replaces every Done (tags kept); lanes, row tools, loops strip and
  key panel got one; Back closes every panel. padFlick (default on): a flick up off a pad opens its Edit tray. Repeat
  rate and pattern are two DockWords (dock-rate, dock-pattern) and two Selects in the tray. BAR_TABS: Pads, Synth,
  Steps (Seq, with SeqSwitch to Roll), Song, Perform (PerformScreen), Sounds. Empty pads act on a still tap's lift
  (emptyActsOnLift). Lessons use dock(tag) targets per layout. Notes: late notes say when they were written; a page
  opened without the key is OPENED until Check for replies finds it; Send all without the key = one bundled page.
  Panel, sharper (Nov 21 evening, Tyler: icons and scaling looked weird; wanted a worn font, text plus icon, more
  defined icons): fonts Worn (Rubik Distressed) on keys, Stencil (Black Ops One) for stamped words, Lcd (Share Tech
  Mono) on readouts, all OFL from Google Fonts via npm. InlayIcon draws the 40 px icons at a whole multiple, in their
  own colours through enamel() (less saturation, warmer) with a dark offset shadow. Btn shows Icons.forLabel(text)
  beside the word when not tight. Top bar keys share equal weights in the panel Look.
- **Live before the overhaul:** build 104 (green, 2026-11-18). The final plan (builds 96 to 101) shipped as runs 97, 98, 101, 102, 103 and 104
  (runs 96, 99 and 100 failed on tests or a compile error and were fixed; run 98's phone test was rerun after an
  emulator download broke). Notes 20, 21, 22 closed. Plan doc: https://claude.ai/artifact/SMmt7Jz3BfDsP7xUqLLEqq .
  Not heard by ear yet: Pump, Tape, Phaser, Freeze, the voice limit's feel. The first in-app update Tyler can try is
  any build after the one he installs.
- **Earlier live:** build 92 (green, published 2026-11-17). The whole approved proposal (84 to 95) shipped in two builds:
  88 (bottom dock, Menu only, chord slots and octaves, lesson bug, quarter note, slash, drag paint, MIDI one at a
  time and list, notes key) and 92 (FannyPack, FX and LFO strips, triplet and dotted repeat, pitch wheel, row
  tools, MIDI round two, Step mode, What the FAQ, speed guide, Moves). Runs 85 to 87 and 89 to 91 were test fixes.
  Notes #16 to #19 closed; #15 (question, answered) open. Nothing heard by ear yet: LFO, filters, voicings.
- **Earlier live:** build 83 (green, published 2026-11-14; note #12 closed): the full test pass. 59 tests pass (51 big test
  flows, the monkey test, 4 audit widths, 3 chord tests); the emulator phone test is clean (no crash, two monkey
  runs clean). Audit at 369 dp: 8 cut-off labels left (all sound names, plus the keys bar's button with the dock
  open), 6 buttons under 32 dp (five of them 30 or 31 dp wide in the top bar and the Song palette, and the effect
  pencil). 82 passed the big test and was replaced by the third pass before publishing.
- **Next for the tests:** make the audit a ratchet (a baseline file of today's findings; any new small button or
  cut-off label fails the build), with the next app change so it does not cost a build of its own. Not tested by
  anything: real MIDI hardware, real mic input for mouth control, and how the sound is by ear.
- **Before that:** the second and third test passes (narrow screens, small targets; see the
  2026-11-14 decisions).
- **Before that:** build 81 (80 passed the big test but was replaced before it published, because
  its new Menu button squeezed the bank numbers to dots with the dock open): the slim keys bar (Hold, key and chords, always there, on the Play tab
  and the Synth tab), Menu by the bank numbers for every pad, the Synth tab's pad picker (note #12), and the MIDI
  map (see the 2026-11-14 decisions).
- **Before that:** build 79: note #11 (Hold bar on the Synth tab, the synth's Padiano on the grid), Padiano XL and rows,
  Repeat patterns, mouth control (see the 2026-11-12 decisions). 76 to 78 failed on the way: setX functions next to
  var x clash on the JVM (precheck rule 8 now), an Int in a test Offset, and a test measuring the clipped bounds.
- **Before that:** build 75: loop keys and tempo (KeyFinder, 38 of 90 loops keyed, Workbench sounds keep tempo and key), the
  pad menu window, tempo that follows the slider, the Repeat panel, the Sample button fix. From the demo
  conversation (see the 2026-11-11 decisions). Tyler is checking the loop keys by ear.
- **Before that:** build 74: Hold latches grid pads; 29 lessons. (73 failed one test: the test had to render audio for "played" to count.)
- **Before that:** build 72: interactive lessons (Learn, 16 lessons). Builds 69 to 71 failed their tests on the way: the
  mess comparison tripped on the save time, the dock started folded so H wasn't on screen (lessons now open it),
  and Hold latches notes played as keys, not grid pads (the Hold and On the beat lessons go through Keys).
- **Before that:** build 68 (2026-11-11): the whole sound engine plan v2. Grain and Stretch, Sustain, repeat styles,
  Hold on the beat, the Sample sheet (61); bake and Latency (62); the Synth tab with Analog, FM, Wavetable,
  Granular, Drums and Pluck (63 to 65); Qbldy Loops and Hits replacing the 0.35 s chops (66); packs (68).
- **Plan:** the review doc https://claude.ai/artifact/QoAq7LzDGYD8XNyDDjPRHS, items MM-1 to MM-24, with an Outcome
  section at the top. MM-11 (Play Store) is last and notes only. MM-5 (Oboe) waits for real numbers from the Audio
  check. MM-15 (Fold layouts) skipped with a big note. MM-22 (creature cards) is an outline in the doc.
- **Next:** sound engine plan v2, piece by piece (see Decisions). Built: 1 to 3 (build 61), 4 bake and Latency
  (62), 5 the Synth tab with Analog (63), 6 and 7 FM and wavetable (64), 8 and 9 granular, drums and plucks (65), 10 loops and hits (66), 11 the Vault and packs (68; 67 failed to compile, see precheck rule 6). The first pack waits for Tyler's review in the Vault. Tyler's Latency note still decides MM-5.
- **Not done, and why:** MM-14 strong skipping stays off: composable lambdas that read the mess without snapshot
  state would stop redrawing (note #6 again), and nothing measured says the UI costs audio time yet; the Audio
  check's "screen busy" flag will say. MM-21 (Heap sorting) needs ears: 0.35 s slices of full songs have no right
  answer a script can check; the sorter now reads FLAC and sorts exactly as before. Pads still play when held for
  their menu (decision 4: keep).
- **Two tracks (2026-11-10):** this file and `app/`, `tools/`, `.github/` belong to the app conversation. The site
  and the web Mess Machine rebuild run in a second conversation that owns `site/` only (see site/DESIGN.md).
  Pushes touching only `site/` skip the app build, so the two never cancel each other's runs.

## Decisions

One line each, dated, with the reason and who decided (BT-2). Older decisions live in the plan sections below.

- 2026-11-22 Loops and the cut (Tyler's video and his answers to the cut/look review): the loop strip under the top
  bar is the one place for loops: pad-sized A to H (hold for the loop menu), Wait bar/Now, Click, Count (with Rec on,
  Play from a stop counts in off/1/2 bars), - + 2x (any 1 to 8 bars; + adds an empty bar), the loop's bars, Copy to,
  Clear, Auto, Bottle it and the Song painter. Steps, Roll and Song lost their own loop and bar rows (a one-line "where"
  chip opens the strip); Song has no tab. The loop readout swipes for the previous/next loop. The click is on by
  default. A swipe off a pad cancels its own hit without adding latency: the hit sounds on touch, and once the finger
  has gone 44 dp inside 300 ms its voice fades in 6 ms and a recorded hit is taken back (only if it turned the step on).
  Swipe right opens the FX vials as a strip under the row in every layout, two rows of wider vials on a phone. Synth:
  one row of kinds, Simple/Full and Poly/Mono/Acid as one-tap switches, Synthipad removed, replacing a pad that has a
  sound takes a second tap. Look: only the milled tab slot with the raised current tab (Settings, Look, Tabs: keys
  goes back) and readouts that shrink to fit; help lines are off for everyone (Explain in Settings brings them back).
  Not built, per Tyler: the edit tray stopping above the dock, Song inside Seq, the rest of the look spec.
- 2026-11-22 Sizing, first build (Tyler: "build it", after two reviews of the sizing proposal): floors are real dp on
  the glass (Float.rdp divides by View size). Bottom dock keys share one width, 44 real dp sized for their widest
  word (Hold/Last/8/8) where there is room, never scrolling. Tyler, after a build put Chords and Repeat behind More
  ("I hate it. I need chords and repeat"), chose smaller keys when folded: they go down to 38 real dp so all seven and
  the key readout stay; only narrower than his folded phone does a key go behind More (Pack first, Edit never). The pad grid no longer shrinks a second time with View size. Loop strip keys are
  at least 44 real dp. The audit fails the build when a bar key or pad-0 is under its floor at 369, 411 and 707 dp and
  at 70 percent view. Next: the top bar (Tyler sets its priorities), Menu size, Pad layout 4x4 or 2x2.
- 2026-11-22 Second video and the look spec (Tyler's "3"): gone: the Sample key on Play (the pad's Edit tray has
  Sample), the FannyPack's zipper bar (Pack opens and shuts it, a swipe up on Pack picks its strips), the where-line
  on Steps and Roll, the loops row on Perform, the beat dot in the perform row, and every explanation line (Help is
  a no-op; the FAQ still explains each control). Pick-one groups are sliders (a milled track, a raised block on the
  pick, tap or drag and let go): synth kind, Simple/Full, Poly/Mono/Acid, LFO wave, pad filter Lo/Hi/Band,
  Straight/Triplet/Dotted, Steps/Roll, and the loop strip's Loop/Song sides. Synth's top is two short rows beside its
  pad. The loop strip's keys are as wide as what is on them; Song's keys read Play loop and Play song on one row.
  The perform row is one short panel: keys in a row, the pitch wheel and the filter on their sides. Hold's tray is
  one bar (-, count, +, Let go). A quick swipe up on Pack, Hold, Chords, Repeat, the key readout and the perform row's
  keys opens their settings whatever the flick switch says; Edit stays for now. Steps names are grid cells, level with
  their steps. Look: bars and trays are one plate (#24211F) framed by one brass rule; in the panel Look a lamp's
  colour means one thing: yellow on, pink recording only, oxblood takes sound away, teal a shape, amber readouts.
  Kept: the worn key font (Archivo or worn is still Tyler's call).
- 2026-11-22 Review battery on build 153 (four reviewers: Tyler's notes, playability, code, device): fixed each finger
  tracked on its own for swipes (two-finger drumming dropped hits), count-in positions count back from the top (hits
  in the count were landing on steps 0 and 15; now only a hit within half a step of the downbeat records, on 0), pause
  keeps the rest of the count, repeats and beat pads keep going in the count, each recorded hit carries an id so a
  swipe takes back exactly its own, - and + clear accents, chances, locks and off-grid hits past the end and 2x copies
  them, the strip opens on Perform too and holds at most half the screen, Song folds when the page changes, - + 2x are
  full-width keys, the vials under a row are two rows while the Edit tray keeps one, a quick double tap can't replace
  a pad, mode lamps are yellow (pink is recording). Kept as Tyler asked (speed cost noted by the playability review):
  bar picks live only in the strip, not on Steps or Roll.
- 2026-11-21 Video review (Tyler, build 130): lamps sit in a metal lip under each key, only on keys that switch
  something; icons as big as the cap allows; keys widen with the screen up to a cap (UiLook.widthDp). Swipes off a
  pad are HotKeys in Settings, one action per direction (defaults: up Edit, down Padiano, left Sample, right FX).
  FX are glass vials with an in/out lamp each (hold: Auto lane) and Bypass that restores exactly; the master throws
  are vials too, where the finger lands is how much, on a brass master plate. Mutes (pad, bank) ride under the loops
  strip (LOOP on the top bar, any page) and on Perform. Chords on with no Padiano out opens one. The Synth tab opens
  on a scratch synth (Engine.SCRATCH, on no pad) that Put on pad copies onto a pad.
- 2026-11-21 Mutes (Tyler, second video): the mute rows were a wall of keys. MUTE on the dock makes the pads the
  switches (hold for a moment, tap to stay on), holding a bank number mutes the bank, DROP held leaves only touched pads
  (Project.dropKeep, never saved). Mute is also a HotKeys swipe, and the same swipe again closes what it opened.
- 2026-11-21 Lag, first half: 60 frames a second by default (Smooth screen setting), the beat glow and top bar flash in
  their own layers, no Random made on every key redraw, the Latency screen shows the fast path Android granted.
- 2026-11-21 Second video, Tyler's go on everything (proposal doc https://claude.ai/artifact/GbkRTdFSCg8Pa2115EHA1C):
  Cut fill is the amount of filtering (empty = off, like every vial); vial names open a settings card; Auto lanes draw
  in place over the vials (laneHere), not on Seq; saved FX with five built in (Wash, Grit, Tape, Space, Dry); colored
  caps on keys that open things (capTint); Large keys; buffer growth capped (Tight 3 bursts, Steady 8); clicks this
  minute on the BPM readout. Next: scratch synth on a hidden 65th pad (S3), one keyboard style, AAudio (L4).
- 2026-11-21 Scratch synth on its own pad (S3, Tyler): Project.SCRATCH = 64, SLOTS = 65; the slot is in no bank, saved
  with the mess, always holds a synth (ensureScratch), and is not counted as a loaded pad. The Synth tab shows a pad
  tile for it (tap plays, swipes follow HotKeys, same swipe closes). Put on moves its key to a pad so recorded notes
  follow, and a fresh copy stays. One keyboard (Tyler: "it needs to be consistent"): the Synth tab uses the Padiano,
  and the panel Look's Keyboard style is the synth's cream and bakelite keys. Open for discussion: whether Squares
  should stay the default or the Keyboard should be. Auto lanes over the vials (Round C) shipped with round A.
- 2026-11-21 AAudio (L4, Tyler: "definitely move the sound onto AAudio"): app/src/main/cpp/mmaudio.c opens a
  low-latency AAudio stream (exclusive first, then shared) with a callback that only reads a lock-free ring; the Java
  engine keeps rendering on its own thread and writes blocks into the ring (fill starts at one engine block plus two
  device bursts, grows a block per click run up to the Buffer cap, shrinks after ten clean seconds). A dead stream is
  reopened up to five times, then AudioTrack takes over. Settings, Playing, Sound out switches to the older path.
- 2026-11-22 Chords (Tyler: 12 chords per set, smooth changes as the default, not Maschine's exact sets): every set
  is 12 chords, all in its home scale: the seven degree chords, then five colour chords. Revised the same day
  (Tyler: sets stay in key): a key in the scale plays its own degree's chord, the five keys outside it play the
  colour chords in order, and a set written in another scale moves the scale to it (the first build of the day
  followed the root only and played chromatic chords). Theory.smooth picks the inversion nearest the pad's last chord
  within a fifth of where it was asked; live keys, grid Keys and chord boxes use it, the Roll writes root position.
  Sets: Plain Major, Plain Minor, Sunday Pop, Dreamy Sus, Gospel Drip, Lo-fi Jazz, Basement Minor, Neo Soul,
  Cinematic, Haunted, Trap Dread, Power Stabs.
- 2026-11-21 Dock plan, not built yet (Tyler): the app needs one cohesive dock idea, so mutes and the other live
  controls can be brought up on any screen the same way. Pad things and master things (docks, FX) must look clearly
  different. Today's step toward it: the master plate (brass trim) for whole-mix controls, pad colour for pad ones.

- 2026-11-20 Deep review (Tyler, doc https://claude.ai/artifact/PjAnmWrBStAqX9wNEc6ZHf): the design reference is
  Freakshow Industries (playful, ominous, mysterious controls, one hero XY per screen). Playful names stay and grow,
  each with a plain caption. Play Store is the goal. Velocity: 16 Levels across the pads plus an accent per step,
  and MIDI velocity. FX means one fader strip. Build order agreed: 1 trust fixes, 2 shared parameter layer,
  3 one Edit door, 4 velocity, 5 skin system with Stage and Specimen, 6 step locks and chance, 7 stems and master
  bus, 8 Felt and Paper and Rust. The side dock is still open: Tyler asked how its controls stay reachable.

- 2026-11-18 Builds 96 to 101 (Tyler, final plan https://claude.ai/artifact/SMmt7Jz3BfDsP7xUqLLEqq): soft voice limit 48
  with the quietest fading first, delay given back after 10 clean seconds, growth logged with voices; loops from the
  top bar (a pick waits for the bar line, or Now); the view row is gone (Step and Keys modes retired, Padiano Full
  size, FX on the bottom bar, My Desk is a Play layout in Settings); Padiano rows run straight up; wheel Stay snaps to
  the key; XY At the start and At the edge; FX fader picker; Wobble is an LFO preset; Moves is called Auto; Menu then
  tap opens a control's settings in a tray, Menu twice the last thing touched, flick up behind a setting; the LFO
  strip keeps rate, depth, cutoff and res; the text diet (playing pages explain only with Page help on); the pack's
  + is four icon tiles; Looks: Classic, Pro dark, Pro light with 72 pixel icons (tools/icons) on printed rails and a
  lag readout; the finer time grid (24 ticks a step) records triplet and dotted repeats, with Rec exact as a
  setting; Auto shows the whole loop; Pump, Tape, Phaser and Freeze as live sends.
- 2026-11-17 Update from inside the app (Tyler): the app reads the latest release, downloads the APK and installs it
  through PackageInstaller with USER_ACTION_NOT_REQUIRED (Android 12+, an app updating itself). The signing key stays
  in the public repo: Tyler does not care that it is public, do not raise it again.
- 2026-11-17 Quick notes (note #20, Tyler): tap the bug, talk, tap again; the note keeps the state and taps from when it
  was spoken and waits on the phone. The notes list lives in the menu with Send all.
- 2026-11-17 Menu is the one way to a pad's menu (Tyler): a hold always plays; carry and swap became Menu's Move.
  Menu, Hold, Chords on/off and the key button sit on a bottom dock that stays on screen on Play, My Desk and Synth
  (Tyler: "it can't be stuck to the top"). The key panel opens above the dock; a Padiano no longer opens it.
- 2026-11-17 Chords never wander (Tyler): no per-note folding (root always lowest, a chord past the range moves by
  whole octaves); the Roll and the Padiano's keys reach C1 to C6 (ROLL_LO -36, ROLL_HI 24, two octaves on screen);
  chord sets are fixed slots, Maschine style: slot N is always the same chord, a key's slot is its place in the
  scale, the same in every octave; the Padiano shows a set as named boxes; Keys grid: pads 1 to 8 the set, 9 to 16 an
  octave up. New types 1-4-5 (sus) and Octaves. Chords on the dock flips to single notes and remembers the type.
- 2026-11-17 Notes: option A (Tyler, after the correction that B needed his setup too): a fine-grained GitHub key
  pasted once in the note box; Send posts with it, offline notes wait; without a key Send opens the page as before.
- 2026-11-17 Controller-agnostic MIDI (Tyler): nothing is built for one controller. The map works on his hardware.
- 2026-11-17 Steps names: two lines, 84 dp column (Claude, Tyler left it to me).
- 2026-11-17 "What the FAQ" is the helper's name (Tyler). FannyPack holds only the strips you add (Tyler).
- 2026-11-17 FannyPack built (Claude, from Tyler's idea): per-pad `pack` in PadOpts (keys, fx, lfo, xy); open packs
  are the old `keyboards` list (one per row); an empty pack opens with Keys; Pack on the bottom dock (not on Synth),
  zipper bar with the pull, strip names to hide or show, + to pack. FX strip = live sends (engine.mod), kept until
  Off, recorded as moves. LFO in PadOpts (lfo* fields, saved as one "lfo" string); engine lfoBlock sets the pad filter
  (Lo/Hi/Band, no glide) and moves one target per block. Wobble kept as is (Claude: folding it in would change old
  messes). Repeat: any rate, straight x1, triplet x2/3, dotted x1.5; off-grid repeats are not recorded.
- 2026-11-17 MIDI round two (Claude, Tyler: any controller): map keys are layered, key = (page * 2 + shift) * 128 +
  note (old maps are page A); "act" targets for buttons (note or CC, CC fires on a rise past 64); knobs reach fx, wheel,
  dock cut/res, lfo rate/depth and tempo by touching them while the map is up; pitch bend drives the dock wheel.
  Step mode is a Play view (Pads, Keys, Step); pads read left to right, top to bottom. What the FAQ replaced the ?
  (page help moved to its card and Settings); Faq.kt has a line per tag pattern and the big test fails on a tappable
  control without one. Moves is a panel on Steps (lane tools in Project.Pattern: lane, laneSmooth, laneStamp).

- 2026-11-10 Review go-ahead (Tyler): build MM-1 to MM-14 and more; skip MM-5 (Oboe) until the Audio check shows
  what the lag is; MM-11 (Play Store) after everything else; skip MM-15 (Fold layouts) with a big note; MM-22
  (creature cards) as an outline only. Decisions 1 (cut the Workbench Resample) and 5 (notes privacy) unanswered,
  taken as the recommended answer.
- 2026-11-10 Bundled sounds are FLAC (Claude, MM-8): sample-exact (EngineTest checks the kick), 31.7 MB to 14.7 MB.
  Keys stay ".wav" so messes from the site still load.
- 2026-11-10 Never format() a string with ${...} in it (Claude): build 58's audio thread crashed on "0% average".
  tools/precheck.sh flags it; a line can say // format-ok with the reason.

- 2026-11-10 Overhaul built in full (Tyler): Play tab with Pads, Keys, My Desk; Hush gone; dock folded; C minor;
  pad card gone; Steps one row (unanswered, taken as yes); help behind ?; all utility ideas; pad buzz.
- 2026-11-10 Design guidelines, not hard rules (Tyler): one home per control; a second home needs a written
  reason; unsupported duplicates are removed without asking and listed in the report; close calls come to Tyler.
- 2026-11-10 Targets (Tyler): 5 tabs, about 12 controls per screen outside the playing surface, menus of 8 then
  groups, 1 tap while playing, 2 for settings, 3 at most.
- 2026-11-10 Trace IDs on proposal items (Tyler): BT-1 to BT-10 for the skill proposal, used in commits and tests.
- 2026-11-10 In key only: one home, the Padiano's settings (Claude, BT-1): it only changes the Padiano.
- 2026-11-10 First design check (build 53): loop tools removed from Roll and Song (unsupported duplicates,
  Claude, BT-1); scale pickers kept on Roll with a reason; Sounds' chip rows and list rows count once each.
- 2026-11-10 The sheet backdrop no longer acts as one big button (Claude): it hid every sheet's controls from
  screen readers and from the design check.
- 2026-11-10 Second design check (build 55): rows of choices count once (`-chips` tags); Doctor tools tab
  allowed 13 controls with a reason; the design check is strict from now on (Claude, BT-5).

Sound engine plan v2 (doc https://claude.ai/artifact/Vsr6HBLcVa8wehHaNbqSD9), built as separate tested pieces:

- 2026-11-10 Pitch modes are "Stretch" (tape, as before) and "Grain" (keeps the length) (Tyler, SE-1). Default
  Auto keeps the length for keys, chords and Roll notes (rateMul not 1), and keeps the Doctor's Tune as tape
  (Tyler: decision 1, yes). Tape stop always stays tape.
- 2026-11-10 Grain engine (Claude, SE-1): two Hann-windowed read heads 50 ms apart, each new grain aligned to the
  fading one by a 32-point correlation search over +/-8 ms. Ripple on a sine at +12 fell from 44% to 2%. Cost
  about 3x Stretch per voice (33 ms vs 12 ms of CPU per second of audio for 8 voices on the workspace); the bake
  (piece 4) is the answer when a phone runs hot.
- 2026-11-10 Sustain (Tyler, "perfect", SE-5): plays the attack, then freezes grains around 45% of the length
  (0.06 to 1.5 s in) with +/-12 ms jitter while held; fades on release.
- 2026-11-10 Repeat styles Cut (default), Ring, Flow (Tyler, SE-6). Home: the repeat rate menu, not the Sample
  sheet as the plan doc said (Claude): the style is a Live setting for every pad, like the rate, so it lives
  beside the rate. Session only, like the rate.
- 2026-11-10 Hold "On the beat" (Tyler, SE-7): a latched pad plays whole and restarts every 1, 2 or 4 beats,
  on the step clock while playing and on its own beat clock while stopped.
- 2026-11-10 The Doctor becomes the Sample sheet with Play as its first page (Tyler asked for a "very
  accessible" samples menu): pitch, when it is held, beat rate. The held chips moved there from Shape (one home).
  A one-tap Sample button on the Pads bank row is a second door to the same sheet, not a second home for any
  setting (Claude): the pad menu is two touches and a hold away while playing.
- 2026-11-10 Synths get their own sixth tab (Tyler, overrides the 5-tab target): different enough from samples
  to need a big screen. Bake (phase vocoder render) lives in three places on purpose (Tyler): the Sample sheet,
  the latency screen, and a pop-up when the CPU stays high.
- 2026-11-10 The 0.35 s Heap and Qbldy chops go once loops and re-cut chops replace them; the song chop switch
  (SE-8) is dropped (Tyler).
- 2026-11-10 Bake built as a phase vocoder (Claude, SE-3): 2048-point frames, hop 512, identity phase locking,
  phase reset on hits, then a cubic resample with a 63-tap anti-alias filter going up. Every semitone within an
  octave, sounds up to 6 s, 96 MB for all bakes in a mess. Measured against live grain (added envelope wobble in
  40 ms windows): chords and bass 13 to 41% live vs 4 to 14% baked, glass harp 16 to 18% vs under 1%, sine 3% vs
  1%. Bake stays off by default, as agreed; turning it on for keys automatically is a question for Tyler.
- 2026-11-10 The Audio check is renamed Latency and is the "latency screen" of the bake plan (Claude): same home,
  in Notes for Claude, plus Keep it light. The CPU pop-up asks once a session, after 20 s over 70% load with live
  grain playing.
- 2026-11-10 Synth tab (Claude, SE-11): the tab is labeled SYNTH and sits right after PLAY (it is an instrument);
  the pad view is called Synthipad (Tyler's suggestion). A synth sits on a pad (key "synth:<n>", saved in the
  mess under "synths"), so every pad path plays it; the Synth tab edits the synth on the selected pad.
- 2026-11-10 Synth voices reuse the sample voice (Claude): the engine's choke, tone, effects, pan, bend, wobble
  and hold all apply on top. Knob turns reach held notes (the voice rereads its patch every block).
- 2026-11-10 Analog first (Tyler: poly with mono and acid): PolyBLEP oscillators, a zero-delay ladder (24 dB;
  acid takes the 18 dB tap) or state-variable filter, control rate every 16 samples. Costs on the workspace:
  acid about 0.5% of a core per voice, a 3-voice unison pad about 2%. Steps play a synth for one step (acid
  half a step); Roll notes for their length; acid notes that touch slide.
- 2026-11-10 Knobs are not counted by the design check (they are a playing surface, like keys); each knob group
  is one row of controls. The design check now allows 6 tabs from design/exceptions.txt.
- 2026-11-10 FM (Claude, SE-13): four operators, eight algorithms (bitmask tables), feedback on operator 4,
  16 ratios including 1.41 and 3.5 for bells, an envelope per operator, no filter. About 2 to 5 ms of CPU per
  voice-second on the workspace.
- 2026-11-10 Wavetable (Claude, SE-14): six tables made in code on first use (92 ms, about 2 MB): 16 shapes each,
  6 levels of detail picked by pitch so high notes do not alias. Shares Analog's filter, envelopes and LFO
  (one voice class, a different oscillator per kind). Switching kind loads that kind's first preset.
- 2026-11-10 Granular (Claude, SE-15): up to 24 grains per voice from a sound on one of the mess's pads (the
  patch keeps its key in "src"; the source loads like a pad sound). The source counts as middle C. A pad with
  no source is silent. 2 to 11 ms of CPU per voice-second.
- 2026-11-10 Drums and Pluck (Claude, SE-16): 8 drum models from sines, squares, noise and state-variable
  filters, always one-shots; a Karplus-Strong string with a pick-position comb, loop damping (its delay taken
  off the string length, so it stays within 1 cent up to G6) and a body resonance. The pluck burst is
  normalized (it clipped before the fix).
- 2026-11-10 Six kind chips wrap rather than squeeze into one row (Claude).
- 2026-11-10 Loops and hits (Claude, SE-4, SE-9, SE-10; tools/make_loops.py): song tempo from bar self-similarity,
  sharpened by a least-squares sixteenth grid through every attack; 44 of 45 songs land within 0.04 bpm of a
  whole number, so tempos snap when within 0.35 (the old manifests' 120.19 and 80.75 were 0.2 to 0.9% off).
  Two one-bar loops per song (loud, repeating into the next bar, 15 s apart), cut to the sample with a 5 ms
  crossfade (no seam step larger than the loop's own); four hits per song from attack to next attack, max 0.6 s.
- 2026-11-10 The 390 Heap and Qbldy chops are removed (Tyler); their keys alias to hits from the same song in
  library.json "aliases", so old messes still open with sound on those pads. Hits are typed "chop" (the
  kick/hat guess on full mixes was unreliable). Sounds: 15 MB to 18 MB.
- 2026-11-10 Loops fit the mess tempo (Claude, SE-4): Grain keeps pitch; Stretch or tape stop fit it like tape.
- 2026-11-10 RC-505 library (SE-10): 600 clips from the 96 stems in library/rc505/ (back end, not in the app);
  CI ignores library/**. Verified here: all decode with the app's FLAC reader, all 322 loops exact to half a
  sample.
- 2026-11-10 The Vault (Claude, SE-17): a published review page, https://claude.ai/artifact/QEtqaBRxjr9qZDchAM92rS,
  with all 600 RC-505 clips (one WAV per song, played through Web Audio so loops repeat without the gap an audio
  element leaves; FLAC is not a type artifacts serve). Verdicts and packs live in its shared store
  ("decisions", "packs"). Claude's 48 starter picks are marked, not kept: Tyler decides.
- 2026-11-10 Packs pipeline (Claude, SE-17): tools/make_pack.py turns a Ready Vault pack (kept clips only) into
  <id>.zip (pack.json + clips/*.flac) and packs.json, uploaded to the `packs` release; the app's Packs sheet lists
  it, installs into its own storage (zip entries outside the pack folder are refused), shows a jar, updates by
  version, removes. Packs are public and free; paid packs would need accounts and Play billing (a later call).
- 2026-11-11 Interactive lessons (Tyler asked for "interactive tutorials for each feature"; Claude designed them,
  MM-T): 16 lessons in Learn (top menu). Each step has a target tag and a check on the app's own state, so it
  moves on only when the thing is really done. Every UI tag now goes through Modifier.tagged(), which also
  records where the control is on screen, so the lesson card can ring it. Lessons run on a practice mess:
  autosave pauses, the real mess is kept in before-lesson.json and restored at the end or on the next start.
  Menu items open in their own window, so lessons ring the pad instead and say what to tap.
- 2026-11-11 Hold latches grid pads (Tyler: "fix the hold issue"): a grid press under Hold uses the same latch
  rules as keys (slots, Last), under ids 1000 + pad so a latch survives bank switches; latched pads show an H.
- 2026-11-11 Lessons finished (Tyler): 29 in all. The Tools step of the Sample sheet lesson waits for Skip step
  (the open tab isn't app state). Favorites stars a real sound: stars live outside the practice mess.
- 2026-11-11 The pad menu offer has a window (Tyler: "make a solution up for the pad holding sustain issue"): a lift
  opens the menu only between 0.45 s and about 1.3 s after the touch; later the yellow outline goes and nothing
  opens or carries. A pad that sounds while held (a synth, Sustain, gate) never offers it: the bank row button
  reads Menu for it. That button also reads selV now; it used to keep the pad from before.
- 2026-11-11 The Tempo slider sets the tempo as it moves, one undo step on release (Tyler).
- 2026-11-11 Repeat's menu is one panel of three chip rows, Every, Plays, Each repeat, not 11 list rows (Tyler).
- 2026-11-11 Loops carry key and tempo (Tyler): `engine/KeyFinder` names a key only when sure (match 0.72, 0.08
  ahead of any other note set, tonal). `tools/LoopKeys.java` writes `mkey` into library.json (38 of 90; a song's
  loops share one name); run it after make_loops.py. Workbench sounds are measured when made (older ones once, in
  the background). A resample stores bpm and beats and so follows the mess's tempo (Claude's call: it is what
  "loops fit the tempo" promises); a mic take only remembers its bpm. Rows and the Sample sheet show both names
  for the notes ("C major / A minor"), and search matches either.
- 2026-11-11 Demo videos are rendered, not filmed (Tyler): branch `demo-render` holds DemoRender.kt (frame by frame
  video of the real screens with engine audio), its own workflow and tools/demo. None of it is on main; output
  goes to branch `demo-out`.
- 2026-11-12 Note #11, "the hold options dont appear on the synth screen" (Tyler: a held chord plus single notes on
  top works on the Padiano, not on the synth). The engine was fine (probe: poly holds three and plays a fourth on
  top, mono slides to the fourth and back). The Synth tab had only the dock's H, whose count you change by tapping
  the small number, so a count of Last or 8 meant every note took the hold. Fix: the Hold bar (count, Let go) sits
  over the keys in every Synth view. "🎹 On pads" opens the synth's Padiano on the pad grid (since build 75 a synth
  pad's long press gives no menu, so the Padiano was hard to reach); a synth's Padiano has ◎ back to its knobs.
- 2026-11-12 Padiano XL and rows (Tyler): size S M L XL; XL rows are 124 dp and squares at least 30 dp wide (they
  wrap sooner), keys at least 44 dp. Rows 1 to 3: each row up is the same notes an octave higher (Claude's call:
  octaves, like stacked keyboards, over fourths like a Push; easy to change). Padiano ids are now 10000 + pad x 200
  + (pitch + 96), so the high rows never collide.
- 2026-11-12 Repeat patterns (Tyler: "trigger every two notes, and then skip one"): Live.nrPat, one letter per
  repeat, x plays and . rests. While playing it counts from the top of the loop (on the grid the same way each
  pass); while stopped it counts from the press, which is repeat 0. Presets plus your own (tap a step, 2 to 16
  long), kept between launches. Hold's latched notes follow it (they repeat through Repeat). A separate gate
  pattern for Hold without Repeat was not built: Repeat plus Flow does that.
- 2026-11-12 Mouth control (Tyler: "make wubby noises and then convert it to automation"): the mic at 16 kHz into
  `engine/MouthTracker` (32 ms windows every 8 ms; vowel = pre-emphasized spectral centroid, 600 Hz to 1.8 kHz on
  a log scale; level -50 to -10 dB; a gate with 80 ms hang). The vowel moves one XY target (Filter by default: ooo
  closes, eee opens), loudness another (Nothing by default), on the picked pad, through xyMove, so Rec while
  playing records it as XY moves. Stay put (default) or spring back when quiet. Mic source: voice recognition (no
  loudness evening-out) plus the phone's echo canceller where there is one; headphones advised. Camera mouth
  tracking was not built: it needs ML Kit and the camera, a bigger step; mic first.
- 2026-11-14 The keys bar is always there (Tyler: "I would prefer that they always have at least a mini bar so that
  you can click it open. Same inside of the synth deep menu"): shut, it is one slim row with the Hold switch and a
  button that reads the key and chords ("D minor · chords off ▾") and opens the rest; open, it has root, scale,
  chords, Hold's count and Let go, and a ▴ to shut it. Opening a Padiano opens it and closing the last one shuts
  it, as before; Keys mode always shows it open. The Synth tab uses the same bar over its keys in all three views
  (it had only Hold's settings, and no chords). One switch for both tabs, not kept between launches.
- 2026-11-14 The pad menu on every pad (Tyler: "the press and hold on the pad to open options isn't working ...
  sometimes it just does work and sometimes it doesn't"): what he hit is the rule that a pad which sounds while
  held (a synth, Sustain, gate) gets no menu from a hold, because there a hold is playing. That rule stays, but
  the button by the bank numbers is now two: Sample (or Synth, for a synth pad: straight to its knobs) and Menu,
  which opens the picked pad's menu for every pad, always under the same name (it used to be one button that
  changed between Sample and Menu). A hold-and-lift on a pad that plays while held now says where its menu is,
  once per launch. The still-finger allowance for the hold went from 10 to 14 dp. Not done: a menu handle on
  the pad itself (it would take playing surface).
- 2026-11-14 Note #12: the Synth tab's "PAD n" is a picker of the pads that have synths, and with no synth on the
  picked pad the page lists the synths the mess has, one tap each.
- 2026-11-14 MIDI map (Tyler: "you just click on a pad or a note on Padiano, and it has like a little floating thing
  that pops up ... whatever you press on an attached MIDI keyboard, it moves to the right and down"): ☰ > MIDI map
  puts a small panel under the top bar; the page stays live under it, tabs included. What you touch is where the
  next controller key lands: a pad (then the next pad, right and down, to the end of the bank), a Padiano key (then
  the next key up, in key only when the Padiano is), or a synth knob (then the next knob the page shows; knobs take
  control changes, so any knob, slider or wheel that sends CC). Skip passes one; Clear empties the map. A mapped
  key plays its target exactly like a finger (pad taps pick the pad and record; Padiano keys play chords and obey
  Hold). Pads map by their place in the bank on screen, like notes 36 to 51 always did; unmapped notes keep the
  old rule. The map lives on the phone (prefs `midiNotes`, `midiKnobs`), not in the mess. Not built yet: mapping
  buttons (Play, Rec, the dock), velocity, a controller knob on pad filters or XY, and a map per controller.
  The MIDI reader also skips program change and channel pressure bytes now (one data byte each; they used to
  shift every pair after them). No hardware here: the tests feed notes and control changes in software.
- 2026-11-14 Full test pass (Tyler: "a full bug test and usability test as well as design test"): every test screen
  read by eye, plus a new `AuditTest` that walks every page, sheet and menu at 360, 411 and 673 dp and lists small
  buttons (under 32 dp), cut-off text, text under 10 sp and controls off the right edge, to
  `usability-<width>.md` in the test screens. Fixed from it: the bank row goes to two rows under 352 dp; the Sample
  sheet's tabs are sized by their words and Effects is FX there; Favorites is Starred; Octave down a size smaller;
  the Padiano's Rows on its own line; "Env to pos"; the keys bar's ▴ is 44 dp wide. A MIDI key on an empty pad
  does nothing (a finger there opens the jars; a stray controller key should not).
- 2026-11-14 Second test pass, from the audit's first numbers and its pictures at 360 and 673 dp. The first run's
  "cut off text" column was wrong (hasVisualOverflow is true for nearly every label with this app's line height);
  it now means a line that ends in an ellipsis or dropped lines. Widths are now 344, 369 (about a Z Fold's cover
  screen), 411 and 707 (about a Z Fold opened), and at 369 and up the words a player steers by must not be cut
  off or the test fails. Fixed: `Btn(tight = true)` (4 dp side padding) for buttons that share a row by weight
  (top bar, Play tab views, Sample tabs); Sounds views wrap at their own widths; pad names are sized from the
  pad's measured width (words broke mid-word at 360; bigger names on an opened Fold); the words under dock
  buttons are `DockWord` buttons 32 dp tall and the dock's width (they were about 24 by 13 dp); one-letter
  buttons are at least 40 dp wide. Notes now report the screen's dp size, view size and dock state. Left on
  purpose: the effect buttons' 24 dp pencil corner (bigger would take playing surface).
- 2026-11-14 Third pass, from the corrected audit at 369 dp: Synth view chips sized by their words; "Oct down"
  (was Octave down) and effect names of 10 letters or more a size smaller; "Hear steps on tap"; the slices
  buttons wrap; the keys bar's button is tight; the synth "On pad n" button says what it replaces in a line
  under it; ↶, ? and ☰ are weight .55 in the top bar (32 dp at 369). Still open, needing Tyler's call: sound
  names in the Steps list are cut at every width (the name column is fixed), a Padiano's title is cut when its
  sound's name is long, and pad names go down to about 8 sp for long words on a Fold's cover screen.

## Control map

Every control's one home, any second access point and why (BT-1). The dock and My Desk are shortcuts you choose,
so they never count as a second home. `design/exceptions.txt` holds the same reasons for the design check test.

| Control | Home | Also at | Why the second spot |
|---|---|---|---|
| Play, Stop, Rec, Undo, tempo | Top bar | Desk transport piece | Shortcut you choose |
| Undo after a clear | Top bar | The clear's message | Right where the clear happened, for a few seconds |
| Hold | Bottom dock (Play, My Desk, Synth) | Side dock, desk Hold piece | Shortcuts you choose |
| Repeat and its rate | Dock | Desk Repeat piece | Shortcut you choose |
| Filter and resonance | Dock | Desk pad filter piece, XY targets | Shortcut; XY moves it as one of its targets |
| Root, scale, chords | Bottom dock's key panel (Chords on/off on the dock itself) | Roll, desk Scale piece | Roll places and folds notes by the scale; desk is a shortcut |
| In key only | Padiano settings | none | |
| Bank 1 to 4 | Play tab (Pads) | Steps | Each page shows that bank's pads; switching tabs to change it would break the flow |
| Loop letters A to H | Steps | Roll, Song, Song palette | Roll edits one loop and Song plays one: both need to pick it. The palette paints the song |
| Loop tools (bars, Copy to, Clear loop, Bottle it) | Steps | none | Removed from Roll and Song 2026-11-10 (unsupported, BT-1) |
| Resample the loop | Steps | Workbench (Sounds) | Flagged for Tyler |
| Record a sound | Empty pads, pad menu | Sounds mic | Sounds records into the Workbench, not onto a pad |
| Mute, solo | Pad menu | none | |
| The pad menu | Menu on the bottom dock | Hold a sound's name in Steps or Roll | Those name rows are not playing surfaces; the pads have no hold menu since build 84 |
| MIDI map | ☰ menu | none | |
| Doctor | Pad menu | Sounds row menu | From a pad or from the library: different starting points |
| Hear it | Pad menu | Sounds, Doctor | Each hears the thing on that screen |
| Share a sound | Pad menu | Sounds, Doctor (as WAV) | Same: each shares what is on that screen |
| Click | Tempo sheet | none | |
| Messes, Settings, Redo, Learn, MIDI map | ☰ menu | none | |
| Notes for Claude (the bug button) | Bottom left corner, by the tabs, on every page | none | |
| How-to text | ? in the top bar | First visit to each page | Shown once, then hidden |

## The idea

Keep the Mess Machine on the site as the place where ideas get tried, and make the phone app the version you play. Both read and write the same mess file, so the two never drift into different instruments.

## Pillars

1. **Timing first.** A drum machine that drags is useless. Every step, swing, repeat and arp note is counted in audio frames inside the engine, never by a screen timer.
2. **Same mess, both places.** The app saves the site's v3 sequence JSON. Sound keys match the site (`samples/basement-kick.wav`, `qb:stwretch-hit`).
3. **Hands on the pads.** Pads fire on touch down, multi-touch works, and the controls you reach for while playing (play, rec, repeat, undo) stay on screen.
4. **The site's look.** Chunky stickers with hard shadows, Archivo and Bungee, the jar colours and the jar art.

## How it is built

| Part | Where | What it does |
|---|---|---|
| Engine | `app/src/main/java/.../engine/` (Java) | Voices, the clock, swing, song mode, note repeat, arp, recording, the master bus, offline bounce. No Android code, so it is tested on a plain JVM. |
| Project | `engine/Project.java` | The mess: 64 pads, 8 loops, song lane, edits. Reads and writes the site's JSON. |
| Library | `engine/Library.java` + `assets/sounds/library.json` | The 433 bundled sounds. Qbldy and Heap sounds are slices of sprite WAVs, decoded when first used. |
| Audio out | `AudioOut.kt` | An `AudioTrack` in low-latency float mode on its own high-priority thread. Starts with a two-burst buffer and grows it only if the phone underruns. |
| State | `AppState.kt` | Everything the screens read; every edit rebuilds an immutable snapshot for the engine, adds an undo step and autosaves. |
| Screens | `ui/` (Jetpack Compose) | Pads, Steps, Roll, Song, Sounds, the doctor's table, tempo, messes. |

How the UI and the audio thread share the mess: the UI owns the `Project`. After each change it builds an `Engine.Snapshot` (an immutable copy of what to play) and swaps it in. The engine never touches the `Project`. Live hits go to the engine through a lock-free queue, and recorded hits come back through another one.

How a sound plays follows `voiceEd()` on the site: trim, reverse, tune and fine, one-shot or gate, loop, attack and release, volume, pan, tone (low or high pass), normalize, choke groups.

## Built in 0.1

- Pads: 4 banks of 16, multi-touch, an empty pad opens the jars, the selected pad shows its sound with swap, doctor, hear and clear.
- Keys mode: the grid becomes the selected sound in any of the 10 scales and 12 roots, with chord modes (triads, 7ths, power, and the eight chord sets from the site).
- Steps: per-bank rows, mute, solo, 1 or 2 bar loops with paging, dots for roll notes.
- Roll: two octaves around the sound's own pitch, scale-coloured rows, fold to scale, note lengths 1, 2, 4, 8, chords drop as a stack.
- Loops A to H: copy, clear, length.
- Song lane: up to 64 bars, paint with a loop or a rest, chain your loops.
- Transport: play/pause, stop (stop twice to cut every sound), record, tempo with tap and swing, click, note repeat at 1/8, 1/16, 1/32 with repeat or arp up, down, up-down, random. Undo and redo (80 steps).
- Doctor's table: waveform with drag trim and every setting above.
- Messes: autosave, save, save as new, open, toss, new, import a site mess file, share a mess file, bounce a loop or the song to WAV and share it.

## Piano keyboards

Hold a pad for its menu (like a right click: Piano, Doctor it, Hear it, Swap sound, Star, Clear pad). Piano drops a keyboard right under that pad's row and lights the pad's bottom edge; one per row, so four pads, a piano, four pads, a piano. The grid itself never changes. Each covers C3 to C5 around the sound's own pitch (C4), shows about an octave and a half on a phone with ◀ ▶ to shift, plays with several fingers and slides, shades keys for the scale and tints the root, and follows the chord mode.

In-scale keys glow softly, out-of-scale keys dim, the root gets a pink dot. Chords can Play (the whole chord sounds) or Show (only the pressed note sounds; the chord's other notes light up to play yourself).

## Hold

Hold +/- sets how many notes keep sounding after you lift (1 to 8). The first N notes hold, later ones play normally, pressing a held note again lets it go, Let go releases all. Held notes keep feeding note repeat and the arp. Works on the pianos and in Keys mode.

Hold is a one-tap switch on every piano and in the keys bar; + and − (1 to 8) set how many notes hold, and switching it off lets them all go.

## Pad filters

Every piano has a strip under it: a low pass filter on that pad only (its live notes, held notes, repeats and its steps). Left closes the cutoff, up adds resonance. Lifting glides it open again unless Stay is on. In the engine each pad has its own cutoff and resonance gliding once per block, and each voice keeps its own filter memory, so other pads are untouched.

## Your Console

A page Tyler builds from pieces: transport, pads, one row of pads, a piano for any pad, a filter for any pad, Hold, all nine effects or one, the master filter pad, repeat and arp, loops, scale and chords, beat light. Edit mode moves (▲ ▼), sizes (S M L), sets half width (two side by side) and points pianos, filters and single effects at a pad or effect. It starts as a Perform layout (the performance effects page) and is saved in the phone's settings. Pieces stack; free dragging and patching one control to another are not in yet.

## Recording and sharing sounds

Hold a pad: Record onto this pad (mic straight onto it, red bar to stop, take kept in Your Workbench), Keep in Your Workbench (the sound as set, baked), Share this sound (a WAV through Android's share menu). Workbench rows have Share too. The site's shared Donated jar runs on claude.ai storage, which the app cannot reach, so sharing to others goes through files.

## Finding sounds

The picker sorts by instrument type (kicks, snares and claps, hats and cymbals, percussion, bass, keys and stabs, leads, pads and drones, voices, FX, chops) and feel (punchy, long, dark, bright, tonal, gritty, busy), plus By jar and Favorites. Types come from names where they say it and from the audio for the Heap's song chops (`tools/classify_sounds.py`); the Heap sorting is rough.

## Next

The 2026-11-10 review replaced the old list (items 1 to 4 were built by build 7). Its items are MM-1 to MM-24 in
https://claude.ai/artifact/QoAq7LzDGYD8XNyDDjPRHS; the order is in Now above.

## Notes for Claude (the bug button)

The 🐞 button opens the notes panel. A note has a category (Bug, Tuning, Feature, Question, Discuss with a topic, Wishlist), text typed or spoken through Android's speech app, and is kept on the phone. Send opens GitHub's new-issue page filled in with the note, the build, the phone, the audio buffer and underruns, a summary of the mess, the last 40 or so actions and any crash trace; Tyler taps Submit. Claude reads the `from-app` issues, answers in comments, and Check for replies pulls those comments back under each note. Triage rules are in CLAUDE.md and match the bug-testing skill. The start guide (what changed, what to try, what is not in yet) comes from `assets/guide.json` and shows once per new build.

Trade-off: the repo is public, so notes are public. Making it private would hide them, but then the app could not read replies without a sign-in.

## Not carried over from the site yet

- Creature cards and the Ring (the sound battle game). A big port; waiting on Tyler.
- Jams and duels: they run on claude.ai's shared rooms for signed-in viewers, which a phone app cannot join.
- Saving to the site's cloud: replaced by files. The site has Save as file and Open a file; the app has Share this mess and Open a mess file.

Everything else from the site's Mess Machine is in as of build 7: the live FX bus and filter pad, the keys strip, Your Workbench (import, mic, resample, saved edits, chops), doctor zoom, snap, loop preview, fit to tempo and chop to pads, Random drums, chord sets that move the scale, and starred sounds.

The site's source is kept in `site/` (build with `python3 build.py` next to the site's samples, images and loops, which live in the app's assets and the published pages).

## Differences from the site, on purpose

- Workbench sounds (chops, resamples, uploads, mic takes) live in the site's browser storage, so they do not come across yet. Those pads load empty and the app says how many.
- The phone has no keyboard shortcuts for pads.

## Wishlist

Ideas Tyler tags "wishlist" go here, not into the build.

- 2026-11-17 (build 83) A Compact layout: tighter gaps with finger-sized buttons, in place of shrinking everything
  with view size (Tyler plays at 70 to 80 percent, so every button is 20 to 30 percent smaller in real size).
- 2026-11-17 (build 83) A finer time grid than 16th steps, so triplet repeats record as plain hits and fast wubs in
  the automation view are exact. A deep engine change; it sits behind several notes.
- 2026-11-17 (build 83) Sets you build yourself (Maschine's User Chords). The 12-slot sets shipped Nov 22.

- 2026-11-03 (site) Mess Machine as a standalone Android app: done, this repo (builds 1 to 56).
- 2026-11-10 (build 68) Jam station brain: Mess Machine as the hub of a jam setup (Tyler's jam idea). Many MIDI
  devices at once (a USB hub, or daisy-chained over MIDI thru), each new device expanding the setup instead of
  replacing it. A built-in mapping tool that pre-maps intelligently, not only custom maps: across devices and
  inside one (say two keyboard zones and two pad zones on one controller, each its own instrument). Key and
  tempo shared by everything plugged in.
- 2026-11-10 (build 68) Cheap hardware that mirrors the app, ideally mass produced: (1) keys with mod wheel,
  pitch bend, a filter control and four buttons for instrument select and octave; (2) a 9-pad drum pad with even
  fewer buttons; (3) an XY control surface. The aim is that the app's moves (pads, Padiano, XY sphere) feel real
  outside the screen.
- 2026-11-10 (build 68) Several Padianos (piano rolls) open and playable at once, each with its own effects,
  while drums still play; plus the keystroke and touch handling work that multi-layer live play needs. Tyler sees
  the circle-to-XY sphere and layered Padianos with drums as the most unusual part of the app.

## Cancelled

Ideas Tyler has cancelled. Never propose these again.

- 2026-11-22 Hold a pad to sample (EP-133's Sample switch). Cancelled by Tyler.
- 2026-11-22 Scenes with a crossfade. Cancelled by Tyler (XY and the throws sweep many things at once).
- 2026-11-22 Four colored macros per screen (OP-1). Cancelled by Tyler (a second mapping system on top of MIDI map).
- 2026-11-22 Follow actions on loop letters (Ableton). Cancelled by Tyler (the Song lane chains loops).
- 2026-11-22 Ableton Link. Cancelled by Tyler (MIDI clock covers his gear).
- 2026-11-22 A reorderable FX chain per sound. Cancelled by Tyler (saved FX sets and the vials cover it).

## Plan agreed 2026-11-09 (builds after 30)

The proposal doc (claude.ai artifact 7DvA8tAeAYSUofSab1sWFG) is the short version; this is the full spec. Tyler's notes #1 to #6 are on GitHub.

### Build A: fixes and safety
- Pads redraw after any change (#6). Cause: Compose strong skipping skipped screens whose only input is the AppState object; they now read the change counter.
- Hold holds one-shots (#4): a latched note on a one-shot loops its trimmed part while held. Doctor choice per sound, "When held": loop it, hold the tail (freeze near the end), or play once.
- Hold "Last" (#1): a Hold count below 1. Only the newest note holds; the next note replaces it.
- Tabs, sheets and menus act on lift with a short (about 150 ms) slide or fade (#5). Pads and keys still fire on touch down.
- Pad menu: hold still about 0.45 s, then lift. Any move over about 10 dp cancels it, so drags and circles never open menus.
- In key only (scale setting): the Padiano hides out-of-key notes; chords use only scale notes.
- Empty pads: the top corner records, the bottom corner picks a sound (note #14). Mode: tap to start, tap again to stop (default) or hold to record. (The old "Empty pads record" setting is gone, build 105.)
- Beat pulse behind the whole screen: off, soft or full (View size area of the menu).
- Scrolling: anything you play (pads, Padiano, XY, sliders) never scrolls the page. Everything else scrolls with one finger; the scroll bar always works; Lock turns scrolling off.

### Build B: Padiano, Glide, the dock and Lock
- Piano becomes Padiano everywhere. Layouts: Squares (default, one row of small squares) and Keys (the old piano).
- Padiano settings: semitones shown, 7 to 36; height S M L. Squares are never narrower than 22 dp; past that they wrap into even rows.
- Glide: switch plus 0 to 1 s time, per pad, on the Padiano header and in the doctor. Legato only option. Off for drums by default.
- Side dock on the left edge, folds to a tab: Lock, Hold, filter and resonance sliders (target the mix or one pad), XY preset picker, and any of Repeat, Hush, Rec, one effect, beat light.
- Lock freezes the page (no scroll, tabs, menus, sheets, undo); playing still works. It also asks Android to pin the screen (Android asks each time). Hold the lock 1 s to unlock; unlocking unpins.

### Build C: effects, routing and XY
- Perform effects become editable: tap to hold, slide to set the amount, press and hold for settings (amount, tempo-synced time, tone, behaviour: held, stays on, follows slider).
- Doctor effects per sound: Reverb, Echo, Dirty, Crush, Chorus, Wobble. Reverb and echo are shared sends. Save as new bakes them in. The site plays them plain.
- Routing: each pad goes to the Mix or one of 4 FX buses. Stutter, tape stop, gate, ring mod and octave down get per-pad versions. Every effect, slider and XY direction picks a target: this pad, a bus, or the Mix.
- XY belongs to the pad (set from the pad menu). Two-way (zero bottom-left) or four-way (zero in the middle, dead zone, M and L sizes only). Springs back or stays. Ring or square, S M L. Each direction has its own range.
- XY targets: pitch (scale steps by default), filter, resonance, volume, pan, attack, release, start point, doctor effects, glide time, repeat rate, per-pad versions of the mix effects.
- Draw a circle on a pad or a Padiano note: a mostly clear sphere appears over it, reaching past the pad's edges, until you lift. On one note it moves that note only where it can (pitch, filter, volume, pan, sends).
- XY presets: Sweep (X filter, Y res), Dub (right echo, up reverb, left filter down, down crush), Bend (right pitch up, left pitch down, up chorus, down wobble), Lo-fi (X crush, Y dirty), Scrub (X start, Y pitch in scale steps), plus your own saved ones.

### Build D: M(ess)(y) Desk and deeper menus
- The Console becomes M(ess)(y) Desk (tab label DESK): Arrange mode, drag pieces on a grid, resize from a corner, float and overlap, pin. Several saved layouts, switchable from the dock.
- Motion recording: with Rec on while playing, XY and slider moves record into the loop and play back.
- Menus everywhere (#2): sound names in Steps and Roll, loop letters, song bars, picker rows, jar tiles; hold then lift.
- Pitch strip in Steps (#3): tap a sound name for a one-row Padiano under its row; pick a step, then a note.

### Status (2026-11-10)

All four builds are in (builds 31 to 40), each with big-test flows and engine tests. How they turned out:
- Hold on one-shots loops the trim by default; the doctor's When held picks loop, tail or once.
- Padiano squares: 22 dp minimum; past that they wrap into even rows.
- The dock sits in a row beside the page (it never covers pads). Lock blocks scrolling, tabs, sheets, menus, undo and back, and asks Android to pin.
- Engine: per-voice dirty, crush, chorus and wobble; two shared spaces (reverb and a tempo echo) fed by sends; per-pad and per-bus perform effects; live per-pad modulation (pitch, volume, pan, sends, gate, ring, tape, stutter, roll) with rates interpolated inside each audio block. Render cost rose from about 2.7 to 5.8 ms per second of audio.
- Effect settings open from the ✎ corner, because pressing and holding an effect is how you play it.
- XY setups are saved in the mess under padOpts; your own presets live on the phone.
- Circles: a finger must turn most of a full turn and travel 36 to 40 dp. On a Padiano note, the sphere moves that note's pitch and level; everything else moves the whole pad.
- Desk: a 12-column grid with 24 dp rows. Pieces clip instead of scrolling inside the page; resize to see more. Layouts are saved on the phone, not in the mess.
- Motion recording records XY moves (including filter and resonance) per step; dock sliders and effect buttons don't record yet.
- Menus: Steps names, the Roll pad name, loop letters, song bars and picker rows. Jar tiles have none.

### Known trade-offs
- Starting a circle on the Padiano can touch a neighbour note before the circle is recognised; it then snaps back and silences the stray note.
- The pad still sounds on touch before its menu opens (same as before).

## Overhaul (agreed 2026-11-10)

Proposal doc: https://claude.ai/artifact/2vcp2CcSo4nXTmVskaCpTS. Tyler said build it all (2026-11-10): Play tab
with a switch between the default Pads page and My Desk; Hush gone; dock folded by default; Minor on C;
pad card gone; Steps one row (unanswered, taken as yes); help behind ?; all five utility ideas; pad buzz. Short version: one home per control (dock and desk
become shortcuts you choose), one 48 dp top bar, dock folds to an edge handle, Pads and Desk merge into
one Play tab, Steps one row per sound, four button tiers, help behind a ? per screen, no allocation on
the audio thread, targeted redraws with strong skipping back on. Build order: Smooth, Room, Tidy, Useful.

Built 2026-11-10 (builds after 47): Smooth (no allocation on the audio thread for click, arp and roll;
3-burst start buffer; chord tones fold onto the visible Padiano), Room (one-row top bar with ☰, Hush gone and
Stop twice hushes, dock folded with a swipe handle and its own repeat rate, Settings sheet, how-to text behind ?
and shown once per page), Tidy (Play tab with Pads / Keys / My Desk, pad card gone with Mute, Solo and Resample in
the pad menu, Steps one row per sound, slim Padiano header, Doctor tabs, button tiers, Undo on every clear, lock
edge, C minor default), Useful (1/2/4/8 bar loops, hold then slide to swap pads, desk copy/rename/delete, mess
pictures, Share the song, pad buzz). Not done: re-enabling strong skipping (needs every screen on targeted state);
pad menus still play the pad because pads play on touch.
