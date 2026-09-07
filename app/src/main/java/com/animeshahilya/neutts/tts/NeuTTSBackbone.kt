package com.animeshahilya.neutts.tts

import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * The GGUF speech model, via llama.cpp.
 *
 * Emits speech tokens rather than text - see llama_engine.cpp. This class owns the native handle
 * and is **not** thread-safe: one generation at a time, on a background thread.
 */
class NeuTTSBackbone private constructor(private var handle: Long) : Closeable {

    private external fun nativeBeginGenerate(
        handle: Long, promptTokens: IntArray, endToken: Int,
        maxNewTokens: Int, temperature: Float, topK: Int, seed: Int,
    ): Boolean

    private external fun nativeNextToken(handle: Long): Int
    private external fun nativeEndGenerate(handle: Long)

    private external fun nativeTokenize(handle: Long, text: String): IntArray
    private external fun nativeTokenToPiece(handle: Long, token: Int): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    fun tokenize(text: String): IntArray = nativeTokenize(handle, text)

    fun tokenToPiece(token: Int): String = nativeTokenToPiece(handle, token)

    /**
     * Generates speech tokens, handing each to [onToken] as it is produced.
     *
     * The whole utterance is never held natively - the caller decides what to do with each token,
     * which is what lets audio start playing while the rest is still being generated. Returning
     * false from [onToken] stops generation, so a consumer that has heard enough (or been closed)
     * does not have to wait for the model to finish.
     *
     * Defaults match the reference pipeline. They are not free knobs: the samples this app was
     * designed against were produced with exactly these.
     */
    fun generate(
        promptTokens: IntArray,
        endToken: Int,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        temperature: Float = 1.0f,
        topK: Int = 50,
        seed: Int = System.nanoTime().toInt(),
        onToken: (Int) -> Boolean,
    ) {
        if (!nativeBeginGenerate(handle, promptTokens, endToken, maxNewTokens, temperature, topK, seed)) {
            return
        }
        try {
            while (true) {
                val token = nativeNextToken(handle)
                if (token < 0) break
                if (!onToken(token)) break
            }
        } finally {
            nativeEndGenerate(handle)
        }
    }

    /** Collects the whole utterance, for callers that cannot make use of partial audio. */
    fun generateAll(
        promptTokens: IntArray,
        endToken: Int,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        temperature: Float = 1.0f,
        topK: Int = 50,
        seed: Int = System.nanoTime().toInt(),
    ): IntArray {
        val collected = ArrayList<Int>(256)
        generate(promptTokens, endToken, maxNewTokens, temperature, topK, seed) {
            collected.add(it); true
        }
        return collected.toIntArray()
    }

    /** Safe to call from another thread; the generation loop checks once per token. */
    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        /**
         * Roughly 20 seconds of speech at ~50 tokens/second. A cap is needed because an
         * autoregressive model that fails to emit its end token would otherwise run until the
         * context is full - minutes of compute for output nobody wants.
         */
        const val DEFAULT_MAX_NEW_TOKENS = 1024

        /**
         * Context has to hold the phonemes, the whole reference clip's codes (300-650 of them for
         * the bundled speakers) and everything generated, with room to spare. 2048 is what the
         * reference pipeline uses.
         */
        const val CONTEXT_TOKENS = 2048

        /** More than any layer count these models have, i.e. "all of them". */
        const val ALL_LAYERS_ON_GPU = 99

        init {
            System.loadLibrary("neutts_jni")
        }

        @JvmStatic
        private external fun nativeLoad(modelPath: String, contextTokens: Int, threads: Int, gpuLayers: Int): Long

        /**
         * Loads [modelFile], using [threads] worker threads.
         *
         * Returns null rather than throwing when the model cannot be loaded: on a phone the usual
         * cause is a download that was interrupted, and that is a "tap to retry" situation rather
         * than a crash.
         */
        fun load(
            modelFile: File,
            threads: Int = performanceCoreCount(),
            contextTokens: Int = CONTEXT_TOKENS,
            /** Layers to run on the GPU. Ignored unless the build has a Vulkan backend; llama.cpp
             * falls back to the CPU by itself when no usable device is present. */
            gpuLayers: Int = ALL_LAYERS_ON_GPU,
        ): NeuTTSBackbone? {
            val handle = nativeLoad(modelFile.absolutePath, contextTokens, threads, gpuLayers)
            if (handle == 0L) {
                Log.e("NeuTTS", "could not load ${modelFile.name}")
                return null
            }
            return NeuTTSBackbone(handle)
        }

        /**
         * How many threads to run generation on.
         *
         * Phones are big.LITTLE: a flagship has a handful of fast cores and several slow ones, and
         * llama.cpp splits each layer evenly across whatever threads it is given. Include the slow
         * cores and every layer finishes at the pace of the slowest thread, so more threads makes
         * generation *slower*. Half the reported cores is a reasonable stand-in for "the fast
         * ones" across the range of devices this will run on, clamped so a small phone still gets
         * two and a large one does not spawn more than the big cluster can hold.
         */
        fun performanceCoreCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    }
}
