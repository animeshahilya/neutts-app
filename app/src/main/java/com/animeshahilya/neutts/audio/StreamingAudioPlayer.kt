package com.animeshahilya.neutts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable

/**
 * One AudioTrack, held open for the length of an utterance, fed a chunk at a time.
 *
 * The first version of the streaming path called [AudioIo.play] per chunk, which built and released
 * a whole AudioTrack twice a second. That is not merely wasteful: each track drains and stops at the
 * end of its buffer, so every chunk boundary became an audible gap - a stutter roughly every half
 * second, which is worse than the non-streaming version it was meant to improve on. Keeping one
 * track open and writing into it is what makes the seams inaudible.
 *
 * [write] blocks while the track's buffer is full, which is exactly the back-pressure wanted: when
 * generation is faster than playback the producer waits rather than growing an unbounded queue, and
 * when it is slower the track simply underruns and continues.
 */
class StreamingAudioPlayer(sampleRate: Int, private val bufferSeconds: Float = 2f) : Closeable {

    private val track: AudioTrack
    @Volatile private var stopped = false

    init {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)
        // Deliberately larger than the minimum. Generation runs at roughly real time on a phone, so
        // it only has to fall behind briefly for a minimum-sized buffer to run dry mid-word; a
        // couple of seconds of slack absorbs that at the cost of a little latency at the start.
        val wanted = (sampleRate * bufferSeconds).toInt() * 4
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBytes, wanted))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    /** Writes one chunk, blocking until the track has room. Returns false once stopped. */
    fun write(samples: FloatArray): Boolean {
        if (stopped || samples.isEmpty()) return !stopped
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return false
            offset += written
        }
        return !stopped
    }

    /** Stops immediately, discarding anything still buffered. */
    fun stop() {
        stopped = true
        runCatching { track.pause(); track.flush() }
    }

    /** Lets whatever is already buffered finish playing, rather than cutting the last word off. */
    override fun close() {
        runCatching { if (!stopped) track.stop() }
        runCatching { track.release() }
    }
}
