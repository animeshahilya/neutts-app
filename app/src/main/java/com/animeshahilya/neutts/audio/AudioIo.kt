package com.animeshahilya.neutts.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/** 24 kHz mono PCM playback, and WAV export for saving a result. */
object AudioIo {

    /**
     * Plays [samples] and returns when playback finishes.
     *
     * Blocking on purpose: the caller already runs on a background scope, and a play call that
     * returns immediately makes "stop when the user leaves" much harder to get right than it needs
     * to be. [shouldContinue] is polled per buffer so stopping is responsive rather than waiting
     * out the whole clip.
     */
    fun play(samples: FloatArray, sampleRate: Int, shouldContinue: () -> Boolean = { true }) {
        if (samples.isEmpty()) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // MEDIA rather than ASSISTANT: this is a generated clip the user asked to hear,
                    // so it should duck and route like any other media the phone plays.
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
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            val chunk = minBuffer / 4
            while (offset < samples.size && shouldContinue()) {
                val count = min(chunk, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            // Let whatever is already buffered finish rather than cutting the last syllable off.
            if (shouldContinue()) track.playbackHeadPosition.let { track.stop() } else track.pause()
        } finally {
            track.release()
        }
    }

    /** Writes 16-bit mono WAV, the format anything else on the phone will open. */
    fun writeWav(target: File, samples: FloatArray, sampleRate: Int) {
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { out ->
            val dataBytes = samples.size * 2
            out.writeAscii("RIFF"); out.writeIntLe(36 + dataBytes); out.writeAscii("WAVE")
            out.writeAscii("fmt "); out.writeIntLe(16); out.writeShortLe(1); out.writeShortLe(1)
            out.writeIntLe(sampleRate); out.writeIntLe(sampleRate * 2)
            out.writeShortLe(2); out.writeShortLe(16)
            out.writeAscii("data"); out.writeIntLe(dataBytes)
            for (sample in samples) {
                val clipped = sample.coerceIn(-1f, 1f)
                out.writeShortLe((clipped * 32767f).toInt())
            }
        }
    }

    private fun OutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))
    private fun OutputStream.writeIntLe(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF); write((value shr 24) and 0xFF)
    }
    private fun OutputStream.writeShortLe(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }
}

/**
 * Records a reference clip for voice cloning, at the 16 kHz the codec encoder expects.
 *
 * Recorded at 16 kHz directly rather than at 44.1 and resampled: the encoder wants 16 kHz, and
 * every resampling step is a chance to introduce something the clone then faithfully reproduces.
 */
class ReferenceRecorder {

    @Volatile private var recording = false

    /**
     * Records until [shouldContinue] returns false or [maxSeconds] elapses, returning mono float
     * samples at 16 kHz.
     *
     * Requires RECORD_AUDIO; the caller checks that, because a permission prompt belongs in the UI
     * and not down here.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun record(maxSeconds: Int = 30, shouldContinue: () -> Boolean): FloatArray {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        val recorder = AudioRecord.Builder()
            // VOICE_RECOGNITION, not MIC: it is the source least likely to have aggressive AGC or
            // noise suppression applied by the vendor, and a clone should copy the voice rather
            // than the phone's processing of it.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .build()

        val collected = ArrayList<Float>(SAMPLE_RATE * maxSeconds)
        val buffer = FloatArray(minBuffer / 4)
        recording = true
        try {
            recorder.startRecording()
            while (recording && shouldContinue() && collected.size < SAMPLE_RATE * maxSeconds) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                for (i in 0 until read) collected.add(buffer[i])
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recording = false
        }
        return collected.toFloatArray()
    }

    fun stop() { recording = false }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * Whether a recording is loud enough to clone from.
         *
         * A clone made from a near-silent clip does not fail - it produces a thin, breathy voice
         * and the user is left wondering what they did wrong. Checking up front and saying so is
         * far kinder than letting them find out after a two-minute generation.
         */
        fun isLoudEnough(samples: FloatArray): Boolean =
            samples.isNotEmpty() && samples.maxOf { abs(it) } >= 0.05f
    }
}
