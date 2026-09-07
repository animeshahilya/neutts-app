package com.animeshahilya.neutts.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Progress of one install, reported often enough to drive a progress bar but not per-byte. */
sealed interface InstallProgress {
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : InstallProgress {
        val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }

    data class Done(val file: File) : InstallProgress
    data class Failed(val reason: String) : InstallProgress
}

/**
 * Downloads models into the app's own files directory.
 *
 * Deliberately built on HttpURLConnection rather than pulling in a HTTP client: this makes exactly
 * one kind of request - a ranged GET of a large static file - and a dependency would buy nothing
 * that is not already here.
 *
 * Three things this has to get right, because each of them is a real failure a user would hit:
 *
 *  - **Resume.** These are 273-765 MB files, often on Indian mobile data. A download that restarts
 *    from zero on every dropped connection never finishes. A partial file is kept and continued
 *    with a Range request.
 *  - **Never publish a half-file.** Bytes land in `<name>.part` and are renamed only once the
 *    length matches what the catalog promised. A truncated GGUF otherwise loads and then produces
 *    noise, which reads as "this model is bad" rather than "this download was cut short".
 *  - **Check for space first.** Failing after 500 MB because the device was full is far worse than
 *    refusing at the start.
 */
class ModelInstaller(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(backbone: BackboneModel): File = File(modelsDir, backbone.fileName)

    fun fileForCodec(codec: CodecModel): File = File(modelsDir, "${codec.id}.onnx")

    fun isInstalled(backbone: BackboneModel): Boolean =
        fileFor(backbone).length() == backbone.sizeBytes

    fun isInstalled(codec: CodecModel): Boolean =
        fileForCodec(codec).length() == codec.sizeBytes

    /** The encoder's graph file. ONNX resolves its weights file by name from beside it, so both
     * land in the same directory and the graph is the one handed to the runtime. */
    fun fileForEncoder(encoder: EncoderModel): File =
        File(modelsDir, encoder.files.first().substringAfterLast('/'))

    fun isEncoderInstalled(encoder: EncoderModel): Boolean =
        encoder.files.all { File(modelsDir, it.substringAfterLast('/')).length() > 0 }

    /** Downloads every file the encoder needs, reporting one combined progress across them. */
    fun installEncoder(encoder: EncoderModel): Flow<InstallProgress> = flow {
        var completed = 0L
        for (path in encoder.files) {
            val target = File(modelsDir, path.substringAfterLast('/'))
            var lastForThisFile = 0L
            download(hfUrl(encoder.repo, path), target, expectedBytes = 0L).collect { progress ->
                when (progress) {
                    is InstallProgress.Downloading -> {
                        lastForThisFile = progress.bytesDone
                        emit(InstallProgress.Downloading(completed + lastForThisFile, encoder.sizeBytes))
                    }
                    is InstallProgress.Failed -> { emit(progress); return@collect }
                    is InstallProgress.Done -> completed += target.length()
                }
            }
        }
        emit(InstallProgress.Done(fileForEncoder(encoder)))
    }.flowOn(Dispatchers.IO)

    /** Everything already on device, so a settings screen can offer to free the space up again. */
    fun installedBytes(): Long = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun uninstall(backbone: BackboneModel): Boolean = fileFor(backbone).delete()

    fun install(backbone: BackboneModel): Flow<InstallProgress> =
        download(backbone.downloadUrl, fileFor(backbone), backbone.sizeBytes)

    fun install(codec: CodecModel): Flow<InstallProgress> =
        download(hfUrl(codec.repo, codec.file), fileForCodec(codec), codec.sizeBytes)

    private fun hfUrl(repo: String, file: String) =
        "https://huggingface.co/$repo/resolve/main/$file"

    private fun download(url: String, target: File, expectedBytes: Long): Flow<InstallProgress> = flow {
        if (expectedBytes > 0 && target.length() == expectedBytes) {
            emit(InstallProgress.Done(target)); return@flow
        }

        val partial = File(target.parentFile, target.name + ".part")
        val already = partial.length()
        val needed = expectedBytes - already
        if (usableBytes() < needed + SPACE_HEADROOM_BYTES) {
            emit(InstallProgress.Failed("Not enough free space - this needs about ${needed / MB} MB."))
            return@flow
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            // Continue rather than restart. A server that ignores this answers 200 and the whole
            // file, which is handled below by discarding what we had rather than appending to it.
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }

        try {
            connection.connect()
            val resuming = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (connection.responseCode !in 200..299) {
                emit(InstallProgress.Failed("Download failed (HTTP ${connection.responseCode}).")); return@flow
            }
            var done = if (resuming) already else 0L
            if (!resuming && already > 0) partial.delete()

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // Roughly every 2 MB: frequent enough that the bar visibly moves on a slow
                        // connection, rare enough not to flood the collector on a fast one.
                        if (done - lastEmit >= PROGRESS_STEP_BYTES) {
                            lastEmit = done
                            emit(InstallProgress.Downloading(done, expectedBytes))
                        }
                    }
                }
            }

            if (partial.length() != expectedBytes) {
                // Kept, not deleted: the next attempt resumes from here instead of starting over.
                emit(InstallProgress.Failed("Download was cut short - tap to resume.")); return@flow
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                emit(InstallProgress.Failed("Could not save the model.")); return@flow
            }
            emit(InstallProgress.Done(target))
        } catch (e: IOException) {
            emit(InstallProgress.Failed(e.message ?: "Network error."))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun usableBytes(): Long =
        StatFs(context.filesDir.absolutePath).let { it.availableBlocksLong * it.blockSizeLong }

    private companion object {
        const val MB = 1024L * 1024L
        const val PROGRESS_STEP_BYTES = 2L * MB
        // Android starts behaving badly well before a volume is genuinely full, and a model is not
        // worth being the thing that fills it.
        const val SPACE_HEADROOM_BYTES = 200L * MB
    }
}
