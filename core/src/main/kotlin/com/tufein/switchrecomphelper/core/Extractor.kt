package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.fs.Nca
import com.tufein.switchrecomphelper.core.fs.Ticket
import com.tufein.switchrecomphelper.core.fs.PartitionFs
import com.tufein.switchrecomphelper.core.fs.RomFs
import com.tufein.switchrecomphelper.core.fs.Xci
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.readFully
import com.tufein.switchrecomphelper.core.io.slice
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where extracted files go. Paths are relative, '/'-separated and already validated. */
interface OutputSink {
    fun create(relativePath: String): OutputStream

    /**
     * How many [create] calls may run at the same time. Keep 1 unless [create] is thread-safe;
     * a higher value lets slow file creation (Android's SAF) overlap.
     */
    val parallelCreates: Int get() = 1
}

interface ProgressListener {
    fun status(message: String) {}
    fun progress(doneBytes: Long, totalBytes: Long) {}
    val cancelled: Boolean get() = false
}

class ExtractOptions(val exeFs: Boolean = true, val romFs: Boolean = true)

class TitleResult(val programId: Long, val folder: String, val exeFsFiles: Int, val romFsFiles: Int)

class ExtractionResult(val titles: List<TitleResult>, val totalFiles: Int, val totalBytes: Long)

class SwitchExtractor(private val keys: KeySet) {

    private class Planned(
        val path: String,
        val data: RandomAccessSource,
        val group: Int,
        val sectionStart: Long,
        val offset: Long,
    )

    /**
     * Extracts ExeFS and RomFS of every Program NCA/NCZ in an XCI (secure partition), NSP or NSZ.
     * The container type is detected from the file contents, not the extension.
     */
    fun extract(
        xciSource: RandomAccessSource,
        sink: OutputSink,
        options: ExtractOptions = ExtractOptions(),
        listener: ProgressListener = object : ProgressListener {},
    ): ExtractionResult {
        if (!options.exeFs && !options.romFs) throw SwitchFormatException("Select ExeFS and/or RomFS")
        if (!keys.hasHeaderKey) throw SwitchFormatException("prod.keys has no usable header_key")

        listener.status("Reading file")
        val container = openContainer(xciSource)
        val effectiveKeys = withTicketKeys(container, listener)

        val planned = ArrayList<Planned>()
        val titles = ArrayList<TitleResult>()
        val usedFolders = HashSet<String>()

        for ((group, entry) in container.entries.withIndex()) {
            val compressed = entry.name.endsWith(".ncz")
            if (!compressed && !entry.name.endsWith(".nca")) continue
            if (listener.cancelled) throw ExtractionCancelledException()
            val nca = Nca(container.open(entry), effectiveKeys, compressed)
            if (nca.contentType != Nca.TYPE_PROGRAM) continue

            listener.status("Program ${entry.name}")
            var folder = "%016x".format(nca.programId)
            if (!usedFolders.add(folder)) {
                folder += "_" + entry.name.substringBeforeLast('.')
                usedFolders.add(folder)
            }
            var exeCount = 0
            var romCount = 0
            for (section in nca.sections) {
                if (section.isPfs0 && options.exeFs && exeCount == 0) {
                    val pfs = try {
                        PartitionFs.parsePfs0(nca.openFilesystem(section))
                    } catch (e: UnsupportedSectionException) {
                        listener.status("Skipping section ${section.index}: ${e.message}")
                        continue
                    }
                    // The logo section is also a PFS0; ExeFS is the one with the executable.
                    if (pfs.find("main") == null && pfs.find("main.npdm") == null) continue
                    for (f in pfs.entries) {
                        planned.add(Planned("$folder/exefs/${safeName(f.name)}", pfs.open(f), group, section.start, f.offset))
                        exeCount++
                    }
                } else if (section.isRomFs && options.romFs && romCount == 0) {
                    val image = try {
                        nca.openFilesystem(section)
                    } catch (e: UnsupportedSectionException) {
                        listener.status("Skipping RomFS: ${e.message}")
                        continue
                    }
                    for (f in RomFs.list(image)) {
                        planned.add(Planned("$folder/romfs/${safePath(f.path)}", image.slice(f.offset, f.size), group, section.start, f.offset))
                        romCount++
                    }
                }
            }
            titles.add(TitleResult(nca.programId, folder, exeCount, romCount))
        }
        if (titles.isEmpty()) throw SwitchFormatException("No Program NCA found (is this a base game dump? update/DLC-only files are not supported)")
        // Read in on-disk order: required for single-stream NCZ (forward-only), and kinder to storage anyway.
        planned.sortWith(compareBy<Planned>({ it.group }, { it.sectionStart }, { it.offset }))

        val total = planned.sumOf { it.data.size }
        listener.progress(0, total)
        Pipeline(sink, planned.map { it.path }).use { pipeline ->
            pipeline.run(
                sources = planned.map { it.data },
                cancelled = { listener.cancelled },
                onFile = { i -> listener.status("(${i + 1}/${planned.size}) ${planned[i].path}") },
                onProgress = { written -> listener.progress(written, total) },
            )
        }
        return ExtractionResult(titles, planned.size, total)
    }

    private fun openContainer(source: RandomAccessSource): PartitionFs {
        val magic = String(source.readBytes(0, 4), Charsets.US_ASCII)
        if (magic == "PFS0") return PartitionFs.parsePfs0(source) // NSP / NSZ
        val xci = Xci(source) // also XCZ: same layout, secure partition holds .ncz
        return xci.partition("secure")
            ?: throw SwitchFormatException("No 'secure' partition found (partitions: ${xci.partitionNames.joinToString()})")
    }

    /** NSP/NSZ normally use title-key crypto: pick the title keys up from the tickets in the package. */
    private fun withTicketKeys(container: PartitionFs, listener: ProgressListener): KeySet {
        val extra = HashMap<String, ByteArray>()
        for (e in container.entries) {
            if (!e.name.endsWith(".tik")) continue
            if (e.size > MAX_TICKET) continue
            try {
                val t = Ticket.parse(container.open(e).readBytes(0, e.size.toInt()))
                extra[t.rightsId.toHex()] = t.encryptedTitleKey
            } catch (ex: SwitchFormatException) {
                listener.status("Ignoring ticket ${e.name}: ${ex.message}")
            }
        }
        return if (extra.isEmpty()) keys else keys.withTitleKeys(extra)
    }

    /**
     * Copies every planned file through one pipeline that spans file boundaries:
     * - the calling thread reads (decrypts, decompresses) in on-disk order,
     * - a writer thread writes and closes the output files,
     * - opener threads create the output files ahead of time.
     * Creating and closing a file is the slow part on Android's SAF, and a RomFS often has thousands of small
     * files, so reading the next file no longer waits until the previous one is created, written and closed.
     */
    private class Pipeline(private val sink: OutputSink, private val paths: List<String>) : AutoCloseable {
        private val openers: ExecutorService =
            Executors.newFixedThreadPool(sink.parallelCreates.coerceIn(1, MAX_OPENERS), daemon("xci-opener"))
        private val writer: ExecutorService = Executors.newSingleThreadExecutor(daemon("xci-writer"))
        private val free = ArrayBlockingQueue<ByteArray>(BUFFERS).also { q -> repeat(BUFFERS) { q.add(ByteArray(BUFFER)) } }
        private val queue = LinkedBlockingQueue<Item>(BUFFERS * 4)

        // Filled by the reading thread before any item for that file is queued (the queue hands it over safely).
        private val outputs = arrayOfNulls<CompletableFuture<OutputStream>>(paths.size)
        private var scheduled = 0

        // Owned by the writer thread; read by the reading thread only after the writer has finished.
        private val closed = BooleanArray(paths.size)

        private val written = AtomicLong()
        private val error = AtomicReference<Throwable?>()
        @Volatile private var aborted = false

        private sealed class Item
        private class Data(val file: Int, val buf: ByteArray, val len: Int) : Item()
        private class End(val file: Int) : Item()
        private object Stop : Item()

        fun run(
            sources: List<RandomAccessSource>,
            cancelled: () -> Boolean,
            onFile: (Int) -> Unit,
            onProgress: (Long) -> Unit,
        ) {
            val job = writer.submit(::writeLoop)
            var ok = false
            try {
                for ((i, src) in sources.withIndex()) {
                    scheduleOpens(minOf(paths.size, i + 1 + LOOKAHEAD))
                    onFile(i)
                    val size = src.size
                    var pos = 0L
                    while (pos < size) {
                        if (cancelled()) throw ExtractionCancelledException()
                        error.get()?.let { throw it }
                        val buf = free.take()
                        val n = minOf(BUFFER.toLong(), size - pos).toInt()
                        try {
                            src.readFully(pos, buf, 0, n)
                        } catch (t: Throwable) {
                            free.put(buf)
                            throw t
                        }
                        queue.put(Data(i, buf, n))
                        pos += n
                        onProgress(written.get())
                    }
                    queue.put(End(i))
                }
                ok = true
            } finally {
                if (!ok) aborted = true // the writer skips what is still queued
                queue.put(Stop)
                job.get()
                if (!ok || error.get() != null) closeLeftovers()
            }
            error.get()?.let { throw it }
            onProgress(written.get())
        }

        private fun scheduleOpens(upTo: Int) {
            while (scheduled < upTo) {
                val path = paths[scheduled]
                outputs[scheduled] = CompletableFuture.supplyAsync({ sink.create(path) }, openers)
                scheduled++
            }
        }

        private fun writeLoop() {
            while (true) {
                when (val item = queue.take()) {
                    Stop -> return
                    is Data -> {
                        if (!aborted && error.get() == null) {
                            try {
                                await(outputs[item.file]!!).write(item.buf, 0, item.len)
                                written.addAndGet(item.len.toLong())
                            } catch (t: Throwable) {
                                error.compareAndSet(null, t)
                            }
                        }
                        // After a failure keep draining so the reading thread never blocks.
                        free.put(item.buf)
                    }
                    is End -> if (!aborted && error.get() == null) {
                        closed[item.file] = true
                        try {
                            await(outputs[item.file]!!).close()
                        } catch (t: Throwable) {
                            error.compareAndSet(null, t)
                        }
                    }
                }
            }
        }

        /** On failure/cancel: don't leak streams that were opened ahead of time or left half-written. */
        private fun closeLeftovers() {
            for (i in 0 until scheduled) {
                if (!closed[i]) runCatching { await(outputs[i]!!).close() }
            }
        }

        override fun close() {
            writer.shutdownNow()
            openers.shutdownNow()
        }

        /** Unwraps the real error (e.g. IOException) from a failed background open. */
        private fun await(f: CompletableFuture<OutputStream>): OutputStream =
            try {
                f.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }

        private companion object {
            const val BUFFER = 1 shl 20
            const val BUFFERS = 16
            const val LOOKAHEAD = BUFFERS
            const val MAX_OPENERS = 8
            fun daemon(name: String) = java.util.concurrent.ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
        }
    }

    companion object {
        private const val MAX_TICKET = 0x10000L

        /** Names come from an untrusted dump: refuse anything that could escape the output directory. */
        fun safeName(name: String): String {
            if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
                throw SwitchFormatException("Refusing unsafe file name '${name.filter { it >= ' ' }}' in dump")
            }
            return name
        }

        fun safePath(path: String): String = path.split('/').joinToString("/") { safeName(it) }
    }
}
