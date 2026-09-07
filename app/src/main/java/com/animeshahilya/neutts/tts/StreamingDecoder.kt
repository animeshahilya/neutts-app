package com.animeshahilya.neutts.tts

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a stream of speech tokens into audio a chunk at a time, so playback can start long before
 * generation finishes.
 *
 * The naive version - decode each 25-frame chunk on its own and concatenate - clicks at every join,
 * roughly twice a second. Two things prevent that, both taken from the reference implementation:
 *
 *  - **Context.** The codec is not causal: what it reconstructs for a frame depends on its
 *    neighbours. Each chunk is decoded with 50 frames of lookback and 5 of lookahead and only the
 *    middle is kept. Decoding the same frames twice costs a little compute and is the entire reason
 *    the seams are inaudible.
 *  - **Overlap-add.** Consecutive chunks overlap and are crossfaded with a triangular window, so
 *    any residual discontinuity is smeared rather than stepped.
 *
 * The overlap-add is done incrementally. Doing it the way the Python reference does - re-running it
 * across every chunk produced so far, each time a chunk is produced - is quadratic, and a
 * thirty-second passage is sixty chunks; the last chunk would re-process all sixty. Because a chunk
 * is only ever overlapped by its immediate neighbour (the stride is shorter than a chunk by exactly
 * the overlap), everything before the next chunk's start is already final and can be emitted, and
 * only that short tail needs carrying forward.
 *
 * Not thread-safe: one stream at a time, driven from the generation thread.
 */
class StreamingDecoder(
    private val codec: NeuCodec,
    referenceCodes: IntArray,
) {
    // The reference codes are part of the codec's context even though they are never played: the
    // first generated frames are reconstructed partly from them, exactly as in a whole-utterance
    // decode.
    private val tokens = ArrayList<Int>(referenceCodes.size + 1024).apply {
        referenceCodes.forEach { add(it) }
    }
    private var decodedTokens = referenceCodes.size

    /** The not-yet-final tail: weighted samples and the weights they were scaled by. */
    private var tailWeighted = FloatArray(0)
    private var tailWeight = FloatArray(0)

    /** Adds one generated token. Returns audio if this completed a chunk, otherwise null. */
    fun offer(speechToken: Int): FloatArray? {
        tokens.add(speechToken)
        if (tokens.size - decodedTokens < FRAMES_PER_CHUNK + LOOKAHEAD) return null

        val chunk = decodeWindow(
            start = max(decodedTokens - LOOKBACK - OVERLAP, 0),
            end = min(decodedTokens + FRAMES_PER_CHUNK + LOOKAHEAD + OVERLAP, tokens.size),
            keepFrames = FRAMES_PER_CHUNK + 2 * OVERLAP,
        ) ?: return null
        decodedTokens += FRAMES_PER_CHUNK
        return addChunk(chunk, finalChunk = false)
    }

    /**
     * Decodes whatever is left once generation has stopped.
     *
     * The tail has no lookahead - there is nothing after it - so it is handled separately rather
     * than by pretending a final partial chunk is full.
     */
    fun drain(): FloatArray? {
        if (tokens.size > decodedTokens) {
            val chunk = decodeWindow(
                start = max(decodedTokens - LOOKBACK - OVERLAP, 0),
                end = tokens.size,
                keepFrames = Int.MAX_VALUE,
            )
            decodedTokens = tokens.size
            if (chunk != null) return addChunk(chunk, finalChunk = true)
        }
        return flushTail()
    }

    private fun decodeWindow(start: Int, end: Int, keepFrames: Int): FloatArray? {
        if (end <= start) return null
        val audio = codec.decode(tokens.subList(start, end).toIntArray())
        val from = (decodedTokens - start) * HOP
        if (from >= audio.size) return null
        val to = if (keepFrames == Int.MAX_VALUE) audio.size
                 else min(from + keepFrames * HOP, audio.size)
        return audio.copyOfRange(from, to)
    }

    /**
     * Crossfades [chunk] onto the carried tail and returns everything that is now final.
     *
     * A chunk starts STRIDE samples after the previous one, so the first STRIDE samples of the tail
     * can never be touched again and are emitted; the rest is carried.
     */
    private fun addChunk(chunk: FloatArray, finalChunk: Boolean): FloatArray? {
        val total = max(tailWeighted.size, chunk.size)
        val weighted = FloatArray(total)
        val weight = FloatArray(total)
        tailWeighted.copyInto(weighted)
        tailWeight.copyInto(weight)

        for (i in chunk.indices) {
            // linspace(0, 1, n + 2)[1..n], then 0.5 - |t - 0.5| - a triangle peaking mid-chunk.
            val t = (i + 1).toFloat() / (chunk.size + 1).toFloat()
            val w = 0.5f - abs(t - 0.5f)
            weighted[i] += w * chunk[i]
            weight[i] += w
        }

        if (finalChunk) {
            tailWeighted = weighted
            tailWeight = weight
            return flushTail()
        }

        val ready = min(STRIDE, total)
        val out = FloatArray(ready)
        for (i in 0 until ready) out[i] = if (weight[i] > 0f) weighted[i] / weight[i] else 0f

        tailWeighted = weighted.copyOfRange(ready, total)
        tailWeight = weight.copyOfRange(ready, total)
        return out
    }

    private fun flushTail(): FloatArray? {
        if (tailWeighted.isEmpty()) return null
        val out = FloatArray(tailWeighted.size)
        for (i in out.indices) {
            out[i] = if (tailWeight[i] > 0f) tailWeighted[i] / tailWeight[i] else 0f
        }
        tailWeighted = FloatArray(0)
        tailWeight = FloatArray(0)
        return out
    }

    private companion object {
        /** 480 samples per frame at 24 kHz - the codec runs at 50 frames a second. */
        const val HOP = 480
        const val FRAMES_PER_CHUNK = 25          // half a second of audio per chunk
        const val OVERLAP = 1
        const val LOOKBACK = 50
        const val LOOKAHEAD = 5
        const val STRIDE = FRAMES_PER_CHUNK * HOP
    }
}
