# Mess Machine for Android: design doc

## Now

Rewritten at the end of every session and at each step of a long job (BT-2). Read this first.

- **Live:** build 60 (2026-11-10): the review's builds. 59 brought the Audio check, buffer give-back, slow-block
  log, CPU hint, Baseline Profile, Resample cut, notes privacy, FLAC sounds (APK 17.0 MB), background play and MIDI;
  60 targets API 36 (edge to edge, predictive back). 57 failed a test and 58's phone test caught an audio-thread
  crash; neither was published.
- **Plan:** the review doc https://claude.ai/artifact/QoAq7LzDGYD8XNyDDjPRHS, items MM-1 to MM-24, with an Outcome
  section at the top. MM-11 (Play Store) is last and notes only. MM-5 (Oboe) waits for real numbers from the Audio
  check. MM-15 (Fold layouts) skipped with a big note. MM-22 (creature cards) is an outline in the doc.
- **Next:** sound engine plan v2, piece by piece (see Decisions). Pieces 1 to 3 (grain, sustain, repeat styles,
  hold on the beat, the Sample sheet) are in build 61. Then 4 (bake and latency), 5 (the Synth tab: poly analog,
  mono, acid), 6 to 9 (FM, wavetable, granular, drums and plucks), 10 (loops, re-cut chops), 11 (the Vault and
  packs). Tyler's Audio check note still decides MM-5.
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

## Control map

Every control's one home, any second access point and why (BT-1). The dock and My Desk are shortcuts you choose,
so they never count as a second home. `design/exceptions.txt` holds the same reasons for the design check test.

| Control | Home | Also at | Why the second spot |
|---|---|---|---|
| Play, Stop, Rec, Undo, tempo | Top bar | Desk transport piece | Shortcut you choose |
| Undo after a clear | Top bar | The clear's message | Right where the clear happened, for a few seconds |
| Hold | Keys bar | Dock, desk Hold piece | Shortcuts you choose |
| Repeat and its rate | Dock | Desk Repeat piece | Shortcut you choose |
| Filter and resonance | Dock | Desk pad filter piece, XY targets | Shortcut; XY moves it as one of its targets |
| Root, scale, chords | Keys bar | Roll, desk Scale piece | Roll places and folds notes by the scale; desk is a shortcut |
| In key only | Padiano settings | none | |
| Bank 1 to 4 | Play tab (Pads) | Steps | Each page shows that bank's pads; switching tabs to change it would break the flow |
| Loop letters A to H | Steps | Roll, Song, Song palette | Roll edits one loop and Song plays one: both need to pick it. The palette paints the song |
| Loop tools (bars, Copy to, Clear loop, Bottle it) | Steps | none | Removed from Roll and Song 2026-11-10 (unsupported, BT-1) |
| Resample the loop | Steps | Workbench (Sounds) | Flagged for Tyler |
| Record a sound | Empty pads, pad menu | Sounds mic | Sounds records into the Workbench, not onto a pad |
| Mute, solo | Pad menu | none | |
| Doctor | Pad menu | Sounds row menu | From a pad or from the library: different starting points |
| Hear it | Pad menu | Sounds, Doctor | Each hears the thing on that screen |
| Share a sound | Pad menu | Sounds, Doctor (as WAV) | Same: each shares what is on that screen |
| Click | Tempo sheet | none | |
| Messes, Settings, Redo, Notes for Claude | ☰ menu | none | |
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

- 2026-11-03 (site) Mess Machine as a standalone Android app: done, this repo (builds 1 to 56).

## Plan agreed 2026-11-09 (builds after 30)

The proposal doc (claude.ai artifact 7DvA8tAeAYSUofSab1sWFG) is the short version; this is the full spec. Tyler's notes #1 to #6 are on GitHub.

### Build A: fixes and safety
- Pads redraw after any change (#6). Cause: Compose strong skipping skipped screens whose only input is the AppState object; they now read the change counter.
- Hold holds one-shots (#4): a latched note on a one-shot loops its trimmed part while held. Doctor choice per sound, "When held": loop it, hold the tail (freeze near the end), or play once.
- Hold "Last" (#1): a Hold count below 1. Only the newest note holds; the next note replaces it.
- Tabs, sheets and menus act on lift with a short (about 150 ms) slide or fade (#5). Pads and keys still fire on touch down.
- Pad menu: hold still about 0.45 s, then lift. Any move over about 10 dp cancels it, so drags and circles never open menus.
- In key only (scale setting): the Padiano hides out-of-key notes; chords use only scale notes.
- Empty pads record (setting, off by default). Mode: tap to start, tap again to stop (default) or hold to record.
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
