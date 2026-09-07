package com.animeshahilya.neutts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeshahilya.neutts.data.BackboneModel
import kotlin.math.roundToInt

private fun Long.asMegabytes(): String = "${(this / (1024 * 1024))} MB"

@Composable
private fun SectionHeading(text: String) {
    // heading() is what lets a screen reader jump between sections instead of walking every
    // control - the same convention SherpaVoices uses throughout.
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuTTSApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("NeuTTS") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Speak") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Voices and models") })
            }
            state.message?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }
            when (tab) {
                0 -> SpeakScreen(state, viewModel)
                else -> ModelsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun SpeakScreen(state: UiState, viewModel: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            label = { Text("What should it say?") },
            supportingText = {
                // The context window is shared between the reference clip and the speech, so there
                // is a real ceiling on how much can be said at once. Saying so up front beats
                // truncating silently - see the catalog notes on prompt budget.
                Text("Around 30 seconds of speech at a time.")
            },
        )

        VoicePicker(state, viewModel)
        if (state.emotions.isNotEmpty()) EmotionPicker(state, viewModel)
        ModelSummary(state, viewModel)

        when {
            state.busy != null -> Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text(state.busy, Modifier.weight(1f))
                    TextButton(onClick = viewModel::stopSpeaking) { Text("Stop") }
                }
            }

            !state.decoderInstalled -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The voice decoder needs downloading before anything can be spoken.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = viewModel::installDecoder) { Text("Download it now (298 MB)") }
                }
            }

            else -> Button(
                onClick = viewModel::speak,
                enabled = state.canSpeak,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak") }
        }

        if (state.generatedSeconds != null && state.generationSeconds != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${state.generatedSeconds.roundToInt()} seconds of speech, " +
                            "made in ${state.generationSeconds.roundToInt()} seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OutlinedButton(onClick = viewModel::playLastAgain) { Text("Play again") }
                }
            }
        }
    }
}

/**
 * The voice list as a dropdown rather than a row of chips.
 *
 * With nine bundled voices and however many the user records, chips ran off the screen and forced a
 * scroll inside a scroll. A dropdown collapses to one control, states the current choice in its
 * own label, and gives a screen reader a single stop instead of N.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(state: UiState, viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val voices = state.usableVoices
    val selected = voices.firstOrNull { it.id == state.selectedVoiceId }
    val label = selected?.let {
        if (it.isUserRecorded) "${it.name} (yours)" else it.name
    } ?: "No voice"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Voice")
        if (voices.size < state.voices.size) {
            Text(
                "This model can only use its own ${voices.size} voices.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(
                    androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true,
                ).fillMaxWidth()
                    .semantics {
                        contentDescription = "Voice, currently $label. Double tap to choose another."
                    },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                voices.forEach { voice ->
                    val name = if (voice.isUserRecorded) "${voice.name} (yours)" else voice.name
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { viewModel.selectVoice(voice.id); expanded = false },
                        modifier = Modifier.semantics {
                            stateDescription = if (voice.id == state.selectedVoiceId) "Selected" else "Not selected"
                        },
                    )
                }
            }
        }
    }
}

/**
 * Emotion, for the models that understand it.
 *
 * Only shown when the chosen model has emotions at all - NeuTTS Air has none, and an emotion tag
 * it does not know would be read out as literal text rather than ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmotionPicker(state: UiState, viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.emotion ?: "neutral"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Tone")
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = current.replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text("Tone") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(
                    androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true,
                ).fillMaxWidth()
                    .semantics {
                        contentDescription = "Tone, currently $current. Double tap to choose another."
                    },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.emotions.forEach { emotion ->
                    DropdownMenuItem(
                        text = { Text(emotion.replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            // Neutral is the absence of a tag rather than a tag of its own.
                            viewModel.selectEmotion(emotion.takeIf { it != "neutral" })
                            expanded = false
                        },
                        modifier = Modifier.semantics {
                            stateDescription = if (emotion == current) "Selected" else "Not selected"
                        },
                    )
                }
            }
        }
    }
}

/** Which model will speak, and a way to change it without leaving the Speak tab. */
@Composable
private fun ModelSummary(state: UiState, viewModel: AppViewModel) {
    val installed = state.models.filter { it.installed }
    val current = state.models.firstOrNull { it.model.id == state.selectedModelId }
    var picking by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Model", style = MaterialTheme.typography.labelMedium)
            Text(
                current?.model?.name ?: "None chosen",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics {
                    contentDescription = "Model: ${current?.model?.name ?: "none chosen"}" +
                        if (current?.installed == false) ", not downloaded yet" else ""
                },
            )
            if (current?.installed == false) {
                Text(
                    "Not downloaded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (installed.size > 1) {
                TextButton(onClick = { picking = true }) { Text("Change model") }
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Choose a model") },
            text = {
                // A radio list in a dialog, the same shape SherpaVoices uses for its own pickers:
                // every option is announced with its own selected state rather than the whole row
                // being one opaque control.
                Column(Modifier.selectableGroup()) {
                    installed.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = row.model.id == state.selectedModelId,
                                onClick = { viewModel.selectModel(row.model.id); picking = false },
                            )
                            Text("${row.model.name} · ${row.model.langName}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ModelsScreen(state: UiState, viewModel: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Required") }
        item {
            SupportComponentCard(
                title = "Voice decoder",
                body = "Needed by every voice, downloaded once.",
                installed = state.decoderInstalled,
                progress = state.decoderProgress,
                sizeLabel = "298 MB",
                onInstall = viewModel::installDecoder,
            )
        }
        item { SectionHeading("Optional") }
        item {
            SupportComponentCard(
                title = "Voice cloning",
                body = "Record a voice and have the app speak in it. " +
                    "The nine built-in voices work without this.",
                installed = state.cloningInstalled,
                progress = state.cloningProgress,
                sizeLabel = "495 MB",
                onInstall = viewModel::installCloning,
            )
        }
        item { SectionHeading("Speech models") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Use the phone's AI accelerator", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "May speed up the decoder on some phones, and may make it sound worse " +
                                "on others. Try it and listen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.useNnapi,
                        onCheckedChange = viewModel::setUseNnapi,
                        modifier = Modifier.semantics {
                            contentDescription = "Use the phone's AI accelerator for the decoder"
                        },
                    )
                }
            }
        }
        items(state.models, key = { it.model.id }) { row ->
            ModelCard(row, state.selectedModelId == row.model.id, viewModel)
        }
    }
}

@Composable
private fun SupportComponentCard(
    title: String,
    body: String,
    installed: Boolean,
    progress: Float?,
    sizeLabel: String,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics {
                    stateDescription = if (installed) "Installed" else "Not installed"
                },
            )
            Text(body, style = MaterialTheme.typography.bodySmall)
            when {
                installed -> Text("Installed", style = MaterialTheme.typography.labelMedium)
                progress != null -> DownloadProgress(title, progress)
                else -> Button(onClick = onInstall) { Text("Download ($sizeLabel)") }
            }
        }
    }
}

@Composable
private fun ModelCard(row: ModelRow, selected: Boolean, viewModel: AppViewModel) {
    val model: BackboneModel = row.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                model.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = buildString {
                        append(model.name).append(", ").append(model.langName)
                        append(", ").append(model.sizeBytes.asMegabytes())
                    }
                    stateDescription = when {
                        selected -> "In use"
                        row.installed -> "Downloaded"
                        else -> "Not downloaded"
                    }
                },
            )
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(
                "${model.langName} · ${model.sizeBytes.asMegabytes()} · ${model.license}",
                style = MaterialTheme.typography.labelSmall,
            )
            row.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.playPreview(model) }) { Text("Preview") }
                when {
                    row.progress != null -> Unit
                    row.installed -> {
                        if (!selected) {
                            Button(onClick = { viewModel.selectModel(model.id) }) { Text("Use") }
                        }
                        TextButton(onClick = { viewModel.uninstall(model) }) { Text("Remove") }
                    }
                    else -> Button(onClick = { viewModel.install(model) }) { Text("Download") }
                }
            }
            row.progress?.let { DownloadProgress(model.name, it) }
        }
    }
}

@Composable
private fun DownloadProgress(label: String, fraction: Float) {
    val percent = (fraction * 100).roundToInt()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Downloading $label, $percent percent" },
        )
        Text("$percent%", style = MaterialTheme.typography.labelSmall)
    }
}
