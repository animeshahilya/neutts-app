package com.animeshahilya.neutts.tts

import android.util.Log
import com.animeshahilya.neutts.data.BackboneModel
import java.io.Closeable
import java.io.File

/**
 * Text in, audio out - the whole pipeline in one place.
 *
 * Four stages, in order: phonemize the words, build a prompt that carries both the phonemes and the
 * chosen voice's reference codes, generate speech tokens with the backbone, decode those to PCM.
 *
 * The prompt built here mirrors `_apply_chat_template` in the upstream Python implementation
 * exactly. It is written as a string with the special tokens inline rather than by splicing token
 * ids, which is equivalent because every special token is a hard boundary for the BPE - and it was
 * checked that way against the desktop pipeline before the samples this app is designed around were
 * generated. Any drift here does not throw; it degrades the voice.
 */
class NeuTTSEngine private constructor(
    private val backbone: NeuTTSBackbone,
    private val codec: NeuCodec,
    private val phonemizer: Phonemizer,
    private val model: BackboneModel,
) : Closeable {

    private val espeakVoice: String get() = model.espeakVoice

    val canClone: Boolean get() = codec.canClone

    /** Cached because it is the generation loop's stop condition, looked up once per model. */
    private val speechEndToken: Int = backbone.tokenize(SPEECH_GENERATION_END).let {
        require(it.size == 1) { "the model's vocabulary has no $SPEECH_GENERATION_END token" }
        it.first()
    }

    /**
     * The vocabulary id of `<|speech_0|>`.
     *
     * The speech tokens are contiguous, so an id is a subtraction rather than a lookup. That
     * matters: this runs once per generated token, and the first version asked the native side to
     * decode each token to a string and then parsed the number back out of it - a JNI transition,
     * a string allocation and a parse, fifty times a second of audio, to recover an integer that
     * was already there. The base differs per model (151671 in Air, 128262 in Nano), so it is read
     * from the model rather than hardcoded.
     */
    private val speechTokenBase: Int = backbone.tokenize(SPEECH_PREFIX + "0" + SPEECH_SUFFIX).let {
        require(it.size == 1) { "the model's vocabulary has no speech tokens" }
        it.first()
    }

    /** Phonemes for the reference clip, which never change for a voice but were being recomputed
     * for every utterance - noticeable when a reader app sends a sentence at a time. */
    private var cachedTranscriptVoiceId: String? = null
    private var cachedTranscriptPhonemes: String = ""

    /**
     * Speaks [text] in [voice], returning 24 kHz mono PCM.
     *
     * Returns null when the model produced no speech tokens at all - a real outcome on an
     * interrupted or cancelled generation, and better handled by the caller than by an exception.
     */
    fun speak(
        text: String,
        voice: ReferenceVoice,
        emotion: String? = null,
        maxNewTokens: Int = NeuTTSBackbone.DEFAULT_MAX_NEW_TOKENS,
    ): FloatArray? {
        val prompt = buildPrompt(text, voice, emotion)
        val promptTokens = backbone.tokenize(prompt)
        Log.i(TAG, "prompt ${promptTokens.size} tokens for ${voice.id}")

        val generated = backbone.generateAll(promptTokens, speechEndToken, maxNewTokens)
        val speechTokens = generated.asIterable().mapNotNull(::speechIdOf).toIntArray()
        if (speechTokens.isEmpty()) {
            Log.w(TAG, "no speech tokens produced")
            return null
        }
        return codec.decode(speechTokens)
    }

    /**
     * Speaks [text], handing audio to [onAudio] roughly every half second instead of once at the
     * end.
     *
     * This is what makes the app usable for long passages: generation runs at around real time, so
     * waiting for a whole paragraph means waiting as long as it takes to say it. Returning false
     * from [onAudio] stops both playback and generation.
     *
     * Returns the total number of samples produced, so a caller can report the length without
     * keeping the audio.
     */
    fun speakStreaming(
        text: String,
        voice: ReferenceVoice,
        emotion: String? = null,
        maxNewTokens: Int = NeuTTSBackbone.DEFAULT_MAX_NEW_TOKENS,
        onAudio: (FloatArray) -> Boolean,
    ): Int {
        val promptTokens = backbone.tokenize(buildPrompt(text, voice, emotion))
        val decoder = StreamingDecoder(codec, voice.codesArray)
        var samples = 0
        var keepGoing = true

        backbone.generate(promptTokens, speechEndToken, maxNewTokens) { token ->
            val id = speechIdOf(token)
            if (id != null) {
                val chunk = decoder.offer(id)
                if (chunk != null && chunk.isNotEmpty()) {
                    samples += chunk.size
                    keepGoing = onAudio(chunk)
                }
            }
            keepGoing
        }

        if (keepGoing) {
            decoder.drain()?.takeIf { it.isNotEmpty() }?.let {
                samples += it.size
                onAudio(it)
            }
        }
        return samples
    }

    fun cancel() = backbone.cancel()

    /**
     * Encodes a 16 kHz recording into a new reference voice.
     *
     * [transcript] must be what was actually said - see [ReferenceVoice].
     */
    fun cloneVoice(id: String, name: String, samples16k: FloatArray, transcript: String): ReferenceVoice =
        ReferenceVoice(
            id = id,
            name = name,
            transcript = transcript.trim(),
            codes = codec.encode(samples16k).toList(),
            isUserRecorded = true,
        )

    /**
     * The reference transcript and the text to speak are phonemized and concatenated as one string
     * before tokenizing, which is what upstream does - encoding them separately would let the BPE
     * resolve the join differently.
     */
    private fun buildPrompt(text: String, voice: ReferenceVoice, emotion: String?): String {
        val codes = buildString {
            voice.codes.forEach { append("<|speech_").append(it).append("|>") }
        }
        if (!model.usesPhonemes) {
            // The BPE models (2E) take words rather than phonemes, and carry no "user:"/"assistant:"
            // framing - upstream's template for them is only TEXT_REPLACE + SPEECH_REPLACE. An
            // emotion tag sits between the reference transcript and the new text; neutral is the
            // absence of a tag rather than a tag of its own.
            val tag = emotion
                ?.takeIf { it.isNotBlank() && !it.equals("neutral", ignoreCase = true) }
                ?.let { "<|" + it.uppercase() + "|>" }
                ?: " "
            val body = normalizeForBpe(voice.transcript) + tag + normalizeForBpe(text)
            return TEXT_PROMPT_START + body + TEXT_PROMPT_END + SPEECH_GENERATION_START + codes
        }
        val phonemes = referencePhonemes(voice) + " " + phonemizer.phonemize(text, espeakVoice)
        return "user: Convert the text to speech:" +
            TEXT_PROMPT_START + phonemes + TEXT_PROMPT_END +
            "\nassistant:" +
            SPEECH_GENERATION_START + codes
    }

    /** NFKC, matching upstream's _normalize_text, so curly quotes and similar tokenize the way the
     * model saw them during training. */
    private fun normalizeForBpe(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)

    /** `<|speech_1234|>` to 1234, or null for anything else the model happened to emit. */
    private fun speechIdOf(token: Int): Int? {
        val id = token - speechTokenBase
        return if (id in 0 until SPEECH_TOKEN_COUNT) id else null
    }

    /** Phonemizes the reference transcript once per voice rather than once per utterance. */
    private fun referencePhonemes(voice: ReferenceVoice): String {
        if (cachedTranscriptVoiceId != voice.id) {
            cachedTranscriptPhonemes = phonemizer.phonemize(voice.transcript, espeakVoice)
            cachedTranscriptVoiceId = voice.id
        }
        return cachedTranscriptPhonemes
    }

    override fun close() {
        backbone.close()
        codec.close()
    }

    companion object {
        private const val TAG = "NeuTTS.engine"
        private const val TEXT_PROMPT_START = "<|TEXT_PROMPT_START|>"
        private const val TEXT_PROMPT_END = "<|TEXT_PROMPT_END|>"
        private const val SPEECH_GENERATION_START = "<|SPEECH_GENERATION_START|>"
        private const val SPEECH_GENERATION_END = "<|SPEECH_GENERATION_END|>"
        private const val SPEECH_PREFIX = "<|speech_"
        private const val SPEECH_SUFFIX = "|>"

        /** NeuCodec has a single codebook of 65536 entries, so that is how many speech tokens a
         * NeuTTS vocabulary carries. */
        private const val SPEECH_TOKEN_COUNT = 65536

        /** Builds an engine around parts the caller already owns - used by benchmarks that need
         * to vary how the backbone was loaded. */
        fun attach(
            backbone: NeuTTSBackbone,
            codec: NeuCodec,
            phonemizer: Phonemizer,
            model: BackboneModel,
        ): NeuTTSEngine = NeuTTSEngine(backbone, codec, phonemizer, model)

        /**
         * Loads everything the engine needs. Returns null if the backbone cannot be loaded, which
         * on a phone almost always means an interrupted download rather than a broken model.
         */
        fun load(
            phonemizer: Phonemizer,
            model: BackboneModel,
            modelFile: File,
            decoderFile: File,
            encoderFile: File?,
            useNnapi: Boolean = false,
        ): NeuTTSEngine? {
            val backbone = NeuTTSBackbone.load(modelFile) ?: return null
            return runCatching {
                NeuTTSEngine(backbone, NeuCodec.open(decoderFile, encoderFile, useNnapi), phonemizer, model)
            }.getOrElse {
                Log.e(TAG, "could not open the codec", it)
                backbone.close()
                null
            }
        }
    }
}
