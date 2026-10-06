package com.pocketai.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * System TTS wrapper restricted to installed voices that declare no network requirement.
 * The app itself never sends speech text to a remote API.
 */
internal class LocalSpeech(
    context: Context,
    private val onActiveMessageChanged: (String?) -> Unit,
    private val onError: (String) -> Unit,
) : TextToSpeech.OnInitListener {
    private data class Request(val messageId: String, val text: String)

    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var destroyed = false
    private var pending: Request? = null
    private var activeMessageId: String? = null
    private var finalUtteranceId: String? = null

    override fun onInit(status: Int) {
        if (destroyed) return
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            fail("Le moteur de lecture vocale Android n’a pas pu démarrer.")
            return
        }

        val locale = Locale.getDefault()
        val offline = runCatching { tts.voices.orEmpty().filterNot { it.isNetworkConnectionRequired } }
            .getOrDefault(emptyList())
        val voice = offline.sortedWith(
            compareByDescending<android.speech.tts.Voice> { it.locale.language == locale.language }
                .thenByDescending { it.locale.country == locale.country }
                .thenByDescending { it.quality },
        ).firstOrNull()

        if (voice == null) {
            fail("Aucune voix hors ligne n’est installée. Installe une voix locale dans les réglages de synthèse vocale Android.")
            return
        }

        if (tts.voice != voice && tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            fail("La voix hors ligne sélectionnée n’a pas pu être activée.")
            return
        }

        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == finalUtteranceId) clearActive()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == finalUtteranceId) {
                    clearActive()
                    onError("La lecture vocale a été interrompue par le moteur Android.")
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == finalUtteranceId) {
                    clearActive()
                    onError("La lecture vocale a échoué (code $errorCode).")
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == finalUtteranceId || interrupted) clearActive()
            }
        })

        ready = true
        pending?.also {
            pending = null
            speakNow(it)
        }
    }

    fun toggle(messageId: String, rawText: String) {
        if (destroyed) return
        if (activeMessageId == messageId) {
            stop()
            return
        }
        val text = SpeechText.prepare(rawText)
        if (text.isBlank()) {
            onError("Cette réponse ne contient pas de texte à lire.")
            return
        }
        val request = Request(messageId, text)
        if (!ready) {
            pending = request
            return
        }
        speakNow(request)
    }

    private fun speakNow(request: Request) {
        val tts = engine ?: return
        val maximum = (TextToSpeech.getMaxSpeechInputLength() - 128).coerceAtLeast(512)
        val chunks = SpeechText.chunks(request.text, maximum)
        if (chunks.isEmpty()) return

        tts.stop()
        activeMessageId = request.messageId
        onActiveMessageChanged(request.messageId)
        finalUtteranceId = "${request.messageId}:${chunks.lastIndex}"

        chunks.forEachIndexed { index, chunk ->
            val id = "${request.messageId}:$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts.speak(chunk, mode, null, id)
            if (result != TextToSpeech.SUCCESS) {
                stop()
                onError("Le moteur de lecture vocale a refusé un segment de texte.")
                return
            }
        }
    }

    fun stop() {
        pending = null
        engine?.stop()
        clearActive()
    }

    fun shutdown() {
        if (destroyed) return
        destroyed = true
        pending = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        clearActive()
    }

    private fun clearActive() {
        activeMessageId = null
        finalUtteranceId = null
        onActiveMessageChanged(null)
    }

    private fun fail(message: String) {
        ready = false
        pending = null
        clearActive()
        onError(message)
    }
}
