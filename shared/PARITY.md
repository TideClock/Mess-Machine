# Mess Machine parity list

Every feature and control of the Android app as it is at **build 170** (2026-11-23), so the iOS app and the web
version can match it. Read from the code, not from memory. Tags are the Compose test tags (`Modifier.tagged(...)` or
`tag = "..."`); use the same strings as iOS `accessibilityIdentifier` and web `data-testid`, so the FAQ text, the
lessons and the tests carry over. `<n>` is a number, `<pad>` a pad index (0 to 63, 64 is the scratch synth), `<d>`
a pad's place in the bank on screen (0 to 15), `<t>` an engine target number, `<id>` a name.

Paths: `ui/` is `app/src/main/java/com/tideclock/messmachine/ui/`; files without `ui/` sit one folder up.
`Faq.kt` has one plain line per tag pattern: it is the help text, and the big test fails on a tappable control
without one. Port it as is. Mark iOS and Web cells `done`, `part` (say what is missing) or `skip` (say why).

Gesture words: **tap** (press and lift), **hold** (press and stay), **hold-lift** (still for 450 ms, then lift:
`tapOrHold` in ui/Widgets.kt), **quick swipe** (44 dp within 300 ms; direction is the axis that is at least twice
the other: App.kt `watchTouches`), **drag**.

## Layouts and looks (read first)

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Layout | Where controls live. `new` (default): bottom dock plus perform row. `side`: perform row as the left side dock. `classic`: build 104 (Menu instead of Edit, FX key on the dock, side dock, pad menu instead of Edit tray). | `layout-new`, `layout-side`, `layout-classic` | ui/FilesScreen.kt, AppState.kt `layout` | todo | done (shared screens, CMP web) |
| Look | Button skin: `panel` (default for everyone: bakelite keys in worn metal, lamps, pixel icons), `classic` (site stickers, words), `dark` and `light` (Pro: icon tiles on dotted rails). Separate from Skin. | `look-panel`, `look-classic`, `look-dark`, `look-light`, `look-chips` | ui/Widgets.kt `Btn`, ui/Icons.kt `BarBtn` `PanelKey` `rail` | todo | done (shared screens, CMP web) |
| Skin | Colour set: Classic, Specimen, Stage, Felt and Paper, Rust. Sets grit, sticker shadow, pad face (jar, glass, band, felt), light status bar. See tokens.json. | `skin-<id>`, `skin-chips` | ui/Widgets.kt `Skins`, `C` | todo | done (shared screens, CMP web) |
| Words under icons | Small word under each bar key in non-classic looks. | `look-words` | ui/FilesScreen.kt | todo | done (shared screens, CMP web) |
| Word plates | Panel look: words on dark plates with a brass rule, block letters (else stamped stencil words). | `look-wordplate` | ui/Widgets.kt `PlateWord` | todo | done (shared screens, CMP web) |
| Tabs: slot or keys | Panel look: tabs in one milled slot with the current tab raised (default), or as keys. | `look-tabslot` | ui/App.kt `TabSlot` | todo | done (shared screens, CMP web) |
| Large keys | Every key 1.25x. | `look-large` | ui/Widgets.kt `UiLook.big` | todo | done (shared screens, CMP web) |
| View size | 70 to 120 percent; scales the whole UI density. Floors stay in real dp (see tokens.json `rdp`). | `zoom-<pct>`, `zoom-chips` | ui/App.kt (Density times uiScale), ui/BarPlan.kt | todo | done (shared screens, CMP web) |
| Play layout | Play tab shows the pads or My Desk. | `layout-pads`, `layout-desk` | ui/FilesScreen.kt | todo | done (shared screens, CMP web) |

## Top bar

One row on the rail, every page. Panel look: every key gets the same weight (readouts 1.5 and 1.7). The bottom rule
flashes on each beat (brass, lighter on the one).

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Play / Pause | Starts or pauses the loop (or the song in Song mode). With Rec on, a start from the top counts in first. Brass key (Primary). | `play` | ui/App.kt `Transport`, AppState.kt `togglePlay` | todo | done (shared screens, CMP web) |
| Stop | Stops, back to start. Pressed while stopped: hush (every held note, effect and filter let go). | `stop` | ui/App.kt, AppState.kt `stop` | todo | done (shared screens, CMP web) |
| Rec | Arms recording (pink lamp). Glows red; flares on each beat while playing. | `rec` | ui/App.kt `RecBtn` `recGlow` | todo | done (shared screens, CMP web) |
| Undo | Last change to the mess back (80 steps). Disabled when nothing to undo. | `undo` | ui/App.kt, AppState.kt `undo` | todo | done (shared screens, CMP web) |
| Tempo readout | Shows BPM, and in non-classic looks the lag in ms plus clicks this minute (red). Tap: Tempo sheet. In the MIDI map, tap aims a knob at tempo. Quick swipe up: Tempo sheet. | `bpm`, `lag`, `clicks` | ui/App.kt | todo | done (shared screens, CMP web) |
| Loop readout | Where the music is (`A 1.2.3`, `SONG n`, `COUNT n`, `→ B` while a pick waits). Tap: loop strip. Drag sideways 28 dp: previous or next loop (waits for the bar line like a pick). | `loop-open`, `position` | ui/App.kt, AppState.kt `stepLoop` | todo | done (shared screens, CMP web) |
| What the FAQ | Toggle. While on, a tap anywhere explains the control under it instead of pressing it. Its icon is the FAQ guy. | `help-toggle` | ui/App.kt, ui/FaqOverlay.kt | todo | done (shared screens, CMP web) |
| ☰ menu | Dropdown: Messes, Settings, Notes for Claude (with waiting count), Learn, Mouth control, MIDI map, Redo. Red when a crash waits. | `menu`, `topmenu`, `messes`, `settings`, `notes-open`, `learn`, `mouth`, `midimap-open`, `redo` | ui/App.kt `TopMenu` | todo | done (shared screens, CMP web) |
| Beat glow | Soft wash behind the page on each beat (pink on the one). Off, Soft, Full. | `pulse`, `set-pulse-<0..2>`, `glow-chips` | ui/App.kt `PulseBackdrop` | todo | done (shared screens, CMP web) |

Banners under the top bar: recording (`rec-banner`, tap stops the mic take), update ready (`update-bar`,
`update-go`, `update-later`), phone working hard (`cpu-banner`, `cpu-bake`, `cpu-latency`, `cpu-no`; once a session
after 20 s over 70 percent load with live grain).

## Tabs (tab slot)

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Tab bar | Play, Synth, Seq (Steps and Roll), Perform, Sounds. Song has no tab (it is in the loop strip). Play opens Pads or My Desk per Play layout. Disabled while locked. | `tab-Pads`, `tab-Synth`, `tab-Steps`, `tab-Perform`, `tab-Sounds`, `tab-slot` | ui/App.kt `Tabs` `TabSlot`, AppState.kt `BAR_TABS` | todo | done (shared screens, CMP web) |
| Bug button | Bottom left of every page. Tap: start a quick voice note; tap again (or Keep): keep it on the phone. Red while listening or when a crash waits (then it opens Notes). | `notes` | ui/App.kt, AppState.kt `bugTap` | todo | part: the web build has no speech to text; type the note (shared screens) |
| Quick note bar | While listening: what was heard, Keep, Drop. | `quick-note`, `quick-save`, `quick-drop` | ui/App.kt `QuickNoteBar` | todo | part: no speech to text on web |
| Page fade | Pages cross fade in 150 ms. | `screen-<Tab>` | ui/App.kt | todo | done (shared screens, CMP web) |

## Play page (Pads)

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Pad grid | 4 x 4 pads of the picked bank, square, 10 dp gaps, at most 560 real dp wide. Multi-touch. | `pad-<d>` | ui/PadsScreen.kt `Pad` | todo | done (shared screens, CMP web) |
| Pad: play | Sounds on touch down (no waiting). Hold: keeps playing (gate, sustain, synth). Picks the pad. Pad buzz if on. Pressed: scale .93 and a pink ring. | `pad-<d>` | ui/PadsScreen.kt, AppState.kt `padDown` `padUp` | todo | done (shared screens, CMP web) |
| Pad: swipes | Quick swipe off a pad does its HotKeys action (defaults: up Edit tray, down Padiano, left Sample, right FX strip under the row). Same swipe again closes it. Once the finger is 24 dp one way within 300 ms the hit fades out (6 ms) and a recorded hit is taken back. Each finger tracked on its own. | `pad-<d>`, `set-padflick`, `swipe-up`, `swipe-down`, `swipe-left`, `swipe-right`, `hotkeys` | ui/App.kt `watchTouches`, AppState.kt `padSwipeAt` `cancelPadHit` | todo | done (shared screens, CMP web) |
| Pad: circle | Draw a circle on a loaded pad (40 dp path): its XY opens as a sphere over the screen until lift; the finger moves the XY. | `sphere` | ui/PadsScreen.kt, ui/XyUi.kt `CircleDetector` `SphereOverlay` | todo | done (shared screens, CMP web) |
| Empty pad | Dashed outline split by a slash. Above the slash: record from the mic onto it (tap start, tap stop, or Hold to record). On or below: pick a sound (Picker sheet). Acts on a still tap's lift. | `pad-<d>`, `pad-pick-<d>` | ui/PadsScreen.kt, AppState.kt `emptyActsOnLift` | todo | part: mic recording works on a plain web page; Hubbub's game frame and claude.ai artifacts block the mic |
| Pad marks | Top: number. Bottom: name (sized to fit). ✂ when edited, `B<n>` when routed to a bus, `H` when held by Hold, dots down the right edge (yellow LFO, teal lane in this loop, red resting FX fader), dashed zipper when something is packed, yellow bottom bar when its FannyPack is open, dark with an oxblood slash when muted, oxblood rim in Mute mode, dashed yellow when it is being moved. | none | ui/PadsScreen.kt `Pad` | todo | done (shared screens, CMP web) |
| Pad face per skin | jar (gradient, hard shadow, pink shadow when picked), glass (dark with a glow), band (black with a colour band), felt (flat with a stitched edge). | none | ui/PadsScreen.kt | todo | done (shared screens, CMP web) |
| Banks | BANK 1 to 4. Tap picks. Hold 500 ms: mute or unmute the whole bank. | `bank-<0..3>` | ui/PadsScreen.kt `BankButtons`, AppState.kt `toggleBankMute` | todo | done (shared screens, CMP web) |
| Click | Metronome on every beat while playing. On by default, remembered. Nov 24: on a screen 560 dp or wider, Click and Count-in sit in the top bar on every page (after the loop readout) instead of the Play page's bank row; Tempo has them too (`tempo-click`, `tempo-countin`). | `loop-click`, `tempo-click`, `tempo-countin` | ui/PadsScreen.kt, AppState.kt `switchClick` | todo | done (shared screens, CMP web) |
| Count | Count-in with Rec on: off, 1 (default), 2 bars. Tap cycles. | `loop-countin` | ui/PadsScreen.kt, AppState.kt `cycleCountIn` | todo | done (shared screens, CMP web) |
| 16 Levels | One pad's sound on all 16 pads, softest on 1, hardest on 16. Recorded hits keep ghost, normal or accent. Banner: tap to stop. | `menu-levels`, `levels-banner` | ui/PadsScreen.kt, ui/EditTray.kt, AppState.kt `switchLevels` | todo | done (shared screens, CMP web) |
| Move a pad | After Move, the next pad tapped takes the sound (a loaded pad trades places). | `menu-move` | AppState.kt `armSwap` | todo | done (shared screens, CMP web) |
| Keys and Step modes | Retired (forced off in `PlayViews`). Do not port. | `mode-keys`, `mode-step`, `steppad-<d>`, `stepbar-<b>` | ui/PadsScreen.kt | skip | skip |
| My Desk | Your own layout of pieces on a 12 column grid (rows of 24 dp): transport, pads, row, Padiano, pad filter, Hold, effects, one effect, filter pad, Repeat, loops, scale, beat light, pad XY. Arrange: drag, resize from the corner, pin, remove. Several desks: new, copy, name, delete. | `console`, `console-edit`, `console-add`, `console-reset`, `desk-<k>`, `desk-new`, `desk-copy`, `desk-delete`, `desk-name`, `desk-grid`, `mod-<i>`, `mod-drag-<i>`, `mod-size-<i>`, `mod-pin-<i>`, `mod-x-<i>`, `mod-gear-<i>`, `mod-pad-<i>`, `cplay`, `crepeat`, `cslot-<k>`, `cxyp-<pad>`, `cfx-<id>`, `cfx1-<id>` | ui/ConsoleScreen.kt, AppState.kt `Module` `CONSOLE_TYPES` | todo | done (shared screens, CMP web) |

## Bottom dock and More

Shown under Play, My Desk and Synth. Keys share one width (44 real dp, widened to the widest word, at most 1.5x),
down to 38 real dp on a narrow screen; only then do keys go behind More, highest priority number first. Teal rule on
top (brass in panel). Quick swipe up on Pack, Hold, Chords, Repeat or the key readout opens its settings whatever
the flick switch says (`DOCK_FLICK`).

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Edit (Menu in classic) | Priority 0 (never hidden). Tap: armed (next tap on anything opens its settings; banner "Tap anything for its settings"). Tap twice: settings of what you touched last (the picked pad when nothing). Tap while a tray or menu is open: closes it. Classic opens the pad menu. | `open-padmenu`, `menu-layer` | ui/PadsScreen.kt `BottomDock`, ui/App.kt `MenuLayer`, AppState.kt `menuTap` `settingsKind` | todo | done (shared screens, CMP web) |
| Pack | Priority 6. Opens or zips the picked pad's FannyPack under its row (not on Synth). Swipe up: pick its strips. | `pack-toggle` | ui/PadsScreen.kt, AppState.kt `togglePack` | todo | done (shared screens, CMP web) |
| Hold | Priority 3. On or off. Word shows Hold, Last, or held/count (like 2/3). Swipe up: Hold bar. | `hold-toggle` | ui/PadsScreen.kt, AppState.kt `switchHold` | todo | done (shared screens, CMP web) |
| Chords | Priority 2. One tap between chords and single notes; remembers the chord type. | `chords-toggle` | ui/PadsScreen.kt, AppState.kt `toggleChords` | todo | done (shared screens, CMP web) |
| Repeat | Priority 1 (new and side layouts). Note repeat on or off. Swipe up (or Edit, then Repeat): rate, style, pattern tray. | `repeat-toggle`, `tray-repeat`, `tray-repeat-rate`, `tray-repeat-pattern` | ui/PadsScreen.kt, ui/App.kt `RepeatControls` | todo | done (shared screens, CMP web) |
| FX (classic only) | Priority 1, Play tab only. Shows the perform effects panel under the pads. | `fx-toggle` | ui/PadsScreen.kt | todo | done (shared screens, CMP web) |
| Mute | Priority 4. Hold it and tap pads to mute or unmute; let go to play. Quick tap (under 300 ms): stays on until tapped again. Oxblood lamp. | `mute-key` | ui/PadsScreen.kt `PressKey`, AppState.kt `switchMuteMode` | todo | done (shared screens, CMP web) |
| Drop | Priority 5. Nov 24: its icon is DROP in block letters on the grey function tile. Held: everything goes quiet but the pads under a finger (and held notes). Let go: all back. Never saved. | `drop-key` | ui/PadsScreen.kt, AppState.kt `drop` | todo | done (shared screens, CMP web) |
| Key readout | Takes the rest of the row: "D minor · triads ▴" on an amber LCD (panel). Tap: key panel above the dock. | `keysbar-open` | ui/PadsScreen.kt | todo | done (shared screens, CMP web) |
| More | Appears only when keys do not fit. Dropdown of the hidden keys; a key there closes the menu on lift. | `dock-more`, `dock-more-menu` | ui/PadsScreen.kt, ui/BarPlan.kt | todo | done (shared screens, CMP web) |
| Key panel | Root, scale, chord type selects; with chords on: Play chord, Show chord, Smooth moves / From the root; Hold bar; readout of the last chord. Screw closes. | `keys-panel`, `keys-close`, `pick-root`, `pick-scale`, `pick-chord`, `chord-play`, `chord-show`, `chord-smooth` | ui/PadsScreen.kt `KeysPanel` | todo | done (shared screens, CMP web) |

## Settings tray and Edit tray

The tray sits above the bottom dock (100 dp up when the dock shows, 52 when not), framed plate with a brass rule, at
most 380 dp tall (Edit: 430 to 470), scrolls. Screw closes; Back closes. Kinds: `edit:<pad>`, `fx:<pad>`,
`lfo:<pad>`, `glide:<pad>`, `padiano:<pad>`, `pack:<pad>`, `hold`, `repeat`, `wheel`, `dockfilter`.

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Tray frame | Title (like `FX · PAD 3`), More (fx kind: the sound's Sample sheet), screw. Hold kind is one short bar with no title. | `tray`, `tray-done`, `tray-more`, `tray-*` | ui/App.kt `SettingsTray` | todo | done (shared screens, CMP web) |
| Edit tray | Edit, then a loaded pad (or quick swipe up). Nov 24: as tall as its drawer (max 470 dp), drawer keys shorter (22 dp icon), FX vials 92 to 128 dp. Title `EDIT · PAD n · NAME`. Five drawer keys; open drawer remembered. Panel look: drawer lamp in the pad colour, icon over word. | `edit-tabs`, `edit-tab-sound`, `edit-tab-fx`, `edit-tab-keys`, `edit-tab-mod`, `edit-tab-more`, `edit-<tab>` | ui/EditTray.kt | todo | done (shared screens, CMP web) |
| Sound drawer | Hear it, Sample (or Synth for a synth pad), Swap sound, Record onto it / Stop recording, Star, Keep in Workbench, Share this sound. Scratch synth: only Hear and Synth. | `menu-hear`, `menu-doctor`, `menu-synth`, `menu-swap`, `menu-record`, `menu-star`, `menu-keep`, `menu-share` | ui/EditTray.kt | todo | done (shared screens, CMP web) |
| FX drawer | The FX vials (panel) or faders plus the FX tray (other looks). | see FX vials | ui/EditTray.kt | todo | done (shared screens, CMP web) |
| Keys drawer | Padiano under this row / hide, 16 Levels, Glide (on, Always or Legato only, time). | `menu-piano`, `menu-levels`, `glide-<pad>`, `glide-always-<pad>`, `glide-legato-<pad>` | ui/EditTray.kt, ui/PerformPanels.kt `GlideTray` | todo | done (shared screens, CMP web) |
| Mod drawer | XY square setup, LFO strip and its tray, Record a lane for (one key per FX target: opens its Auto lane over the vials). | `menu-xy`, `edit-lane-<t>` | ui/EditTray.kt | todo | done (shared screens, CMP web) |
| More drawer | Mute, Solo, Route (mix, bus 1 to 4; tap cycles), Move to another pad, FannyPack strips, Clear from loop X, Clear pad. | `menu-mute-<pad>`, `menu-solo-<pad>`, `menu-route`, `menu-move`, `packtray-<id>`, `menu-clearloop`, `menu-clear` | ui/EditTray.kt | todo | done (shared screens, CMP web) |
| Pad menu (classic) | Dropdown in groups PLAY, SOUND, CLEAR with the same actions; empty pad: Record onto this pad, Auto sample, Pick a sound. Nov 24: Auto sample (`menu-autosample`, also in the Edit tray's Sound drawer): the mic listens on that pad, records the next sound it hears, trims it and stops by itself after a third of a second of quiet. Also from hold-lift on a sound name in Steps or Roll. | `padmenu`, `menu-*`, `menu-pick` | ui/PadsScreen.kt `PadMenu` | todo | done (shared screens, CMP web) |
| Wheel tray | Range ±2/7/12, Bends the picked pad / every pad, Stay or Spring. | `tray-wheel-range`, `tray-wheel-what`, `tray-wheel-stay` | ui/App.kt | todo | done (shared screens, CMP web) |
| Dock filter tray | Filters the mix / pad n, Stay or Spring. | `tray-dock-target`, `tray-dock-stay` | ui/Dock.kt `DockFilterTray` | todo | done (shared screens, CMP web) |

## FannyPack strips

Per pad, under its row, one open pack per row. Holds only the strips you pack (`PACK_STRIPS`: keys, fx, lfo, xy, in
that order). An empty pack shows the strip picker. Saved per pad in the mess (`PadOpts.pack`).

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| FannyPack | The strips for one pad, stacked. | `pack-<pad>`, `packtray-<id>` | ui/FannyPack.kt `FannyPack` `PackTray` | todo | done (shared screens, CMP web) |
| Padiano header | Swatch, pad number and name, Oct− Oct+ (squares or chord boxes), Glide, Synth (synth pads), XY toggle, ⚙ settings, ✕ close (not inside a pack). | `piano-<pad>`, `piano-down-<pad>`, `piano-up-<pad>`, `glide-<pad>`, `piano-synth-<pad>`, `piano-xy-<pad>`, `piano-set-<pad>`, `piano-close-<pad>` | ui/PerformPanels.kt `PianoKeyboard` | todo | done (shared screens, CMP web) |
| Padiano: Squares | Default. One row of squares, N semitones (7 to 36, default 13); light white-key, dark black-key notes; root tinted; scale lit. Multi-finger, slide to glide between notes. Circle on a key: its sphere. Rows 1 to 3, each an octave up. | `piano-keys-<pad>`, `piano-keys-<pad>-r<o>` | ui/PerformPanels.kt `PadianoSquares` `playNotes` | todo | done (shared screens, CMP web) |
| Padiano: Keyboard | Piano keys, cream and bakelite in panel. Oct−, ◀, ▶, Oct+ to scroll. Heights by size S 96, M 132, L 176, XL 236, Full 300 dp. | `pkeys-octdown-<pad>`, `pkeys-octup-<pad>` | ui/PerformPanels.kt `PadianoKeys` | todo | done (shared screens, CMP web) |
| Chord boxes | With a chord set: 12 named boxes, slot n always the same chord. Several fingers, several chords; sliding changes chord. | `chord-slots-<pad>` | ui/PerformPanels.kt `ChordSlots` | todo | done (shared screens, CMP web) |
| Padiano settings | Squares / Keyboard, In key only, notes − +, size S M L XL Full, rows 1 2 3. | `pset-squares`, `pset-keys`, `pset-inkey`, `pset-n-down`, `pset-n-up`, `pset-size-<0..4>`, `pset-rows-<1..3>` | ui/PerformPanels.kt `PadianoSettings` | todo | done (shared screens, CMP web) |
| FX vials (panel look) | Header: Load (Wash, Grit, Tape, Space, Dry, yours), Save, Auto (lanes over the vials), Bypass (all out, back exactly). 11 vials: Verb, Echo, Dirt, Crush, Chor, Pump, Tape, Phase, Freeze, Cut, Res. Drag on a vial to fill (empty is off). Lamp under it: tap in/out, hold-lift for its Auto lane. Icon, then a name plate: tap for that effect's settings card. Under a row on a phone: two rows of 6 and 5; wide screens one row. Vial height widthDp/3.6, 112 to 190 dp. | `fxstrip-<pad>`, `fxpreset-load`, `fxpreset-save`, `fx-auto-<pad>`, `fxstrip-off-<pad>`, `fx-<t>-<pad>`, `fxin-<t>-<pad>`, `fxname-<t>-<pad>` | ui/FannyPack.kt `VialStrip` `Vial` | todo | done (shared screens, CMP web) |
| FX settings card | One effect on this pad: how much, its own extra (reverb space, echo time 1/16 1/8 3/16 1/4), Auto lane, The sound's own FX (Sample sheet FX tab). | `fx-settings`, `fx-settings-close`, `fx-echo-<0..3>`, `fx-settings-lane`, `fx-settings-sound` | ui/FannyPack.kt `FxSettingsCard` | todo | done (shared screens, CMP web) |
| FX faders (other looks) | Faders ▾ (pick which show; default all but Tape, Phase, Freeze), Off, upright faders per FX plus Cut and Res. Drag. With Rec on while playing, moves record. | `fxstrip-pick-<pad>`, `fxstrip-pickmenu`, `fxshow-<name>`, `fxstrip-off-<pad>`, `fx-<t>-<pad>`, `fx-cut-<pad>`, `fx-res-<pad>` | ui/FannyPack.kt `FxStrip` `Fader` | todo | done (shared screens, CMP web) |
| FX tray | Faders shown, and Auto per FX (opens its lane). | `fxshow-<name>`, `fxauto-<t>` | ui/FannyPack.kt `FxTray` | todo | done (shared screens, CMP web) |
| LFO strip | On/Off, wave and target button (opens LFO tray), rate strip (drag, snaps at 2 bars, 1 bar, 1/2, 1/4, 1/8, 1/16, 1/32), Straight / Triplet / Dotted slider, Depth; aimed at the filter: Cutoff (Hz) and Res. | `lfostrip-<pad>`, `lfo-on-<pad>`, `lfo-more-<pad>`, `lfo-rate-<pad>`, `lfo-straight-<pad>`, `lfo-trip-<pad>`, `lfo-dot-<pad>`, `lfofeel-chips` | ui/FannyPack.kt `LfoStrip` `RateStrip` | todo | done (shared screens, CMP web) |
| LFO tray | Wave slider (Sine, Tri, Saw ↑, Saw ↓, Square, Random), Wobble preset, pad filter type slider Lo Hi Band, Target select (Filter, Resonance, Volume, Pan, Pitch, Reverb, Echo, Dirty, Crush, Chorus, Pump, Tape, Phaser), With loop / Each hit. | `lfo-wave-<w>-<pad>`, `lfowave-chips`, `lfo-wobble-<pad>`, `lfo-type-<1..3>-<pad>`, `lfotype-chips`, `lfo-target-<pad>`, `lfo-retrig-<pad>` | ui/FannyPack.kt `LfoTray` | todo | done (shared screens, CMP web) |
| XY strip | The pad's own XY square or ring. Two-way: zero at bottom left. Four-way: zero in the middle with a dead zone. Springs back on lift unless it stays. Sizes 130, 190, 260 dp. | `xyp-<pad>` | ui/XyUi.kt `PadXy` | todo | done (shared screens, CMP web) |
| Pad filter strip | Desk piece: slide left closes cutoff, up adds resonance; lift opens again unless Stay. | `pfilt-<pad>`, `pfilt-stay-<pad>`, `pfilt-open-<pad>` | ui/PerformPanels.kt `PadFilterStrip` | todo | done (shared screens, CMP web) |

## Perform row and side dock

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Perform row (new layout) | Plate above the bottom dock on every page. Handle: tap flips, swipe up 12 dp opens, down closes; remembered. Keys share one row, each at most 104 dp wide (Nov 24); pitch wheel and filter lie on their sides under them. ⋯ picks what lives in it (`DOCK_TYPES` minus hold, rec, beat). Default items: lock, hold, pitch, filter, repeat, then throws added once. | `performrow`, `perform-handle`, `perform-edit`, `perform-pick-<id>` | ui/Dock.kt `PerformRow` `PerformBody` | todo | done (shared screens, CMP web) |
| Lock | Tap locks: only playing works, yellow edge round the screen, no tabs, menus or scrolling; Back says how to unlock. Hold 1 s (ring fills) to unlock. Can pin the screen. | `dock-lock`, `lock-edge` | ui/Dock.kt `LockButton`, AppState.kt `lock` | todo | part: no screen pinning in a browser |
| Repeat key | Note repeat on/off. Swipe up: repeat tray. | `dock-repeat` | ui/Dock.kt | todo | done (shared screens, CMP web) |
| Toss (throws) | Shows the master throws panel under the pads. | `dock-throws` | ui/Dock.kt | todo | done (shared screens, CMP web) |
| Mouth key | Mouth control on/off (reads Wub while it hears you). Swipe up: Mouth sheet. | `dock-mouth` | ui/Dock.kt | todo | done (shared screens, CMP web) |
| XY key | Cycles the picked pad's XY setup. Swipe up: XY sheet. | `dock-xy` | ui/Dock.kt | todo | done (shared screens, CMP web) |
| Desk key | Next desk layout. | `dock-desk` | ui/Dock.kt | todo | done (shared screens, CMP web) |
| Stutter, Echo keys | Single throws as hold keys. Nov 24: Stutter holds one rate. On a vial, the height picks it in four equal zones, locked to the grid (bottom up: 1/4, 1/8, 1/16, 1/32 notes, shown on the vial while held); on a key, sliding sideways does (left slow); a plain press is 1/8. Engine `stutterLength(steps)`. | `dock-stutter`, `dock-echo` | ui/Dock.kt, ui/XyUi.kt `FxButton` | todo | done (shared screens, CMP web) |
| Pitch wheel | Sideways (row) or upright 26 x 130 (side dock). Drag from the middle to bend; Spring returns, Stay settles on the nearest note in key. Range ±2/7/12, PAD or ALL. Records with Rec on. MIDI pitch bend drives it. | `dock-pitch`, `dock-pitch-range`, `dock-pitch-what`, `dock-pitch-stay` | ui/Dock.kt `SidePitch` `PitchWheel` | todo | done (shared screens, CMP web) |
| Dock filter | Cut (teal) and Res (pink) sliders; MIX or P<n> target; Stay or Spring. | `dock-cut`, `dock-res`, `dock-target`, `dock-stay` | ui/Dock.kt `SideFilter` `DockFilter` | todo | done (shared screens, CMP web) |
| Side dock (side and classic layouts) | 62 dp column on the left; folds to a 14 dp edge (tap or swipe right 24 dp to open). Same pieces plus H with count word, ● Rec, beat light, RPT with rate and pattern words, MTH with SET word. ⋯ picks; "Lock pins the screen"; ◀ folds. | `dock`, `dock-open`, `dock-fold`, `dock-edit`, `dock-pick-<id>`, `dock-hold`, `dock-hold-n`, `dock-rec`, `beatlight`, `dock-rate`, `dock-pattern`, `dock-mouth-set` | ui/Dock.kt `Dock` `DockItem` `DockWord` | todo | done (shared screens, CMP web) |

## Hold, chords and scales, note repeat

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Hold | The first N notes you play keep sounding (N 1 to 8, or Last: the newest takes over; default 3). Pressing a held note again lets it go. Latches grid pads (ids 1000 + pad, survive bank switches), Padiano keys and synth notes. Held notes feed Repeat and the arp. | `hold-toggle`, `hold-count` | AppState.kt `switchHold` `latched` | todo | done (shared screens, CMP web) |
| Hold bar | − count + and Let go, in one row (tray, key panel). | `hold-down`, `hold-up`, `hold-release`, `hold-count` | ui/PadsScreen.kt `HoldBar` | todo | done (shared screens, CMP web) |
| Root, scale | 12 roots, scales from `Theory.SCALES`. Keys, Padiano, Roll and In key only follow them. | `pick-root`, `pick-scale` | ui/PadsScreen.kt, engine/Theory.kt | todo | done (shared screens, CMP web) |
| Chord type | Off, Triads, 7ths, 1-4-5 (sus), Power, Octaves, or a chord set (12 chords, all in key: in-scale keys play their degree chord, the five outside keys the colour chords). Sets: Plain Major, Plain Minor, Sunday Pop, Dreamy Sus, Gospel Drip, Lo-fi Jazz, Basement Minor, Neo Soul, Cinematic, Haunted, Trap Dread, Power Stabs. A set in another scale moves the scale. | `pick-chord` | ui/PadsScreen.kt `chordOptions`, engine/Theory.kt | todo | done (shared screens, CMP web) |
| Play or Show chord | Play: whole chord sounds. Show: one note sounds, the rest light. | `chord-play`, `chord-show` | ui/PadsScreen.kt | todo | done (shared screens, CMP web) |
| Smooth moves | On (default): each chord picks the inversion nearest the last, within a fifth. Off: root position. Roll always writes root position. | `chord-smooth` | engine/Theory.kt `smooth` | todo | done (shared screens, CMP web) |
| Note repeat | Held pads or keys repeat in time. Rate 1/4, 1/8, 1/16, 1/32 times straight, triplet (2/3) or dotted (1.5). Style Cut (default), Ring, Flow. Pattern: Repeat, Up, Down, Up-down, Random (arp), or rests: Every one, 2 on 1 off, On off, 3 on 1 off, 1 on 2 off, Gallop, Tresillo, Clave, Mine (tap steps to flip, 2 to 16 long). Counts from the top of the loop while playing. | `repeat-toggle`, `rate-r4`, `rate-r2`, `rate-r1`, `rate-r.5`, `feel-straight`, `feel-trip`, `feel-dot`, `rate-scut`, `rate-sring`, `rate-sflow`, `rate-a<rep|up|down|updown|rand>`, `pat-<code>`, `pat-mine`, `pattern-strip`, `pat-shorter`, `pat-longer` | ui/Dock.kt `RepeatMenu` `RepeatPatternPicker`, ui/App.kt `RepeatControls` | todo | done (shared screens, CMP web) |

## Mute and drop

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Mute mode | Pads become mute switches (see Mute key). | `mute-key` | AppState.kt `switchMuteMode` `togglePadMute` | todo | done (shared screens, CMP web) |
| Bank mute | Hold a bank number. | `bank-<b>` | AppState.kt `toggleBankMute` | todo | done (shared screens, CMP web) |
| Drop | See Drop key. `Project.dropKeep`, never saved. | `drop-key` | AppState.kt `drop` | todo | done (shared screens, CMP web) |
| Mute, solo per sound | From the pad's More drawer or menu; Steps names show M and S. | `menu-mute-<pad>`, `menu-solo-<pad>` | AppState.kt `toggleMute` `toggleSolo` | todo | done (shared screens, CMP web) |
| Mute as a swipe | HotKeys action `mute`. | `swipe-<dir>` | AppState.kt `padSwipeAt` | todo | done (shared screens, CMP web) |

## Loop strip (Loop side, Song side, Auto)

Opens under the top bar on any page (loop readout tap, or quick swipe up on it). At most half the screen tall,
scrolls. Plate with a brass rule. Screw closes.

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Strip | Loop / Song slider, 190 dp wide (Song reads "Song on" in Song mode), screw. Nov 24: closes when the page changes and on a tap (not a drag) anywhere on the page under it; the tap still does its own job. | `loopstrip`, `loopside-chips`, `loop-tab-loop`, `loop-song`, `loop-close` | ui/App.kt `LoopStrip` | todo | done (shared screens, CMP web) |
| Loops A to H | Pad-sized keys, 54 dp tall. Current is yellow; waiting pink; used shows a colour dot; bars count in the corner; pink border where it is playing. Tap: pick (while playing, waits for the bar line when Wait bar). Hold-lift: loop menu (Play loop, Copy to the next empty loop, Clear loop, Clear its auto). | `loop-<0..7>`, `slotmenu-pick`, `slotmenu-copy`, `slotmenu-clear`, `slotmenu-moves` | ui/App.kt `LoopPad` | todo | done (shared screens, CMP web) |
| Wait bar / Now | When a pick lands while playing. Default Wait bar. | `loop-when` | ui/App.kt, AppState.kt `loopAtBar` | todo | done (shared screens, CMP web) |
| Bars | Readout, − (drop the last bar), + (add an empty bar, up to 8), 2× (double, up to 8). − + clear accents, chances, locks and off-grid hits past the end; 2× copies them. | `loop-bars`, `loop-minus`, `loop-plus`, `loop-double` | ui/App.kt `LoopSide`, AppState.kt `addBar` `dropBar` `doubleLoop` | todo | done (shared screens, CMP web) |
| Bar pick | With 2+ bars: which bar Steps and Roll show. | `page-<n>`, `page2` | ui/App.kt | todo | done (shared screens, CMP web) |
| Loop tools | Nov 24: one wrapping bar of tight keys with Wait bar, the bars readout, − + 2× (one line on a wide screen). Copy to (select), Clear loop (danger, toast Undo), Auto (lanes), Clear auto (when there is motion), Bottle it (loop becomes a sound on a free pad). | `loop-clear`, `moves-open`, `clear-moves`, `bottle` | ui/App.kt, AppState.kt `bottleIt` | todo | done (shared screens, CMP web) |
| Auto lanes | Lane select, New lane (pad and target), lane canvas (drag draws, Erase mode erases), Draw, Erase, Smooth, Clear lane, shapes Hold, Ramp ↑, Ramp ↓, Triangle, Sine, Saw, Square; span Beat, Half bar, Bar; strength; Stamp the shape. Each change one undo. Also drawn in place over the vials. | `moves`, `moves-lane`, `moves-newpad`, `moves-newtarget`, `moves-lane-canvas`, `moves-draw`, `moves-erase`, `moves-smooth`, `moves-clear`, `moves-shape-<0..6>`, `moves-shape-chips`, `moves-span-<4|8|16>`, `moves-span-chips`, `moves-stamp`, `moves-close` | ui/MovesPanel.kt | todo | done (shared screens, CMP web) |
| Song side | Play loop / Play song, length and time readout, Paint with A to H or Rest (Nov 24: block-letter icons A to H, a bed for Rest), up to 64 bars: tap a bar to paint, hold-lift for bar menu (Clear this bar, Put a bar in here, Take this bar out). Rough it in, Share the song (WAV), Clear the song. Folds when the page changes. | `song-body`, `song-play-loop`, `song-play-song`, `bar-<i>`, `barmenu-clear`, `barmenu-insert`, `barmenu-delete`, `rough`, `share-song` | ui/SongScreen.kt `SongBody` | todo | done (shared screens, CMP web) |

## Synth page

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Scratch synth | Opens on a synth on pad 64 (in no bank, saved, never empty). Nov 24: at 560 dp and wider the To pad / Over keys sit in the top row beside Scratch and the preset; narrower, on their own row. To pad n (first empty pad, one tap), To pad n / Over n then Replace n? (second tap) for the picked pad. A fresh copy stays. | `synth-new-empty`, `synth-new-here` | ui/SynthScreen.kt, AppState.kt `scratchToPad` | todo | done (shared screens, CMP web) |
| Synth pad tile | Tap plays its note. Swipes follow HotKeys (up Edit, right FX tray, down folds the keys away); same swipe closes. | `synth-tile` | ui/SynthScreen.kt `SynthPadTile` | todo | done (shared screens, CMP web) |
| Which synth, preset | Select Scratch or any pad's synth; preset select; Pads (its Padiano on the pad grid). | `synth-pad`, `synth-preset`, `nosynth-preset`, `synth-to-pads` | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |
| Simple / Full | Slider. Simple: the knobs that matter; Full: every knob in groups. | `synthview-simple`, `synthview-full`, `synthview-chips` | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |
| Mode | Slider Poly / Mono / Acid (kinds that have it). | `synthmode-<i>`, `synthmode-chips` | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |
| Kind | Slider Analog, FM, Wave, Grain, Drum, Pluck (scratch has no Grain). Switching loads that kind's first preset. | `synthkind-<id>`, `scratchkind-<id>`, `synthkind-chips` | ui/SynthScreen.kt, engine/Synth.kt | todo | done (shared screens, CMP web) |
| Model | Drum and Pluck models as chips. | `synthmodel-<i>`, `synthmodel-chips` | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |
| Grains of | Granular source: a sound on one of the mess's pads. | `grain-src` | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |
| Knobs | Four to a row. Drag up or right (whole range in about 200 dp), double tap: default, long press: type a value. MIDI map: touch to give it a controller knob. | `knob-<paramId>`, `knobs-simple-chips`, `knobs-<group>-chips` | ui/SynthScreen.kt `Knob` `KnobRows` | todo | done (shared screens, CMP web) |
| Keys | The same Padiano as the pads, plus the bottom dock. | `piano-keys-64` (scratch) | ui/SynthScreen.kt | todo | done (shared screens, CMP web) |

## Seq page: Steps

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Steps / Roll / Mixer switch | Slider at the top of Seq. | `seq-switch`, `seqview-chips`, `seq-steps`, `seq-roll`, `seq-mixer` | ui/PerformScreen.kt `SeqSwitch` | todo | done (shared screens, CMP web) |
| Mixer | Nov 24. Seq's third view. Bank 1 to 4 keys; Bank n level slider (0 to 200%, moves every pad in the bank keeping their balance, one undo on release); a fader per loaded pad in the bank (its sound's Volume, the Sample sheet's Shape Volume, 0 to 200%), number and name under it. | `mixer-bank-<b>`, `mixer-bank`, `mixer-pads`, `mixer-<pad>` | ui/PerformScreen.kt `MixerScreen` | todo | done (shared screens, CMP web) |
| Bank | Which bank's sounds are listed. | `steps-bank-<b>` | ui/StepsScreen.kt | todo | done (shared screens, CMP web) |
| Random drums, Clear notes | Fill the loop with a random beat; clear every pitch and roll note. | `random`, `clear-all-notes` | ui/StepsScreen.kt | todo | done (shared screens, CMP web) |
| Sound name cell | 84 x 30 dp, colour bar, number, M or S, two-line name. Tap: row tools and pitch strip. Hold-lift: pad menu. | `steps-name-<pad>` | ui/StepsScreen.kt | todo | done (shared screens, CMP web) |
| Step row | 16 cells, 30 dp tall. Tap a step: off to on; tap a lit step: normal, accent, ghost, off. Drag sideways: paint the way the first cell went. Hold still: step sheet. Vertical drag scrolls. Ghost fills the lower half; accent has a cream ring; off-grid hits are thin marks. Playhead yellow. | `steps-<pad>` | ui/StepsScreen.kt `StepRow` | todo | done (shared screens, CMP web) |
| Step sheet | Chance Always, 75, 50, 25 percent; 1st of 2, 2nd of 2, 1st of 4, 4th of 4; this step only locks (Filter shut 25/50/75/90, Reverb 30/60/100, Echo 30/60/100, Off); strength Quiet, Normal, Loud. | `stepsheet`, `stepsheet-done`, `chance-<v>`, `lock-<t>-<pct>`, `lock-<t>-off`, `strength-<0..2>` | ui/StepsScreen.kt `StepSheet` | todo | done (shared screens, CMP web) |
| Row tools | Every 2, 4, 8; Repeat 4, 8, bar; nudge ◀ ▶; Copy, Paste; Clear; Spread n hits (− +). One undo each. | `rowtools-<pad>`, `rowtools-chips`, `row-every2`, `row-every4`, `row-every8`, `row-rep4`, `row-rep8`, `row-repbar`, `row-left`, `row-right`, `row-copy`, `row-paste`, `row-clear`, `row-spread-down`, `row-spread-up`, `row-spread`, `rowtools-close` | ui/StepsScreen.kt `RowTools` | todo | done (shared screens, CMP web) |
| Pitch strip | Pick a step, then a note: that step plays at that pitch. Plain takes it off. ◀ ▶ octave, ✕ closes. | `pitch-strip-<pad>`, `pitch-keys-<pad>`, `pitch-plain` | ui/StepsScreen.kt `StepPitchStrip` | todo | done (shared screens, CMP web) |

## Seq page: Roll

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Sound name | Tap: hear it. Hold-lift: pad menu. | `roll-name` | ui/RollScreen.kt | todo | done (shared screens, CMP web) |
| Header | Pad select, ✂ (Sample sheet), root, scale, chord type, Fold to scale / Unfold, Oct▲ Oct▼ (two octaves on screen, C1 to C6), Clear this sound's notes. | `pick-root`, `pick-scale`, `pick-chord`, `roll-octup`, `roll-octdown`, `roll-clear` | ui/RollScreen.kt | todo | done (shared screens, CMP web) |
| Note length | 1 step, 2 steps, 1 beat, Half a bar, Whole bar. | `notelen-chips` | ui/RollScreen.kt | todo | done (shared screens, CMP web) |
| Grid | Rows coloured by pitch class (`pcColor`). Tap a square: drop a note (a chord with chords on), tap again: pull it. Tap the key column: hear the note. Range note shows notes above or below. | `roll`, `roll-range` | ui/RollScreen.kt | todo | done (shared screens, CMP web) |

## Perform page

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Master throws | Brass master plate. Nine throws as vials (panel) or hold buttons (other looks): Stutter, Echo throw, Crush, Tape stop, Gate, Dirty, Ring mod, Oct down, Reverse. Hold; where the finger lands is how much, slide to change. Name opens settings: Amount, While held / Stays on / Slider, Mix / Bus 1 to 4 / Pad, gate length 1/16 1/8 1/4. | `master-throws`, `fx-<id>`, `fxedit-<id>`, `fx-editor`, `fx-editor-done`, `fxb-<id>-<held|stays|slider>`, `fxt-<id>-mix`, `fxt-<id>-bus<1..4>`, `fxt-<id>-pad` | ui/PerformPanels.kt `FxPanel` `MasterThrows`, ui/XyUi.kt `ThrowVial` `FxButton` `FxEditor` | todo | done (shared screens, CMP web) |
| Mud filter | Low pass / High pass and the XY filter pad for the mix. | `xy` | ui/PerformPanels.kt `XyPad` | todo | done (shared screens, CMP web) |
| Picked pad | Its XY square and its FX strip. | `perform-pad`, `perform-xy` | ui/PerformScreen.kt | todo | done (shared screens, CMP web) |

## Sheets

All sheets: dim backdrop, paper page 18 dp from the top with a 4 dp pink line, Bungee title, screw (`done`) closes,
Back closes. Slide up 1/12 and fade in 150 ms.

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Welcome | New install: three things to try, Make a mess, Take a lesson. | `start-go`, `start-learn` | ui/App.kt `StartScreen` | todo | done (shared screens, CMP web) |
| Start guide | Once per new build: guide.json (title, this build, watch, limits) on the Notes sheet; Shows on each new build / Hidden. | none | ui/NotesScreen.kt, assets/guide.json | todo | done (shares Android's guide.json) |
| Settings | Nov 24 layout: Build and Check for updates / Update on one header line; groups LOOK (View size, Skin, Buttons, its switches, Layout, Play tab shows), PLAYING (switches: Keep playing when I leave, Pad buzz, Record exactly where I hit, Auto trim new recordings, Auto-pitch new sounds, Flick up off a button), SOUND AND SPEED (switches: Smooth screen, Steady buffer, Fast sound path AAudio; Beat glow), HOTKEYS. Two columns at 560 dp and wider. Every on/off is a one-line switch (whole line taps). Hold to record is gone (tap start, tap stop always). | `update-check`, `update-go`, `set-autotrim`, `set-autopitch`, `set-bgplay`, `set-buzz`, `set-recexact`, `set-flick`, `flick-chips`, `set-smooth`, `set-buffer`, `set-aaudio`, `set-pulse-<v>`, `set-padflick`, `swipe-<dir>`, `hotkeys` | ui/FilesScreen.kt `SettingsScreen` | todo | part: no app updates (a page is always the newest); no background play |
| Tempo + feel | Nov 24: a drop-down card under the top bar (max 480 dp wide, tap outside or the screw closes), not a whole sheet. − BPM +, Tap tempo (up to 5 taps), Tempo slider 60 to 180 (follows as it moves, one undo on release), Swing 0 to 100, master bus Squash and Level, As it was, Click, Count-in, Hear steps on tap. | `master-bus`, `master-reset`, `tempo-click`, `tempo-countin`, `done` | ui/FilesScreen.kt `TempoScreen` | todo | done (shared screens, CMP web) |
| Sample (Doctor) | Hear it, Loop the trim, waveform with drag trim and zoom. Tabs Play, Trim, Shape, FX, Tools. Play: Re-pitch (Nov 26: one clear note moves to C, a melody or loop moves into the mess's key by the fewest semitones keeping its length, drift taken out; Again and Undo; a line says what it heard and how far it moved; core `AutoPitch`, stored as edits `ap` and `apKind` on top of Tune and Fine), pitch Auto / Stretch / Grain, Bake it, when held (Loop it, Hold the tail, Play once, Sustain, On the beat with Every beat, 2 beats, 1 bar). Trim: Zoom to trim, Whole sound, Snap to zero. Shape: Reverse, Loop, Gate / One shot, Normalize, Volume, Tune, Fine, Pan, Tone, Attack, Release, choke 0 to 6. FX: Reverb (amount, space), Echo (amount, time), Dirt, Crush, Chorus, Wobble. Nov 24: Chop to pads also opens the Play tab (one copy, shown in Play and Tools). Tools: Re-pitch to fit (1 beat to 4 bars), Chop to pads (Equal 2 4 8 16, Auto by hits, Tap the wave, Lazy), Save as a new sound, Share as WAV, Toss from Workbench, Reset everything. | `dtab-<play|trim|shape|fx|tools>`, `dtab-chips`, `repitch`, `unpitch`, `pitch-<auto|stretch|grain>`, `pitch-chips`, `bake-it`, `held-<v>`, `held-chips`, `beat-<1|2|4>`, `beat-chips`, `choke-chips`, `fit-chips`, `chop-chips`, `slice-chips` | ui/DoctorScreen.kt | todo | done (shared screens, CMP web) |
| Sound picker | Sounds browser for one pad: Put on pad n. | `put-<key>` | ui/SoundsScreen.kt (pickFor) | todo | done (shared screens, CMP web) |
| Messes | This mess: name, Save / Save changes, Save as new, New mess (tap twice). Pour it out: loop once, loop x4, the song (WAV share). For a DAW: stems loop/song, MIDI loop/song. Site and phone: Open a mess file, Share this mess. Lab logbook: saved messes with a little picture, Open, Toss (tap twice). | `daw-chips`, `stems-loop`, `stems-song`, `midi-loop`, `midi-song`, `mess-pic-<id>` | ui/FilesScreen.kt `FilesScreen` `MessPicture` | todo | done (shared screens, CMP web) |
| Sounds tab | By type (All plus types, Feel tags), By jar (jar art row), Starred, Packs. Search. Import sounds, Sample the mic (into Your Workbench). List rows: tap hear, hold-lift for menu; Share, Toss (Workbench), Put on pad. | `mode-type`, `mode-jar`, `mode-fav`, `open-packs`, `type-all`, `type-<t>`, `type-chips`, `feel-<q>`, `mood-chips`, `jar-<id>`, `jar-chips`, `search`, `import`, `mic`, `sound-list`, `row-<key>`, `share-<key>`, `put-<key>` | ui/SoundsScreen.kt | todo | part: Sample the mic blocked in Hubbub and artifacts; Packs shows none on web yet |
| Packs | Check for packs, each pack card with Install / Update / Remove. Installs as its own jar. | `packs-refresh`, `pack-<id>`, `pack-install-<id>`, `pack-remove-<id>` | ui/PacksScreen.kt, Packs.kt | todo | todo: the web build offers no packs yet |
| XY setup | Presets, Two-way / Four-way, Springs back / Stays put, Ring / Square, size S M L, Pitch scale steps / smooth, what each direction moves (select), Save as mine, a try-it pad. | `xy-editor`, `xy-preset-<name>`, `xy-two`, `xy-four`, `xy-spring`, `xy-shape`, `xy-steps`, `xy-dir-<i>`, `xy-save`, `xy-try` | ui/XyUi.kt `XyEditor` | todo | done (shared screens, CMP web) |
| Mouth control | Listen, vowel moves (target select), loudness moves (target select), Stay put / Spring back, Sensitivity, a live meter. Records as XY moves with Rec on. | `mouth-toggle`, `mouth-vowel-to`, `mouth-level-to`, `mouth-stay`, `mouth-letgo`, `mouth-meter`, `mouth-quiet-chips` | ui/MouthScreen.kt, MouthMic.kt, engine/MouthTracker.kt | todo | done (shared screens, CMP web) |
| Latency (Audio check) | Live numbers, a pad to tap, Run the speaker test (five clicks timed with the mic), Bake them, Save as a note. | `check-pad`, `check-live`, `check-light`, `check-speaker`, `check-bake`, `check-save`, `audio-check` | ui/AudioCheckScreen.kt, SpeakerTest.kt | todo | part: no speaker test on web |
| Learn | Speed guide (the whole app in a minute) and 29 lessons in groups. Each runs on a practice mess and restores yours; a ring points at the control and lets taps through. Back, Skip step, Stop lesson. | `learn-speed`, `learn-<id>`, `tut-card`, `tut-text`, `tut-back`, `tut-skip`, `tut-stop` | ui/Tutor.kt, Tutorials.kt | todo | done (shared screens, CMP web) |
| What the FAQ card | While on: ring the control under the tap, name, line, Lesson button, Speed guide, screw. Speed guide card: Back, Pause / Go on, Next, screw. The FAQ guy (`ic_what_the_faq`, Tyler's drawing) sits at the left of the header of this card, the speed guide, lesson and start guide cards (inside the card, never over the screen) and hops when the line changes. | `faq-layer`, `faq-card`, `faq-name`, `faq-text`, `faq-lesson`, `faq-speed`, `faq-done`, `speed-card`, `speed-text`, `speed-back`, `speed-pause`, `speed-next`, `speed-done` | ui/FaqOverlay.kt, Faq.kt | todo | done (shared screens, CMP web) |
| Notes for Claude | Crash card, new note: kind (Bug, Tuning, Feature, Question, Discuss, Wishlist), topic for Discuss, text, Speak, Send to Claude, Keep on phone. Quick notes send right away / wait. Send all (n), Check for replies. Note list with replies, Send, Delete. Phone status with Latency. GitHub key: Set up, Save key, Remove key. | `category-chips`, `notecat-<c>`, `notes-sendnow`, `notes-sendall`, `notekey-open`, `notekey-field`, `notekey-save`, `notekey-forget`, `audio-check` | ui/NotesScreen.kt, Notes.kt | todo | part: sends with the key or GitHub's page; no Speak (no speech to text) |

Toast (yellow, bottom, 3.2 s, tap to hide; Undo after a clear): `toast`, `toast-undo`.

Stale FAQ lines (pattern in Faq.kt, no control in the code at build 170): `synth-go-*`, `zoom-*` as "how much of the sound you see" (the only `zoom-` tags are View size). Port the FAQ minus these.

## MIDI

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| MIDI in | USB controllers. Notes 36 to 51 hit pads 1 to 16 of the bank on screen; other notes play the picked pad as keys (60 is the sound's own pitch). Velocity plays and records. Skips program change and channel pressure bytes. Pitch bend drives the wheel. A key on an empty pad does nothing. | none | Midi.kt | todo | part: Web MIDI (Chrome, Edge); blocked in Hubbub's frame and claude.ai artifacts |
| MIDI map | ☰ > MIDI map: a panel floats (low in new layouts, under the top bar in classic) while the page stays live. Touch a pad, Padiano key or knob, then press a key or turn a knob; the place moves on (right, then down). Skip, Clear, Run / One at a time, List (Teach again, take off), pages A to D (key = (page * 2 + shift) * 128 + note), Buttons (Play, Stop, Rec, Hold, bank, page, Shift...), Pad volume. Pads show `♪ n` or `♪ ?`. Map kept on the phone, not in the mess. | `midimap`, `midimap-skip`, `midimap-clear`, `midimap-done`, `midimap-run`, `midimap-one`, `midimap-list`, `midimap-rows`, `midimap-teach-<code>`, `midimap-x-<code>`, `midimap-page-<0..3>`, `midimap-acts`, `midimap-vol` | ui/MidiMap.kt, AppState.kt `midiAim` | todo | part: needs Web MIDI (see MIDI in) |
| MIDI file out | Format 1, PPQ 96, tempo track plus one track per sound; C3 = 60 plus step pitch; velocity 48 / 100 / 127 by strength; chance ignored. | `midi-loop`, `midi-song` | engine/MidiOut.kt | todo | done (shared screens, CMP web) |

## Recording

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Live recording | Rec on while the loop plays: pad hits, keys, chords, XY, FX faders, vials, wheel and mouth land in the loop. Each hit has an id (a swipe takes back exactly its own). Snapped to 16ths, or exact on a 24-tick-a-step grid (records triplet and dotted repeats). Count-in hits within half a step of the downbeat land on step 0. | `rec`, `set-recexact` | AppState.kt, engine/Engine.kt `RecEvent` | todo | done (shared screens, CMP web) |
| Velocity | Hits record ghost (vel at most .55), normal, accent (at least .95). Steps gain .42 / .85 / 1. | none | engine/Engine.kt | todo | done (shared screens, CMP web) |
| Mic onto a pad | Empty pad top corner or Record onto it. Tap start / tap stop, or hold to record. Red banner. | `menu-record`, `rec-banner` | Workbench.kt, AppState.kt `recordToPad` | todo | part: mic blocked in Hubbub's game frame and in claude.ai artifacts |
| Workbench | Imports, mic takes, resampled loops (Bottle it), saved edits and chops; measures key and tempo. | `mic`, `import`, `menu-keep` | Workbench.kt | todo | part: imports done (the browser decodes); mic takes blocked in Hubbub and artifacts; big files last only in IndexedDB |
| Bounce | Loop once, x4, the song, stems: WAV to share. | see Messes | engine/Engine.kt `bounce` `bounceStems` | todo | done (shared screens, CMP web) |

## Undo

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Undo / Redo | 80 snapshots of the mess. Sliders add one step on release. Live settings (Repeat, Rec, Hold) are not undone. Redo in ☰. Clears show a toast with Undo. Lessons pause autosave and restore your mess. | `undo`, `redo`, `toast-undo` | AppState.kt `undo` `redo` `edit` | todo | done (shared screens, CMP web) |
| Missing sounds and foreign keys | A pad whose sound this device lacks keeps its key, reads Missing, is silent and keeps its steps, notes and edits; saving writes it all back. Unknown top-level and per-loop keys are carried unchanged. A file with `v` above 3 loads with a "newer version" line. See MESS-FORMAT.md 4.2 and 10. | none (pad label "MISSING") | engine/Project.kt `fromJson` `toJson` `extra`, engine/Library.kt `titleOf` | todo | done (shared screens, CMP web) |
| Autosave | Every edit autosaves; crash-safe (.new then rename, .bak kept). | none | AppState.kt | todo | done (shared screens, CMP web) |

## Accounts, Slice A (only on a build with a server)

Nothing below exists unless the build has a server (`mm.cloudUrl` and `mm.cloudKey`, docs/ACCOUNTS-API.md s5). The
client logic is the shared `CloudStore` in core/; these rows are the screens. Nothing is added to the play screen.

| Feature | What it does | Tags | Android file | iOS | Web |
|---|---|---|---|---|---|
| Account entry | ☰ menu item and the row at the top of the Messes sheet: "Sign in to save online", or your name. Opens the Account sheet. | `acct-open` | ui/App.kt `TopMenu`, ui/CloudScreens.kt `AccountRow` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Sign in with an email code | Email, Send me a code, the 6 to 10 digit code, Sign in. Send a new code wakes 60 s after the last. Plain errors: wrong or old code, too many tries, no connection. | `acct-email`, `acct-send`, `acct-code`, `acct-verify`, `acct-resend` | ui/CloudScreens.kt `SignIn` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Terms | Short terms in a scroll box, Read the full terms (site), I agree, Not now (signs out). Nothing goes online before I agree. | `terms-text`, `terms-agree`, `terms-decline`, `acct-terms` | ui/CloudScreens.kt `Terms` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Name, asked once | After the first sign-in while `me.name_set` is false: "What should others call you?". Closing the sheet counts as asked (pref `cloudNameAsked`). | `name-ask`, `name-ask-ok` | ui/CloudScreens.kt `NameCard` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Put your messes online? | Once, when this phone has messes not online yet: All, Pick (opens Messes), Not now (pref `cloudMoveAsked`). | `cloud-move-all`, `cloud-move-pick`, `cloud-move-later` | ui/CloudScreens.kt `MoveAsk` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Account page | Who you are, your name (Save name), "Use mobile data for sounds" (off: Wi-Fi only), Terms and Privacy pages, Sign out ("Your messes on this phone stay"). No usage bar (cut by the rulings). | `acct-name`, `name-ask-ok`, `acct-data`, `acct-terms`, `acct-privacy`, `acct-signout` | ui/CloudScreens.kt `AccountPage` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Save goes online | Every mess saved while signed in goes online 5 s later (30 s at most while it keeps changing), and at once when the app leaves the screen. | `mess-save` | AppState.kt `saveMess`, `cloudBackground` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Cloud mark | Under each mess on this phone: Online, Waiting to go online, Going online, Offline (tries again), Taken down online, Not saved online, On this phone only. | `mess-cloud-<id>` | ui/CloudScreens.kt `CloudMark` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Online lamp | On the open mess's row: green online, amber "N changes waiting", grey offline. | `sync-state` | ui/CloudScreens.kt `CloudMark` | todo | done (shared screens, CMP web) |
| Keep off the cloud | Row button: Keep off the cloud (asks once more; deletes the online copy, the phone file stays) or Put online. | `mess-offcloud-<id>` | ui/CloudScreens.kt `CloudRowButton` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Online, not on this phone | Online messes with no file here (or tossed here). Tap brings it here and opens it; Duplicate (a copy, yours); Delete online (owner, asks once more). `<cid>` is the first 8 characters of the cloud id. | `omess-<cid>`, `omess-dup-<cid>`, `omess-del-<cid>` | ui/CloudScreens.kt `OnlineOnly` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Get sounds now | On the open mess's row when some of its sounds have not come (mobile data, sounds off): "Get N sounds now". | `sounds-get` | ui/CloudScreens.kt `CloudMark` | todo | done (shared screens, CMP web) |
| Sounds that arrive | Your own sounds made on another phone go into Your Workbench under the same `bn:` key; other people's (Slice B) into a "From shared messes" jar. A sound whose maker left reads Missing. | | AppState.kt `cloudSoundArrived`, Workbench.kt `adoptCloud` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Clash: keep both | Two phones changed one mess: the server's goes into the mess, mine becomes "<name> (this phone)" and the screen follows it; a notice says so. Nov 24 (Tyler's answer 7): when both phones are mine, a banner asks instead: Merge (only when the last shared version is known: my changes since it are laid on the other phone's), Keep this phone's (it becomes the main mess, the copy goes, on the phone and online), Keep both. Core: `CloudStore.two`, `settleTwo`, `StoreHost.dropLocal`. A newer version arriving while the screen has unsaved changes leaves them on screen as a new mess. | `two-banner` (a card over every sheet, under the top bar), `two-merge`, `two-this`, `two-both` | AppState.kt `cloudKeepBoth`, `cloudTakeServer`, `answerTwo`, ui/App.kt `TwoPhonesBanner` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |


## Accounts, Slices B to F (only on a build with a server)

Two people in one mess, live feel, share links and History, the store requirements, the Vault. The logic is the shared
`SyncSession`, `Ops` and `CloudStore` in core/ (driven as docs/ACCOUNTS-HOST-B.md says); these rows are the screens.
Nothing here sits on the play screen. `<cid>`, `<uid>`, `<iid>` are the first 8 characters of a cloud mess, user or
invite id; `<n>` counts History rows from 0, newest first.

| Feature | What it does | Tags | Android | iOS | Web |
|---|---|---|---|---|---|
| Share sheet | "Share" on a mess that is online (Messes rows, on this phone or not): the mess, who made it, then the cards below. | `omess-share-<cid>` | ui/ShareScreens.kt `ShareScreen`, ui/CloudScreens.kt `CloudRowButton` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Invite to edit | Owner only. Makes a 7 day single-use link and opens the phone's share sheet (`https://<site>/join/<token>`, or `messmachine://j/<token>` without a site). What is on screen goes online first. Unused invites are listed with "Cancel it". | `share-invite`, `inv-revoke-<iid>` | AppState.kt `inviteToEdit`, `revokeInvite` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Name at the first share or join | While `me.name_set` is false the Share sheet and a waiting invite ask for the name first. | `name-ask`, `name-ask-ok` | ui/CloudScreens.kt `NameCard` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Members | Everyone on the mess, owner first: colour dot (from the server), name, "made it" or "can edit", "here now" while they have it open (Slice C). | `share-members`, `mem-<uid>` | ui/ShareScreens.kt | todo | done (shared screens, CMP web) |
| Remove, and block | Owner only: Remove opens the question; its answers are Remove and "Remove and block". The removed person keeps their own copy. | `mem-remove-<uid>`, `mem-remove-go`, `mem-remove-block` | AppState.kt `removeMember` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Leave | Not the owner: "Leave this mess" (asks once more). The copy on the phone stays, on this phone only. | `mem-leave` | AppState.kt `leaveMess` | todo | done (shared screens, CMP web) |
| Links into the app | `messmachine://j/<token>` (invite) and `messmachine://m/<token>` (share link), the same as https on the site host (`/join/`, `/j/`, `/m/`; verified only once the site's assetlinks.json names the signing key), or "Paste a link" on the Account page (reads the clipboard). | `link-paste` | MainActivity.kt `handle`, AndroidManifest.xml, AppState.kt `cloudLink`, `pasteLink` | todo | skip: no app links on the web |
| Join question | "Join Basement jam?" with who invited you. Signed out it waits above the sign-in; it survives sign-in, the terms and a restart (pref `cloudInvite`). Join opens the mess; "No thanks" forgets it. Plain errors: used or run out, 8 people already, cannot join (blocked). | `invite-join`, `invite-no` | ui/ShareScreens.kt `InviteCard`, AppState.kt `joinInvite` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| People you blocked | Account page, People: the list with Unblock. | `acct-blocks`, `block-undo-<uid>` | ui/ShareScreens.kt `AccountPeople` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| A mess edited together | A mess more than one person is on (or with an invite out) runs a `SyncSession`: every change goes up as it is made (no Save needed, the file follows the screen), other people's changes come in at the top of the next loop while playing and at once when stopped, the same field is last writer wins. The poll is every 2 s (10 s with the socket, 30 s alone). | | AppState.kt `syncTick`, `syncArm`, `SyncSide`; engine `setSnapshotAtWrap` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Undo in a shared mess | Undo takes back your own last change only; what someone else changed since is left alone, with a line saying so. No Redo. | | AppState.kt `undo`, `undoFlags` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Session lamp | On the open mess's row in Messes: "Online, edited together", "N changes going up", "Offline, N changes waiting", "Listening only, 4 people are editing". | `sync-state` | ui/CloudScreens.kt `CloudMark` | todo | done (shared screens, CMP web) |
| Newer app notice | "Someone here has a newer Mess Machine. Update to see everything they do." under the open mess's row. | `sync-newer` | ui/CloudScreens.kt `CloudMark` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Removed, or the mess went away | The session ends, the mess stays on the phone as your own copy (on this phone only), a line says so. Changes the server would not take are kept as "<name> (my copy)". | | AppState.kt `syncTick`, `keepCopyNow` | todo | done (shared screens, CMP web) |
| Your own sound in a shared mess | A Workbench sound put on a pad goes online at once (Wi-Fi, or mobile data when allowed); the others fetch it into "From shared messes". | | AppState.kt `cloudSoundUsed`, CloudStore `byOps`, `soundsChanged` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Live socket (Slice C) | Supabase Realtime over one websocket (OkHttp, only for this): join the mess's topic with the access token, heartbeat 25 s, `ops` and `meta` broadcasts make the session ask the server at once, presence at most every 2 s. A dropped socket retries after 1, 2, 5, 10 s; without it the poll carries everything. | | Realtime.kt `RealtimeClient`, SupabaseApi.kt, AppState.kt `rtTick` | todo | done (shared screens, CMP web) |
| Who is here (Slice C) | Small chips in the "This mess" card of the Messes sheet: colour dot and "Mia is here" or "Mia is playing". Never on the pads. | `here-chip`, `here-<uid>` | ui/ShareScreens.kt `HereChips` | todo | done (shared screens, CMP web) |
| "Mia is shaping this too" (Slice C) | One line at the top of a pad's Edit tray, in that person's colour, while they have the same pad's Edit tray open. No question, no lock. | | ui/EditTray.kt, AppState.kt `shapingToo` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Share link (Slice D) | Owner, on the Share sheet: Off, Listen, Listen and copy; the link; Send the link; New link (the old one dies). | `share-link-off`, `share-link-view`, `share-link-copy`, `share-link-send`, `share-link-new` | ui/ShareScreens.kt `ShareExtras`, AppState.kt `setShare` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Opening a share link (Slice D) | Works signed out: the mess's name and maker, Listen (opens it unsaved; from a listen link Save stays off), Make it mine (a copy link, signed in: your own copy online and on the phone), No thanks. | `shared-open`, `shared-mine`, `shared-no` | ui/ShareScreens.kt `SharedCard`, AppState.kt `openShared`, `makeSharedMine` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| History (Slice D) | On the Share sheet: the saved versions (label, when, who), Restore (asks once more) puts the mess back for everyone; what it was is kept as "Before restore". Save in a shared mess keeps a version named Saved. | `hist-open`, `hist-<n>`, `hist-restore-<n>` | ui/ShareScreens.kt `ShareExtras`, AppState.kt `restoreVersion` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Report (Slice E) | "Report this mess" (not its maker; also on an opened share link) and "Report" on a person: pick a reason (sexual, hate, harassment, someone else's music, spam, something else), Send report; for a person also "Send and block". | `report-mess`, `report-user-<uid>`, `report-r-<reason>`, `report-send`, `report-send-block`, `report-no` | ui/ShareScreens.kt `ReportCard`, AppState.kt `sendReport` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Delete account (Slice E) | Account page: Delete my account lists what goes and what stays, mails a fresh code, then "Delete it all". The phone's messes and sounds stay. | `acct-delete`, `del-send`, `del-code`, `del-go`, `del-no` | ui/ShareScreens.kt `DeleteAccountCard`, AppState.kt `deleteGo` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Reviewer sign-in (Slice E) | On the sign-in card, quiet: a password field for the app stores' review account only (contract s1.2). | `acct-review`, `acct-review-pass`, `acct-review-go` | ui/CloudScreens.kt `SignIn`, AppState.kt `StoreApi` | todo | todo: accounts and sharing need the cloud API, not wired in the web build yet |
| Give a sound to the Vault (Slice F) | Hold a Workbench sound, "Give it to the Vault": the grant text from the server, the credit name, I agree, Send it (the sound goes up first when it is not online). The sound's row then says "Vault: waiting for a listen", "In the Vault" or "Vault: not taken". | `vault-give`, `vault-grant`, `vault-credit`, `vault-agree`, `vault-send` | ui/ShareScreens.kt `VaultScreen`, AppState.kt `vaultSend`, `vaultRefresh` | todo | done (shared screens, CMP web) |

## Android-only pieces and what they map to

| Android piece | Where | iOS | Web |
|---|---|---|---|
| AAudio low-latency stream (exclusive, then shared), lock-free ring, buffer that grows on clicks (Tight 3 bursts, Steady 8) and shrinks after 10 clean s; AudioTrack fallback (Sound out: older) | app/src/main/cpp/mmaudio.c, AudioOut.kt | AVAudioEngine or a RemoteIO AudioUnit, `AVAudioSession` preferred IO buffer about 5 ms | AudioWorklet with a SharedArrayBuffer ring (needs cross-origin isolation), `latencyHint: "interactive"` |
| Android MIDI (`android.media.midi`) | Midi.kt | CoreMIDI (USB and Bluetooth) | Web MIDI API (Chrome and Edge; not Safari) |
| Background play with a media notification (Stop) | PlaybackService.kt, `set-bgplay` | `UIBackgroundModes` audio, Now Playing and remote commands | Media Session API; browsers may still suspend a hidden tab |
| Notifications permission (Android 13) and the reopen notice after an update | Updater.kt, ui/FilesScreen.kt | not needed | not needed |
| In-app update from GitHub releases (PackageInstaller, no tap on Android 12+) | Updater.kt, `update-*` | TestFlight or App Store; show "build N is ready" only | service worker update, then reload |
| Bug notes as GitHub issues (`from-app` plus kind label, hidden `mm-note:<id>` marker, replies read back) | Notes.kt | the same GitHub REST calls with URLSession | the same calls with fetch, or open the new-issue URL |
| Quick note speech (SpeechRecognizer, restarted until the second tap) | VoiceNote.kt | SFSpeechRecognizer | Web Speech API (`webkitSpeechRecognition`), else the note box |
| Mic for mouth control (16 kHz, voice recognition source, echo canceller) and mic takes | MouthMic.kt, Workbench.kt | AVAudioSession record, AVAudioEngine input tap | getUserMedia with echoCancellation |
| Pad buzz (haptic KEYBOARD_TAP) | ui/PadsScreen.kt | UIImpactFeedbackGenerator | `navigator.vibrate` (Android browsers only) |
| Lock pins the screen (startLockTask) | ui/App.kt | Guided Access (set by the user), no API | Fullscreen API at most |
| 60 Hz or Smooth screen (preferredDisplayModeId) | ui/App.kt `setScreenRate` | CADisplayLink `preferredFrameRateRange` | not available; draw on requestAnimationFrame |
| Back button closes the top panel | ui/App.kt BackHandler | no back key: every panel keeps its screw | Escape key and history state |
| Light status bar icons on light skins | ui/App.kt | `preferredStatusBarStyle` | `theme-color` meta |
| Share sheet (WAV, mess, stems, MIDI), open a mess file, import sounds | AppState.kt, ui/FilesScreen.kt, ui/SoundsScreen.kt | UIActivityViewController, UIDocumentPicker | Web Share API with files, else download; `<input type=file>` |
| Test tags as resource ids, Robolectric big test, emulator phone test | ui/Tutor.kt `tagged`, app/src/test, tools/phone-test.sh | accessibilityIdentifier, XCUITest | data-testid, Playwright |
