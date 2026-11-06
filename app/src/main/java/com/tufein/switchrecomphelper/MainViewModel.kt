package com.tufein.switchrecomphelper

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.switchrecomphelper.core.ExtractOptions
import com.tufein.switchrecomphelper.core.ExtractionCancelledException
import com.tufein.switchrecomphelper.core.KeySet
import com.tufein.switchrecomphelper.core.ProgressListener
import com.tufein.switchrecomphelper.core.SwitchExtractor
import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.FileChannelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

data class UiState(
    val keysText: String? = null, // null = none loaded
    val xciUri: Uri? = null,
    val xciName: String? = null,
    val outUri: Uri? = null,
    val outName: String? = null,
    val running: Boolean = false,
    val progress: Int = 0, // 0..1000
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long = -1, // -1 = unknown yet
    val currentFile: String = "",
    val message: String = "",
    val isError: Boolean = false,
    val log: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val keysFile = File(app.filesDir, "prod.keys")
    private var keys: KeySet? = null
    private var job: Job? = null

    @Volatile private var cancelRequested = false
    @Volatile private var pendingFile = ""

    init {
        // Keys imported earlier are kept in app-private storage.
        if (keysFile.exists()) {
            runCatching { KeySet.parse(keysFile.readText()) }.getOrNull()?.takeIf { it.hasHeaderKey }?.let {
                keys = it
                _state.update { s -> s.copy(keysText = getString(R.string.keys_loaded, it.size)) }
            }
        }
    }

    private fun getString(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    fun importKeys(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val text = resolver.openInputStream(uri)?.use { input ->
                    // InputStream.readNBytes(int) needs API 33; read with an explicit limit instead.
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        if (out.size() > MAX_KEYS_BYTES) throw SwitchFormatException("File too large for a key file")
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                } ?: throw SwitchFormatException("Cannot open file")
                val parsed = KeySet.parse(text)
                if (!parsed.hasHeaderKey) {
                    _state.update { it.copy(message = getString(R.string.keys_invalid), isError = true) }
                    return@launch
                }
                keysFile.writeText(text)
                keys = parsed
                _state.update { it.copy(keysText = getString(R.string.keys_loaded, parsed.size), message = "", isError = false) }
            } catch (e: Exception) {
                _state.update { it.copy(message = getString(R.string.keys_error, e.message ?: e.javaClass.simpleName), isError = true) }
            }
        }
    }

    fun setXci(uri: Uri) {
        _state.update { it.copy(xciUri = uri, xciName = displayName(uri)) }
    }

    fun setOut(uri: Uri) {
        _state.update { it.copy(outUri = uri, outName = uri.lastPathSegment ?: uri.toString()) }
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun cancel() {
        cancelRequested = true
    }

    fun start(exeFs: Boolean, romFs: Boolean) {
        val s = _state.value
        val k = keys
        val xci = s.xciUri
        val out = s.outUri
        if (k == null || xci == null || out == null) {
            _state.update { it.copy(message = getString(R.string.need_all), isError = true) }
            return
        }
        if (!exeFs && !romFs) {
            _state.update { it.copy(message = getString(R.string.need_part), isError = true) }
            return
        }
        if (s.running) return
        cancelRequested = false
        _state.update {
            it.copy(running = true, progress = 0, speedBytesPerSec = 0, etaSeconds = -1, currentFile = "", isError = false, message = "", log = "")
        }

        job = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val startNanos = System.nanoTime()
            var lastUi = 0L
            var lastBytes = 0L
            var smoothed = 0.0
            val logBuf = StringBuilder()
            val listener = object : ProgressListener {
                override val cancelled: Boolean get() = cancelRequested

                override fun status(message: String) {
                    // Called once per file (thousands for RomFS): only append here, publish on the progress tick.
                    logBuf.append(message).append('\n')
                    if (logBuf.length > MAX_LOG_CHARS) logBuf.delete(0, logBuf.length - MAX_LOG_CHARS)
                    pendingFile = message
                }

                override fun progress(doneBytes: Long, totalBytes: Long) {
                    val now = System.nanoTime()
                    if (now - lastUi < UI_INTERVAL_NANOS && doneBytes != totalBytes) return
                    val dt = (now - maxOf(lastUi, startNanos)) / 1e9
                    if (dt > 0) {
                        val instant = (doneBytes - lastBytes) / dt
                        smoothed = if (smoothed == 0.0) instant else 0.7 * smoothed + 0.3 * instant
                    }
                    lastUi = now
                    lastBytes = doneBytes
                    val p = if (totalBytes > 0) (doneBytes * 1000 / totalBytes).toInt() else 0
                    val eta = if (smoothed > 1.0 && totalBytes > doneBytes) ((totalBytes - doneBytes) / smoothed).toLong() else -1L
                    val log = logBuf.toString()
                    _state.update {
                        it.copy(progress = p, speedBytesPerSec = smoothed.toLong(), etaSeconds = eta, currentFile = pendingFile, log = log)
                    }
                }
            }
            try {
                val folder = (s.xciName ?: "xci").substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_") + "_extracted"
                val pfd = resolver.openFileDescriptor(xci, "r") ?: throw SwitchFormatException("Cannot open the XCI file")
                pfd.use {
                    val stream = FileInputStream(it.fileDescriptor)
                    FileChannelSource(stream.channel, stream).use { source ->
                        val sink = SafSink(resolver, out, folder)
                        val result = SwitchExtractor(k).extract(source, sink, ExtractOptions(exeFs, romFs), listener)
                        val titleLines = result.titles.joinToString("\n") {
                            getString(R.string.title_line, it.folder, it.exeFsFiles, it.romFsFiles)
                        }
                        _state.update { st ->
                            st.copy(
                                running = false, progress = 1000, isError = false, currentFile = "",
                                message = getString(R.string.done, result.totalFiles, Formatter.formatFileSize(app, result.totalBytes)),
                                log = logBuf.toString() + titleLines + "\n",
                            )
                        }
                    }
                }
            } catch (e: ExtractionCancelledException) {
                _state.update { it.copy(running = false, isError = false, currentFile = "", message = getString(R.string.cancelled), log = logBuf.toString()) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        running = false, isError = true, currentFile = "",
                        message = getString(R.string.failed, e.message ?: e.javaClass.simpleName), log = logBuf.toString(),
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_KEYS_BYTES = 4 * 1024 * 1024
        const val MAX_LOG_CHARS = 20_000
        const val UI_INTERVAL_NANOS = 250_000_000L
    }
}
