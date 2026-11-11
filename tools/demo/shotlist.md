# Mess Machine demo library: shot list

Updated 2026-11-11. Build 68 screens, rendered from the app's own code (branch `demo-render`), not filmed on a phone.

## How these were made

Each clip is drawn frame by frame from the real screens by the app's test setup, with the real sound engine
rendering the audio on the same clock. So picture and sound cannot drift, and touches (including several
fingers at once) are scripted. The yellow dots are the fingers. The screen is a generic 411 x 891 phone.

- `raw/<shot>/` has the uncut phone frames (`frames.mp4`), the audio (`audio.flac`) and the touch log.
- `clips/16x9/` and `clips/9x16/` have the captioned cuts.
- `screenshots/` has three stills per shot.
- `trailer.mp4` is not made yet. It comes after Tier 2, so it can open on the strongest shot.

## Status

| Shot | What | Status | Length |
|---|---|---|---|
| B1 | Stretch vs Grain | done | 18 s |
| B2 | Sustain | done | 12 s |
| B3 | Hold, on the beat | done | 16 s |
| B4 | Repeat styles (Cut, Ring, Flow) | done | 16 s |
| B5 | Synth tab tour, six kinds | done | 24 s |
| B6 | Acid line in Roll | done | 17 s |
| B7 | Synthipad with the filter square | done | 12 s |
| B8 | Loops fit the tempo | done | 19 s |
| C1 | Acid line + Qbldy loop + granular cloud | done | 21 s |
| C2 | Drum synths on Steps + sample pads | done | 20 s |
| B9 to B15, C3 to C5 | Tier 2 | not started | |
| Tier 3 | Desk, Sounds jars, FM presets, Vowel talk, Latency, Notes | not started | |
| A | Trailer | not started (after Tier 2) | |

## One line per shot

- **B1 Stretch vs Grain.** The same 2.2 second sound played up the keys two ways: Stretch gets shorter as it goes up (like tape), Grain keeps its full length at every pitch. Most samplers only do the first.
- **B2 Sustain.** A 1.2 second ding becomes a pad: hold the pad and the sound freezes mid-note for as long as the finger stays down, then fades on release.
- **B3 Hold, on the beat.** A latched loop restarts itself in time with the drums, every beat or every bar, with no steps programmed.
- **B4 Repeat styles.** One held chord with Repeat on, three ways: Cut chops each repeat, Ring lets them overlap, Flow holds the chord and pulses its volume.
- **B5 Synth tab tour.** Six synth engines on one screen (Analog, FM, Wavetable, Granular, Drums, Pluck), each with a knob turned while the note sounds.
- **B6 Acid.** An acid line in the piano roll: notes that touch slide into each other, added while the loop plays, then the filter turned by hand.
- **B7 Synthipad.** A grid where every square is in key, with a filter square above it: a three finger chord with a fourth finger sweeping the filter.
- **B8 Loops fit the tempo.** A one bar loop from a finished song plays with the drums; drag the tempo and the loop stays locked without changing pitch.
- **C1 Three layers.** A song loop, an acid line and a granular cloud built up one at a time while the loop keeps playing.
- **C2 Drum synths on Steps.** Kick, snare, hat and clap made by synth engines, programmed on Steps while playing, then sample pads by hand on top.

## Check these with your ears

I measure timing and level. I cannot judge whether it sounds good.

| Shot | Where | What to listen for | What I measured |
|---|---|---|---|
| B1 | 0:03 to 0:08 | Stretch notes get shorter going up | Note lengths 2.2 s, 1.5 s, 1.1 s |
| B1 | 0:10 to 0:18 | Grain notes keep their length; any wobble on the top note | All three notes 2.2 s |
| B2 | 0:05 to 0:08 | The held ding should sound like a pad, not a buzz | Level steady for 3.5 s, fades in 0.4 s |
| B3 | 0:05 to 0:09 | Loop restarting every beat, in time with the kick | Not measured |
| B3 | 0:10 | A copy of the loop plays over the drums when the "1 bar" chip is tapped | See tester log 4 |
| B3 | 0:11 to 0:15 | Loop restarting every bar | Not measured |
| B4 | whole clip | Cut, Ring and Flow should be clearly different | Flow is about 5 dB quieter and pulsing |
| B5 | 0:05 to 0:08 | FM: the E piano fades, so the knob sweep is faint | Level falls from -14 to -25 dB |
| B5 | 0:13 to 0:16 | Granular cloud of the glass harp | Sound present |
| B6 | 0:06 to 0:10 | Slides between the touching notes | Not measured |
| B6 | 0:11 to 0:15 | Filter sweep on the acid line | Knob moves 420 Hz to 800 Hz and back |
| B7 | 0:03 to 0:08 | Chord with filter sweep | Sound present, no clipping |
| B8 | 0:07 and 0:14 | Loop at 143 and at 93 BPM: same pitch, no gap or flam at the bar line | No silent gaps |
| C1 | 0:05 on | Does the Ametrine loop sit in key with the C minor acid line? | Not measurable by me |
| C1 | 0:11 to 0:18 | Cloud level against the loop | Sound present |
| C2 | 0:15 to 0:19 | Clipping when the sample pads join | 84 samples at full scale |

## Tester log

Things that were confusing, slow or broken while scripting these shots.

1. **Holding a pad opens its menu when you lift.** With Sustain on, holding a pad for more than about half a second and lifting cleanly opens the pad menu. The demo fingers shift slightly to avoid it. A player holding a sustained pad still will get the menu every time.
2. **The mix reaches full scale.** In C2, four drum synths plus sample pads hit 0 dB (84 clipped samples). Nothing on screen warns about it.
3. **Tempo slider shows two numbers while dragging.** The big BPM number keeps the old tempo and the slider label shows the new one until you let go. The tempo itself only changes on release.
4. **Sample sheet chips audition the sound.** Tapping Stretch, Grain, Sustain or a beat chip plays the sound once. While a loop is playing, that lays an out of time copy over the beat.
5. **The Tempo sheet covers the whole page.** You cannot watch the steps while you change tempo.
6. **The Repeat rate menu has 11 rows** (3 rates, 5 arps, 3 styles). The design target is menus of 8, then groups.
7. **Padiano name is cut to "5 ..." when the dock is open.** The pad's name is unreadable in its own header.
8. **A new granular synth takes the first sample pad as its source.** If that pad is a drum, the cloud is a cloud of a clap until you change it.
9. **The piano roll starts with all 25 rows.** The sound's own pitch (C4) sits near the bottom of the screen until you use Fold to scale.
10. **One loop title has swearing in it.** I kept it out of the demos.
11. **Speed.** Not tested. These renders run off the clock, so they say nothing about lag on a real phone.

Not broken: every control the ten shots touch did what the design doc says.

## Shots that still want your hands

The renders can press several points at once, so this list is short. These are for proof that it plays live.

1. **Chords on a Padiano with drums under them.** Phone flat on a table, camera straight above, both hands in frame. Left hand holds a three note chord on the Padiano, right hand taps a kick and snare pattern on the pads. 10 to 15 seconds.
2. **Circle to XY sphere.** Close on one pad. Draw a slow circle on the pad until the sphere appears, then drag around inside it. Two takes: one on a pad, one on a single Padiano note. 8 seconds each.
3. **Synthipad with several fingers.** Synth tab, Synthipad view. One hand holds a chord on the grid, a finger of the other hand sweeps the filter square. 10 seconds.
4. **Two Padianos at once.** Padianos open under two rows. One hand on each, a bass note on one and a melody on the other. 10 seconds.

For each: film at 30 fps, and run the phone's own screen recorder at the same time so I can use its clean audio and sync it to the hands.
