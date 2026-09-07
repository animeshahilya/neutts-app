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
 * Does offloading to the GPU actually help?
 *
 * Same build, same model, same text - only the number of layers handed to the Vulkan backend
 * changes. Running both in one go is the only way to tell "the GPU ran and was slower" apart from
 * "the GPU was unavailable and it quietly fell back", which look identical from a single timing.
 */
@RunWith(AndroidJUnit4::class)
class GpuComparisonTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compareCpuAndGpu() {
        val catalog = ModelCatalog.load(context)
        val installer = ModelInstaller(context)
        val model = catalog.backbones.firstOrNull { it.id == "2e-q4" && installer.isInstalled(it) }
        assumeTrue("2e-q4 not installed", model != null)
        assumeTrue("decoder not installed", installer.isInstalled(catalog.codec))

        val voice = ReferenceVoiceStore(context).bundled().first { model!!.supportsVoice(it.id) }
        val text = "The door creaked open, and she realised she was not alone in the house."

        for (gpuLayers in listOf(0, 99)) {
            val loadStart = System.nanoTime()
            val backbone = NeuTTSBackbone.load(
                installer.fileFor(model!!),
                gpuLayers = gpuLayers,
            ) ?: continue
            val loadSeconds = (System.nanoTime() - loadStart) / 1e9

            val engine = NeuTTSEngine.attach(
                backbone = backbone,
                codec = NeuCodec.open(installer.fileForCodec(catalog.codec), null),
                phonemizer = Phonemizer(context),
                model = model,
            )
            var samples = 0
            val start = System.nanoTime()
            engine.speakStreaming(text, voice, null) { chunk -> samples += chunk.size; true }
            val elapsed = (System.nanoTime() - start) / 1e9
            engine.close()

            val seconds = samples / NeuCodec.SAMPLE_RATE.toFloat()
            Log.i(
                TAG,
                "GPUCMP gpu_layers=%d  load %.2fs  %.2fs speech in %.2fs  RTF %.2f"
                    .format(gpuLayers, loadSeconds, seconds, elapsed, elapsed / seconds)
            )
        }
    }

    private companion object {
        const val TAG = "NeuTTS.gpucmp"
    }
}
