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
        Tutorial("hold", "Playing", "Hold", "Latch notes so they keep playing while your hands do something else.", listOf(
            TutStep("Tap Keys at the top. The pads become notes of the selected sound.", t("mode-keys")) { s, _ -> s.keysMode },
            TutStep("Tap H in the dock to turn Hold on.", t("dock-hold")) { s, _ -> s.holdOn },
            TutStep("Tap a key. It keeps playing after you let go.", t("pad-2")) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap the same key again to let it go.", t("pad-2")) { s, _ -> s.latched.isEmpty() },
            TutStep("Turn Hold off with H, then tap Pads.", t("dock-hold")) { s, _ -> !s.holdOn && !s.keysMode },
        )),
        Tutorial("beat", "Playing", "On the beat", "A held pad that starts again on every beat, in time.", listOf(
            openSample,
            TutStep("Under When it is held, pick On the beat.", t("held-beat")) { s, _ -> selEdit(s).held == "beat" },
            TutStep("Pick 2 beats.", t("beat-2")) { s, _ -> selEdit(s).beatN == 2 },
            closeSheet,
            TutStep("Tap Keys at the top, then H in the dock to turn Hold on.", t("mode-keys")) { s, _ -> s.keysMode && s.holdOn },
            TutStep("Tap a key. It starts again every 2 beats, in time.", t("pad-4")) { s, _ -> s.latched.isNotEmpty() },
            TutStep("Tap it again, turn Hold off and go back to Pads.", t("dock-hold")) { s, _ -> s.latched.isEmpty() && !s.holdOn && !s.keysMode },
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
        Tutorial("latency", "Help", "Latency and notes", "See what the phone adds to each tap, and send Claude a note.", listOf(
            TutStep("Open the menu (☰) and pick Notes for Claude.", t("menu")) { s, _ -> s.overlay == Overlay.Notes },
            TutStep("Tap Latency.", t("audio-check")) { s, _ -> s.overlay == Overlay.AudioCheck },
            TutStep("Tap the square ten times.", t("check-pad")) { s, c -> played(s, c, 10) },
            closeSheet,
        )),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
