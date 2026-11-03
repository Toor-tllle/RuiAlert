package com.rui.nervlive

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** On-device TTS readouts for the most important alerts. User-toggleable, default on. */
object Speaker {
    private const val PREFS = "speech"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) {
            try {
                tts?.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun warmUp(context: Context) {
        ensureInit(context)
    }

    private fun ensureInit(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            val app = context.applicationContext
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        val r = tts?.setLanguage(Locale.JAPANESE)
                        ready = r != TextToSpeech.LANG_MISSING_DATA &&
                            r != TextToSpeech.LANG_NOT_SUPPORTED
                        // Attributes are set per utterance in speak(); alarm is the default.
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    } catch (e: Exception) {
                        ready = false
                    }
                }
            }
        }
    }

    /**
     * Speaks [text] after [delayMs] of silence, so a notification chime can finish first.
     * Critical readouts (EEW warning, tsunami) use the alarm stream and can break
     * through silent mode; non-critical ones (EEW forecast, confirmed shindo) use
     * the notification stream and respect the ringer mode.
     * No-op when speech is disabled or no Japanese TTS voice is available.
     */
    fun speak(context: Context, text: String, delayMs: Long = 0, critical: Boolean = true) {
        if (!isEnabled(context)) return
        ensureInit(context)
        val engine = tts ?: return
        if (!ready) return
        try {
            val usage = if (critical) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_NOTIFICATION
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            if (delayMs > 0) {
                engine.playSilentUtterance(delayMs, TextToSpeech.QUEUE_ADD, "rui_pause")
            }
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "rui_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            // TTS is best effort; the chime and notification already fired.
        }
    }
}
