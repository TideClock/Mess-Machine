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
    /** Nov 22: a dock key that sits behind ⋯ on this screen is reached through ⋯. */
    private fun more(s: AppState, tag: String?): String? = if (tag != null && tag in s.dockOverflow && !s.dockMoreOpen) "dock-more" else tag
    private fun t(tag: String?): (AppState) -> String? = { s -> more(s, tag) }
    /**
     * A control that lives on the side dock in Classic. In the New and Side layouts (Nov 21 fix): Hold and Repeat are
     * on the bottom bar, and the rest are in the perform row, which the ring opens first.
     */
    private fun dock(tag: String): (AppState) -> String? = { s ->
        when {
            s.layout == "classic" -> tag
            tag == "dock-hold" -> more(s, "hold-toggle")
            tag == "dock-repeat" -> more(s, "repeat-toggle")
            !s.performOpen -> "perform-handle"
            else -> tag
        }
    }
    private fun editOpen(s: AppState) = s.padMenu >= 0 || s.tray?.startsWith("edit:") == true
    private fun played(s: AppState, c: TutCtx, n: Int = 1) = s.engine.triggers >= c.trig0 + n
    private fun selKey(s: AppState) = s.project.kit[s.project.sel]
    private fun selEdit(s: AppState) = s.project.edit(selKey(s))
    private fun cut(s: AppState) = s.synthOn(s.project.sel)?.get("cut") ?: 0.0

    // Nov 22: the Sample key left the Play page; the Sample sheet opens from the pad's Edit tray
    private fun toSample(s: AppState): String? = when {
        s.overlay is Overlay.Doctor -> null
        s.tray?.startsWith("edit:") == true -> "menu-doctor"
        s.menuArmed -> "pad-${s.project.sel % com.tideclock.messmachine.engine.Project.BANK}"
        else -> "open-padmenu"
    }
    private val openSample = TutStep("Tap Edit, then the pad, then Sample in its tray. That is the pad's Sample sheet.", { s -> toSample(s) }) { s, _ -> s.overlay is Overlay.Doctor }
    private val closeSheet = TutStep("Take the sheet off: tap the screw in its corner.", t("done")) { s, _ -> s.overlay == null }

    val all: List<Tutorial> = listOf(
        Tutorial("pads", "Start here", "Pads and banks", "Play pads, switch banks, find a pad's menu.", listOf(
            TutStep("Tap a few pads.", t("pad-0")) { s, c -> played(s, c, 3) },
            TutStep("Tap 2 under BANK: a second set of 16 pads.", t("bank-1")) { s, _ -> s.bank == 1 },
            TutStep("Back to bank 1.", t("bank-0")) { s, _ -> s.bank == 0 },
            TutStep("Tap Edit on the bar at the bottom, then tap a pad: its Edit tray opens instead of it playing. Edit, then tap works on strips and perform row buttons too. A flick up off a pad does the same.", { s -> if (s.menuArmed) "pad-0" else "open-padmenu" }) { s, _ -> editOpen(s) },
            TutStep("Close it: the screw in its corner, or Back.", { s -> if (s.tray != null) "tray-done" else null }) { s, _ -> !editOpen(s) },
        )),
        Tutorial("hold", "Playing", "Hold", "Latch pads so they keep playing while your hands do something else.", listOf(
            TutStep("Tap Hold (on the bottom bar; H on the side dock in Classic) to turn it on.", dock("dock-hold")) { s, _ -> s.holdOn },
            TutStep("Tap a pad. It keeps playing on its own, and an H shows in its corner.", t("pad-2")) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap a second pad. It holds too: Hold keeps as many as the number under H (3 to start; at Last, the newest takes over).", t("pad-3")) { s, c -> played(s, c, 1) },
            TutStep("Tap each held pad again to let them go.") { s, _ -> s.latched.isEmpty() },
            TutStep("Tap Hold again to turn it off.", dock("dock-hold")) { s, _ -> !s.holdOn },
        )),
        Tutorial("beat", "Playing", "On the beat", "A held pad that starts again on every beat, in time.", listOf(
            openSample,
            TutStep("Under When it is held, pick On the beat.", t("held-beat")) { s, _ -> selEdit(s).held == "beat" },
            TutStep("Pick 2 beats.", t("beat-2")) { s, _ -> selEdit(s).beatN == 2 },
            closeSheet,
            TutStep("Turn Hold on.", dock("dock-hold")) { s, _ -> s.holdOn },
            TutStep("Tap the selected pad. It starts again every 2 beats, in time.", { s -> "pad-${s.project.sel % Project.BANK}" }) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap it again, then turn Hold off.", dock("dock-hold")) { s, _ -> s.latched.isEmpty() && !s.holdOn },
        )),
        Tutorial("repeat", "Playing", "Note repeat", "Hold a pad and it repeats in time; pick how the repeats sound.", listOf(
            TutStep("Turn Repeat on (on the bottom bar; RPT on the side dock in Classic).", dock("dock-repeat")) { s, _ -> s.live.nr },
            TutStep("Press and hold a pad: it repeats at Repeat's rate.", t("pad-0")) { s, c -> played(s, c, 4) },
            TutStep("Open the perform row and tap the rate under RPT, then pick Flow (hold and pulse).", dock("dock-rate")) { s, _ -> s.live.nrStyle == "flow" },
            TutStep("Hold a pad again: one note that breathes with the rate.", t("pad-0")) { s, c -> played(s, c, 1) },
            TutStep("Tap the rate, pick Cut, then turn Repeat off.", { st -> if (st.live.nrStyle == "cut") dock("dock-repeat")(st) else dock("dock-rate")(st) }) { s, _ -> s.live.nrStyle == "cut" && !s.live.nr },
        )),
        Tutorial("padiano", "Playing", "Padiano", "Any pad's sound as a keyboard, dropped right under its row.", listOf(
            TutStep("Tap the first pad, then Pack on the bar at the bottom: its FannyPack opens with the Padiano in it.", { s -> if (s.project.sel == 0) "pack-toggle" else "pad-0" }) { s, _ -> s.keyboards.isNotEmpty() },
            TutStep("Play some keys. In key only keeps every key in the mess's key.", { s -> s.keyboards.firstOrNull()?.let { "piano-keys-$it" } }) { s, c -> played(s, c, 4) },
            TutStep("Tap Pack on the bottom bar to zip the pack shut.", t("pack-toggle")) { s, _ -> s.keyboards.isEmpty() },
        )),
        Tutorial("sphere", "Playing", "XY sphere", "Draw a circle on a pad and it becomes a ball you push around to bend the sound.", listOf(
            TutStep("Press a pad and, without lifting, draw a small circle on it.", t("pad-0")) { s, c -> if (s.sphere != null) c.seen.add("sphere"); "sphere" in c.seen },
            TutStep("Keep your finger down and push the ball around. Lift to let go.") { s, c -> "sphere" in c.seen && s.sphere == null },
        )),
        Tutorial("grain", "Samples", "Stretch or Grain", "Change a sound's pitch like tape, or keep its length.", listOf(
            openSample,
            TutStep("Under PITCH, pick Stretch: higher notes play faster and shorter, like tape.", t("pitch-stretch")) { s, _ -> selEdit(s).pitch == "stretch" },
            closeSheet,
            TutStep("Tap Pack on the bottom bar: the sound's Padiano opens under its row.", t("pack-toggle")) { s, _ -> s.project.sel in s.keyboards },
            TutStep("Play a low key and a high one: the high one is shorter.", { s -> "piano-keys-${s.project.sel}" }) { s, c -> played(s, c, 2) },
            TutStep("Open Sample again and pick Grain.", { s -> if (s.overlay == null) toSample(s) else "pitch-grain" }) { s, _ -> selEdit(s).pitch == "grain" },
            TutStep("Close it and play low and high again: same length now.", { s -> if (s.overlay != null) "done" else "piano-keys-${s.project.sel}" }) { s, c -> s.overlay == null && played(s, c, 2) },
            TutStep("Tap Pack to zip it shut.", t("pack-toggle")) { s, _ -> s.project.sel !in s.keyboards },
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
            TutStep("Under Put it on a pad, tap Empty pad: the synth you see lands there.", t("synth-new-empty")) { s, c -> (s.synthOn(s.project.sel) != null).also { if (it) c.num0 = cut(s) } },
            TutStep("Play the keyboard under the knobs.", { s -> "piano-keys-${s.project.sel}" }) { s, c -> played(s, c, 2) },
            TutStep("Drag the Cutoff knob up or down. Hold a key while you do it.", t("knob-cut")) { s, c -> kotlin.math.abs(cut(s) - c.num0) > c.num0 * .15 },
            TutStep("Double tap the knob to put it back.", t("knob-cut")) { s, _ -> kotlin.math.abs(cut(s) - 2400) < 1 },
            TutStep("Pick Acid, then play the keys: one voice that slides.", t("synthmode-2")) { s, c -> s.synthOn(s.project.sel)?.get("mode") == 2.0 && played(s, c, 1) },
            TutStep("Try three other kinds at the top (FM, Wavetable, Granular, Drums, Pluck).", t("synthkind-chips")) { s, c ->
                s.synthOn(s.project.sel)?.kind?.id?.let { c.seen.add(it) }; c.seen.count { it != "analog" } >= 3
            },
        )),
        Tutorial("steps", "Writing", "Steps", "Program a beat on the step grid and play it.", listOf(
            TutStep("Tap STEPS.", t("tab-Steps")) { s, _ -> s.tab == Tab.Steps },
            TutStep("Tap a few steps on the first row.", t("steps-0")) { s, c -> s.version >= c.v0 + 3 },
            TutStep("Press Play.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("roll", "Writing", "Roll", "Draw notes with lengths for any pad, synths included.", listOf(
            TutStep("Tap SEQ, then Roll at the top.", { s -> if (s.tab == Tab.Steps) "seq-roll" else "tab-Steps" }) { s, _ -> s.tab == Tab.Roll },
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
        Tutorial("filter", "Playing", "The filter strip", "Sweep a filter over the whole mix, or one pad.", listOf(
            TutStep("Tap a pad, then drag the filter in the perform row (the side dock in Classic) down.", dock("dock-cut")) { s, _ -> s.dockCut < .8f },
            TutStep("Let go: it springs back open.", dock("dock-cut")) { s, _ -> s.dockCut > .95f },
        )),
        Tutorial("lock", "Playing", "Lock", "Lock the screen so only pads play: no menus, no accidents mid-set.", listOf(
            TutStep("Tap the lock in the perform row (the side dock in Classic).", dock("dock-lock")) { s, _ -> s.locked },
            TutStep("Play some pads. Tabs and menus stay shut while it's locked.", t("pad-1")) { s, c -> played(s, c, 3) },
            TutStep("Unlock it the same way.", dock("dock-lock")) { s, _ -> !s.locked },
        )),
        Tutorial("chords", "Playing", "Chords and key", "Pick the mess's key and scale, and play chords from one finger.", listOf(
            TutStep("Tap Pack on the bottom bar: the sound's Padiano opens under its row.", t("pack-toggle")) { s, _ -> s.project.sel in s.keyboards },
            TutStep("Tap the key button on the bar at the bottom, then pick a different scale.", { s -> if (s.keysBarOpen) "pick-scale" else "keysbar-open" }) { s, _ -> s.project.scale != "Minor" },
            TutStep("Pick a different root note.", { s -> if (s.keysBarOpen) "pick-root" else "keysbar-open" }) { s, _ -> s.project.root != 0 },
            TutStep("Tap Chords on the bottom bar: each key now plays a chord in the key. The key button picks which chords.", t("chords-toggle")) { s, _ -> s.chordMode != "off" },
            TutStep("Play a few keys.", { s -> "piano-keys-${s.project.sel}" }) { s, c -> played(s, c, 3) },
            TutStep("Tap Pack to zip it shut.", t("pack-toggle")) { s, _ -> s.project.sel !in s.keyboards },
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
            TutStep("Tap the loop readout at the top, then Song.", { s -> if (s.loopStripOpen) "loop-song" else "loop-open" }) { s, _ -> s.songOpen },
            TutStep("Tap a few bars to fill them with the loop.", t("bar-0")) { s, _ -> s.project.songLen() > 0 },
            TutStep("Press Play: it plays the song, bar by bar.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("bottle", "Writing", "Bottle it", "Turn the loop you made into one sound on a pad.", listOf(
            TutStep("Tap STEPS and tap a few steps.", t("tab-Steps")) { s, _ -> s.project.pat().used() },
            TutStep("Tap the loop readout at the top, then Bottle it. The loop becomes a sound on a free pad.", { s -> if (s.loopStripOpen) "bottle" else "loop-open" }) { s, c -> s.project.kit.count { it.isNotEmpty() } > c.kit0 },
            TutStep("Tap PLAY and play the new pad.", t("tab-Pads")) { s, c -> s.tab == Tab.Pads && played(s, c, 1) },
        )),
        Tutorial("mic", "Sounds", "Record onto a pad", "Any empty pad is a record button: catch a sound with the mic.", listOf(
            TutStep("Tap 2 under BANK: these pads are empty.", t("bank-1")) { s, _ -> s.bank == 1 },
            TutStep("Tap the top corner of an empty pad, above the slash, to start recording. The first time, allow the mic.", t("pad-0")) { s, _ -> s.micOn },
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
            TutStep("Tap Edit at the bottom, then the first pad, then XY setup.", { s -> if (s.padMenu >= 0) "menu-xy" else if (s.menuArmed) "pad-0" else "open-padmenu" }) { s, c -> (s.overlay is Overlay.Xy).also { if (it) c.xy0 = s.xyOf(s.project.sel).name } },
            TutStep("Pick a different preset.", t("xy-editor")) { s, c -> s.xyOf(s.project.sel).name != c.xy0 },
            closeSheet,
            TutStep("Draw a circle on that pad and push the ball around: hear the new setup.", t("pad-0")) { s, c -> if (s.sphere != null) c.seen.add("sphere"); "sphere" in c.seen && s.sphere == null },
        )),
        Tutorial("desk", "Playing", "My Desk", "Build your own playing surface from pieces: pads, keys, XY, effects.", listOf(
            TutStep("Open the menu at the top right, then Settings. Pick My Desk under Play layout, then the screw in the corner.", { s -> if (s.overlay == Overlay.Settings) (if (s.tab == Tab.Console) "done" else "layout-desk") else "menu" }) { s, c -> (s.tab == Tab.Console && s.overlay == null).also { if (it) c.con0 = s.console.size } },
            TutStep("Tap + Add and pick a piece.", t("console-add")) { s, c -> s.console.size > c.con0 },
            TutStep("Play the piece you added.") { s, c -> played(s, c, 2) },
            TutStep("Back to the pads: menu, Settings, Play layout, Pads.", { s -> if (s.overlay == Overlay.Settings) (if (s.tab == Tab.Pads) "done" else "layout-pads") else "menu" }) { s, _ -> s.tab == Tab.Pads },
        )),
        Tutorial("granular", "Synths", "Granular from your sounds", "Turn any sound in your mess into a cloud of grains.", listOf(
            TutStep("Tap SYNTH.", t("tab-Synth")) { s, _ -> s.tab == Tab.Synth },
            TutStep("Tap On empty pad.", t("synth-new-empty")) { s, _ -> s.synthOn(s.project.sel) != null },
            TutStep("Tap Granular at the top.", t("synthkind-grain")) { s, c -> (s.synthOn(s.project.sel)?.kind == Synth.GRAIN).also { if (it) { c.num0 = s.synthOn(s.project.sel)!!.get("pos"); c.xy0 = s.synthOn(s.project.sel)!!.src } } },
            TutStep("Under Grains of, pick a different sound to read.", t("grain-src")) { s, c -> (s.synthOn(s.project.sel)?.src ?: c.xy0) != c.xy0 },
            TutStep("Hold a key and drag Position: you move through the sound.", t("knob-pos")) { s, c -> kotlin.math.abs((s.synthOn(s.project.sel)?.get("pos") ?: 0.0) - c.num0) > .05 },
        )),
        Tutorial("acid", "Synths", "Acid slides", "Notes that touch in Roll slide into each other on an Acid synth.", listOf(
            TutStep("Tap SYNTH, then On empty pad.", t("tab-Synth")) { s, _ -> s.synthOn(s.project.sel) != null },
            TutStep("Pick Acid.", t("synthmode-2")) { s, _ -> s.synthOn(s.project.sel)?.get("mode") == 2.0 },
            TutStep("Tap SEQ, then Roll at the top, and draw a few notes, some touching end to start.", { s -> if (s.tab == Tab.Roll) null else if (s.tab == Tab.Steps) "seq-roll" else "tab-Steps" }) { s, c -> s.tab == Tab.Roll && s.version >= c.v0 + 3 },
            TutStep("Press Play and listen for the slides.", t("play")) { s, _ -> s.playing },
            TutStep("Press Stop.", t("stop")) { s, _ -> !s.playing },
        )),
        Tutorial("favorites", "Sounds", "Starred sounds", "Star the sounds you love and find them in one place.", listOf(
            TutStep("Tap Edit at the bottom, then a pad, then Star.", { s -> if (s.padMenu >= 0) "menu-star" else if (s.menuArmed) "pad-0" else "open-padmenu" }) { s, c -> s.favs.size > c.fav0 },
            TutStep("Tap SOUNDS.", t("tab-Sounds")) { s, _ -> s.tab == Tab.Sounds },
            TutStep("Tap Starred: your starred sounds. (Unstar from the same pad menu if you like; stars aren't part of the practice mess.)", t("mode-fav")) { s, _ -> s.soundsMode == "fav" },
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
