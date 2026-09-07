package com.animeshahilya.neutts.tts

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A voice the model can speak in: a short clip already encoded to NeuCodec codes, plus the exact
 * words spoken in it.
 *
 * The transcript is not documentation - it goes into the prompt alongside the codes, and the model
 * relies on the pairing to learn what this speaker's phonemes sound like. A wrong transcript does
 * not produce an error; it produces a worse clone, which is the kind of failure a user will blame
 * on the model. Anything that collects a recording has to collect its words too.
 */
@Serializable
data class ReferenceVoice(
    val id: String,
    val name: String,
    val transcript: String,
    val codes: List<Int>,
    /** False for the voices bundled with the app, true for one the user recorded. */
    val isUserRecorded: Boolean = false,
) {
    val codesArray: IntArray get() = codes.toIntArray()

    /** Roughly how long the source clip was - 50 code frames a second. */
    val approximateSeconds: Float get() = codes.size / 50f
}

@Serializable
private data class BundledVoices(val formatVersion: Int = 1, val voices: List<ReferenceVoice>)

/**
 * The voices available to speak with: nine that ship in the APK (22 KB of integers, so bundling
 * them costs nothing) and any the user has cloned.
 */
class ReferenceVoiceStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val userVoicesFile: File
        get() = File(appContext.filesDir, "voices/user_voices.json").apply { parentFile?.mkdirs() }

    fun bundled(): List<ReferenceVoice> =
        appContext.assets.open("reference_voices.json").use {
            json.decodeFromString<BundledVoices>(it.readBytes().decodeToString()).voices
        }

    fun userRecorded(): List<ReferenceVoice> {
        val file = userVoicesFile
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<BundledVoices>(file.readText()).voices
        }.getOrDefault(emptyList())
    }

    fun all(): List<ReferenceVoice> = bundled() + userRecorded()

    fun find(id: String): ReferenceVoice? = all().firstOrNull { it.id == id }

    /** Adds or replaces a cloned voice. Written whole rather than appended, so a partial write
     * cannot leave a file that fails to parse and takes every other saved voice with it. */
    fun save(voice: ReferenceVoice) {
        val updated = userRecorded().filterNot { it.id == voice.id } + voice.copy(isUserRecorded = true)
        val temporary = File(userVoicesFile.parentFile, "user_voices.json.tmp")
        temporary.writeText(json.encodeToString(BundledVoices.serializer(), BundledVoices(voices = updated)))
        temporary.renameTo(userVoicesFile)
    }

    fun delete(id: String) {
        val remaining = userRecorded().filterNot { it.id == id }
        val temporary = File(userVoicesFile.parentFile, "user_voices.json.tmp")
        temporary.writeText(json.encodeToString(BundledVoices.serializer(), BundledVoices(voices = remaining)))
        temporary.renameTo(userVoicesFile)
    }
}
