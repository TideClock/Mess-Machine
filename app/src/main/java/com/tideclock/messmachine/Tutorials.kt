package com.tideclock.messmachine

import com.tideclock.messmachine.engine.Project
import com.tideclock.messmachine.engine.Synth

/**
 * Interactive tutorials (MM-T). Each lesson runs on a practice mess, points at one control at a time, and moves on
 * only when you have done the step for real (the app watches its own state). Your mess is put back at the end.
 */
class TutCtx {
    var trig0 = 0
    var v0 = 0
    var bpm0 = 0
    var num0 = 0.0
    var kit0 = 0
    var con0 = 0
    var fav0 = 0
    var xy0 = ""
    val seen = HashSet<String>()
}

class TutStep(
    val text: String,
    /** The control to point at: a tag, worked out when the step starts (so it can name the selected pad). */
    val target: (AppState) -> String? = { null },
    val done: (AppState, TutCtx) -> Boolean,
)

class Tutorial(val id: String, val group: String, val title: String, val blurb: String, val steps: List<TutStep>, val setup: (AppState) -> Unit = {})

object Tutorials {
    private fun t(tag: String?): (AppState) -> String? = { tag }
    private fun played(s: AppState, c: TutCtx, n: Int = 1) = s.engine.triggers >= c.trig0 + n
    private fun selKey(s: AppState) = s.project.kit[s.project.sel]
    private fun selEdit(s: AppState) = s.project.edit(selKey(s))
    private fun cut(s: AppState) = s.synthOn(s.project.sel)?.get("cut") ?: 0.0

    private val openSample = TutStep("Tap Sample, by the bank numbers. It opens the selected pad's Sample sheet.", t("open-sample")) { s, _ -> s.overlay is Overlay.Doctor }
    private val closeSheet = TutStep("Close the sheet with Done.", t("done")) { s, _ -> s.overlay == null }

    val all: List<Tutorial> = listOf(
        Tutorial("pads", "Start here", "Pads and banks", "Play pads, switch banks, find a pad's menu.", listOf(
            TutStep("Tap a few pads.", t("pad-0")) { s, c -> played(s, c, 3) },
            TutStep("Tap 2 under BANK: a second set of 16 pads.", t("bank-1")) { s, _ -> s.bank == 1 },
            TutStep("Back to bank 1.", t("bank-0")) { s, _ -> s.bank == 0 },
            TutStep("Press and hold a pad: its menu opens (sounds, keys, Sample, clear).", t("pad-1")) { s, _ -> s.padMenu >= 0 },
            TutStep("Close the menu by tapping outside it.") { s, _ -> s.padMenu < 0 },
        )),
        Tutorial("hold", "Playing", "Hold", "Latch pads so they keep playing while your hands do something else.", listOf(
            TutStep("Tap H in the dock to turn Hold on.", t("dock-hold")) { s, _ -> s.holdOn },
            TutStep("Tap a pad. It keeps playing on its own, and an H shows in its corner.", t("pad-2")) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap a second pad. It holds too: Hold keeps as many as the number under H (3 to start; at Last, the newest takes over).", t("pad-3")) { s, c -> played(s, c, 1) },
            TutStep("Tap each held pad again to let them go.") { s, _ -> s.latched.isEmpty() },
            TutStep("Tap H again to turn Hold off.", t("dock-hold")) { s, _ -> !s.holdOn },
        )),
        Tutorial("beat", "Playing", "On the beat", "A held pad that starts again on every beat, in time.", listOf(
            openSample,
            TutStep("Under When it is held, pick On the beat.", t("held-beat")) { s, _ -> selEdit(s).held == "beat" },
            TutStep("Pick 2 beats.", t("beat-2")) { s, _ -> selEdit(s).beatN == 2 },
            closeSheet,
            TutStep("Turn Hold on with H.", t("dock-hold")) { s, _ -> s.holdOn },
            TutStep("Tap the selected pad. It starts again every 2 beats, in time.", { s -> "pad-${s.project.sel % Project.BANK}" }) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap it again, then turn Hold off.", t("dock-hold")) { s, _ -> s.latched.isEmpty() && !s.holdOn },
        )),
        Tutorial("repeat", "Playing", "Note repeat", "Hold a pad and it repeats in time; pick how the repeats sound.", listOf(
            TutStep("Tap RPT in the dock.", t("dock-repeat")) { s, _ -> s.live.nr },
            TutStep("Press and hold a pad: it repeats at the rate shown under RPT.", t("pad-0")) { s, c -> played(s, c, 4) },
            TutStep("Tap the rate under RPT and pick Flow (hold and pulse).", t("dock-rate")) { s, _ -> s.live.nrStyle == "flow" },
            TutStep("Hold a pad again: one note that breathes with the rate.", t("pad-0")) { s, c -> played(s, c, 1) },
            TutStep("Tap the rate, pick Cut each repeat, then turn RPT off.", t("dock-rate")) { s, _ -> s.live.nrStyle == "cut" && !s.live.nr },
        )),
        Tutorial("padiano", "Playing", "Padiano", "Any pad's sound as a keyboard, dropped right under its row.", listOf(
            TutStep("Press and hold the first pad, then tap Padiano under this row.", t("pad-0")) { s, _ -> s.keyboards.isNotEmpty() },
            TutStep("Play some keys. In key only keeps every key in the mess's key.", { s -> s.keyboards.firstOrNull()?.let { "piano-keys-$it" } }) { s, c -> played(s, c, 4) },
            TutStep("Close it with the x on its bar.", { s -> s.keyboards.firstOrNull()?.let { "piano-close-$it" } }) { s, _ -> s.keyboards.isEmpty() },
        )),
        Tutorial("sphere", "Playing", "XY sphere", "Draw a circle on a pad and it becomes a ball you push around to bend the sound.", listOf(
            TutStep("Press a pad and, without lifting, draw a small circle on it.", t("pad-0")) { s, c -> if (s.sphere != null) c.seen.add("sphere"); "sphere" in c.seen },
            TutStep("Keep your finger down and push the ball around. Lift to let go.") { s, c -> "sphere" in c.seen && s.sphere == null },
        )),
        Tutorial("grain", "Samples", "Stretch or Grain", "Change a sound's pitch like tape, or keep its length.", listOf(
            openSample,
            TutStep("Under PITCH, pick Stretch: higher notes play faster and shorter, like tape.", t("pitch-stretch")) { s, _ -> selEdit(s).pitch == "stretch" },
            closeSheet,
            TutStep("Tap Keys at the top. The pads become notes of the selected sound.", t("mode-keys")) { s, _ -> s.keysMode },
            TutStep("Play a low pad and a high one: the high one is shorter.", t("pad-12")) { s, c -> played(s, c, 2) },
            TutStep("Tap Pads, open Sample again and pick Grain.", t("mode-pads")) { s, _ -> selEdit(s).pitch == "grain" },
            TutStep("Close it, tap Keys and play low and high again: same length now.", t("mode-keys")) { s, c -> s.keysMode && s.overlay == null && played(s, c, 2) },
            TutStep("Back to Pads.", t("mode-pads")) { s, _ -> !s.keysMode },
        )),
        Tutorial("sustain", "Samples", "Sustain", "Any short sound held like a pad for as long as your finger stays down.", listOf(
            openSample,
            TutStep("Under When it is held, pick Sustain.", t("held-sustain")) { s, _ -> selEdit(s).held == "sustain" },
            closeSheet,
            TutStep("Press and hold the selected pad for a few seconds, then let go.", { s -> "pad-${s.project.sel % Project.BANK}" }) { s, c -> played(s, c, 1) },
        )),
        Tutorial("bake", "Samples", "Bake", "Do Grain's work ahead of time: cleaner, and as light as Stretch.", listOf(
            openSample,
            TutStep("Pick Grain under PITCH.", t("pitch-grain")) { s, _ -> selEdit(s).pitch == "grain" },
            TutStep("Tap Bake it. It renders every semitone an octave either way, in a few seconds.", t("bake-it")) { s, _ -> selEdit(s).bake },
            TutStep("Wait for it to say Baked.", t("bake-it")) { s, _ -> s.bakeState(selKey(s)) == "baked" || s.bakeState(selKey(s)) == "too long" },
            closeSheet,
        )),
        Tutorial("synth", "Synths", "Make a synth", "A synth on a pad: knobs, modes, and the six kinds.", listOf(
            TutStep("Tap SYNTH.", t("tab-Synth")) { s, _ -> s.tab == Tab.Synth },
            TutStep("Tap On empty pad: a Warm pad synth lands there.", t("synth-new-empty")) { s, c -> (s.synthOn(s.project.sel) != null).also { if (it) c.num0 = cut(s) } },
            TutStep("Play the keyboard under the knobs.", { s -> "piano-keys-${s.project.sel}" }) { s, c -> played(s, c, 2) },
            TutStep("Drag the Cutoff knob up or down. Hold a key while you do it.", t("knob-cut")) { s, c -> Math.abs(cut(s) - c.num0) > c.num0 * .15 },
            TutStep("Double tap the knob to put it back.", t("knob-cut")) { s, _ -> Math.abs(cut(s) - 2400) < 1 },
            TutStep("Pick Acid, then play the keys: one voice that slides.", t("synthmode-2")) { s, c -> s.synthOn(s.project.sel)?.get("mode") == 2.0 && played(s, c, 1) },
            TutStep("Try three other kinds at the top (FM, Wavetable, Granular, Drums, Pluck).", t("synthkind-chips")) { s, c ->
                s.synthOn(s.project.sel)?.kind?.id?.let { c.seen.add(it) }; c.seen.count { it != "analog" } >= 3
            },
        )),
        Tutorial("synthipad", "Synths", "Synthipad", "The Synth page as a playing surface: in-key notes and a filter square.", listOf(
            TutStep("Tap SYNTH.", t("tab-Synth")) { s, _ -> s.tab == Tab.Synth },
            TutStep("Tap Synthipad.", t("synthview-pad")) { s, c -> (s.synthView == "pad").also { if (it) c.num0 = cut(s) } },
            TutStep("Play the note grid. More fingers, more notes.", t("synthipad-notes")) { s, c -> played(s, c, 3) },
            TutStep("Drag in the square: across is cutoff, up is resonance.", t("synthipad-xy")) { s, c -> Math.abs(cut(s) - c.num0) > 1 },
            TutStep("Back to Simple.", t("synthview-simple")) { s, _ -> s.synthView == "simple" },
        )) { s -> s.newSynth(Project.BANK - 1, Synth.ANALOG.presets.first()); s.tab = Tab.Pads },
        Tutorial("steps", "Writing", "Steps", "Program a beat on the step grid and play it.", listOf(
            TutStep("Tap STEPS.", t("tab-Steps")) { s, _ -> s.tab == Tab.Steps },
            TutStep("Tap a few steps on the first row.", t("steps-0")) { s, c -> s.version >= c.v0 + 3 },
            TutStep("Press Play.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("roll", "Writing", "Roll", "Draw notes with lengths for any pad, synths included.", listOf(
            TutStep("Tap ROLL.", t("tab-Roll")) { s, _ -> s.tab == Tab.Roll },
            TutStep("Tap in the grid to draw a few notes.", t("roll")) { s, c -> s.version >= c.v0 + 3 },
            TutStep("Press Play.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("loops", "Sounds", "Loops at your tempo", "Qbldy loops play one bar at whatever tempo your mess is.", listOf(
            TutStep("Tap SOUNDS.", t("tab-Sounds")) { s, _ -> s.tab == Tab.Sounds },
            TutStep("Open the Qbldy Loops jar and put a loop on a pad.", t("jar-loops")) { s, _ -> s.project.kit.any { it.startsWith("loop:") } },
            TutStep("Tap PLAY and play the loop's pad.", t("tab-Pads")) { s, c -> s.tab == Tab.Pads && played(s, c, 1) },
            TutStep("Tap the BPM at the top, change the tempo, close it and play the loop again: same pitch, new speed.", t("bpm")) { s, c -> s.project.bpm != c.bpm0 && played(s, c, 1) },
        )),
        Tutorial("packs", "Sounds", "Packs", "Sounds picked in the Vault, sent to the app as jars.", listOf(
            TutStep("Tap SOUNDS.", t("tab-Sounds")) { s, _ -> s.tab == Tab.Sounds },
            TutStep("Tap Packs.", t("open-packs")) { s, _ -> s.overlay == Overlay.Packs },
            TutStep("Tap Check for packs. Install any that are there.", t("packs-refresh")) { s, _ -> s.packOffers != null && s.packNote != "Looking for packs…" },
            closeSheet,
        )),
        Tutorial("filter", "Playing", "Dock filter", "Sweep a filter over the whole mix, or one pad.", listOf(
            TutStep("Tap a pad, then drag the filter slider in the dock down.", t("dock-cut")) { s, _ -> s.dockCut < .8f },
            TutStep("Let go: it springs back open.", t("dock-cut")) { s, _ -> s.dockCut > .95f },
        )),
        Tutorial("lock", "Playing", "Lock", "Lock the screen so only pads play: no menus, no accidents mid-set.", listOf(
            TutStep("Tap the lock in the dock.", t("dock-lock")) { s, _ -> s.locked },
            TutStep("Play some pads. Tabs and menus stay shut while it's locked.", t("pad-1")) { s, c -> played(s, c, 3) },
            TutStep("Unlock it the same way.", t("dock-lock")) { s, _ -> !s.locked },
        )),
        Tutorial("chords", "Playing", "Chords and key", "Pick the mess's key and scale, and play chords from one finger.", listOf(
            TutStep("Tap Keys at the top.", t("mode-keys")) { s, _ -> s.keysMode },
            TutStep("Pick a different scale.", t("pick-scale")) { s, _ -> s.project.scale != "Minor" },
            TutStep("Pick a different root note.", t("pick-root")) { s, _ -> s.project.root != 0 },
            TutStep("Pick a chord type: each key now plays a chord in the key.", t("pick-chord")) { s, _ -> s.chordMode != "off" },
            TutStep("Play a few keys.", t("pad-4")) { s, c -> played(s, c, 3) },
            TutStep("Back to Pads.", t("mode-pads")) { s, _ -> !s.keysMode },
        )),
        Tutorial("live", "Writing", "Record live", "Play pads while the loop runs and they land on the steps.", listOf(
            TutStep("Tap the record button at the top so it lights up.", t("rec")) { s, _ -> s.live.rec },
            TutStep("Press Play.", t("play")) { s, _ -> s.playing },
            TutStep("Play a beat on the pads, in time with the loop.", t("pad-0")) { s, _ -> s.project.pat().used() },
            TutStep("Press Stop, then tap record again to turn it off.", t("stop")) { s, _ -> !s.playing && !s.live.rec },
            TutStep("Tap STEPS to see what you played.", t("tab-Steps")) { s, _ -> s.tab == Tab.Steps },
        )),
        Tutorial("song", "Writing", "Song", "Chain loops into a song, bar by bar.", listOf(
            TutStep("Tap STEPS and tap a few steps, so loop A has something in it.", t("tab-Steps")) { s, _ -> s.project.pat().used() },
            TutStep("Tap SONG.", t("tab-Song")) { s, _ -> s.tab == Tab.Song },
            TutStep("Tap a few bars to fill them with the loop.", t("bar-0")) { s, _ -> s.project.songLen() > 0 },
            TutStep("Press Play: it plays the song, bar by bar.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("bottle", "Writing", "Bottle it", "Turn the loop you made into one sound on a pad.", listOf(
            TutStep("Tap STEPS and tap a few steps.", t("tab-Steps")) { s, _ -> s.project.pat().used() },
            TutStep("Tap Bottle it. The loop becomes a sound on a free pad.", t("bottle")) { s, c -> s.project.kit.count { it.isNotEmpty() } > c.kit0 },
            TutStep("Tap PLAY and play the new pad.", t("tab-Pads")) { s, c -> s.tab == Tab.Pads && played(s, c, 1) },
        )),
        Tutorial("mic", "Sounds", "Record onto a pad", "Any empty pad is a record button: catch a sound with the mic.", listOf(
            TutStep("Tap 2 under BANK: these pads are empty.", t("bank-1")) { s, _ -> s.bank == 1 },
            TutStep("Tap an empty pad to start recording (or hold it and tap Record onto this pad). The first time, allow the mic.", t("pad-0")) { s, _ -> s.micOn },
            TutStep("Make a sound, then tap the pad again to stop.", t("pad-0")) { s, c -> !s.micOn && s.project.kit.count { it.isNotEmpty() } > c.kit0 },
            TutStep("Play your new sound.", t("pad-0")) { s, c -> played(s, c, 1) },
        )),
        Tutorial("sheet", "Samples", "The Sample sheet", "Trim a sound, shape it, add effects, and the tools that chop and fit it.", listOf(
            openSample,
            TutStep("Tap Trim. Drag the yellow lines to cut the start or the end.", t("dtab-trim")) { s, _ -> selEdit(s).st > 0.001 || selEdit(s).en < .999 },
            TutStep("Tap Effects and turn one up (reverb, echo, dirt...).", t("dtab-fx")) { s, _ -> selEdit(s).hasFx() },
            TutStep("Tap Tools: fit to tempo, chop to pads, keep it as a new sound. Have a look, then tap Skip step.", t("dtab-tools")) { _, _ -> false },
            closeSheet,
        )),
        Tutorial("xy", "Playing", "XY setup", "Choose what a pad's XY sphere does when you push it around.", listOf(
            TutStep("Press and hold the first pad, then tap XY setup.", t("pad-0")) { s, c -> (s.overlay is Overlay.Xy).also { if (it) c.xy0 = s.xyOf(s.project.sel).name } },
            TutStep("Pick a different preset.", t("xy-editor")) { s, c -> s.xyOf(s.project.sel).name != c.xy0 },
            closeSheet,
            TutStep("Draw a circle on that pad and push the ball around: hear the new setup.", t("pad-0")) { s, c -> if (s.sphere != null) c.seen.add("sphere"); "sphere" in c.seen && s.sphere == null },
        )),
        Tutorial("desk", "Playing", "My Desk", "Build your own playing surface from pieces: pads, keys, XY, effects.", listOf(
            TutStep("Tap My Desk, next to Pads and Keys.", t("view-desk")) { s, c -> (s.tab == Tab.Console).also { if (it) c.con0 = s.console.size } },
            TutStep("Tap + Add and pick a piece.", t("console-add")) { s, c -> s.console.size > c.con0 },
            TutStep("Play the piece you added.") { s, c -> played(s, c, 2) },
            TutStep("Back to Pads.", t("mode-pads")) { s, _ -> s.tab == Tab.Pads },
        )),
        Tutorial("granular", "Synths", "Granular from your sounds", "Turn any sound in your mess into a cloud of grains.", listOf(
            TutStep("Tap SYNTH.", t("tab-Synth")) { s, _ -> s.tab == Tab.Synth },
            TutStep("Tap On empty pad.", t("synth-new-empty")) { s, _ -> s.synthOn(s.project.sel) != null },
            TutStep("Tap Granular at the top.", t("synthkind-grain")) { s, c -> (s.synthOn(s.project.sel)?.kind == Synth.GRAIN).also { if (it) { c.num0 = s.synthOn(s.project.sel)!!.get("pos"); c.xy0 = s.synthOn(s.project.sel)!!.src } } },
            TutStep("Under Grains of, pick a different sound to read.", t("grain-src")) { s, c -> (s.synthOn(s.project.sel)?.src ?: c.xy0) != c.xy0 },
            TutStep("Hold a key and drag Position: you move through the sound.", t("knob-pos")) { s, c -> Math.abs((s.synthOn(s.project.sel)?.get("pos") ?: 0.0) - c.num0) > .05 },
        )),
        Tutorial("acid", "Synths", "Acid slides", "Notes that touch in Roll slide into each other on an Acid synth.", listOf(
            TutStep("Tap SYNTH, then On empty pad.", t("tab-Synth")) { s, _ -> s.synthOn(s.project.sel) != null },
            TutStep("Pick Acid.", t("synthmode-2")) { s, _ -> s.synthOn(s.project.sel)?.get("mode") == 2.0 },
            TutStep("Tap ROLL and draw a few notes, some touching end to start.", t("tab-Roll")) { s, c -> s.tab == Tab.Roll && s.version >= c.v0 + 3 },
            TutStep("Press Play and listen for the slides.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("favorites", "Sounds", "Favorites", "Star the sounds you love and find them in one place.", listOf(
            TutStep("Press and hold a pad, then tap Star.", t("pad-0")) { s, c -> s.favs.size > c.fav0 },
            TutStep("Tap SOUNDS.", t("tab-Sounds")) { s, _ -> s.tab == Tab.Sounds },
            TutStep("Tap Favorites: your starred sounds. (Unstar from the same pad menu if you like; stars aren't part of the practice mess.)", t("mode-fav")) { s, _ -> s.soundsMode == "fav" },
        )),
        Tutorial("latency", "Help", "Latency and notes", "See what the phone adds to each tap, and send Claude a note.", listOf(
            TutStep("Open the menu (☰) and pick Notes for Claude.", t("menu")) { s, _ -> s.overlay == Overlay.Notes },
            TutStep("Tap Latency.", t("audio-check")) { s, _ -> s.overlay == Overlay.AudioCheck },
            TutStep("Tap the square ten times.", t("check-pad")) { s, c -> played(s, c, 10) },
            closeSheet,
        )),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
