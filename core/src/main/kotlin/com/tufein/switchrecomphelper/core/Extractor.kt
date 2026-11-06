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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where extracted files go. Paths are relative, '/'-separated and already validated. */
interface OutputSink {
    fun create(relativePath: String): OutputStream
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
        var done = 0L
        listener.progress(0, total)
        Copier().use { copier ->
            // Opening the next output file (slow on Android's SAF) overlaps with copying the current one.
            var next: CompletableFuture<OutputStream>? = planned.firstOrNull()?.let { openAsync(copier.opener, sink, it.path) }
            try {
                for ((i, p) in planned.withIndex()) {
                    listener.status("(${i + 1}/${planned.size}) ${p.path}")
                    val current = next!!
                    next = planned.getOrNull(i + 1)?.let { openAsync(copier.opener, sink, it.path) }
                    val base = done
                    await(current).use { out ->
                        copier.copy(p.data, out, { listener.cancelled }) { written -> listener.progress(base + written, total) }
                    }
                    done += p.data.size
                    listener.progress(done, total)
                }
                next = null
            } finally {
                // On failure/cancel: don't leak the stream opened ahead of time.
                next?.let { f -> runCatching { await(f).close() } }
            }
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

    /** Unwraps the real error (e.g. IOException) from a failed background open. */
    private fun await(f: CompletableFuture<OutputStream>): OutputStream =
        try {
            f.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    private fun openAsync(executor: ExecutorService, sink: OutputSink, path: String): CompletableFuture<OutputStream> =
        CompletableFuture.supplyAsync({ sink.create(path) }, executor)

    /**
     * Copies a source to a stream with reading/decrypting and writing on separate threads, so slow storage
     * (SAF writes) and AES work overlap instead of alternating.
     */
    private class Copier : AutoCloseable {
        private val writer: ExecutorService = Executors.newSingleThreadExecutor(daemon("xci-writer"))
        val opener: ExecutorService = Executors.newSingleThreadExecutor(daemon("xci-opener"))
        private val free = ArrayBlockingQueue<ByteArray>(DEPTH).also { q -> repeat(DEPTH) { q.add(ByteArray(BUFFER)) } }

        private class Chunk(val buf: ByteArray, val len: Int)

        fun copy(src: RandomAccessSource, out: OutputStream, cancelled: () -> Boolean, onWritten: (Long) -> Unit) {
            val size = src.size
            if (size == 0L) return
            val full = ArrayBlockingQueue<Chunk>(DEPTH + 1)
            val written = AtomicLong()
            val error = AtomicReference<Throwable?>()
            val eof = Chunk(ByteArray(0), -1)
            val job = writer.submit {
                while (true) {
                    val c = full.take()
                    if (c === eof) break
                    // After a write failure keep draining so the reader never blocks.
                    if (error.get() == null) {
                        try {
                            out.write(c.buf, 0, c.len)
                            written.addAndGet(c.len.toLong())
                        } catch (t: Throwable) {
                            error.set(t)
                        }
                    }
                    free.put(c.buf)
                }
            }
            try {
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
                    full.put(Chunk(buf, n))
                    pos += n
                    onWritten(written.get())
                }
            } finally {
                full.put(eof)
                job.get()
            }
            error.get()?.let { throw it }
            onWritten(written.get())
        }

        override fun close() {
            writer.shutdownNow()
            opener.shutdownNow()
        }

        private companion object {
            const val BUFFER = 4 shl 20
            const val DEPTH = 3
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
