package com.tideclock.messmachine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The quick note (note #20): tap the bug, talk, tap it again. Android's recognizer stops by itself after a pause, so
 * this starts it again until the second tap and joins what it heard. Nothing is sent: the note waits on the phone.
 */
class VoiceNote(private val app: Context, private val heard: (String) -> Unit, private val done: (text: String, problem: String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private val said = StringBuilder()
    private var partial = ""
    private var on = false
    private var finished = true

    val listening get() = on

    private fun defaultOk() = try { SpeechRecognizer.isRecognitionAvailable(app) } catch (_: Exception) { false }
    private fun onDeviceOk() = android.os.Build.VERSION.SDK_INT >= 33 && try { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) } catch (_: Exception) { false }
    /** The phone's own speech service, or Android 13's on-device one when that is all there is (or the first one fails). */
    private var onDevice = false

    fun available() = defaultOk() || onDeviceOk()

    fun start(): Boolean {
        if (!available()) return false
        said.clear(); partial = ""; on = true; finished = false
        onDevice = !defaultOk()
        listen()
        return true
    }

    /** The second tap: let the recognizer hand over its last words, then finish. */
    fun stop() {
        if (finished) return
        on = false
        try { sr?.stopListening() } catch (_: Exception) {}
        main.postDelayed({ finish(null) }, 1800)
    }

    fun cancel() {
        on = false; finished = true
        main.removeCallbacksAndMessages(null)
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }

    private fun text() = (said.toString() + " " + partial).trim()

    private fun add(s: String?) { if (!s.isNullOrBlank()) { if (said.isNotEmpty()) said.append(' '); said.append(s.trim()) } }

    private fun listen() {
        try { sr?.destroy() } catch (_: Exception) {}
        val r = if (onDevice && android.os.Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                add(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial)
                partial = ""
                heard(text())
                if (on) listen() else finish(null)
            }
            override fun onPartialResults(b: Bundle?) {
                val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) { partial = p; heard(text()) }
            }
            override fun onError(e: Int) {
                add(partial); partial = ""
                when {
                    !on -> finish(null)
                    e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()
                    e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ if (on) listen() }, 250)
                    e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { on = false; finish("The mic needs permission.") }
                    // the phone's service failed outright: try Android's on-device one once before giving up
                    !onDevice && onDeviceOk() && e != SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { onDevice = true; main.postDelayed({ if (on) listen() }, 150) }
                    e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> { on = false; finish("Speech needs a connection on this phone.") }
                    else -> main.postDelayed({ if (on) listen() }, 250)
                }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        try { r.startListening(i) } catch (_: Exception) { on = false; finish("Speech would not start on this phone.") }
    }

    private fun finish(problem: String?) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        add(partial); partial = ""
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
        done(said.toString().trim(), problem)
    }
}
