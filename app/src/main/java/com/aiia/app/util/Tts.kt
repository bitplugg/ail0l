package com.aiia.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Tts {
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    private val configured = AtomicBoolean(false)

    private fun ensure(context: Context): TextToSpeech? {
        ttsRef.get()?.let { return it }
        synchronized(this) {
            ttsRef.get()?.let { return it }
            val created = TextToSpeech(context.applicationContext) { status ->
                configured.set(status == TextToSpeech.SUCCESS)
            }
            runCatching { created.language = Locale.getDefault() }
            ttsRef.set(created)
            return created
        }
    }

    private fun configure(tts: TextToSpeech) {
        if (configured.get()) return
        val supported = tts.isLanguageAvailable(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE
        runCatching {
            tts.language = if (supported) Locale("ru", "RU") else Locale.getDefault()
            tts.setSpeechRate(1.0f)
        }
        configured.set(true)
    }

    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val clean = text.replace(Regex("[*_`#▶]"), "").trim().take(1200)
        if (clean.isBlank()) return
        val tts = ensure(context) ?: return
        configure(tts)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aiia")
    }

    fun stop() {
        ttsRef.get()?.stop()
    }
}
