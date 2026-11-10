package com.tideclock.messmachine

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * MIDI over USB (MM-12): plug in a pad controller or a keyboard and play. Notes 36 to 51 (the usual pad
 * controller layout, C1 to D#2) hit pads 1 to 16 of the bank on screen; every other note plays the selected
 * pad as a key, with middle C (60) at the sound's own pitch. Velocity is not used yet: every hit is full.
 */
class MidiIn(private val ctx: Context, private val state: AppState) {
    private val main = Handler(Looper.getMainLooper())
    private var mm: MidiManager? = null
    private val open = HashMap<Int, Pair<MidiDevice, MidiOutputPort>>()
    private val cb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = connect(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            open.remove(info.id)?.let { (d, p) -> try { p.close(); d.close() } catch (_: Exception) {} }
            state.say("MIDI: ${name(info)} unplugged.")
        }
    }

    fun start() {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        mm = m
        @Suppress("DEPRECATION") for (info in m.devices) connect(info)
        @Suppress("DEPRECATION") m.registerDeviceCallback(cb, main)
    }

    fun stop() {
        mm?.unregisterDeviceCallback(cb)
        for ((d, p) in open.values) try { p.close(); d.close() } catch (_: Exception) {}
        open.clear()
        mm = null
    }

    private fun name(info: MidiDeviceInfo) = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "a MIDI device"

    private fun connect(info: MidiDeviceInfo) {
        // a device's output ports are what it sends to us
        if (info.outputPortCount == 0 || open.containsKey(info.id)) return
        mm?.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            val port = dev.openOutputPort(0) ?: run { dev.close(); return@openDevice }
            port.connect(Parser())
            open[info.id] = dev to port
            state.say("MIDI: ${name(info)} plugged in. Notes 36 to 51 hit pads 1 to 16; the rest play the selected pad as keys.")
        }, main)
    }

    /** Reads note on and note off from the bytes a device sends, and hands them to the screen thread. */
    private inner class Parser : MidiReceiver() {
        private var status = 0
        private var d1 = -1
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (k in offset until offset + count) {
                val b = msg[k].toInt() and 0xFF
                if (b >= 0xF8) continue // clock and other real-time bytes can arrive anywhere
                if (b >= 0x80) { status = b; d1 = -1; continue }
                if (d1 < 0) { d1 = b; continue }
                val note = d1; val vel = b; d1 = -1 // running status: the next pair uses the same status
                when (status and 0xF0) {
                    0x90 -> if (vel > 0) main.post { state.midiDown(note) } else main.post { state.midiUp(note) }
                    0x80 -> main.post { state.midiUp(note) }
                }
            }
        }
    }
}
