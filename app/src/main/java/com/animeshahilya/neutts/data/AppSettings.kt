package com.animeshahilya.neutts.data

import android.content.Context

/**
 * The few choices that have to outlive the app process, and be visible to the system TTS service.
 *
 * The service runs in the same app but is started by whatever reader the user is in, with no UI of
 * its own - so "which model, which voice, which emotion" has to be settled here rather than passed
 * in. Plain SharedPreferences: there are five values and they are read once per utterance.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("neutts", Context.MODE_PRIVATE)

    var modelId: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voiceId: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** Null or "neutral" means no emotion tag; only the 2E family understands the others. */
    var emotion: String?
        get() = prefs.getString(KEY_EMOTION, null)
        set(value) = prefs.edit().putString(KEY_EMOTION, value).apply()

    /**
     * Whether to run the codec through NNAPI rather than the CPU kernels.
     *
     * Off by default, deliberately. NNAPI is the obvious thing to reach for on a flagship, but a
     * vendor driver that does not fully support a graph falls back to CPU silently, and where it
     * does run a quantised audio model it can produce subtly different numbers rather than an
     * error - which for a codec means artifacts, audible only by listening. Google has also been
     * moving away from NNAPI since Android 15. So it is offered, and measured, rather than assumed.
     */
    var useNnapi: Boolean
        get() = prefs.getBoolean(KEY_NNAPI, false)
        set(value) = prefs.edit().putBoolean(KEY_NNAPI, value).apply()

    private companion object {
        const val KEY_NNAPI = "use_nnapi"
        const val KEY_MODEL = "model_id"
        const val KEY_VOICE = "voice_id"
        const val KEY_EMOTION = "emotion"
    }
}
