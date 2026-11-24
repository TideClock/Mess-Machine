# Mess Machine assets

Where the Android app keeps everything the iOS app and the web version need to copy, as at build 170. Paths are from
the repo root. Copy files as they are; do not redraw or re-encode them.

## Icons

| What | Where | Notes |
|---|---|---|
| 102 key icons | `app/src/main/res/drawable-nodpi/ic_<name>.png` | 40 x 40 px PNG, RGBA: 32 x 32 px pixel art with an ink outline and a white glow, on a clear background. The tile colour behind is drawn by the app (tokens.json `tileFill`), so a look can recolour it. |
| Icon source | `tools/icons/icons.py` (art), `lib.py`, `export.py`, `sheet.py`, `mock.py` | `python3 export.py <out dir>` writes every PNG and `icon-names.json`. |
| Name list | `tools/icons/icon-names.json` | Icon name to `[file slug, category]`. The app's own map (name to drawable and tile group) is `Icons.all` in `app/src/main/java/com/tideclock/messmachine/ui/Icons.kt`; groups and short-name aliases are in tokens.json `iconGroups`. |
| Jar art | `app/src/main/res/drawable-nodpi/jar_<id>.png` | 9 jars (bodily, bw, goo, heap, machines, mine, qbldy, thumps, tones), about 420 x 357 px, shown in the Sounds tab's By jar row. |
| App icon | `app/src/main/res/mipmap-*/ic_launcher*.png`, `mipmap-anydpi-v26/ic_launcher*.xml` | Adaptive icon. |

### How the icon art is drawn (InlayIcon, ui/Widgets.kt)

- Always at a whole-number multiple of 40 px, never in between, so every art pixel is the same size.
- The multiple k is the nearest whole number to (wanted size in px / 40), at least 1. If 40 x k would be more than
  1.22 times the wanted size, k drops by one (the art has a clear margin, so up to a fifth bigger is fine). Example: a
  26 dp icon on a 2.6x screen is 68 px wanted, so k = 2 and it draws at 80 px.
- Nearest-neighbour sampling only (Android `FilterQuality.None`). iOS: `magnificationFilter = .nearest` or
  `interpolation = .none`. Web: `image-rendering: pixelated`, and size the element in device pixels
  (`40 * k / devicePixelRatio` CSS px).
- Under the icon, the same image tinted black at alpha .7 is drawn 1 dp right and 1.5 dp down (an engraved shadow).
- The icon itself goes through the enamel colour matrix (tokens.json `enamel`): a little less saturated and warmer
  when unlit, a touch more saturated when lit.
- `PixIcon` (Pro looks) draws the icon on its tile colour with corner radius size / 8, alpha .55 when disabled.

## Fonts

All in `app/src/main/res/font/`, all OFL, from Google Fonts. Which UI uses which is in tokens.json `typography`.

| File | Family |
|---|---|
| `archivo_medium.ttf`, `archivo_extrabold.ttf`, `archivo_black.ttf` | Archivo 500, 800, 900 (also the Block word plates) |
| `bungee.ttf` | Bungee |
| `plexmono_400.ttf`, `plexmono_700.ttf` | IBM Plex Mono |
| `rubik_distressed.ttf` | Rubik Distressed (Worn: panel key words) |
| `black_ops_one.ttf` | Black Ops One (Stencil: stamped words) |
| `share_tech_mono.ttf` | Share Tech Mono (Lcd: amber readouts) |

## Built-in sounds

| What | Where | Notes |
|---|---|---|
| Library index | `app/src/main/assets/sounds/library.json` | `cabs` (8 jars: id, name, colour), `sounds` (313 entries: `k` key, `t` title, `c` jar, `f` file, `ty` type, `q` feel tags, plus key and tempo for loops), `aliases` (390 old Heap and Qbldy chop keys mapped to hits from the same song, so old messes still load). |
| One-shots | `app/src/main/assets/sounds/*.flac` | 43 files. Keys keep the site's names (`samples/basement-kick.wav`) even though the files are FLAC. |
| Loops | `app/src/main/assets/sounds/loops/*.flac` | 90 one-bar loops, cut sample exact with a 5 ms crossfade; `mkey` in library.json when the key is known. |
| Hits | `app/src/main/assets/sounds/hits/*.flac` | 180 hits, typed `chop`. |
| Decoder and loader | `core/src/commonMain/kotlin/com/tideclock/messmachine/engine/Library.kt`, `engine/Flac.kt` | Sounds decode on first use. FLAC is used because it is sample exact (the engine test checks the kick). iOS and current browsers decode FLAC, but check each web target. |
| Sound packs | GitHub release `packs` on `hubbub0/Mess-Machine` (`packs.json` plus `<id>.zip` with `pack.json` and `clips/*.flac`) | Made by `tools/make_pack.py`; installed by `app/src/main/java/com/tideclock/messmachine/Packs.kt` (refuses zip entries outside the pack folder). |

## Text

| What | Where | Notes |
|---|---|---|
| Start guide | `app/src/main/assets/guide.json` | `title`, `thisBuild`, `watch`, `limits` (lists of lines). Shown once per new build on the Notes sheet, unless turned off. Rewritten before every build Tyler installs. |
| Welcome card | `StartScreen` in `app/src/main/java/com/tideclock/messmachine/ui/App.kt` | Three lines and two buttons, shown on a new install. |
| FAQ (What the FAQ) | `app/src/main/java/com/tideclock/messmachine/Faq.kt` | One `Line(pattern, name, text, lesson?)` per tag pattern; `*` matches one or more characters; first match wins. The big test fails if a tappable control has no line. Two stale patterns are listed at the end of PARITY.md's Sheets section. |
| Speed guide | `SPEED_STOPS` in `app/src/main/java/com/tideclock/messmachine/ui/FaqOverlay.kt` | Tag to one line, about five seconds a stop. |
| Lessons | `app/src/main/java/com/tideclock/messmachine/Tutorials.kt` | 29 `Tutorial(id, group, title, blurb, steps, setup)`; each `TutStep` has text, a target tag and a check on app state. Run on a practice mess; the real mess waits in `before-lesson.json`. Drawn by `ui/Tutor.kt`. |
| Toast and status lines | `say(...)` calls in `app/src/main/java/com/tideclock/messmachine/AppState.kt` | Plain sentences; copy them word for word. |
| Note kinds | `NOTE_CATS` in `app/src/main/java/com/tideclock/messmachine/Notes.kt` | Bug, Tuning, Feature, Question, Discuss, Wishlist; GitHub labels `from-app` plus the kind. |

## Other

| What | Where |
|---|---|
| Design check exceptions (controls per screen, tabs) | `design/exceptions.txt` |
| Native audio | `app/src/main/cpp/mmaudio.c`, `CMakeLists.txt` |
| Big test and screenshots | `app/src/test/java/com/tideclock/messmachine/BigTest.kt` (also AuditTest, BarPlanTest, ChordShowTest, MonkeyTest); screenshots on the `test-screens` release, emulator shots on `phone-screens` |
