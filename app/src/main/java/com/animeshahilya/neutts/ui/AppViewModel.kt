package com.animeshahilya.neutts.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeshahilya.neutts.audio.AudioIo
import com.animeshahilya.neutts.audio.ReferenceRecorder
import com.animeshahilya.neutts.audio.StreamingAudioPlayer
import com.animeshahilya.neutts.data.AppSettings
import com.animeshahilya.neutts.data.BackboneModel
import com.animeshahilya.neutts.data.InstallProgress
import com.animeshahilya.neutts.data.ModelCatalog
import com.animeshahilya.neutts.data.ModelInstaller
import com.animeshahilya.neutts.tts.NeuCodec
import com.animeshahilya.neutts.tts.NeuTTSEngine
import com.animeshahilya.neutts.tts.Phonemizer
import com.animeshahilya.neutts.tts.ReferenceVoice
import com.animeshahilya.neutts.tts.ReferenceVoiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the model picker shows for one entry. */
data class ModelRow(
    val model: BackboneModel,
    val installed: Boolean,
    val progress: Float? = null,
    val error: String? = null,
)

data class UiState(
    val models: List<ModelRow> = emptyList(),
    val voices: List<ReferenceVoice> = emptyList(),
    val selectedModelId: String? = null,
    val selectedVoiceId: String? = null,
    val decoderInstalled: Boolean = false,
    val decoderProgress: Float? = null,
    val cloningInstalled: Boolean = false,
    val cloningProgress: Float? = null,
    val text: String = "",
    val emotion: String? = null,
    val useNnapi: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val lastClipPath: String? = null,
    val generatedSeconds: Float? = null,
    val generationSeconds: Float? = null,
) {
    /** The model in use, if any. */
    val currentModel: BackboneModel?
        get() = models.firstOrNull { it.model.id == selectedModelId }?.model

    /** 2E is fixed-speaker: it knows only the four voices it was trained on. Offering the rest
     * would ask it to clone a voice it has never heard. */
    val usableVoices: List<ReferenceVoice>
        get() = currentModel?.let { m -> voices.filter { m.supportsVoice(it.id) } } ?: voices

    val emotions: List<String> get() = currentModel?.emotions.orEmpty()

    /** Ready to speak only when a model and the shared decoder are both on device. */
    val canSpeak: Boolean
        get() = decoderInstalled && selectedModelId != null &&
            models.any { it.model.id == selectedModelId && it.installed } &&
            text.isNotBlank() && busy == null
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val installer = ModelInstaller(application)
    private val settings = AppSettings(application)
    private val voiceStore = ReferenceVoiceStore(application)
    private val phonemizer = Phonemizer(application)
    private val catalog = ModelCatalog.load(application)
    private val recorder = ReferenceRecorder()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engine: NeuTTSEngine? = null
    private var loadedModelId: String? = null
    private var generationJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var streamingPlayer: StreamingAudioPlayer? = null
    @Volatile private var playing = false

    init {
        val voices = voiceStore.all()
        _state.update {
            it.copy(
                models = catalog.backbones.map { model ->
                    ModelRow(model, installer.isInstalled(model))
                },
                voices = voices,
                // Restored from settings so the app and the system TTS service always agree on
                // what is speaking - the service has no UI of its own in which to ask.
                selectedVoiceId = settings.voiceId ?: voices.firstOrNull()?.id,
                selectedModelId = settings.modelId
                    ?: catalog.backbones.firstOrNull { m -> installer.isInstalled(m) }?.id
                    ?: catalog.defaultBackbone.id,
                emotion = settings.emotion,
                useNnapi = settings.useNnapi,
                decoderInstalled = installer.isInstalled(catalog.codec),
                cloningInstalled = installer.isEncoderInstalled(catalog.encoder),
            )
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text, message = null) }
    fun selectModel(id: String) {
        settings.modelId = id
        _state.update { state ->
            val model = state.models.firstOrNull { it.model.id == id }?.model
            // Switching to a fixed-speaker model can strand the current voice; move to one it
            // actually knows rather than failing later at generation time.
            val voice = state.selectedVoiceId
                ?.takeIf { model == null || model.supportsVoice(it) }
                ?: state.voices.firstOrNull { model == null || model.supportsVoice(it.id) }?.id
            settings.voiceId = voice
            // An emotion the new model does not understand would be read out as a literal tag.
            val emotion = state.emotion?.takeIf { model?.emotions?.contains(it) == true }
            settings.emotion = emotion
            state.copy(selectedModelId = id, selectedVoiceId = voice, emotion = emotion)
        }
    }

    fun selectVoice(id: String) {
        settings.voiceId = id
        _state.update { it.copy(selectedVoiceId = id) }
    }

    fun selectEmotion(emotion: String?) {
        settings.emotion = emotion
        _state.update { it.copy(emotion = emotion) }
    }

    fun setUseNnapi(enabled: Boolean) {
        settings.useNnapi = enabled
        releaseEngine()   // the execution provider is fixed when the ONNX session is created
        _state.update { it.copy(useNnapi = enabled) }
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Plays the bundled preview clip for [model], so a voice can be judged before downloading. */
    fun playPreview(model: BackboneModel) {
        stopPreview()
        runCatching {
            getApplication<Application>().assets.openFd(model.preview).use { fd ->
                previewPlayer = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { stopPreview() }
                    prepare()
                    start()
                }
            }
        }.onFailure { _state.update { s -> s.copy(message = "No preview available for ${model.name}.") } }
    }

    fun stopPreview() {
        previewPlayer?.runCatching { stop(); release() }
        previewPlayer = null
    }

    fun installDecoder() = viewModelScope.launch {
        installer.install(catalog.codec).collect { progress ->
            when (progress) {
                is InstallProgress.Downloading ->
                    _state.update { it.copy(decoderProgress = progress.fraction) }
                is InstallProgress.Done ->
                    _state.update { it.copy(decoderProgress = null, decoderInstalled = true) }
                is InstallProgress.Failed ->
                    _state.update { it.copy(decoderProgress = null, message = progress.reason) }
            }
        }
    }

    fun installCloning() = viewModelScope.launch {
        installer.installEncoder(catalog.encoder).collect { progress ->
            when (progress) {
                is InstallProgress.Downloading ->
                    _state.update { it.copy(cloningProgress = progress.fraction) }
                is InstallProgress.Done ->
                    _state.update { it.copy(cloningProgress = null, cloningInstalled = true) }
                is InstallProgress.Failed ->
                    _state.update { it.copy(cloningProgress = null, message = progress.reason) }
            }
        }
    }

    fun install(model: BackboneModel) = viewModelScope.launch {
        installer.install(model).collect { progress ->
            when (progress) {
                is InstallProgress.Downloading -> updateRow(model.id) { it.copy(progress = progress.fraction) }
                is InstallProgress.Done -> {
                    updateRow(model.id) { it.copy(progress = null, installed = true, error = null) }
                    if (loadedModelId == model.id) releaseEngine()   // reload from the new file
                }
                is InstallProgress.Failed ->
                    updateRow(model.id) { it.copy(progress = null, error = progress.reason) }
            }
        }
    }

    fun uninstall(model: BackboneModel) {
        if (loadedModelId == model.id) releaseEngine()
        installer.uninstall(model)
        updateRow(model.id) { it.copy(installed = false, progress = null) }
    }

    fun speak() {
        val current = _state.value
        val modelId = current.selectedModelId ?: return
        val model = catalog.backbones.firstOrNull { it.id == modelId } ?: return
        val voice = current.selectedVoiceId?.let { voiceStore.find(it) } ?: return

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(busy = "Preparing ${model.name}…", message = null) }
            val ready = withContext(Dispatchers.Default) { ensureEngine(model) }
            if (ready == null) {
                _state.update { it.copy(busy = null, message = "Could not load ${model.name}. Try downloading it again.") }
                return@launch
            }

            _state.update { it.copy(busy = "Speaking…") }
            val startedAt = System.nanoTime()
            val collected = ArrayList<FloatArray>()
            playing = true

            // Streamed rather than generated whole: at roughly real time, waiting for the finished
            // clip means waiting as long as it takes to say it. Audio starts about half a second in.
            // One AudioTrack for the whole utterance. Opening one per chunk - which is what the
            // first version did - made every chunk boundary an audible gap, because each track
            // drains and stops at the end of its own buffer.
            withContext(Dispatchers.Default) {
                val player = StreamingAudioPlayer(NeuCodec.SAMPLE_RATE)
                streamingPlayer = player
                try {
                    runCatching {
                        ready.speakStreaming(current.text, voice, current.emotion) { chunk ->
                            collected.add(chunk)
                            player.write(chunk) && playing
                        }
                    }
                } finally {
                    player.close()
                    streamingPlayer = null
                }
            }
            playing = false
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000f

            if (collected.isEmpty()) {
                _state.update { it.copy(busy = null, message = "Nothing was generated. Try shorter text.") }
                return@launch
            }

            val audio = FloatArray(collected.sumOf { it.size }).also { out ->
                var at = 0
                for (chunk in collected) { chunk.copyInto(out, at); at += chunk.size }
            }
            val file = File(getApplication<Application>().filesDir, "clips/last.wav")
            withContext(Dispatchers.IO) { AudioIo.writeWav(file, audio, NeuCodec.SAMPLE_RATE) }
            _state.update {
                it.copy(
                    busy = null,
                    lastClipPath = file.absolutePath,
                    generatedSeconds = audio.size / NeuCodec.SAMPLE_RATE.toFloat(),
                    generationSeconds = elapsed,
                )
            }
        }
    }

    /** Replays the clip that was just generated, without regenerating it - a 30-second wait is
     * far too long to repeat just because someone wants to hear it twice. */
    fun playLastAgain() = viewModelScope.launch {
        val path = _state.value.lastClipPath ?: return@launch
        withContext(Dispatchers.IO) {
            runCatching {
                previewPlayer?.runCatching { stop(); release() }
                previewPlayer = MediaPlayer().apply { setDataSource(path); prepare(); start() }
            }
        }
    }

    fun stopSpeaking() {
        playing = false
        // Drop what is already buffered rather than letting a couple of seconds keep playing after
        // the user asked it to stop.
        streamingPlayer?.stop()
        generationJob?.cancel()
        engine?.cancel()
        _state.update { it.copy(busy = null) }
    }

    /** Records, encodes and saves a new voice. [transcript] must be what was actually said. */
    fun cloneVoice(name: String, transcript: String, samples: FloatArray) = viewModelScope.launch {
        val model = catalog.backbones.firstOrNull { it.id == _state.value.selectedModelId } ?: return@launch
        if (!ReferenceRecorder.isLoudEnough(samples)) {
            _state.update { it.copy(message = "That recording is too quiet to copy a voice from.") }
            return@launch
        }
        _state.update { it.copy(busy = "Saving voice…") }
        val result = withContext(Dispatchers.Default) {
            val ready = ensureEngine(model) ?: return@withContext null
            if (!ready.canClone) return@withContext null
            runCatching {
                ready.cloneVoice(
                    id = "user-" + System.currentTimeMillis(),
                    name = name.ifBlank { "My voice" },
                    samples16k = samples,
                    transcript = transcript,
                )
            }.getOrNull()
        }
        if (result == null) {
            _state.update { it.copy(busy = null, message = "Could not save that voice.") }
            return@launch
        }
        voiceStore.save(result)
        val voices = voiceStore.all()
        _state.update { it.copy(busy = null, voices = voices, selectedVoiceId = result.id) }
    }

    fun recorder(): ReferenceRecorder = recorder

    private fun ensureEngine(model: BackboneModel): NeuTTSEngine? {
        if (loadedModelId == model.id) engine?.let { return it }
        releaseEngine()
        val loaded = NeuTTSEngine.load(
            phonemizer = phonemizer,
            model = model,
            modelFile = installer.fileFor(model),
            decoderFile = installer.fileForCodec(catalog.codec),
            encoderFile = installer.fileForEncoder(catalog.encoder).takeIf { it.exists() },
            useNnapi = _state.value.useNnapi,
        ) ?: return null
        engine = loaded
        loadedModelId = model.id
        return loaded
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        loadedModelId = null
    }

    private fun updateRow(id: String, transform: (ModelRow) -> ModelRow) = _state.update { state ->
        state.copy(models = state.models.map { if (it.model.id == id) transform(it) else it })
    }

    override fun onCleared() {
        stopPreview()
        releaseEngine()
        super.onCleared()
    }
}
