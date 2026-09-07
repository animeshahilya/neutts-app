// espeak-ng, exposed to Kotlin as one call: text in, IPA phonemes out.
//
// NeuTTS is a phoneme model - it never sees letters - so this is not a nicety, it is the front
// half of the engine. The desktop reference pipeline (gen_samples.py) uses the Python `phonemizer`
// package over this same C library; the parity between the two was checked sentence by sentence
// before this was written, and the punctuation rule that phonemizer applies on top lives in
// Phonemizer.kt rather than here, so the two implementations can be compared directly.
//
// espeak-ng keeps mutable global state and is not safe to call from two threads at once - every
// entry point here is therefore synchronized on the Kotlin side.

#include <jni.h>
#include <android/log.h>
#include <string>
#include "espeak-ng/speak_lib.h"

#define LOG_TAG "NeuTTS.espeak"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
// phonememode bit 1 selects IPA output (UTF-8) rather than espeak's own ASCII phoneme names.
// Verified against the phonemizer package's output on the desktop: identical for every test
// sentence, which is what lets the samples generated there stand for what this app will produce.
constexpr int kPhonemeModeIpa = 0x02;
// espeakCHARS_UTF8 - the text handed down from Kotlin is always UTF-8.
constexpr int kCharsUtf8 = 1;
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_animeshahilya_neutts_tts_Phonemizer_nativeInit(JNIEnv* env, jobject, jstring dataPath) {
    const char* path = env->GetStringUTFChars(dataPath, nullptr);
    // AUDIO_OUTPUT_SYNCHRONOUS: nothing here ever plays audio through espeak, it only translates
    // text. Returns the sample rate on success and -1 on failure.
    const int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, 0);
    if (rate < 0) LOGE("espeak_Initialize failed for data path %s", path);
    env->ReleaseStringUTFChars(dataPath, path);
    return rate;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_animeshahilya_neutts_tts_Phonemizer_nativeSetVoice(JNIEnv* env, jobject, jstring voice) {
    const char* name = env->GetStringUTFChars(voice, nullptr);
    const espeak_ERROR err = espeak_SetVoiceByName(name);
    if (err != EE_OK) LOGE("espeak_SetVoiceByName(%s) failed: %d", name, err);
    env->ReleaseStringUTFChars(voice, name);
    return err == EE_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_animeshahilya_neutts_tts_Phonemizer_nativePhonemize(JNIEnv* env, jobject, jstring text) {
    const char* input = env->GetStringUTFChars(text, nullptr);

    // espeak_TextToPhonemes consumes the buffer a clause at a time, advancing the pointer and
    // returning one clause per call until it lands on null. A single call returns only the first
    // clause, which silently truncates anything with a comma in it.
    const void* cursor = input;
    std::string out;
    while (cursor != nullptr) {
        const char* clause = espeak_TextToPhonemes(&cursor, kCharsUtf8, kPhonemeModeIpa);
        if (clause == nullptr) break;
        if (!out.empty()) out += ' ';
        out += clause;
    }

    env->ReleaseStringUTFChars(text, input);
    return env->NewStringUTF(out.c_str());
}
