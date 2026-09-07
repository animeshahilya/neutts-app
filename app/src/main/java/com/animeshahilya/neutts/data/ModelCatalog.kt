package com.animeshahilya.neutts.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The models this app can install, read from the bundled assets/models.json.
 *
 * Nothing here ships inside the APK - the app is a few megabytes and every model is fetched on
 * demand, the same arrangement SherpaVoices uses for its voice catalog. That is not only about
 * download size: the smallest entry is 273 MB and the largest 765 MB, and a user who only ever
 * wants English should never carry Korean weights around.
 *
 * Every repository referenced here was checked to be **ungated** on Hugging Face, so installing a
 * model needs no account and no token. Neuphonic's own `neutts-air` repositories are gated (they
 * answer 401 to an anonymous request), which is why the English entries point at community
 * re-quantisations of the same Apache-2.0 weights instead.
 */
@Serializable
data class ModelCatalog(
    val formatVersion: Int = 1,
    val codec: CodecModel,
    val encoder: EncoderModel,
    val backbones: List<BackboneModel>,
) {
    /** The entry to install when the user has expressed no preference. */
    val defaultBackbone: BackboneModel
        get() = backbones.firstOrNull { it.isDefault } ?: backbones.first()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): ModelCatalog =
            context.assets.open("models.json").use { json.decodeFromString(it.readBytes().decodeToString()) }
    }
}

/**
 * The NeuCodec decoder, which turns the speech tokens a backbone emits into actual audio.
 *
 * Shared by every backbone and therefore downloaded exactly once. Worth surfacing that in the UI:
 * the first install costs its size *plus* a backbone (~870 MB for the default pairing), while every
 * model after that costs only its own size, and a user who is not told this reasonably concludes
 * the app is far heavier than it is.
 */
@Serializable
data class CodecModel(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
    val description: String,
)

/**
 * The codec's encoder, which turns a recording into reference codes - the piece that makes voice
 * cloning possible.
 *
 * Optional and kept separate from [CodecModel] for one reason: it is ~495 MB, which is more than
 * the speech model itself. Someone who only wants to use the nine built-in voices should never be
 * asked to download it, so cloning is a feature the user turns on rather than a cost everyone pays.
 *
 * Two files, because ONNX keeps weights beside the graph past a certain size and both are needed.
 */
@Serializable
data class EncoderModel(
    val id: String,
    val name: String,
    val repo: String,
    val files: List<String>,
    val sizeBytes: Long,
    val description: String,
)

/**
 * One speech model. [preview] names a short clip bundled in the APK so a listener can hear the
 * voice *before* committing to a several-hundred-megabyte download - the whole point of a catalog
 * over a fixed bundled model.
 */
@Serializable
data class BackboneModel(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val lang: String,
    val langName: String,
    val sizeBytes: Long,
    val description: String,
    val preview: String,
    /** Where the weights originate, and the licence verified at that repo rather than at the
     * re-upload this app downloads from - a re-uploader's own metadata is not evidence of either. */
    val sourceRepo: String = "",
    val license: String = "",
    /** Set when the model is not on Hugging Face. NeuTTS-2E has no ungated GGUF there, so it is
     * served from a GitHub release instead - see [downloadUrl]. */
    val url: String = "",
    /** "phonemes" (Air: text goes through espeak first) or "text" (2E: BPE, no phonemizer). Driving
     * a model with the wrong one does not fail, it just sounds wrong. */
    val inputFormat: String = "phonemes",
    /** Emotion tags this model understands, empty when it has none. */
    val emotions: List<String> = emptyList(),
    /** Reference voices this model is restricted to, empty when any voice may be used. 2E is
     * fixed-speaker: it was trained on four voices and cannot clone. */
    val speakers: List<String> = emptyList(),
    @SerialName("default") val isDefault: Boolean = false,
) {
    /** Where the file lands on device, relative to the app's models directory. */
    val fileName: String get() = "$id.gguf"

    /** Where to fetch it from - an explicit URL wins, otherwise the Hugging Face resolve path. */
    val downloadUrl: String
        get() = url.ifEmpty { "https://huggingface.co/$repo/resolve/main/$file" }

    val usesPhonemes: Boolean get() = inputFormat != "text"

    /** True when [voiceId] can be used with this model. */
    fun supportsVoice(voiceId: String): Boolean = speakers.isEmpty() || voiceId in speakers

    /**
     * The espeak-ng voice used to phonemize text for this model. NeuTTS consumes phonemes, not
     * letters, so a model fine-tuned on Hindi has to be fed Hindi phonemes - driving it with the
     * English phonemizer produces fluent-sounding nonsense rather than an obvious error, which is
     * exactly the kind of bug that survives a quick listen.
     */
    val espeakVoice: String
        get() = when (lang) {
            "en" -> "en-us"
            else -> lang
        }
}
