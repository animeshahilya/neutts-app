package com.animeshahilya.neutts.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.animeshahilya.neutts.data.AppSettings
import com.animeshahilya.neutts.data.BackboneModel
import com.animeshahilya.neutts.data.ModelCatalog
import com.animeshahilya.neutts.data.ModelInstaller
import java.util.Locale
import kotlin.math.min

/**
 * NeuTTS as a system text-to-speech engine, so any reader app can speak through it.
 *
 * This exists because of how it is used: reading stories, where a pause between sentences is
 * ordinary and the alternative is no long-form reading at all. It is deliberately **not** suitable
 * as a screen-reader engine - generation runs at roughly real time on a phone, and navigation
 * speech needs to start in tens of milliseconds. Choosing this as the system default for TalkBack
 * would make the phone unusable.
 *
 * Three things make it work rather than merely exist:
 *
 *  - **Streaming.** Audio is handed back roughly every half second as it is produced, so a reader
 *    starts speaking almost immediately instead of after the whole passage. Without this the engine
 *    would look frozen for as long as the passage takes to say.
 *  - **Sentence splitting.** The model's context is shared between the reference clip and the
 *    speech, giving a ceiling around 30 seconds. A reader that hands over a whole paragraph would
 *    otherwise be silently truncated; instead it is split and spoken sentence by sentence.
 *  - **Honest availability.** With no model installed the engine reports MISSING_DATA rather than
 *    accepting work and producing silence, so the reader can say something useful.
 */
class NeuTtsService : TextToSpeechService() {

    private lateinit var settings: AppSettings
    private lateinit var installer: ModelInstaller
    private lateinit var catalog: ModelCatalog
    private lateinit var voiceStore: ReferenceVoiceStore
    private lateinit var phonemizer: Phonemizer

    private var engine: NeuTTSEngine? = null
    private var loadedModelId: String? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        settings = AppSettings(this)
        installer = ModelInstaller(this)
        catalog = ModelCatalog.load(this)
        voiceStore = ReferenceVoiceStore(this)
        phonemizer = Phonemizer(this)
        // Must come last: the base class calls onGetLanguage() during its own onCreate.
        super.onCreate()
    }

    override fun onDestroy() {
        releaseEngine()
        super.onDestroy()
    }

    override fun onGetLanguage(): Array<String> {
        val model = chosenModel()
        return arrayOf(model?.lang.orEmpty().ifEmpty { "eng" }, "", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val wanted = threeLetterToTag(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val forLanguage = catalog.backbones.filter { it.lang == wanted }
        if (forLanguage.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        // The language exists in the catalog but nothing is downloaded: MISSING_DATA is exactly
        // this case, and it lets the caller prompt the user to open the app.
        if (forLanguage.none { installer.isInstalled(it) } || !installer.isInstalled(catalog.codec)) {
            return TextToSpeech.LANG_MISSING_DATA
        }
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        stopRequested = true
        engine?.cancel()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val text = request.charSequenceText?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            callback.start(NeuCodec.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val model = chosenModel()
        if (model == null || !installer.isInstalled(model) || !installer.isInstalled(catalog.codec)) {
            Log.w(TAG, "asked to speak with nothing installed")
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        val voice = chosenVoice(model)
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val ready = ensureEngine(model)
        if (ready == null) {
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }

        val emotion = settings.emotion.takeIf { model.emotions.isNotEmpty() }
        callback.start(NeuCodec.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(1024)

        try {
            for (sentence in splitIntoSpeakableParts(text)) {
                if (stopRequested) break
                ready.speakStreaming(sentence, voice, emotion) { chunk ->
                    writePcm(callback, chunk, maxBytes)
                    !stopRequested
                }
            }
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    /** Floats to 16-bit PCM, handed over in pieces the framework will accept. */
    private fun writePcm(callback: SynthesisCallback, samples: FloatArray, maxBytes: Int) {
        val bytes = ByteArray(samples.size * 2)
        var at = 0
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[at++] = (value and 0xFF).toByte()
            bytes[at++] = ((value shr 8) and 0xFF).toByte()
        }
        var offset = 0
        while (offset < bytes.size && !stopRequested) {
            val count = min(maxBytes, bytes.size - offset)
            callback.audioAvailable(bytes, offset, count)
            offset += count
        }
    }

    private fun chosenModel(): BackboneModel? {
        val saved = settings.modelId?.let { id -> catalog.backbones.firstOrNull { it.id == id } }
        // Fall back to anything actually installed rather than to the catalog default, which may
        // well not be on the device.
        return saved?.takeIf { installer.isInstalled(it) }
            ?: catalog.backbones.firstOrNull { installer.isInstalled(it) }
    }

    private fun chosenVoice(model: BackboneModel): ReferenceVoice? {
        val usable = voiceStore.all().filter { model.supportsVoice(it.id) }
        return settings.voiceId?.let { id -> usable.firstOrNull { it.id == id } } ?: usable.firstOrNull()
    }

    private fun ensureEngine(model: BackboneModel): NeuTTSEngine? {
        if (loadedModelId == model.id) engine?.let { return it }
        releaseEngine()
        val loaded = NeuTTSEngine.load(
            phonemizer = phonemizer,
            model = model,
            modelFile = installer.fileFor(model),
            decoderFile = installer.fileForCodec(catalog.codec),
            encoderFile = null,   // cloning is never needed to speak
        ) ?: return null
        engine = loaded
        loadedModelId = model.id
        return loaded
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        loadedModelId = null
    }

    companion object {
        private const val TAG = "NeuTTS.service"

        /** Beyond this many characters a part is split further, to stay inside the context. */
        const val MAX_PART_CHARS = 300

        /** Android hands three-letter ISO codes to a TTS engine; the catalog uses two-letter tags. */
        fun threeLetterToTag(lang: String?): String? = when (lang?.lowercase()) {
            "eng", "en" -> "en"
            "hin", "hi" -> "hi"
            else -> null
        }

        /**
         * Splits text into parts that comfortably fit the model's context.
         *
         * Sentence boundaries first, because a break there is inaudible; only if a single sentence
         * is still too long is it cut at a comma or a space. Cutting mid-word would be heard.
         */
        @JvmStatic
        fun splitIntoSpeakableParts(text: String): List<String> {
            val sentences = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
            val parts = ArrayList<String>()
            for (sentence in sentences) {
                if (sentence.length <= MAX_PART_CHARS) {
                    parts.add(sentence)
                    continue
                }
                var rest = sentence
                while (rest.length > MAX_PART_CHARS) {
                    val window = rest.substring(0, MAX_PART_CHARS)
                    val cut = window.lastIndexOf(',').takeIf { it > MAX_PART_CHARS / 2 }
                        ?: window.lastIndexOf(' ').takeIf { it > 0 }
                        ?: MAX_PART_CHARS - 1
                    parts.add(rest.substring(0, cut + 1).trim())
                    rest = rest.substring(cut + 1).trim()
                }
                if (rest.isNotEmpty()) parts.add(rest)
            }
            return parts.ifEmpty { listOf(text) }
        }

        /** Keeps the terminator with the sentence it ends, so the model still hears the question
         * mark that shapes the intonation. */
        val SENTENCE_END = Regex("(?<=[.!?।])\\s+")
    }
}
