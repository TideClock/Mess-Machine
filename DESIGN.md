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

## Next

1. **Perform FX bus** from the site: stutter, tape stop, filter XY pad, echo, crush, octave down.
2. **Your own sounds:** import a WAV or MP3 from the phone, and record from the mic onto a pad.
3. **Chop to pads:** slice one long sound across a bank, like the site's doctor.
4. **Drag to lengthen** notes in the roll, and drag-paint across steps.
5. **Oboe audio** (Google's low-latency audio library) if `AudioTrack` lags on Tyler's phone. The engine stays the same; only `AudioOut` changes.
6. **Keep playing in the background** with a media notification (needs a foreground service).
7. **"Send to phone" on the site:** a button that downloads the current mess as a file the app opens.
8. **MIDI:** plug in a pad controller or keyboard over USB.

## Differences from the site, on purpose

- Workbench sounds (chops, resamples, uploads, mic takes) live in the site's browser storage, so they do not come across yet. Those pads load empty and the app says how many.
- The phone has no keyboard shortcuts for pads.

## Wishlist

Ideas Tyler tags "wishlist" go here, not into the build.

- (Started) Mess Machine as a standalone Android app. This repo.
