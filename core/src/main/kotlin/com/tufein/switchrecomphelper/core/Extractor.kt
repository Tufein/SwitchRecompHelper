package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.fs.Nca
import com.tufein.switchrecomphelper.core.fs.NcaSection
import com.tufein.switchrecomphelper.core.fs.Nso
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

class ExtractOptions(
    val exeFs: Boolean = true,
    val romFs: Boolean = true,
    /** Also write every ExeFS NSO uncompressed (LZ4 segments unpacked, hashes checked) to `exefs_uncompressed/`. */
    val uncompressedNso: Boolean = true,
    /** Write `recomp_info.toml` (title id, SHA-256 and build ids of the ExeFS files) next to `exefs/`. */
    val recompInfo: Boolean = true,
    /** Check the SHA-256 hash tree of RomFS and ExeFS while reading (IVFC / HierarchicalSha256). Slower; a corrupt dump then fails. */
    val verifyHashes: Boolean = true,
)

class TitleResult(
    val programId: Long,
    val folder: String,
    val exeFsFiles: Int,
    val romFsFiles: Int,
    /** Uncompressed NSO copies written to `exefs_uncompressed/`. */
    val uncompressedNsoFiles: Int = 0,
    /** The RomFS (and ExeFS, if the update has one) come from an update applied to the base game. */
    val updateApplied: Boolean = false,
)

class ExtractionResult(val titles: List<TitleResult>, val totalFiles: Int, val totalBytes: Long)

class SwitchExtractor(private val keys: KeySet) {

    private class Planned(
        val path: String,
        val data: RandomAccessSource,
        val group: Int,
        val sectionStart: Long,
        val offset: Long,
    )

    /** A Program NCA of the input. [group] is its position in the input, which orders the reads. */
    private class ProgramFile(val group: Int, val name: String, val nca: Nca) {
        val programId: Long get() = nca.programId
    }

    /** A title as the user sees it: a base program, plus the update that applies to it if the input has one. */
    private class Title(val base: ProgramFile, val update: ProgramFile?)

    /**
     * Extracts ExeFS and RomFS of every Program NCA/NCZ in an XCI (secure and update partitions), NSP or NSZ.
     * Update NCAs are applied to their base game when the base is in the same input.
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
        val containers = openContainers(xciSource, listener)
        val effectiveKeys = withTicketKeys(containers.first(), listener)

        val programs = ArrayList<ProgramFile>()
        var group = 0
        for (container in containers) {
            for (entry in container.entries) {
                val compressed = entry.name.endsWith(".ncz")
                if (!compressed && !entry.name.endsWith(".nca")) continue
                if (listener.cancelled) throw ExtractionCancelledException()
                val nca = Nca(container.open(entry), effectiveKeys, compressed)
                if (nca.contentType == Nca.TYPE_PROGRAM) programs.add(ProgramFile(group, entry.name, nca))
                group++
            }
        }
        val titles = titlesOf(programs, listener)

        val planned = ArrayList<Planned>()
        val titleResults = ArrayList<TitleResult>()
        // (path, toml) per title; built after the pipeline because the hashes are only known then.
        val manifestBuilders = ArrayList<() -> Pair<String, String>>()
        val usedFolders = HashSet<String>()

        for (title in titles) {
            if (listener.cancelled) throw ExtractionCancelledException()
            val base = title.base
            val update = title.update
            val programId = base.programId

            listener.status("Program ${base.name}")
            var folder = "%016x".format(programId)
            if (!usedFolders.add(folder)) {
                folder += "_" + base.name.substringBeforeLast('.')
                usedFolders.add(folder)
            }
            if (update != null) listener.status("Applying update ${update.name}")
            var exeCount = 0
            var romCount = 0
            var nsoCount = 0
            val exeInfos = ArrayList<ExeFsFileInfo>()

            if (options.exeFs) {
                // An update with its own ExeFS replaces the base game's executables.
                val exe = findExeFs(update, base, options, listener)
                if (exe != null) {
                    val (exeFile, exeSection, exeFs) = exe
                    for (f in exeFs.entries) {
                        val name = safeName(f.name)
                        val src = exeFs.open(f)
                        val info = ExeFsFileInfo(name, f.size).also { exeInfos.add(it) }
                        planned.add(Planned("$folder/exefs/$name", DigestingSource(src) { info.sha256 = it }, exeFile.group, exeSection.start, f.offset))
                        exeCount++
                        if (!options.uncompressedNso || !Nso.isNso(src)) continue
                        val header = try {
                            Nso.parseHeader(src, "ExeFS '$name'")
                        } catch (e: SwitchFormatException) {
                            listener.status("Not decompressing $name: ${e.message}")
                            continue
                        }
                        val path = "exefs_uncompressed/$name"
                        val nso = NsoInfo(header.buildIdHex, path, header.uncompressedFileSize)
                        info.nso = nso
                        // Same sort key as the original: read right after it, while that part of the dump is still warm.
                        planned.add(Planned("$folder/$path", UncompressedNsoSource(src, header, "ExeFS '$name'", nso), exeFile.group, exeSection.start, f.offset))
                        nsoCount++
                    }
                }
            }

            var romFromUpdate = false
            if (options.romFs) {
                val rom = romFsOf(title, options, listener)
                if (rom != null) {
                    val (romFile, image) = rom
                    romFromUpdate = romFile === update
                    for (f in RomFs.list(image.source)) {
                        planned.add(Planned("$folder/romfs/${safePath(f.path)}", image.source.slice(f.offset, f.size), romFile.group, image.sectionStart, f.offset))
                        romCount++
                    }
                }
            }

            titleResults.add(TitleResult(programId, folder, exeCount, romCount, nsoCount, updateApplied = romFromUpdate))
            if (options.recompInfo && exeInfos.isNotEmpty()) {
                val source = base.name
                manifestBuilders.add { "$folder/${RecompInfo.FILE_NAME}" to RecompInfo.toml(programId, source, exeInfos) }
            }
        }
        if (titleResults.isEmpty()) {
            throw SwitchFormatException("No base game (Program NCA) found. An update needs its base game in the same file (XCI, NSP or NSZ).")
        }
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
        val manifests = manifestBuilders.map { it() }
        for ((path, text) in manifests) {
            listener.status("Writing $path")
            sink.create(path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        return ExtractionResult(titleResults, planned.size + manifests.size, total)
    }

    /**
     * Pairs every update with its base game. Titles are the base programs (and DLC, which are programs too) in
     * input order; an update without a base in the same input is reported and left out.
     */
    private fun titlesOf(programs: List<ProgramFile>, listener: ProgressListener): List<Title> {
        val updates = HashMap<Long, ProgramFile>()
        val bases = programs.filter { !isUpdateId(it.programId) }
        val baseIds = bases.map { it.programId }.toSet()
        for (p in programs.filter { isUpdateId(it.programId) }) {
            val baseId = p.programId and UPDATE_BIT.inv()
            if (baseId !in baseIds) {
                listener.status("Skipping update ${p.name}: its base game is not in this file")
                continue
            }
            val known = updates[baseId]
            if (known == null) {
                updates[baseId] = p
            } else {
                listener.status("Two updates for ${"%016x".format(baseId)} (${known.name}, ${p.name}); using ${known.name}")
            }
        }
        return bases.map { Title(it, updates[it.programId]) }
    }

    /** The ExeFS of the update if it has one with executables, otherwise of the base game. */
    private fun findExeFs(
        update: ProgramFile?,
        base: ProgramFile,
        options: ExtractOptions,
        listener: ProgressListener,
    ): Triple<ProgramFile, NcaSection, PartitionFs>? {
        for (file in listOfNotNull(update, base)) {
            for (section in file.nca.sections) {
                if (!section.isPfs0) continue
                val pfs = try {
                    PartitionFs.parsePfs0(file.nca.openFilesystem(section, options.verifyHashes))
                } catch (e: UnsupportedSectionException) {
                    listener.status("Skipping section ${section.index} of ${file.name}: ${e.message}")
                    continue
                }
                // The logo section is also a PFS0; ExeFS is the one with the executable.
                if (pfs.find("main") != null || pfs.find("main.npdm") != null) return Triple(file, section, pfs)
            }
        }
        return null
    }

    /**
     * The RomFS of a title: the update's RomFS applied to the base game when the update is a patch (BKTR), the
     * update's own RomFS if it is a full one, otherwise the base game's.
     */
    private fun romFsOf(title: Title, options: ExtractOptions, listener: ProgressListener): Pair<ProgramFile, RomFsImage>? {
        val update = title.update
        if (update != null) {
            val section = update.nca.sections.firstOrNull { it.isRomFs }
            if (section != null) {
                try {
                    if (section.isPatch) {
                        val baseSection = title.base.nca.sections.firstOrNull { it.isRomFs }
                            ?: throw UnsupportedSectionException("the base game has no RomFS to apply the update to")
                        val baseData = title.base.nca.openSection(baseSection)
                        return update to RomFsImage(update.nca.openPatchedFilesystem(section, baseData, options.verifyHashes), section.start)
                    }
                    return update to RomFsImage(update.nca.openFilesystem(section, options.verifyHashes), section.start)
                } catch (e: UnsupportedSectionException) {
                    listener.status("Skipping RomFS of update ${update.name}: ${e.message}")
                    return null
                }
            }
        }
        val section = title.base.nca.sections.firstOrNull { it.isRomFs } ?: return null
        return try {
            title.base to RomFsImage(title.base.nca.openFilesystem(section, options.verifyHashes), section.start)
        } catch (e: UnsupportedSectionException) {
            listener.status("Skipping RomFS: ${e.message}")
            null
        }
    }

    private class RomFsImage(val source: RandomAccessSource, val sectionStart: Long)

    /** Every container of the input: the whole file for NSP/NSZ; the secure and update partitions for XCI/XCZ. */
    private fun openContainers(source: RandomAccessSource, listener: ProgressListener): List<PartitionFs> {
        val magic = String(source.readBytes(0, 4), Charsets.US_ASCII)
        if (magic == "PFS0") return listOf(PartitionFs.parsePfs0(source)) // NSP / NSZ
        val xci = Xci(source) // also XCZ: same layout, secure partition holds .ncz
        val secure = xci.partition("secure")
            ?: throw SwitchFormatException("No 'secure' partition found (partitions: ${xci.partitionNames.joinToString()})")
        val containers = arrayListOf(secure)
        // The update partition is optional: a card without one is normal.
        val update = try {
            xci.partition("update")
        } catch (e: SwitchFormatException) {
            listener.status("The update partition could not be read, updates are not applied: ${e.message}")
            null
        }
        if (update != null) containers.add(update)
        return containers
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

        /** Update titles are their base game's id with this bit set (the DLC range is 0x1000 and up). */
        private const val UPDATE_BIT = 0x800L

        fun isUpdateId(programId: Long): Boolean = (programId and UPDATE_BIT) != 0L && (programId and 0x1000L) == 0L

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
