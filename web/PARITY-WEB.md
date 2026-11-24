# Web parity with the Android app (build 172)

Grouped by area; the full row list is `shared/PARITY.md` (its Web column is still `todo`: fill it from here when
this branch merges). `done` means it runs the app's own code or follows its numbers; `part` says what is missing.

| Area | Web | Notes |
|---|---|---|
| Engine (sound, timing, synths, FX, LFO, XY targets, recording) | done | The app's Java engine compiled to JS (TeaVM); JVM vs JS render of the demo matches within 1e-6 on every build. |
| Mess file | done | Read and written by the app's own `Project.fromJson` / `toJson`; app messes open with nothing lost. |
| Mess edits (steps, strength, bars, copy, clear, random, pads, chops, recorded takes) | done | Ports of the AppState functions in `WebEngine.java`. |
| Top bar (Play, Stop, Rec, Undo, BPM, Loop readout with swipe, FAQ, Menu) | done | |
| Tab slot (Play, Synth, Seq, Perform, Sounds) and Note key | done | Note sends to the artifact db (site copy: signed-in owner). |
| Look and skins | part | Panel Look only; all five skins with pad faces. Pro looks, Classic layout, Side layout, view size: not built. |
| Pads, banks, Click, Count-in | done | Multitouch, swipe-to-cancel (24 px / 300 ms), HotKeys (44 px), empty pad corners. |
| Hold (count, Last, Let go), Repeat (rate, feel, style, pattern, arp), Chords (types, 12 sets, play/show, smooth), key panel | done | |
| Mute (mode, bank hold), Drop (held) | done | |
| FannyPack: Padiano (rows, size, notes shown, in key, glide, legato), FX vials (11, lamps, bypass, saved FX), LFO strip and tray, XY strip and setup | done | XY moves are not recorded as lanes yet. |
| XY sphere (circle on a pad) | done | |
| 16 Levels, Perform row, Lock, pitch wheel, mouth control, My Desk | no | |
| Loop strip (A to H, Wait bar / Now, minus, plus, 2x, Copy to, Clear, Bottle it, Song painter with bar insert/delete) | done | Auto lanes in place over vials: no. |
| Steps (accent/ghost cycle, drag paint, bar pages, step sheet chance and strength, row tools) | done | Step lock editing: only clearing. |
| Roll (C1 to C6 window, lengths, fold, chords as stacks) | done | |
| Synth tab (6 kinds, Simple/Full, modes, models, grain source, presets, scratch to pad with replace guard, keys) | done | |
| Sample sheet (wave with trim lines, Hear it, Loop the trim, Play / Trim / Shape / FX / Tools, fit to tempo, chop to pads) | done | Bake, auto/lazy chop, save as new sound: no. |
| Sounds (313 sounds by jar, types, search, stars, Workbench, packs) | done | Packs install only on the Netlify copy. |
| Messes (autosave, save, save new, new, demo, open file, save file, copy/paste) | done | |
| Exports (WAV loop or song, stems zip, MIDI) | done | The app's own bounce and MIDI writer. |
| Learn | part | 11 of the app's 29 lessons, checking the real state. |
| What the FAQ | done | The app's own lines (`Faq.kt`). |
| Mic (record to pad) | part | Netlify copy: the mic. claude.ai: blocked, so it opens the phone's recorder or file picker. |
| MIDI in | part | Netlify copy only: notes 36 to 51 play the bank, others the Padiano, pitch bend. No MIDI map. |
| Settings | part | Skin, Large keys, HotKeys, buzz, keep awake. |
