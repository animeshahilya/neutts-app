# NeuTTS

An offline text-to-speech app for Android, built on [NeuTTS](https://github.com/neuphonic/neutts).
Speech is generated entirely on the phone; nothing is sent anywhere.

- **Speaks in nine built-in voices**, or one you record yourself.
- **Emotions** (happy, sad, angry, fearful, disgusted, surprised) with the NeuTTS-2E model.
- **English and Hindi.**
- **Registers as a system text-to-speech engine**, so reading apps can speak through it.
- **Ships no model weights.** Models are chosen and downloaded in the app, and every one has a
  short sample bundled so a voice can be heard *before* committing to a few hundred megabytes.

## It is a reading app, not a screen reader

Generation runs at roughly 2.5 to 3 times slower than real time on a Pixel 8. Streaming means
speech starts after about three seconds rather than after the whole passage, which is fine between
sentences of a story and unusable between menu items. Do not set this as the system engine for
TalkBack.

Measured on a Pixel 8, 4 threads:

| Model | Size | First audio | Real-time factor |
| --- | --- | --- | --- |
| NeuTTS 2E (Q4) | 165 MB | 3.5 s | 2.4 – 2.7 |
| NeuTTS Air (Q4) | 569 MB | 18.7 s | 7.4 |

2E is the one to use for reading. Air is there for cloning your own voice, which 2E cannot do.

## Building

    git clone --recurse-submodules <this repo>
    ./gradlew assembleDebug

Release builds are signed if a `keystore.properties` is present (see `app/build.gradle.kts`), and
unsigned otherwise, so a clean checkout still builds.

### Optional: the Vulkan GPU backend

Off by default. It is about 25% faster on a Pixel 8 - measured, reproducibly - but adds 46 MB of
compiled shaders to the APK, needs a patch to llama.cpp (see `patches/`), and a Windows host
compiler for the shader generator. 25% does not turn 3.3 into real time, so the size is hard to
justify; on an Adreno GPU the gain may be larger.

    # from a shell with an MSVC environment, and with the header paths set
    ./gradlew assembleDebug -PvulkanBackend

## Licences

The app is the author's. The models it downloads are not: NeuTTS Air and its community fine-tunes
are Apache-2.0, and NeuTTS-2E is under the NeuTTS Open License v1.0 (free below a $5M annual
revenue threshold). The catalog records the source repository and licence of every entry, verified
at the source rather than at the re-upload.
