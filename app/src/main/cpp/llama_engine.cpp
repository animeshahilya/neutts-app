// The NeuTTS backbone, run through llama.cpp.
//
// NeuTTS Air is a Qwen2-architecture LM whose output is not text but speech tokens - <|speech_N|>
// pieces that NeuCodec turns into audio. So this file's job is narrow: take a prompt that already
// contains the phonemes and the reference codes, generate until the model emits
// <|SPEECH_GENERATION_END|>, and hand back the speech token ids.
//
// This replaces a first pass that had three problems worth naming, because each of them shows up as
// "the model sounds bad" rather than as an error:
//
//   1. It sampled greedily (argmax). The reference pipeline samples with temperature 1.0 and
//      top-k 50, and an autoregressive speech model driven greedily produces flat, looping audio -
//      argmax is not a neutral choice here, it is a different model.
//   2. It allocated a 512-token batch and pushed the whole prompt into it. A reference clip of a
//      few seconds is already 300-650 speech tokens before the phonemes are added, so any real
//      prompt wrote past the end of the batch.
//   3. It never set n_threads, leaving llama.cpp on its default rather than the phone's actual
//      performance-core count - the single biggest generation-speed lever on a flagship.
//
// It also returns token ids rather than a decoded string. The previous version searched the decoded
// text for "<|SPEECH_GENERATION_END|>", which cannot work: that token decodes to itself only if the
// detokenizer renders specials, and scanning a growing string every step is quadratic for no reason.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "NeuTTS.llama", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NeuTTS.llama", __VA_ARGS__)

namespace {

struct EngineContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    // Set from Kotlin to abort a generation that the user has cancelled or that outlived its
    // request. Checked once per token, which is as often as it can usefully be honoured.
    std::atomic<bool> cancelled{false};

    // Streaming state. Generation is exposed as begin/next/end rather than one blocking call so
    // audio can start playing while the rest is still being produced - at roughly real time, that
    // is the difference between waiting half a minute and hearing the first words immediately.
    llama_sampler* chain = nullptr;
    int n_past = 0;
    int produced = 0;
    int max_new = 0;
    llama_token end_token = -1;
};

// Prompt evaluation is chunked at this many tokens. Large enough that a long prompt costs only a
// handful of decode calls, small enough that the batch stays a modest allocation.
constexpr int kBatchTokens = 256;

bool eval_tokens(EngineContext* engine, const std::vector<llama_token>& tokens, int n_past) {
    llama_batch batch = llama_batch_init(kBatchTokens, 0, 1);
    bool ok = true;
    for (size_t offset = 0; offset < tokens.size() && ok; offset += kBatchTokens) {
        const size_t count = std::min<size_t>(kBatchTokens, tokens.size() - offset);
        batch.n_tokens = 0;
        for (size_t i = 0; i < count; i++) {
            batch.token[batch.n_tokens] = tokens[offset + i];
            batch.pos[batch.n_tokens] = static_cast<llama_pos>(n_past + offset + i);
            batch.n_seq_id[batch.n_tokens] = 1;
            batch.seq_id[batch.n_tokens][0] = 0;
            // Only the very last token of the whole prompt needs logits - everything before it is
            // just filling the KV cache.
            batch.logits[batch.n_tokens] = (offset + count == tokens.size()) && (i + 1 == count);
            batch.n_tokens++;
        }
        if (llama_decode(engine->ctx, batch) != 0) {
            LOGE("llama_decode failed at offset %zu", offset);
            ok = false;
        }
    }
    llama_batch_free(batch);
    return ok;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeLoad(
        JNIEnv* env, jobject, jstring modelPath, jint contextTokens, jint threads, jint gpuLayers) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    // Memory-mapped, so the ~570 MB of weights are paged in by the kernel on demand and stay
    // evictable under pressure rather than counting as private dirty pages the whole time. On a
    // phone that is the difference between a background app surviving and being killed. Not
    // MMAP_MLOCK: pinning half a gigabyte of a phone's RAM to speed up one app is the wrong
    // trade for everything else running on it.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

#ifdef GGML_USE_VULKAN
    // Offload every layer to the GPU when the build has a Vulkan backend. llama.cpp falls back to
    // the CPU on its own if no Vulkan device is usable, so this is safe on a phone whose driver
    // refuses - it simply runs as it did before.
    model_params.n_gpu_layers = gpuLayers;
#else
    (void) gpuLayers;
#endif

    llama_model* model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(contextTokens);
    ctx_params.n_batch = kBatchTokens;
    // The caller passes the performance-core count. llama.cpp's own default is conservative, and
    // on a big.LITTLE phone spilling onto the efficiency cores makes generation slower rather than
    // faster - the fast cores end up waiting on the slow ones at every layer boundary.
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    LOGI("loaded backbone: n_ctx=%d threads=%d gpu_layers=%d", contextTokens, threads, gpuLayers);
    return reinterpret_cast<jlong>(new EngineContext{model, ctx});
}

namespace {

/** Frees any sampler left over from a previous generation. */
void end_generation(EngineContext* engine) {
    if (engine->chain != nullptr) {
        llama_sampler_free(engine->chain);
        engine->chain = nullptr;
    }
}

}  // namespace

/**
 * Prepares a generation: clears the KV cache, evaluates the prompt, and builds the sampler.
 *
 * Sampling mirrors the reference pipeline exactly - top-k, then temperature, then draw. Reversing
 * the first two changes which tokens are reachable at all, so the order is not cosmetic.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeBeginGenerate(
        JNIEnv* env, jobject, jlong handle, jintArray promptTokens, jint endToken,
        jint maxNewTokens, jfloat temperature, jint topK, jint seed) {
    auto* engine = reinterpret_cast<EngineContext*>(handle);
    if (engine == nullptr) return JNI_FALSE;

    engine->cancelled.store(false);
    end_generation(engine);

    const jsize promptLength = env->GetArrayLength(promptTokens);
    std::vector<llama_token> prompt(promptLength);
    env->GetIntArrayRegion(promptTokens, 0, promptLength, reinterpret_cast<jint*>(prompt.data()));

    llama_memory_clear(llama_get_memory(engine->ctx), true);
    if (!eval_tokens(engine, prompt, 0)) return JNI_FALSE;

    engine->chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(engine->chain, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(engine->chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(engine->chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    engine->n_past = promptLength;
    engine->produced = 0;
    engine->max_new = maxNewTokens;
    engine->end_token = endToken;
    return JNI_TRUE;
}

/**
 * Produces the next speech token, or -1 when generation is over - the model emitted its end token,
 * the cap was reached, or the caller cancelled.
 *
 * One JNI call per token sounds costly and is not: a token costs a full forward pass through the
 * model, next to which the transition is free. What it buys is the ability to stop, and to start
 * playing audio, without waiting for the whole utterance.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeNextToken(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<EngineContext*>(handle);
    if (engine == nullptr || engine->chain == nullptr) return -1;
    if (engine->cancelled.load() || engine->produced >= engine->max_new) return -1;

    const llama_token next = llama_sampler_sample(engine->chain, engine->ctx, -1);
    const llama_vocab* vocab = llama_model_get_vocab(engine->model);
    // Both stops matter: the model's own end-of-speech token, and a genuine end-of-generation in
    // case a fine-tune terminates differently.
    if (next == engine->end_token || llama_vocab_is_eog(vocab, next)) return -1;

    llama_sampler_accept(engine->chain, next);
    engine->produced++;

    std::vector<llama_token> one{next};
    if (!eval_tokens(engine, one, engine->n_past)) return -1;
    engine->n_past++;
    return static_cast<jint>(next);
}

extern "C" JNIEXPORT void JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeEndGenerate(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = reinterpret_cast<EngineContext*>(handle)) end_generation(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = reinterpret_cast<EngineContext*>(handle)) engine->cancelled.store(true);
}

/** Decodes one token to its text piece, specials included - used to map ids back to <|speech_N|>. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeTokenToPiece(
        JNIEnv* env, jobject, jlong handle, jint token) {
    auto* engine = reinterpret_cast<EngineContext*>(handle);
    if (engine == nullptr) return env->NewStringUTF("");
    char buffer[256];
    const int length = llama_token_to_piece(
            llama_model_get_vocab(engine->model), token, buffer, sizeof(buffer), 0, true);
    if (length <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buffer, length).c_str());
}

/** Tokenizes text with special tokens parsed, which the prompt depends on. */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeTokenize(
        JNIEnv* env, jobject, jlong handle, jstring text) {
    auto* engine = reinterpret_cast<EngineContext*>(handle);
    if (engine == nullptr) return env->NewIntArray(0);

    const char* input = env->GetStringUTFChars(text, nullptr);
    const int inputLength = static_cast<int>(strlen(input));
    const llama_vocab* vocab = llama_model_get_vocab(engine->model);

    // Ask for the count first rather than guessing a buffer size: a prompt carrying hundreds of
    // reference codes is far larger than any fixed guess that would be safe to put on the stack.
    int needed = -llama_tokenize(vocab, input, inputLength, nullptr, 0, false, true);
    std::vector<llama_token> tokens(needed > 0 ? needed : 0);
    const int count = llama_tokenize(
            vocab, input, inputLength, tokens.data(), static_cast<int32_t>(tokens.size()),
            /*add_special=*/false, /*parse_special=*/true);
    env->ReleaseStringUTFChars(text, input);

    if (count < 0) {
        LOGE("tokenize failed (needed %d)", needed);
        return env->NewIntArray(0);
    }
    jintArray result = env->NewIntArray(count);
    env->SetIntArrayRegion(result, 0, count, reinterpret_cast<const jint*>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_animeshahilya_neutts_tts_NeuTTSBackbone_nativeFree(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = reinterpret_cast<EngineContext*>(handle)) {
        end_generation(engine);
        llama_free(engine->ctx);
        llama_model_free(engine->model);
        delete engine;
        llama_backend_free();
    }
}
