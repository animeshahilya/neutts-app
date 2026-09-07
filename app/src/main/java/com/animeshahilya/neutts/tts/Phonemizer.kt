package com.animeshahilya.neutts.tts

import android.content.Context
import java.io.File

/**
 * Text to IPA phonemes, via espeak-ng.
 *
 * NeuTTS consumes phonemes rather than letters, so this is the front half of the engine and its
 * output has to match what the model was driven with when the reference samples were produced.
 * Those came from the Python `phonemizer` package, which wraps this same C library and then applies
 * one rule of its own: punctuation is stripped by espeak, and phonemizer puts it back. That rule is
 * re-implemented in [phonemize] below, and the two were compared sentence by sentence on the
 * desktop first - identical output on every test but one (a space inside "D.C.", which is
 * inaudible).
 *
 * espeak-ng holds mutable global state - the selected voice above all - and is not safe to call
 * from two threads at once, so every native call here is synchronized. Generation runs on one
 * background thread anyway; this guards against a second one arriving from the UI.
 */
class Phonemizer(context: Context) {

    private val appContext = context.applicationContext
    private var currentVoice: String? = null
    private var initialized = false

    private external fun nativeInit(dataPath: String): Int
    private external fun nativeSetVoice(voice: String): Boolean
    private external fun nativePhonemize(text: String): String

    /** Unpacks espeak-ng-data out of the APK once, since espeak needs a real filesystem path. */
    private fun ensureInitialized() {
        if (initialized) return
        val dataRoot = File(appContext.filesDir, "espeak")
        val marker = File(dataRoot, ".unpacked-v1")
        if (!marker.exists()) {
            dataRoot.deleteRecursively()
            copyAsset("espeak-ng-data", File(dataRoot, "espeak-ng-data"))
            marker.writeText("1")
        }
        val rate = nativeInit(dataRoot.absolutePath)
        check(rate > 0) { "espeak-ng failed to initialise (returned $rate)" }
        initialized = true
    }

    private fun copyAsset(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        children.forEach { copyAsset("$assetPath/$it", File(target, it)) }
    }

    /**
     * Phonemizes [text] for [voice] (an espeak voice name such as "en-us" or "hi").
     *
     * Punctuation is preserved in place, because the model was trained with it and it carries
     * prosody - a comma is where the voice pauses. espeak drops it, so the text is split into runs
     * of punctuation and runs of words, only the words go through espeak, and the marks are put
     * back where they were. Opening marks bind to the word after them; every other mark binds to
     * the word before.
     */
    @Synchronized
    fun phonemize(text: String, voice: String): String {
        ensureInitialized()
        if (voice != currentVoice) {
            check(nativeSetVoice(voice)) { "espeak-ng has no voice '$voice'" }
            currentVoice = voice
        }

        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            val markStart = index
            while (index < text.length && text[index] in MARKS) index++
            if (index > markStart) {
                val marks = text.substring(markStart, index)
                if (marks.last() in OPENING_MARKS) builder.append(' ').append(marks)
                else builder.append(marks).append(' ')
                continue
            }
            val wordStart = index
            while (index < text.length && text[index] !in MARKS) index++
            val words = text.substring(wordStart, index)
            if (words.isNotBlank()) builder.append(nativePhonemize(words))
        }
        return builder.toString().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        // phonemizer's own default punctuation set, matched exactly so the two agree.
        private const val MARKS = ";:,.!?¡¿—…\"«»“”"
        private const val OPENING_MARKS = "¡¿«“"
        private val WHITESPACE = Regex("\\s+")

        init {
            System.loadLibrary("neutts_jni")
        }
    }
}
