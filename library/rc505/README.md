# RC-505 sound library (Brotherwife loops)

Single notes, drum hits, chord stabs and beat-grid loops cut from Tyler's 96 RC-505 looper stems (21 memories: 8 from the Mk1 looper, 13 from the Mk2). Made for the Mess Machine app's sound library and its Loops shelf. Claims are tagged [Certain], [Likely] or [Guessing]. I cannot hear the audio: everything about what an instrument *is* comes from measurements and from looking at spectrogram pictures, and says so.

## Totals

- **600 clips** [Certain]: 322 loops, 195 notes, 82 hits, 1 stab.
- Loops [Certain]: 198 one-bar, 59 two-bar, 38 two-beat, 27 one-beat. 260 start on a downbeat.
- Hits [Certain counts, names are guesses]: kick 30, snare or clap 20, open hat or cymbal 8, knock 7, tom or thump 6, bell or ping 5, closed hat 4, click 2.
- Size on disk [Certain]: 39.1 MB of FLAC (13.3 minutes of audio).
- Stems by instrument family [Guessing unless marked]: synth lead 34 (34 [Guessing]); bass 33 (9 [Guessing], 24 [Likely]); drums 12 (4 [Guessing], 8 [Likely]); guitar 10 (10 [Guessing]); mixed 5 (5 [Guessing]); percussion 2 (2 [Likely]).
- Every stem gave something [Certain]. 26 stems gave only loops, no clean single sounds: Mk1_006_2, Mk1_006_4, Mk1_053_1, Mk1_053_3, Mk1_053_5, Mk1_056_2, Mk1_056_3, Mk1_076_3, Mk1_087_2, Mk1_087_4, Mk2_017_1, Mk2_025_2, Mk2_025_4, Mk2_028_2, Mk2_028_4, Mk2_053_2, Mk2_055_2, Mk2_056_2, Mk2_063_1, Mk2_063_3, Mk2_063_4, Mk2_075_4, Mk2_082_5, Mk2_087_2, Mk2_087_3, Mk2_087_4.
- Most clips: Mk2_025_5 (15), Mk2_075_3 (15), Mk2_028_3 (14), Mk1_063_3 (12), Mk2_075_1 (12).

Files: `clips/<kind>/<stem>_<nnn>_<label>.flac` (nnn counts up through the stem in time order; `#` is written `s`, so C#4 is `Cs4`). `library.json` has one entry per clip (where it came from, pitch or chord or hit type or tempo, loudness, brightness, tags, a one-line description). `stems.json` has each stem's profile and the counts of what was kept and dropped, plus each memory's tempo and key.

Every clip is mono, 16-bit FLAC at 44.1 kHz, peak at -1 dBFS [Certain]. Notes, hits and stabs start at a zero crossing just before the attack (or with a 4 ms fade in where there was none), keep their tail until the next attack or until they fall 50 dB, at most 4 s, and end with a 12 to 20 ms fade. Loops are exactly 1, 2, 4 or 8 beats long to the sample, start 5 ms before the beat's attack, and the last 5 ms crossfade into what played just before the start, so they repeat without a click [Certain]. 95 clips run past the end of the stem and continue from its start, which is what the looper plays (`wraps` in the JSON).

## The songs

Each memory is one song; its tracks are layers. Tempo comes from the loop lengths (RC-505 loops are whole bars) plus where the attacks fall; bars assume 4/4. Key comes from the pitched tracks' chroma. 'Took' lists what went into the library.

### Mk1_006

160.0 BPM [Guessing], tracks are 6, 6, 10, 12 bars (runner-up reading: 80.0 BPM). Beat tracker said 154.3 BPM. Key G major [Likely], or C major.

- **Track 1**: bass [Guessing], C1 to D#2, 4.44 attacks a second, brightness (spectral centroid) 1750 Hz. Pitched, below C3 most of the time, energy sits low. The recording clips in places (44 samples at full scale). Took: 6 notes (C2 D#2 D2 C3 D3); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], C1 to B3, 8.33 attacks a second, brightness (spectral centroid) 2330 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 2beat, 1 x 1bar, 1 x 1beat).
- **Track 3**: bass [Guessing], C2 to E3, 4.27 attacks a second, brightness (spectral centroid) 2444 Hz. Pitched, below C3 most of the time, energy sits low. The recording clips in places (62 samples at full scale). Took: 2 notes (D2); loops (2 x 1bar, 1 x 2bar).
- **Track 4**: synth lead [Guessing], C3 to A3, 12.17 attacks a second, brightness (spectral centroid) 3615 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 2beat, 1 x 1bar, 1 x 1beat).

### Mk1_033

123.0 BPM [Guessing], tracks are 8, 8, 4 bars (runner-up reading: 61.5 BPM). Beat tracker said 121.6 BPM. Key D major [Guessing], or B minor.

- **Track 1**: synth lead [Guessing], D1 to B2, 1.67 attacks a second, brightness (spectral centroid) 1142 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 5 notes (G1 E2 G2 D4); hits (knock); loops (2 x 1bar, 1 x 2bar).
- **Track 2**: bass [Likely], G1 to A2, 2.69 attacks a second, brightness (spectral centroid) 233 Hz. Pitched, below C3 most of the time, energy sits low. Took: 4 notes (D2 E2); loops (3 x 1bar, 1 x 2bar).
- **Track 3**: synth lead [Guessing], B0 to A3, 0.38 attacks a second, brightness (spectral centroid) 734 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). The recording clips in places (210 samples at full scale). Took: 1 note (G3); loops (1 x 1beat, 1 x 2beat, 1 x 1bar).

### Mk1_053

80.0 BPM [Guessing], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 160.0 BPM). Beat tracker said 106.6 BPM. Key C major [Guessing], or E minor.

- **Track 1**: bass [Likely], G1 to C2, 0.17 attacks a second, brightness (spectral centroid) 1457 Hz. Pitched, below C3 most of the time, energy sits low. Took: loops (1 x 1bar, 1 x 1beat, 1 x 2beat).
- **Track 2**: synth lead [Guessing], C4 to E4, 5.42 attacks a second, brightness (spectral centroid) 869 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 3 notes (C4 D4); loops (2 x 1bar, 1 x 2bar).
- **Track 3**: synth lead [Guessing], E3 to C4, 8.25 attacks a second, brightness (spectral centroid) 1380 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).
- **Track 4**: synth lead [Guessing], C4 to G4, 5.83 attacks a second, brightness (spectral centroid) 2843 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: hits (snare or clap); loops (3 x 1bar, 1 x 2bar).
- **Track 5**: mixed [Guessing], C1 to C4, 5.67 attacks a second, brightness (spectral centroid) 3366 Hz. No single family fits the numbers. Took: loops (1 x 1bar, 1 x 2beat, 1 x 1beat).

### Mk1_056

120.0 BPM [Likely], tracks are 4, 4, 4 bars (runner-up reading: 90.0 BPM). Beat tracker said 116.1 BPM. Key F major [Guessing], or D minor.

- **Track 1**: bass [Likely], A#1 to G2, 2.5 attacks a second, brightness (spectral centroid) 278 Hz. Pitched, below C3 most of the time, energy sits low. Took: 1 note (E2); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], G2 to A3, 5.75 attacks a second, brightness (spectral centroid) 2019 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).
- **Track 3**: bass [Likely], A#1 to G2, 2.5 attacks a second, brightness (spectral centroid) 1304 Hz. Pitched, below C3 most of the time, energy sits low. Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).

### Mk1_063

120.0 BPM [Likely], tracks are 4, 4, 4, 4 bars (runner-up reading: 60.0 BPM). Beat tracker said 118.8 BPM. Key B minor [Guessing], or D major.

- **Track 1**: bass [Likely], F#1 to D3, 2.75 attacks a second, brightness (spectral centroid) 904 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (B1 G1); loops (2 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], D3 to A3, 1.0 attacks a second, brightness (spectral centroid) 1934 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 1 note (E3); hits (knock, tom or thump); loops (1 x 1bar, 1 x 1beat, 1 x 2beat).
- **Track 3**: synth lead [Guessing], D2 to B5, 1.88 attacks a second, brightness (spectral centroid) 1581 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 8 notes (D2 G2 F#4 A5 B5 F#5); loops (3 x 1bar, 1 x 2bar).
- **Track 4**: synth lead [Guessing], B2 to B4, 1.37 attacks a second, brightness (spectral centroid) 2222 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 4 notes (B2 D3 B4); hits (kick, bell or ping); loops (3 x 1bar).

### Mk1_076

100.0 BPM [Likely], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 200.0 BPM). Beat tracker said 100.3 BPM. Key F# major [Guessing], or F# minor.

- **Track 1**: bass [Likely], E1 to B2, 3.85 attacks a second, brightness (spectral centroid) 517 Hz. Pitched, below C3 most of the time, energy sits low. The recording clips in places (1038 samples at full scale). Took: 7 notes (A1 E1 F#1 B2 E2); loops (1 x 2bar, 3 x 1bar).
- **Track 2**: synth lead [Guessing], A#2 to F#3, 3.75 attacks a second, brightness (spectral centroid) 1401 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 1 note (E3); loops (2 x 1bar, 1 x 2bar).
- **Track 3**: synth lead [Guessing], B1 to F#4, 1.35 attacks a second, brightness (spectral centroid) 1517 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (2 x 1bar, 1 x 2bar).
- **Track 4**: mixed [Guessing], D#6 to E6, 3.96 attacks a second, brightness (spectral centroid) 4001 Hz. Both clear hits and clear notes: drums with a pitched part, or a beatboxed line. Took: 4 notes (D#6 E6 D#7); hits (bell or ping x2); loops (3 x 1bar, 1 x 2bar).
- **Track 5**: mixed [Guessing], B2 to E5, 4.48 attacks a second, brightness (spectral centroid) 3938 Hz. Drum-like attacks and steady pitch in the same track: drums with a pitched part, or a beatboxed line. The recording clips in places (150 samples at full scale). Took: 2 notes (E5); hits (kick x2, tom or thump x2); loops (3 x 1bar, 1 x 2bar).

### Mk1_082

120.0 BPM [Likely], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 60.0 BPM). Beat tracker said 121.6 BPM. Key E major [Likely], or C# minor.

- **Track 1**: bass [Guessing], A1 to C#3, 4.0 attacks a second, brightness (spectral centroid) 1273 Hz. Pitched, below C3 most of the time, energy sits low. Took: 3 notes (A1 A2 B2); hits (kick); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: drums [Guessing], 5.88 attacks a second, brightness (spectral centroid) 4188 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Took: hits (kick x2, snare or clap x2, knock, click); loops (1 x 2bar, 2 x 1bar, 1 x 2beat).
- **Track 3**: guitar [Guessing], E2 to F#2, 3.12 attacks a second, brightness (spectral centroid) 2859 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Took: 2 notes (E2); hits (knock); loops (3 x 1bar, 1 x 2bar).
- **Track 4**: percussion [Likely], 5.12 attacks a second, brightness (spectral centroid) 5687 Hz. Most attacks are noisy high-frequency bursts, no kick-like low end. Took: hits (open hat or cymbal x2, bell or ping x2); loops (1 x 2beat, 2 x 1bar, 1 x 2bar).
- **Track 5**: bass [Likely], C#1 to D2, 4.75 attacks a second, brightness (spectral centroid) 1629 Hz. Pitched, below C3 most of the time, energy sits low. Took: 1 note (C#2); loops (1 x 2beat, 1 x 1beat, 1 x 1bar).

### Mk1_087

84.0 BPM [Guessing], tracks are 4, 4, 4, 4 bars (runner-up reading: 168.0 BPM). Beat tracker said 166.7 BPM. Key B minor [Guessing], or E minor.

- **Track 1**: synth lead [Guessing], C2 to E3, 1.05 attacks a second, brightness (spectral centroid) 344 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 4 notes (C2 E2 D3); loops (1 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], B2 to B3, 0.79 attacks a second, brightness (spectral centroid) 1170 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 2bar, 2 x 1bar).
- **Track 3**: bass [Likely], E1 to E2, 4.81 attacks a second, brightness (spectral centroid) 313 Hz. Pitched, below C3 most of the time, energy sits low. Took: 1 note (E1); loops (1 x 2beat, 1 x 1beat, 1 x 1bar).
- **Track 4**: synth lead [Guessing], G3 to D4, 1.57 attacks a second, brightness (spectral centroid) 1371 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).

### Mk2_015

120.0 BPM [Likely], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 90.0 BPM). Beat tracker said 121.6 BPM. Key E major [Likely], or C# minor.

- **Track 1**: bass [Likely], E1 to E2, 5.88 attacks a second, brightness (spectral centroid) 526 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (A1 B1); hits (tom or thump x2); loops (1 x 1bar, 1 x 2beat, 1 x 1beat).
- **Track 2**: drums [Likely], 3.75 attacks a second, brightness (spectral centroid) 4868 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Took: hits (kick x2, snare or clap x2); loops (1 x 2beat, 2 x 1bar, 1 x 2bar).
- **Track 3**: guitar [Guessing], F1 to E3, 2.25 attacks a second, brightness (spectral centroid) 2136 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Took: 1 note (E1); loops (1 x 2beat, 1 x 1bar, 1 x 1beat).
- **Track 4**: synth lead [Guessing], A2 to E5, 3.88 attacks a second, brightness (spectral centroid) 2107 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 5 notes (A2 C#5 D5 E5); hits (knock x2, click); loops (2 x 1bar, 1 x 2bar).
- **Track 5**: bass [Guessing], F#1 to B2, 3.88 attacks a second, brightness (spectral centroid) 1939 Hz. Pitched, below C3 most of the time, energy sits low. Took: 1 note (B1); loops (2 x 1bar).

### Mk2_017

152.7 BPM [Guessing], tracks are 4, 4, 4, 4, 8 bars (runner-up reading: 76.3 BPM). Beat tracker said 147.7 BPM. Key D major [Guessing], or G major.

- **Track 1**: synth lead [Guessing], D3 to A3, 4.77 attacks a second, brightness (spectral centroid) 1558 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 2beat, 1 x 1bar, 1 x 1beat).
- **Track 2**: drums [Likely], 2.86 attacks a second, brightness (spectral centroid) 3216 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Took: hits (kick x2, tom or thump, snare or clap x2); loops (2 x 1bar, 1 x 2beat, 1 x 2bar).
- **Track 3**: synth lead [Guessing], C#5 to A5, 5.56 attacks a second, brightness (spectral centroid) 2290 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 2 notes (A5 C#5); loops (1 x 2bar, 3 x 1bar).
- **Track 4**: bass [Likely], F#1 to B2, 3.18 attacks a second, brightness (spectral centroid) 688 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (F#1 G1); loops (4 x 1bar).
- **Track 5**: bass [Likely], D1 to B2, 2.86 attacks a second, brightness (spectral centroid) 1202 Hz. Pitched, below C3 most of the time, energy sits low. Took: 5 notes (G1 A2 B2 G2); loops (1 x 2beat, 1 x 1beat, 1 x 1bar).

### Mk2_018

155.0 BPM [Guessing], tracks are 4, 4, 8, 4, 4 bars (runner-up reading: 77.5 BPM). Beat tracker said 156.6 BPM. Key B minor [Likely], or D major.

- **Track 1**: bass [Likely], E1 to F2, 6.3 attacks a second, brightness (spectral centroid) 616 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (B1); loops (2 x 1bar).
- **Track 2**: synth lead [Guessing], A3 to D4, 3.23 attacks a second, brightness (spectral centroid) 853 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 2 notes (D4); loops (3 x 1bar, 1 x 2bar).
- **Track 3**: drums [Likely], 4.68 attacks a second, brightness (spectral centroid) 3709 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Took: hits (snare or clap x2, kick x2); loops (1 x 2bar, 2 x 1bar, 1 x 2beat).
- **Track 4**: guitar [Guessing], A4 to B5, 4.04 attacks a second, brightness (spectral centroid) 1460 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Took: 3 notes (A4 B4 E5); loops (4 x 1bar).
- **Track 5**: synth lead [Guessing], B3 to F#5, 2.1 attacks a second, brightness (spectral centroid) 1996 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 1 note (F#4); loops (2 x 1bar).

### Mk2_025

125.0 BPM [Likely], tracks are 4, 4, 4, 8, 16 bars (runner-up reading: 62.5 BPM). Beat tracker said 126.0 BPM. Key B minor [Likely], or D major.

- **Track 1**: bass [Likely], A1 to E2, 5.47 attacks a second, brightness (spectral centroid) 1057 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (B1); loops (1 x 1bar, 1 x 2beat, 1 x 1beat).
- **Track 2**: synth lead [Guessing], D3 to F#3, 2.34 attacks a second, brightness (spectral centroid) 1559 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: loops (1 x 2bar, 2 x 1bar).
- **Track 3**: guitar [Guessing], D3 to C#4, 1.69 attacks a second, brightness (spectral centroid) 2948 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Took: 5 notes (B3 E3 C#4); loops (3 x 1bar, 1 x 2bar).
- **Track 4**: bass [Likely], G1 to D2, 5.66 attacks a second, brightness (spectral centroid) 1421 Hz. Pitched, below C3 most of the time, energy sits low. Took: loops (1 x 2beat, 1 x 1beat, 1 x 1bar).
- **Track 5**: synth lead [Guessing], F#3 to B4, 2.25 attacks a second, brightness (spectral centroid) 2579 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 11 notes (A4 B4 C#4 D4 F#4 G4); loops (1 x 2bar, 3 x 1bar).

### Mk2_028

154.0 BPM [Guessing], tracks are 4, 4, 8, 4, 8 bars (runner-up reading: 77.0 BPM). Beat tracker said 154.3 BPM. Key B minor [Guessing], or B major.

- **Track 1**: bass [Guessing], E1 to E2, 4.01 attacks a second, brightness (spectral centroid) 2923 Hz. Pitched, below C3 most of the time, energy sits low. The recording clips in places (2 samples at full scale). Took: 1 note (B1); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: bass [Guessing], G1 to B1, 3.37 attacks a second, brightness (spectral centroid) 1861 Hz. Pitched, below C3 most of the time, energy sits low. Took: loops (1 x 1bar, 1 x 2beat, 1 x 1beat).
- **Track 3**: synth lead [Guessing], B2 to B3, 1.84 attacks a second, brightness (spectral centroid) 1745 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 10 notes (B2 D2 A3 B3 D3 E3 F#3); loops (3 x 1bar, 1 x 2bar).
- **Track 4**: mixed [Guessing], B0 to B3, 6.1 attacks a second, brightness (spectral centroid) 2020 Hz. No single family fits the numbers. Took: loops (1 x 2beat, 1 x 1beat, 1 x 1bar).
- **Track 5**: synth lead [Guessing], B1 to A5, 2.25 attacks a second, brightness (spectral centroid) 2648 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 1 note (B3); loops (3 x 1bar, 1 x 2bar).

### Mk2_030

145.0 BPM [Likely], tracks are 8, 8, 8, 8, 8 bars (runner-up reading: 72.5 BPM). Beat tracker said 143.6 BPM. Key E major [Likely], or B major.

- **Track 1**: bass [Likely], E1 to D2, 4.23 attacks a second, brightness (spectral centroid) 1420 Hz. Pitched, below C3 most of the time, energy sits low. Took: 2 notes (E1 D2); loops (1 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], B3 to F#4, 1.74 attacks a second, brightness (spectral centroid) 2879 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 1 note (F#4); loops (1 x 2bar, 3 x 1bar).
- **Track 3**: drums [Likely], 3.02 attacks a second, brightness (spectral centroid) 2857 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. The recording clips in places (10 samples at full scale). Took: hits (kick x2); loops (1 x 2bar, 1 x 2beat, 2 x 1bar).
- **Track 4**: bass [Guessing], G#1 to B2, 4.61 attacks a second, brightness (spectral centroid) 1586 Hz. Pitched, below C3 most of the time, energy sits low. Took: 4 notes (A1 B1); loops (3 x 1bar, 1 x 2bar).
- **Track 5**: synth lead [Guessing], E3 to C#4, 0.23 attacks a second, brightness (spectral centroid) 3461 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Took: 2 notes (B3 G#3); loops (1 x 2bar, 2 x 1bar).

### Mk2_053

123.0 BPM [Guessing], tracks are 8, 8, 8, 4 bars (runner-up reading: 61.5 BPM). Beat tracker said 118.8 BPM. Key B minor [Guessing], or D major.

- **Track 1**: percussion [Likely], 3.59 attacks a second, brightness (spectral centroid) 5614 Hz. Most attacks are noisy high-frequency bursts, no kick-like low end. Recorded on the left side only (other side -19 dB). The recording clips in places (37 samples at full scale). Took: hits (closed hat x2, snare or clap x2, open hat or cymbal x2); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: synth lead [Guessing], G2 to B2, 0.19 attacks a second, brightness (spectral centroid) 1475 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -16 dB). Took: loops (3 x 1bar, 1 x 2bar).
- **Track 3**: guitar [Guessing], D4 to G#4, 1.86 attacks a second, brightness (spectral centroid) 442 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the right side only (other side -11 dB). Took: 6 notes (D4 E4 F#4); loops (2 x 1bar, 1 x 2bar).
- **Track 4**: drums [Guessing], 3.59 attacks a second, brightness (spectral centroid) 6024 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -23 dB). The recording clips in places (108 samples at full scale). Took: hits (kick x2, open hat or cymbal); loops (1 x 2beat).

### Mk2_055

120.0 BPM [Likely], tracks are 8, 8, 4, 4, 4 bars (runner-up reading: 60.0 BPM). Beat tracker said 120.2 BPM. Key D minor [Likely], or D major.

- **Track 1**: guitar [Guessing], A#1 to F3, 3.19 attacks a second, brightness (spectral centroid) 972 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the right side only (other side -17 dB). Took: 6 notes (A#1 A#2 C3 C4 F4); loops (1 x 2bar, 3 x 1bar).
- **Track 2**: bass [Likely], D1 to A1, 2.0 attacks a second, brightness (spectral centroid) 1112 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -3 dB). Took: loops (1 x 1bar, 1 x 2beat, 1 x 1beat).
- **Track 3**: drums [Likely], 3.88 attacks a second, brightness (spectral centroid) 7481 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -26 dB). The recording clips in places (73 samples at full scale). Took: hits (kick x2, snare or clap x2); loops (1 x 2beat, 2 x 1bar, 1 x 1beat).
- **Track 4**: guitar [Guessing], A4 to D5, 1.62 attacks a second, brightness (spectral centroid) 1147 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the left side only (other side -67 dB). Took: 3 notes (A4 D5); loops (3 x 1bar).
- **Track 5**: synth lead [Guessing], G1 to G3, 1.12 attacks a second, brightness (spectral centroid) 840 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -73 dB). Took: 3 notes (D3 F3); loops (1 x 2bar, 3 x 1bar).

### Mk2_056

120.0 BPM [Likely], tracks are 4, 4, 8, 4, 8 bars (runner-up reading: 60.0 BPM). Beat tracker said 120.2 BPM. Key F major [Likely], or D minor.

- **Track 1**: bass [Likely], F2 to C3, 4.25 attacks a second, brightness (spectral centroid) 1769 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -26 dB). Took: 5 notes (A#2 F2 C3); loops (1 x 1bar, 1 x 1beat, 1 x 2beat).
- **Track 2**: synth lead [Guessing], F1 to F2, 3.62 attacks a second, brightness (spectral centroid) 2139 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -29 dB). Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).
- **Track 3**: mixed [Guessing], F3 to A4, 1.44 attacks a second, brightness (spectral centroid) 3084 Hz. Drum-like attacks and steady pitch in the same track: drums with a pitched part, or a beatboxed line. Recorded on the right side only (other side -14 dB). Took: 3 notes (F3 F4); hits (open hat or cymbal, closed hat x2); loops (3 x 1bar, 1 x 2bar).
- **Track 4**: drums [Likely], 4.88 attacks a second, brightness (spectral centroid) 4870 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -29 dB). The recording clips in places (183 samples at full scale). Took: hits (kick x2); loops (2 x 1bar, 1 x 2bar, 1 x 2beat).
- **Track 5**: synth lead [Guessing], A#3 to C5, 0.38 attacks a second, brightness (spectral centroid) 3980 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -67 dB). Took: 5 notes (A4 F4 G4 C5); loops (1 x 1bar, 1 x 2bar).

### Mk2_063

120.0 BPM [Guessing], tracks are 4, 4, 4, 4 bars (runner-up reading: 60.0 BPM). Beat tracker said 126.0 BPM. Key C# minor [Guessing], or A major.

- **Track 1**: bass [Likely], D2 to F#2, 11.62 attacks a second, brightness (spectral centroid) 3316 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -18 dB). Took: loops (1 x 2beat, 1 x 1bar, 1 x 1beat).
- **Track 2**: drums [Likely], 3.75 attacks a second, brightness (spectral centroid) 5684 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -32 dB). The recording clips in places (19 samples at full scale). Took: hits (open hat or cymbal x2, kick x2, snare or clap x2); loops (2 x 1bar, 1 x 2bar, 1 x 2beat).
- **Track 3**: synth lead [Guessing], B3 to C#5, 0.88 attacks a second, brightness (spectral centroid) 1462 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -17 dB). Took: loops (3 x 1bar, 1 x 2bar).
- **Track 4**: synth lead [Guessing], G1 to F#4, 2.88 attacks a second, brightness (spectral centroid) 5349 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the left side only (other side -15 dB). Took: loops (1 x 2bar, 3 x 1bar).

### Mk2_075

110.0 BPM [Likely], tracks are 4, 4, 12, 4, 4 bars (runner-up reading: 82.5 BPM). Beat tracker said 110.0 BPM. Key C minor [Guessing], or F minor.

- **Track 1**: bass [Likely], G#1 to G2, 5.16 attacks a second, brightness (spectral centroid) 749 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -51 dB). Took: 8 notes (G#1 C2 D#2 G2); loops (3 x 1bar, 1 x 2bar).
- **Track 2**: drums [Guessing], 4.47 attacks a second, brightness (spectral centroid) 4348 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -31 dB). Took: hits (kick x2, snare or clap x2); loops (1 x 2beat, 2 x 1bar, 1 x 2bar).
- **Track 3**: guitar [Guessing], D3 to G3, 2.75 attacks a second, brightness (spectral centroid) 1447 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the left side only (other side -25 dB). Took: 9 notes (D#3 D3 F3 G#3 G3); hits (knock); stab Dsus4; loops (3 x 1bar, 1 x 2bar).
- **Track 4**: bass [Likely], G#1 to F2, 4.13 attacks a second, brightness (spectral centroid) 581 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -21 dB). Took: loops (3 x 1bar, 1 x 2bar).
- **Track 5**: guitar [Guessing], F1 to C2, 2.52 attacks a second, brightness (spectral centroid) 1974 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the left side only (other side -21 dB). Took: 2 notes (F1 C2); loops (3 x 1bar, 1 x 2bar).

### Mk2_082

140.0 BPM [Likely], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 70.0 BPM). Beat tracker said 141.6 BPM. Key A major [Guessing], or D major.

- **Track 1**: bass [Guessing], A1 to E2, 4.67 attacks a second, brightness (spectral centroid) 757 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -38 dB). Took: hits (kick x2); loops (2 x 1bar).
- **Track 2**: bass [Guessing], D2 to B3, 5.4 attacks a second, brightness (spectral centroid) 1394 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -29 dB). Took: 5 notes (E2 A3 B3 D3); loops (1 x 1bar, 1 x 2bar).
- **Track 3**: guitar [Guessing], D4 to A4, 1.9 attacks a second, brightness (spectral centroid) 1657 Hz. One pitch at a time and each note falls away after its attack, like a picked string (a plucked synth would look the same). Recorded on the right side only (other side -15 dB). Took: 7 notes (A4 D4 E4 G4); loops (1 x 1bar, 1 x 2bar).
- **Track 4**: drums [Guessing], 6.27 attacks a second, brightness (spectral centroid) 6742 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -15 dB). The recording clips in places (102 samples at full scale). Took: hits (snare or clap x2, kick x2); loops (1 x 1beat, 1 x 1bar, 1 x 2beat).
- **Track 5**: bass [Likely], A1 to E3, 4.67 attacks a second, brightness (spectral centroid) 1563 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -28 dB). Took: loops (2 x 1bar, 1 x 2bar).

### Mk2_087

84.0 BPM [Guessing], tracks are 4, 4, 4, 4, 4 bars (runner-up reading: 168.0 BPM). Beat tracker said 112.3 BPM. Key E minor [Likely], or G major.

- **Track 1**: drums [Likely], 2.01 attacks a second, brightness (spectral centroid) 4031 Hz. Most attacks are sudden jumps of low (kick-like) or noisy high (hat or snare-like) energy, little steady pitch. Recorded on the left side only (other side -18 dB). Took: hits (kick x2, snare or clap); loops (1 x 2beat, 2 x 1bar, 1 x 2bar).
- **Track 2**: bass [Likely], C1 to G1, 1.93 attacks a second, brightness (spectral centroid) 257 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the right side only (other side -45 dB). Took: loops (3 x 1bar, 1 x 2bar).
- **Track 3**: synth lead [Guessing], E2 to B4, 2.1 attacks a second, brightness (spectral centroid) 2395 Hz. One pitch at a time, steady and sustained (a synth, or a guitar with heavy sustain or effects would look the same). Recorded on the right side only (other side -9 dB). Took: loops (1 x 2bar, 3 x 1bar).
- **Track 4**: bass [Likely], D2 to A2, 3.24 attacks a second, brightness (spectral centroid) 689 Hz. Pitched, below C3 most of the time, energy sits low. Recorded on the left side only (other side -12 dB). Took: loops (1 x 1beat, 1 x 2beat, 1 x 1bar).
- **Track 5**: bass [Likely], C2 to E4, 3.94 attacks a second, brightness (spectral centroid) 1519 Hz. Pitched, below C3 most of the time, energy sits low. Took: 6 notes (C2 E2 E3 E4); loops (3 x 1bar).

## How the cutting was calibrated

I tuned everything on 15 stems first, spread over both loopers and over what looked like drums, bass, chords and lead lines:
Mk1_006_4, Mk1_033_2, Mk1_056_2, Mk1_063_3, Mk1_076_4, Mk1_076_5, Mk1_082_2, Mk1_082_5, Mk2_015_2, Mk2_017_3, Mk2_025_5,
Mk2_028_5, Mk2_030_4, Mk2_053_1, Mk2_087_2. Then I ran all 96 with the same settings. What I settled on, and why:

- **Two channels.** Mk1 files and Mk2 memories up to 030 are dual mono (left equals right), so I used their average. From Mk2_053 on,
  the two sides hold different inputs (left/right correlation near 0, the quieter side 3 to 73 dB down), so I used the louder side
  only: averaging would have mixed a second, quieter sound into every clip [Certain]. Rule: average when L/R correlation is 0.5 or more.
- **Levels.** Source peaks run from 0.045 to 0.999, so every test is against the stem's own loudest frame [Certain]. 13 stems
  have samples at full scale (clipped while recording); clips that contain those samples carry the tag `clipped source`.
- **Finding attacks.** Spectral-flux onsets (peak threshold 0.07 of the stem's strongest, at least 45 ms apart). An onset only
  counts if the level rises 1 dB or more within 60 ms: Mk1_006_4's filter wobble set off 116 false onsets that rose 0 to 0.9 dB,
  while real re-attacks on the Mk2_087_2 bass rose 1.1 to 2.6 dB. The cut point is then found sample by sample: halfway up the
  attack, back down the rising edge, 1 ms earlier, then the zero crossing before that.
- **Pitch.** pyin from 30 Hz to 1.4 kHz. The 30 Hz floor matters: with 40 Hz, the Mk2_087_2 bass (down to C1, 32.7 Hz) read as
  unpitched. pyin's own voicing probability was useless as a gate (Mk1_082_5: 84 % of frames voiced, 0 % above probability 0.3),
  so I used its voiced/unvoiced decision. A legato note change counts when the pitch moves more than 0.7 semitone and holds 35 ms;
  a pitch change up to 120 ms after an attack belongs to that attack (in Mk2_025_5, 69 attacks and 72 pitch changes were mostly
  the same 80 notes seen twice). The pitch written in the library comes from a second estimate (harmonic sum on a long FFT,
  1 cent steps), because pyin works in 10 cent steps. Then an octave check: energy only on even harmonics moves the note up an octave
  (7 notes), energy an octave below plus agreement from YIN moves it down (2 notes); doubtful cases are tagged `octave unsure`.
- **What counts as a note.** Pitched for at least 70 % of its steady part, pitch spread (standard deviation) 30 cents or less,
  at least 0.15 s, no more than 2 clear pitches at once, the previous note not still ringing, and the level just before the start
  at least 6 dB under the note's peak. Steady notes in Mk2_025_5 had 0 to 4 cents of spread; wobbly or gliding parts (Mk1_063_3)
  ran 7 to 50. The 6 dB clean-start rule is the strictest one: without it the cutter took about 650 notes, most of them slices out of
  legato lines that start in the middle of a sound; with it, 195.
- **Several pitches at once.** A harmonic sieve on a 36-bins-per-octave spectrum. Each found note's harmonics are removed with a guard
  band, and a note must stand 8 dB above its spectral neighbourhood. Without those two guards, single bass notes read as 4 to 6
  note clusters (Mk1_082_5: an A1 plus leakage at G#1 and A#1), and drum hits read as chords.
- **Stabs.** 3 or more clear pitch classes present in both 40 to 140 ms and 140 to 240 ms after the attack, a harmonic (not noisy)
  spectrum, a clean start, and a nameable chord. Only 1 passed.
- **Hits.** A clean start (10 dB or more under the hit's peak just before it), falls 20 dB within 0.45 s, and the clip's last 20 ms
  sit 12 dB or more under its peak. The name comes from the power in five bands over the first 80 ms: kick when half or more is
  under 120 Hz, hat when 60 % is above 2 kHz and noisy, bell or ping when that high energy is tonal, snare or clap when noisy with
  mids, tom or thump when 120 to 500 Hz dominates, knock or click otherwise.
- **Tempo and bars.** Candidate tempos are the ones where every track of the memory is a whole number of 4/4 bars (within 0.4 % of
  a bar). Each candidate is scored by how well all the tracks' attacks line up with its beats and 8ths, allowing the grid to sit up
  to 87 ms off the loop start, weighted toward 120 BPM, with a bonus when librosa's beat tracker agrees. The grid offset mattered:
  without it Mk1_056 and Mk2_056 came out at 90 BPM; their attacks sit 30 to 45 ms late on a 120 BPM grid.
- **Loops.** Every beat of every stem was tried as a start for 1 bar, 2 bars, 2 beats and 1 beat. Score: 30 % how well the end
  matches what played before the start, 20 % a clear attack at the start, 10 % a clear attack right after the end, 15 % starting on
  a downbeat, 15 % how typical it is of the stem, 10 % level. Floors: 0.55 for one bar, 0.6 for the rest. A loop may start off the
  downbeat only where something clearly starts. Players land a little off the grid, so each track's loops start 5 ms before that
  track's average attack position (68 tracks, all within 50 ms of the grid), or the song's average for tracks with too few clear
  attacks (10), or right on the grid where the attacks are scattered (18). Bars that look the same as a bar already taken are skipped.
- **Keeping the best.** Score floor 0.45 for notes, hits and stabs. At most 2 takes per pitch, chord or hit type in a stem
  (dropped 86 repeat notes and 175 repeat hits). At most 4 loops per stem. Across the tracks of one song, a piece is dropped as
  near-identical when its timbre distance (MFCC) is under 0.12 and it has the same label: repeat takes inside one stem measured
  0.075 (median), different tracks 0.37 (median) and 0.20 at the 5th percentile. Loops also need the same pattern in time and the
  same notes. That dropped 2 notes and 3 hits; no tracks turned out to be copies of each other (waveform correlation 0.27 at most).

## Checks I ran on the finished library

- Decoded all clips: every one is mono 16-bit FLAC at 44.1 kHz, peaks at -1.00 dBFS, none is silent (quietest RMS -28 dBFS), every
  loop is its exact number of beats to within 1 sample, and `library.json` and the files match one to one [Certain].
- Pitch, checked with a third method (YIN) on all 195 notes: 183 agree within 50 cents (median difference 2.3 cents, 90 % within
  12.5); 8 disagree by an octave; 4 disagree otherwise (wobbly or gliding notes) [Certain].
- Spot check of 10 random clips (4 notes, 2 hits, 3 loops, the stab) with methods the cutter does not use: every attack lands
  within the first 2.3 ms of its clip (plain level threshold), every loop is exact to the sample, 3 of the 4 notes agree with YIN
  within 6 cents and one wobbly note (Mk2_053_3_005_D4) differs by 49 cents, and the Dsus4 stab's strongest pitch classes are
  D, G, D# and A [Certain].
- I drew waveform and level pictures of over 100 random clips and the stems they came from, and looked at them. That is how I found
  and fixed early problems: notes that started mid-sound, hits that never died away, cuts that began 40 ms before the attack, and
  loops on a 90 BPM grid that should have been 120 [Certain].

## Honest limits

- **I cannot hear any of this.** Instrument families are guesses from numbers and pictures. "Synth lead" really means "one pitch at
  a time, steady and sustained": it could be a guitar with heavy sustain or effects, an organ, or a voice through effects.
  "Guitar" means the notes fall away after each attack, which a plucked synth also does. I found no pads, keys, voice or noise
  tracks, but I would not bet on that [Guessing].
- **Tempo** is a guess for 10 of the 21 songs, mostly the half-or-double question (Mk1_006: 160 or 80; Mk1_053: 80, 120 or 160;
  Mk1_087 and Mk2_087: 84 or 168, and the beat tracker heard 112 in Mk2_087, which may mean a shuffle or triplet feel; Mk2_017, Mk2_018
  and Mk2_028: around 155 or half that). If the real tempo is half, a "1 bar" loop is really half a bar. I also assumed 4/4 and that
  each loop starts on a downbeat; if Tyler started a loop on another beat, the `starts_on` labels are shifted.
- **Keys** are often a coin toss between relative major and minor (the runner-up is in `stems.json`). 12 of 21 are [Guessing].
- **Notes from legato or dense parts are rare.** 131 clips are tagged `cut short`: the next note arrives before the tail dies away.
  26 stems gave loops only. There is just 1 chord stab: chord-like moments either had noise in them, a note ringing over from
  before, or no clean start.
- **Octaves.** 26 notes are tagged `octave unsure`. Low basses with a weak fundamental are the usual case.
- **Hit names** (kick, snare or clap, hat) are from spectral shape only; a beatboxed kick and a drum kick look alike to me.
- **Tuning.** Many parts sit 10 to 30 cents sharp of A440 and stay there through a song (Mk2_025_5 about +19, Mk2_028 about +22),
  so the band's instruments were probably tuned a little sharp [Likely]. Each note's `cents` field says how far off it is.
- **Split-stereo stems** lost their quieter side. In Mk2_055_2 that side was only 3 dB quieter and held a different sound, which is
  not in the library.
- **Same memory number on both loopers** (053, 056, 063, 082, 087): Mk1_056 and Mk2_056 (both 120 BPM, F major) and Mk1_087 and
  Mk2_087 (both 84 BPM, 11.43 s loops) may be the same songs recorded twice [Guessing]; none of their tracks are copies [Certain].
- **Loudness** is integrated LUFS for clips of 0.4 s or more; shorter clips get the plain K-weighted average.
- 42 clips contain samples that were clipped in the original recording (tag `clipped source`).
