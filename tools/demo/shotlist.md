# Mess Machine demo library: shot list

Updated 2026-11-11. Rendered from the app's own code on branch `demo-render` (main as of build 73, plus three fixes
listed below), not filmed on a phone.

## How these were made

Each clip is drawn frame by frame from the real screens by the app's test setup, with the real sound engine
rendering the audio on the same clock. Picture and sound cannot drift, and touches (several fingers at once)
are scripted. The yellow dots are the fingers. The screen is a generic 411 x 891 phone.

- `trailer.mp4` (16:9) and `trailer_9x16.mp4`: 62 seconds, 17 cuts, opening on the Synthipad chord and filter sweep.
- `clips/16x9/` and `clips/9x16/`: the captioned cuts, one file per shot.
- `raw/<shot>/`: the uncut phone frames (`frames.mp4`), the audio (`audio.flac`) and the touch log.
- `screenshots/`: three stills per shot.

## Status

| Shot | What | Status | Length | File |
|---|---|---|---|---|
| A | Whole-app trailer | done | 62 s | `trailer.mp4`, `trailer_9x16.mp4` |
| B1 | Stretch vs Grain | done | 18 s | `b1.mp4` |
| B2 | Sustain | done | 12 s | `b2.mp4` |
| B3 | Hold, on the beat | done | 16 s | `b3.mp4` |
| B4 | Repeat styles | done | 15 s | `b4.mp4` |
| B5 | Six kinds of synth | done | 24 s | `b5.mp4` |
| B6 | Acid lines | done | 17 s | `b6.mp4` |
| B7 | Synthipad | done | 12 s | `b7.mp4` |
| B8 | Loops follow the tempo | done | 19 s | `b8.mp4` |
| B9 | Padiano | done | 14 s | `b9.mp4` |
| B10 | Granular | done | 19 s | `b10.mp4` |
| B11 | Bake | done | 14 s | `b11.mp4` |
| B12 | Sample sheet tools | done | 18 s | `b12.mp4` |
| B13 | Perform effects | done | 16 s | `b13.mp4` |
| B14 | Song mode | done | 16 s | `b14.mp4` |
| B15 | Resample | done | 16 s | `b15.mp4` |
| C1 | Three layers | done | 21 s | `c1.mp4` |
| C2 | Drum synths on Steps | done | 20 s | `c2.mp4` |
| C3 | Loop, chords and XY | done | 23 s | `c3.mp4` |
| C4 | Record a melody | done | 17 s | `c4.mp4` |
| C5 | Resample to grains | done | 21 s | `c5.mp4` |
| T1 | My Desk | done | 14 s | `e1.mp4` |
| T2 | Sounds | done | 21 s | `e2.mp4` |
| T3 | FM presets | done | 13 s | `e3.mp4` |
| T4 | Vowel talk | done | 9 s | `e4.mp4` |
| T5 | Latency screen | done | 9 s | `e5.mp4` |
| T6 | Notes for Claude | done | 6 s | `e6.mp4` |

Skipped inside finished shots, and why:

- T2 Sounds: Packs is left out. No pack has been sent yet, so the sheet only says so.
- T5 Latency: screens only. The render has no speaker or mic, so the numbers on screen are empty.
- T6 Notes for Claude: the note is written but Send is never pressed (it opens a public GitHub page).

## One line per shot

- **B1 Stretch vs Grain.** The same 2.2 second sound played up the keys two ways: Stretch gets shorter as it goes up (like tape), Grain keeps its full length at every pitch. Most samplers only do the first.
- **B2 Sustain.** A 1.2 second ding becomes a pad: hold the pad and the sound freezes mid-note for as long as the finger stays down, then fades on release.
- **B3 Hold, on the beat.** A latched loop restarts itself in time with the drums, every beat or every bar, with no steps programmed. A hands-free way to lock a loop to the tempo.
- **B4 Repeat styles.** One held chord with Repeat on, three ways: Cut chops each repeat, Ring lets them overlap, Flow holds the chord and pulses its volume.
- **B5 Six kinds of synth.** Six synth engines on one screen (Analog, FM, Wavetable, Granular, Drums, Pluck), each with a knob turned while the note sounds. A full synth rack inside a pad sampler.
- **B6 Acid lines.** An acid synth line in the piano roll: notes that touch slide into each other, added while the loop plays, then the filter opened by hand.
- **B7 Synthipad.** A grid where every square is in key, with a filter square above it: play a chord with three fingers and sweep the filter with a fourth.
- **B8 Loops follow the tempo.** A one-bar loop from a finished song plays with the drums; drag the tempo and the loop stays locked to the beat without changing pitch.
- **B9 Padiano.** Hold any pad and a keyboard opens under its row, playing that pad's sound. In key only hides every wrong note, so any square you hit fits.
- **B10 Granular.** A synth that plays tiny grains of one of your own sounds: move Position by hand to travel through the sound, then Freeze and Shimmer presets from the same source.
- **B11 Bake.** Chords on keys use live grain to keep each note the same length. Bake renders every pitch ahead of time for a cleaner sound and less work for the phone. The difference is in the sound.
- **B12 Sample sheet tools.** One sheet does the sampler chores: drag to trim, add reverb and echo to just this sound, then chop it across pads and play the slices.
- **B13 Perform effects.** Hold-to-use effects over a playing loop: stutter, an echo throw that rings on after you let go, and a tape stop that winds the whole mix down and back.
- **B14 Song mode.** Three loops painted into bars like blocks, then played as a song: the bar lights follow along as A, B and C take turns.
- **B15 Resample.** The whole playing loop, every pad and synth in it, becomes one new sound on a pad, which can then be played at any pitch on its own keys.
- **C1 Three layers.** A song loop, an acid line and a granular cloud built up one layer at a time while the loop keeps playing: sample, synth and grains in one mess, all in the loop's key.
- **C2 Drum synths on Steps.** Kick, snare, hat and clap made by synth engines (no samples), programmed on Steps while the loop plays, then sample pads played by hand on top.
- **C3 Loop, chords and XY.** A song loop latched with Hold restarts itself every bar, Flow chords pulse on a Padiano in the loop's key, and a second finger sweeps the pad's XY filter, all live.
- **C4 Record a melody.** A melody played on a Padiano is recorded straight into the loop while it runs, shows up as notes in Roll, and the loop is painted into the Song.
- **C5 Resample to grains.** A loop is resampled to a pad, then a granular synth is pointed at that pad, so the beat you just made becomes a cloud you can play and travel through.
- **T1 My Desk.** The same machine laid out three ways: saved desk layouts you build from pieces (pads, keys, effects, filters) and switch between while it plays.
- **T2 Sounds.** Sounds live in jars. Two of them are cut from finished songs: one bar loops and single hits. Search, star a favorite and send it to a pad.
- **T3 FM presets.** Three very different instruments from the same four FM operators: an electric piano, a bell and clanging metal.
- **T4 Vowel talk.** A wavetable preset that sweeps through vowel shapes on its own, so a simple bass line seems to speak.
- **T5 Latency screen.** A built-in screen that measures how late the sound is on your phone and offers fixes. Screens only in this render: there is no speaker to measure.
- **T6 Notes for Claude.** A bug button inside the app: write or speak a note and it travels with the build, the phone and the last things you did.

## Check these with your ears

I measure timing, level and length. I cannot judge whether it sounds good or in tune.

| Shot | What to listen for | What I measured |
|---|---|---|
| C1 | Is it in key now? The loop measures as B flat minor; the mess, the acid line and the cloud (F and B flat) now follow it | Key estimate from the loop's notes, not from hearing it |
| C3 | Loop (measures as D minor) against the D minor chords; whether Flow also pulses the latched loop | Very loud: average -9 dB, peak at full scale |
| B1 | Stretch notes get shorter going up; Grain notes keep their length | 2.2, 1.5, 1.1 s against 2.2 s each |
| B2 | The held ding should sound like a pad, not a buzz | Steady for 3.5 s, fades in 0.4 s |
| B3 | Loop restarting every beat, then every bar, in time with the kick | Not measured |
| B4 | Cut, Ring and Flow clearly different | Flow about 5 dB quieter and pulsing |
| B6 | Slides between touching notes | Not measured |
| B8 | Loop at 152 and 88 BPM: same pitch, no gap at the bar line | No silent gaps |
| B9 | The clashing run at 0:06, then the in-key run at 0:10 | Sound present |
| B11 | Baked chords cleaner than live ones | Bake finished in about a second |
| B12 | Trimmed loop, reverb and echo, then the four slices | Sound present |
| B13 | Stutter at 0:03, echo tail at 0:08, tape stop at 0:11 | Sound present, peak at full scale |
| B15 | The resampled beat at three pitches | Sound present |
| C4 | The recorded melody against the beat | 7 notes recorded, all on steps |
| C5 | The beat as a granular cloud | Sound present |
| T3 | E piano, Bell, Metal | 4 clipped samples on the chords |
| T4 | Does the bass line seem to speak? | Quiet: peak at 36% |
| C2 | Clipping when the sample pads join | 84 samples at full scale |

## Fixed on the `demo-render` branch (not on main yet)

The app's full big test passes on the branch with these in.

1. **Holding a pad no longer opens its menu by accident.** A lift opens the menu only between about half a second
   and 1.3 seconds after the touch; hold longer and you are playing. A pad that sounds for as long as it is held
   (a synth, Sustain, gate) never offers its menu from a hold: the button by the bank numbers reads MENU for it.
2. **The tempo follows the slider while it moves.** Both numbers agree, and letting go is one undo step.
3. **The Repeat menu is one small panel.** Three rows of choices (Every, Plays, Each repeat) in place of 11 list rows.
4. **The button by the bank numbers follows the pad you tapped last.** It could keep the pad from before and open
   the wrong pad's Sample sheet (found while testing fix 1).

## Tester log

Things that were confusing, slow or broken while scripting these shots. Items 1 to 4 above are not repeated.

1. **The mix reaches full scale often.** C2, C3, B13, B14, B15, C5, T1 and T3 all peak at or within 1% of 0 dB, and
   C2 clips 84 samples. Nothing on screen warns about it and nothing holds it back.
2. **Sample sheet chips audition the sound.** Tapping Stretch, Grain, Sustain or a beat chip plays the sound once.
   While a loop is playing, that lays an out of time copy over the beat (B3 at 0:10).
3. **The Tempo sheet covers the whole page.** You cannot watch the steps while you change tempo.
4. **Stutter and tape stop hold the loop's place.** After a one second stutter the step light is one step on, not
   eight, so the loop comes back shifted against where it would have been.
5. **The "Start from" list for a new synth is one menu of about 54 presets.** Most of it is off screen; the target
   is menus of 8, then groups.
6. **A new granular synth takes the first sample pad as its source.** If that pad is a drum, the cloud is a cloud
   of a kick until you change it (C5 shows the change).
7. **Padiano name is cut to "5 ..." when the dock is open.**
8. **The piano roll starts with all 25 rows.** The sound's own pitch (C4) sits near the bottom of the screen until
   you use Fold to scale.
9. **Loop keys are not shown anywhere.** C1 was out of key because nothing says the Ametrine loop is in B flat
   minor. A key label on loops (I measured all 90) would have prevented it.
10. **Loop titles with swearing show on the first screen of the Qbldy Loops jar.** The T2 clip is cropped above them.
11. **Speed.** Not tested. These renders run off the clock, so they say nothing about lag on a real phone.
12. **Render only:** jumping a list straight to a far item crashed the renderer twice (out of memory). Real swipes
    work. Probably not an app fault.

## Shots that still want your hands

The renders press several points at once, so this list is short. These are for proof that it plays live.

1. **Chords on a Padiano with drums under them.** Phone flat on a table, camera straight above, both hands in frame.
   Left hand holds a three note chord on the Padiano, right hand taps a kick and snare pattern on the pads. 10 to 15 seconds.
2. **Circle to XY sphere.** Close on one pad. Draw a slow circle on the pad until the sphere appears, then drag
   around inside it. Two takes: one on a pad, one on a single Padiano note. 8 seconds each. Not scripted yet.
3. **Synthipad with several fingers.** Synth tab, Synthipad view. One hand holds a chord on the grid, a finger of
   the other hand sweeps the filter square. 10 seconds.
4. **Two Padianos at once.** Padianos open under two rows. One hand on each, a bass note on one and a melody on the other. 10 seconds.

For each: film at 30 fps, and run the phone's own screen recorder at the same time so I can use its clean audio
and sync it to the hands.
