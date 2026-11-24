# The saved mess file (format v3)

This is the contract for the file every Mess Machine saves and opens: the Android app, the iOS app and the web
rebuild. If you write what this page says and read the way it says, the three can open each other's messes.

The Android code is the source of truth. Everything here was read from it:

- `engine/Project.java`: `toJson` (the writer), `fromJson` (the reader), `encSteps`, `decSteps`, `decNotes`, and the
  `Pattern` class.
- `engine/SoundEdit.java`, `engine/PadOpts.java`, `engine/Synth.java`: the per-sound, per-pad and synth parts.
- `engine/Library.java`, `Packs.kt`, `Workbench.kt`: how a sound key names a sound.
- `engine/Json.java`: the JSON reader and writer.

Statements with no tag are read straight from that code. [Likely] and [Guessing] mark the rest.

The working copy of this check lives in `shared/fixtures/`: `example.mess.json`, four older files and
`other-platform.mess.json`, plus `RoundTrip.java`, which loads them with the Android code and saves them again (see "Checking a reader" at the end).

## 1. The file

- One JSON object, UTF-8. The app names shared files `<slug of the name>.json`. Mess files saved inside the app are
  `<id>.json`, and the working mess is `current.json`.
- Media type when shared: `application/json`.
- The Android writer prints compact JSON (no spaces) with keys in a fixed order (section 3). Readers must not
  depend on key order or whitespace.
- Numbers: whole numbers are printed with no decimal point (`112`, not `112.0`). Others are printed the way Java
  prints a double (`0.08`, and very small ones like `5.0E-4`). Readers must accept any valid JSON number,
  exponents included.
- Several values are strings that hold a list of numbers (motion, locks, chance, offs, the LFO code). Inside those
  strings the Android writer prints Java floats and doubles, so you will see `7.0`, `0.5`, `-12.0`. Parse them as
  numbers; never compare them as text.
- A writer on another platform does not have to match the Android bytes. It has to produce a file that loads into
  the same mess. Compare files by loading them, not by text.

## 2. Shape at a glance

```
{
  "name", "bpm", "v", "kit", "sel", "steps",
  "pats": [ 8 loops, A to H: { "len", "steps", "notes", "motion"?, "offs"?, "acc"?, "chance"?, "locks"? } ],
  "song", "swing", "squash"?, "level"?, "mutes", "solos", "scale", "root", "fold",
  "edits", "synths"?, "padOpts"?, "at", "app", "mode", "cur"
}
```

A `?` means the writer leaves the key out when it holds nothing or only defaults.

## 3. Top-level keys

The order below is the order the Android writer uses.

| Key | Type | Meaning | Written | Read: when absent or wrong type | Read: range and rules |
| --- | --- | --- | --- | --- | --- |
| `name` | string | The mess's name. | Always. Empty becomes `"Untitled mess"`. Cut to 48 UTF-16 code units. | `""` | Kept as is. Not cut on read. |
| `bpm` | number | Tempo in beats a minute. | Always, a whole number. | 112 | Clamped to 60..180, then cut to a whole number (112.9 becomes 112). |
| `v` | number | Format version. Always 3. | Always. | Not read. | The reader never looks at it (section 9). |
| `kit` | array of strings | The pads. Index = pad number. `""` is an empty pad. | Always, 65 entries: pads 0..63 plus slot 64, the Synth tab's scratch synth. | All pads empty. | Entries past 65 are ignored. Non-strings count as empty. See section 4 for what happens to unknown and repeated keys. |
| `sel` | number | The selected pad. | Always. | First loaded pad, else 0. | Must be 0..63 and point at a loaded pad, or it falls back to the first loaded pad (or 0). |
| `steps` | object | A copy of loop A's step rows (same shape as a loop's `steps`). Kept for old readers. | Always. | Ignored. | Read only when `pats` is missing or empty (section 9). |
| `pats` | array of objects | The 8 loops, A to H, in order. Section 5. | Always, all 8. | 8 empty 16-step loops. | Entries past 8 are ignored. Missing entries stay empty 16-step loops. |
| `song` | string | The song lane, one letter per bar. Section 7. | Always, at least 16 characters. | Empty song. | Read only when `pats` is present and not empty. Up to 64 characters are used. |
| `swing` | number | Swing amount, percent. | Always, whole number. | 0 | Clamped 0..100, cut to a whole number. |
| `squash` | number | Master bus squash. 0 off, 0.5 the old fixed amount, 1 hard. | Only when not 0.5. | 0.5 | Clamped 0..1. |
| `level` | number | Master level after the squash. 1 = unchanged. | Only when not 1. | 1 | Clamped 0..1.6. |
| `mutes` | array of strings | Sound keys that are muted. | Always. | Empty. | Non-strings are skipped. Keys not on a pad are kept but do nothing. |
| `solos` | array of strings | Sound keys that are soloed. Any solo means only soloed sounds play. | Always. | Empty. | Same as `mutes`. |
| `scale` | string | Scale name. One of: `Chromatic`, `Major`, `Minor`, `Harmonic minor`, `Dorian`, `Phrygian`, `Mixolydian`, `Major pentatonic`, `Minor pentatonic`, `Blues`. | Always. | `"Chromatic"` | An unknown name becomes `Chromatic`. New messes start in `Minor`, but a file with no scale loads as `Chromatic`. |
| `root` | number | Scale root, 0 = C .. 11 = B. | Always. | 0 | Clamped 0..11, cut to a whole number. |
| `fold` | boolean | Roll folds to the scale. | Always. | false | Must be a real boolean; anything else is false. |
| `edits` | object | Doctor's table settings per sound key. Section 8.1. | Always, possibly `{}`. Only keys on a pad, only non-default edits. | No edits. | Keys not on a pad are dropped. An edit that comes out all default is dropped. |
| `synths` | object | Synth patches per synth key. Section 8.3. | Only when a pad holds a synth. | No synths. | Only keys starting with `synth:` are read. |
| `padOpts` | object | Per-pad settings, keyed by pad number as a string (`"0"` .. `"64"`). Section 8.2. | Only non-default pads. | Defaults. | Keys that are not a whole number in 0..64 are skipped. |
| `at` | string | When it was saved, ISO 8601 UTC (`2026-11-23T20:00:00.123Z`). | Always (left out only for internal undo compares). | Not read. | Not read. |
| `app` | string | Who wrote it. Android writes `"android"`. The site writes nothing. | Always. | Not read. | Not read. [Likely] iOS should write `"ios"` and the web rebuild `"web"`; it is only a label. |
| `mode` | string | `"song"` or `"loop"`: what Play plays. | Always. | Loop mode. | Song mode only when it is exactly `"song"` and the song has at least one bar. |
| `cur` | number | The loop on screen, 0 = A .. 7 = H. | Always. | 0 | Clamped 0..7. |

The site writes nothing that is not in this table. Its file is a strict subset: `name`, `bpm`, `v`,
`kit`, `sel`, `steps`, `pats` (with only `len`, `steps`, `notes`), `song`, `swing`, `mutes`, `solos`, `scale`,
`root`, `fold`, `edits`, `at`.

## 4. Sound keys: how a pad names its sound

Every pad holds a string key. Steps, notes, edits, mutes and the per-step extras are all keyed by that string, not
by pad number. (Motion lanes are the one exception: they use the pad number. Section 6.4.)

### 4.1 Key families

| Key looks like | What it is | Where the sound comes from |
| --- | --- | --- |
| `samples/<name>.wav` | One of the 43 named built-in sounds (`samples/basement-kick.wav`). | `app/src/main/assets/sounds/library.json` entry with this `k`; the audio is the FLAC in its `f` field (`basement-kick.flac`). The `.wav` in the key is history from the site; the file is FLAC. |
| `loop:<song>-<n>` | A built-in Qbldy loop (90 of them). | Same: `library.json`, file like `loops/ametrine-1.flac`. Entries with `bpm` and `beats` follow the mess's tempo. |
| `hit:<song>-<n>` | A built-in Qbldy hit (180). | Same. |
| `qb:<slug>` | An old Qbldy key from earlier site builds. | Not a sound of its own: `library.json` `aliases` maps each one (390 of them) to the `hit:` key that replaced it. The pad keeps the `qb:` spelling and saves it back unchanged. |
| `pack:<packId>:<clipId>` | A clip from an installed sound pack. | The pack's own folder on the device (`packs/<packId>/`, from a zip on the `packs` release). `pack.json` lists clips; the key uses the clip's `id`, else its file name without the extension. Pack ids match `[a-z0-9][a-z0-9-]{0,40}`. |
| `bn:<id>` | A Workbench sound: an import, mic take, resample or saved edit. | A WAV in the device's own Workbench folder. The id is made on that device (time in base 36 plus 3 digits). Another device will not have it. |
| `chop:<id>` | A Workbench chop: a region of another sound. | No file. The device's Workbench index says which sound it chops; the region itself travels in the mess as that key's `edits` `st` and `en`. [Likely] the region is always in `edits`, because the app writes it there when the chop goes on a pad. |
| `synth:<n>` | A synth built in this mess. | The mess itself: its `synths` entry. It needs no audio file. |
| `a:<asset>` | A site sound with no `src` (an older site upload). | Only the site. Android has none of these. |

Built-in sounds are the same keys on every platform. [Likely] iOS and web should ship the same `library.json`
(keys, `aliases`, files) so that every built-in key resolves. [Likely] the site's sound list is not identical to
the app's today (the app has 313 sounds after the "piece 10" cleanup, the README still says 433); keys the app dropped
are covered by `aliases`.

### 4.2 Loading the kit

For each `kit` entry, in order:

1. Not a string, or `""`: the pad is empty.
2. The key is known (in the library, an alias, an installed pack, this device's Workbench) or it is a `synth:` key
   that has an entry in this file's `synths`: it goes on that pad, unless the same key is already on an earlier pad.
   A sound lives on one pad at a time, so a repeated key keeps only its first pad and the later pad loads empty.
3. A `synth:` key with no entry in `synths`: the pad loads empty and the missing count goes up by one (a synth with
   no patch is nothing).
4. Otherwise the sound is missing on this device. Since Nov 23 2026 (Android build 172) **the key stays on its pad**:
   the pad is silent, reads "Missing" and keeps its steps, notes, offs, acc, chance, locks and edits, and the next
   save writes it all back. The missing count goes up by one and the app says "N pads use sounds this phone does not
   have. They show as Missing and stay in the mess." If the sound arrives later (a pack installed, the account syncs
   it), the pad plays again with nothing else to do.

Then everything keyed by a sound that is not on a pad is dropped: steps and notes on load, and offs, acc, chance,
locks and edits on the next save.

Every platform must do the same: **never write `""` for a pad just because its sound is not on this device.** Before
build 172 Android did, so a mess opened and saved there lost those pads for good.

### 4.3 Finding the audio for a key

- Built-in: `library.json` sits in the sounds folder (`assets/sounds/` on Android). Each sound entry is
  `{ "k": key, "t": title, "c": jar id, "f": file relative to the sounds folder, "ty": type, "q": [qualities],
  "g": group, "al": album, "bpm", "beats", "mkey", "a", "d" }`. When `d` is above 0 the sound is a slice of the
  sprite file `f`, starting `a` seconds in and lasting `d` seconds (no current sound uses this). Files ending in
  `.flac` are FLAC, anything else WAV.
- Look up aliases after the direct keys: an alias never hides a real key.
- Packs, Workbench: device storage only. They never travel inside the mess file.

## 5. A loop (`pats[i]`)

`pats` has 8 entries, slots A to H (`"ABCDEFGH"`, index 0 to 7).

| Key | Type | Meaning | Written | Read |
| --- | --- | --- | --- | --- |
| `len` | number | Steps in the loop. 16 steps = 1 bar of sixteenths. | Always. | Must be 16, 32, 48 ... 128 (a multiple of 16 from 1 to 8 bars). Anything else becomes 16. |
| `steps` | object: key to string | One row per sound: which steps hit. | Always: one entry for every loaded pad, in kit order, even when all zeros. | Section 6.1. |
| `notes` | object: key to string | Piano roll notes per sound. | Always, possibly `{}`. Only sounds on a pad with at least one note. | Section 6.2. An old string form is also read (section 9). |
| `motion` | object: `"pad:target"` to string | Recorded XY moves: one value per step for one control of one pad. | Only when the loop has lanes. Every lane is written, even one whose pad is now empty. | Section 6.4. |
| `offs` | object: key to string | Hits between the sixteenths, in ticks. | Only when some sound on a pad has one. | Section 6.3. |
| `acc` | object: key to string | How hard each step hits. | Only rows that differ from normal, sounds on a pad. | Section 6.5. |
| `chance` | object: key to string | Whether each step plays this time round. | Only rows that differ from always, sounds on a pad. | Section 6.6. |
| `locks` | object: `"key@target"` to string | A control's value for one step only. | Only for sounds on a pad. | Section 6.7. |

Lengths: `len` is how far the loop plays. Every per-step string is written with exactly `len` entries. Readers keep
up to 128 entries (`MAXSTEPS`) from a longer string; entries at `len` or past it are not played and are not saved
again.

Multi-bar loops: 16, 32, 48, 64, 80, 96, 112 or 128 steps (1 to 8 bars). The site itself only plays 16 or 32 and
turns any other `len` into 16 when it loads [read from `site/index2.src.html`, `loadSeq`]. The web rebuild must
accept the full range.

The ticks grid: `TICKS = 24` ticks per step, so 96 per quarter note. A loop of `len` steps has `len * 24` ticks.

## 6. The compact strings

### 6.1 Steps: `"1010..."`

One character per step. `1` is a hit. Any other character, or a missing one, is no hit. Written as `0` and `1`,
exactly `len` characters. A row with no `1` at all is not kept in memory (and is written back as all zeros only
because the writer writes a row for every loaded pad).

### 6.2 Notes: `"s.p.l,s.p.l,..."`

A comma list of notes. Each note is three whole numbers joined by dots:

- `s`: the step it starts on, 0 to `len - 1`.
- `p`: pitch in semitones from the sound's own pitch (0 = as recorded, C4 on the keyboards). Can be negative, so a
  note looks like `8.-5.4`.
- `l`: length in steps, at least 1.

Reading: notes with fewer than three parts, or parts that are not whole numbers, are skipped. Spaces around the
numbers are allowed. `s` outside 0..`len - 1` is dropped. `p` is clamped to -36..24 (`Theory.ROLL_LO`,
`ROLL_HI`). `l` is clamped to 1..`len - s`, so a note never runs past the loop end. Several notes can share a step
(a chord). Notes are read after `len`, so a 32-step loop keeps notes on steps 16 to 31.

The site clamps nothing and its roll only spans -12..12 [read from the site source], so a site mess always fits.

A step can have both a hit in `steps` and notes; both play.

### 6.3 Off-grid hits (`offs`): `"92,284"`

A comma list of tick positions from the top of the loop (`step * 24 + tick inside the step`), written sorted, no
spaces, no repeats.

Reading: each entry is trimmed and parsed as a whole number; bad entries are skipped. The tick is wrapped into
0..`len * 24 - 1`. **A tick that lands exactly on a step (a multiple of 24) is turned into a normal step hit** in
`steps` and is not kept in `offs`. Duplicates are dropped.

Offs are read before `notes` but after `len`, so the wrap uses the right length.

### 6.4 Motion lanes: `"0.2,,,,0.4,,,"`

The key is `"<pad>:<target>"`: the pad number (0..64, not the sound key) and a control target number (table in
6.8). The value is a comma list with exactly `len` fields. An empty field means "no move on this step"; while the
loop plays, the control holds the last value it was given. A number is the control's value on that step, rounded to
3 decimals when written (printed as a Java float: `0.5`, `7.0`).

Reading: split on commas keeping empty fields. Each non-empty field that parses as a number is stored; others are
treated as empty. Lane keys are not checked on load; a key whose pad or target does not parse, or whose target is
24 or more, is ignored when playing.

Because lanes use the pad number, the app moves them when pads are swapped and deletes them when a pad is cleared.
Another platform must do the same.

### 6.5 Accents (`acc`): `"a.....g........."`

One character per step: `g` ghost (quiet), `.` normal, `a` accent (full). Written with exactly `len` characters,
and only when the row has at least one `g` or `a`. Reading: `g` and `a` count, every other character is normal.
Missing row means every step is normal.

Engine gains: ghost 0.42, normal 0.85, accent 1.0 (`Engine.STEP_GAIN`).

### 6.6 Chance (`chance`): `",,,,,,,,,,,,50,,,"`

A comma list with exactly `len` fields, one per step. Empty = always plays (100). Otherwise a whole number:

| Value | Meaning |
| --- | --- |
| 0 to 99 | Percent chance this step plays each time round. |
| 100 | Always (written as an empty field). |
| 101 | Plays on the 1st of every 2 times round the loop. |
| 102 | The 2nd of every 2. |
| 103 | The 1st of every 4. |
| 104 | The 4th of every 4. |

The app offers 100, 75, 50, 25, 101, 102, 103, 104. Reading: each non-empty field is trimmed and parsed as a whole
number (a bad one counts as 100), then clamped to 0..104. So 150 becomes 104, not 100. A row is written only when
some step is not 100.

"Times round the loop" counts loop wraps since Play was pressed (it starts at 0). The percent dice is a seeded
`java.util.Random(11)` per engine, rolled once per step hit whose chance is 0..99, in pad order. Section 11 says why
this matters for matching engines.

Chance gates the step hit only. Notes and off-grid hits on that step still play.

### 6.7 Step locks (`locks`): `"0.7,,,,,,,,0.3,,,,,,,"`

The key is `"<sound key>@<target>"`. The sound key may itself contain `@`; the target is after the last `@`. The
value is a comma list with exactly `len` fields: empty = no lock, a number = the control's value on that step only
(rounded to 3 decimals, printed as a Java float). On the next step without a lock the control snaps back.

A lock only takes effect on a step that has a hit in `steps`.

Reading: a key with no `@`, or a target that is not a whole number 0 or above, is skipped. Bad numbers are skipped.
A lock row that ends up with no values is not kept. Targets of 24 and up load but are ignored when playing.

### 6.8 Control targets

Used by motion lanes, locks, the pad's `fx` rest values and the LFO target (`Engine.T_*`):

| # | Control | # | Control | # | Control |
| --- | --- | --- | --- | --- | --- |
| 0 | Pitch (semitones) | 8 | Reverb | 16 | Gate |
| 1 | Filter (how far closed, 0..1) | 9 | Echo | 17 | Ring mod |
| 2 | Resonance | 10 | Dirty | 18 | Stutter |
| 3 | Volume | 11 | Crush | 19 | Tape stop |
| 4 | Pan | 12 | Chorus | 20 | Pump |
| 5 | Attack | 13 | Wobble | 21 | Tape warmth |
| 6 | Release | 14 | Glide time | 22 | Phaser |
| 7 | Start point | 15 | Repeat roll | 23 | Freeze |

Value ranges follow the XY setup screen (`Xy.kt`): pitch -24..24, volume and pan -1..1, release 0..2, start point
0..0.95, the rest 0..1. [Likely] lanes and locks carry values in these same units, since the engine feeds lane,
lock, hand and rest values into the same slot (`Engine.combine`).

## 7. The song lane: `"AB-B------------"`

One character per bar slot. `A` to `H` play that loop; `-` is a rest of 16 steps. The song's length is the
position of the last letter plus one; trailing rests do not count. Written with `max(16, song length)` characters.

Reading: up to 64 characters (`MAXSONG`). Any character that is not one of `ABCDEFGH` (lowercase included) is a
rest. The song is only read when `pats` is present; a file with only top-level `steps` has no song.

Playing: each slot plays its loop once for that loop's own `len` (a 32-step loop takes two bars of time), then the
next slot; after the last letter it starts again from the first slot. A rest slot is silent for 16 steps.

## 8. Nested objects

### 8.1 `edits`: the doctor's table, per sound key

Same fields and defaults as the site's `ED_DEF`, plus app-only fields. The writer always writes the first 14 fields,
then the rest only when not default.

| Field | Type | Default | Read range | Meaning |
| --- | --- | --- | --- | --- |
| `st` | number | 0 | 0..1 | Trim start, fraction of the sound. |
| `en` | number | 1 | 0..1 | Trim end. If `en <= st` both reset to 0 and 1. |
| `att` | number | 0 | 0..4 | Attack, seconds. |
| `rel` | number | 0 | 0..4 | Release, seconds. |
| `rev` | boolean | false | | Play reversed. |
| `loop` | boolean | false | | Loop the trim. |
| `mode` | string | `"one"` | `one` or `gate` | One-shot plays it all; gate stops when the note ends. Anything else is `one`. |
| `tune` | number | 0 | -48..48 | Semitones. |
| `fine` | number | 0 | -100..100 | Cents. |
| `vol` | number | 1 | 0..4 | Volume. |
| `pan` | number | 0 | -1..1 | Pan. |
| `tone` | number | 0 | -1..1 | Below 0 darker (low pass), above 0 thinner (high pass). |
| `norm` | boolean | false | | Normalize. |
| `choke` | number | 0 | 0..8, whole | Choke group, 0 = none. |
| `held` | string | `"loop"` | `loop`, `tail`, `once`, `sustain`, `beat` | App only. What a one-shot does while Hold keeps it. |
| `beatN` | number | 1 | 1, 2 or 4 | App only. With `held: "beat"`, restart every N beats. Anything else is 1. |
| `pitch` | string | `"auto"` | `auto`, `stretch`, `grain` | App only. How a pitch change plays. |
| `bake` | boolean | false | | App only. Keep baked pitch versions. Written only when true. |
| `fx` | object | | | App only. Written when any effect is on or a size/time/rate is not default. Fields below. |

`fx` fields: `rvb` 0..1 (0), `rvbSize` 0..1 (0.5), `echo` 0..1 (0), `echoT` 0..3 whole (1; picks 1/16, 1/8,
3/16, 1/4), `dirt` 0..1 (0), `crush` 0..1 (0), `chorus` 0..1 (0), `wob` 0..1 (0), `wobRate` 0..1 (0.3). Defaults in
brackets; all are clamped on read.

Every number: missing or not a number gives the default; NaN gives the low end of the range. Booleans must be real
JSON booleans.

### 8.2 `padOpts`: settings that belong to the pad, keyed `"0"` to `"64"`

| Field | Type | Default | Read range | Written | Meaning |
| --- | --- | --- | --- | --- | --- |
| `glide` | boolean | false | | Always | Glide between notes. |
| `glideT` | number | 0.12 | 0..1 | Always | Glide time, seconds. |
| `legato` | boolean | false | | Always | Glide only when notes overlap. |
| `route` | number | 0 | 0..4, whole | Always | 0 the mix, 1 to 4 an FX bus. |
| `xy` | string | `""` | | When set | The pad's XY setup (below). |
| `pack` | string | `""` | | When set | Strips packed on the pad, comma list from `keys`, `fx`, `lfo`, `xy`, in that order. Unknown names are ignored by the app. |
| `fx` | string | `""` | | When set | Where the FX faders rest: `"target=value;target=value"`, targets from 6.8, values 0..1 rounded to 3 decimals, sorted by target, zeros left out. |
| `ftype` | number | 1 | 1..3, whole | Always | Pad filter kind: 1 low pass, 2 high pass, 3 band pass. Its presence marks a file from build 106 or later (section 9). |
| `lfo` | string | | | When not default | The LFO, 9 comma fields (below). |

`lfo` code: `on,wave,rate,target,depth,type,cut,res,retrig`.

| Field | Default | Read range | Meaning |
| --- | --- | --- | --- |
| on | 0 | `1` is on, anything else off | LFO on. |
| wave | 0 | whole number | 0 sine, 1 triangle, 2 saw up, 3 saw down, 4 square, 5 random steps. |
| rate | 4.0 | 0.125..64 | Steps per full wave (32 = 2 bars, 16 = 1 bar, 0.5 = 1/32). |
| target | 1 | whole number | Control target from 6.8. |
| depth | 0.5 | 0..1 | How far it moves. |
| type | 0 | 0..3 | Old LFO filter kind. Only read to upgrade old files; always 0 after load. |
| cut | 0.55 | 0..1 | Old LFO filter cutoff. Upgrade only. |
| res | 0.25 | 0..1 | Old LFO filter resonance. Upgrade only. |
| retrig | 0 | `1` is on | Restart the wave on every hit. |

Default code: `0,0,4.0,1,0.5,0,0.55,0.25,0`. The writer leaves `lfo` out when the code equals it exactly. Reading is
fragile: fields are parsed in order and a bad field stops the parse, keeping the fields before it and defaults after.
Numbers print as Java doubles (`4.0`).

`xy` code: seven fields joined by `|`:

1. name (with `|` and `;` replaced by spaces)
2. `2` two-way (zero at bottom left) or `4` four-way (zero in the middle)
3. `s` springs back or `k` stays
4. `o` ring shown or `q` not
5. size 0, 1 or 2
6. `t` records steps or `f` not
7. directions, comma list, each `target:to` or `target:to:from` (`-1` target = nothing). Two-way uses the first two
   (X, Y); four-way uses right, up, left, down.

Example: `Sweep|2|s|q|1|t|1:1.0,2:1.0,-1:0.0,-1:0.0`. Numbers print as Kotlin floats (`1.0`). If the code does not
parse, the app treats the pad as having no XY setup. The `xy` string is kept as written, never cleaned on load.

A pad whose settings are all default is not written.

### 8.3 `synths`: synth patches, keyed `"synth:<n>"`

```
"synth:1": { "kind": "pluck", "name": "Nylon", "v": { "<param id>": number, ... }, "src": "<sound key>" }
```

- `kind`: `analog`, `fm`, `wave`, `grain`, `drum` or `pluck`. Unknown or missing is `analog`.
- `name`: the patch name. Missing is the kind's name (`Analog`, `FM`, `Wavetable`, `Granular`, `Drums`, `Pluck`).
- `v`: every parameter of that kind is written. On read, each known id that holds a number is clamped to its range
  (choice and int params are rounded); unknown ids are ignored; missing ids take their default.
- `src`: Granular only, the library key of the sound its grains come from. Written only when not empty.

Synth keys: the app picks the lowest `synth:<n>` (n from 1) not used in this mess. They are local to the mess, so two
messes can both have a different `synth:1`.

A synth is written only when its key is on a pad, including slot 64.

Parameters per kind (type: lin = straight line, log = knob is logarithmic, int = whole numbers, choice = index into
the list). Ranges and defaults are what `Synth.java` declares; the tables were printed from the code.

#### `analog` (Analog)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| mode | Voice: Mode | choice: 0 Poly, 1 Mono, 2 Acid | 0 | 2 | 0 |
| w1 | Oscillator 1: Wave (0 sine, 1 tri, 2 saw, 3 square, morphs between) | lin | 0 | 3 | 2 |
| pw1 | Oscillator 1: Width | lin | 0.05 | 0.95 | 0.5 |
| w2 | Oscillator 2: Wave (0 sine, 1 tri, 2 saw, 3 square, morphs between) | lin | 0 | 3 | 2 |
| semi2 | Oscillator 2: Semis | int | -24 | 24 | 0 |
| fine2 | Oscillator 2: Fine (cents) | lin | -50 | 50 | 7 |
| mix2 | Oscillator 2: Level | lin | 0 | 1 | 0.5 |
| sync | Oscillator 2: Sync | choice: 0 Off, 1 On | 0 | 1 | 0 |
| sub | Mixer: Sub | lin | 0 | 1 | 0 |
| noise | Mixer: Noise | lin | 0 | 1 | 0 |
| uni | Mixer: Unison | int | 1 | 5 | 1 |
| spread | Mixer: Spread | lin | 0 | 1 | 0.3 |
| ftype | Filter: Filter | choice: 0 Low 24, 1 Low 12, 2 Band, 3 High | 0 | 3 | 0 |
| cut | Filter: Cutoff (Hz) | log | 30 | 18000 | 2400 |
| res | Filter: Resonance | lin | 0 | 1 | 0.2 |
| fenv | Filter: Env amount | lin | -1 | 1 | 0.35 |
| track | Filter: Key track | lin | 0 | 1 | 0.5 |
| drive | Filter: Drive | lin | 0 | 1 | 0.1 |
| fa | Filter envelope: Attack (s) | log | 0.001 | 5 | 0.005 |
| fd | Filter envelope: Decay (s) | log | 0.005 | 5 | 0.4 |
| fs | Filter envelope: Sustain | lin | 0 | 1 | 0.2 |
| fr | Filter envelope: Release (s) | log | 0.005 | 8 | 0.3 |
| aa | Amp envelope: Attack (s) | log | 0.001 | 5 | 0.004 |
| ad | Amp envelope: Decay (s) | log | 0.005 | 5 | 0.5 |
| as | Amp envelope: Sustain | lin | 0 | 1 | 0.8 |
| ar | Amp envelope: Release (s) | log | 0.005 | 8 | 0.25 |
| lrate | LFO: Rate (Hz) | log | 0.05 | 20 | 4 |
| lshape | LFO: Shape | choice: 0 Sine, 1 Triangle, 2 Saw, 3 Square, 4 Random | 0 | 4 | 0 |
| lpitch | LFO: To pitch | lin | 0 | 1 | 0 |
| lcut | LFO: To filter | lin | 0 | 1 | 0 |
| lamp | LFO: To volume | lin | 0 | 1 | 0 |
| lpw | LFO: To width | lin | 0 | 1 | 0 |
| glide | Voice: Glide (s) | log | 0.001 | 1.5 | 0.001 |
| accent | Voice: Accent | lin | 0 | 1 | 0.5 |
| oct | Voice: Octave | int | -3 | 2 | 0 |
| level | Voice: Level | lin | 0 | 1 | 0.7 |

#### `fm` (FM)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| mode | Voice: Mode | choice: 0 Poly, 1 Mono | 0 | 1 | 0 |
| algo | Operators: Algorithm | choice: 0 Stack, 1 Branch, 2 Three into one, 3 Two stacks, 4 One drives three, 5 Stack + sine, 6 Pair + two sines, 7 Organ | 0 | 7 | 3 |
| bright | Operators: Brightness | lin | 0 | 2 | 1 |
| fb | Operators: Feedback | lin | 0 | 1 | 0 |
| det | Operators: Detune (cents) | lin | 0 | 20 | 0 |
| r1 | Operator 1: Ratio | choice: 0 0.5, 1 1, 2 1.41, 3 1.5, 4 2, 5 2.5, 6 3, 7 3.5, 8 4, 9 5, 10 6, 11 7, 12 8, 13 9, 14 11, 15 14 | 0 | 15 | 1 |
| l1 | Operator 1: Level | lin | 0 | 1 | 1 |
| a1 | Operator 1: Attack (s) | log | 0.001 | 5 | 0.002 |
| d1 | Operator 1: Decay (s) | log | 0.005 | 8 | 0.8 |
| s1 | Operator 1: Sustain | lin | 0 | 1 | 0.6 |
| rl1 | Operator 1: Release (s) | log | 0.005 | 8 | 0.3 |
| r2 | Operator 2: Ratio | choice: 0 0.5, 1 1, 2 1.41, 3 1.5, 4 2, 5 2.5, 6 3, 7 3.5, 8 4, 9 5, 10 6, 11 7, 12 8, 13 9, 14 11, 15 14 | 0 | 15 | 1 |
| l2 | Operator 2: Level | lin | 0 | 1 | 0.5 |
| a2 | Operator 2: Attack (s) | log | 0.001 | 5 | 0.002 |
| d2 | Operator 2: Decay (s) | log | 0.005 | 8 | 0.8 |
| s2 | Operator 2: Sustain | lin | 0 | 1 | 0.6 |
| rl2 | Operator 2: Release (s) | log | 0.005 | 8 | 0.3 |
| r3 | Operator 3: Ratio | choice: 0 0.5, 1 1, 2 1.41, 3 1.5, 4 2, 5 2.5, 6 3, 7 3.5, 8 4, 9 5, 10 6, 11 7, 12 8, 13 9, 14 11, 15 14 | 0 | 15 | 1 |
| l3 | Operator 3: Level | lin | 0 | 1 | 1 |
| a3 | Operator 3: Attack (s) | log | 0.001 | 5 | 0.002 |
| d3 | Operator 3: Decay (s) | log | 0.005 | 8 | 0.8 |
| s3 | Operator 3: Sustain | lin | 0 | 1 | 0.6 |
| rl3 | Operator 3: Release (s) | log | 0.005 | 8 | 0.3 |
| r4 | Operator 4: Ratio | choice: 0 0.5, 1 1, 2 1.41, 3 1.5, 4 2, 5 2.5, 6 3, 7 3.5, 8 4, 9 5, 10 6, 11 7, 12 8, 13 9, 14 11, 15 14 | 0 | 15 | 1 |
| l4 | Operator 4: Level | lin | 0 | 1 | 0.3 |
| a4 | Operator 4: Attack (s) | log | 0.001 | 5 | 0.002 |
| d4 | Operator 4: Decay (s) | log | 0.005 | 8 | 0.8 |
| s4 | Operator 4: Sustain | lin | 0 | 1 | 0.6 |
| rl4 | Operator 4: Release (s) | log | 0.005 | 8 | 0.3 |
| lrate | LFO: Rate (Hz) | log | 0.05 | 20 | 5 |
| lpitch | LFO: Vibrato | lin | 0 | 1 | 0 |
| lamp | LFO: Tremolo | lin | 0 | 1 | 0 |
| glide | Voice: Glide (s) | log | 0.001 | 1.5 | 0.001 |
| oct | Voice: Octave | int | -3 | 2 | 0 |
| level | Voice: Level | lin | 0 | 1 | 0.7 |

#### `wave` (Wavetable)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| mode | Voice: Mode | choice: 0 Poly, 1 Mono | 0 | 1 | 0 |
| table | Oscillator: Table | choice: 0 Basic, 1 Pulse, 2 Sweep, 3 Vowels, 4 Hollow, 5 Grit | 0 | 5 | 0 |
| pos | Oscillator: Position | lin | 0 | 1 | 0.3 |
| penv | Oscillator: Env to pos | lin | -1 | 1 | 0 |
| plfo | Oscillator: LFO to position | lin | 0 | 1 | 0 |
| warp | Oscillator: Warp | lin | 0 | 1 | 0 |
| uni | Oscillator: Unison | int | 1 | 5 | 1 |
| spread | Oscillator: Spread | lin | 0 | 1 | 0.3 |
| ftype | Filter: Filter | choice: 0 Low 24, 1 Low 12, 2 Band, 3 High | 0 | 3 | 0 |
| cut | Filter: Cutoff (Hz) | log | 30 | 18000 | 6000 |
| res | Filter: Resonance | lin | 0 | 1 | 0.1 |
| fenv | Filter: Env amount | lin | -1 | 1 | 0.2 |
| track | Filter: Key track | lin | 0 | 1 | 0.5 |
| drive | Filter: Drive | lin | 0 | 1 | 0 |
| fa | Envelope 2 (filter, position): Attack (s) | log | 0.001 | 5 | 0.005 |
| fd | Envelope 2 (filter, position): Decay (s) | log | 0.005 | 5 | 0.6 |
| fs | Envelope 2 (filter, position): Sustain | lin | 0 | 1 | 0.3 |
| fr | Envelope 2 (filter, position): Release (s) | log | 0.005 | 8 | 0.4 |
| aa | Amp envelope: Attack (s) | log | 0.001 | 5 | 0.005 |
| ad | Amp envelope: Decay (s) | log | 0.005 | 5 | 0.6 |
| as | Amp envelope: Sustain | lin | 0 | 1 | 0.8 |
| ar | Amp envelope: Release (s) | log | 0.005 | 8 | 0.4 |
| lrate | LFO: Rate (Hz) | log | 0.05 | 20 | 1 |
| lshape | LFO: Shape | choice: 0 Sine, 1 Triangle, 2 Saw, 3 Square, 4 Random | 0 | 4 | 0 |
| lpitch | LFO: To pitch | lin | 0 | 1 | 0 |
| lcut | LFO: To filter | lin | 0 | 1 | 0 |
| lamp | LFO: To volume | lin | 0 | 1 | 0 |
| glide | Voice: Glide (s) | log | 0.001 | 1.5 | 0.001 |
| oct | Voice: Octave | int | -3 | 2 | 0 |
| level | Voice: Level | lin | 0 | 1 | 0.7 |

#### `grain` (Granular)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| mode | Voice: Mode | choice: 0 Poly, 1 Mono | 0 | 1 | 0 |
| pos | Grains: Position | lin | 0 | 1 | 0.3 |
| spray | Grains: Spray | lin | 0 | 1 | 0.1 |
| size | Grains: Size (s) | log | 0.01 | 0.5 | 0.08 |
| dens | Grains: Density (Hz) | log | 2 | 120 | 30 |
| scan | Grains: Scan | lin | -1 | 1 | 0 |
| pspray | Grains: Pitch spray | lin | 0 | 12 | 0 |
| rev | Grains: Reverse | lin | 0 | 1 | 0 |
| gshape | Grains: Shape | choice: 0 Smooth, 1 Sharp | 0 | 1 | 0 |
| ftype | Filter: Filter | choice: 0 Low 24, 1 Low 12, 2 Band, 3 High | 0 | 3 | 1 |
| cut | Filter: Cutoff (Hz) | log | 30 | 18000 | 12000 |
| res | Filter: Resonance | lin | 0 | 1 | 0 |
| aa | Amp envelope: Attack (s) | log | 0.001 | 5 | 0.05 |
| ad | Amp envelope: Decay (s) | log | 0.005 | 5 | 0.5 |
| as | Amp envelope: Sustain | lin | 0 | 1 | 1 |
| ar | Amp envelope: Release (s) | log | 0.005 | 8 | 0.8 |
| lrate | LFO: Rate (Hz) | log | 0.05 | 20 | 0.3 |
| lpos | LFO: To position | lin | 0 | 1 | 0 |
| lpitch | LFO: To pitch | lin | 0 | 1 | 0 |
| glide | Voice: Glide (s) | log | 0.001 | 1.5 | 0.001 |
| oct | Voice: Octave | int | -3 | 2 | 0 |
| level | Voice: Level | lin | 0 | 1 | 0.8 |

#### `drum` (Drums)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| model | Drum: Model | choice: 0 Kick, 1 Snare, 2 Hat, 3 Open hat, 4 Clap, 5 Tom, 6 Cowbell, 7 Zap | 0 | 7 | 0 |
| tune | Drum: Tune | int | -24 | 24 | 0 |
| decay | Drum: Decay (s) | log | 0.02 | 3 | 0.5 |
| tone | Drum: Tone | lin | 0 | 1 | 0.5 |
| snap | Drum: Snap | lin | 0 | 1 | 0.4 |
| sweep | Drum: Sweep | lin | 0 | 1 | 0.5 |
| stime | Drum: Sweep time (s) | log | 0.005 | 0.5 | 0.04 |
| noise | Drum: Noise | lin | 0 | 1 | 0.5 |
| drive | Drum: Drive | lin | 0 | 1 | 0.2 |
| level | Drum: Level | lin | 0 | 1 | 0.8 |

#### `pluck` (Pluck)

| id | what | type | min | max | default |
| --- | --- | --- | --- | --- | --- |
| mode | Voice: Mode | choice: 0 Poly, 1 Mono | 0 | 1 | 0 |
| decay | String: Decay (s) | log | 0.1 | 12 | 2.5 |
| bright | String: Bright | lin | 0 | 1 | 0.7 |
| damp | String: Damp | lin | 0 | 1 | 0.3 |
| pick | String: Pick position | lin | 0 | 1 | 0.2 |
| body | String: Body | lin | 0 | 1 | 0.3 |
| mute | String: Let go (s) | log | 0.01 | 3 | 0.6 |
| drive | String: Drive | lin | 0 | 1 | 0 |
| lrate | LFO: Rate (Hz) | log | 0.05 | 20 | 5 |
| lpitch | LFO: Vibrato | lin | 0 | 1 | 0 |
| glide | Voice: Glide (s) | log | 0.001 | 1.5 | 0.001 |
| oct | Voice: Octave | int | -3 | 2 | 0 |
| level | Voice: Level | lin | 0 | 1 | 0.8 |

## 9. Older files and how they are upgraded

The reader never looks at `v` or `app`. It decides by what is in the file.

| What the file has | Where it comes from | What the reader does |
| --- | --- | --- |
| No `pats` (or an empty array), top-level `steps` | The oldest saves, one 16-step loop. [Guessing] this is what v1 was. | Top-level `steps` becomes loop A. Loops B to H are empty, `song` is ignored, everything else reads as usual. |
| `pats[i].notes` is a string, with `pats[i].inst` naming the sound | An older save with one roll instrument per loop. [Guessing] this is v2. | The string is read as that sound's notes (6.2). On the next save it is written as a `notes` object. If `inst` is missing or empty the notes are dropped. |
| `v: 3`, no `app`, no `padOpts`, `synths`, `motion`, `offs`, `acc`, `chance`, `locks`, `squash`, `level`, `mode`, `cur` | The site. | Every missing key takes its default. Keys like `a:<asset>` and `bn:<id>` usually miss (4.2). |
| `qb:` keys | Earlier site builds. | Resolved through `library.json` `aliases`; the key is kept as is. |
| `padOpts` entries with no `ftype` and an `lfo` whose type field is above 0 | Android before build 106. | If the LFO was on, its filter becomes the pad filter: `ftype` takes the LFO's type, the pad's `fx` gains `1=<1 - cut>` and `2=<res>` (rounded to 3 decimals). The LFO's type is then set to 0. If the LFO was off, only the type is cleared. A file that has `ftype` is never changed this way. |
| A `len` other than 16..128 in steps of 16 | Hand edits, or a future format. | 16. |

Android also copied every mess on the phone into `kept-before-106/` once, before the first save in the new form, so a
rolled-back app could still open them.

Rule for every platform (from `shared/README.md`): a change to the format bumps `v`, keeps loading every older
version, and adds a fixture.

## 10. What loading tolerates and what it rejects

Rejected (the app says "That does not look like a Mess Machine file."):

- Text that is not valid JSON, or valid JSON followed by more text. `Json.parse` throws.

Accepted, and probably should not be:

- Any valid JSON that is not an object, and `{}`. Both load as an empty mess (112 BPM, Chromatic, no pads). The site
  is stricter: it rejects a file whose `kit` is not an array. [Likely] the other platforms should reject a file with
  no `kit` array, and Android could adopt the same check.

Tolerated quietly:

- Any missing key (defaults from sections 3, 5 and 8).
- Wrong types: a number given as a string, a boolean given as `0`/`1`, and so on, all count as missing.
- Out-of-range numbers: clamped as listed. Fractions where whole numbers are expected: cut toward zero.
- Unknown keys at the top level and inside a loop object (`pats[i]`): kept exactly as they were and written back on
  every save (Android build 172 on). Android never reads or edits them; clearing a loop drops that loop's. Unknown keys
  deeper down (inside `edits`, `synths`, `padOpts` entries, or a per-sound row) are still ignored and not written back,
  so a platform that needs a new per-sound field must add it here first.
- A `v` above 3: the file loads as well as it can and the app says it was made by a newer version. It is saved back as
  `v: 3`, with the unknown keys carried as above.
- Unknown and repeated sound keys (4.2).
- Per-step strings that are too long (extra entries ignored), too short (missing entries are empty), or have bad
  entries (skipped).
- Duplicate JSON keys: the last value wins.

## 11. Golden fixtures for engine parity (proposal)

The format makes the three apps open the same mess. Golden fixtures would make them play it the same. The idea: the
Java engine is the reference. It reads a mess and a short script of actions, and writes down what it did. iOS and web
run the same inputs through their own engines and must produce the same record.

### 11.1 Layout

```
shared/fixtures/
  example.mess.json            the format example (this page)
  legacy-*.mess.json, ...      older files that must keep loading
  other-platform.mess.json     missing sounds and unknown keys that must survive a save
  RoundTrip.java               load and re-save check (exists)
  golden/
    README.md                  how to run and what "match" means
    <case>/
      mess.json                the mess
      script.json              what happens, in frames
      events.json              expected: every step and voice start (written by Java)
      fingerprint.json         expected: coarse audio numbers (written by Java)
```

Cases to start with, one idea each: `straight-16` (4 pads, no swing), `swing-50`, `two-bar-loop`, `song-with-rest`,
`notes-and-chords`, `offs-ticks`, `accents`, `chance-conditions` (101 to 104 only), `chance-dice`, `locks`,
`motion-lane`, `gate-and-trim` (edits), `choke`, `synth-<kind>` for each kind, `tempo-change` (a script event), and
`live-hits` (pad down and up from the script, with Rec on).

### 11.2 `script.json`

```
{ "rate": 48000, "block": 256, "frames": 192000,
  "events": [ { "at": 0, "do": "play" },
              { "at": 24000, "do": "down", "id": 1, "pad": 3, "pitches": [0], "vel": 0.9 },
              { "at": 30000, "do": "up", "id": 1 },
              { "at": 96000, "do": "set", "path": "bpm", "value": 140 },
              { "at": 180000, "do": "stop" } ] }
```

`at` is the frame where the command enters the engine; Java applies it at the start of the block that contains that
frame, so every engine must too (or the script sticks to block-aligned frames, which is simpler). `set` changes the
mess and rebuilds the snapshot, the way the UI does.

### 11.3 `events.json`

One line per event, in order, all times in frames from the start:

```
{ "t": "step", "frame": 0, "pat": 0, "step": 0, "song": 0 }
{ "t": "voice", "frame": 0, "pad": 0, "key": "samples/basement-kick.wav", "src": "step", "step": 0,
  "rate": 1.0, "gain": 1.0, "dur": 5513, "gate": false }
{ "t": "voice", "frame": 2297, "pad": 1, "key": "samples/wet-clap.wav", "src": "off", "tick": 92, ... }
```

`src` is `step`, `note`, `off`, `repeat`, `arp`, `live` or `click`. Match rules: integers exactly, `rate` and
`gain` to 1e-6, `dur` to 1 frame. This is the part that catches drag, swing, song order, chance and lock bugs, and it
is independent of how each platform makes sound.

### 11.4 `fingerprint.json`

Coarse numbers from the rendered stereo mix, loose enough that different resamplers and filters still pass:

- per 50 ms window: RMS in dB (left and right), peak, and the frame of the first sample above -40 dB after silence
  (onsets);
- for the whole render: total length, RMS, and energy in 4 bands (below 200 Hz, 200 Hz to 2 kHz, 2 to 8 kHz, above).

Match: onset frames within 64 frames, window RMS within 1.5 dB, band energy within 3 dB. [Guessing] the tolerances
need tuning on the first real iOS run.

### 11.5 What EngineTest (or a small generator next to it) would need

- A hook in `Engine` that records each step and each voice start: a `List` the engine appends to only when a test
  sets it, called from `fireStep` and `trigger`. With it off, nothing changes on the audio thread.
- A way to feed the script: the engine already takes commands through `cmds`; the generator renders block by block
  and adds each event's command when its block starts (like `Engine.bounce` does with `play`).
- A fake clock for live hits. `down` and `up` stamp `System.nanoTime()`, and Rec uses it to place a recorded hit
  inside the block. The generator must pass a time worked out from the script's frame instead, or recorded live hits
  will differ run to run.
- The fingerprint maths: windows, RMS, onsets, and a band split. A small FFT already exists (`Bake.fft`).
- A mode that writes the expected files (`EngineTest --golden shared/fixtures/golden`) and a mode that checks them,
  so the Java engine also fails when it drifts from its own goldens. Writing is done by hand when a change is meant
  to change the sound, in the same commit.

Things other engines must copy exactly for `events.json` to match:

- Step length in frames: `rate * 60 / max(40, bpm) / 4`, kept as a double and accumulated (`nextStepAt += spb`),
  then rounded per event. Odd steps are pushed late by `swing / 100 * spb * 0.5`. Off-grid hits are placed at
  `stepTime + round(tick * spb / 24)`, so swing moves them too.
- Order of events inside one step: pads in pad order; per pad its lock changes, then the step hit, then off-grid hits,
  then notes.
- Gains: step hits use the accent gain (6.5), off-grid hits 0.85, notes 0.78.
- Step hit length is the gap to the next hit in that row (`gap * spb`); a note's length is `l * spb`.
- The chance dice: `java.util.Random` seeded with 11, `nextInt(100) < chance`, rolled only for chance values 0..99, in
  the order above, and not reset by Play. iOS and web need a copy of Java's 48-bit generator (a few lines). The
  `chance-dice` case catches a wrong copy; the other cases avoid percent chance so a dice bug does not hide
  everything else.

## 12. Checking a reader: `RoundTrip.java`

`shared/fixtures/RoundTrip.java` loads every fixture with the Android code and saves it again. From the repo root:

```
javac -d build/rt app/src/main/java/com/tideclock/messmachine/engine/*.java shared/fixtures/RoundTrip.java
java -cp build/rt RoundTrip shared/fixtures app/src/main/assets/sounds/
```

The second argument is optional. With it, sound keys are checked against the real `library.json`, as the app does.
It checks:

- `example.mess.json` loads with nothing missing, saves back to the same file (ignoring `at` and whitespace), and a
  second load and save changes nothing;
- the example means what this page says (tempo, song, steps, notes, accents, chance, offs, locks, lanes, edits,
  synth, pad options);
- `legacy-v1.mess.json`, `legacy-v2-inst.mess.json`, `site-v3.mess.json` and `android-pre106.mess.json` upgrade as
  section 9 says;
- `other-platform.mess.json` keeps its missing sounds on their pads with their steps, notes and edits, and writes its
  unknown top-level and per-loop keys back unchanged (4.2, section 10);
- `{}`, out-of-range values and broken JSON behave as section 10 says.

The example is in the Android writer's exact form: all 65 kit entries, every loop with a `steps` row for every loaded
pad. Another platform's test can load it, save it, load its own output and compare the two loaded messes field by
field.

## Appendix: the example file

This is `shared/fixtures/example.mess.json` in full. Four pads: a kick, a clap, a pluck synth on pad 2 and a Qbldy
loop on pad 3. Loop A has notes, a filter lane on pad 1, two off-grid clap flams, an accent and a ghost on the kick,
a 50% clap, and two kick filter locks. Loop B is two bars. The song plays A, B, a rest, B.

```json
{
  "name": "Shared example",
  "bpm": 96,
  "v": 3,
  "kit": ["samples/basement-kick.wav", "samples/wet-clap.wav", "synth:1", "loop:ametrine-1", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""],
  "sel": 0,
  "steps": {
    "samples/basement-kick.wav": "1000001010010000",
    "samples/wet-clap.wav": "0000100000001000",
    "synth:1": "0000000000000000",
    "loop:ametrine-1": "1000000000000000"
  },
  "pats": [
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "1000001010010000",
        "samples/wet-clap.wav": "0000100000001000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "1000000000000000"
      },
      "notes": {
        "synth:1": "0.0.4,8.-5.4"
      },
      "motion": {
        "1:1": "0.2,,,,0.4,,,,0.6,,,,0.8,,,"
      },
      "offs": {
        "samples/wet-clap.wav": "92,284"
      },
      "acc": {
        "samples/basement-kick.wav": "a.....g........."
      },
      "chance": {
        "samples/wet-clap.wav": ",,,,,,,,,,,,50,,,"
      },
      "locks": {
        "samples/basement-kick.wav@1": "0.7,,,,,,,,0.3,,,,,,,"
      }
    },
    {
      "len": 32,
      "steps": {
        "samples/basement-kick.wav": "10001000100010001000100010001000",
        "samples/wet-clap.wav": "00001000000010000000100000001000",
        "synth:1": "00000000000000000000000000000000",
        "loop:ametrine-1": "10000000000000001000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    },
    {
      "len": 16,
      "steps": {
        "samples/basement-kick.wav": "0000000000000000",
        "samples/wet-clap.wav": "0000000000000000",
        "synth:1": "0000000000000000",
        "loop:ametrine-1": "0000000000000000"
      },
      "notes": {}
    }
  ],
  "song": "AB-B------------",
  "swing": 20,
  "squash": 0.6,
  "mutes": [],
  "solos": [],
  "scale": "Minor",
  "root": 9,
  "fold": false,
  "edits": {
    "samples/wet-clap.wav": {
      "st": 0,
      "en": 1,
      "att": 0,
      "rel": 0.08,
      "rev": false,
      "loop": false,
      "mode": "gate",
      "tune": -2,
      "fine": 0,
      "vol": 0.7,
      "pan": 0,
      "tone": 0,
      "norm": false,
      "choke": 0
    },
    "loop:ametrine-1": {
      "st": 0.25,
      "en": 0.75,
      "att": 0,
      "rel": 0,
      "rev": false,
      "loop": true,
      "mode": "one",
      "tune": 0,
      "fine": 0,
      "vol": 0.9,
      "pan": -0.2,
      "tone": 0,
      "norm": false,
      "choke": 1,
      "held": "beat",
      "beatN": 2,
      "pitch": "stretch",
      "fx": {
        "rvb": 0.3,
        "rvbSize": 0.5,
        "echo": 0,
        "echoT": 1,
        "dirt": 0,
        "crush": 0,
        "chorus": 0,
        "wob": 0,
        "wobRate": 0.3
      }
    }
  },
  "synths": {
    "synth:1": {
      "kind": "pluck",
      "name": "Nylon",
      "v": {
        "mode": 0,
        "decay": 2.5,
        "bright": 0.55,
        "damp": 0.45,
        "pick": 0.25,
        "body": 0.45,
        "mute": 0.6,
        "drive": 0,
        "lrate": 5,
        "lpitch": 0,
        "glide": 0.001,
        "oct": 0,
        "level": 0.8
      }
    }
  },
  "padOpts": {
    "1": {
      "glide": false,
      "glideT": 0.12,
      "legato": false,
      "route": 1,
      "xy": "Sweep|2|s|q|1|t|1:1.0,2:1.0,-1:0.0,-1:0.0",
      "fx": "8=0.25",
      "ftype": 1
    },
    "2": {
      "glide": true,
      "glideT": 0.08,
      "legato": true,
      "route": 0,
      "pack": "keys,lfo",
      "ftype": 2,
      "lfo": "1,0,4.0,1,0.5,0,0.55,0.25,0"
    }
  },
  "at": "2026-11-23T20:00:00Z",
  "app": "android",
  "mode": "song",
  "cur": 0
}
```
