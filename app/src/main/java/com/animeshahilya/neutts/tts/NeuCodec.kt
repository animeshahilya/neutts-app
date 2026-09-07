package com.animeshahilya.neutts.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * NeuCodec, the audio half of the engine, run on ONNX Runtime.
 *
 * Two directions, and the app can work with only the first:
 *  - **decode** turns the speech tokens the backbone emits into 24 kHz audio. Required.
 *  - **encode** turns a recording into the reference codes that define a voice. Optional, and a
 *    separate ~495 MB download, so cloning is something a user opts into rather than pays for up
 *    front.
 *
 * The execution providers below are the same tuning SherpaVoices arrived at for its own ONNX
 * models, for the same reasons - see [sessionOptions].
 */
class NeuCodec private constructor(
    private val environment: OrtEnvironment,
    private val decoder: OrtSession,
    private val encoder: OrtSession?,
) : Closeable {

    val canClone: Boolean get() = encoder != null

    /**
     * Speech tokens to 24 kHz mono PCM in [-1, 1].
     *
     * Input shape is [1, 1, n] int32 and the model reports its output length as `480*n - 480`,
     * i.e. 480 samples per frame at 24 kHz - 50 frames a second.
     */
    fun decode(speechTokens: IntArray): FloatArray {
        require(speechTokens.isNotEmpty()) { "no speech tokens to decode" }
        val shape = longArrayOf(1, 1, speechTokens.size.toLong())
        OnnxTensor.createTensor(environment, IntBuffer.wrap(speechTokens), shape).use { input ->
            decoder.run(mapOf(decoder.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val audio = result[0].value as Array<Array<FloatArray>>
                return audio[0][0]
            }
        }
    }

    /**
     * A mono 16 kHz recording to reference codes.
     *
     * [samples] must already be 16 kHz - the encoder has no resampler of its own - and is trimmed
     * here to a whole number of 320-sample frames. That trim is not cosmetic: a ragged tail makes
     * the graph fail inside a Concat with a shape mismatch rather than returning a useful error.
     */
    fun encode(samples: FloatArray): IntArray {
        val session = encoder ?: error("voice cloning is not installed")
        val usable = samples.size / SAMPLES_PER_FRAME * SAMPLES_PER_FRAME
        require(usable >= SAMPLES_PER_FRAME) { "recording is too short to clone" }
        val trimmed = samples.copyOf(usable)
        val shape = longArrayOf(1, 1, usable.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(trimmed), shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val codes = result[0].value as Array<Array<IntArray>>
                return codes[0][0]
            }
        }
    }

    override fun close() {
        decoder.close()
        encoder?.close()
    }

    companion object {
        /** 24 kHz output, 50 frames a second. */
        const val SAMPLE_RATE = 24_000

        /** The encoder consumes 16 kHz, also at 50 frames a second - hence 320 samples a frame. */
        const val ENCODER_SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = ENCODER_SAMPLE_RATE / 50

        fun open(decoderFile: File, encoderFile: File?, useNnapi: Boolean = false): NeuCodec {
            val environment = OrtEnvironment.getEnvironment()
            val decoder = environment.createSession(decoderFile.absolutePath, sessionOptions(useNnapi))
            val encoder = encoderFile?.takeIf { it.exists() }?.let {
                environment.createSession(it.absolutePath, sessionOptions(useNnapi))
            }
            return NeuCodec(environment, decoder, encoder)
        }

        /**
         * Execution tuning, matching what SherpaVoices settled on for its own ONNX models.
         *
         * XNNPACK is always on: same arithmetic as the default CPU kernels, better ARM code.
         *
         * NNAPI is off unless the user asks for it. Vendor drivers silently fall back to CPU for
         * graphs they do not fully support, and where they do run a quantised audio model they can
         * produce subtly different numbers rather than an error - which for a codec means
         * artifacts, detectable only by listening. It is worth having as something to measure on a
         * particular phone, not as a default nobody checked.
         *
         * Threads are capped the same way the backbone's are, and for the same reason: on a
         * big.LITTLE phone, spreading onto the efficiency cores slows the whole op down.
         */
        private fun sessionOptions(useNnapi: Boolean): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(NeuTTSBackbone.performanceCoreCount())
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                if (useNnapi) {
                    // CPU_DISABLED would refuse rather than silently fall back, which sounds
                    // stricter but is worse here: a partial fallback still produces sound, and a
                    // refusal leaves the user with no speech at all. Falling back is logged so the
                    // choice can at least be measured.
                    runCatching { addNnapi() }
                        .onSuccess { Log.i("NeuTTS", "codec running through NNAPI") }
                        .onFailure { Log.w("NeuTTS", "NNAPI unavailable, falling back", it) }
                }
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to NeuTTSBackbone.performanceCoreCount().toString())) }
                    .onFailure { Log.i("NeuTTS", "XNNPACK unavailable, using the default CPU kernels") }
            }
    }
}
