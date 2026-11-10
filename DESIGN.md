# Mess Machine for Android: design doc

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

1. **Perform FX bus** from the site: stutter, tape stop, filter XY pad, echo, crush, octave down.
2. **Your own sounds:** import a WAV or MP3 from the phone, and record from the mic onto a pad.
3. **Chop to pads:** slice one long sound across a bank, like the site's doctor.
4. **Drag to lengthen** notes in the roll, and drag-paint across steps.
5. **Oboe audio** (Google's low-latency audio library) if `AudioTrack` lags on Tyler's phone. The engine stays the same; only `AudioOut` changes.
6. **Keep playing in the background** with a media notification (needs a foreground service).
7. **"Send to phone" on the site:** a button that downloads the current mess as a file the app opens.
8. **MIDI:** plug in a pad controller or keyboard over USB.

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

- (Started) Mess Machine as a standalone Android app. This repo.
