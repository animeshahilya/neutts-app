package com.animeshahilya.neutts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeshahilya.neutts.data.BackboneModel
import com.animeshahilya.neutts.data.ModelCatalog
import com.animeshahilya.neutts.data.ModelInstaller
import com.animeshahilya.neutts.tts.NeuCodec
import com.animeshahilya.neutts.tts.NeuTTSBackbone
import com.animeshahilya.neutts.tts.NeuTTSEngine
import com.animeshahilya.neutts.tts.NeuTtsService
import com.animeshahilya.neutts.tts.Phonemizer
import com.animeshahilya.neutts.tts.ReferenceVoiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The measurements that only a real phone can give.
 *
 * Everything about correctness was settled on the desktop - the phoneme rules, the prompt shape, the
 * overlap-add. What is left is whether the three native pieces load and cooperate under Android,
 * and how fast generation actually is, which is the number that decides whether this is usable as a
 * reading engine at all.
 *
 * Skips rather than fails when a model is absent: they are hundreds of megabytes and no CI machine
 * should be made to fetch them.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceGenerationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog by lazy { ModelCatalog.load(context) }
    private val installer by lazy { ModelInstaller(context) }

    @Test
    fun phonemizerProducesIpaOnDevice() {
        val phonemes = Phonemizer(context).phonemize("Hello, world!", "en-us")
        Log.i(TAG, "phonemes = $phonemes")
        assertTrue("espeak produced nothing", phonemes.isNotBlank())
        assertTrue("punctuation was dropped: $phonemes", phonemes.contains(","))
        assertTrue("no stress marks, so prosody would be flat: $phonemes", phonemes.contains("ˈ"))
    }

    /** The service splits a paragraph so nothing is silently truncated by the context limit. */
    @Test
    fun longTextIsSplitIntoSpeakableParts() {
        val paragraph = "The door creaked open. She realised she was not alone! " +
            "Somewhere below, a floorboard shifted. Who was there?"
        val parts = NeuTtsService.splitIntoSpeakableParts(paragraph)
        Log.i(TAG, "split into ${parts.size}: $parts")
        assertEquals(4, parts.size)
        assertTrue("the question mark was lost", parts.last().endsWith("?"))
    }

    @Test
    fun airGeneratesAndReportsSpeed() = measure("air-q4")

    @Test
    fun twoEGeneratesAndReportsSpeed() = measure("2e-q4", emotion = "fearful")

    /**
     * Loads a model, speaks a sentence with streaming, and logs the numbers that matter: how long
     * before the first audio arrives, and the real-time factor over the whole utterance. Anything
     * at or below 1.0 means the phone can generate speech faster than it is spoken.
     */
    private fun measure(modelId: String, emotion: String? = null) {
        val model: BackboneModel = catalog.backbones.firstOrNull { it.id == modelId } ?: return
        val modelFile = installer.fileFor(model)
        val decoderFile = installer.fileForCodec(catalog.codec)
        assumeTrue("$modelId is not installed on this device", modelFile.exists())
        assumeTrue("the decoder is not installed on this device", decoderFile.exists())

        val cores = Runtime.getRuntime().availableProcessors()
        val threads = NeuTTSBackbone.performanceCoreCount()

        val loadStart = System.nanoTime()
        val engine = NeuTTSEngine.load(
            phonemizer = Phonemizer(context),
            model = model,
            modelFile = modelFile,
            decoderFile = decoderFile,
            encoderFile = null,
        )
        assertNotNull("$modelId failed to load", engine)
        val loadSeconds = (System.nanoTime() - loadStart) / 1e9

        val voice = ReferenceVoiceStore(context).bundled()
            .first { model.supportsVoice(it.id) }
        val text = "The door creaked open, and she realised she was not alone in the house."

        var firstChunkAt = 0.0
        var samples = 0
        val start = System.nanoTime()
        engine!!.speakStreaming(text, voice, emotion) { chunk ->
            if (samples == 0) firstChunkAt = (System.nanoTime() - start) / 1e9
            samples += chunk.size
            true
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        engine.close()

        assertTrue("$modelId produced no audio", samples > 0)
        val seconds = samples / NeuCodec.SAMPLE_RATE.toFloat()
        Log.i(
            TAG,
            "RESULT %s | cores=%d threads=%d | load %.1fs | first audio %.2fs | %.2fs speech in %.2fs | RTF %.2f"
                .format(modelId, cores, threads, loadSeconds, firstChunkAt, seconds, elapsed, elapsed / seconds)
        )
        assertTrue("$modelId produced implausibly little audio", seconds > 0.5f)
    }

    /** Streamed audio has to be audible, not merely present - a silent buffer would still "pass". */
    @Test
    fun streamedAudioIsNotSilent() {
        val model = catalog.backbones.firstOrNull { installer.isInstalled(it) } ?: return
        assumeTrue("nothing installed", installer.isInstalled(catalog.codec))

        val engine = NeuTTSEngine.load(
            phonemizer = Phonemizer(context),
            model = model,
            modelFile = installer.fileFor(model),
            decoderFile = installer.fileForCodec(catalog.codec),
            encoderFile = null,
        ) ?: return
        val voice = ReferenceVoiceStore(context).bundled().first { model.supportsVoice(it.id) }

        var peak = 0f
        engine.speakStreaming("Testing, one two three.", voice) { chunk ->
            chunk.forEach { peak = maxOf(peak, abs(it)) }
            true
        }
        engine.close()
        Log.i(TAG, "peak amplitude = $peak")
        assertTrue("streamed audio was silent", peak > 0.01f)
    }

    private companion object {
        const val TAG = "NeuTTS.devicetest"
    }
}
