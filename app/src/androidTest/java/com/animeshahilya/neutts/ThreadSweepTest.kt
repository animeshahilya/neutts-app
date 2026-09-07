package com.animeshahilya.neutts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeshahilya.neutts.data.ModelCatalog
import com.animeshahilya.neutts.data.ModelInstaller
import com.animeshahilya.neutts.tts.NeuCodec
import com.animeshahilya.neutts.tts.NeuTTSBackbone
import com.animeshahilya.neutts.tts.NeuTTSEngine
import com.animeshahilya.neutts.tts.Phonemizer
import com.animeshahilya.neutts.tts.ReferenceVoiceStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How many threads should generation actually use on this phone?
 *
 * The default is half the reported cores, which is a guess at "the fast ones" on a big.LITTLE
 * layout. It is worth checking rather than assuming: too few leaves the fast cores idle, and too
 * many drags every layer down to the pace of an efficiency core, so the curve has a peak somewhere
 * in the middle rather than rising with thread count.
 *
 * Run explicitly - it loads the model once per setting and takes a few minutes.
 */
@RunWith(AndroidJUnit4::class)
class ThreadSweepTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sweepThreadCounts() {
        val catalog = ModelCatalog.load(context)
        val installer = ModelInstaller(context)
        val model = catalog.backbones.firstOrNull { it.id == "2e-q4" && installer.isInstalled(it) }
            ?: catalog.backbones.firstOrNull { installer.isInstalled(it) }
        assumeTrue("no model installed", model != null)
        assumeTrue("no decoder installed", installer.isInstalled(catalog.codec))

        val cores = Runtime.getRuntime().availableProcessors()
        val voice = ReferenceVoiceStore(context).bundled().first { model!!.supportsVoice(it.id) }
        val text = "The door creaked open, and she realised she was not alone in the house."
        Log.i(TAG, "sweeping on ${model!!.id}, $cores cores reported")

        for (threads in listOf(2, 3, 4, 5, 6, 8)) {
            if (threads > cores) continue
            val backbone = NeuTTSBackbone.load(installer.fileFor(model), threads = threads) ?: continue
            val engine = NeuTTSEngine.attach(
                backbone = backbone,
                codec = NeuCodec.open(installer.fileForCodec(catalog.codec), null),
                phonemizer = Phonemizer(context),
                model = model,
            )
            var samples = 0
            var firstAt = 0.0
            val start = System.nanoTime()
            engine.speakStreaming(text, voice, null) { chunk ->
                if (samples == 0) firstAt = (System.nanoTime() - start) / 1e9
                samples += chunk.size
                true
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            engine.close()
            val seconds = samples / NeuCodec.SAMPLE_RATE.toFloat()
            Log.i(
                TAG,
                "SWEEP threads=%d  %.2fs speech in %.2fs  RTF %.2f  first audio %.2fs"
                    .format(threads, seconds, elapsed, elapsed / seconds, firstAt)
            )
        }
    }

    private companion object {
        const val TAG = "NeuTTS.sweep"
    }
}
